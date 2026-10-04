package com.tablecards.engine;

import com.tablecards.engine.ptcg.PtcgGame;
import com.tablecards.engine.ptcg.PtcgLibrary;
import com.tablecards.engine.ygo.YgoGame;
import com.tablecards.engine.ygo.YgoLibrary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Headless bot-vs-bot runner with real cards: plays many games of each card game, using the
 * decks built from an imported card library (see {@link ImportFiles}), and fails loudly on
 * exceptions, stalls or games that never end.
 *
 *   java -cp out com.tablecards.engine.Simulate games ygo|ptcg|both ygo-library.json ptcg-library.json
 */
public final class Simulate {
    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.out.println("usage: Simulate games ygo|ptcg|both ygo-library.json ptcg-library.json");
            System.exit(2);
        }
        int games = Integer.parseInt(args[0]);
        String which = args[1];
        boolean ok = true;
        if (!which.equals("ptcg")) {
            YgoLibrary lib = YgoLibrary.parse(Files.readString(Path.of(args[2]), StandardCharsets.UTF_8));
            Map<String, List<com.tablecards.engine.ygo.YgoCard>> all = new LinkedHashMap<>(lib.decks);
            List<String> problems = new ArrayList<>();
            all.putAll(com.tablecards.engine.ygo.YgoPrebuilt.build(lib, problems));
            problems.forEach(p -> System.out.println("  deck problem: " + p));
            all.putAll(com.tablecards.engine.ygo.YgoAutoDecks.build(lib));
            lib.decks.putAll(all);
            List<String> decks = new ArrayList<>(lib.decks.keySet());
            System.out.println("Yu-Gi-Oh!: " + lib.cards.size() + " cards, decks " + decks);
            ok &= run("Yu-Gi-Oh!", games, decks, seed -> {
                String d0 = decks.get((int) (seed % decks.size()));
                String d1 = decks.get((int) ((seed / 2) % decks.size()));
                return new YgoGame(new String[]{d0 + " bot", d1 + " bot"}, lib.decks.get(d0), lib.decks.get(d1), seed);
            });
        }
        if (!which.equals("ygo")) {
            PtcgLibrary lib = PtcgLibrary.parse(Files.readString(Path.of(args[3]), StandardCharsets.UTF_8));
            lib.decks.putAll(com.tablecards.engine.ptcg.PtcgAutoDecks.build(lib));
            List<String> decks = new ArrayList<>(lib.decks.keySet());
            System.out.println("Pokemon TCG: " + lib.cards.size() + " cards, decks " + decks);
            ok &= run("Pokemon TCG", games, decks, seed -> {
                String d0 = decks.get((int) (seed % decks.size()));
                String d1 = decks.get((int) ((seed / 2) % decks.size()));
                return new PtcgGame(new String[]{d0 + " bot", d1 + " bot"}, lib.decks.get(d0), lib.decks.get(d1), seed);
            });
        }
        if (!ok) {
            System.exit(1);
        }
    }

    interface Factory {
        CardGame make(long seed);
    }

    private static boolean run(String title, int games, List<String> decks, Factory factory) {
        int[] wins = new int[3];
        long decisions = 0;
        int failures = 0;
        String sample = null;
        for (int i = 0; i < games; i++) {
            long seed = 1000L + i;
            try {
                CardGame g = factory.make(seed);
                Bot bot = g.bot();
                int steps = 0;
                while (!g.isOver()) {
                    Decision d = g.pending();
                    if (d == null) {
                        throw new IllegalStateException("No decision but game not over");
                    }
                    g.view(0);
                    g.view(1);
                    if (!g.choose(d.player(), bot.choose(g, d))) {
                        throw new IllegalStateException("Bot made an invalid choice");
                    }
                    if (++steps > 20000) {
                        throw new IllegalStateException("Game did not end after 20000 decisions");
                    }
                }
                decisions += steps;
                wins[g.winner() < 0 ? 2 : g.winner()]++;
                if (sample == null) {
                    sample = g.endReason();
                }
            } catch (RuntimeException e) {
                failures++;
                if (failures <= 3) {
                    System.out.println(title + " game seed " + seed + " failed: " + e);
                    e.printStackTrace(System.out);
                }
            }
        }
        System.out.printf("%s: %d games, first-seat wins %d, second-seat wins %d, no winner %d, avg %.0f decisions, failures %d%n",
                title, games, wins[0], wins[1], wins[2], decisions / (double) Math.max(1, games - failures), failures);
        System.out.println("  e.g. \"" + sample + "\"");
        return failures == 0;
    }
}
