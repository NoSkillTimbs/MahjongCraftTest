package com.tablecards.engine.ygo;

import com.tablecards.engine.Json;
import com.tablecards.engine.JsonWriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts real cards from the YGOPRODeck card database (db.ygoprodeck.com/api/v7/cardinfo.php)
 * into the library format.
 *
 * A card is imported only if the engine can play it exactly as printed: Normal Monsters, every card
 * with its own script in {@link YgoScripts} (effect monsters, Extra Deck monsters and the Spells and
 * Traps of the supported archetypes), and Normal/Quick-Play/Equip Spells and Normal/Counter Traps
 * whose whole text matches one of the recognised wordings below.
 */
public final class YgoImporter {
    /** Bumped when the import format changes, so servers re-import their cards. */
    public static final int FORMAT = 2;

    public static final class Result {
        public String libraryJson;
        public int monstersOk, spellsOk, trapsOk, scriptedOk, total;
        public final Map<String, Integer> rejected = new TreeMap<>();

        void reject(String why) {
            rejected.merge(why, 1, Integer::sum);
        }
    }

    private static final List<String> ATTRIBUTES = List.of("EARTH", "WATER", "FIRE", "WIND", "LIGHT", "DARK");
    private static final List<String> RACES = List.of("Warrior", "Spellcaster", "Fairy", "Fiend", "Zombie", "Machine", "Aqua", "Pyro",
            "Rock", "Winged Beast", "Plant", "Insect", "Thunder", "Dragon", "Beast", "Beast-Warrior", "Dinosaur", "Fish", "Sea Serpent",
            "Reptile", "Psychic", "Wyrm", "Cyberse", "Illusion");

    private record Rule(Pattern pattern, BiConsumer<Matcher, Map<String, Object>> apply) {
    }

    private static final List<Rule> SPELLS = new ArrayList<>();
    private static final List<Rule> EQUIPS = new ArrayList<>();
    private static final List<Rule> TRAPS = new ArrayList<>();

    private static void rule(List<Rule> list, String regex, BiConsumer<Matcher, Map<String, Object>> apply) {
        list.add(new Rule(Pattern.compile("^" + regex + "$"), apply));
    }

    private static void fx(Map<String, Object> c, String effect, int value, String text) {
        c.put("effect", effect);
        if (value != 0) {
            c.put("value", value);
        }
        c.put("text", text);
    }

    private static int n(Matcher m, int g) {
        return Integer.parseInt(m.group(g));
    }

