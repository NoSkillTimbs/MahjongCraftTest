package com.tablecards.engine.ygo;

import com.tablecards.engine.ygo.YgoGame.Card;
import com.tablecards.engine.ygo.YgoGame.Zone;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static com.tablecards.engine.ygo.YgoScripts.def;
import static com.tablecards.engine.ygo.YgoScripts.onField;
import static com.tablecards.engine.ygo.YgoScripts.revivable;
import static com.tablecards.engine.ygo.YgoScripts.target;
import static com.tablecards.engine.ygo.YgoScripts.targetable;
import static com.tablecards.engine.ygo.YgoScripts.without;

/** Blue-Eyes White Dragon and its support, each written from its card text. */
final class ScriptsBlueEyes {
    static final String BEWD = "Blue-Eyes White Dragon";

    private ScriptsBlueEyes() {
    }

    static boolean blueEyesMonster(YgoGame g, Card c) {
        return g.isMonster(c) && g.isArch(c, "Blue-Eyes");
    }

    /** "Blue-Eyes White Dragon" cards in the hand (by printed name: it isn't renamed there). */
    static List<Card> bewdInHand(YgoGame g, int who) {
        return g.where(g.pl(who).hand, c -> c.def.name.equals(BEWD));
    }

    static void register() {
        // ---------------------------------------------------------------- main deck monsters

        def("Blue-Eyes Alternative White Dragon", () -> {
            Script s = new Script();
            s.alias = BEWD;
            s.noNormalSummon = true;
            s.mustProperSummon = true;
            s.proc("Special Summon by revealing \"Blue-Eyes White Dragon\"", Zone.HAND,
                    (g, c, x) -> g.freeMonsterZone(c.owner) && !bewdInHand(g, c.owner).isEmpty() && !g.pl(c.owner).once.contains("alt_proc"),
                    (g, c, l, done) -> {
                        g.pl(c.owner).once.add("alt_proc");
                        g.note(g.name(c.owner) + " reveals Blue-Eyes White Dragon.");
                        c.properOnce = true;
                        g.specialSummon(c, c.owner, "special", false);
                        done.run();
                    });
            s.add(Fx.ignition("Destroy 1 monster your opponent controls").once("alt_destroy")
                    .when((g, c, x) -> !YgoScripts.monsterTargets(g, c.controller, c, false).isEmpty())
                    .cost((g, c, l, done) -> {
                        g.flag(c, "noattack");
                        target(g, l, "Choose a monster to destroy", YgoScripts.monsterTargets(g, l.player, c, false), done);
                    })
                    .then((g, c, l, done) -> {
                        if (onField(l.target())) g.destroy(l.target(), "effect", l.player, c);
                        done.run();
                    }));
            return s;
        });

        def("Sage with Eyes of Blue", () -> {
            Script s = new Script();
            s.tuner = true;
            Predicate<Card> lightTuner = c -> c.def.isMonster() && c.def.level == 1 && c.def.attribute.equals("LIGHT")
                    && (c.def.tuner || c.script.tuner) && !c.def.name.equals("Sage with Eyes of Blue");
            s.add(Fx.trigger("Add 1 Level 1 LIGHT Tuner from your Deck to your hand", Fx.Ev.SUMMONED).cats("search")
                    .when((g, c, x) -> x.card == c && x.how.equals("normal") && !g.deckWhere(c.controller, lightTuner).isEmpty())
                    .then((g, c, l, done) -> g.search(l.player, "Add which Level 1 LIGHT Tuner?", lightTuner, done)));
            s.add(Fx.ignition("Discard this card: send 1 Effect Monster you control to the GY, then Special Summon 1 \"Blue-Eyes\" monster from your Deck")
                    .from(Zone.HAND).once("sage_ss").cats("ss_deck")
                    .when((g, c, x) -> !sageTargets(g, c.owner, c).isEmpty() && !g.deckWhere(c.owner, d -> blueEyesMonster(g, d)).isEmpty())
                    .cost((g, c, l, done) -> {
                        g.sendToGy(c, "discard");
                        target(g, l, "Choose an Effect Monster you control to send to the GY", sageTargets(g, l.player, c), done);
                    })
                    .then((g, c, l, done) -> {
                        Card t = l.target();
                        if (!onField(t)) {
                            done.run();
                            return;
                        }
                        g.sendToGy(t, "effect");
                        if (t.zone != Zone.GY) {
                            done.run();
                            return;
                        }
                        List<Card> be = g.deckWhere(l.player, d -> blueEyesMonster(g, d) && g.canSpecial(l.player, d));
                        if (be.isEmpty()) {
                            done.run();
                            return;
                        }
                        g.pick(l.player, "Special Summon which \"Blue-Eyes\" monster from your Deck?", be, false, "pick", b -> {
                            g.summonFrom(l.player, b, false);
                            done.run();
                        }, null);
                    }));
            return s;
        });

        def("The White Stone of Ancients", () -> {
            Script s = new Script();
            s.tuner = true;
            s.add(Fx.trigger("Special Summon 1 \"Blue-Eyes\" monster from your Deck", Fx.Ev.END_PHASE).from(Zone.GY).once("wsa_end").cats("ss_deck")
                    .when((g, c, x) -> c.sentToGyTurn == g.turn && g.freeMonsterZone(c.owner)
                            && !g.deckWhere(c.owner, d -> blueEyesMonster(g, d) && g.canSpecial(c.owner, d)).isEmpty())
                    .then((g, c, l, done) -> {
                        List<Card> be = g.deckWhere(l.player, d -> blueEyesMonster(g, d) && g.canSpecial(l.player, d));
                        if (be.isEmpty()) {
                            done.run();
                            return;
                        }
                        g.pick(l.player, "Special Summon which \"Blue-Eyes\" monster?", be, false, "pick", b -> {
                            g.summonFrom(l.player, b, false);
                            done.run();
                        }, null);
                    }));
            s.add(Fx.ignition("Banish this card from your GY: add 1 \"Blue-Eyes\" monster from your GY to your hand").from(Zone.GY).once("wsa_gy")
                    .when((g, c, x) -> !gyBlueEyes(g, c.owner, c).isEmpty())
                    .cost((g, c, l, done) -> {
                        g.banish(c, "cost");
                        target(g, l, "Add which \"Blue-Eyes\" monster to your hand?", gyBlueEyes(g, l.player, c), done);
                    })
                    .then((g, c, l, done) -> {
                        if (l.target() != null && l.target().zone == Zone.GY) g.toHand(l.target());
                        done.run();
                    }));
            return s;
        });

        def("The White Stone of Legend", () -> {
            Script s = new Script();
            s.tuner = true;
            s.add(Fx.trigger("Add 1 \"Blue-Eyes White Dragon\" from your Deck to your hand", Fx.Ev.SENT_TO_GY).from(Zone.GY).mandatory().cats("search")
                    .when((g, c, x) -> x.card == c)
                    .then((g, c, l, done) -> {
                        List<Card> b = g.deckWhere(l.player, d -> d.def.name.equals(BEWD));
                        if (!b.isEmpty()) g.toHand(b.get(0));
                        done.run();
                    }));
            return s;
        });

        def("Dragon Spirit of White", () -> {
            Script s = new Script();
            s.alsoArchetype = "Blue-Eyes";
            s.normalInHandGy = true;
            s.add(Fx.trigger("Banish 1 Spell/Trap your opponent controls", Fx.Ev.SUMMONED)
                    .when((g, c, x) -> x.card == c && !targetable(g, c.controller, c, g.spellsTraps(g.opp(c.controller))).isEmpty())
                    .cost((g, c, l, done) -> target(g, l, "Banish which Spell/Trap?", targetable(g, l.player, c, g.spellsTraps(g.opp(l.player))), done))
                    .then((g, c, l, done) -> {
                        if (onField(l.target())) {
                            g.note(g.name(l.target()) + " is banished.");
                            g.banish(l.target(), "effect");
                        }
                        done.run();
                    }));
            s.add(Fx.quick("Tribute this card: Special Summon 1 \"Blue-Eyes White Dragon\" from your hand")
                    .when((g, c, x) -> !g.monsters(g.opp(c.controller)).isEmpty() && !bewdInHand(g, c.controller).isEmpty())
                    .cost((g, c, l, done) -> {
                        g.note(g.name(l.player) + " tributes Dragon Spirit of White.");
                        g.sendToGy(c, "tribute");
                        done.run();
                    })
                    .then((g, c, l, done) -> {
                        List<Card> b = g.where(bewdInHand(g, l.player), d -> g.canSpecial(l.player, d));
                        if (b.isEmpty()) {
                            done.run();
                            return;
                        }
                        g.summonFrom(l.player, b.get(0), false);
                        done.run();
                    }));
            return s;
        });

        def("Kaibaman", () -> new Script().add(Fx.ignition("Tribute this card: Special Summon 1 \"Blue-Eyes White Dragon\" from your hand")
                .when((g, c, x) -> !bewdInHand(g, c.controller).isEmpty())
                .cost((g, c, l, done) -> {
                    g.sendToGy(c, "tribute");
                    done.run();
                })
                .then((g, c, l, done) -> {
                    List<Card> b = g.where(bewdInHand(g, l.player), d -> g.canSpecial(l.player, d));
                    if (!b.isEmpty()) g.summonFrom(l.player, b.get(0), false);
                    done.run();
                })));

        def("Blue-Eyes Chaos MAX Dragon", () -> {
            Script s = new Script();
            s.ritual = true;
            s.noNormalSummon = true;
            s.mustProperSummon = true;
            s.oppCantTarget = true;
            s.oppCantDestroy = true;
            s.piercing = 2;
            return s;
        });

        // ---------------------------------------------------------------- spells and traps

        def("Chaos Form", () -> new Script().add(Fx.activate("Ritual Summon a \"Chaos\" or \"Black Luster Soldier\" Ritual Monster")
                .when((g, c, x) -> !g.ritualTargets(c.controllerOrOwner(), c, chaosRitual(), chaosGy(g), false).isEmpty())
                .then((g, c, l, done) -> g.ritualSummon(l.player, c, chaosRitual(), chaosGy(g), false, done))));

        def("Trade-In", () -> new Script().add(Fx.activate("Discard 1 Level 8 monster; draw 2 cards")
                .when((g, c, x) -> !g.where(without(g.pl(c.controllerOrOwner()).hand, c), h -> g.isMonster(h) && g.level(h) == 8).isEmpty()
                        && g.pl(c.controllerOrOwner()).deck.size() >= 2)
                .cost((g, c, l, done) -> g.discardOne(l.player, h -> h != c && g.isMonster(h) && g.level(h) == 8, "Discard a Level 8 monster",
                        h -> done.run()))
                .then((g, c, l, done) -> {
                    g.draw(l.player, 2);
                    done.run();
                })));

        def("Cards of Consonance", () -> new Script().add(Fx.activate("Discard 1 Dragon Tuner with 1000 or less ATK; draw 2 cards")
                .when((g, c, x) -> !g.where(g.pl(c.controllerOrOwner()).hand, h -> consonance(g, h)).isEmpty()
                        && g.pl(c.controllerOrOwner()).deck.size() >= 2)
                .cost((g, c, l, done) -> g.discardOne(l.player, h -> consonance(g, h), "Discard a Dragon Tuner with 1000 or less ATK", h -> done.run()))
                .then((g, c, l, done) -> {
                    g.draw(l.player, 2);
                    done.run();
                })));

        def("Silver's Cry", () -> new Script().add(Fx.activate("Special Summon 1 Dragon Normal Monster from your GY").once("act:Silver's Cry")
                .when((g, c, x) -> !silverTargets(g, c.controllerOrOwner(), c).isEmpty())
                .cost((g, c, l, done) -> target(g, l, "Special Summon which Dragon Normal Monster?", silverTargets(g, l.player, c), done))
                .then((g, c, l, done) -> {
                    if (l.target() != null && l.target().zone == Zone.GY) g.summonFrom(l.player, l.target(), false);
                    done.run();
                })));

        def("Dragon Shrine", () -> new Script().add(Fx.activate("Send 1 Dragon from your Deck to the GY (2 if it's a Dragon Normal Monster)")
                .once("act:Dragon Shrine").cats("send_deck")
                .when((g, c, x) -> !g.deckWhere(c.controllerOrOwner(), d -> g.race(d, "Dragon")).isEmpty())
                .then((g, c, l, done) -> {
                    List<Card> dragons = g.deckWhere(l.player, d -> g.race(d, "Dragon"));
                    g.pick(l.player, "Send which Dragon to the GY?", dragons, false, "pick", d -> {
                        g.sendToGy(d, "effect");
                        g.note(g.name(l.player) + " sends " + g.name(d) + " to the GY.");
                        List<Card> more = g.deckWhere(l.player, e -> g.race(e, "Dragon"));
                        if (d.zone == Zone.GY && g.isNormalMonster(d) && g.race(d, "Dragon") && !more.isEmpty()) {
                            g.pickOr(l.player, "Send 1 more Dragon to the GY?", more, "pick", e -> {
                                g.sendToGy(e, "effect");
                                g.note(g.name(l.player) + " sends " + g.name(e) + " to the GY.");
                                g.shuffleDeck(l.player);
                                done.run();
                            }, "Don't", () -> {
                                g.shuffleDeck(l.player);
                                done.run();
                            });
                        } else {
                            g.shuffleDeck(l.player);
                            done.run();
                        }
                    }, null);
                })));

        def("Return of the Dragon Lords", () -> new Script().add(Fx.activate("Special Summon 1 Level 7 or 8 Dragon from your GY")
                .when((g, c, x) -> !lordTargets(g, c.controllerOrOwner(), c).isEmpty())
                .cost((g, c, l, done) -> target(g, l, "Special Summon which Dragon?", lordTargets(g, l.player, c), done))
                .then((g, c, l, done) -> {
                    if (l.target() != null && l.target().zone == Zone.GY) g.summonFrom(l.player, l.target(), false);
                    done.run();
                })));

        // ---------------------------------------------------------------- Extra Deck

        def("Blue-Eyes Ultimate Dragon", () -> {
            Script s = new Script();
            s.fusion = new ArrayList<>(List.of(c -> c.zone != null && isBewd(c), ScriptsBlueEyes::isBewd, ScriptsBlueEyes::isBewd));
            return s;
        });

        def("Blue-Eyes Twin Burst Dragon", () -> {
            Script s = new Script();
            s.fusion = new ArrayList<>(List.of(ScriptsBlueEyes::isBewd, ScriptsBlueEyes::isBewd));
            s.mustProperSummon = true;
            s.cannotBeDestroyedByBattle = true;
            s.monsterAttacks = 2;
            s.proc("Special Summon by sending 2 \"Blue-Eyes White Dragon\" you control to the GY", Zone.EXTRA,
                    (g, c, x) -> g.where(g.monsters(c.owner), m -> m.faceUp && g.named(m, BEWD)).size() >= 2,
                    (g, c, l, done) -> {
                        List<Card> chosen = new ArrayList<>();
                        Runnable[] next = new Runnable[1];
                        next[0] = () -> {
                            if (chosen.size() == 2) {
                                for (Card m : chosen) g.sendToGy(m, "material");
                                g.note(g.name(c.owner) + " sends 2 \"Blue-Eyes White Dragon\" to the GY.");
                                c.properOnce = true;
                                g.specialSummon(c, c.owner, "special", false);
                                done.run();
                                return;
                            }
                            List<Card> opts = g.where(g.monsters(c.owner), m -> m.faceUp && g.named(m, BEWD) && !chosen.contains(m));
                            g.pick(c.owner, "Send which \"Blue-Eyes White Dragon\" to the GY?", opts, false, "material", m -> {
                                chosen.add(m);
                                next[0].run();
                            }, null);
                        };
                        next[0].run();
                    });
            s.add(Fx.trigger("Banish the monster it battled", Fx.Ev.BATTLE_SURVIVED)
                    .when((g, c, x) -> x.card == c && onField(x.other) && x.other.controller != c.controller)
                    .then((g, c, l, done) -> {
                        Card t = l.ctx.other;
                        if (onField(t) && g.affects(l.player, c, t)) {
                            g.note(g.name(t) + " is banished.");
                            g.banish(t, "effect");
                        }
                        done.run();
                    }));
            return s;
        });

        def("Blue-Eyes Spirit Dragon", () -> {
            Script s = new Script();
            s.synchroNonTuner = c -> c.def.isMonster() && (c.def.name.contains("Blue-Eyes") || "Blue-Eyes".equals(c.script.alsoArchetype));
            s.add(Fx.quick("Negate the activation of an effect from the GY").once("bespirit_negate")
                    .when((g, c, x) -> x != null && x.link != null && x.link.fromGy && !x.link.negated && g.chain.contains(x.link))
                    .cost((g, c, l, done) -> {
                        l.data.put("negate", l.ctx.link);
                        done.run();
                    })
                    .then((g, c, l, done) -> {
                        YgoGame.Link t = (YgoGame.Link) l.data.get("negate");
                        if (t != null && g.chain.contains(t)) {
                            g.negate(t);
                            g.note("Blue-Eyes Spirit Dragon negates the activation.");
                        }
                        done.run();
                    }));
            s.add(Fx.quick("Tribute this card: Special Summon 1 LIGHT Dragon Synchro Monster from your Extra Deck")
                    .when((g, c, x) -> c.summon.equals("synchro") && !spiritTargets(g, c.controller).isEmpty())
                    .cost((g, c, l, done) -> {
                        g.sendToGy(c, "tribute");
                        done.run();
                    })
                    .then((g, c, l, done) -> {
                        List<Card> t = spiritTargets(g, l.player);
                        if (t.isEmpty() || !g.freeMonsterZone(l.player)) {
                            done.run();
                            return;
                        }
                        g.pick(l.player, "Special Summon which Synchro Monster (in Defense Position)?", t, false, "pick", m -> {
                            g.specialSummon(m, l.player, "special", true);
                            g.destroyAtEnd.add(m);
                            done.run();
                        }, null);
                    }));
            return s;
        });

        def("Azure-Eyes Silver Dragon", () -> {
            Script s = new Script();
            s.synchroNonTuner = c -> c.def.isMonster() && c.def.isNormalMonster();
            s.add(Fx.trigger("Your Dragons can't be targeted or destroyed by card effects until the end of the next turn", Fx.Ev.SUMMONED)
                    .mandatory()
                    .when((g, c, x) -> x.card == c)
                    .then((g, c, l, done) -> {
                        for (Card m : g.monsters(l.player)) {
                            if (g.race(m, "Dragon")) m.flags.put("azure", g.turn + 1);
                        }
                        g.note("Dragons " + g.name(l.player) + " controls can't be targeted or destroyed by card effects until the end of the next turn.");
                        done.run();
                    }));
            s.add(Fx.trigger("Special Summon 1 Normal Monster from your GY", Fx.Ev.STANDBY).once("azure_standby")
                    .when((g, c, x) -> g.tp == c.controller && !targetable(g, c.controller, c, revivable(g, c.controller, g.pl(c.controller).gy,
                            g::isNormalMonster)).isEmpty())
                    .cost((g, c, l, done) -> target(g, l, "Special Summon which Normal Monster?",
                            targetable(g, l.player, c, revivable(g, l.player, g.pl(l.player).gy, g::isNormalMonster)), done))
                    .then((g, c, l, done) -> {
                        if (l.target() != null && l.target().zone == Zone.GY) g.summonFrom(l.player, l.target(), false);
                        done.run();
                    }));
            return s;
        });
    }

