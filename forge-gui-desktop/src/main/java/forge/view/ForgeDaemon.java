package forge.view;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

/**
 * Forge Daemon Mode
 *
 * Keeps the JVM running with cards loaded in memory.
 * Accepts game requests via TCP socket, runs games in threads.
 *
 * Memory Architecture:
 * - Card database loaded ONCE at startup (~500MB)
 * - Each game thread uses ~20MB for game state
 * - All threads share the read-only card database
 *
 * Protocol:
 * - Connect to TCP port (default 17171)
 * - Send: NEWGAME deck1.dck deck2.dck [options]
 * - Receive: Game output (same as interactive mode)
 * - Send: Decision responses
 * - Connection closes when game ends
 */
public class ForgeDaemon {

    private static final int DEFAULT_PORT = 17171;
    private static final int MAX_CONCURRENT_GAMES = 10;

    private final int port;
    private final ExecutorService gameExecutor;
    private final AtomicInteger activeGames = new AtomicInteger(0);
    private final AtomicInteger totalGamesPlayed = new AtomicInteger(0);
    private volatile boolean running = true;

    public ForgeDaemon(int port) {
        this.port = port;
        this.gameExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_GAMES);
    }

    public void start() {
        System.out.println("=".repeat(60));
        System.out.println("FORGE DAEMON MODE");
        System.out.println("=".repeat(60));

        // Initialize card database ONCE
        long initStart = System.currentTimeMillis();
        System.out.println("Loading card database...");
        try {
            FModel.initialize(null, null);
        } catch (Throwable t) {
            System.err.println("Failed to initialize FModel:");
            t.printStackTrace();
            return;
        }
        long initTime = System.currentTimeMillis() - initStart;
        System.out.println("Card database loaded in " + initTime + "ms");
        System.out.println("Memory: " + getMemoryUsage());
        System.out.println();

        // Start TCP server
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Daemon listening on port " + port);
            System.out.println("Max concurrent games: " + MAX_CONCURRENT_GAMES);
            System.out.println();
            System.out.println("To connect: nc localhost " + port);
            System.out.println("Commands:");
            System.out.println("  NEWGAME deck1.dck deck2.dck [-i] [-q] [-c timeout] [-s seed]");
            System.out.println("  STATUS");
            System.out.println("  SHUTDOWN");
            System.out.println("=".repeat(60));

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    handleClient(clientSocket);
                } catch (SocketException e) {
                    if (running) {
                        System.err.println("Socket error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to start daemon: " + e.getMessage());
        }

        shutdown();
    }

    private void handleClient(Socket socket) {
        gameExecutor.submit(() -> {
            try (
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
            ) {
                String command = in.readLine();
                if (command == null) return;

                command = command.trim();

                if (command.startsWith("NEWGAME")) {
                    handleNewGame(command, in, out);
                } else if (command.equals("STATUS")) {
                    handleStatus(out);
                } else if (command.equals("SHUTDOWN")) {
                    out.println("Shutting down daemon...");
                    running = false;
                } else {
                    out.println("ERROR: Unknown command. Use NEWGAME, STATUS, or SHUTDOWN");
                }
            } catch (IOException e) {
                System.err.println("Client error: " + e.getMessage());
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        });
    }

    private void handleNewGame(String command, BufferedReader in, PrintWriter out) {
        // Parse command: NEWGAME deck1.dck deck2.dck [-i] [-o] [-q] [-c timeout] [-s seed]
        // -i = interactive (external agent controls)
        // -o = observation (AI plays, decisions logged for training)
        String[] parts = command.split("\\s+");
        if (parts.length < 3) {
            out.println("ERROR: Usage: NEWGAME deck1.dck deck2.dck [-i] [-o] [-q] [-c timeout] [-s seed]");
            return;
        }

        String deck1 = parts[1];
        String deck2 = parts[2];
        boolean interactive = false;
        boolean observe = false;
        boolean quiet = false;
        int timeout = 120;
        Long seed = null;

        for (int i = 3; i < parts.length; i++) {
            if (parts[i].equals("-i")) {
                interactive = true;
            } else if (parts[i].equals("-o")) {
                observe = true;
            } else if (parts[i].equals("-q")) {
                quiet = true;
            } else if (parts[i].equals("-c") && i + 1 < parts.length) {
                timeout = Integer.parseInt(parts[++i]);
            } else if (parts[i].equals("-s") && i + 1 < parts.length) {
                seed = Long.parseLong(parts[++i]);
            }
        }

        activeGames.incrementAndGet();
        try {
            runGame(deck1, deck2, interactive, observe, quiet, timeout, seed, in, out);
        } finally {
            activeGames.decrementAndGet();
            totalGamesPlayed.incrementAndGet();
        }
    }

    private void runGame(
        String deck1Name,
        String deck2Name,
        boolean interactive,
        boolean observe,
        boolean quiet,
        int timeout,
        Long seed,
        BufferedReader in,
        PrintWriter out
    ) {
        // Load decks
        Deck d1 = loadDeck(deck1Name);
        Deck d2 = loadDeck(deck2Name);

        if (d1 == null) {
            out.println("ERROR: Could not load deck: " + deck1Name);
            return;
        }
        if (d2 == null) {
            out.println("ERROR: Could not load deck: " + deck2Name);
            return;
        }

        // Set seed for deterministic replay if provided
        if (seed != null) {
            MyRandom.setRandom(new Random(seed));
            if (!quiet) {
                out.println("SEED: " + seed);
            }
        }

        // Set up game
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setSimTimeout(timeout);

        List<RegisteredPlayer> players = new ArrayList<>();

        // Determine player type:
        // -i = interactive (external agent controls)
        // -o = observe (AI plays, decisions logged)
        // neither = pure AI (no output)
        String prefix = interactive ? "Agent" : (observe ? "AiObs" : "Ai");

        RegisteredPlayer rp1 = new RegisteredPlayer(d1);
        if (interactive) {
            rp1.setPlayer(new LobbyPlayerDaemon(prefix + "(1)-" + d1.getName(), in, out));
        } else if (observe) {
            rp1.setPlayer(new LobbyPlayerAiObserver(prefix + "(1)-" + d1.getName(), in, out));
        } else {
            rp1.setPlayer(GamePlayerUtil.createAiPlayer(prefix + "(1)-" + d1.getName(), 0));
        }
        players.add(rp1);

        RegisteredPlayer rp2 = new RegisteredPlayer(d2);
        if (interactive) {
            rp2.setPlayer(new LobbyPlayerDaemon(prefix + "(2)-" + d2.getName(), in, out));
        } else if (observe) {
            rp2.setPlayer(new LobbyPlayerAiObserver(prefix + "(2)-" + d2.getName(), in, out));
        } else {
            rp2.setPlayer(GamePlayerUtil.createAiPlayer(prefix + "(2)-" + d2.getName(), 1));
        }
        players.add(rp2);

        if (!quiet) {
            out.println("GAME_START: " + d1.getName() + " vs " + d2.getName());
        }

        Match match = new Match(rules, players, "DaemonGame");
        Game game = match.createGame();

        long startTime = System.currentTimeMillis();

        try {
            // Run game with timeout
            CompletableFuture<Void> gameFuture = CompletableFuture.runAsync(() -> {
                match.startGame(game);
            });

            gameFuture.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            out.println("GAME_TIMEOUT: Game exceeded " + timeout + " seconds");
            if (!game.isGameOver()) {
                game.setGameOver(GameEndReason.Draw);
            }
        } catch (Exception e) {
            out.println("GAME_ERROR: " + e.getMessage());
            if (!game.isGameOver()) {
                game.setGameOver(GameEndReason.Draw);
            }
        }

        long gameTime = System.currentTimeMillis() - startTime;

        // Output result
        if (game.getOutcome().isDraw()) {
            out.println("GAME_RESULT: Draw in " + gameTime + "ms");
        } else {
            String winner = game.getOutcome().getWinningLobbyPlayer().getName();
            out.println("GAME_RESULT: " + winner + " won in " + gameTime + "ms");
        }
    }

    private Deck loadDeck(String deckName) {
        // Try as absolute path first
        File f = new File(deckName);
        if (f.exists() && f.isFile()) {
            System.out.println("Loading deck from absolute path: " + deckName);
            return DeckSerializer.fromFile(f);
        }

        // Try in user's constructed deck directory
        if (deckName.endsWith(".dck")) {
            f = new File(ForgeConstants.DECK_CONSTRUCTED_DIR + deckName);
            if (f.exists()) {
                System.out.println("Loading deck from user dir: " + f.getAbsolutePath());
                return DeckSerializer.fromFile(f);
            }
        }

        // Try in resource directory (res/decks/constructed)
        String resDir = ForgeConstants.RES_DIR + "decks" + File.separator + "constructed" + File.separator;
        if (deckName.endsWith(".dck")) {
            f = new File(resDir + deckName);
            if (f.exists()) {
                System.out.println("Loading deck from res dir: " + f.getAbsolutePath());
                return DeckSerializer.fromFile(f);
            }
        }

        // Try as deck name from storage
        Deck deck = FModel.getDecks().getConstructed().get(deckName);
        if (deck != null) {
            System.out.println("Loading deck from storage: " + deckName);
            return deck;
        }

        // Debug: print where we looked
        System.err.println("Could not find deck: " + deckName);
        System.err.println("  Checked absolute: " + deckName);
        System.err.println("  Checked user dir: " + ForgeConstants.DECK_CONSTRUCTED_DIR + deckName);
        System.err.println("  Checked res dir: " + resDir + deckName);
        return null;
    }

    private void handleStatus(PrintWriter out) {
        out.println("FORGE DAEMON STATUS");
        out.println("  Active games: " + activeGames.get());
        out.println("  Total games played: " + totalGamesPlayed.get());
        out.println("  Memory: " + getMemoryUsage());
        out.println("  Max concurrent: " + MAX_CONCURRENT_GAMES);
    }

    private String getMemoryUsage() {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long max = rt.maxMemory() / (1024 * 1024);
        return used + "MB / " + max + "MB";
    }

    private void shutdown() {
        System.out.println("Daemon shutting down...");
        gameExecutor.shutdown();
        try {
            if (!gameExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                gameExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            gameExecutor.shutdownNow();
        }
        System.out.println("Daemon stopped. Total games: " + totalGamesPlayed.get());
    }

    // Entry point
    public static void startDaemon(String[] args) {
        int port = DEFAULT_PORT;

        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-p") && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            }
        }

        new ForgeDaemon(port).start();
    }
}
