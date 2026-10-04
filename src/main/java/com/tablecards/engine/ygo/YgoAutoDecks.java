package com.tablecards.engine.ygo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds 40-card decks out of an imported card pool, one per Attribute: its strongest Normal
 * Monsters by level band, plus the imported classic Spells and Traps. Deterministic.
 */
public final class YgoAutoDecks {
    private YgoAutoDecks() {
    }

    private static final List<String> ATTRIBUTES = List.of("LIGHT", "DARK", "EARTH", "WATER", "FIRE", "WIND");

    /** Spell/Trap effects to include, with how many copies (the strongest card of each effect is used). */
    private static final List<Object[]> SUPPORT = List.of(
            new Object[]{"draw", 1}, new Object[]{"destroy_opp_all", 1}, new Object[]{"destroy_all", 1}, new Object[]{"revive", 1},
            new Object[]{"destroy_st", 2}, new Object[]{"destroy_st_opp_all", 1}, new Object[]{"destroy_lowest", 1},
            new Object[]{"burn", 1}, new Object[]{"gain", 1}, new Object[]{"mirror_force", 1}, new Object[]{"magic_cylinder", 1},
            new Object[]{"destroy_attacker", 2}, new Object[]{"negate_attack", 1}, new Object[]{"destroy_summoned", 2});

    public static Map<String, List<YgoCard>> build(YgoLibrary lib) {
        List<YgoCard> real = new ArrayList<>();
        for (YgoCard c : lib.cards.values()) {
            if (!c.codes.isEmpty()) {
                real.add(c); // imported cards only
            }
        }
        Map<String, YgoCard> supportByEffect = new HashMap<>();
        for (YgoCard c : real) {
            if (c.isMonster() || c.effect.isEmpty() || c.effect.equals("equip")) {
                continue;
            }
            YgoCard cur = supportByEffect.get(c.effect);
            if (cur == null || c.value > cur.value) {
                supportByEffect.put(c.effect, c);
            }
        }
        Map<String, List<YgoCard>> decks = new LinkedHashMap<>();
        for (String attr : ATTRIBUTES) {
            List<YgoCard> monsters = new ArrayList<>();
            for (YgoCard c : real) {
                if (c.isMonster() && c.isNormalMonster() && attr.equals(c.attribute)) {
                    monsters.add(c);
                }
            }
            monsters.sort(Comparator.comparingInt((YgoCard c) -> -(c.atk * 2 + c.def)).thenComparing(c -> c.id));
            List<YgoCard> deck = new ArrayList<>();
            List<String> used = new ArrayList<>();
            add(deck, used, monsters, 1, 4, 5, 3);
            add(deck, used, monsters, 5, 6, 2, 2);
            add(deck, used, monsters, 7, 12, 3, 1);
            add(deck, used, monsters, 1, 4, 3, 3); // top up with more small monsters if a band was short
            while (deck.size() > 22) {
                deck.remove(deck.size() - 1);
            }
            if (deck.size() < 18) {
                continue;
            }
            YgoCard star = deck.stream().max(Comparator.comparingInt(c -> c.atk)).orElse(deck.get(0));
            YgoCard equip = bestEquip(real, attr, deck);
            if (equip != null) {
                deck.add(equip);
                deck.add(equip);
            }
            for (Object[] s : SUPPORT) {
                YgoCard c = supportByEffect.get((String) s[0]);
                for (int k = 0; c != null && k < (int) s[1] && deck.size() < YgoLibrary.MIN_DECK; k++) {
                    deck.add(c);
                }
            }
            // fill any remaining space with more monsters
            for (YgoCard m : monsters) {
                if (deck.size() >= YgoLibrary.MIN_DECK) {
                    break;
                }
                if (m.level <= 4 && deck.stream().filter(x -> x.name.equals(m.name)).count() < YgoLibrary.MAX_COPIES) {
                    deck.add(m);
                }
            }
            if (YgoLibrary.validate(deck) == null) {
                decks.put("Auto: " + attr + " (" + star.name + ")", deck);
            }
        }
        return decks;
    }

    /** Adds up to {@code names} new monster names with level in [lo, hi], {@code copies} each. */
    private static void add(List<YgoCard> deck, List<String> used, List<YgoCard> pool, int lo, int hi, int names, int copies) {
        int added = 0;
        for (YgoCard m : pool) {
            if (added >= names) {
                return;
            }
            if (m.level < lo || m.level > hi || used.contains(m.name)) {
                continue;
            }
            used.add(m.name);
            for (int k = 0; k < copies; k++) {
                deck.add(m);
            }
            added++;
        }
    }

    /** The equip spell that helps this deck's monsters most. */
    private static YgoCard bestEquip(List<YgoCard> real, String attr, List<YgoCard> deck) {
        YgoCard best = null;
        int bestScore = 0;
        for (YgoCard c : real) {
            if (!c.effect.equals("equip") || c.equipAtk <= 0) {
                continue;
            }
            long fits = deck.stream().filter(m -> (c.equipRace == null && c.equipAttr == null) || c.equipMatches(m)).count();
            int score = (int) (c.equipAtk * fits / deck.size());
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return bestScore >= 150 ? best : null;
    }
}
