package com.tablecards.engine.ygo;

import com.tablecards.engine.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Card pool and decks. Several JSON sources can be added (built-in starter cards, imported real
 * cards); each is {"cards": {id: {...}}, "decks": {name: {id: count}}}.
 */
public final class YgoLibrary {
    public static final int MIN_DECK = 40;
    public static final int MAX_DECK = 60;
    public static final int MAX_COPIES = 3;

    public final Map<String, YgoCard> cards = new LinkedHashMap<>();
    public final Map<String, List<YgoCard>> decks = new LinkedHashMap<>();
    /** Passcode (any artwork) to card, for .ydk deck lists. */
    public final Map<String, YgoCard> byCode = new HashMap<>();

    public static YgoLibrary parse(String json) {
        YgoLibrary lib = new YgoLibrary();
        lib.add(json, "");
        return lib;
    }

    /** Adds the cards and decks of {@code json}; deck names get {@code deckPrefix} in front. */
    public void add(String json, String deckPrefix) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        Json.obj(root.get("cards")).forEach((id, v) -> {
            YgoCard c = new YgoCard(id, Json.obj(v));
            cards.put(id, c);
            for (String code : c.codes) {
                byCode.put(code, c);
            }
        });
        Map<String, Object> deckMap = root.get("decks") == null ? Map.of() : Json.obj(root.get("decks"));
        deckMap.forEach((name, v) -> {
            List<YgoCard> deck = new ArrayList<>();
            Json.obj(v).forEach((id, count) -> {
                YgoCard c = cards.get(id);
                if (c == null) {
                    throw new IllegalArgumentException("Deck " + name + " uses unknown card " + id);
                }
                for (int i = 0; i < ((Number) count).intValue(); i++) {
                    deck.add(c);
                }
            });
            String problem = validate(deck);
            if (problem != null) {
                throw new IllegalArgumentException("Deck " + name + ": " + problem);
            }
            decks.put(deckPrefix + name, deck);
        });
    }

    /** Null if the main deck is legal here, otherwise why not. */
    public static String validate(List<YgoCard> deck) {
        if (deck.size() < MIN_DECK || deck.size() > MAX_DECK) {
            return "has " + deck.size() + " cards (needs " + MIN_DECK + " to " + MAX_DECK + ")";
        }
        Map<String, Integer> copies = new HashMap<>();
        for (YgoCard c : deck) {
            copies.merge(c.name, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : copies.entrySet()) {
            if (e.getValue() > MAX_COPIES) {
                return "has " + e.getValue() + " copies of " + e.getKey() + " (max " + MAX_COPIES + ")";
            }
        }
        if (deck.stream().noneMatch(YgoCard::isMonster)) {
            return "has no monsters";
        }
        return null;
    }
}