    static {
        String opp = "When an opponent's monster declares an attack: ";
        rule(SPELLS, "Destroy all monsters on the field\\.", (m, c) -> fx(c, "destroy_all", 0, "Destroy all monsters on the field."));
        rule(SPELLS, "Destroy all monsters your opponent controls\\.", (m, c) -> fx(c, "destroy_opp_all", 0, "Destroy all your opponent's monsters."));
        rule(SPELLS, "Draw (\\d) cards?\\.", (m, c) -> fx(c, "draw", n(m, 1), "Draw " + m.group(1) + "."));
        rule(SPELLS, "Inflict (\\d+) (?:points of )?damage to your opponent(?:'s Life Points)?\\.",
                (m, c) -> fx(c, "burn", n(m, 1), "Inflict " + m.group(1) + " damage."));
        rule(SPELLS, "(?:Increase your Life Points by|You gain|Gain) (\\d+) (?:points|Life Points|LP)\\.",
                (m, c) -> fx(c, "gain", n(m, 1), "Gain " + m.group(1) + " LP."));
        rule(SPELLS, "Target 1 Spell/Trap(?: Card)? on the field; destroy that target\\.",
                (m, c) -> fx(c, "destroy_st", 0, "Destroy 1 Spell/Trap on the field."));
        rule(SPELLS, "Destroy all Spells? and Traps?(?: Cards)? your opponent controls\\.",
                (m, c) -> fx(c, "destroy_st_opp_all", 0, "Destroy all your opponent's Spells/Traps."));
        rule(SPELLS, "Destroy all Spells? and Traps?(?: Cards)? on the field\\.",
                (m, c) -> fx(c, "destroy_st_all", 0, "Destroy all Spells/Traps on the field."));
        rule(SPELLS, "Target 1 monster in either (?:GY|Graveyard|player's Graveyard); Special Summon it\\.", (m, c) -> {
            fx(c, "revive", 0, "Special Summon 1 monster from either GY.");
            c.put("scope", "either");
        });
        rule(SPELLS, "Target 1 monster in your (?:GY|Graveyard); Special Summon it\\.",
                (m, c) -> fx(c, "revive", 0, "Special Summon 1 monster from your GY."));
        rule(SPELLS, "Target 1 monster on the field; destroy that target\\.", (m, c) -> fx(c, "destroy_any", 0, "Destroy 1 monster on the field."));
        rule(SPELLS, "Target 1 monster your opponent controls; destroy that target\\.",
                (m, c) -> fx(c, "destroy_one", 0, "Destroy 1 monster your opponent controls."));
        rule(SPELLS, "Destroy the 1 face-up monster your opponent controls that has the lowest ATK \\(your choice, if tied\\)\\.",
                (m, c) -> fx(c, "destroy_lowest", 0, "Destroy their face-up monster with the lowest ATK."));

        rule(EQUIPS, "The equipped monster gains (\\d+) ATK\\.", (m, c) -> equip(c, n(m, 1), 0, null, true));
        rule(EQUIPS, "The equipped monster gains (\\d+) ATK(?:/| and )DEF\\.", (m, c) -> equip(c, n(m, 1), n(m, 1), null, true));
        rule(EQUIPS, "The equipped monster gains (\\d+) DEF\\.", (m, c) -> equip(c, 0, n(m, 1), null, true));
        rule(EQUIPS, "The equipped monster gains (\\d+) ATK and loses (\\d+) DEF\\.", (m, c) -> equip(c, n(m, 1), -n(m, 2), null, true));
        rule(EQUIPS, "Equip only to an? ([A-Za-z -]+?)(?:-Type)? monster\\. It gains (\\d+) ATK\\.", (m, c) -> equip(c, n(m, 2), 0, m.group(1), true));
        rule(EQUIPS, "Equip only to an? ([A-Za-z -]+?)(?:-Type)? monster\\. It gains (\\d+) ATK/DEF\\.", (m, c) -> equip(c, n(m, 2), n(m, 2), m.group(1), true));
        rule(EQUIPS, "An? ([A-Za-z -]+?)(?:-Type)? monster equipped with this card increases its ATK and DEF by (\\d+) points\\.",
                (m, c) -> equip(c, n(m, 2), n(m, 2), m.group(1), false));
        rule(EQUIPS, "An? ([A-Za-z -]+?)(?:-Type)? monster equipped with this card increases its ATK by (\\d+) points and decreases its DEF by (\\d+) points\\.",
                (m, c) -> equip(c, n(m, 2), -n(m, 3), m.group(1), false));
        rule(EQUIPS, "An? ([A-Za-z -]+?)(?:-Type)? monster equipped with this card increases its ATK by (\\d+) points\\.",
                (m, c) -> equip(c, n(m, 2), 0, m.group(1), false));

        rule(TRAPS, opp + "Target the attacking monster; destroy that target\\.", (m, c) -> fx(c, "destroy_attacker", 0, "When attacked: destroy the attacker."));
        rule(TRAPS, opp + "Destroy (?:it|that monster|the attacking monster)\\.", (m, c) -> fx(c, "destroy_attacker", 0, "When attacked: destroy the attacker."));
        rule(TRAPS, opp + "Destroy it, then inflict (\\d+) damage to your opponent\\.", (m, c) -> {
            fx(c, "destroy_attacker", 0, "When attacked: destroy the attacker, inflict " + m.group(1) + ".");
            c.put("burn", n(m, 1));
        });
        rule(TRAPS, opp + "Target the attacking monster; negate the attack, then end the Battle Phase\\.", (m, c) -> {
            fx(c, "negate_attack", 0, "When attacked: negate it and end the Battle Phase.");
            c.put("endBattle", true);
        });
        rule(TRAPS, opp + "Negate the attack, then end the Battle Phase\\.", (m, c) -> {
            fx(c, "negate_attack", 0, "When attacked: negate it and end the Battle Phase.");
            c.put("endBattle", true);
        });
        rule(TRAPS, opp + "(?:Target the attacking monster; n|N)egate the attack\\.", (m, c) -> fx(c, "negate_attack", 0, "When attacked: negate the attack."));
        rule(TRAPS, opp + "Destroy all (?:your opponent's Attack Position monsters|Attack Position monsters your opponent controls)\\.",
                (m, c) -> fx(c, "mirror_force", 0, "When attacked: destroy all their Attack Position monsters."));
        rule(TRAPS, opp + "Target the attacking monster; negate the attack, and if you do, inflict damage to your opponent equal to its ATK\\.",
                (m, c) -> fx(c, "magic_cylinder", 0, "When attacked: negate it, inflict damage equal to its ATK."));
        rule(TRAPS, "When your opponent Normal or Flip Summons a monster with (\\d+) or more ATK: Target that monster; destroy that target\\.", (m, c) -> {
            fx(c, "destroy_summoned", n(m, 1), "When they Normal/Flip Summon a " + m.group(1) + "+ ATK monster: destroy it.");
            c.put("flipToo", true);
        });
        rule(TRAPS, "When your opponent Normal Summons a monster with (\\d+) or more ATK: Target that monster; destroy that target\\.",
                (m, c) -> fx(c, "destroy_summoned", n(m, 1), "When they Normal Summon a " + m.group(1) + "+ ATK monster: destroy it."));
    }

