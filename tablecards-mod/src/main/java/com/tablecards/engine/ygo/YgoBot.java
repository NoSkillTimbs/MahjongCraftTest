package com.tablecards.engine.ygo;

import com.tablecards.engine.Bot;
import com.tablecards.engine.CardGame;
import com.tablecards.engine.Decision;
import com.tablecards.engine.Move;
import com.tablecards.engine.Option;
import com.tablecards.engine.ygo.YgoGame.Card;
import com.tablecards.engine.ygo.YgoGame.Monster;
import com.tablecards.engine.ygo.YgoGame.Player;

/**
 * Scores every option and picks the best. It only uses information a player at the table has:
 * the opponent's face-down cards are treated as unknown.
 */
final class YgoBot implements Bot {
    /** What the bot assumes a face-down monster's defense to be. */
    private static final int FACE_DOWN_GUESS = 1500;

    @Override
    public int choose(CardGame game, Decision decision) {
        YgoGame g = (YgoGame) game;
        int me = decision.player();
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < decision.options().size(); i++) {
            double s = score(g, me, decision.options().get(i));
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        return best;
    }

    private double score(YgoGame g, int me, Option o) {
        Move mv = o.move();
        Player self = g.p[me];
        Player op = g.p[g.opp(me)];
        int oppBest = strongestVisible(op);
        return switch (mv.type()) {
            case "spell" -> spellScore(g, me, ((Card) mv.a()).def);
            case "summon" -> {
                YgoCard c = ((Card) mv.a()).def;
                double loss = tributeLoss(self, mv.n());
                double s = 50 + c.atk / 100.0 - loss;
                if (c.atk < oppBest && c.def > c.atk) {
                    s -= 30; // would just die in attack position; setting is better
                }
                yield s;
            }
            case "set_monster" -> {
                YgoCard c = ((Card) mv.a()).def;
                double loss = tributeLoss(self, mv.n());
                yield (c.atk < oppBest ? 40 + c.def / 200.0 : 10) - loss;
            }
            case "set_st" -> {
                YgoCard c = ((Card) mv.a()).def;
                yield c.kind == YgoCard.Kind.TRAP ? 30 : (g.canActivate(me, c) ? -5 : 8);
            }
            case "flip" -> ((Monster) mv.a()).card.def.atk >= oppBest ? 30 : 12;
            case "position" -> {
                Monster m = (Monster) mv.a();
                if (!m.attackPos && m.atk() > oppBest) {
                    yield 15;
                }
                yield m.attackPos && m.atk() < oppBest && m.def() > m.atk() ? 14 : -5;
            }
            case "battle" -> 5;
            case "main2" -> 1;
            case "end" -> 0;
            case "attack" -> attackScore(g, me, (Monster) mv.a());
            case "target" -> targetScore((Monster) mv.a(), (Monster) mv.b());
            case "cancel" -> -20;
            case "tribute" -> -((Monster) mv.a()).atk();
            case "trap_response" -> trapScore(g, me, (YgoGame.SpellTrap) mv.a(), (Monster) mv.b());
            case "no_response" -> {
                // let small attacks through; stop the rest
                Monster attacker = (Monster) mv.a();
                yield attacker.atk() < 1000 ? 60 : 10;
            }
            case "discard" -> -cardValue(((Card) mv.a()).def);
            case "equip_target" -> {
                Monster m = (Monster) mv.a();
                yield "opp".equals(mv.b()) ? -50 - m.atk() / 100.0 : m.atk() / 100.0 + (m.attackPos ? 5 : 0);
            }
            case "revive_target" -> ((Card) mv.a()).def.atk / 100.0;
            case "destroy_target" -> {
                Monster m = (Monster) mv.a();
                double v = m.faceUp ? m.atk() / 100.0 : FACE_DOWN_GUESS / 100.0;
                yield "own".equals(mv.b()) ? -100 - v : v;
            }
            case "st_target" -> "own".equals(mv.b()) ? -100 : ((YgoGame.SpellTrap) mv.a()).faceUp ? 5 : 10;
            default -> 0;
        };
    }

