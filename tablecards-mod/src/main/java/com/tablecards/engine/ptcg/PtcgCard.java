package com.tablecards.engine.ptcg;

import com.tablecards.engine.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A card definition, read from the library JSON format (built-in cards and imported real cards
 * share it).
 * <ul>
 *   <li>pokemon: stage 0/1/2, evolvesFrom (a card id or a Pokemon name), type, hp, weakness
 *       (+ weaknessPlus for old "+20" weakness, otherwise x2), resistance (+ resistanceValue,
 *       default 30), retreat, prizes (1, 2 for ex/V/GX/EX, 3 for VMAX/TAG TEAM), benchProtected
 *       (Tera), attacks</li>
 *   <li>energy: type (basic Energy only)</li>
 *   <li>item / supporter: effect + value (see {@link PtcgGame#canPlayTrainer})</li>
 * </ul>
 * Types are lowercase: fire, water, grass, lightning, psychic, fighting, darkness, metal, fairy,
 * dragon, colorless.
 */
public final class PtcgCard {
    public enum Kind { POKEMON, ENERGY, ITEM, SUPPORTER }

    /**
     * An attack: energy cost, base damage and its effects. Every field is optional; together they
     * cover the attack wordings the importer recognises (see PtcgImporter).
     */
    public static final class Attack {
        public final String name;
        public final List<String> cost;
        public final int damage;
        /** Special Conditions put on the opponent's Active Pokemon (always / if a coin is heads). */
        public final List<String> status;
        public final List<String> coinStatus;
        public final boolean tailsNothing;
        public final boolean eitherTailsNothing;
        public final int headsBonus;
        /** coinTimes: flip n coins, damage = per x heads. coinBonus: base damage + per x heads. */
        public final int coinTimesN, coinTimesPer;
        public final int coinBonusN, coinBonusPer;
        public final int perCounter;
        public final int draw;
        public final int drawUntil;
        public final int heal;
        public final boolean healDealt;
        public final int healOne;
        public final int selfDamage;
        public final int discardEnergy;
        public final boolean discardOppEnergy;
        public final boolean coinDiscardOppEnergy;
        public final boolean cantAttackNext;
        public final boolean coinDefenderCantAttack;
        public final boolean ignoreResistance;
        public final boolean ignoreEffects;
        public final boolean noRetreat;
        public final String selfSwitch;
        public final boolean forceSwitch;
        public final int benchSnipe;
        public final int snipeAny;
        public final int mill;
        public final boolean discardRandom;
        public final boolean coinProtect;
        public final int reduceNext;
        public final int coinUntilTailsPer;
        public final int coinUntilTailsBonus;
        public final boolean smokescreen;
        public final int benchSearch;
        public final boolean revealHand;

        Attack(Map<String, Object> m) {
            name = Json.str(m, "name", "Attack");
            cost = strings(m.get("cost"));
            damage = Json.num(m, "damage", 0);
            status = strings(m.get("status"));
            coinStatus = strings(m.get("coinStatus"));
            tailsNothing = bool(m, "tailsNothing");
            eitherTailsNothing = bool(m, "eitherTailsNothing");
            headsBonus = Json.num(m, "headsBonus", 0);
            List<Object> ct = Json.arr(m.get("coinTimes"));
            coinTimesN = ct.size() == 2 ? ((Number) ct.get(0)).intValue() : 0;
            coinTimesPer = ct.size() == 2 ? ((Number) ct.get(1)).intValue() : 0;
            List<Object> cb = Json.arr(m.get("coinBonus"));
            coinBonusN = cb.size() == 2 ? ((Number) cb.get(0)).intValue() : 0;
            coinBonusPer = cb.size() == 2 ? ((Number) cb.get(1)).intValue() : 0;
            perCounter = Json.num(m, "perCounter", 0);
            draw = Json.num(m, "draw", 0);
            drawUntil = Json.num(m, "drawUntil", 0);
            heal = Json.num(m, "heal", 0);
            healDealt = bool(m, "healDealt");
            healOne = Json.num(m, "healOne", 0);
            selfDamage = Json.num(m, "selfDamage", 0);
            discardEnergy = Json.num(m, "discardEnergy", 0);
            discardOppEnergy = bool(m, "discardOppEnergy");
            coinDiscardOppEnergy = bool(m, "coinDiscardOppEnergy");
            cantAttackNext = bool(m, "cantAttackNext");
            coinDefenderCantAttack = bool(m, "coinDefenderCantAttack");
            ignoreResistance = bool(m, "ignoreResistance");
            ignoreEffects = bool(m, "ignoreEffects");
            noRetreat = bool(m, "noRetreat");
            selfSwitch = Json.str(m, "selfSwitch", null);
            forceSwitch = bool(m, "forceSwitch");
            benchSnipe = Json.num(m, "benchSnipe", 0);
            snipeAny = Json.num(m, "snipeAny", 0);
            mill = Json.num(m, "mill", 0);
            discardRandom = bool(m, "discardRandom");
            coinProtect = bool(m, "coinProtect");
            reduceNext = Json.num(m, "reduceNext", 0);
            coinUntilTailsPer = Json.num(m, "coinUntilTailsPer", 0);
            coinUntilTailsBonus = Json.num(m, "coinUntilTailsBonus", 0);
            smokescreen = bool(m, "smokescreen");
            benchSearch = Json.num(m, "benchSearch", 0);
            revealHand = bool(m, "revealHand");
        }

        /** Short description in our own words, for the board view and buttons. */
        public String summary() {
            StringBuilder b = new StringBuilder(name).append(" [");
            for (int i = 0; i < cost.size(); i++) {
                b.append(i > 0 ? " " : "").append(PtcgCard.typeName(cost.get(i)));
            }
            b.append("]");
            if (coinTimesN > 0) {
                b.append(" ").append(coinTimesPer).append("x heads of ").append(coinTimesN);
            } else if (coinUntilTailsPer > 0) {
                b.append(" ").append(coinUntilTailsPer).append("x heads until tails");
            } else if (damage > 0 || (headsBonus == 0 && coinBonusN == 0 && perCounter == 0 && coinUntilTailsBonus == 0)) {
                b.append(" ").append(damage);
            }
            List<String> fx = new ArrayList<>();
            if (headsBonus > 0) fx.add("heads: +" + headsBonus);
            if (coinBonusN > 0) fx.add("+" + coinBonusPer + " per heads of " + coinBonusN);
            if (perCounter > 0) fx.add("+" + perCounter + " per damage counter on itself");
            if (tailsNothing) fx.add("tails: nothing");
            if (eitherTailsNothing) fx.add("flip 2, any tails: nothing");
            if (!status.isEmpty()) fx.add(String.join(" + ", status));
            if (!coinStatus.isEmpty()) fx.add("heads: " + String.join(" + ", coinStatus));
            if (draw > 0) fx.add("draw " + draw);
            if (drawUntil > 0) fx.add("may draw up to " + drawUntil + " in hand");
            if (heal > 0) fx.add("heal " + heal);
            if (healDealt) fx.add("heal the damage dealt");
            if (healOne > 0) fx.add("heal " + healOne + " from 1 of yours");
            if (selfDamage > 0) fx.add(selfDamage + " to itself");
            if (discardEnergy > 0) fx.add(discardEnergy >= 99 ? "discard all its Energy" : "discard " + discardEnergy + " Energy");
            if (discardOppEnergy) fx.add("discard 1 Energy from the Defending");
            if (coinDiscardOppEnergy) fx.add("heads: discard 1 Energy from the Defending");
            if (cantAttackNext) fx.add("can't attack next turn");
            if (coinDefenderCantAttack) fx.add("heads: Defending can't attack");
            if (ignoreResistance) fx.add("ignores Resistance");
            if (ignoreEffects) fx.add("ignores effects on the Defending");
            if (noRetreat) fx.add("Defending can't retreat");
            if (selfSwitch != null) fx.add((selfSwitch.equals("may") ? "may " : "") + "switch itself out");
            if (forceSwitch) fx.add("opponent switches their Active");
            if (benchSnipe > 0) fx.add(benchSnipe + " to 1 Benched");
            if (snipeAny > 0) fx.add(snipeAny + " to any 1 of theirs");
            if (mill > 0) fx.add("discard top " + mill + " of their deck");
            if (discardRandom) fx.add("discard a random card from their hand");
            if (coinProtect) fx.add("heads: protected next turn");
            if (reduceNext > 0) fx.add("takes " + reduceNext + " less next turn");
            if (coinUntilTailsBonus > 0) fx.add("+" + coinUntilTailsBonus + " per heads until tails");
            if (smokescreen) fx.add("Defending flips to attack next turn");
            if (benchSearch > 0) fx.add("bench up to " + benchSearch + " Basic from deck");
            if (revealHand) fx.add("reveals their hand");
            if (!fx.isEmpty()) {
                b.append(" (").append(String.join("; ", fx)).append(")");
            }
            return b.toString();
        }
    }

    public final String id;
    public final String name;
    public final Kind kind;
    public final int stage;
    /** As written in the JSON (an id for built-in cards, a name for imported ones); see {@link #evolvesFromName}. */
    public final String evolvesFrom;
    /** The name of the Pokemon this evolves from, resolved by the library. */
    String evolvesFromName;
    /** For a Stage 2: the name of the Basic at the start of its line (for Rare Candy), when known. */
    String rootName;
    public final String type;
    public final int hp;
    public final String weakness;
    public final int weaknessPlus;
    public final String resistance;
    public final int resistanceValue;
    public final int retreat;
    public final int prizes;
    public final boolean benchProtected;
    public final List<Attack> attacks;
    public final String effect;
    public final int value;
    public final String text;
    /** Set code (e.g. "SVI") and collector number, used to match deck lists. */
    public final String set;
    public final String number;
    public final boolean aceSpec;
    /** Card art path on images.pokemontcg.io ("sv1/25"), or "" for the mod's own cards. */
    public final String image;

    PtcgCard(String id, Map<String, Object> m) {
        this.id = id;
        this.name = Json.str(m, "name", id);
        this.kind = Kind.valueOf(Json.str(m, "kind", "pokemon").toUpperCase());
        this.stage = Json.num(m, "stage", 0);
        this.evolvesFrom = Json.str(m, "evolvesFrom", null);
        this.evolvesFromName = evolvesFrom;
        this.rootName = Json.str(m, "rootName", null);
        this.type = Json.str(m, "type", "colorless");
        this.hp = Json.num(m, "hp", 0);
        this.weakness = Json.str(m, "weakness", null);
        this.weaknessPlus = Json.num(m, "weaknessPlus", 0);
        this.resistance = Json.str(m, "resistance", null);
        this.resistanceValue = Json.num(m, "resistanceValue", 30);
        this.retreat = Json.num(m, "retreat", 0);
        this.prizes = Json.num(m, "prizes", 1);
        this.benchProtected = bool(m, "benchProtected");
        List<Attack> a = new ArrayList<>();
        for (Object o : Json.arr(m.get("attacks"))) {
            a.add(new Attack(Json.obj(o)));
        }
        this.attacks = List.copyOf(a);
        this.effect = Json.str(m, "effect", "");
        this.value = Json.num(m, "value", 0);
        this.text = Json.str(m, "text", "");
        this.set = Json.str(m, "set", "");
        this.number = Json.str(m, "number", "");
        this.aceSpec = bool(m, "aceSpec");
        // imported cards are keyed by their data set id ("sv1-25"); the art lives at sv1/25
        String img = Json.str(m, "image", "");
        if (img.isEmpty() && !set.isEmpty() && id.lastIndexOf('-') > 0 && !number.isEmpty()) {
            img = id.substring(0, id.lastIndexOf('-')) + "/" + number;
        }
        this.image = img.matches("[a-z0-9.]{1,24}/[A-Za-z0-9-]{1,12}") ? img : "";
    }

    public String evolvesFromName() {
        return evolvesFromName;
    }

    public boolean isBasic() {
        return kind == Kind.POKEMON && stage == 0;
    }

    public static String typeName(String type) {
        return type == null || type.isEmpty() ? "" : Character.toUpperCase(type.charAt(0)) + type.substring(1);
    }

    public String summary() {
        return switch (kind) {
            case POKEMON -> name + " (" + (stage == 0 ? "Basic" : "Stage " + stage) + ", " + typeName(type) + ", " + hp + " HP"
                    + (prizes > 1 ? ", " + prizes + " Prizes" : "") + ")";
            case ENERGY -> typeName(type) + " Energy";
            case ITEM -> name + " [Item: " + text + "]";
            case SUPPORTER -> name + " [Supporter: " + text + "]";
        };
    }

    static boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Boolean b && b;
    }

    static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        for (Object x : Json.arr(o)) {
            out.add(x.toString());
        }
        return List.copyOf(out);
    }
}
