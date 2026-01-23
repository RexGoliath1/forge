package forge.view;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import forge.LobbyPlayer;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameType;
import forge.game.GameStateLogger;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardState;
import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerController;
import forge.game.player.PlayerView;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.game.PlanarDice;
import forge.item.PaperCard;
import forge.util.ITriggerEvent;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * PlayerController that communicates via stdin/stdout for external agent control.
 * Outputs decision requests as JSON, reads responses as JSON.
 */
public class PlayerControllerExternal extends PlayerController {
    private final BufferedReader input;
    private final PrintStream output;
    private final GameStateLogger stateLogger;
    private int decisionId = 0;

    public PlayerControllerExternal(Game game, Player p, LobbyPlayer lp) {
        this(game, p, lp, new BufferedReader(new InputStreamReader(System.in)), System.out);
    }

    public PlayerControllerExternal(Game game, Player p, LobbyPlayer lp, BufferedReader input, PrintStream output) {
        super(game, p, lp);
        this.input = input;
        this.output = output;
        this.stateLogger = new GameStateLogger(game, output);
    }

    private String readResponse() {
        try {
            String line = input.readLine();
            if (line == null) {
                throw new RuntimeException("End of input stream - external agent disconnected");
            }
            return line.trim();
        } catch (IOException e) {
            throw new RuntimeException("Error reading from external agent: " + e.getMessage(), e);
        }
    }

    private int parseIntResponse(String response, int min, int max) {
        try {
            int value = Integer.parseInt(response);
            if (value < min || value > max) {
                output.println("ERROR: Value " + value + " out of range [" + min + ", " + max + "]");
                return min;
            }
            return value;
        } catch (NumberFormatException e) {
            output.println("ERROR: Invalid number: " + response);
            return min;
        }
    }

