package com.tablecards.engine.ptcg;

import com.tablecards.engine.Json;
import com.tablecards.engine.JsonWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts real cards from the open Pokemon TCG data set (github.com/PokemonTCG/pokemon-tcg-data,
 * the same format as the pokemontcg.io API) into the library format.
 *
 * A card is imported only if the engine can play it exactly as printed: every sentence of every
 * attack must match one of the recognised wordings below, it has no Ability, and its rules box only
 * sets its Prize value (or the Tera Bench protection). Trainers are imported when their whole text
 * matches a recognised Item/Supporter wording. Only basic Energy is imported.
 */
public final class PtcgImporter {
    /** Summary of an import, for the report. */
    public static final class Result {
        public String libraryJson;
        public int pokemonTotal, pokemonOk, trainerTotal, trainerOk, energyOk;
        public final Map<String, Integer> rejected = new TreeMap<>();
        public final List<String> themeDecks = new ArrayList<>();
        public final Map<String, Integer> unmatchedAttackText = new HashMap<>();

        void reject(String why) {
            rejected.merge(why, 1, Integer::sum);
        }
    }

    private static final String P = "(?:the Defending Pokémon|your opponent's Active Pokémon)";
    private static final String COND = "(Asleep|Burned|Confused|Paralyzed|Poisoned)";
    private static final String N = "(a|an|one|two|three|four|\\d+)";

    private record Rule(Pattern pattern, BiConsumer<Matcher, Map<String, Object>> apply) {
    }

    private static final List<Rule> ATTACK_RULES = new ArrayList<>();
    private static final List<Rule> TRAINER_RULES = new ArrayList<>();

    private static void attack(String regex, BiConsumer<Matcher, Map<String, Object>> apply) {
        ATTACK_RULES.add(new Rule(Pattern.compile("^" + regex + "$", Pattern.CASE_INSENSITIVE), apply));
    }

    private static void trainer(String regex, BiConsumer<Matcher, Map<String, Object>> apply) {
        TRAINER_RULES.add(new Rule(Pattern.compile("^" + regex + "$", Pattern.CASE_INSENSITIVE), apply));
    }

