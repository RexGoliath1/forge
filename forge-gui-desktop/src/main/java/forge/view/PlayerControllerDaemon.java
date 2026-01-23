package forge.view;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.Player;

/**
 * PlayerController for daemon mode - wraps PlayerControllerExternal with socket streams.
 */
public class PlayerControllerDaemon extends PlayerControllerExternal {

    public PlayerControllerDaemon(Game game, Player p, LobbyPlayer lp, BufferedReader reader, PrintWriter writer) {
        super(game, p, lp, reader, new PrintWriterStream(writer));
    }

    /**
     * Adapter to use PrintWriter as PrintStream.
     */
    private static class PrintWriterStream extends PrintStream {
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

        @Override
        public PrintStream append(CharSequence csq) {
            writer.append(csq);
            return this;
        }
    }

    private static class NullOutputStream extends OutputStream {
        @Override
        public void write(int b) {
            // discard
        }
    }
}
