package forge.view;

import java.io.BufferedReader;
import java.io.PrintWriter;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * LobbyPlayer for daemon mode - uses socket streams instead of System.in/out.
 *
 * Each game connection gets its own reader/writer, allowing multiple
 * concurrent games to communicate independently.
 */
public class LobbyPlayerDaemon extends LobbyPlayer implements IGameEntitiesFactory {

    private final BufferedReader reader;
    private final PrintWriter writer;

    public LobbyPlayerDaemon(String name, BufferedReader reader, PrintWriter writer) {
        super(name);
        this.reader = reader;
        this.writer = writer;
    }

    private PlayerControllerDaemon createControllerFor(Player player) {
        return new PlayerControllerDaemon(player.getGame(), player, this, reader, writer);
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
        // Daemon doesn't need to hear messages - they go to the socket
    }

    public BufferedReader getReader() {
        return reader;
    }

    public PrintWriter getWriter() {
        return writer;
    }
}
