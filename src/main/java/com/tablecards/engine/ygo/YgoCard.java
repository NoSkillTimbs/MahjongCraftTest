package com.tablecards.engine.ygo;

import com.tablecards.engine.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A card definition, read from the library JSON format (imported real cards).
 *
 * Monsters have a frame (normal, effect, fusion, synchro, ritual), Level, ATK/DEF, race and
 * Attribute. Spells and Traps have a type: spells normal, quick, continuous, field, equip or ritual;
 * traps normal, continuous or counter.
 *
 * How a card plays is decided by {@link YgoScripts}: cards with their own script (looked up by
 * name) and the simple cards the importer recognised by wording, which carry an {@code effect}:
 * <ul>
 *   <li>spell: destroy_one, destroy_any, destroy_opp_all, destroy_all, destroy_lowest, draw, gain,
 *       burn, destroy_st, destroy_st_opp_all, destroy_st_all, revive (scope "either" or own GY),
 *       equip (equipAtk/equipDef, optional equipRace/equipAttr, equipStrict)</li>
 *   <li>trap: negate_attack (endBattle), destroy_attacker (+ burn), mirror_force, magic_cylinder,
 *       destroy_summoned (ATK &ge; value; flipToo)</li>
 * </ul>
 */
public final class YgoCard {
    public enum Kind { MONSTER, SPELL, TRAP }

    public final String id;
    public final String name;
    public final Kind kind;
    /** Monsters: normal, effect, fusion, synchro, ritual. */
    public final String frame;
    /** Spells: normal, quick, continuous, field, equip, ritual. Traps: normal, continuous, counter. */
    public final String stype;
    public final boolean tuner;
    public final int level;
    public final int atk;
    public final int def;
    public final String race;
    public final String attribute;
    public final String effect;
    public final int value;
    public final int equipAtk;
    public final int equipDef;
    public final String equipRace;
    public final String equipAttr;
    public final boolean equipStrict;
    public final boolean endBattle;
    public final int burn;
    public final String scope;
    public final boolean flipToo;
    public final boolean quick;
    public final String text;
    /** Passcodes (including alternate artworks), used to match .ydk deck lists. */
    public final List<String> codes;

    YgoCard(String id, Map<String, Object> m) {
        this.id = id;
        this.name = Json.str(m, "name", id);
        this.kind = Kind.valueOf(Json.str(m, "kind", "monster").toUpperCase());
        this.level = Json.num(m, "level", 0);
        this.atk = Json.num(m, "atk", 0);
        this.def = Json.num(m, "def", 0);
        this.race = Json.str(m, "race", "");
        this.attribute = Json.str(m, "attribute", "");
        this.effect = Json.str(m, "effect", "");
        this.value = Json.num(m, "value", 0);
        this.equipAtk = Json.num(m, "equipAtk", 0);
        this.equipDef = Json.num(m, "equipDef", 0);
        this.equipRace = Json.str(m, "equipRace", null);
        this.equipAttr = Json.str(m, "equipAttr", null);
        this.equipStrict = Boolean.TRUE.equals(m.get("equipStrict"));
        this.endBattle = Boolean.TRUE.equals(m.get("endBattle"));
        this.burn = Json.num(m, "burn", 0);
        this.scope = Json.str(m, "scope", "own");
        this.flipToo = Boolean.TRUE.equals(m.get("flipToo"));
        this.text = Json.str(m, "text", "");
        String st = Json.str(m, "stype", "");
        if (st.isEmpty()) {
            st = kind == Kind.MONSTER ? "" : Boolean.TRUE.equals(m.get("quick")) ? "quick" : effect.equals("equip") ? "equip" : "normal";
        }
        this.stype = st;
        this.quick = st.equals("quick");
        this.frame = kind == Kind.MONSTER ? Json.str(m, "frame", "normal") : "";
        this.tuner = Boolean.TRUE.equals(m.get("tuner"));
        List<String> c = new ArrayList<>();
        for (Object o : Json.arr(m.get("codes"))) {
            c.add(o instanceof Number n ? String.valueOf(n.longValue()) : o.toString());
        }
        this.codes = List.copyOf(c);
    }

    public int tributesNeeded() {
        return level >= 7 ? 2 : level >= 5 ? 1 : 0;
    }

    public boolean isMonster() {
        return kind == Kind.MONSTER;
    }

    /** Fusion and Synchro Monsters live in the Extra Deck. */
    public boolean isExtra() {
        return frame.equals("fusion") || frame.equals("synchro");
    }

    public boolean isNormalMonster() {
        return frame.equals("normal");
    }

    /** Whether an equip spell's bonus applies to (or, if strict, may be equipped to) this monster. */
    public boolean equipMatches(YgoCard monster) {
        if (equipRace != null && !equipRace.equalsIgnoreCase(monster.race)) {
            return false;
        }
        return equipAttr == null || equipAttr.equalsIgnoreCase(monster.attribute);
    }

    /** Whether the card text mentions {@code cardName} (in quotes, as card texts do). */
    public boolean mentions(String cardName) {
        return text.contains("\"" + cardName + "\"");
    }

    /** One-line description for menus. */
    public String summary() {
        return switch (kind) {
            case MONSTER -> name + " (" + (isExtra() ? capital(frame) + ", " : "") + "Lv" + level + ", " + atk + "/" + def + ")";
            case SPELL -> name + " [" + capital(stype) + " Spell]";
            case TRAP -> name + " [" + capital(stype) + " Trap]";
        };
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
