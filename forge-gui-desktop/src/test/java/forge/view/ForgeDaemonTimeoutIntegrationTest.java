package forge.view;

import forge.ai.AITest;
import forge.game.Game;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Integration test for the AI timeout feature (PR #5).
 *
 * Validates that the Game.AI_TIMEOUT field has the correct default value
 * and that it can be overridden (as the daemon does after parsing -t flag).
 * Requires FModel initialization to create a proper Game object.
 */
public class ForgeDaemonTimeoutIntegrationTest extends AITest {

    @Test(groups = { "UnitTest" })
    public void testDefaultAiTimeoutIsFive() {
        Game game = initAndCreateGame();
        Assert.assertEquals(game.AI_TIMEOUT, 5,
                "Default AI_TIMEOUT should be 5 seconds, matching daemon default");
    }

    @Test(groups = { "UnitTest" })
    public void testAiTimeoutCanBeOverridden() {
        Game game = initAndCreateGame();
        game.AI_TIMEOUT = 15;
        Assert.assertEquals(game.AI_TIMEOUT, 15,
                "AI_TIMEOUT should be writable from daemon");
        Assert.assertEquals(game.getAITimeout(), 15,
                "getAITimeout() should reflect the updated value");
    }

    @Test(groups = { "UnitTest" })
    public void testParsedTimeoutWiresToGame() {
        // Parse a command with -t flag
        ForgeDaemon.ParsedGameCommand cmd = ForgeDaemon.parseNewGameCommand(
                "NEWGAME deck1.dck deck2.dck -t 20");
        Assert.assertNotNull(cmd);
        Assert.assertEquals(cmd.aiTimeout, 20);

        // Simulate what runGame() does: create game, wire timeout
        Game game = initAndCreateGame();
        game.AI_TIMEOUT = cmd.aiTimeout;

        Assert.assertEquals(game.AI_TIMEOUT, 20,
                "Game.AI_TIMEOUT should reflect parsed -t value");
    }
}