    static boolean isBewd(Card c) {
        // name() needs the game; a card's alias applies on the field and in the GY only
        boolean aliasApplies = c.onField() || c.zone == Zone.GY;
        return c.def.name.equals(BEWD) || (aliasApplies && BEWD.equals(c.script.alias));
    }

    private static Predicate<Card> chaosRitual() {
        return r -> r.def.name.contains("Chaos") || r.def.name.contains("Black Luster Soldier");
    }

    private static Predicate<Card> chaosGy(YgoGame g) {
        return c -> g.named(c, BEWD) || g.named(c, "Dark Magician");
    }

    private static boolean consonance(YgoGame g, Card h) {
        return g.isMonster(h) && g.race(h, "Dragon") && g.isTuner(h) && h.def.atk <= 1000;
    }

    private static List<Card> sageTargets(YgoGame g, int who, Card source) {
        return targetable(g, who, source, g.where(g.monsters(who), m -> m.faceUp && g.isEffectMonster(m)));
    }

    private static List<Card> gyBlueEyes(YgoGame g, int who, Card self) {
        return targetable(g, who, self, g.where(g.pl(who).gy, c -> c != self && blueEyesMonster(g, c)));
    }

    private static List<Card> silverTargets(YgoGame g, int who, Card source) {
        return targetable(g, who, source, revivable(g, who, g.pl(who).gy, c -> g.race(c, "Dragon") && g.isNormalMonster(c)));
    }

    private static List<Card> lordTargets(YgoGame g, int who, Card source) {
        return targetable(g, who, source, revivable(g, who, g.pl(who).gy, c -> g.race(c, "Dragon") && (g.level(c) == 7 || g.level(c) == 8)));
    }

    private static List<Card> spiritTargets(YgoGame g, int who) {
        List<Card> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Card c : g.pl(who).extra) {
            if (c.def.frame.equals("synchro") && g.attr(c, "LIGHT") && g.race(c, "Dragon") && !c.def.name.equals("Blue-Eyes Spirit Dragon")
                    && seen.add(c.def.name)) {
                out.add(c);
            }
        }
        return out;
    }
}
