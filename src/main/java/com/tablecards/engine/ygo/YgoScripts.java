package com.tablecards.engine.ygo;

import com.tablecards.engine.ygo.YgoGame.Card;
import com.tablecards.engine.ygo.YgoGame.Ctx;
import com.tablecards.engine.ygo.YgoGame.Link;
import com.tablecards.engine.ygo.YgoGame.Zone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * How each card plays. Cards with their own script are looked up by name (each one written from
 * its card text); other imported Spells/Traps use the simple effect the importer recognised from
 * their wording; Normal Monsters need nothing.
 */
public final class YgoScripts {
    private static final Map<String, Supplier<Script>> NAMED = new HashMap<>();
    private static final Map<String, Script> CACHE = new HashMap<>();
    private static final Script NONE = new Script();

    static {
        ScriptsBlueEyes.register();
        ScriptsDarkMagician.register();
        ScriptsHero.register();
        registerStaples();
    }

    private YgoScripts() {
    }

    static void def(String name, Supplier<Script> s) {
        NAMED.put(name, s);
    }

    /** Names of all cards with their own script (the importer imports these). */
    public static Set<String> names() {
        return NAMED.keySet();
    }

    static synchronized Script of(YgoCard def) {
        String key = NAMED.containsKey(def.name) ? def.name : "#" + def.id;
        Script s = CACHE.get(key);
        if (s == null) {
            Supplier<Script> sup = NAMED.get(def.name);
            s = sup != null ? sup.get() : generic(def);
            CACHE.put(key, s);
        }
        return s;
    }

    // =================================================================== helpers for scripts

    static Predicate<Card> named(YgoGame g, String n) {
        return c -> g.named(c, n);
    }

    /** Picks a target for the link (stored as "target"), then continues. */
    static void target(YgoGame g, Link l, String prompt, List<Card> options, Runnable done) {
        g.pick(l.player, prompt, options, false, "pick", c -> {
            l.data.put("target", c);
            done.run();
        }, null);
    }

    static List<Card> targetable(YgoGame g, int player, Card source, List<Card> cards) {
        List<Card> out = new ArrayList<>();
        for (Card c : cards) {
            if (g.canTarget(player, source, c)) {
                out.add(c);
            }
        }
        return out;
    }

    static boolean onField(Card c) {
        return c != null && c.onField();
    }

    /** Picks up to {@code max} cards one at a time (each optional after the first {@code min}). */
    static void pickUpTo(YgoGame g, int who, String prompt, Supplier<List<Card>> options, int min, int max, Consumer<List<Card>> done) {
        List<Card> chosen = new ArrayList<>();
        pickMore(g, who, prompt, options, min, max, chosen, done);
    }

    private static void pickMore(YgoGame g, int who, String prompt, Supplier<List<Card>> options, int min, int max, List<Card> chosen,
                                 Consumer<List<Card>> done) {
        List<Card> left = new ArrayList<>(options.get());
        left.removeAll(chosen);
        if (chosen.size() >= max || left.isEmpty()) {
            done.accept(chosen);
            return;
        }
        Consumer<Card> more = c -> {
            chosen.add(c);
            pickMore(g, who, prompt, options, min, max, chosen, done);
        };
        if (chosen.size() >= min) {
            g.pickOr(who, prompt + " (" + chosen.size() + "/" + max + ")", left, "pick", more, "Done", () -> done.accept(chosen));
        } else {
            g.pick(who, prompt + " (" + chosen.size() + "/" + max + ")", left, false, "pick", more, null);
        }
    }

    /** Cards of a link's player in GY that can be Special Summoned. */
    static List<Card> revivable(YgoGame g, int who, List<Card> from, Predicate<Card> pred) {
        List<Card> out = new ArrayList<>();
        for (Card c : from) {
            if (g.isMonster(c) && pred.test(c) && g.canSpecial(who, c)) {
                out.add(c);
            }
        }
        return out;
    }

    // =================================================================== generic effects (by wording)