    private void outputDecision(String decisionType, Map<String, Object> data) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"decision_type\":\"").append(decisionType).append("\"");
        json.append(",\"decision_id\":").append(decisionId++);
        json.append(",\"player\":\"").append(escapeString(player.getName())).append("\"");
        json.append(",\"turn\":").append(getGame().getPhaseHandler().getTurn());
        json.append(",\"phase\":\"").append(getGame().getPhaseHandler().getPhase()).append("\"");

        // Include full game state for RL training
        json.append(",\"game_state\":");
        appendGameState(json);

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            json.append(",");
            appendValue(json, entry.getKey(), entry.getValue());
        }

        json.append("}");
        output.println("DECISION:" + json.toString());
        output.flush();
    }

    private void appendGameState(StringBuilder json) {
        json.append("{");

        // Game metadata
        json.append("\"is_game_over\":").append(getGame().isGameOver());
        json.append(",\"active_player\":\"").append(escapeString(getActivePlayerName())).append("\"");
        json.append(",\"priority_player\":\"").append(escapeString(getPriorityPlayerName())).append("\"");

        // All players' states
        json.append(",\"players\":[");
        boolean first = true;
        for (Player p : getGame().getRegisteredPlayers()) {
            if (!first) json.append(",");
            first = false;
            appendPlayerState(json, p);
        }
        json.append("]");

        // Stack state
        json.append(",");
        appendStackState(json);

        // Combat state if in combat
        if (getGame().getCombat() != null && !getGame().getCombat().getAttackers().isEmpty()) {
            json.append(",");
            appendCombatState(json);
        }

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

        // Hand (full visibility for training)
        json.append(",\"hand_size\":").append(p.getCardsIn(ZoneType.Hand).size());
        json.append(",\"hand\":[");
        boolean first = true;
        for (Card c : p.getCardsIn(ZoneType.Hand)) {
            if (!first) json.append(",");
            first = false;
            appendCardDetails(json, c);
        }
        json.append("]");

        // Library size only (hidden info)
        json.append(",\"library_size\":").append(p.getCardsIn(ZoneType.Library).size());

        // Graveyard
        json.append(",\"graveyard\":[");
        first = true;
        for (Card c : p.getCardsIn(ZoneType.Graveyard)) {
            if (!first) json.append(",");
            first = false;
            appendCardDetails(json, c);
        }
        json.append("]");

        // Battlefield
        json.append(",\"battlefield\":[");
        first = true;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (!first) json.append(",");
            first = false;
            appendBattlefieldCard(json, c);
        }
        json.append("]");

        // Exile
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
        json.append(",\"white\":").append(p.getManaPool().getAmountOfColor(forge.card.MagicColor.WHITE));
        json.append(",\"blue\":").append(p.getManaPool().getAmountOfColor(forge.card.MagicColor.BLUE));
        json.append(",\"black\":").append(p.getManaPool().getAmountOfColor(forge.card.MagicColor.BLACK));
        json.append(",\"red\":").append(p.getManaPool().getAmountOfColor(forge.card.MagicColor.RED));
        json.append(",\"green\":").append(p.getManaPool().getAmountOfColor(forge.card.MagicColor.GREEN));
        json.append(",\"colorless\":").append(p.getManaPool().getAmountOfColor(forge.card.MagicColor.COLORLESS));
        json.append("}");

        json.append("}");
    }

    private void appendCardDetails(StringBuilder json, Card c) {
        json.append("{");
        json.append("\"id\":").append(c.getId());
        json.append(",\"name\":\"").append(escapeString(c.getName())).append("\"");
        json.append(",\"mana_cost\":\"").append(escapeString(c.getManaCost() != null ? c.getManaCost().toString() : "")).append("\"");
        json.append(",\"cmc\":").append(c.getCMC());
        json.append(",\"types\":\"").append(escapeString(c.getType().toString())).append("\"");
        json.append(",\"oracle_text\":\"").append(escapeString(c.getOracleText() != null ? c.getOracleText() : "")).append("\"");
        if (c.isCreature()) {
            json.append(",\"power\":").append(c.getNetPower());
            json.append(",\"toughness\":").append(c.getNetToughness());
        }
        // Include keywords
        if (!c.getKeywords().isEmpty()) {
            json.append(",\"keywords\":[");
            boolean first = true;
            for (var kw : c.getKeywords()) {
                if (!first) json.append(",");
                first = false;
                json.append("\"").append(escapeString(kw.toString())).append("\"");
            }
            json.append("]");
        }
        json.append("}");
    }

    private void appendBattlefieldCard(StringBuilder json, Card c) {
        json.append("{");
        json.append("\"id\":").append(c.getId());
        json.append(",\"name\":\"").append(escapeString(c.getName())).append("\"");
        json.append(",\"mana_cost\":\"").append(escapeString(c.getManaCost() != null ? c.getManaCost().toString() : "")).append("\"");
        json.append(",\"cmc\":").append(c.getCMC());
        json.append(",\"types\":\"").append(escapeString(c.getType().toString())).append("\"");
        json.append(",\"oracle_text\":\"").append(escapeString(c.getOracleText() != null ? c.getOracleText() : "")).append("\"");
        json.append(",\"tapped\":").append(c.isTapped());
        json.append(",\"summoning_sick\":").append(c.hasSickness());
        json.append(",\"is_creature\":").append(c.isCreature());
        json.append(",\"is_land\":").append(c.isLand());
        json.append(",\"is_artifact\":").append(c.isArtifact());
        json.append(",\"is_enchantment\":").append(c.isEnchantment());
        json.append(",\"is_planeswalker\":").append(c.isPlaneswalker());

        if (c.isCreature()) {
            json.append(",\"power\":").append(c.getNetPower());
            json.append(",\"toughness\":").append(c.getNetToughness());
            json.append(",\"damage\":").append(c.getDamage());
        }

        if (c.isPlaneswalker()) {
            json.append(",\"loyalty\":").append(c.getCurrentLoyalty());
        }

        if (c.hasCounters()) {
            json.append(",\"counters\":\"").append(escapeString(c.getCounters().toString())).append("\"");
        }

        // Keywords (flying, trample, etc.)
        if (!c.getKeywords().isEmpty()) {
            json.append(",\"keywords\":[");
            boolean first = true;
            for (var kw : c.getKeywords()) {
                if (!first) json.append(",");
                first = false;
                json.append("\"").append(escapeString(kw.toString())).append("\"");
            }
            json.append("]");
        }

        json.append("}");
    }

    private void appendStackState(StringBuilder json) {
        json.append("\"stack\":[");
        boolean first = true;
        for (var entry : getGame().getStack()) {
            if (!first) json.append(",");
            first = false;

            json.append("{");
            json.append("\"id\":").append(entry.getId());
            json.append(",\"description\":\"").append(escapeString(entry.getStackDescription())).append("\"");
            json.append(",\"controller\":\"").append(escapeString(entry.getActivatingPlayer().getName())).append("\"");
            if (entry.getSourceCard() != null) {
                json.append(",\"source_card\":\"").append(escapeString(entry.getSourceCard().getName())).append("\"");
                json.append(",\"source_card_id\":").append(entry.getSourceCard().getId());
            }
            json.append("}");
        }
        json.append("]");
    }

    private void appendCombatState(StringBuilder json) {
        json.append("\"combat\":{");
        var gameCombat = getGame().getCombat();

        if (gameCombat.getAttackingPlayer() != null) {
            json.append("\"attacking_player\":\"").append(escapeString(gameCombat.getAttackingPlayer().getName())).append("\"");
        }

        // Attackers
        json.append(",\"attackers\":[");
        boolean first = true;
        for (Card attacker : gameCombat.getAttackers()) {
            if (!first) json.append(",");
            first = false;

            json.append("{");
            json.append("\"card_id\":").append(attacker.getId());
            json.append(",\"name\":\"").append(escapeString(attacker.getName())).append("\"");
            json.append(",\"power\":").append(attacker.getNetPower());
            json.append(",\"toughness\":").append(attacker.getNetToughness());

            var defended = gameCombat.getDefenderByAttacker(attacker);
            if (defended instanceof Player) {
                json.append(",\"attacking\":\"").append(escapeString(((Player) defended).getName())).append("\"");
            } else if (defended instanceof Card) {
                json.append(",\"attacking_planeswalker\":\"").append(escapeString(((Card) defended).getName())).append("\"");
            }

            var blockers = gameCombat.getBlockers(attacker);
            if (blockers != null && !blockers.isEmpty()) {
                json.append(",\"blocked_by\":[");
                boolean bFirst = true;
                for (Card blocker : blockers) {
                    if (!bFirst) json.append(",");
                    bFirst = false;
                    json.append("{\"card_id\":").append(blocker.getId());
                    json.append(",\"name\":\"").append(escapeString(blocker.getName())).append("\"}");
                }
                json.append("]");
            }

            json.append("}");
        }
        json.append("]");

        json.append("}");
    }

    private String getActivePlayerName() {
        Player p = getGame().getPhaseHandler().getPlayerTurn();
        return p != null ? p.getName() : "UNKNOWN";
    }

    private String getPriorityPlayerName() {
        Player p = getGame().getPhaseHandler().getPriorityPlayer();
        return p != null ? p.getName() : "UNKNOWN";
    }

    private void appendValue(StringBuilder json, String key, Object value) {
        json.append("\"").append(key).append("\":");
        if (value == null) {
            json.append("null");
        } else if (value instanceof Number) {
            json.append(value);
        } else if (value instanceof Boolean) {
            json.append(value);
        } else if (value instanceof List) {
            json.append("[");
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) json.append(",");
                first = false;
                if (item instanceof Map) {
                    appendMap(json, (Map<String, Object>) item);
                } else {
                    json.append("\"").append(escapeString(item.toString())).append("\"");
                }
            }
            json.append("]");
        } else {
            json.append("\"").append(escapeString(value.toString())).append("\"");
        }
    }

    private void appendMap(StringBuilder json, Map<String, Object> map) {
        json.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) json.append(",");
            first = false;
            appendValue(json, entry.getKey(), entry.getValue());
        }
        json.append("}");
    }

    private String escapeString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ========== Combat Decisions ==========

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        // Get all creatures that can attack
        CardCollection possibleAttackers = new CardCollection();
        for (Card c : attacker.getCreaturesInPlay()) {
            if (CombatUtil.canAttack(c)) {
                possibleAttackers.add(c);
            }
        }

        if (possibleAttackers.isEmpty()) {
            return;
        }

        // Build options list
        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < possibleAttackers.size(); i++) {
            Card c = possibleAttackers.get(i);
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("index", i);
            option.put("card_id", c.getId());
            option.put("name", c.getName());
            option.put("power", c.getNetPower());
            option.put("toughness", c.getNetToughness());
            options.add(option);
        }

        // Get possible defenders
        List<Map<String, Object>> defenders = new ArrayList<>();
        for (GameEntity defender : combat.getDefenders()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", defender.getName());
            if (defender instanceof Player) {
                d.put("type", "player");
                d.put("life", ((Player) defender).getLife());
            } else {
                d.put("type", "planeswalker");
            }
            defenders.add(d);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("attackers", options);
        data.put("defenders", defenders);
        data.put("message", "Choose attackers (comma-separated indices, or empty for none)");

        outputDecision("declare_attackers", data);
        String response = readResponse();

        if (response.isEmpty() || response.equals("none") || response.equals("[]")) {
            return;
        }

        // Parse response - expects comma-separated indices or JSON array
        String[] indices = response.replace("[", "").replace("]", "").split(",");
        for (String indexStr : indices) {
            try {
                int idx = Integer.parseInt(indexStr.trim());
                if (idx >= 0 && idx < possibleAttackers.size()) {
                    Card attackingCreature = possibleAttackers.get(idx);
                    // Attack first defender (player) by default
                    GameEntity defender = combat.getDefenders().iterator().next();
                    combat.addAttacker(attackingCreature, defender);
                }
            } catch (NumberFormatException e) {
                // Ignore invalid indices
            }
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        CardCollection possibleBlockers = defender.getCreaturesInPlay();
        List<Card> attackers = combat.getAttackers();

        if (possibleBlockers.isEmpty() || attackers.isEmpty()) {
            return;
        }

        // Build blocker options
        List<Map<String, Object>> blockerOptions = new ArrayList<>();
        for (int i = 0; i < possibleBlockers.size(); i++) {
            Card c = possibleBlockers.get(i);
            if (CombatUtil.canBlock(c, combat)) {
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("index", i);
                option.put("card_id", c.getId());
                option.put("name", c.getName());
                option.put("power", c.getNetPower());
                option.put("toughness", c.getNetToughness());
                blockerOptions.add(option);
            }
        }

        // Build attacker list
        List<Map<String, Object>> attackerList = new ArrayList<>();
        for (int i = 0; i < attackers.size(); i++) {
            Card a = attackers.get(i);
            Map<String, Object> attacker = new LinkedHashMap<>();
            attacker.put("index", i);
            attacker.put("card_id", a.getId());
            attacker.put("name", a.getName());
            attacker.put("power", a.getNetPower());
            attacker.put("toughness", a.getNetToughness());
            attackerList.add(attacker);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("blockers", blockerOptions);
        data.put("attackers", attackerList);
        data.put("message", "Assign blockers as blocker_idx:attacker_idx pairs (e.g., '0:0,1:0' or empty for none)");

        outputDecision("declare_blockers", data);
        String response = readResponse();

        if (response.isEmpty() || response.equals("none") || response.equals("[]")) {
            return;
        }

        // Parse response - expects pairs like "blocker_idx:attacker_idx,..."
        String[] pairs = response.replace("[", "").replace("]", "").split(",");
        for (String pair : pairs) {
            String[] parts = pair.trim().split(":");
            if (parts.length == 2) {
                try {
                    int blockerIdx = Integer.parseInt(parts[0].trim());
                    int attackerIdx = Integer.parseInt(parts[1].trim());
                    if (blockerIdx >= 0 && blockerIdx < possibleBlockers.size() &&
                        attackerIdx >= 0 && attackerIdx < attackers.size()) {
                        combat.addBlocker(attackers.get(attackerIdx), possibleBlockers.get(blockerIdx));
                    }
                } catch (NumberFormatException e) {
                    // Ignore invalid pairs
                }
            }
        }
    }

    // ========== Spell/Ability Decisions ==========

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) {
            return null;
        }
        if (abilities.size() == 1) {
            return abilities.get(0);
        }

        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < abilities.size(); i++) {
            SpellAbility sa = abilities.get(i);
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("index", i);
            option.put("description", sa.toString());
            option.put("mana_cost", sa.getPayCosts() != null ? sa.getPayCosts().getTotalMana().toString() : "");
            options.add(option);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("card", hostCard.getName());
        data.put("abilities", options);
        data.put("message", "Choose ability to play (index)");

        outputDecision("choose_ability", data);
        String response = readResponse();

        int idx = parseIntResponse(response, 0, abilities.size() - 1);
        return abilities.get(idx);
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean mayChoseNewTargets) {
        // Auto-play - no choice needed
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        // Play in order for simplicity
        for (SpellAbility sa : activePlayerSAs) {
            // Auto-play
        }
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        if (isMandatory) {
            return true;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("trigger_card", host.getName());
        data.put("trigger", wrapperAbility.toString());
        data.put("message", "Play trigger? (y/n)");

        outputDecision("play_trigger", data);
        String response = readResponse().toLowerCase();

        return response.startsWith("y") || response.equals("1") || response.equals("true");
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("spell", tgtSA.toString());
        data.put("message", "Play spell from effect? (y/n)");

        outputDecision("play_from_effect", data);
        String response = readResponse().toLowerCase();

        return response.startsWith("y") || response.equals("1") || response.equals("true");
    }

    // ========== Card Selection ==========

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        if (sourceList == null || sourceList.isEmpty()) {
            return new CardCollection();
        }

        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < sourceList.size(); i++) {
            Card c = sourceList.get(i);
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("index", i);
            option.put("card_id", c.getId());
            option.put("name", c.getName());
            options.add(option);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", title);
        data.put("cards", options);
        data.put("min", min);
        data.put("max", max);
        data.put("optional", isOptional);
        data.put("message", "Choose " + min + "-" + max + " cards (comma-separated indices)");

        outputDecision("choose_cards", data);
        String response = readResponse();

        CardCollection result = new CardCollection();
        if (response.isEmpty() || response.equals("none") || response.equals("[]")) {
            if (isOptional || min == 0) {
                return result;
            }
        }

        String[] indices = response.replace("[", "").replace("]", "").split(",");
        for (String indexStr : indices) {
            try {
                int idx = Integer.parseInt(indexStr.trim());
                if (idx >= 0 && idx < sourceList.size() && result.size() < max) {
                    result.add(sourceList.get(idx));
                }
            } catch (NumberFormatException e) {
                // Ignore
            }
        }

        // Fill with first cards if min not met
        while (result.size() < min && result.size() < sourceList.size()) {
            for (Card c : sourceList) {
                if (!result.contains(c)) {
                    result.add(c);
                    break;
                }
            }
        }

        return result;
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        // Simplify - just choose from all
        CardCollection all = new CardCollection();
        for (CardCollection cc : validMap.values()) {
            all.addAll(cc);
        }
        return (CardCollection) chooseCardsForEffect(all, sa, title, 0, all.size(), isOptional, null);
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player relatedPlayer, Map<String, Object> params) {
        if (optionList == null || optionList.isEmpty()) {
            return null;
        }
        if (optionList.size() == 1 && !isOptional) {
            return optionList.getFirst();
        }

        List<Map<String, Object>> options = new ArrayList<>();
        int i = 0;
        for (T entity : optionList) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("index", i++);
            option.put("name", entity.getName());
            if (entity instanceof Card) {
                option.put("card_id", ((Card) entity).getId());
            }
            options.add(option);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", title);
        data.put("entities", options);
        data.put("optional", isOptional);
        data.put("message", "Choose entity (index" + (isOptional ? " or -1 for none" : "") + ")");

        outputDecision("choose_entity", data);
        String response = readResponse();

        if (isOptional && (response.equals("-1") || response.isEmpty() || response.equals("none"))) {
            return null;
        }

        int idx = parseIntResponse(response, 0, optionList.size() - 1);
        return optionList.get(idx);
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player relatedPlayer, Map<String, Object> params) {
        List<T> result = new ArrayList<>();
        if (optionList == null || optionList.isEmpty()) {
            return result;
        }

        // Simplified - choose first N
        int count = 0;
        for (T entity : optionList) {
            if (count >= min) break;
            result.add(entity);
            count++;
        }
        return result;
    }

    // ========== Confirmation Decisions ==========

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", mode.toString());
        data.put("message", message);
        if (cardToShow != null) {
            data.put("card", cardToShow.getName());
        }
        data.put("message", "Confirm action? (y/n)");

        outputDecision("confirm_action", data);
        String response = readResponse().toLowerCase();

        return response.startsWith("y") || response.equals("1") || response.equals("true");
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, int bid, Player winner) {
        return true; // Auto-confirm
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect re, SpellAbility effectSA, GameEntity affected, String question) {
        return true; // Auto-confirm
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) {
        return true; // Auto-confirm
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        return true; // Auto-confirm
    }

    // ========== Other Required Methods (simplified implementations) ==========

    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        return null;
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        return losses;
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        Map<Card, Integer> result = new HashMap<>();
        if (blockers.isEmpty()) {
            return result;
        }
        // Assign all damage to first blocker
        result.put(blockers.getFirst(), damageDealt);
        return result;
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        return affected;
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        Map<Byte, Integer> result = new HashMap<>();
        // Default to colorless
        result.put((byte) 0, manaAmount);
        return result;
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return chooseCardsForEffect(validTargets, sa, message, min, max, min == 0, null);
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return chooseCardsForEffect(validTargets, sa, message, min, max, min == 0, null);
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, String announce) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ability", ability.toString());
        data.put("announce", announce);
        data.put("message", "Enter value for " + announce);

        outputDecision("announce_value", data);
        String response = readResponse();

        return parseIntResponse(response, 0, Integer.MAX_VALUE);
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        return null; // Keep original targets
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        return currentAbility.getTargetRestrictions() == null;
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        if (allTargets.isEmpty()) return null;
        return allTargets.get(0);
    }

    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        return false;
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) {
        return optionList.isEmpty() ? null : optionList.getFirst();
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num, Map<String, Object> params) {
        List<SpellAbility> result = new ArrayList<>();
        for (int i = 0; i < Math.min(num, spells.size()); i++) {
            result.add(spells.get(i));
        }
        return result;
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        if (spells.isEmpty()) return null;
        return spells.get(0);
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        return new ArrayList<>();
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return new ArrayList<>();
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return blockers;
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        CardCollection result = new CardCollection(oldBlockers);
        result.add(blocker);
        return result;
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return attackers;
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addMsgSuffix) {
        // Output revealed cards
        List<String> cardNames = new ArrayList<>();
        for (Card c : cards) {
            cardNames.add(c.getName());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("zone", zone.toString());
        data.put("owner", owner.getName());
        data.put("cards", cardNames);
        outputDecision("reveal", data);
    }

    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix, boolean addMsgSuffix) {
        // Output revealed cards
    }

    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject relatedTarget, String value) {
        // Output notification
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        // Put all on bottom
        return ImmutablePair.of(new CardCollection(), topN);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        // Put all in graveyard
        return ImmutablePair.of(new CardCollection(), topN);
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        return true;
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        return cards;
    }

    @Override
    public void autoPassCancel() {}

    @Override
    public void awaitNextInput() {}

    @Override
    public void cancelAwaitNextInput() {}

    // ========== Zone Change Methods ==========

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        if (fetchList == null || fetchList.isEmpty()) {
            return null;
        }
        return fetchList.getFirst();
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        if (fetchList == null || fetchList.isEmpty()) {
            return new ArrayList<>();
        }
        List<Card> result = new ArrayList<>();
        for (int i = 0; i < Math.min(min, fetchList.size()); i++) {
            result.add(fetchList.get(i));
        }
        return result;
    }

    // ========== Discard Methods ==========

    @Override
    public CardCollectionView chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max) {
        return chooseCardsForEffect(validCards, sa, "Discard", min, max, min == 0, null);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand, String param, SpellAbility sa) {
        CardCollection result = new CardCollection();
        for (int i = 0; i < Math.min(min, hand.size()); i++) {
            result.add(hand.get(i));
        }
        return result;
    }

    @Override
    public CardCollection chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));
        CardCollection result = new CardCollection();
        for (int i = 0; i < Math.min(numDiscard, hand.size()); i++) {
            result.add(hand.get(i));
        }
        return result;
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        return new CardCollection();
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost,
            CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        return new HashMap<>();
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return new ArrayList<>();
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        CardCollection result = new CardCollection();
        for (int i = 0; i < Math.min(min, valid.size()); i++) {
            result.add(valid.get(i));
        }
        return result;
    }

    // ========== Game Start Methods ==========

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        return new ArrayList<>();
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        return player;
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        return zones.isEmpty() ? null : zones.get(0);
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        return manaChoices.isEmpty() ? null : manaChoices.get(0);
    }

    // ========== Type/Color Choice Methods ==========

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        return validTypes.isEmpty() ? "" : validTypes.iterator().next();
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        return sectors.isEmpty() ? "Alpha" : sectors.get(0);
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        return new ArrayList<>();
    }

    @Override
    public int chooseSprocket(Card assignee, boolean forceDifferent) {
        return 1;
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        return colors.hasWhite() ? (byte) 1 : colors.getColor();
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        return colors.getColor();
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        return options;
    }

    // ========== Dice/Roll Methods ==========

    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) {
        return rolls.isEmpty() ? null : rolls.get(0);
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        return rolls.isEmpty() ? null : rolls.get(0);
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        return new ArrayList<>();
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        return rolls.isEmpty() ? null : rolls.get(0);
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        return rolls.isEmpty() ? null : rolls.get(0);
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness) {
        return swapChoices.isEmpty() ? "" : swapChoices.get(0);
    }

    // ========== Voting Methods ==========

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        return options.isEmpty() ? null : options.get(0);
    }

    // ========== Mulligan Methods ==========

    @Override
    public boolean mulliganKeepHand(Player player, int cardsToReturn) {
        return true;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(Player mulliganingPlayer, int cardsToReturn) {
        CardCollection hand = new CardCollection(mulliganingPlayer.getCardsIn(ZoneType.Hand));
        CardCollection result = new CardCollection();
        for (int i = 0; i < Math.min(cardsToReturn, hand.size()); i++) {
            result.add(hand.get(i));
        }
        return result;
    }

    @Override
    public boolean confirmMulliganScry(Player p) {
        return true;
    }

    // ========== Spell/Ability Play Methods ==========

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        // Collect all playable spell abilities from all available cards
        List<SpellAbility> allPlayable = new ArrayList<>();

        // Cards in hand
        CardCollection available = new CardCollection(player.getCardsIn(ZoneType.Hand));
        // Cards on battlefield (for activated abilities)
        available.addAll(player.getCardsIn(ZoneType.Battlefield));
        // Graveyard (for flashback, etc.)
        available.addAll(player.getCardsIn(ZoneType.Graveyard));

        for (Card c : available) {
            List<SpellAbility> possible = c.getAllPossibleAbilities(player, false);
            for (SpellAbility sa : possible) {
                if (sa.canPlay()) {
                    allPlayable.add(sa);
                }
            }
        }

        // Also check for special actions like land plays
        CardCollection lands = CardLists.filter(player.getCardsIn(ZoneType.Hand),
            card -> card.isLand() && player.canPlayLand(card, false, null));
        for (Card land : lands) {
            SpellAbility landAbility = land.getFirstSpellAbility();
            if (landAbility != null && !allPlayable.contains(landAbility)) {
                allPlayable.add(landAbility);
            }
        }

        if (allPlayable.isEmpty()) {
            // No actions available - just pass (return null to signal pass to PhaseHandler)
            return null;
        }

        // Build options list for external agent
        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < allPlayable.size(); i++) {
            SpellAbility sa = allPlayable.get(i);
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("index", i);
            option.put("description", sa.toString());
            option.put("card", sa.getHostCard() != null ? sa.getHostCard().getName() : "");
            option.put("card_id", sa.getHostCard() != null ? sa.getHostCard().getId() : -1);
            option.put("mana_cost", sa.getPayCosts() != null && sa.getPayCosts().getTotalMana() != null
                ? sa.getPayCosts().getTotalMana().toString() : "");
            option.put("is_land", sa.getHostCard() != null && sa.getHostCard().isLand());
            options.add(option);
        }

        // Add pass option
        Map<String, Object> passOption = new LinkedHashMap<>();
        passOption.put("index", -1);
        passOption.put("description", "Pass priority");
        passOption.put("card", "");
        passOption.put("card_id", -1);
        passOption.put("mana_cost", "");
        passOption.put("is_land", false);
        options.add(passOption);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actions", options);
        data.put("message", "Choose action to play (index, or -1 to pass)");

        outputDecision("choose_action", data);
        String response = readResponse();

        if (response.isEmpty() || response.equals("-1") || response.equalsIgnoreCase("pass")) {
            return null;  // null signals "pass" to PhaseHandler
        }

        try {
            int idx = Integer.parseInt(response.trim());
            if (idx >= 0 && idx < allPlayable.size()) {
                List<SpellAbility> result = new ArrayList<>();
                result.add(allPlayable.get(idx));
                return result;
            }
        } catch (NumberFormatException e) {
            // Ignore
        }

        return null;  // null signals "pass" to PhaseHandler
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        return true;
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        List<AbilitySub> result = new ArrayList<>();
        for (int i = 0; i < Math.min(min, possible.size()); i++) {
            result.add(possible.get(i));
        }
        return result;
    }

    // ========== Number Choice Methods ==========

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return min;
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        return 0;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return min;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        return values.isEmpty() ? 0 : values.get(0);
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        return defaultChoice != null ? defaultChoice : true;
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean[] results, boolean call) {
        return true;
    }

    // ========== Card Face/State Methods ==========

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) {
        return null;
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        return faces.isEmpty() ? null : faces.get(0);
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) {
        return states.isEmpty() ? null : states.get(0);
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        return true;
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        return options.isEmpty() ? null : options.get(0);
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) {
        return options.isEmpty() ? "" : options.get(0);
    }

    // ========== Payment/Cost Methods ==========

    @Override
    public boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) {
        return true;
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        return possibleReplacers.isEmpty() ? null : possibleReplacers.get(0);
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(String prompt, List<StaticAbility> possibleStatics) {
        return possibleStatics.isEmpty() ? null : possibleStatics.get(0);
    }

    @Override
    public String chooseProtectionType(String string, SpellAbility sa, List<String> choices) {
        return choices.isEmpty() ? "" : choices.get(0);
    }

    // ========== Reveal Methods ==========

    @Override
    public void revealAnte(String message, Multimap<Player, PaperCard> removedAnteCards) {
        // External agent doesn't need ante reveal
    }

    @Override
    public void revealAISkipCards(String message, Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) {
        // External agent doesn't need this
    }

    @Override
    public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) {
        // External agent doesn't need this
    }

    // ========== Turn/Phase Methods ==========

    @Override
    public void resetAtEndOfTurn() {
        // Nothing to reset for external agent
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen, List<OptionalCostValue> optionalCostValues) {
        return new ArrayList<>();
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return costs;
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        return false;
    }

    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa, FCollectionView<Player> allPayers) {
        return false;
    }

    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        return false;
    }

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect) {
        return true;
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        return "";
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        return faces.isEmpty() ? "" : faces.get(0).getName();
    }
}
