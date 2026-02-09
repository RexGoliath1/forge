package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests for the activating player fix (PR #4).
 *
 * Validates that Spell.canPlayFromHost(), AbilityActivated.canPlay(), and
 * AbilityStatic.canPlay() correctly set the activating player when it is null,
 * rather than throwing NullPointerException or printing warning messages.
 *
 * The fix ensures that when a SpellAbility has no activating player set,
 * the card's controller is used as a fallback.
 */
public class SpellAbilityActivatorTest extends AITest {

    /**
     * Test that a Spell (card in hand) can be queried with canPlay() even when
     * no activating player has been explicitly set. The fix in Spell.canPlayFromHost()
     * should fall back to the card's controller.
     */
    @Test(groups = { "UnitTest" })
    public void testSpellCanPlayWithNullActivator() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        // Add mana sources so the spell could theoretically be cast
        addCards("Mountain", 5, p);

        // Put a spell card in hand - Lightning Bolt is simple and cheap
        Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        // Get the spell ability (cast from hand)
        SpellAbility spellSA = bolt.getSpellAbilities().get(0);

        // Deliberately clear the activating player to simulate the bug scenario
        spellSA.setActivatingPlayer(null);
        Assert.assertNull(spellSA.getActivatingPlayer(),
                "Activating player should be null before canPlay()");

        // canPlay() should NOT throw an exception
        boolean canPlay = spellSA.canPlay();

        // After canPlay(), the activating player should be set to the card's controller
        Assert.assertNotNull(spellSA.getActivatingPlayer(),
                "Activating player should be set after canPlay()");
        Assert.assertEquals(spellSA.getActivatingPlayer(), p,
                "Activating player should be the card's controller");
    }

    /**
     * Test that an activated ability can be queried with canPlay() even when
     * no activating player has been explicitly set. The fix in AbilityActivated.canPlay()
     * should fall back to the card's controller.
     */
    @Test(groups = { "UnitTest" })
    public void testActivatedAbilityCanPlayWithNullActivator() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCards("Plains", 5, p);

        // Nantuko Shade has a simple activated ability: {B}: +1/+1
        // Herald of Anafenza has Outlast which is an activated ability
        Card herald = addCard("Herald of Anafenza", p);
        herald.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility outlastSA = findSAWithPrefix(herald, "Outlast");
        Assert.assertNotNull(outlastSA, "Should find Outlast ability");

        // Deliberately clear the activating player
        outlastSA.setActivatingPlayer(null);
        Assert.assertNull(outlastSA.getActivatingPlayer(),
                "Activating player should be null before canPlay()");

        // canPlay() should NOT throw an exception
        boolean canPlay = outlastSA.canPlay();

        // After canPlay(), the activating player should be set to the card's controller
        Assert.assertNotNull(outlastSA.getActivatingPlayer(),
                "Activating player should be set after canPlay() on activated ability");
        Assert.assertEquals(outlastSA.getActivatingPlayer(), p,
                "Activating player should be the card's controller");
    }

    /**
     * Test that a static ability (like morph/unmorph) can be queried with canPlay()
     * even when no activating player has been explicitly set. The fix in
     * AbilityStatic.canPlay() should fall back to the card's controller.
     */
    @Test(groups = { "UnitTest" })
    public void testStaticAbilityCanPlayWithNullActivator() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCards("Swamp", 5, p);

        // Ruthless Ripper has morph - face down, it has a static "turn face up" ability
        Card ripper = createCard("Ruthless Ripper", p);
        ripper.turnFaceDownNoUpdate();
        p.getZone(ZoneType.Battlefield).add(ripper);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        // Find the morph/unmorph ability (static ability to turn face up)
        SpellAbility morphSA = findSAWithPrefix(ripper, "Morph");
        if (morphSA == null) {
            // Try alternate prefix - some cards use "Turn face up"
            morphSA = findSAWithPrefix(ripper, "Turn face up");
        }

        // If we found a morph/turn-face-up ability, test it
        if (morphSA != null) {
            // Deliberately clear the activating player
            morphSA.setActivatingPlayer(null);
            Assert.assertNull(morphSA.getActivatingPlayer(),
                    "Activating player should be null before canPlay()");

            // canPlay() should NOT throw an exception
            boolean canPlay = morphSA.canPlay();

            // After canPlay(), the activating player should be set
            Assert.assertNotNull(morphSA.getActivatingPlayer(),
                    "Activating player should be set after canPlay() on static ability");
            Assert.assertEquals(morphSA.getActivatingPlayer(), p,
                    "Activating player should be the card's controller");
        }
    }

    /**
     * Test that canPlay() works correctly when an activating player IS already set.
     * This is a regression guard - the fix should not break the normal path.
     */
    @Test(groups = { "UnitTest" })
    public void testSpellCanPlayWithActivatorAlreadySet() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCards("Mountain", 5, p);

        Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility spellSA = bolt.getSpellAbilities().get(0);

        // Set the activating player explicitly (normal path)
        spellSA.setActivatingPlayer(p);
        Assert.assertNotNull(spellSA.getActivatingPlayer());

        // canPlay() should still work normally
        boolean canPlay = spellSA.canPlay();

        // Activating player should still be set to the same player
        Assert.assertEquals(spellSA.getActivatingPlayer(), p,
                "Activating player should remain unchanged when already set");
    }

    /**
     * Test that canPlay() on an activated ability with activator already set
     * does not change the activator. Regression guard.
     */
    @Test(groups = { "UnitTest" })
    public void testActivatedAbilityCanPlayWithActivatorAlreadySet() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCards("Plains", 5, p);

        Card herald = addCard("Herald of Anafenza", p);
        herald.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility outlastSA = findSAWithPrefix(herald, "Outlast");
        Assert.assertNotNull(outlastSA);

        // Set activating player (normal path)
        outlastSA.setActivatingPlayer(p);

        // canPlay() should work normally
        boolean canPlay = outlastSA.canPlay();

        // Player should still be the same
        Assert.assertEquals(outlastSA.getActivatingPlayer(), p);
    }
}
