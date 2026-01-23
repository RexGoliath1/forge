package forge.game;

import com.google.common.eventbus.Subscribe;
import forge.game.event.*;

/**
 * Event visitor that logs game state at key moments for RL training data.
 * Subscribes to game events and triggers state logging at decision points.
 */
public class GameStateEventVisitor {
    private final GameStateLogger logger;
    private final Game game;

    public GameStateEventVisitor(Game game) {
        this.game = game;
        this.logger = new GameStateLogger(game);
    }

    public GameStateLogger getLogger() {
        return logger;
    }

    @Subscribe
    public void onTurnBegan(GameEventTurnBegan ev) {
        logger.logState("turn_began");
    }

    @Subscribe
    public void onPhaseChanged(GameEventTurnPhase ev) {
        // Log state at main phases (most important decision points)
        String phase = ev.phase().toString();
        if (phase.contains("Main") || phase.contains("Combat") || phase.contains("End")) {
            logger.logState("phase_" + phase);
        }
    }

    @Subscribe
    public void onSpellCast(GameEventSpellAbilityCast ev) {
        String player = ev.sa().getActivatingPlayer().getName();
        String card = ev.sa().getHostCard().toString();
        String action = ev.sa().isSpell() ? "cast" : "activated";
        logger.logAction(player, action, card);
    }

    @Subscribe
    public void onLandPlayed(GameEventLandPlayed ev) {
        logger.logAction(ev.player().getName(), "play_land", ev.land().toString());
    }

    @Subscribe
    public void onAttackersDeclared(GameEventAttackersDeclared ev) {
        if (!ev.attackersMap().isEmpty()) {
            StringBuilder details = new StringBuilder();
            for (var entry : ev.attackersMap().asMap().entrySet()) {
                for (var attacker : entry.getValue()) {
                    if (details.length() > 0) details.append(", ");
                    details.append(attacker.getName()).append(" -> ").append(entry.getKey().getName());
                }
            }
            logger.logAction(ev.player().getName(), "declare_attackers", details.toString());
            logger.logState("attackers_declared");
        }
    }

    @Subscribe
    public void onBlockersDeclared(GameEventBlockersDeclared ev) {
        logger.logState("blockers_declared");
    }

    @Subscribe
    public void onCombatDamage(GameEventCombatEnded ev) {
        logger.logState("combat_ended");
    }

    @Subscribe
    public void onCardDamaged(GameEventCardDamaged ev) {
        String details = ev.source().getName() + " deals " + ev.amount() + " to " + ev.card().getName();
        logger.logAction(ev.source().getController().getName(), "damage", details);
    }

    @Subscribe
    public void onPlayerDamaged(GameEventPlayerDamaged ev) {
        String details = ev.source().getName() + " deals " + ev.amount() + " to " + ev.target().getName();
        logger.logAction(ev.source().getController().getName(), "damage_player", details);
    }

    @Subscribe
    public void onGameOutcome(GameEventGameOutcome ev) {
        logger.logGameEnd();
    }
}
