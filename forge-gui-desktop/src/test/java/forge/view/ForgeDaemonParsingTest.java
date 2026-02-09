package forge.view;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests for ForgeDaemon command parsing logic.
 *
 * Validates the NEWGAME command parser, including the -t flag
 * for per-decision AI timeout (PR #5) and default values.
 *
 * These tests exercise {@link ForgeDaemon#parseNewGameCommand(String)}
 * without requiring FModel initialization or network setup.
 */
public class ForgeDaemonParsingTest {

    @Test(groups = { "UnitTest" })
    public void testParseMinimalCommand() {
        ForgeDaemon.ParsedGameCommand cmd = ForgeDaemon.parseNewGameCommand("NEWGAME deck1.dck deck2.dck");
        Assert.assertNotNull(cmd, "Minimal command should parse successfully");
        Assert.assertEquals(cmd.deck1, "deck1.dck");
        Assert.assertEquals(cmd.deck2, "deck2.dck");
        Assert.assertFalse(cmd.interactive);
        Assert.assertFalse(cmd.observe);
        Assert.assertFalse(cmd.quiet);
        Assert.assertEquals(cmd.timeout, 120, "Default game timeout should be 120");
        Assert.assertEquals(cmd.aiTimeout, 5, "Default AI timeout should be 5");
        Assert.assertNull(cmd.seed, "Seed should be null when not specified");
    }

    @Test(groups = { "UnitTest" })
    public void testParseAiTimeoutFlag() {
        ForgeDaemon.ParsedGameCommand cmd = ForgeDaemon.parseNewGameCommand("NEWGAME deck1.dck deck2.dck -t 10");
        Assert.assertNotNull(cmd);
        Assert.assertEquals(cmd.aiTimeout, 10, "AI timeout should be parsed from -t flag");
        Assert.assertEquals(cmd.timeout, 120, "Game timeout should remain at default");
    }

    @Test(groups = { "UnitTest" })
    public void testParseAiTimeoutWithOtherFlags() {
        ForgeDaemon.ParsedGameCommand cmd = ForgeDaemon.parseNewGameCommand(
                "NEWGAME deck1.dck deck2.dck -o -c 60 -t 15 -q");
        Assert.assertNotNull(cmd);
        Assert.assertEquals(cmd.aiTimeout, 15);
        Assert.assertEquals(cmd.timeout, 60);
        Assert.assertTrue(cmd.observe);
        Assert.assertTrue(cmd.quiet);
        Assert.assertFalse(cmd.interactive);
    }

    @Test(groups = { "UnitTest" })
    public void testParseInteractiveMode() {
        ForgeDaemon.ParsedGameCommand cmd = ForgeDaemon.parseNewGameCommand("NEWGAME deck1.dck deck2.dck -i");
        Assert.assertNotNull(cmd);
        Assert.assertTrue(cmd.interactive);
        Assert.assertFalse(cmd.observe);
    }

    @Test(groups = { "UnitTest" })
    public void testParseObservationMode() {
        ForgeDaemon.ParsedGameCommand cmd = ForgeDaemon.parseNewGameCommand("NEWGAME deck1.dck deck2.dck -o");
        Assert.assertNotNull(cmd);
        Assert.assertTrue(cmd.observe);
        Assert.assertFalse(cmd.interactive);
    }

    @Test(groups = { "UnitTest" })
    public void testParseSeedFlag() {
        ForgeDaemon.ParsedGameCommand cmd = ForgeDaemon.parseNewGameCommand(
                "NEWGAME deck1.dck deck2.dck -s 42");
        Assert.assertNotNull(cmd);
        Assert.assertEquals(cmd.seed, Long.valueOf(42));
    }

    @Test(groups = { "UnitTest" })
    public void testParseAllFlags() {
        ForgeDaemon.ParsedGameCommand cmd = ForgeDaemon.parseNewGameCommand(
                "NEWGAME alpha.dck beta.dck -i -o -q -c 90 -t 20 -s 12345");
        Assert.assertNotNull(cmd);
        Assert.assertEquals(cmd.deck1, "alpha.dck");
        Assert.assertEquals(cmd.deck2, "beta.dck");
        Assert.assertTrue(cmd.interactive);
        Assert.assertTrue(cmd.observe);
        Assert.assertTrue(cmd.quiet);
        Assert.assertEquals(cmd.timeout, 90);
        Assert.assertEquals(cmd.aiTimeout, 20);
        Assert.assertEquals(cmd.seed, Long.valueOf(12345));
    }

    @Test(groups = { "UnitTest" })
    public void testParseTooFewArgsReturnsNull() {
        Assert.assertNull(ForgeDaemon.parseNewGameCommand("NEWGAME"),
                "Command with no decks should return null");
        Assert.assertNull(ForgeDaemon.parseNewGameCommand("NEWGAME deck1.dck"),
                "Command with only one deck should return null");
    }

    @Test(groups = { "UnitTest" })
    public void testParseDefaultAiTimeoutIsFive() {
        // Explicitly verify the default value matches Game.AI_TIMEOUT default (5)
        ForgeDaemon.ParsedGameCommand cmd = ForgeDaemon.parseNewGameCommand("NEWGAME d1.dck d2.dck -o -c 60");
        Assert.assertNotNull(cmd);
        Assert.assertEquals(cmd.aiTimeout, 5,
                "Default AI timeout must be 5 to match Game.AI_TIMEOUT default");
    }

    @Test(groups = { "UnitTest" })
    public void testParseGameTimeoutFlag() {
        ForgeDaemon.ParsedGameCommand cmd = ForgeDaemon.parseNewGameCommand("NEWGAME d1.dck d2.dck -c 300");
        Assert.assertNotNull(cmd);
        Assert.assertEquals(cmd.timeout, 300);
    }

    @Test(groups = { "UnitTest" })
    public void testParseExtraWhitespace() {
        ForgeDaemon.ParsedGameCommand cmd = ForgeDaemon.parseNewGameCommand(
                "NEWGAME   deck1.dck   deck2.dck   -t   7");
        Assert.assertNotNull(cmd, "Extra whitespace should be handled by split(\\\\s+)");
        Assert.assertEquals(cmd.aiTimeout, 7);
    }
}
