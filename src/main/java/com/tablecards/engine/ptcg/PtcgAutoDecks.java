package com.tablecards.engine.ptcg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds playable 60-card decks out of an imported card pool, one per energy type: the strongest
 * complete evolution lines of that type, a standard Trainer package from whatever Trainers were
 * imported, and basic Energy. Deterministic, so the same pool always gives the same decks.
 */
public final class PtcgAutoDecks {
    private PtcgAutoDecks() {
    }

    private static final List<String> TYPES = List.of("fire", "water", "grass", "lightning", "psychic", "fighting", "darkness", "metal");

    /** Trainer effects to include, in order of priority, with how many copies. */
    private static final List<Object[]> TRAINERS = List.of(
            new Object[]{"shuffle_draw", 4}, new Object[]{"nest_ball", 3}, new Object[]{"search_pokemon", 3},
            new Object[]{"draw", 2}, new Object[]{"gust", 2}, new Object[]{"switch", 2}, new Object[]{"heal", 2},
            new Object[]{"search_basic", 2}, new Object[]{"energy_retrieval", 1}, new Object[]{"search_energy", 1});

    public static Map<String, List<PtcgCard>> build(PtcgLibrary lib) {
        // best printing of each Pokemon name, and the cards by effect
        Map<String, PtcgCard> bestByName = new HashMap<>();
        Map<String, PtcgCard> energyByType = new HashMap<>();
        Map<String, PtcgCard> trainerByEffect = new HashMap<>();
        PtcgCard rareCandy = null;
        for (PtcgCard c : lib.cards.values()) {
            if (c.set.isEmpty()) {
                continue; // only imported (real) cards
            }
            switch (c.kind) {
                case POKEMON -> {
                    PtcgCard cur = bestByName.get(c.name);
                    if (cur == null || score(c) > score(cur)) {
                        bestByName.put(c.name, c);
                    }
                }
                case ENERGY -> energyByType.putIfAbsent(c.type, c);
                default -> {
                    if (c.effect.equals("rare_candy") && c.value == 0) {
                        rareCandy = rareCandy == null ? c : rareCandy;
                    } else if (!c.aceSpec) {
                        trainerByEffect.putIfAbsent(c.effect, c);
                    }
                }
            }
        }
        Map<String, List<PtcgCard>> decks = new LinkedHashMap<>();
        for (String type : TYPES) {
            PtcgCard energy = energyByType.get(type);
            if (energy == null) {
                continue;
            }
            List<List<PtcgCard>> lines = lines(bestByName, type);
            if (lines.isEmpty()) {
                continue;
            }
            List<PtcgCard> deck = new ArrayList<>();
            List<String> names = new ArrayList<>();
            boolean anyStage2 = false;
            for (List<PtcgCard> line : lines) {
                int[] counts = line.size() == 3 ? new int[]{4, 3, 3} : line.size() == 2 ? new int[]{4, 3} : new int[]{3};
                if (deck.size() + sum(counts) > 22) {
                    continue;
                }
                for (int i = 0; i < line.size(); i++) {
                    for (int k = 0; k < counts[i]; k++) {
                        deck.add(line.get(i));
                    }
                }
                anyStage2 |= line.size() == 3;
                names.add(line.get(line.size() - 1).name);
                if (names.size() == 3) {
                    break;
                }
            }
            if (anyStage2 && rareCandy != null) {
                for (int k = 0; k < 3; k++) deck.add(rareCandy);
            }
            for (Object[] t : TRAINERS) {
                PtcgCard card = trainerByEffect.get((String) t[0]);
                if (card != null) {
                    for (int k = 0; k < (int) t[1] && deck.size() < 44; k++) deck.add(card);
                }
            }
            while (deck.size() < PtcgLibrary.DECK_SIZE) {
                deck.add(energy);
            }
            if (PtcgLibrary.validate(deck) == null) {
                decks.put("Auto: " + PtcgCard.typeName(type) + " (" + String.join(", ", names) + ")", deck);
            }
        }
        return decks;
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int x : a) s += x;
        return s;
    }

    /** Complete evolution lines (Basic first) of {@code type}, strongest first. */
    private static List<List<PtcgCard>> lines(Map<String, PtcgCard> best, String type) {
        List<List<PtcgCard>> out = new ArrayList<>();
        for (PtcgCard top : best.values()) {
            if (!top.type.equals(type) || !payable(top, type) || top.prizes > 2) {
                continue;
            }
            List<PtcgCard> line = new ArrayList<>();
            line.add(top);
            PtcgCard cur = top;
            boolean ok = true;
            while (cur.stage > 0) {
                PtcgCard prev = best.get(cur.evolvesFromName());
                if (prev == null || prev.stage != cur.stage - 1 || !payable(prev, type)) {
                    ok = false;
                    break;
                }
                line.add(0, prev);
                cur = prev;
            }
            if (ok) {
                out.add(line);
            }
        }
        out.sort(Comparator.comparingDouble((List<PtcgCard> l) -> -lineScore(l))
                .thenComparing(l -> l.get(l.size() - 1).id));
        // don't use the same Basic twice (e.g. two evolutions of one Basic)
        List<List<PtcgCard>> unique = new ArrayList<>();
        List<String> usedBasics = new ArrayList<>();
        for (List<PtcgCard> l : out) {
            if (!usedBasics.contains(l.get(0).name)) {
                usedBasics.add(l.get(0).name);
                unique.add(l);
            }
        }
        return unique;
    }

    /** Can every attack's cost be paid with this type's Energy alone? */
    private static boolean payable(PtcgCard c, String type) {
        if (c.attacks.isEmpty()) {
            return false;
        }
        for (PtcgCard.Attack a : c.attacks) {
            for (String t : a.cost) {
                if (!t.equals(type) && !t.equals("colorless")) {
                    return false;
                }
            }
        }
        return true;
    }

    static double score(PtcgCard c) {
        double best = 0;
        for (PtcgCard.Attack a : c.attacks) {
            double dmg = a.damage + a.coinTimesN * a.coinTimesPer / 2.0 + a.headsBonus / 2.0 + a.coinUntilTailsPer;
            if (a.tailsNothing) dmg /= 2;
            best = Math.max(best, dmg / Math.max(1, a.cost.size()));
        }
        return best + c.hp / 20.0;
    }

    private static double lineScore(List<PtcgCard> line) {
        PtcgCard top = line.get(line.size() - 1);
        // a strong final form matters most; longer lines are slower, so they need to pay off
        return score(top) * 2 + line.get(0).hp / 20.0 - (line.size() - 1) * 4 + (top.prizes > 1 ? -6 : 0);
    }
}
