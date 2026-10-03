package com.shandalar.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All known cards by name. Loads a JSON array of card objects. */
public final class CardLibrary {
    private final Map<String, CardDef> cards = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public static CardLibrary parse(String json) {
        CardLibrary lib = new CardLibrary();
        for (Object o : (List<Object>) Json.parse(json)) {
            CardDef def = new CardDef((Map<String, Object>) o);
            lib.cards.put(def.name, def);
        }
        return lib;
    }

    public static CardLibrary load(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads from the classpath, which is how the mod jar ships its cards. */
    public static CardLibrary loadResource(String resourcePath) {
        try (InputStream in = CardLibrary.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing resource " + resourcePath);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public CardDef get(String name) {
        CardDef c = cards.get(name);
        if (c == null) {
            throw new IllegalArgumentException("Unknown card: " + name);
        }
        return c;
    }

    public int size() {
        return cards.size();
    }

    /** Expands a name-to-count map into a flat list of cards. */
    public List<CardDef> buildDeck(Map<String, Integer> counts) {
        List<CardDef> deck = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            CardDef def = get(e.getKey());
            for (int i = 0; i < e.getValue(); i++) {
                deck.add(def);
            }
        }
        return deck;
    }
}
