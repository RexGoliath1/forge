package forge.view;

import java.io.BufferedReader;
import java.io.PrintWriter;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * LobbyPlayer for AI observation mode.
 * Uses AI to make decisions while logging them for training data.
 */
public class LobbyPlayerAiObserver extends LobbyPlayer implements IGameEntitiesFactory {

    private final BufferedReader reader;
    private final PrintWriter writer;

    public LobbyPlayerAiObserver(String name, BufferedReader reader, PrintWriter writer) {
        super(name);
        this.reader = reader;
        this.writer = writer;
    }

    private PlayerControllerAiObserver createControllerFor(Player player) {
        return new PlayerControllerAiObserver(
            player.getGame(), player, this, reader,
            new PrintWriterStream(writer));
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return createControllerFor(slave);
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        player.setFirstController(createControllerFor(player));
        return player;
    }

    @Override
    public void hear(LobbyPlayer player, String message) {
        // Messages go to socket
    }

    /**
     * Adapter to use PrintWriter as PrintStream.
     */
    private static class PrintWriterStream extends java.io.PrintStream {
        private final PrintWriter writer;

        public PrintWriterStream(PrintWriter writer) {
            super(new NullOutputStream());
            this.writer = writer;
        }

        @Override
        public void print(String s) {
            writer.print(s);
        }

        @Override
        public void println(String s) {
            writer.println(s);
            writer.flush();
        }

        @Override
        public void println() {
            writer.println();
            writer.flush();
        }

        @Override
        public void flush() {
            writer.flush();
        }
    }

    private static class NullOutputStream extends java.io.OutputStream {
        @Override
        public void write(int b) {
            // discard
        }
    }
}
