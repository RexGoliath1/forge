package forge.game;

import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.io.PrintStream;
import java.util.*;

/**
 * GameStateLogger captures game state for reinforcement learning training.
 * Outputs JSON Lines format (one JSON object per line) for easy parsing.
 */
public class GameStateLogger {
    private final PrintStream output;
    private final Game game;
    private int stateCounter = 0;

    public GameStateLogger(Game game) {
        this(game, System.out);
    }

    public GameStateLogger(Game game, PrintStream output) {
        this.game = game;
        this.output = output;
    }

    /**
     * Log the current game state.
     * Call this at decision points (phase changes, priority passes).
     */
    public void logState(String eventType) {
        StringBuilder json = new StringBuilder();
        json.append("{");

        // Metadata
        appendString(json, "event", eventType);
        json.append(",");
        appendNumber(json, "state_id", stateCounter++);
        json.append(",");
        appendNumber(json, "game_id", game.getId());
        json.append(",");
        appendNumber(json, "timestamp", System.currentTimeMillis());
        json.append(",");

        // Game state
        appendNumber(json, "turn", game.getPhaseHandler().getTurn());
        json.append(",");
        appendString(json, "phase", getPhaseString());
        json.append(",");
        appendString(json, "active_player", getActivePlayerName());
        json.append(",");
        appendString(json, "priority_player", getPriorityPlayerName());
        json.append(",");
        appendBoolean(json, "is_game_over", game.isGameOver());
        json.append(",");

        // Player states
        json.append("\"players\":[");
        boolean first = true;
        for (Player p : game.getRegisteredPlayers()) {
            if (!first) json.append(",");
            first = false;
            appendPlayerState(json, p);
        }
        json.append("],");

        // Stack
        appendStackState(json);

        // Combat info if in combat
        if (game.getCombat() != null) {
            json.append(",");
            appendCombatState(json);
        }

        json.append("}");

        output.println("GAMESTATE:" + json.toString());
    }

    /**
     * Log a specific action taken by a player.
     */
    public void logAction(String playerName, String actionType, String actionDetails) {
        StringBuilder json = new StringBuilder();
        json.append("{");

        appendString(json, "event", "action");
        json.append(",");
        appendNumber(json, "state_id", stateCounter);
        json.append(",");
        appendNumber(json, "game_id", game.getId());
        json.append(",");
        appendNumber(json, "timestamp", System.currentTimeMillis());
        json.append(",");
        appendNumber(json, "turn", game.getPhaseHandler().getTurn());
        json.append(",");
        appendString(json, "phase", getPhaseString());
        json.append(",");
        appendString(json, "player", playerName);
        json.append(",");
        appendString(json, "action_type", actionType);
        json.append(",");
        appendString(json, "action_details", actionDetails);

        json.append("}");

        output.println("GAMESTATE:" + json.toString());
    }

    /**
     * Log game outcome.
     */
    public void logGameEnd() {
        StringBuilder json = new StringBuilder();
        json.append("{");

        appendString(json, "event", "game_end");
        json.append(",");
        appendNumber(json, "game_id", game.getId());
        json.append(",");
        appendNumber(json, "timestamp", System.currentTimeMillis());
        json.append(",");
        appendNumber(json, "turns_played", game.getPhaseHandler().getTurn());

        if (game.getOutcome() != null) {
            json.append(",");
            appendBoolean(json, "is_draw", game.getOutcome().isDraw());
            if (!game.getOutcome().isDraw()) {
                json.append(",");
                appendString(json, "winner", game.getOutcome().getWinningLobbyPlayer().getName());
            }
            json.append(",");
            appendString(json, "end_reason", game.getOutcome().getWinCondition().toString());
        }

        // Final player states
        json.append(",\"players\":[");
        boolean first = true;
        for (Player p : game.getRegisteredPlayers()) {
            if (!first) json.append(",");
            first = false;
            json.append("{");
            appendString(json, "name", p.getName());
            json.append(",");
            appendNumber(json, "life", p.getLife());
            json.append(",");
            appendBoolean(json, "has_lost", p.hasLost());
            json.append("}");
        }
        json.append("]");

        json.append("}");

        output.println("GAMESTATE:" + json.toString());
    }

    private void appendPlayerState(StringBuilder json, Player p) {
        json.append("{");

        appendString(json, "name", p.getName());
        json.append(",");
        appendNumber(json, "life", p.getLife());
        json.append(",");
        appendNumber(json, "poison", p.getPoisonCounters());
        json.append(",");
        appendBoolean(json, "has_lost", p.hasLost());
        json.append(",");
        appendNumber(json, "lands_played_this_turn", p.getLandsPlayedThisTurn());
        json.append(",");
        appendNumber(json, "max_land_plays", p.getMaxLandPlays());
        json.append(",");

        // Hand
        appendNumber(json, "hand_size", p.getCardsIn(ZoneType.Hand).size());
        json.append(",");
        appendCardArray(json, "hand", p.getCardsIn(ZoneType.Hand));
        json.append(",");

        // Library size (don't expose contents)
        appendNumber(json, "library_size", p.getCardsIn(ZoneType.Library).size());
        json.append(",");

        // Graveyard
        appendCardArray(json, "graveyard", p.getCardsIn(ZoneType.Graveyard));
        json.append(",");

        // Battlefield
        appendBattlefieldState(json, p);
        json.append(",");

        // Exile
        appendCardArray(json, "exile", p.getCardsIn(ZoneType.Exile));
        json.append(",");

        // Mana pool
        appendString(json, "mana_pool", p.getManaPool().toString());

        json.append("}");
    }