    private static void equip(Map<String, Object> c, int atk, int def, String restriction, boolean strict) {
        c.put("effect", "equip");
        c.put("equipAtk", atk);
        c.put("equipDef", def);
        StringBuilder text = new StringBuilder("Equip");
        if (restriction != null) {
            String r = restriction.trim();
            if (ATTRIBUTES.contains(r)) {
                c.put("equipAttr", r);
            } else {
                String race = RACES.stream().filter(x -> x.equalsIgnoreCase(r)).findFirst().orElse(null);
                if (race == null) {
                    c.put("invalid", true);
                    return;
                }
                c.put("equipRace", race);
            }
            text.append(strict ? " only to " : ", for ").append(r);
        }
        if (strict && restriction != null) {
            c.put("equipStrict", true);
        }
        text.append(": ").append(atk >= 0 ? "+" : "").append(atk).append(" ATK");
        if (def != 0) {
            text.append(", ").append(def >= 0 ? "+" : "").append(def).append(" DEF");
        }
        c.put("text", text.append(".").toString());
    }

    /** @param cardinfoJson the YGOPRODeck cardinfo.php response ({"data": [...]}) */
    public static Result convert(String cardinfoJson) {
        Result r = new Result();
        Map<String, Object> cards = new LinkedHashMap<>();
        for (Object o : Json.arr(Json.obj(Json.parse(cardinfoJson)).get("data"))) {
            Map<String, Object> c = Json.obj(o);
            r.total++;
            Map<String, Object> out = convertCard(c, r);
            if (out == null) {
                continue;
            }
            List<Object> codes = new ArrayList<>();
            String id = idOf(c.get("id"));
            codes.add(id);
            for (Object img : Json.arr(c.get("card_images"))) {
                String alt = idOf(Json.obj(img).get("id"));
                if (!codes.contains(alt)) {
                    codes.add(alt);
                }
            }
            out.put("codes", codes);
            cards.put("ygo-" + id, out);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("cards", cards);
        root.put("decks", new LinkedHashMap<>());
        r.libraryJson = JsonWriter.write(root);
        return r;
    }

    private static String idOf(Object o) {
        return o instanceof Number num ? String.valueOf(num.longValue()) : String.valueOf(o);
    }

    private static Map<String, Object> convertCard(Map<String, Object> c, Result r) {
        String type = Json.str(c, "type", "");
        String name = Json.str(c, "name", "?");
        String desc = Json.str(c, "desc", "").replaceAll("\\s+", " ").trim();
        String race = Json.str(c, "race", "");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        if (YgoScripts.names().contains(name)) {
            return scripted(c, out, r);
        }
        switch (type) {
            case "Normal Monster", "Normal Tuner Monster" -> {
                Object atk = c.get("atk");
                Object def = c.get("def");
                int level = Json.num(c, "level", 0);
                if (!(atk instanceof Number) || !(def instanceof Number) || level < 1 || level > 12) {
                    r.reject("Monster: missing stats");
                    return null;
                }
                out.put("kind", "monster");
                out.put("level", level);
                out.put("atk", ((Number) atk).intValue());
                out.put("def", ((Number) def).intValue());
                out.put("race", race);
                out.put("attribute", Json.str(c, "attribute", ""));
                r.monstersOk++;
                return out;
            }
            case "Spell Card" -> {
                List<Rule> rules = switch (race) {
                    case "Normal", "Quick-Play" -> SPELLS;
                    case "Equip" -> EQUIPS;
                    default -> null;
                };
                if (rules == null) {
                    r.reject("Spell: " + race + " Spells");
                    return null;
                }
                out.put("kind", "spell");
                if (race.equals("Quick-Play")) {
                    out.put("quick", true);
                }
                if (!applyRules(rules, desc, out) || out.containsKey("invalid")) {
                    r.reject("Spell: effect not supported");
                    return null;
                }
                r.spellsOk++;
                return out;
            }
            case "Trap Card" -> {
                if (!race.equals("Normal") && !race.equals("Counter")) {
                    r.reject("Trap: " + race + " Traps");
                    return null;
                }
                out.put("kind", "trap");
                if (!applyRules(TRAPS, desc, out)) {
                    r.reject("Trap: effect not supported");
                    return null;
                }
                r.trapsOk++;
                return out;
            }
            default -> {
                r.reject(type.contains("Effect") || type.contains("Flip") || type.contains("Union") || type.contains("Spirit")
                        || type.contains("Toon") || type.contains("Gemini") || type.contains("Tuner") ? "Monster: effect monsters"
                        : type.contains("Monster") ? "Monster: Extra Deck / Pendulum / Ritual" : "Other: " + type);
                return null;
            }
        }
    }

    /** A card with its own script: keep everything the engine needs, and its real text for display. */
    private static Map<String, Object> scripted(Map<String, Object> c, Map<String, Object> out, Result r) {
        String type = Json.str(c, "type", "");
        String race = Json.str(c, "race", "");
        String desc = Json.str(c, "desc", "").trim();
        out.put("text", desc);
        if (type.contains("Monster")) {
            Object atk = c.get("atk");
            Object def = c.get("def");
            out.put("kind", "monster");
            out.put("frame", Json.str(c, "frameType", type.contains("Normal") ? "normal" : "effect"));
            out.put("level", Json.num(c, "level", 0));
            out.put("atk", atk instanceof Number n ? n.intValue() : 0);
            out.put("def", def instanceof Number n ? n.intValue() : 0);
            out.put("race", race);
            out.put("attribute", Json.str(c, "attribute", ""));
            if (type.contains("Tuner")) {
                out.put("tuner", true);
            }
            r.monstersOk++;
        } else if (type.contains("Spell")) {
            out.put("kind", "spell");
            out.put("stype", switch (race) {
                case "Quick-Play" -> "quick";
                case "Continuous" -> "continuous";
                case "Field" -> "field";
                case "Equip" -> "equip";
                case "Ritual" -> "ritual";
                default -> "normal";
            });
            r.spellsOk++;
        } else {
            out.put("kind", "trap");
            out.put("stype", switch (race) {
                case "Continuous" -> "continuous";
                case "Counter" -> "counter";
                default -> "normal";
            });
            r.trapsOk++;
        }
        r.scriptedOk++;
        return out;
    }

    private static boolean applyRules(List<Rule> rules, String desc, Map<String, Object> out) {
        for (Rule rule : rules) {
            Matcher m = rule.pattern.matcher(desc);
            if (m.matches()) {
                rule.apply.accept(m, out);
                return true;
            }
        }
        return false;
    }

}