    private static Script generic(YgoCard d) {
        if (d.kind == YgoCard.Kind.MONSTER || d.effect.isEmpty()) {
            return NONE;
        }
        Script s = new Script();
        switch (d.effect) {
            case "destroy_one" -> s.add(destroyTarget("Destroy 1 monster your opponent controls", false));
            case "destroy_any" -> s.add(destroyTarget("Destroy 1 monster on the field", true));
            case "destroy_opp_all" -> s.add(Fx.activate("Destroy all your opponent's monsters")
                    .when((g, c, x) -> !g.monsters(g.opp(c.controllerOrOwner())).isEmpty())
                    .then((g, c, l, done) -> {
                        for (Card m : List.copyOf(g.monsters(g.opp(l.player)))) g.destroy(m, "effect", l.player, c);
                        done.run();
                    }));
            case "destroy_all" -> s.add(Fx.activate("Destroy all monsters")
                    .when((g, c, x) -> !g.allMonsters().isEmpty())
                    .then((g, c, l, done) -> {
                        for (Card m : List.copyOf(g.allMonsters())) g.destroy(m, "effect", l.player, c);
                        done.run();
                    }));
            case "destroy_lowest" -> s.add(Fx.activate("Destroy their face-up monster with the lowest ATK")
                    .when((g, c, x) -> !g.faceUpMonsters(g.opp(c.controllerOrOwner())).isEmpty())
                    .then((g, c, l, done) -> {
                        List<Card> up = g.faceUpMonsters(g.opp(l.player));
                        int low = up.stream().mapToInt(g::atk).min().orElse(0);
                        List<Card> lowest = g.where(up, m -> g.atk(m) == low);
                        if (lowest.isEmpty()) {
                            done.run();
                            return;
                        }
                        g.pick(l.player, "Destroy which monster (tied for lowest ATK)?", lowest, false, "pick", m -> {
                            g.destroy(m, "effect", l.player, c);
                            done.run();
                        }, null);
                    }));
            case "draw" -> s.add(Fx.activate("Draw " + d.value)
                    .when((g, c, x) -> g.pl(c.controllerOrOwner()).deck.size() >= Math.max(1, d.value))
                    .then((g, c, l, done) -> {
                        g.draw(l.player, d.value);
                        done.run();
                    }));
            case "gain" -> s.add(Fx.activate("Gain " + d.value + " LP").then((g, c, l, done) -> {
                g.gain(l.player, d.value);
                done.run();
            }));
            case "burn" -> s.add(Fx.activate("Inflict " + d.value + " damage").then((g, c, l, done) -> {
                g.damage(g.opp(l.player), d.value, d.name);
                done.run();
            }));
            case "destroy_st" -> s.add(Fx.activate("Destroy 1 Spell/Trap on the field")
                    .when((g, c, x) -> !stTargets(g, c.controllerOrOwner(), c).isEmpty())
                    .cost((g, c, l, done) -> target(g, l, d.name + ": choose a Spell/Trap to destroy", stTargets(g, l.player, c), done))
                    .then((g, c, l, done) -> {
                        if (onField(l.target())) g.destroy(l.target(), "effect", l.player, c);
                        done.run();
                    }));
            case "destroy_st_opp_all" -> s.add(Fx.activate("Destroy all your opponent's Spells/Traps")
                    .when((g, c, x) -> !g.spellsTraps(g.opp(c.controllerOrOwner())).isEmpty())
                    .then((g, c, l, done) -> {
                        for (Card t : List.copyOf(g.spellsTraps(g.opp(l.player)))) g.destroy(t, "effect", l.player, c);
                        done.run();
                    }));
            case "destroy_st_all" -> s.add(Fx.activate("Destroy all Spells/Traps on the field")
                    .when((g, c, x) -> g.spellsTraps(0).size() + g.spellsTraps(1).size() > (c.onField() ? 1 : 0))
                    .then((g, c, l, done) -> {
                        for (int i = 0; i < 2; i++) {
                            for (Card t : List.copyOf(g.spellsTraps(i))) {
                                if (t != c) g.destroy(t, "effect", l.player, c);
                            }
                        }
                        done.run();
                    }));
            case "revive" -> {
                boolean either = "either".equals(d.scope);
                s.add(Fx.activate("Special Summon 1 monster from " + (either ? "either GY" : "your GY"))
                        .when((g, c, x) -> !reviveTargets(g, c.controllerOrOwner(), c, either).isEmpty())
                        .cost((g, c, l, done) -> target(g, l, d.name + ": choose a monster to Special Summon",
                                reviveTargets(g, l.player, c, either), done))
                        .then((g, c, l, done) -> {
                            Card t = l.target();
                            if (t != null && t.zone == Zone.GY) g.summonFrom(l.player, t, false);
                            done.run();
                        }));
            }
            case "equip" -> s.add(Fx.activate("Equip to a monster")
                    .when((g, c, x) -> !equipTargets(g, c.controllerOrOwner(), c, d).isEmpty())
                    .cost((g, c, l, done) -> target(g, l, d.name + ": choose a monster to equip", equipTargets(g, l.player, c, d), done))
                    .then((g, c, l, done) -> {
                        Card t = l.target();
                        if (onField(t) && t.faceUp) {
                            c.equipTarget = t;
                            g.recomputeEquips();
                            g.note(d.name + " is equipped to " + g.name(t) + ".");
                        }
                        done.run();
                    }));
            // ---- traps that respond to an attack or a summon
            case "negate_attack" -> s.add(attackTrap("Negate the attack" + (d.endBattle ? " and end the Battle Phase" : ""), (g, c, l) -> {
                g.attackNegated = true;
                g.note("The attack is negated.");
                if (d.endBattle) {
                    g.endBattlePhase();
                }
            }));
            case "destroy_attacker" -> s.add(attackTrap("Destroy the attacking monster", (g, c, l) -> {
                if (onField(g.attacker) && g.affects(l.player, c, g.attacker)) g.destroy(g.attacker, "effect", l.player, c);
                if (d.burn > 0) g.damage(g.opp(l.player), d.burn, d.name);
            }));
            case "mirror_force" -> s.add(attackTrap("Destroy all their Attack Position monsters", (g, c, l) -> {
                for (Card m : List.copyOf(g.monsters(g.opp(l.player)))) {
                    if (m.attackPos) g.destroy(m, "effect", l.player, c);
                }
            }));
            case "magic_cylinder" -> s.add(attackTrap("Negate the attack and inflict its ATK as damage", (g, c, l) -> {
                g.attackNegated = true;
                g.note("The attack is negated.");
                if (onField(g.attacker) && g.affects(l.player, c, g.attacker)) g.damage(g.opp(l.player), g.atk(g.attacker), d.name);
            }));
            case "destroy_summoned" -> s.add(Fx.activate("Destroy the summoned monster")
                    .when((g, c, x) -> g.chain.isEmpty() && g.lastSummon != null && g.lastSummon.player != c.controller
                            && (g.lastSummon.how.equals("normal") || (d.flipToo && g.lastSummon.how.equals("flip")))
                            && onField(g.lastSummon.card) && g.atk(g.lastSummon.card) >= d.value
                            && g.canTarget(c.controller, c, g.lastSummon.card))
                    .cost((g, c, l, done) -> {
                        l.data.put("target", g.lastSummon.card);
                        done.run();
                    })
                    .then((g, c, l, done) -> {
                        if (onField(l.target())) g.destroy(l.target(), "effect", l.player, c);
                        done.run();
                    }));
            default -> {
            }
        }
        return s;
    }

