package forge.view;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * AI Observer Controller
 *
 * Uses Forge AI to make decisions while logging them for training data collection.
 * This provides:
 * 1. Proper game flow (AI makes good decisions)
 * 2. Training data (all decisions logged with game state)
 * 3. Observable decisions (output to socket)
 */
public class PlayerControllerAiObserver extends PlayerControllerAi {

    private final PrintStream output;
    private final PlayerControllerExternal externalLogger;
    private int decisionId = 0;

    public PlayerControllerAiObserver(Game game, Player p, LobbyPlayer lp,
            BufferedReader input, PrintStream output) {
        super(game, p, lp);
        this.output = output;
        // Use external logger for JSON output format
        this.externalLogger = new PlayerControllerExternal(game, p, lp, input, output);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        // Get AI's choice
        List<SpellAbility> aiChoice = super.chooseSpellAbilityToPlay();

        // Log the decision with game state
        logDecision("choose_action", aiChoice);

        return aiChoice;
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        // Get possible attackers before AI decides
        CardCollection possibleAttackers = new CardCollection();
        for (Card c : attacker.getCreaturesInPlay()) {
            if (CombatUtil.canAttack(c)) {
                possibleAttackers.add(c);
            }
        }

        // Let AI declare attackers
        super.declareAttackers(attacker, combat);

        // Log the decision
        List<Card> chosenAttackers = combat.getAttackers();
        logAttackerDecision(possibleAttackers, chosenAttackers);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        // Get possible blockers before AI decides
        CardCollection possibleBlockers = defender.getCreaturesInPlay();
        List<Card> attackers = combat.getAttackers();

        // Let AI declare blockers
        super.declareBlockers(defender, combat);

        // Log the decision
        logBlockerDecision(possibleBlockers, attackers, combat);
    }

