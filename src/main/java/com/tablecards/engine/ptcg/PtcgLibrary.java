package com.tablecards.engine.ptcg;

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
public final class PtcgLibrary {
    public static final int DECK_SIZE = 60;
    public static final int MAX_COPIES = 4;

    public final Map<String, PtcgCard> cards = new LinkedHashMap<>();
    public final Map<String, List<PtcgCard>> decks = new LinkedHashMap<>();

    public static PtcgLibrary parse(String json) {
        PtcgLibrary lib = new PtcgLibrary();
        lib.add(json, "");
        return lib;
    }

    /** Adds the cards and decks of {@code json}; deck names get {@code deckPrefix} in front. */
    public void add(String json, String deckPrefix) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        Json.obj(root.get("cards")).forEach((id, v) -> cards.put(id, new PtcgCard(id, Json.obj(v))));
        resolve();
        Map<String, Object> deckMap = root.get("decks") == null ? Map.of() : Json.obj(root.get("decks"));
        deckMap.forEach((name, v) -> {
            List<PtcgCard> deck = new ArrayList<>();
            Json.obj(v).forEach((id, count) -> {
                PtcgCard c = cards.get(id);
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

    /** Turns card-id references in evolvesFrom into names, and fills in Rare Candy roots. */
    private void resolve() {
        Map<String, PtcgCard> byName = new HashMap<>();
        for (PtcgCard c : cards.values()) {
            if (c.evolvesFrom != null && cards.containsKey(c.evolvesFrom)) {
                c.evolvesFromName = cards.get(c.evolvesFrom).name;
            }
            byName.putIfAbsent(c.name, c);
        }
        for (PtcgCard c : cards.values()) {
            if (c.stage == 2 && c.rootName == null && c.evolvesFromName != null) {
                PtcgCard stage1 = byName.get(c.evolvesFromName);
                if (stage1 != null) {
                    c.rootName = stage1.evolvesFromName;
                }
            }
        }
    }

    /** Null if the deck is legal here, otherwise why not. */
    public static String validate(List<PtcgCard> deck) {
        if (deck.size() != DECK_SIZE) {
            return "has " + deck.size() + " cards (needs exactly " + DECK_SIZE + ")";
        }
        if (deck.stream().noneMatch(PtcgCard::isBasic)) {
            return "has no Basic Pokemon";
        }
        Map<String, Integer> copies = new HashMap<>();
        int aceSpecs = 0;
        for (PtcgCard c : deck) {
            if (c.kind != PtcgCard.Kind.ENERGY) {
                copies.merge(c.name, 1, Integer::sum);
            }
            if (c.aceSpec) {
                aceSpecs++;
            }
        }
        for (Map.Entry<String, Integer> e : copies.entrySet()) {
            if (e.getValue() > MAX_COPIES) {
                return "has " + e.getValue() + " copies of " + e.getKey() + " (max " + MAX_COPIES + ")";
            }
        }
        if (aceSpecs > 1) {
            return "has more than 1 ACE SPEC card";
        }
        return null;
    }
}
