package com.shandalar.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A heuristic opponent: scores every legal action and takes the best, attacks when it is
 * safe or lethal, blocks when it wins the exchange or must chump. Good enough for random
 * overworld encounters; smarter "archetype" AIs can subclass it later.
 */
public class SimpleAI implements Agent {

    static int value(CardDef c) {
        int v = c.power * 2 + c.toughness + c.cost.total();
        if (c.keywords.contains(Keyword.FLYING)) {
            v += 3;
        }
        if (c.keywords.contains(Keyword.FIRST_STRIKE)) {
            v += 2;
        }
        return v;
    }

    @Override
    public Action chooseMainAction(Game game, Player me, List<Action> options) {
        Player opp = game.opponent(me);
        Action best = null;
        double bestScore = 0;
        for (Action a : options) {
            double s = score(a, me, opp);
            if (s > bestScore) {
                bestScore = s;
                best = a;
            }
        }
        return best;
    }

    private double score(Action a, Player me, Player opp) {
        CardDef c = a.card();
        if (c.type == CardType.LAND) {
            return 1000;
        }
        if (c.type == CardType.CREATURE) {
            return 100 + value(c);
        }
        double s = 0;
        for (Effect e : c.effects) {
            switch (e.kind()) {
                case DRAW -> s += me.library.size() > 5 ? 60 : 0;
                case GAIN_LIFE -> s += me.life <= 12 ? 30 : 0;
                case DAMAGE -> {
                    Target t = a.target();
                    if (t.player() == opp) {
                        s += e.amount() >= opp.life ? 5000 : 20;
                    } else if (t.permanent() != null && t.permanent().owner == opp
                            && t.permanent().isCreature()
                            && e.amount() >= t.permanent().toughness() - t.permanent().damage) {
                        s += 110 + value(t.permanent().def);
                    } else {
                        return -1;
                    }
                }
                case DESTROY -> {
                    Target t = a.target();
                    if (t.permanent() != null && t.permanent().owner == opp) {
                        s += 115 + value(t.permanent().def);
                    } else {
                        return -1;
                    }
                }
                case PUMP -> {
                    return -1; // needs combat timing; added with the stack/priority milestone
                }
            }
        }
        return s;
    }

    @Override
    public List<Permanent> chooseAttackers(Game game, Player me, List<Permanent> eligible) {
        Player opp = game.opponent(me);
        List<Permanent> untappedBlockers = new ArrayList<>();
        for (Permanent p : opp.creatures()) {
            if (!p.tapped) {
                untappedBlockers.add(p);
            }
        }
        int totalPower = 0;
        for (Permanent p : eligible) {
            totalPower += p.power();
        }
        boolean allIn = totalPower >= opp.life && untappedBlockers.size() < eligible.size();

        List<Permanent> attackers = new ArrayList<>();
        for (Permanent a : eligible) {
            boolean danger = false;
            for (Permanent b : untappedBlockers) {
                if (!game.canBlock(b, a)) {
                    continue;
                }
                boolean killsMe = b.power() >= a.toughness();
                boolean iStrikeFirst = a.has(Keyword.FIRST_STRIKE) && !b.has(Keyword.FIRST_STRIKE)
                        && a.power() >= b.toughness();
                if (killsMe && !iStrikeFirst) {
                    danger = true;
                    break;
                }
            }
            if (!danger || allIn) {
                attackers.add(a);
            }
        }
        return attackers;
    }

    @Override
    public Map<Permanent, Permanent> chooseBlocks(Game game, Player me, List<Permanent> attackers,
                                                  List<Permanent> eligibleBlockers) {
        Map<Permanent, Permanent> blocks = new HashMap<>();
        Set<Permanent> used = new HashSet<>();
        List<Permanent> sorted = new ArrayList<>(attackers);
        sorted.sort(Comparator.comparingInt(Permanent::power).reversed());

        // Good blocks first: kill the attacker and survive, or trade evenly or better.
        for (Permanent a : sorted) {
            Permanent pick = null;
            int pickScore = Integer.MIN_VALUE;
            for (Permanent b : eligibleBlockers) {
                if (used.contains(b) || !game.canBlock(b, a)) {
                    continue;
                }
                boolean kills = b.power() >= a.toughness();
                boolean survives = b.toughness() > a.power();
                int s = Integer.MIN_VALUE;
                if (kills && survives) {
                    s = 1000 - value(b.def);
                } else if (kills && value(a.def) >= value(b.def)) {
                    s = 500 + value(a.def) - value(b.def);
                }
                if (s > pickScore) {
                    pickScore = s;
                    pick = b;
                }
            }
            if (pick != null) {
                blocks.put(a, pick);
                used.add(pick);
            }
        }

        // If the rest would still be lethal, chump the biggest attackers with the cheapest creatures.
        int incoming = 0;
        for (Permanent a : sorted) {
            if (!blocks.containsKey(a)) {
                incoming += a.power();
            }
        }
        for (Permanent a : sorted) {
            if (incoming < me.life) {
                break;
            }
            if (blocks.containsKey(a)) {
                continue;
            }
            Permanent chump = null;
            for (Permanent b : eligibleBlockers) {
                if (!used.contains(b) && game.canBlock(b, a)
                        && (chump == null || value(b.def) < value(chump.def))) {
                    chump = b;
                }
            }
            if (chump != null) {
                blocks.put(a, chump);
                used.add(chump);
                incoming -= a.power();
            }
        }
        return blocks;
    }

    @Override
    public CardDef chooseDiscard(Game game, Player me) {
        CardDef worst = null;
        for (CardDef c : me.hand) {
            if (c.type == CardType.LAND && me.landCountInHand() > 3) {
                return c;
            }
            if (worst == null || c.cost.total() > worst.cost.total()) {
                worst = c;
            }
        }
        return worst;
    }
}
