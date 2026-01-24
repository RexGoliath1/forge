/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package forge.view;

import java.io.*;
import java.net.*;
import java.util.*;

import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gamemodes.limited.*;
import forge.item.PaperCard;
import forge.model.FModel;

/**
 * TCP daemon for draft simulation.
 * Allows external agents to draft against 7 AI opponents.
 *
 * Protocol:
 *   NEWDRAFT <set_code> - Start a new draft with the specified set
 *   <index> - Pick the card at the given index from current pack
 *   STATUS - Get current draft status
 *   QUIT - End the session
 */
public class ForgeDraftDaemon {

    private final int port;
    private final boolean quiet;
    private ServerSocket serverSocket;
    private volatile boolean running = true;

    public ForgeDraftDaemon(int port, boolean quiet) {
        this.port = port;
        this.quiet = quiet;
    }

    public void start() throws IOException {
        System.out.println("=".repeat(60));
        System.out.println("FORGE DRAFT DAEMON");
        System.out.println("=".repeat(60));

        // Initialize card database
        System.out.println("Loading card database...");
        try {
            FModel.initialize(null, null);
        } catch (Throwable t) {
            System.err.println("Failed to initialize FModel:");
            t.printStackTrace();
            return;
        }
        System.out.println("Card database loaded");

        serverSocket = new ServerSocket(port);
        if (!quiet) {
            System.out.println("Draft Daemon listening on port " + port);
        }

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                if (!quiet) {
                    System.out.println("Client connected: " + clientSocket.getRemoteSocketAddress());
                }

                // Handle each client in a new thread
                new Thread(() -> handleClient(clientSocket)).start();
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error accepting connection: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
            DraftSession session = null;
            String line;

            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+", 2);
                String command = parts[0].toUpperCase();

                try {
                    switch (command) {
                        case "NEWDRAFT":
                            if (parts.length < 2) {
                                out.println("ERROR: NEWDRAFT requires set code (e.g., NEWDRAFT NEO)");
                                break;
                            }
                            String setCode = parts[1].toUpperCase();
                            session = new DraftSession(setCode, out, quiet);
                            if (session.isValid()) {
                                out.println("DRAFT_STARTED " + session.getDraftInfo());
                                // Send first pack
                                out.println("PACK " + session.getCurrentPackJson());
                            } else {
                                out.println("ERROR: Failed to start draft with set " + setCode);
                                session = null;
                            }
                            break;

                        case "STATUS":
                            if (session == null) {
                                out.println("STATUS: No active draft");
                            } else {
                                out.println("STATUS " + session.getStatusJson());
                            }
                            break;

                        case "POOL":
                            if (session == null) {
                                out.println("ERROR: No active draft");
                            } else {
                                out.println("POOL " + session.getPoolJson());
                            }
                            break;

                        case "QUIT":
                            out.println("GOODBYE");
                            return;

                        default:
                            // Try to parse as a pick index
                            if (session == null) {
                                out.println("ERROR: No active draft. Use NEWDRAFT <set> first.");
                                break;
                            }

                            try {
                                int pickIndex = Integer.parseInt(command);
                                String result = session.makePick(pickIndex);
                                out.println(result);

                                // If draft is still ongoing, send next pack
                                if (!session.isComplete()) {
                                    out.println("PACK " + session.getCurrentPackJson());
                                }
                            } catch (NumberFormatException e) {
                                out.println("ERROR: Unknown command: " + command);
                            }
                            break;
                    }
                } catch (Exception e) {
                    out.println("ERROR: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            if (!quiet) {
                System.err.println("Client disconnected: " + e.getMessage());
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            // Ignore
        }
    }

    // JSON helper methods
    private static void appendString(StringBuilder sb, String key, String value) {
        sb.append("\"").append(key).append("\": \"").append(escapeJson(value)).append("\"");
    }

    private static void appendNumber(StringBuilder sb, String key, int value) {
        sb.append("\"").append(key).append("\": ").append(value);
    }

    private static void appendBoolean(StringBuilder sb, String key, boolean value) {
        sb.append("\"").append(key).append("\": ").append(value);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Represents a single draft session with one human player and 7 AIs.
     */
    private class DraftSession {
        private final BoosterDraft draft;
        private final String setCode;
        private final PrintWriter out;
        private final boolean quiet;
        private boolean valid = false;
        private boolean complete = false;
        private int packNum = 1;
        private int pickNum = 1;
        private final List<String> pickedCards = new ArrayList<>();

        public DraftSession(String setCode, PrintWriter out, boolean quiet) {
            this.setCode = setCode;
            this.out = out;
            this.quiet = quiet;

            // Create draft
            this.draft = createDraft();
            this.valid = (draft != null);
        }

        private BoosterDraft createDraft() {
            try {
                // Use Full card pool draft (includes all sets)
                BoosterDraft draft = BoosterDraft.createDraft(LimitedPoolType.Full);

                if (draft == null) {
                    if (!quiet) {
                        System.err.println("Failed to create draft");
                    }
                    return null;
                }

                if (!quiet) {
                    System.out.println("Created draft (Full card pool)");
                }

                return draft;
            } catch (Exception e) {
                if (!quiet) {
                    System.err.println("Error creating draft: " + e.getMessage());
                    e.printStackTrace();
                }
                return null;
            }
        }

        public boolean isValid() {
            return valid;
        }

        public boolean isComplete() {
            return complete;
        }

        public String getDraftInfo() {
            StringBuilder json = new StringBuilder();
            json.append("{");
            appendString(json, "set", setCode);
            json.append(", ");
            appendNumber(json, "pack_count", 3);
            json.append(", ");
            appendNumber(json, "cards_per_pack", 15);
            json.append(", ");
            appendNumber(json, "players", 8);
            json.append(", ");
            appendNumber(json, "seat", 0);
            json.append("}");
            return json.toString();
        }

        public String getStatusJson() {
            StringBuilder json = new StringBuilder();
            json.append("{");
            appendBoolean(json, "complete", complete);
            json.append(", ");
            appendNumber(json, "pack_num", packNum);
            json.append(", ");
            appendNumber(json, "pick_num", pickNum);
            json.append(", ");
            appendNumber(json, "total_picks", pickedCards.size());
            json.append(", ");
            appendNumber(json, "pool_size", pickedCards.size());
            json.append("}");
            return json.toString();
        }

        public String getPoolJson() {
            StringBuilder json = new StringBuilder();
            json.append("{\"cards\": [");
            for (int i = 0; i < pickedCards.size(); i++) {
                if (i > 0) json.append(", ");
                json.append("\"").append(escapeJson(pickedCards.get(i))).append("\"");
            }
            json.append("], ");
            appendNumber(json, "size", pickedCards.size());
            json.append("}");
            return json.toString();
        }

        public String getCurrentPackJson() {
            if (complete) {
                return "{\"error\": \"Draft complete\"}";
            }

            try {
                CardPool pack = draft.nextChoice();
                if (pack == null || pack.isEmpty()) {
                    // Check if draft is complete
                    if (!draft.hasNextChoice()) {
                        complete = true;
                        return getDraftCompleteJson();
                    }
                    return "{\"error\": \"No pack available\"}";
                }

                StringBuilder json = new StringBuilder();
                json.append("{");
                appendNumber(json, "pack_num", packNum);
                json.append(", ");
                appendNumber(json, "pick_num", pickNum);
                json.append(", \"cards\": [");

                List<PaperCard> cardList = pack.toFlatList();
                for (int i = 0; i < cardList.size(); i++) {
                    if (i > 0) json.append(", ");
                    json.append(cardToJson(cardList.get(i), i));
                }

                json.append("], \"pool\": [");
                for (int i = 0; i < pickedCards.size(); i++) {
                    if (i > 0) json.append(", ");
                    json.append("\"").append(escapeJson(pickedCards.get(i))).append("\"");
                }
                json.append("]}");

                return json.toString();
            } catch (Exception e) {
                return "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}";
            }
        }

        public String makePick(int index) {
            if (complete) {
                return "ERROR: Draft already complete";
            }

            try {
                CardPool pack = draft.nextChoice();
                if (pack == null || pack.isEmpty()) {
                    if (!draft.hasNextChoice()) {
                        complete = true;
                        return "DRAFT_COMPLETE " + getDraftCompleteJson();
                    }
                    return "ERROR: No pack available";
                }

                List<PaperCard> cardList = pack.toFlatList();
                if (index < 0 || index >= cardList.size()) {
                    return "ERROR: Invalid index " + index + " (pack has " + cardList.size() + " cards)";
                }

                PaperCard picked = cardList.get(index);

                // Make the pick
                boolean success = draft.setChoice(picked, DeckSection.Main);

                if (!success) {
                    return "ERROR: Failed to pick card";
                }

                pickedCards.add(picked.getName());

                if (!quiet) {
                    System.out.println("Pick " + pickNum + "/" + packNum + ": " + picked.getName());
                }

                // Update counters
                pickNum++;
                if (pickNum > 15) {
                    pickNum = 1;
                    packNum++;
                }

                // Check if draft complete
                if (!draft.hasNextChoice()) {
                    complete = true;
                    return "PICKED {\"card\": \"" + escapeJson(picked.getName()) + "\"}\nDRAFT_COMPLETE " + getDraftCompleteJson();
                }

                return "PICKED {\"card\": \"" + escapeJson(picked.getName()) + "\"}";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        private String getDraftCompleteJson() {
            StringBuilder json = new StringBuilder();
            json.append("{");
            appendBoolean(json, "complete", true);
            json.append(", \"pool\": [");
            for (int i = 0; i < pickedCards.size(); i++) {
                if (i > 0) json.append(", ");
                json.append("\"").append(escapeJson(pickedCards.get(i))).append("\"");
            }
            json.append("], ");
            appendNumber(json, "pool_size", pickedCards.size());
            json.append("}");
            return json.toString();
        }

        private String cardToJson(PaperCard card, int index) {
            StringBuilder json = new StringBuilder();
            json.append("{");
            appendNumber(json, "index", index);
            json.append(", ");
            appendString(json, "name", card.getName());
            json.append(", ");
            appendString(json, "mana_cost", card.getRules().getManaCost().toString());
            json.append(", ");
            appendString(json, "type", card.getRules().getType().toString());
            json.append(", ");
            appendString(json, "rarity", card.getRarity().toString());
            json.append(", ");
            appendString(json, "set", card.getEdition());

            // Add power/toughness for creatures
            if (card.getRules().getType().isCreature()) {
                json.append(", ");
                appendString(json, "power", String.valueOf(card.getRules().getPower()));
                json.append(", ");
                appendString(json, "toughness", String.valueOf(card.getRules().getToughness()));
            }

            // Add oracle text (truncated if too long)
            String oracle = card.getRules().getOracleText();
            if (oracle != null && oracle.length() > 200) {
                oracle = oracle.substring(0, 200) + "...";
            }
            json.append(", ");
            appendString(json, "oracle_text", oracle != null ? oracle : "");

            // Add color identity
            json.append(", ");
            appendString(json, "colors", card.getRules().getColorIdentity().toString());

            json.append("}");
            return json.toString();
        }
    }

    /**
     * Entry point when started from Main.java
     */
    public static void startDaemon(String[] args) {
        int port = 17272;  // Different port from game daemon
        boolean quiet = false;

        for (int i = 1; i < args.length; i++) {  // Start at 1 to skip "draft" command
            if (args[i].equals("-p") && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-q")) {
                quiet = true;
            }
        }

        try {
            ForgeDraftDaemon daemon = new ForgeDraftDaemon(port, quiet);

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down draft daemon...");
                daemon.stop();
            }));

            daemon.start();
        } catch (IOException e) {
            System.err.println("Failed to start draft daemon: " + e.getMessage());
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        // For standalone testing
        String[] newArgs = new String[args.length + 1];
        newArgs[0] = "draft";
        System.arraycopy(args, 0, newArgs, 1, args.length);
        startDaemon(newArgs);
    }
}
