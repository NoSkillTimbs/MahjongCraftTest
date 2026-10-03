package com.tablecards.engine.ygo;

import com.tablecards.engine.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A card definition, read from the library JSON format (built-in and imported real cards share
 * it). Monsters have level/ATK/DEF (and race/attribute for imported cards). Spells and traps have
 * an {@code effect}:
 * <ul>
 *   <li>spell: destroy_one (target 1 your opponent controls), destroy_any (target 1 on the field),
 *       destroy_opp_all, destroy_all, destroy_lowest, draw, gain, burn, destroy_st (target 1 on the
 *       field), destroy_st_opp_all, destroy_st_all, revive (scope "either" or own GY), equip</li>
 *   <li>trap: negate_attack (endBattle), destroy_attacker (+ burn), mirror_force, magic_cylinder
 *       (respond to an attack); destroy_summoned (respond to a Normal - and if flipToo, Flip -
 *       Summon of a monster with ATK &ge; value)</li>
 * </ul>
 * Equip spells: equipAtk/equipDef (may be negative), optional equipRace or equipAttr; with
 * equipStrict the card can only be equipped to a matching monster, otherwise it can be equipped to
 * any monster but only a matching one gets the bonus.
 */
public final class YgoCard {
    public enum Kind { MONSTER, SPELL, TRAP }

    public final String id;
    public final String name;
    public final Kind kind;
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
        this.quick = Boolean.TRUE.equals(m.get("quick"));
        this.text = Json.str(m, "text", "");
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

    /** Whether an equip spell's bonus applies to (or, if strict, may be equipped to) this monster. */
    public boolean equipMatches(YgoCard monster) {
        if (equipRace != null && !equipRace.equalsIgnoreCase(monster.race)) {
            return false;
        }
        return equipAttr == null || equipAttr.equalsIgnoreCase(monster.attribute);
    }

    /** One-line description for hands and menus. */
    public String summary() {
        return switch (kind) {
            case MONSTER -> name + " (Lv" + level + ", " + atk + "/" + def
                    + (race.isEmpty() ? "" : ", " + attribute + " " + race) + ")";
            case SPELL -> name + " [" + (quick ? "Quick-Play Spell" : "Spell") + ": " + text + "]";
            case TRAP -> name + " [Trap: " + text + "]";
        };
    }
}