    private void appendBattlefieldState(StringBuilder json, Player p) {
        json.append("\"battlefield\":[");
        boolean first = true;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (!first) json.append(",");
            first = false;

            json.append("{");
            appendNumber(json, "id", c.getId());
            json.append(",");
            appendString(json, "name", c.getName());
            json.append(",");
            appendBoolean(json, "tapped", c.isTapped());
            json.append(",");
            appendBoolean(json, "summoning_sick", c.hasSickness());
            json.append(",");

            // Card types
            appendBoolean(json, "is_creature", c.isCreature());
            json.append(",");
            appendBoolean(json, "is_land", c.isLand());
            json.append(",");
            appendBoolean(json, "is_artifact", c.isArtifact());
            json.append(",");
            appendBoolean(json, "is_enchantment", c.isEnchantment());
            json.append(",");
            appendBoolean(json, "is_planeswalker", c.isPlaneswalker());

            // Combat stats for creatures
            if (c.isCreature()) {
                json.append(",");
                appendNumber(json, "power", c.getNetPower());
                json.append(",");
                appendNumber(json, "toughness", c.getNetToughness());
                json.append(",");
                appendNumber(json, "damage", c.getDamage());
            }

            // Counters
            if (c.hasCounters()) {
                json.append(",");
                appendString(json, "counters", c.getCounters().toString());
            }

            // Attached cards (equipment, auras)
            if (c.hasCardAttachments()) {
                json.append(",");
                appendCardArray(json, "attachments", c.getAttachedCards());
            }

            json.append("}");
        }
        json.append("]");
    }

    private void appendStackState(StringBuilder json) {
        json.append("\"stack\":[");
        boolean first = true;
        for (var entry : game.getStack()) {
            if (!first) json.append(",");
            first = false;

            json.append("{");
            appendNumber(json, "id", entry.getId());
            json.append(",");
            appendString(json, "description", entry.getStackDescription());
            json.append(",");
            appendString(json, "controller", entry.getActivatingPlayer().getName());
            if (entry.getSourceCard() != null) {
                json.append(",");
                appendString(json, "source_card", entry.getSourceCard().getName());
            }
            json.append("}");
        }
        json.append("]");
    }

    private void appendCombatState(StringBuilder json) {
        json.append("\"combat\":{");
        var gameCombat = game.getCombat();

        if (gameCombat.getAttackingPlayer() != null) {
            appendString(json, "attacking_player", gameCombat.getAttackingPlayer().getName());
        }

        // Attackers
        json.append(",\"attackers\":[");
        boolean first = true;
        for (Card attacker : gameCombat.getAttackers()) {
            if (!first) json.append(",");
            first = false;

            json.append("{");
            appendNumber(json, "card_id", attacker.getId());
            json.append(",");
            appendString(json, "name", attacker.getName());
            json.append(",");
            appendNumber(json, "power", attacker.getNetPower());
            json.append(",");
            appendNumber(json, "toughness", attacker.getNetToughness());

            // What it's attacking
            var defended = gameCombat.getDefenderByAttacker(attacker);
            if (defended instanceof Player) {
                json.append(",");
                appendString(json, "attacking", ((Player) defended).getName());
            } else if (defended instanceof Card) {
                json.append(",");
                appendString(json, "attacking_planeswalker", ((Card) defended).getName());
            }

            // Blockers assigned to this attacker
            var blockers = gameCombat.getBlockers(attacker);
            if (blockers != null && !blockers.isEmpty()) {
                json.append(",\"blocked_by\":[");
                boolean bFirst = true;
                for (Card blocker : blockers) {
                    if (!bFirst) json.append(",");
                    bFirst = false;
                    json.append("\"").append(escapeString(blocker.getName())).append("\"");
                }
                json.append("]");
            }

            json.append("}");
        }
        json.append("]");

        json.append("}");
    }

    private void appendCardArray(StringBuilder json, String key, CardCollectionView cards) {
        json.append("\"").append(key).append("\":[");
        boolean first = true;
        for (Card c : cards) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(escapeString(c.getName())).append("\"");
        }
        json.append("]");
    }

    private void appendString(StringBuilder json, String key, String value) {
        json.append("\"").append(key).append("\":\"").append(escapeString(value)).append("\"");
    }

    private void appendNumber(StringBuilder json, String key, long value) {
        json.append("\"").append(key).append("\":").append(value);
    }

    private void appendBoolean(StringBuilder json, String key, boolean value) {
        json.append("\"").append(key).append("\":").append(value);
    }

    private String escapeString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String getPhaseString() {
        PhaseType phase = game.getPhaseHandler().getPhase();
        return phase != null ? phase.toString() : "UNKNOWN";
    }

    private String getActivePlayerName() {
        Player p = game.getPhaseHandler().getPlayerTurn();
        return p != null ? p.getName() : "UNKNOWN";
    }

    private String getPriorityPlayerName() {
        Player p = game.getPhaseHandler().getPriorityPlayer();
        return p != null ? p.getName() : "UNKNOWN";
    }
}
