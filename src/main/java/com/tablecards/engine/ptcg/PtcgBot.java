package com.tablecards.engine.ptcg;

import com.tablecards.engine.Bot;
import com.tablecards.engine.CardGame;
import com.tablecards.engine.Decision;
import com.tablecards.engine.Move;
import com.tablecards.engine.Option;
import com.tablecards.engine.ptcg.PtcgCard.Attack;
import com.tablecards.engine.ptcg.PtcgGame.Card;
import com.tablecards.engine.ptcg.PtcgGame.Mon;
import com.tablecards.engine.ptcg.PtcgGame.Player;

/**
 * Scores every option and picks the best. Setup moves (benching, evolving, energy, trainers)
 * always outscore attacking, because attacking ends the turn. Uses only what a player at the
 * table can see.
 */
final class PtcgBot implements Bot {
    @Override
    public int choose(CardGame game, Decision decision) {
        PtcgGame g = (PtcgGame) game;
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

    private double score(PtcgGame g, int me, Option o) {
        Move mv = o.move();
        Player self = g.p[me];
        Player op = g.p[g.opp(me)];
        return switch (mv.type()) {
            case "setup_active" -> monValue(((Card) mv.a()).def);
            case "setup_bench" -> 40;
            case "setup_done" -> 0;
            case "bench" -> self.bench.size() < 4 ? 50 : 20;
            case "evolve" -> 70 + ((Card) mv.a()).def.stage * 5;
            case "energy" -> energyWorth(g, self, (Card) mv.a()) > 0 ? 55 : 5;
            case "energy_target" -> energyTargetScore(g, self, (Card) mv.a(), (Mon) mv.b());
            case "item" -> itemScore(g, self, op, ((Card) mv.a()).def);
            case "supporter" -> supporterScore(self, op, ((Card) mv.a()).def);
            case "heal_target" -> ((Mon) mv.a()).damage;
            case "switch_target", "retreat_target", "promote" -> readiness(g, (Mon) mv.a(), op.active);
            case "gust_target" -> gustScore(g, self, (Mon) mv.a());
            case "search_target" -> searchScore(self, ((Card) mv.a()).def);
            case "discard_cost" -> -keepValue(self, ((Card) mv.a()).def);
            case "retrieve_target" -> 10;
            case "done" -> 0;
            case "energy_move" -> mv.b() == self.active ? 10 : 2;
            case "opp_energy" -> 5;
            case "draw_yes" -> self.deck.size() > 10 ? 10 : -10;
            case "draw_no" -> 0;
            case "scoop_target" -> -((Mon) mv.a()).hpLeft();
            case "snipe_target" -> {
                Mon m = (Mon) mv.a();
                boolean ko = mv.n() >= m.hpLeft();
                yield (ko ? 100 + m.top().prizes * 20 : 0) + mv.n() / 10.0 + monValue(m.top()) / 10.0;
            }
            case "no_switch" -> readiness(g, self.active, op.active) / 2;
            case "retreat" -> retreatScore(g, self, op);
            case "attack" -> attackScore(g, self, op, (Attack) mv.a(), mv.n());
            case "cancel" -> -30;
            case "end" -> 0;
            default -> 0;
        };
    }

    private double attackScore(PtcgGame g, Player self, Player op, Attack a, int shown) {
        double expected = shown;
        if (a.coinTimesN > 0) {
            expected = g.applyModifiers(self.active, op.active, a, a.coinTimesPer * a.coinTimesN / 2, true);
        }
        if (a.coinUntilTailsPer > 0) {
            expected = g.applyModifiers(self.active, op.active, a, a.coinUntilTailsPer, true);
        }
        if (a.coinUntilTailsBonus > 0) expected += a.coinUntilTailsBonus;
        if (self.active.smokescreenTurn == g.turn) expected /= 2;
        if (a.headsBonus > 0) expected += a.headsBonus / 2.0;
        if (a.coinBonusN > 0) expected += a.coinBonusPer * a.coinBonusN / 2.0;
        if (a.tailsNothing) expected /= 2;
        if (a.eitherTailsNothing) expected /= 4;
        if (self.active.confused()) expected /= 2;
        boolean ko = op.active != null && expected >= op.active.hpLeft();
        double s = 10 + expected / 20.0 + (ko ? 8 + op.active.top().prizes * 2 : 0);
        if (!a.status.isEmpty()) s += 2;
        if (!a.coinStatus.isEmpty()) s += 1;
        if (a.selfDamage >= self.active.hpLeft()) s -= 15;
        if (a.discardEnergy >= 99) s -= 3;
        return s;
    }

    private static double monValue(PtcgCard c) {
        int bestDamage = c.attacks.stream().mapToInt(a -> a.damage).max().orElse(0);
        return c.hp / 10.0 + bestDamage / 20.0;
    }

    /** How much attaching this energy somewhere helps (0 = pointless). */
    private double energyWorth(PtcgGame g, Player self, Card energy) {
        double best = 0;
        for (Mon m : self.inPlay()) {
            best = Math.max(best, energyTargetScore(g, self, energy, m));
        }
        return best;
    }

    private double energyTargetScore(PtcgGame g, Player self, Card energy, Mon m) {
        double s = 0;
        boolean active = m == self.active;
        for (Attack a : m.top().attacks) {
            if (g.canPay(m, a)) {
                continue;
            }
            boolean useful = a.cost.contains(energy.def.type) || a.cost.contains("colorless");
            if (useful) {
                m.energy.add(energy);
                boolean nowPayable = g.canPay(m, a);
                m.energy.remove(energy);
                s = Math.max(s, (nowPayable ? 40 : 20) + a.damage / 10.0);
            }
        }
        if (energy.def.type.equals(m.top().type)) {
            s += 5;
        }
        if (active) {
            s += 10;
        }
        return s;
    }

    private double itemScore(PtcgGame g, Player self, Player op, PtcgCard t) {
        return switch (t.effect) {
            case "heal" -> self.inPlay().stream().anyMatch(m -> m.damage >= Math.max(10, t.value)) ? 45 : -10;
            case "switch" -> {
                double now = readiness(g, self.active, op.active) - (self.active.conditions().isEmpty() ? 0 : 30);
                double best = self.bench.stream().mapToDouble(m -> readiness(g, m, op.active)).max().orElse(0);
                yield best > now + 20 ? 35 : -10;
            }
            case "gust" -> op.bench.stream().anyMatch(m -> m.hpLeft() <= bestDamage(g, self, m)) ? 48 : -5;
            case "search_basic", "nest_ball" -> self.bench.size() < 4 ? 40 : -5;
            case "search_pokemon", "pokeball_coin" -> self.hand.size() >= 4 ? 30 : -5;
            case "search_energy" -> self.hand.stream().noneMatch(c -> c.def.kind == PtcgCard.Kind.ENERGY) && !self.energyAttached ? 30 : -5;
            case "energy_retrieval" -> self.hand.stream().noneMatch(c -> c.def.kind == PtcgCard.Kind.ENERGY) ? 28 : -5;
            case "energy_switch" -> 6;
            case "rare_candy" -> 75;
            case "coin_draw", "look_top_pokemon", "look_top_supporter" -> 22;
            case "coin_discard_opp_energy" -> 24;
            case "full_heal" -> 30;
            case "scoop_coin" -> self.active.hpLeft() <= 30 && !self.bench.isEmpty() ? 25 : -10;
            default -> 0;
        };
    }

    private double supporterScore(Player self, Player op, PtcgCard t) {
        int handSize = self.hand.size() - 1;
        return switch (t.effect) {
            case "draw", "draw_until" -> handSize <= 5 ? 42 : 15;
            case "shuffle_draw" -> handSize <= 3 ? 44 : 5;
            case "shuffle_in_draw" -> handSize <= 3 ? 40 : 4;
            case "marnie" -> handSize <= 3 || op.hand.size() >= 6 ? 39 : 3;
            case "judge" -> handSize <= 2 || op.hand.size() >= 7 ? 38 : 3;
            case "n_shuffle", "iono" -> self.prizes.size() > op.prizes.size() || handSize <= 2 ? 36 : 2;
            case "gust" -> 46;
            case "heal" -> self.inPlay().stream().anyMatch(m -> m.damage >= 40) ? 40 : -10;
            default -> 0;
        };
    }

    private double bestDamage(PtcgGame g, Player self, Mon target) {
        double best = 0;
        for (Attack a : self.active.top().attacks) {
            if (g.canPay(self.active, a)) {
                best = Math.max(best, g.applyModifiers(self.active, target, a, a.damage, true));
            }
        }
        return best;
    }

    private double gustScore(PtcgGame g, Player self, Mon target) {
        boolean ko = target.hpLeft() <= bestDamage(g, self, target);
        return (ko ? 50 + target.top().prizes * 15 : 0) - target.hpLeft() / 10.0;
    }

    private double searchScore(Player self, PtcgCard c) {
        if (c.kind == PtcgCard.Kind.ENERGY) {
            return self.active != null && self.active.top().type.equals(c.type) ? 10 : 5;
        }
        // an evolution for something already in play is worth more than a new Basic
        boolean evolvesSomething = c.stage > 0 && self.inPlay().stream().anyMatch(m -> m.top().name.equals(c.evolvesFromName()));
        return monValue(c) + (evolvesSomething ? 15 : 0);
    }

    private double keepValue(Player self, PtcgCard c) {
        return switch (c.kind) {
            case ENERGY -> self.hand.stream().filter(x -> x.def.kind == PtcgCard.Kind.ENERGY).count() > 2 ? 2 : 12;
            case POKEMON -> monValue(c);
            default -> 8;
        };
    }

    private double retreatScore(PtcgGame g, Player self, Player op) {
        Mon a = self.active;
        boolean canAttack = a.top().attacks.stream().anyMatch(x -> g.canPay(a, x));
        double best = self.bench.stream().mapToDouble(m -> readiness(g, m, op.active)).max().orElse(0);
        boolean nearlyOut = a.hpLeft() <= a.top().hp * 0.3;
        boolean afflicted = a.confused() || a.poisoned || a.burned;
        if ((!canAttack || nearlyOut || afflicted) && best > readiness(g, a, op.active) + 15) {
            return 33;
        }
        return -10;
    }

    /** How good this Pokemon is as the Active right now: usable damage and remaining HP. */
    private double readiness(PtcgGame g, Mon m, Mon defender) {
        if (m == null) {
            return 0;
        }
        double dmg = 0;
        for (Attack a : m.top().attacks) {
            if (g.canPay(m, a)) {
                dmg = Math.max(dmg, defender != null ? g.damageAgainst(m, defender, a) : a.damage);
            }
        }
        return dmg * 2 + m.hpLeft() / 10.0;
    }
}
