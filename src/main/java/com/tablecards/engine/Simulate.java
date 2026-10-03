package com.tablecards.engine;

import com.tablecards.engine.ptcg.PtcgGame;
import com.tablecards.engine.ptcg.PtcgLibrary;
import com.tablecards.engine.ygo.YgoGame;
import com.tablecards.engine.ygo.YgoLibrary;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless bot-vs-bot runner: plays many games of each card game and fails loudly on
 * exceptions, stalls or games that never end.
 *
 *   java -cp out com.tablecards.engine.Simulate [games] [ygo|ptcg|both]
 */
public final class Simulate {
    public static void main(String[] args) throws IOException {
        int games = args.length > 0 ? Integer.parseInt(args[0]) : 500;
        String which = args.length > 1 ? args[1] : "both";
        boolean ok = true;
        if (!which.equals("ptcg")) {
            YgoLibrary lib = YgoLibrary.parse(resource("/tablecards/ygo_cards.json"));
            List<String> decks = new ArrayList<>(lib.decks.keySet());
            ok &= run("Yu-Gi-Oh!", games, decks, seed -> {
                String d0 = decks.get((int) (seed % decks.size()));
                String d1 = decks.get((int) ((seed / 2) % decks.size()));
                return new YgoGame(new String[]{d0 + " bot", d1 + " bot"}, lib.decks.get(d0), lib.decks.get(d1), seed);
            });
        }
        if (!which.equals("ygo")) {
            PtcgLibrary lib = PtcgLibrary.parse(resource("/tablecards/ptcg_cards.json"));
            List<String> decks = new ArrayList<>(lib.decks.keySet());
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

    static String resource(String path) throws IOException {
        try (InputStream in = Simulate.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