    private void logDecision(String decisionType, List<SpellAbility> aiChoice) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"decision_type\":\"").append(decisionType).append("\"");
        json.append(",\"decision_id\":").append(decisionId++);
        json.append(",\"player\":\"").append(escapeString(player.getName())).append("\"");
        json.append(",\"turn\":").append(getGame().getPhaseHandler().getTurn());
        json.append(",\"phase\":\"").append(getGame().getPhaseHandler().getPhase()).append("\"");

        // Include game state
        json.append(",\"game_state\":");
        appendGameState(json);

        // Include available actions
        json.append(",\"actions\":");
        appendAvailableActions(json);

        // Include AI's choice
        json.append(",\"ai_choice\":");
        if (aiChoice != null && !aiChoice.isEmpty()) {
            SpellAbility chosen = aiChoice.get(0);
            json.append("{");
            json.append("\"card\":\"").append(escapeString(
                chosen.getHostCard() != null ? chosen.getHostCard().getName() : "")).append("\"");
            json.append(",\"description\":\"").append(escapeString(chosen.toString())).append("\"");
            json.append(",\"is_land\":").append(chosen.isLandAbility());
            json.append("}");
        } else {
            json.append("{\"action\":\"pass\"}");
        }

        json.append("}");
        output.println("DECISION:" + json.toString());
        output.flush();
    }

    private void logAttackerDecision(CardCollection possible, List<Card> chosen) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"decision_type\":\"declare_attackers\"");
        json.append(",\"decision_id\":").append(decisionId++);
        json.append(",\"player\":\"").append(escapeString(player.getName())).append("\"");
        json.append(",\"turn\":").append(getGame().getPhaseHandler().getTurn());
        json.append(",\"phase\":\"").append(getGame().getPhaseHandler().getPhase()).append("\"");

        // Include game state
        json.append(",\"game_state\":");
        appendGameState(json);

        // Possible attackers
        json.append(",\"attackers\":[");
        boolean first = true;
        for (int i = 0; i < possible.size(); i++) {
            Card c = possible.get(i);
            if (!first) json.append(",");
            first = false;
            json.append("{");
            json.append("\"index\":").append(i);
            json.append(",\"card_id\":").append(c.getId());
            json.append(",\"name\":\"").append(escapeString(c.getName())).append("\"");
            json.append(",\"power\":").append(c.getNetPower());
            json.append(",\"toughness\":").append(c.getNetToughness());
            json.append("}");
        }
        json.append("]");

        // AI's choice
        json.append(",\"ai_choice\":[");
        first = true;
        for (Card c : chosen) {
            int idx = possible.indexOf(c);
            if (idx >= 0) {
                if (!first) json.append(",");
                first = false;
                json.append(idx);
            }
        }
        json.append("]");

        json.append("}");
        output.println("DECISION:" + json.toString());
        output.flush();
    }

    private void logBlockerDecision(CardCollection blockers, List<Card> attackers, Combat combat) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"decision_type\":\"declare_blockers\"");
        json.append(",\"decision_id\":").append(decisionId++);
        json.append(",\"player\":\"").append(escapeString(player.getName())).append("\"");
        json.append(",\"turn\":").append(getGame().getPhaseHandler().getTurn());
        json.append(",\"phase\":\"").append(getGame().getPhaseHandler().getPhase()).append("\"");

        // Include game state
        json.append(",\"game_state\":");
        appendGameState(json);

        // Attackers
        json.append(",\"attackers\":[");
        boolean first = true;
        for (int i = 0; i < attackers.size(); i++) {
            Card a = attackers.get(i);
            if (!first) json.append(",");
            first = false;
            json.append("{");
            json.append("\"index\":").append(i);
            json.append(",\"card_id\":").append(a.getId());
            json.append(",\"name\":\"").append(escapeString(a.getName())).append("\"");
            json.append(",\"power\":").append(a.getNetPower());
            json.append(",\"toughness\":").append(a.getNetToughness());
            json.append("}");
        }
        json.append("]");

        // Possible blockers
        json.append(",\"blockers\":[");
        first = true;
        for (int i = 0; i < blockers.size(); i++) {
            Card b = blockers.get(i);
            if (CombatUtil.canBlock(b, combat)) {
                if (!first) json.append(",");
                first = false;
                json.append("{");
                json.append("\"index\":").append(i);
                json.append(",\"card_id\":").append(b.getId());
                json.append(",\"name\":\"").append(escapeString(b.getName())).append("\"");
                json.append(",\"power\":").append(b.getNetPower());
                json.append(",\"toughness\":").append(b.getNetToughness());
                json.append("}");
            }
        }
        json.append("]");

        // AI's blocking assignments
        json.append(",\"ai_choice\":[");
        first = true;
        for (Card attacker : attackers) {
            CardCollection assignedBlockers = combat.getBlockers(attacker);
            if (assignedBlockers != null && !assignedBlockers.isEmpty()) {
                int attackerIdx = attackers.indexOf(attacker);
                for (Card blocker : assignedBlockers) {
                    int blockerIdx = blockers.indexOf(blocker);
                    if (blockerIdx >= 0) {
                        if (!first) json.append(",");
                        first = false;
                        json.append("{\"blocker\":").append(blockerIdx);
                        json.append(",\"attacker\":").append(attackerIdx).append("}");
                    }
                }
            }
        }
        json.append("]");

        json.append("}");
        output.println("DECISION:" + json.toString());
        output.flush();
    }

    private void appendGameState(StringBuilder json) {
        json.append("{");

        // Game metadata
        json.append("\"is_game_over\":").append(getGame().isGameOver());
        json.append(",\"active_player\":\"").append(escapeString(
            getGame().getPhaseHandler().getPlayerTurn() != null
                ? getGame().getPhaseHandler().getPlayerTurn().getName() : "")).append("\"");

        // All players' states
        json.append(",\"players\":[");
        boolean first = true;
        for (Player p : getGame().getRegisteredPlayers()) {
            if (!first) json.append(",");
            first = false;
            appendPlayerState(json, p);
        }
        json.append("]");

        json.append("}");
    }

    private void appendPlayerState(StringBuilder json, Player p) {
        json.append("{");

        // Basic info
        json.append("\"name\":\"").append(escapeString(p.getName())).append("\"");
        json.append(",\"life\":").append(p.getLife());
        json.append(",\"poison\":").append(p.getPoisonCounters());
        json.append(",\"has_lost\":").append(p.hasLost());
        json.append(",\"lands_played_this_turn\":").append(p.getLandsPlayedThisTurn());
        json.append(",\"max_land_plays\":").append(p.getMaxLandPlays());

        // Library size (hidden info - don't expose contents)
        json.append(",\"library_size\":").append(p.getCardsIn(ZoneType.Library).size());

        // Hand - full card data
        json.append(",\"hand_size\":").append(p.getCardsIn(ZoneType.Hand).size());
        json.append(",\"hand\":[");
        boolean first = true;
        for (Card c : p.getCardsIn(ZoneType.Hand)) {
            if (!first) json.append(",");
            first = false;
            appendCardDetails(json, c);
        }
        json.append("]");

        // Battlefield - full card data with battlefield-specific fields
        int creatures = 0, lands = 0, other = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature()) creatures++;
            else if (c.isLand()) lands++;
            else other++;
        }
        json.append(",\"battlefield_creatures\":").append(creatures);
        json.append(",\"battlefield_lands\":").append(lands);
        json.append(",\"battlefield_other\":").append(other);
        json.append(",\"battlefield\":[");
        first = true;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (!first) json.append(",");
            first = false;
            appendBattlefieldCard(json, c);
        }
        json.append("]");

        // Graveyard - full card data
        json.append(",\"graveyard\":[");
        first = true;
        for (Card c : p.getCardsIn(ZoneType.Graveyard)) {
            if (!first) json.append(",");
            first = false;
            appendCardDetails(json, c);
        }
        json.append("]");

        // Exile - full card data
        json.append(",\"exile\":[");
        first = true;
        for (Card c : p.getCardsIn(ZoneType.Exile)) {
            if (!first) json.append(",");
            first = false;
            appendCardDetails(json, c);
        }
        json.append("]");

        // Mana pool
        json.append(",\"mana_pool\":{");
        json.append("\"total\":").append(p.getManaPool().totalMana());
        json.append("}");

        json.append("}");
    }

    /**
     * Serialize a card with identity fields common to all zones.
     * Used for hand, graveyard, and exile zones.
     */
    private void appendCardDetails(StringBuilder json, Card c) {
        json.append("{");
        json.append("\"id\":").append(c.getId());
        json.append(",\"name\":\"").append(escapeString(c.getName())).append("\"");
        json.append(",\"cmc\":").append(c.getCMC());

        // Type flags
        json.append(",\"is_creature\":").append(c.isCreature());
        json.append(",\"is_land\":").append(c.isLand());
        json.append(",\"is_artifact\":").append(c.isArtifact());
        json.append(",\"is_enchantment\":").append(c.isEnchantment());
        json.append(",\"is_planeswalker\":").append(c.isPlaneswalker());
        json.append(",\"is_instant\":").append(c.isInstant());
        json.append(",\"is_sorcery\":").append(c.isSorcery());

        // Power/toughness (0 for non-creatures)
        json.append(",\"power\":").append(c.isCreature() ? c.getNetPower() : 0);
        json.append(",\"toughness\":").append(c.isCreature() ? c.getNetToughness() : 0);

        // Oracle text for mechanics parsing
        json.append(",\"oracle_text\":\"").append(escapeString(
            c.getOracleText() != null ? c.getOracleText() : "")).append("\"");

        json.append("}");
    }

    /**
     * Serialize a battlefield card with additional state fields.
     * Includes tapped, summoning sickness, counters, damage, loyalty.
     */
    private void appendBattlefieldCard(StringBuilder json, Card c) {
        json.append("{");
        json.append("\"id\":").append(c.getId());
        json.append(",\"name\":\"").append(escapeString(c.getName())).append("\"");
        json.append(",\"cmc\":").append(c.getCMC());

        // Type flags
        json.append(",\"is_creature\":").append(c.isCreature());
        json.append(",\"is_land\":").append(c.isLand());
        json.append(",\"is_artifact\":").append(c.isArtifact());
        json.append(",\"is_enchantment\":").append(c.isEnchantment());
        json.append(",\"is_planeswalker\":").append(c.isPlaneswalker());
        json.append(",\"is_instant\":").append(c.isInstant());
        json.append(",\"is_sorcery\":").append(c.isSorcery());

        // Power/toughness (0 for non-creatures)
        json.append(",\"power\":").append(c.isCreature() ? c.getNetPower() : 0);
        json.append(",\"toughness\":").append(c.isCreature() ? c.getNetToughness() : 0);

        // Oracle text for mechanics parsing
        json.append(",\"oracle_text\":\"").append(escapeString(
            c.getOracleText() != null ? c.getOracleText() : "")).append("\"");

        // Battlefield-specific state
        json.append(",\"tapped\":").append(c.isTapped());
        json.append(",\"summoningSickness\":").append(c.hasSickness());

        // Damage (for creatures)
        if (c.isCreature()) {
            json.append(",\"damage\":").append(c.getDamage());
        }

        // Loyalty (for planeswalkers)
        if (c.isPlaneswalker()) {
            json.append(",\"loyalty\":").append(c.getCurrentLoyalty());
        }

        // Counters as array of {type, count} objects
        if (c.hasCounters()) {
            json.append(",\"counters\":[");
            boolean counterFirst = true;
            for (Map.Entry<CounterType, Integer> entry : c.getCounters().entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0) {
                    if (!counterFirst) json.append(",");
                    counterFirst = false;
                    json.append("{\"type\":\"").append(escapeString(
                        entry.getKey().getName().toLowerCase())).append("\"");
                    json.append(",\"count\":").append(entry.getValue()).append("}");
                }
            }
            json.append("]");
        }

        json.append("}");
    }

    private void appendAvailableActions(StringBuilder json) {
        json.append("[");

        // Get available spell abilities
        List<SpellAbility> allPlayable = new ArrayList<>();
        CardCollection available = new CardCollection(player.getCardsIn(ZoneType.Hand));
        available.addAll(player.getCardsIn(ZoneType.Battlefield));

        for (Card c : available) {
            List<SpellAbility> possible = c.getAllPossibleAbilities(player, false);
            for (SpellAbility sa : possible) {
                if (sa.canPlay()) {
                    allPlayable.add(sa);
                }
            }
        }

        boolean first = true;
        for (int i = 0; i < allPlayable.size(); i++) {
            SpellAbility sa = allPlayable.get(i);
            if (!first) json.append(",");
            first = false;
            json.append("{");
            json.append("\"index\":").append(i);
            json.append(",\"card\":\"").append(escapeString(
                sa.getHostCard() != null ? sa.getHostCard().getName() : "")).append("\"");
            json.append(",\"description\":\"").append(escapeString(
                sa.toString().length() > 100 ? sa.toString().substring(0, 100) : sa.toString())).append("\"");
            json.append(",\"is_land\":").append(sa.isLandAbility());
            json.append(",\"mana_cost\":\"").append(escapeString(
                sa.getPayCosts() != null && sa.getPayCosts().getTotalMana() != null
                    ? sa.getPayCosts().getTotalMana().toString() : "")).append("\"");
            json.append("}");
        }

        // Add pass option
        if (!first) json.append(",");
        json.append("{\"index\":-1,\"card\":\"\",\"description\":\"Pass priority\",\"is_land\":false,\"mana_cost\":\"\"}");

        json.append("]");
    }

    private String escapeString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