    private double spellScore(YgoGame g, int me, YgoCard c) {
        Player self = g.p[me];
        Player op = g.p[g.opp(me)];
        return switch (c.effect) {
            case "destroy_all" -> {
                int diff = op.monsters.size() - self.monsters.size();
                int atkDiff = op.monsters.stream().mapToInt(Monster::atk).sum()
                        - self.monsters.stream().mapToInt(Monster::atk).sum();
                yield diff > 0 || atkDiff > 1500 ? 70 + diff * 10 : -50;
            }
            case "destroy_one", "destroy_any" -> op.monsters.isEmpty() ? -50 : 60 + strongestVisible(op) / 100.0;
            case "destroy_lowest" -> 45;
            case "destroy_opp_all" -> op.monsters.isEmpty() ? -50 : 80 + op.monsters.size() * 12;
            case "destroy_st_opp_all" -> op.st.isEmpty() ? -50 : 20 + op.st.size() * 10;
            case "destroy_st_all" -> op.st.size() > self.st.size() ? 15 + (op.st.size() - self.st.size()) * 10 : -40;
            case "draw" -> 40;
            case "gain" -> self.lp < 4000 ? 35 : -2;
            case "burn" -> op.lp <= c.value ? 1000 : 30;
            case "destroy_st" -> op.st.isEmpty() ? -50 : 25;
            case "revive" -> {
                int best = self.gy.stream().filter(x -> x.def.isMonster()).mapToInt(x -> x.def.atk).max().orElse(0);
                if ("either".equals(c.scope)) {
                    best = Math.max(best, op.gy.stream().filter(x -> x.def.isMonster()).mapToInt(x -> x.def.atk).max().orElse(0));
                }
                yield 20 + best / 100.0;
            }
            case "equip" -> self.monsters.stream().anyMatch(m -> m.faceUp && m.attackPos && (!c.equipStrict || c.equipMatches(m.card.def)))
                    && c.equipAtk > 0 ? 30 : -20;
            default -> 0;
        };
    }

    private double trapScore(YgoGame g, int me, YgoGame.SpellTrap trap, Monster attacker) {
        YgoCard t = trap.card.def;
        int attackers = (int) g.p[g.opp(me)].monsters.stream().filter(m -> m.attackPos).count();
        return switch (t.effect) {
            case "mirror_force" -> 40 + attackers * 15;
            case "magic_cylinder" -> 30 + attacker.atk() / 100.0;
            default -> 50;
        };
    }

    private double attackScore(YgoGame g, int me, Monster attacker) {
        Player op = g.p[g.opp(me)];
        if (op.monsters.isEmpty()) {
            return 60;
        }
        double best = -10;
        for (Monster t : op.monsters) {
            best = Math.max(best, targetScore(attacker, t));
        }
        return best;
    }

    private double targetScore(Monster attacker, Monster target) {
        if (target == null) {
            return 100;
        }
        int a = attacker.atk();
        int value = !target.faceUp ? FACE_DOWN_GUESS : target.attackPos ? target.atk() : target.def();
        if (a > value) {
            return 40 + (target.faceUp ? target.atk() : FACE_DOWN_GUESS) / 100.0;
        }
        if (a == value && target.attackPos) {
            return 5;
        }
        return -40;
    }

    private static int strongestVisible(Player pl) {
        int best = 0;
        for (Monster m : pl.monsters) {
            if (m.faceUp && m.attackPos) {
                best = Math.max(best, m.atk());
            }
        }
        return best;
    }

    private static double tributeLoss(Player self, int tributes) {
        return self.monsters.stream().mapToInt(Monster::atk).sorted().limit(tributes).sum() / 100.0 * 1.2;
    }

    private static double cardValue(YgoCard c) {
        return c.isMonster() ? c.atk / 100.0 : 20;
    }
}
