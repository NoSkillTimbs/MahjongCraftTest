package com.shandalar.engine;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Headless AI-vs-AI runner for balancing decks and smoke-testing the engine.
 * Usage: Simulate [cards.json] [games] [sample-log-file]
 */
public final class Simulate {
    private static final int MAX_TURNS = 80;

    public static void main(String[] args) throws IOException {
        Path cardsFile = Path.of(args.length > 0 ? args[0] : "src/main/resources/data/shandalar/cards.json");
        int games = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        String sampleLog = args.length > 2 ? args[2] : null;

        CardLibrary lib = CardLibrary.load(cardsFile);
        System.out.println("Loaded " + lib.size() + " cards");

        Map<String, Integer> stompers = new LinkedHashMap<>();
        stompers.put("Mountain", 8);
        stompers.put("Forest", 9);
        stompers.put("Grizzly Bears", 4);
        stompers.put("Hurloon Minotaur", 3);
        stompers.put("Hill Giant", 3);
        stompers.put("Giant Spider", 3);
        stompers.put("Craw Wurm", 2);
        stompers.put("Lightning Bolt", 4);
        stompers.put("Shock", 4);

        Map<String, Integer> fliers = new LinkedHashMap<>();
        fliers.put("Plains", 8);
        fliers.put("Island", 9);
        fliers.put("Suntail Hawk", 4);
        fliers.put("White Knight", 4);
        fliers.put("Wind Drake", 4);
        fliers.put("Serra Angel", 3);
        fliers.put("Air Elemental", 3);
        fliers.put("Divination", 2);
        fliers.put("Healing Salve", 2);
        fliers.put("Ancestral Recall", 1);

        List<CardDef> deckA = lib.buildDeck(stompers);
        List<CardDef> deckB = lib.buildDeck(fliers);
        check("Red-Green Stompers", deckA);
        check("White-Blue Fliers", deckB);

        int winsA = 0;
        int winsB = 0;
        int draws = 0;
        long turns = 0;
        for (int i = 0; i < games; i++) {
            boolean aFirst = i % 2 == 0; // alternate who plays first
            PrintStream log = null;
            if (i == 0 && sampleLog != null) {
                log = new PrintStream(new FileOutputStream(sampleLog), true);
            }
            Game g = aFirst
                    ? new Game("Stompers", deckA, new SimpleAI(), "Fliers", deckB, new SimpleAI(), i, log)
                    : new Game("Fliers", deckB, new SimpleAI(), "Stompers", deckA, new SimpleAI(), i, log);
            Player w = g.run(MAX_TURNS);
            turns += g.turn;
            if (w == null) {
                draws++;
            } else if (w.name.equals("Stompers")) {
                winsA++;
            } else {
                winsB++;
            }
            if (log != null) {
                log.close();
            }
        }
        System.out.printf("%d games: Stompers %d, Fliers %d, draws %d, avg turns %.1f%n",
                games, winsA, winsB, draws, turns / (double) games);
    }

    private static void check(String name, List<CardDef> deck) {
        if (deck.size() != 40) {
            throw new IllegalStateException(name + " has " + deck.size() + " cards, expected 40");
        }
    }
}
