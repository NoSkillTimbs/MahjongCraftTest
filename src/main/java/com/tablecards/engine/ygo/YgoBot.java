package com.tablecards.engine.ygo;

import com.tablecards.engine.Bot;
import com.tablecards.engine.CardGame;
import com.tablecards.engine.Decision;
import com.tablecards.engine.Move;
import com.tablecards.engine.Option;
import com.tablecards.engine.ygo.YgoGame.Card;

import java.util.List;
import java.util.Set;

/**
 * Scores every option and picks the best. It only uses information a player at the table has:
 * the opponent's face-down cards are treated as unknown. It plays its effects and searches,
 * summons its strongest monsters, attacks when the attack wins, and responds with negations and
 * Traps when it can.
 */
final class YgoBot implements Bot {
    /** What the bot assumes a face-down monster's defense to be. */
    private static final int FACE_DOWN_GUESS = 1500;
    private static final Set<String> GIVE_UP = Set.of("tribute", "discard", "material");

    @Override
    public int choose(CardGame game, Decision decision) {
        YgoGame g = (YgoGame) game;
        int me = decision.player();
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        List<Option> opts = decision.options();
        for (int i = 0; i < opts.size(); i++) {
            double s = score(g, me, opts.get(i), opts);
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        return best;
    }

    private double score(YgoGame g, int me, Option o, List<Option> all) {
        Move mv = o.move();
        Card c = mv.a() instanceof Card x ? x : null;
        int used = c == null ? 0 : g.uses.getOrDefault(c.uid, 0);
        int oppBest = strongest(g, g.opp(me));
        return switch (mv.type()) {
            case "ss_proc" -> 80 + g.atkOf(c) / 100.0 - used * 50;
            case "synchro" -> 85 + c.def.atk / 100.0;
            case "effect" -> 62 - used * 45;
            case "activate" -> activateScore(g, me, c) - used * 45;
            case "summon" -> {
                double s = 50 + c.def.atk / 100.0 - mv.n() * 12;
                if (c.def.atk < oppBest && c.def.def > c.def.atk) {
                    s -= 30;
                }
                yield s;
            }
            case "set_monster" -> (c.def.atk < oppBest ? 40 + c.def.def / 200.0 : 10) - mv.n() * 12;
            case "set_st" -> c.def.kind == YgoCard.Kind.TRAP ? 34 : c.def.stype.equals("quick") ? 22 : 3;
            case "flip" -> 45;
            case "position" -> {
                if (!c.attackPos) {
                    yield g.atkOf(c) > oppBest ? 25 : -5;
                }
                yield g.atkOf(c) < oppBest ? 15 : -5;
            }
            case "battle" -> hasGoodAttack(g, me) ? 30 : 4;
            case "main2" -> 6;
            case "end" -> 0;
            case "attack" -> bestAttack(g, me, c) > 0 ? 70 + g.atkOf(c) / 100.0 : 1;
            case "target" -> attackValue(g, (Card) mv.a(), (Card) mv.b());
            case "cancel" -> 0;
            case "respond" -> respondScore(g, me, c);
            case "pass" -> 40;
            case "yes" -> 60;
            case "no" -> 20;
            case "none" -> 30;
            case "mode" -> 50 - mv.n();
            case "fusion", "ritual" -> 50 + c.def.atk / 100.0;
            case "search" -> 50 + (g.isMonster(c) ? c.def.atk / 200.0 : 8);
            default -> {
                if (c == null) {
                    yield 10;
                }
                boolean theirs = c.controllerOrOwner() != me;
                if (theirs) {
                    yield 50 + (c.faceUp ? g.atkOf(c) / 100.0 : 12) + (g.isMonster(c) ? 0 : 5);
                }
                if (GIVE_UP.contains(mv.type())) {
                    yield 50 - (g.isMonster(c) ? c.def.atk / 100.0 : 8);
                }
                yield 50 + (g.isMonster(c) ? c.def.atk / 100.0 : 5);
            }
        };
    }

    private double activateScore(YgoGame g, int me, Card c) {
        if (c.def.stype.equals("field") && g.p[me].field != null) {
            return 5;
        }
        if (c.def.stype.equals("equip")) {
            return 40;
        }
        return 58;
    }

    private double respondScore(YgoGame g, int me, Card c) {
        if (!g.chain.isEmpty()) {
            YgoGame.Link top = g.chain.get(g.chain.size() - 1);
            return top.player == me ? 10 : 70; // stop the opponent; don't fight our own effects
        }
        return 65; // traps answering an attack or a summon
    }

    private int strongest(YgoGame g, int who) {
        int best = 0;
        for (Card m : g.p[who].monsters) {
            int v = m.faceUp ? (m.attackPos ? g.atkOf(m) : g.def(m)) : FACE_DOWN_GUESS;
            best = Math.max(best, v);
        }
        return best;
    }

    private boolean hasGoodAttack(YgoGame g, int me) {
        for (Card m : g.p[me].monsters) {
            if (g.canAttack(m) && bestAttack(g, me, m) > 0) {
                return true;
            }
        }
        return false;
    }

    /** The best outcome among this monster's possible attacks (>0: worth attacking). */
    private double bestAttack(YgoGame g, int me, Card a) {
        List<Card> targets = g.p[g.opp(me)].monsters;
        if (targets.isEmpty() || (!a.attackPos && a.script.defenseDirect)) {
            return 100;
        }
        double best = -1;
        for (Card t : targets) {
            best = Math.max(best, attackValue(g, a, t));
        }
        return best;
    }

    private double attackValue(YgoGame g, Card a, Card t) {
        if (t == null) {
            return 100;
        }
        int atk = g.atkOf(a);
        if (!t.faceUp) {
            return atk > FACE_DOWN_GUESS ? 30 : -20;
        }
        int v = t.attackPos ? g.atkOf(t) : g.def(t);
        if (atk > v) {
            return 80 + (t.attackPos ? (atk - v) / 100.0 : 0) + g.atkOf(t) / 200.0;
        }
        if (atk == v && t.attackPos) {
            return a.script.cannotBeDestroyedByBattle ? 60 : 5;
        }
        return a.script.cannotBeDestroyedByBattle && !t.attackPos ? 1 : -50;
    }
}
