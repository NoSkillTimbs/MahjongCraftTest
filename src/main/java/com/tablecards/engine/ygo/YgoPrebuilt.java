package com.tablecards.engine.ygo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ready-made archetype decks, built from real cards. Each list follows the core of current
 * competitive/popular builds of that archetype (YGOPRODeck and Master Duel Meta lists, 2025),
 * keeping to cards this game plays exactly as printed; generic Link/Synchro/Xyz staples those
 * lists also run are left out until the game supports them.
 */
public final class YgoPrebuilt {
    private YgoPrebuilt() {
    }

    /** Deck name -> card name -> copies (Main and Extra Deck together). */
    static final Map<String, Map<String, Integer>> LISTS = new LinkedHashMap<>();

    private static void deck(String name, Object... cardsAndCounts) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < cardsAndCounts.length; i += 2) {
            m.put((String) cardsAndCounts[i], (Integer) cardsAndCounts[i + 1]);
        }
        LISTS.put(name, m);
    }

    static {
        deck("Blue-Eyes White Dragon Deck",
                // monsters (24)
                "Blue-Eyes White Dragon", 3,
                "Blue-Eyes Alternative White Dragon", 3,
                "Dragon Spirit of White", 3,
                "Sage with Eyes of Blue", 3,
                "The White Stone of Ancients", 3,
                "The White Stone of Legend", 2,
                "Blue-Eyes Chaos MAX Dragon", 2,
                "Kaibaman", 2,
                "Ash Blossom & Joyous Spring", 3,
                // spells (16)
                "Trade-In", 3,
                "Cards of Consonance", 3,
                "Chaos Form", 2,
                "Silver's Cry", 2,
                "Dragon Shrine", 2,
                "Return of the Dragon Lords", 2,
                "Polymerization", 1,
                "Monster Reborn", 1,
                // extra deck
                "Blue-Eyes Ultimate Dragon", 1,
                "Blue-Eyes Twin Burst Dragon", 2,
                "Blue-Eyes Spirit Dragon", 2,
                "Azure-Eyes Silver Dragon", 2);

        deck("Dark Magician Deck",
                // monsters (19)
                "Dark Magician", 3,
                "Dark Magician Girl", 3,
                "Magician's Rod", 3,
                "Apprentice Illusion Magician", 2,
                "Palladium Oracle Mahad", 1,
                "Magicians' Souls", 3,
                "Red-Eyes Black Dragon", 1,
                "Ash Blossom & Joyous Spring", 3,
                // spells (16)
                "Dark Magical Circle", 2,
                "Illusion Magic", 1,
                "Secrets of Dark Magic", 2,
                "Dark Magic Attack", 1,
                "Thousand Knives", 1,
                "The Eye of Timaeus", 2,
                "Bond Between Teacher and Student", 1,
                "Sage's Stone", 1,
                "Dark Magic Twin Burst", 1,
                "Dark Burning Attack", 1,
                "Dark Burning Magic", 1,
                "Magician's Salvation", 1,
                "Dark Magic Veil", 1,
                // traps (5)
                "Eternal Soul", 2,
                "Magic Cylinder", 2,
                "Mirror Force", 1,
                // extra deck
                "Dark Magician the Dragon Knight", 1,
                "Dark Magician Girl the Dragon Knight", 1,
                "The Dark Magicians", 2);

        deck("Elemental HERO Deck",
                // monsters (22)
                "Elemental HERO Neos", 1,
                "Elemental HERO Stratos", 3,
                "Elemental HERO Bubbleman", 2,
                "Elemental HERO Sparkman", 3,
                "Elemental HERO Avian", 3,
                "Elemental HERO Burstinatrix", 3,
                "Elemental HERO Clayman", 3,
                "Elemental HERO Wildheart", 2,
                "Elemental HERO Shadow Mist", 1,
                "Winged Kuriboh", 1,
                // spells (14)
                "Polymerization", 3,
                "Miracle Fusion", 1,
                "E - Emergency Call", 2,
                "A Hero Lives", 1,
                "O - Oversoul", 1,
                "H - Heated Heart", 1,
                "HERO's Bond", 1,
                "Fusion Recovery", 2,
                "Skyscraper", 2,
                // traps (4)
                "Hero Signal", 3,
                "Hero Barrier", 1,
                // extra deck
                "Elemental HERO Flame Wingman", 1,
                "Elemental HERO Thunder Giant", 1,
                "Elemental HERO Rampart Blaster", 1,
                "Elemental HERO Wild Wingman", 1,
                "Elemental HERO Steam Healer", 1,
                "Elemental HERO Shining Flare Wingman", 1,
                "Elemental HERO Tempest", 1,
                "Elemental HERO Great Tornado", 1,
                "Elemental HERO Absolute Zero", 1);
    }

    /**
     * The prebuilt decks that can be made from {@code lib}. A deck with a card that wasn't
     * imported is left out, and the reason added to {@code problems}.
     */
    public static Map<String, List<YgoCard>> build(YgoLibrary lib, List<String> problems) {
        Map<String, YgoCard> byName = new HashMap<>();
        for (YgoCard c : lib.cards.values()) {
            byName.putIfAbsent(c.name, c);
        }
        Map<String, List<YgoCard>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : LISTS.entrySet()) {
            List<YgoCard> deck = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            e.getValue().forEach((name, n) -> {
                YgoCard c = byName.get(name);
                if (c == null) {
                    if (n > 0) missing.add(name);
                    return;
                }
                for (int i = 0; i < n; i++) {
                    deck.add(c);
                }
            });
            if (!missing.isEmpty()) {
                problems.add(e.getKey() + " is missing " + String.join(", ", missing) + " (run /tablecards import yugioh)");
                continue;
            }
            String v = YgoLibrary.validate(deck);
            if (v != null) {
                problems.add(e.getKey() + " " + v);
                continue;
            }
            out.put(e.getKey(), deck);
        }
        return out;
    }

    /** Every card name the prebuilt decks use. */
    public static List<String> cardNames() {
        List<String> out = new ArrayList<>();
        LISTS.values().forEach(m -> m.forEach((n, k) -> {
            if (k > 0 && !out.contains(n)) out.add(n);
        }));
        return out;
    }
}