    static int num(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "a", "an", "one" -> 1;
            case "two" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            default -> Integer.parseInt(s);
        };
    }

    private static List<String> conds(Matcher m) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) {
                out.add(m.group(i).toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    static {
        // Special Conditions
        attack("Flip a coin\\. If heads, " + P + " is now " + COND + "(?:,? and " + COND + ")?\\.", (m, a) -> a.put("coinStatus", conds(m)));
        attack(P + " is now " + COND + "(?:,? and " + COND + ")?\\.", (m, a) -> a.put("status", conds(m)));
        // coins
        attack("Flip a coin\\. If tails, this attack does nothing\\.", (m, a) -> a.put("tailsNothing", true));
        attack("Flip 2 coins\\. If either of them is tails, this attack does nothing\\.", (m, a) -> a.put("eitherTailsNothing", true));
        attack("Flip a coin\\. If heads, this attack does (\\d+) more damage\\.", (m, a) -> a.put("headsBonus", num(m.group(1))));
        attack("Flip a coin\\. If heads, this attack does (\\d+) damage plus (\\d+) more damage\\.", (m, a) -> {
            a.put("headsBonus", num(m.group(2)));
            a.put("baseCheck", num(m.group(1)));
        });
        attack("Flip " + N + " coins\\. This attack does (\\d+) damage (?:times the number of heads|for each heads)\\.",
                (m, a) -> a.put("coinTimes", List.of(num(m.group(1)), num(m.group(2)))));
        attack("Flip " + N + " coins\\. This attack does (\\d+) more damage for each heads\\.",
                (m, a) -> a.put("coinBonus", List.of(num(m.group(1)), num(m.group(2)))));
        attack("This attack does (\\d+) more damage for each damage counter on this Pokémon\\.", (m, a) -> a.put("perCounter", num(m.group(1))));
        attack("Flip a coin until you get tails\\. This attack does (\\d+) damage for each heads\\.", (m, a) -> a.put("coinUntilTailsPer", num(m.group(1))));
        attack("Flip a coin until you get tails\\. This attack does (\\d+) more damage for each heads\\.", (m, a) -> a.put("coinUntilTailsBonus", num(m.group(1))));
        attack("If the Defending Pokémon tries to attack during your opponent's next turn, your opponent flips a coin\\. If tails, that attack does nothing\\.",
                (m, a) -> a.put("smokescreen", true));
        attack("Search your deck for " + N + " Basic Pokémon and put it onto your Bench\\.", (m, a) -> a.put("benchSearch", num(m.group(1))));
        attack("Search your deck for up to (\\d+|two|three) Basic Pokémon and put them onto your Bench\\.", (m, a) -> a.put("benchSearch", num(m.group(1))));
        attack("(?:Then, shuffle|Shuffle) your deck(?: afterward)?\\.", (m, a) -> a.put("shuffleNote", true));
        attack("Your opponent reveals (?:their|his or her) hand\\.", (m, a) -> a.put("revealHand", true));
        // cards
        attack("Draw " + N + " cards?\\.", (m, a) -> a.put("draw", num(m.group(1))));
        attack("You may draw cards until you have (\\d+) cards in your hand\\.", (m, a) -> a.put("drawUntil", num(m.group(1))));
        attack("Discard the top card of your opponent's deck\\.", (m, a) -> a.put("mill", 1));
        attack("Discard the top (\\d+) cards of your opponent's deck\\.", (m, a) -> a.put("mill", num(m.group(1))));
        attack("Discard a random card from your opponent's hand\\.", (m, a) -> a.put("discardRandom", true));
        // healing and recoil
        attack("Heal (\\d+) damage from this Pokémon\\.", (m, a) -> a.put("heal", num(m.group(1))));
        attack("Heal (\\d+) damage from 1 of your Pokémon\\.", (m, a) -> a.put("healOne", num(m.group(1))));
        attack("Heal from this Pokémon the same amount of damage you did to " + P + "\\.", (m, a) -> a.put("healDealt", true));
        attack("This Pokémon (?:also )?does (\\d+) damage to itself\\.", (m, a) -> a.put("selfDamage", num(m.group(1))));
        // energy
        attack("Discard " + N + " Energy (?:card )?(?:attached to|from) this Pokémon\\.", (m, a) -> a.put("discardEnergy", num(m.group(1))));
        attack("Discard all Energy (?:attached to|from) this Pokémon\\.", (m, a) -> a.put("discardEnergy", 99));
        attack("Discard an Energy (?:card )?(?:from|attached to) " + P + "\\.", (m, a) -> a.put("discardOppEnergy", true));
        attack("Flip a coin\\. If heads, discard an Energy (?:card )?(?:from|attached to) " + P + "\\.", (m, a) -> a.put("coinDiscardOppEnergy", true));
        // restrictions and protection
        attack("During your next turn, this Pokémon can't attack\\.", (m, a) -> a.put("cantAttackNext", true));
        attack(P + " can't retreat during your opponent's next turn\\.", (m, a) -> a.put("noRetreat", true));
        attack("During your opponent's next turn, " + P + " can't retreat\\.", (m, a) -> a.put("noRetreat", true));
        attack("Flip a coin\\. If heads, " + P + " can't attack during your opponent's next turn\\.", (m, a) -> a.put("coinDefenderCantAttack", true));
        attack("Flip a coin\\. If heads, during your opponent's next turn, prevent all damage from and effects of attacks done to this Pokémon\\.",
                (m, a) -> a.put("coinProtect", true));
        attack("Flip a coin\\. If heads, prevent all effects of attacks, including damage, done to this Pokémon during your opponent's next turn\\.",
                (m, a) -> a.put("coinProtect", true));
        attack("During your opponent's next turn, this Pokémon takes (\\d+) less damage from attacks \\(after applying Weakness and Resistance\\)\\.",
                (m, a) -> a.put("reduceNext", num(m.group(1))));
        attack("This attack's damage isn't affected by Resistance\\.", (m, a) -> a.put("ignoreResistance", true));
        attack("This attack's damage isn't affected by any effects on " + P + "\\.", (m, a) -> a.put("ignoreEffects", true));
        // switching
        attack("Switch this Pokémon with 1 of your Benched Pokémon\\.", (m, a) -> a.put("selfSwitch", "must"));
        attack("You may switch this Pokémon with 1 of your Benched Pokémon\\.", (m, a) -> a.put("selfSwitch", "may"));
        attack("Your opponent switches " + P + " with 1 of (?:his or her|their) Benched Pokémon\\.", (m, a) -> a.put("forceSwitch", true));
        attack("Your opponent switches their Active Pokémon with 1 of their Benched Pokémon\\.", (m, a) -> a.put("forceSwitch", true));
        attack("Switch out your opponent's Active Pokémon to the Bench\\. \\(Your opponent chooses the new Active Pokémon\\.\\)",
                (m, a) -> a.put("forceSwitch", true));
        // spread
        attack("(?:This attack also does|Does) (\\d+) damage to 1 of your opponent's Benched Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)",
                (m, a) -> a.put("benchSnipe", num(m.group(1))));
        attack("This attack does (\\d+) damage to 1 of your opponent's Benched Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)",
                (m, a) -> a.put("benchSnipe", num(m.group(1))));
        attack("This attack does (\\d+) damage to 1 of your opponent's Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)",
                (m, a) -> a.put("snipeAny", num(m.group(1))));

        String shuffle = " (?:Then, shuffle|Shuffle) your deck(?: afterward)?\\.";
        trainer("Draw (\\d+) cards\\.", (m, t) -> effect(t, "draw", num(m.group(1)), "Draw " + m.group(1) + " cards."));
        trainer("Discard your hand and draw (\\d+) cards\\.", (m, t) -> effect(t, "shuffle_draw", num(m.group(1)), "Discard your hand, draw " + m.group(1) + "."));
        trainer("Shuffle your hand into your deck\\. Then, draw (\\d+) cards\\.", (m, t) -> effect(t, "shuffle_in_draw", num(m.group(1)), "Shuffle your hand in, draw " + m.group(1) + "."));
        trainer("Flip a coin until you get tails\\. For each heads, draw a card\\.", (m, t) -> effect(t, "coin_draw", 0, "Flip until tails, draw 1 per heads."));
        trainer("Flip a coin\\. If heads, discard an Energy from 1 of your opponent's Pokémon\\.", (m, t) -> effect(t, "coin_discard_opp_energy", 0, "Heads: discard an Energy from 1 of theirs."));
        trainer("Look at the top (\\d+) cards of your deck\\. You may reveal a Pokémon you find there and put it into your hand\\. Shuffle the other cards back into your deck\\.",
                (m, t) -> effect(t, "look_top_pokemon", num(m.group(1)), "Take a Pokemon from your top " + m.group(1) + "."));
        trainer("Look at the top (\\d+) cards of your deck\\. You may reveal a Supporter card you find there and put it into your hand\\. Shuffle the other cards back into your deck\\.",
                (m, t) -> effect(t, "look_top_supporter", num(m.group(1)), "Take a Supporter from your top " + m.group(1) + "."));
        trainer("Each player shuffles their hand and puts it on the bottom of their deck\\. If either player put any cards on the bottom of their deck in this way, you draw 5 cards, and your opponent draws 4 cards\\.",
                (m, t) -> effect(t, "marnie", 0, "Both put hands on the bottom; you draw 5, they draw 4."));
        trainer("Search your deck for up to (\\d+) basic Energy cards, reveal them, and put them into your hand\\." + shuffle,
                (m, t) -> effect(t, "search_energy", num(m.group(1)), "Search for up to " + m.group(1) + " basic Energy."));
        trainer("Remove all Special Conditions from your Active Pokémon\\.", (m, t) -> effect(t, "full_heal", 0, "Cure your Active's Special Conditions."));
        trainer("Flip a coin\\. If heads, put 1 of your Pokémon and all cards attached to it into your hand\\.", (m, t) -> effect(t, "scoop_coin", 0, "Heads: return 1 of your Pokemon to your hand."));
        trainer("Choose 1 of your Basic Pokémon in play\\. If you have a Stage 1 or Stage 2 card that evolves from that Pokémon in your hand, put that card on the Basic Pokémon\\. \\(This counts as evolving that Pokémon\\.\\)",
                (m, t) -> effect(t, "rare_candy", 1, "Evolve a Basic to Stage 1 or straight to Stage 2."));
        trainer("Draw cards until you have (\\d+) cards in your hand\\.", (m, t) -> effect(t, "draw_until", num(m.group(1)), "Draw until you have " + m.group(1) + "."));
        trainer("Heal (\\d+) damage from 1 of your Pokémon\\.", (m, t) -> effect(t, "heal", num(m.group(1)), "Heal " + m.group(1) + " from 1 of yours."));
        trainer("Remove (\\d+|two) damage counters from 1 of your Pokémon(?: \\(remove 1 damage counter if that Pokémon has only 1\\))?\\.",
                (m, t) -> effect(t, "heal", num(m.group(1)) * 10, "Heal " + num(m.group(1)) * 10 + " from 1 of yours."));
        trainer("Switch (?:1 of )?your Active Pokémon with 1 of your Benched Pokémon\\.", (m, t) -> effect(t, "switch", 0, "Switch your Active."));
        trainer("Switch 1 of your opponent's Benched Pokémon with (?:their|his or her) Active Pokémon\\.", (m, t) -> effect(t, "gust", 0, "Bring out 1 of their Benched Pokemon."));
        trainer("Switch in 1 of your opponent's Benched Pokémon to the Active Spot\\.", (m, t) -> effect(t, "gust", 0, "Bring out 1 of their Benched Pokemon."));
        trainer("Search your deck for a Basic Pokémon, reveal it, and put it into your hand\\." + shuffle, (m, t) -> effect(t, "search_basic", 0, "Search for a Basic Pokemon."));
        trainer("Search your deck for a Basic Pokémon and put it onto your Bench\\." + shuffle, (m, t) -> effect(t, "nest_ball", 0, "Bench a Basic Pokemon from your deck."));
        trainer("You can use this card only if you discard 2 other cards from your hand\\. Search your deck for a Pokémon, reveal it, and put it into your hand\\." + shuffle,
                (m, t) -> effect(t, "search_pokemon", 2, "Discard 2, search for a Pokemon."));
        trainer("Discard 2 cards from your hand\\. \\(If you can't discard 2 cards, you can't play this card\\.\\) Search your deck for a Pokémon, reveal it, and put it into your hand\\." + shuffle,
                (m, t) -> effect(t, "search_pokemon", 2, "Discard 2, search for a Pokemon."));
        trainer("Flip a coin\\. If heads, search your deck for a Pokémon, reveal it, and put it into your hand\\." + shuffle,
                (m, t) -> effect(t, "pokeball_coin", 0, "Heads: search for a Pokemon."));
        trainer("Search your deck for a basic Energy card, reveal it, and put it into your hand\\." + shuffle, (m, t) -> effect(t, "search_energy", 0, "Search for a basic Energy."));
        trainer("Put (\\d+) basic Energy cards from your discard pile into your hand\\.", (m, t) -> effect(t, "energy_retrieval", num(m.group(1)), "Take " + m.group(1) + " basic Energy from your discard."));
        trainer("Put up to (\\d+) basic Energy cards from your discard pile into your hand\\.", (m, t) -> effect(t, "energy_retrieval", num(m.group(1)), "Take up to " + m.group(1) + " basic Energy from your discard."));
        trainer("Move a basic Energy (?:card )?(?:attached to|from) 1 of your Pokémon to another of your Pokémon\\.", (m, t) -> effect(t, "energy_switch", 0, "Move a basic Energy."));
        trainer("Each player shuffles (?:their|his or her) hand into (?:their|his or her) deck and draws (\\d+) cards\\.", (m, t) -> effect(t, "judge", num(m.group(1)), "Both shuffle hands in, draw " + m.group(1) + "."));
        trainer("Each player shuffles his or her hand into his or her deck\\. Then, each player draws a card for each of his or her remaining Prize cards\\.",
                (m, t) -> effect(t, "n_shuffle", 0, "Both shuffle hands in, draw 1 per Prize left."));
        trainer("Each player shuffles their hand and puts it on the bottom of their deck\\. If either player put any cards on the bottom of their deck in this way, each player draws a card for each of their remaining Prize cards\\.",
                (m, t) -> effect(t, "iono", 0, "Both put hands on the bottom, draw 1 per Prize left."));
        trainer("Choose 1 of your Basic Pokémon in play\\. If you have a Stage 2 card in your hand that evolves from that Pokémon, put that card onto the Basic Pokémon to evolve it, skipping the Stage 1\\. You can't use this card during your first turn or on a Basic Pokémon that was put into play this turn\\.",
                (m, t) -> effect(t, "rare_candy", 0, "Evolve a Basic straight to Stage 2."));
    }

    private static void effect(Map<String, Object> t, String effect, int value, String text) {
        t.put("effect", effect);
        if (value != 0) {
            t.put("value", value);
        }
        t.put("text", text);
    }

    // ------------------------------------------------------------------ conversion

    private static final Pattern PRIZE_RULE = Pattern.compile("takes (\\d+|two|three) Prize cards", Pattern.CASE_INSENSITIVE);
    private static final List<String> OK_SUBTYPES = List.of("Basic", "Stage 1", "Stage 2", "ex", "EX", "GX", "V", "VMAX", "VSTAR",
            "Tera", "Rapid Strike", "Single Strike", "Fusion Strike", "Ultra Beast", "Ancient", "Future", "Team Plasma", "Prime", "SP", "TAG TEAM");

    /**
     * @param setsJson    sets/en.json
     * @param setCards    set id -> that set's cards/en/&lt;id&gt;.json
     * @param themeDecks  the contents of decks/en/*.json files (may be empty)
     */
    public static Result convert(String setsJson, Map<String, String> setCards, List<String> themeDecks) {
        Result r = new Result();
        Map<String, String> setCode = new HashMap<>();
        for (Object o : Json.arr(Json.parse(setsJson))) {
            Map<String, Object> s = Json.obj(o);
            setCode.put(Json.str(s, "id", ""), Json.str(s, "ptcgoCode", ""));
        }
        List<Map<String, Object>> all = new ArrayList<>();
        for (String json : setCards.values()) {
            for (Object o : Json.arr(Json.parse(json))) {
                all.add(Json.obj(o));
            }
        }
        // for Rare Candy: what each Stage 1 evolves from, by name
        Map<String, String> stage1From = new HashMap<>();
        for (Map<String, Object> c : all) {
            if (subtypes(c).contains("Stage 1") && c.get("evolvesFrom") != null) {
                stage1From.putIfAbsent(Json.str(c, "name", ""), Json.str(c, "evolvesFrom", ""));
            }
        }
        Map<String, Object> cards = new LinkedHashMap<>();
        for (Map<String, Object> c : all) {
            String id = Json.str(c, "id", "");
            String set = setCode.getOrDefault(id.contains("-") ? id.substring(0, id.lastIndexOf('-')) : "", "");
            Map<String, Object> out = switch (Json.str(c, "supertype", "")) {
                case "Pokémon" -> {
                    r.pokemonTotal++;
                    Map<String, Object> x = pokemon(c, r, stage1From);
                    if (x != null) r.pokemonOk++;
                    yield x;
                }
                case "Trainer" -> {
                    r.trainerTotal++;
                    Map<String, Object> x = trainer(c, r);
                    if (x != null) r.trainerOk++;
                    yield x;
                }
                case "Energy" -> {
                    Map<String, Object> x = energy(c);
                    if (x != null) r.energyOk++;
                    yield x;
                }
                default -> null;
            };
            if (out != null) {
                out.put("set", set);
                out.put("number", Json.str(c, "number", ""));
                cards.put(id, out);
            }
        }
        Map<String, Object> decks = new LinkedHashMap<>();
        for (String json : themeDecks) {
            for (Object o : Json.arr(Json.parse(json))) {
                Map<String, Object> d = Json.obj(o);
                Map<String, Object> list = new LinkedHashMap<>();
                boolean ok = true;
                int total = 0;
                for (Object co : Json.arr(d.get("cards"))) {
                    Map<String, Object> entry = Json.obj(co);
                    String id = Json.str(entry, "id", "");
                    int count = Json.num(entry, "count", 1);
                    if (!cards.containsKey(id)) {
                        ok = false;
                        break;
                    }
                    list.merge(id, count, (a, b) -> ((Number) a).intValue() + ((Number) b).intValue());
                    total += count;
                }
                if (ok && total == PtcgLibrary.DECK_SIZE) {
                    String name = "Theme: " + Json.str(d, "name", "Deck");
                    decks.put(name, list);
                    r.themeDecks.add(name);
                }
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cards", cards);
        root.put("decks", decks);
        r.libraryJson = JsonWriter.write(root);
        return r;
    }

    private static List<String> subtypes(Map<String, Object> c) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(c.get("subtypes"))) {
            out.add(o.toString());
        }
        return out;
    }

    private static String type(String t) {
        return t.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> pokemon(Map<String, Object> c, Result r, Map<String, String> stage1From) {
        List<String> sub = subtypes(c);
        for (String s : sub) {
            if (!OK_SUBTYPES.contains(s)) {
                r.reject("Pokemon: " + s + " mechanics");
                return null;
            }
        }
        if (!Json.arr(c.get("abilities")).isEmpty()) {
            r.reject("Pokemon: has an Ability");
            return null;
        }
        String hp = Json.str(c, "hp", "");
        List<Object> types = Json.arr(c.get("types"));
        if (!hp.matches("\\d+") || types.size() != 1) {
            r.reject("Pokemon: no HP or dual type");
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", Json.str(c, "name", "?"));
        out.put("kind", "pokemon");
        int stage = sub.contains("Stage 2") ? 2 : sub.contains("Stage 1") ? 1 : sub.contains("Basic") ? 0 : -1;
        String evolvesFrom = Json.str(c, "evolvesFrom", null);
        if (stage < 0) {
            stage = evolvesFrom != null ? 1 : 0; // VMAX / VSTAR evolve from a V
        }
        if (stage > 0 && evolvesFrom == null) {
            r.reject("Pokemon: unknown evolution");
            return null;
        }
        out.put("stage", stage);
        if (evolvesFrom != null && stage > 0) {
            out.put("evolvesFrom", evolvesFrom);
            if (stage == 2 && stage1From.containsKey(evolvesFrom)) {
                out.put("rootName", stage1From.get(evolvesFrom));
            }
        }
        out.put("type", type(types.get(0).toString()));
        out.put("hp", Integer.parseInt(hp));
        List<Object> weak = Json.arr(c.get("weaknesses"));
        List<Object> res = Json.arr(c.get("resistances"));
        if (weak.size() > 1 || res.size() > 1) {
            r.reject("Pokemon: several Weaknesses/Resistances");
            return null;
        }
        if (weak.size() == 1) {
            Map<String, Object> w = Json.obj(weak.get(0));
            String v = Json.str(w, "value", "");
            out.put("weakness", type(Json.str(w, "type", "")));
            if (v.matches("\\+\\d+")) {
                out.put("weaknessPlus", Integer.parseInt(v.substring(1)));
            } else if (!v.equals("×2") && !v.equalsIgnoreCase("x2")) {
                r.reject("Pokemon: unusual Weakness");
                return null;
            }
        }
        if (res.size() == 1) {
            Map<String, Object> w = Json.obj(res.get(0));
            String v = Json.str(w, "value", "");
            if (!v.matches("-\\d+")) {
                r.reject("Pokemon: unusual Resistance");
                return null;
            }
            out.put("resistance", type(Json.str(w, "type", "")));
            out.put("resistanceValue", Integer.parseInt(v.substring(1)));
        }
        out.put("retreat", Json.arr(c.get("retreatCost")).size());
        int prizes = 1;
        for (Object o : Json.arr(c.get("rules"))) {
            String rule = o.toString();
            Matcher m = PRIZE_RULE.matcher(rule);
            if (m.find()) {
                prizes = num(m.group(1));
            } else if (rule.contains("prevent all damage done to this Pokémon by attacks (both yours and your opponent's)")) {
                out.put("benchProtected", true);
            } else {
                r.reject("Pokemon: special rule box");
                return null;
            }
        }
        if (prizes > 1) {
            out.put("prizes", prizes);
        }
        List<Object> attacks = new ArrayList<>();
        for (Object o : Json.arr(c.get("attacks"))) {
            Map<String, Object> a = attack(Json.obj(o), r);
            if (a == null) {
                r.reject("Pokemon: attack effect not supported");
                return null;
            }
            attacks.add(a);
        }
        out.put("attacks", attacks);
        return out;
    }

    private static final Pattern DAMAGE = Pattern.compile("(\\d*)([+×x]?)");

    /** Converts one attack, or returns null if any part of it isn't supported. */
    static Map<String, Object> attack(Map<String, Object> src, Result r) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", Json.str(src, "name", "Attack"));
        List<String> cost = new ArrayList<>();
        for (Object o : Json.arr(src.get("cost"))) {
            String t = o.toString();
            if (!t.equalsIgnoreCase("Free")) {
                cost.add(type(t));
            }
        }
        a.put("cost", cost);
        String text = Json.str(src, "text", "").trim();
        for (String sentence : sentences(text)) {
            boolean matched = false;
            for (Rule rule : ATTACK_RULES) {
                Matcher m = rule.pattern.matcher(sentence);
                if (m.matches()) {
                    int before = a.size();
                    Map<String, Object> probe = new LinkedHashMap<>();
                    rule.apply.accept(m, probe);
                    for (String k : probe.keySet()) {
                        if (a.containsKey(k)) {
                            return null; // the same effect twice in one attack: not modelled
                        }
                    }
                    a.putAll(probe);
                    matched = a.size() > before;
                    break;
                }
            }
            if (!matched) {
                if (r != null) {
                    r.unmatchedAttackText.merge(sentence, 1, Integer::sum);
                }
                return null;
            }
        }
        Matcher dm = DAMAGE.matcher(Json.str(src, "damage", "").trim());
        if (!dm.matches()) {
            return null;
        }
        int base = dm.group(1).isEmpty() ? 0 : Integer.parseInt(dm.group(1));
        String mod = dm.group(2);
        boolean plus = mod.equals("+");
        boolean times = mod.equals("×") || mod.equalsIgnoreCase("x");
        a.remove("shuffleNote");
        boolean wantsPlus = a.containsKey("headsBonus") || a.containsKey("perCounter") || a.containsKey("coinBonus") || a.containsKey("coinUntilTailsBonus");
        boolean wantsTimes = a.containsKey("coinTimes") || a.containsKey("coinUntilTailsPer");
        if (plus != wantsPlus || times != wantsTimes) {
            return null;
        }
        Object check = a.remove("baseCheck");
        if (check != null && ((Number) check).intValue() != base) {
            return null;
        }
        if (!times) {
            a.put("damage", base);
        }
        return a;
    }

    /** Splits card text into sentences, keeping "Flip a coin." together with what follows. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            return out;
        }
        String[] parts = text.split("(?<=\\.)\\s+(?=[A-Z])");
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i].trim();
            if ((s.matches("(?i)Flip (a|\\d+|two|three|four) coins?\\.") || s.matches("(?i)Flip a coin until you get tails\\.")
                    || s.matches("(?i)If the Defending Pokémon tries to attack during your opponent's next turn, your opponent flips a coin\\."))
                    && i + 1 < parts.length) {
                s = s + " " + parts[++i].trim();
            }
            out.add(s);
        }
        return out;
    }

    private static final Pattern BOILERPLATE = Pattern.compile(
            "^(You may play only 1 Supporter card during your turn.*|You may play as many Item cards as you like during your turn.*|You may play any number of Item cards during your turn.*)$",
            Pattern.CASE_INSENSITIVE);

    private static Map<String, Object> trainer(Map<String, Object> c, Result r) {
        List<String> sub = subtypes(c);
        String kind = sub.contains("Supporter") ? "supporter" : sub.contains("Item") ? "item" : null;
        boolean other = sub.stream().anyMatch(s -> !s.equals("Supporter") && !s.equals("Item") && !s.equals("ACE SPEC"));
        if (kind == null || other) {
            r.reject("Trainer: Tools, Stadiums and other kinds");
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Object o : Json.arr(c.get("rules"))) {
            String rule = o.toString().trim();
            if (!BOILERPLATE.matcher(rule).matches()) {
                parts.add(rule);
            }
        }
        String text = String.join(" ", parts).replaceAll("\\s+", " ").trim();
        for (Rule rule : TRAINER_RULES) {
            Matcher m = rule.pattern.matcher(text);
            if (m.matches()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("name", Json.str(c, "name", "?"));
                out.put("kind", kind);
                rule.apply.accept(m, out);
                if (sub.contains("ACE SPEC")) {
                    out.put("aceSpec", true);
                }
                return out;
            }
        }
        r.reject("Trainer: effect not supported");
        return null;
    }

    private static final Pattern ENERGY_NAME = Pattern.compile("(?:Basic )?(\\w+) Energy");

    private static Map<String, Object> energy(Map<String, Object> c) {
        if (!subtypes(c).contains("Basic")) {
            return null;
        }
        Matcher m = ENERGY_NAME.matcher(Json.str(c, "name", ""));
        if (!m.matches()) {
            return null;
        }
        String t = type(m.group(1));
        if (!List.of("fire", "water", "grass", "lightning", "psychic", "fighting", "darkness", "metal", "fairy").contains(t)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", PtcgCard.typeName(t) + " Energy");
        out.put("kind", "energy");
        out.put("type", t);
        return out;
    }
}
