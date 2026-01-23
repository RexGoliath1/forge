package forge.view;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;

import java.io.BufferedReader;
import java.io.PrintStream;

/**
 * LobbyPlayer implementation for external agent control.
 * Creates PlayerControllerExternal instances that communicate via stdin/stdout.
 */
public class LobbyPlayerExternal extends LobbyPlayer implements IGameEntitiesFactory {

    private final BufferedReader input;
    private final PrintStream output;

    public LobbyPlayerExternal(String name) {
        this(name, null, null);
    }

    public LobbyPlayerExternal(String name, BufferedReader input, PrintStream output) {
        super(name);
        this.input = input;
        this.output = output;
    }

    private PlayerControllerExternal createControllerFor(Player player) {
        if (input != null && output != null) {
            return new PlayerControllerExternal(player.getGame(), player, this, input, output);
        }
        return new PlayerControllerExternal(player.getGame(), player, this);
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
        // External agent doesn't need to hear messages
    }
}