    interface TrapAction {
        void run(YgoGame g, Card self, Link l);
    }

    /** A Trap activated "When an opponent's monster declares an attack". */
    static Fx attackTrap(String label, TrapAction action) {
        return Fx.activate(label)
                .when((g, c, x) -> x != null && x.kind.equals("attack") && g.chain.isEmpty() && onField(g.attacker)
                        && g.attacker.controller != c.controller)
                .then((g, c, l, done) -> {
                    action.run(g, c, l);
                    done.run();
                });
    }

    private static Fx destroyTarget(String label, boolean either) {
        return Fx.activate(label)
                .when((g, c, x) -> !monsterTargets(g, c.controllerOrOwner(), c, either).isEmpty())
                .cost((g, c, l, done) -> target(g, l, c.def.name + ": choose a monster to destroy", monsterTargets(g, l.player, c, either), done))
                .then((g, c, l, done) -> {
                    if (onField(l.target())) g.destroy(l.target(), "effect", l.player, c);
                    done.run();
                });
    }

    static List<Card> monsterTargets(YgoGame g, int who, Card source, boolean either) {
        List<Card> pool = new ArrayList<>(g.monsters(g.opp(who)));
        if (either) {
            pool.addAll(g.monsters(who));
        }
        return targetable(g, who, source, pool);
    }

    static List<Card> stTargets(YgoGame g, int who, Card source) {
        List<Card> pool = new ArrayList<>(g.spellsTraps(0));
        pool.addAll(g.spellsTraps(1));
        pool.remove(source);
        return targetable(g, who, source, pool);
    }

    private static List<Card> reviveTargets(YgoGame g, int who, Card source, boolean either) {
        List<Card> pool = new ArrayList<>(g.pl(who).gy);
        if (either) {
            pool.addAll(g.pl(g.opp(who)).gy);
        }
        return targetable(g, who, source, revivable(g, who, pool, c -> true));
    }

    private static List<Card> equipTargets(YgoGame g, int who, Card source, YgoCard d) {
        List<Card> out = new ArrayList<>();
        for (Card m : g.allMonsters()) {
            if (m.faceUp && (!d.equipStrict || d.equipMatches(m.def)) && g.canTarget(who, source, m)) {
                out.add(m);
            }
        }
        return out;
    }

    // =================================================================== staples used by several decks

    private static void registerStaples() {
        def("Ash Blossom & Joyous Spring", () -> new Script().add(Fx.quick("Negate an effect that searches, summons or sends from the Deck")
                .from(Zone.HAND).once("Ash Blossom & Joyous Spring")
                .when((g, c, x) -> x != null && x.link != null && !x.link.negated && g.chain.contains(x.link)
                        && YgoGame.catsOf(x.link).stream().anyMatch(k -> k.equals("search") || k.equals("ss_deck") || k.equals("send_deck")))
                .cost((g, c, l, done) -> {
                    l.data.put("negate", l.ctx.link);
                    g.sendToGy(c, "discard");
                    done.run();
                })
                .then((g, c, l, done) -> {
                    Link t = (Link) l.data.get("negate");
                    if (t != null && g.chain.contains(t)) {
                        g.negate(t);
                        g.note("Ash Blossom & Joyous Spring negates " + g.name(t.card) + "'s effect.");
                    }
                    done.run();
                })));

        def("Polymerization", () -> new Script().add(Fx.activate("Fusion Summon")
                .when((g, c, x) -> !g.fusionTargetsFor(c.controllerOrOwner(), without(g.handAndField(c.controllerOrOwner()), c), f -> true, null).isEmpty())
                .then((g, c, l, done) -> g.fusionSummon(l.player, without(g.handAndField(l.player), c), f -> true, null, false, done))));
    }

    static List<Card> without(List<Card> cards, Card c) {
        List<Card> out = new ArrayList<>(cards);
        out.remove(c);
        return out;
    }
}
