package com.tablecards.engine.ygo;

import com.tablecards.engine.ygo.YgoGame.Card;
import com.tablecards.engine.ygo.YgoGame.Zone;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static com.tablecards.engine.ygo.YgoScripts.def;
import static com.tablecards.engine.ygo.YgoScripts.onField;
import static com.tablecards.engine.ygo.YgoScripts.pickUpTo;
import static com.tablecards.engine.ygo.YgoScripts.revivable;
import static com.tablecards.engine.ygo.YgoScripts.target;
import static com.tablecards.engine.ygo.YgoScripts.targetable;
import static com.tablecards.engine.ygo.YgoScripts.without;

/** Dark Magician and its support, each written from its card text. */
final class ScriptsDarkMagician {
    static final String DM = "Dark Magician";
    static final String DMG = "Dark Magician Girl";

    private ScriptsDarkMagician() {
    }

    static boolean controlsDM(YgoGame g, int who) {
        return g.controls(who, c -> g.named(c, DM));
    }

    /** "Dark Magician" monsters: monsters with "Dark Magician" in their name. */
    static boolean dmMonster(YgoGame g, Card c) {
        return g.isMonster(c) && g.name(c).contains(DM);
    }

    static List<Card> fromHandDeckGy(YgoGame g, int who, Predicate<Card> pred) {
        List<Card> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<Card> l : List.of(g.pl(who).hand, g.pl(who).deck, g.pl(who).gy)) {
            for (Card c : l) {
                if (pred.test(c) && g.canSpecial(who, c) && seen.add(c.zone + c.def.name)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    static void register() {
        def("Dark Magician Girl", () -> {
            Script s = new Script();
            s.atkBonus = (g, self) -> {
                int n = 0;
                for (int i = 0; i < 2; i++) {
                    for (Card c : g.pl(i).gy) {
                        if (g.named(c, DM) || g.named(c, "Magician of Black Chaos")) n++;
                    }
                }
                return 300 * n;
            };
            return s;
        });

        def("Magician's Rod", () -> {
            Script s = new Script();
            s.add(Fx.trigger("Add 1 Spell/Trap that mentions \"Dark Magician\" from your Deck to your hand", Fx.Ev.SUMMONED).once("rod_search").cats("search")
                    .when((g, c, x) -> x.card == c && x.how.equals("normal") && !g.deckWhere(c.controller, d -> !g.isMonster(d) && d.def.mentions(DM)).isEmpty())
                    .then((g, c, l, done) -> g.search(l.player, "Add which Spell/Trap?", d -> !g.isMonster(d) && d.def.mentions(DM), done)));
            s.add(Fx.trigger("Tribute 1 Spellcaster: add this card from your GY to your hand", Fx.Ev.ACTIVATED).from(Zone.GY).once("rod_return")
                    .when((g, c, x) -> x.player == c.owner && g.tp != c.owner && x.card != null && !g.isMonster(x.card)
                            && !g.where(g.monsters(c.owner), m -> g.race(m, "Spellcaster")).isEmpty())
                    .cost((g, c, l, done) -> g.pick(l.player, "Tribute which Spellcaster?", g.where(g.monsters(l.player), m -> g.race(m, "Spellcaster")),
                            false, "tribute", m -> {
                                g.sendToGy(m, "tribute");
                                done.run();
                            }, null))
                    .then((g, c, l, done) -> {
                        if (c.zone == Zone.GY) g.toHand(c);
                        done.run();
                    }));
            return s;
        });

        def("Apprentice Illusion Magician", () -> {
            Script s = new Script();
            s.proc("Special Summon by discarding 1 card", Zone.HAND,
                    (g, c, x) -> g.freeMonsterZone(c.owner) && g.pl(c.owner).hand.size() >= 2,
                    (g, c, l, done) -> g.discardOne(c.owner, h -> h != c, "Discard a card to Special Summon Apprentice Illusion Magician", h -> {
                        g.specialSummon(c, c.owner, "special", false);
                        done.run();
                    }));
            s.add(Fx.trigger("Add 1 \"Dark Magician\" from your Deck to your hand", Fx.Ev.SUMMONED).cats("search")
                    .when((g, c, x) -> x.card == c && !g.deckWhere(c.controller, d -> d.def.name.equals(DM)).isEmpty())
                    .then((g, c, l, done) -> {
                        List<Card> dm = g.deckWhere(l.player, d -> d.def.name.equals(DM));
                        if (!dm.isEmpty()) g.toHand(dm.get(0));
                        done.run();
                    }));
            s.add(Fx.quick("Send this card to the GY: your DARK Spellcaster gains 2000 ATK/DEF during this damage calculation")
                    .from(Zone.HAND, Zone.MZONE)
                    .when((g, c, x) -> x != null && x.kind.equals("calc") && apprenticeBoostTarget(g, c) != null)
                    .cost((g, c, l, done) -> {
                        l.data.put("target", apprenticeBoostTarget(g, c));
                        g.sendToGy(c, "cost");
                        done.run();
                    })
                    .then((g, c, l, done) -> {
                        Card t = l.target();
                        if (onField(t)) {
                            g.calcBonus.merge(t, 2000, Integer::sum);
                            g.note(g.name(t) + " gains 2000 ATK/DEF for this damage calculation.");
                        }
                        done.run();
                    }));
            return s;
        });

        def("Palladium Oracle Mahad", () -> {
            Script s = new Script();
            s.doubleVsDark = true;
            s.add(Fx.trigger("Reveal it and Special Summon it from your hand", Fx.Ev.DRAWN).from(Zone.HAND)
                    .when((g, c, x) -> x.card == c && g.freeMonsterZone(c.owner))
                    .then((g, c, l, done) -> {
                        if (c.zone == Zone.HAND) g.summonFrom(l.player, c, false);
                        done.run();
                    }));
            s.add(Fx.trigger("Special Summon 1 \"Dark Magician\" from your hand, Deck or GY", Fx.Ev.DESTROYED).from(Zone.GY).cats("ss_deck")
                    .when((g, c, x) -> x.card == c && x.fromZone == Zone.MZONE && !fromHandDeckGy(g, c.owner, d -> d.def.name.equals(DM)).isEmpty())
                    .then((g, c, l, done) -> {
                        List<Card> dm = fromHandDeckGy(g, l.player, d -> d.def.name.equals(DM));
                        if (dm.isEmpty() || !g.freeMonsterZone(l.player)) {
                            done.run();
                            return;
                        }
                        g.pick(l.player, "Special Summon which \"Dark Magician\"?", dm, false, "pick", d -> {
                            g.summonFrom(l.player, d, false);
                            done.run();
                        }, null);
                    }));
            return s;
        });

        def("Magicians' Souls", () -> {
            Script s = new Script();
            s.add(Fx.ignition("Send up to 2 Spells/Traps from your hand/field to the GY; draw that many cards").once("souls_draw")
                    .when((g, c, x) -> !soulsFodder(g, c.controller).isEmpty() && !g.pl(c.controller).deck.isEmpty())
                    .cost((g, c, l, done) -> pickUpTo(g, l.player, "Send which Spells/Traps to the GY?", () -> soulsFodder(g, l.player), 1, 2, chosen -> {
                        for (Card t : chosen) g.sendToGy(t, "cost");
                        l.data.put("n", chosen.size());
                        done.run();
                    }))
                    .then((g, c, l, done) -> {
                        g.draw(l.player, (int) l.data.get("n"));
                        done.run();
                    }));
            s.add(Fx.ignition("Send 1 Level 6+ Spellcaster from your Deck to the GY, then Special Summon this card or revive \"Dark Magician\"/\"Dark Magician Girl\"")
                    .from(Zone.HAND).once("souls_hand")
                    .when((g, c, x) -> !g.deckWhere(c.owner, d -> g.race(d, "Spellcaster") && g.level(d) >= 6).isEmpty())
                    .cost((g, c, l, done) -> {
                        List<Card> sc = g.deckWhere(l.player, d -> g.race(d, "Spellcaster") && g.level(d) >= 6);
                        g.pick(l.player, "Send which Spellcaster from your Deck to the GY?", sc, false, "pick", d -> {
                            g.sendToGy(d, "cost");
                            g.shuffleDeck(l.player);
                            g.note(g.name(l.player) + " sends " + g.name(d) + " to the GY.");
                            g.chooseEffect(l.player, "Magicians' Souls: choose an effect", List.of("Special Summon this card",
                                    "Send this card to the GY, then you can Special Summon \"Dark Magician\" or \"Dark Magician Girl\" from your GY"), List.of(
                                    () -> {
                                        l.data.put("mode", 0);
                                        done.run();
                                    },
                                    () -> {
                                        l.data.put("mode", 1);
                                        done.run();
                                    }));
                        }, null);
                    })
                    .then((g, c, l, done) -> {
                        if (c.zone != Zone.HAND) {
                            done.run();
                            return;
                        }
                        if ((int) l.data.get("mode") == 0) {
                            g.summonFrom(l.player, c, false);
                            done.run();
                            return;
                        }
                        g.sendToGy(c, "effect");
                        List<Card> rev = revivable(g, l.player, g.pl(l.player).gy, d -> d.def.name.equals(DM) || d.def.name.equals(DMG));
                        if (rev.isEmpty()) {
                            done.run();
                            return;
                        }
                        g.pickOr(l.player, "Special Summon from your GY?", rev, "pick", d -> {
                            g.summonFrom(l.player, d, false);
                            done.run();
                        }, "Don't", done);
                    }));
            return s;
        });

        // ---------------------------------------------------------------- spells and traps

        def("Dark Magical Circle", () -> {
            Script s = new Script();
            s.add(Fx.activate("Look at the top 3 cards of your Deck; you can add a \"Dark Magician\" card from among them").once("dmc_look")
                    .then((g, c, l, done) -> {
                        List<Card> deck = g.pl(l.player).deck;
                        List<Card> top = new ArrayList<>(deck.subList(Math.max(0, deck.size() - 3), deck.size()));
                        List<Card> ok = g.where(top, d -> d.def.name.equals(DM) || (!g.isMonster(d) && d.def.mentions(DM)));
                        g.note(g.name(l.player) + " looks at the top " + top.size() + " cards of their Deck.");
                        if (ok.isEmpty()) {
                            done.run();
                            return;
                        }
                        g.pickOr(l.player, "Reveal and add which card to your hand?", ok, "pick", d -> {
                            g.move(d, Zone.HAND, "add");
                            g.note(g.name(l.player) + " adds " + g.name(d) + " to their hand.");
                            done.run();
                        }, "Don't add", done);
                    }));
            s.add(Fx.trigger("Banish 1 card your opponent controls", Fx.Ev.SUMMONED).from(Zone.SZONE).once("dmc_banish")
                    .when((g, c, x) -> x.player == c.controller && x.card.controller == c.controller && g.named(x.card, DM)
                            && !targetable(g, c.controller, c, g.cardsOnField(g.opp(c.controller))).isEmpty())
                    .cost((g, c, l, done) -> target(g, l, "Banish which card?", targetable(g, l.player, c, g.cardsOnField(g.opp(l.player))), done))
                    .then((g, c, l, done) -> {
                        if (onField(l.target()) && g.affects(l.player, c, l.target())) {
                            g.note(g.name(l.target().faceUp ? l.target() : l.target()) + " is banished.");
                            g.banish(l.target(), "effect");
                        }
                        done.run();
                    }));
            return s;
        });

        def("Illusion Magic", () -> new Script().add(Fx.activate("Tribute 1 Spellcaster; add up to 2 \"Dark Magician\" from your Deck and/or GY")
                .once("act:Illusion Magic").cats("search")
                .when((g, c, x) -> !g.where(g.monsters(c.controllerOrOwner()), m -> g.race(m, "Spellcaster")).isEmpty())
                .cost((g, c, l, done) -> g.pick(l.player, "Tribute which Spellcaster?", g.where(g.monsters(l.player), m -> g.race(m, "Spellcaster")),
                        false, "tribute", m -> {
                            g.sendToGy(m, "tribute");
                            done.run();
                        }, null))
                .then((g, c, l, done) -> pickUpTo(g, l.player, "Add which \"Dark Magician\"?", () -> {
                    List<Card> out = new ArrayList<>(g.where(g.pl(l.player).deck, d -> d.def.name.equals(DM)));
                    out.addAll(g.where(g.pl(l.player).gy, d -> d.def.name.equals(DM)));
                    return out;
                }, 0, 2, chosen -> {
                    for (Card d : chosen) {
                        g.toHand(d);
                    }
                    done.run();
                }))));

        def("Secrets of Dark Magic", () -> new Script().add(Fx.activate("Fusion or Ritual Summon using \"Dark Magician\" or \"Dark Magician Girl\"")
                .when((g, c, x) -> canSecretsFusion(g, c.controllerOrOwner(), c) || canSecretsRitual(g, c.controllerOrOwner(), c))
                .then((g, c, l, done) -> {
                    boolean fusion = canSecretsFusion(g, l.player, c);
                    boolean ritual = canSecretsRitual(g, l.player, c);
                    Runnable doFusion = () -> g.fusionSummon(l.player, without(g.handAndField(l.player), c), f -> true, ScriptsDarkMagician::includesDmOrDmg, false, done);
                    Runnable doRitual = () -> g.ritualSummon(l.player, c, r -> true, null, true, done);
                    if (fusion && ritual) {
                        g.chooseEffect(l.player, "Secrets of Dark Magic: choose", List.of("Fusion Summon", "Ritual Summon"), List.of(doFusion, doRitual));
                    } else if (fusion) {
                        doFusion.run();
                    } else if (ritual) {
                        doRitual.run();
                    } else {
                        done.run();
                    }
                })));

        def("Dark Magic Attack", () -> new Script().add(Fx.activate("Destroy all Spells and Traps your opponent controls")
                .when((g, c, x) -> controlsDM(g, c.controllerOrOwner()) && !g.spellsTraps(g.opp(c.controllerOrOwner())).isEmpty())
                .then((g, c, l, done) -> {
                    if (controlsDM(g, l.player)) {
                        for (Card t : List.copyOf(g.spellsTraps(g.opp(l.player)))) g.destroy(t, "effect", l.player, c);
                    }
                    done.run();
                })));

        def("Thousand Knives", () -> new Script().add(Fx.activate("Destroy 1 monster your opponent controls")
                .when((g, c, x) -> controlsDM(g, c.controllerOrOwner()) && !YgoScripts.monsterTargets(g, c.controllerOrOwner(), c, false).isEmpty())
                .cost((g, c, l, done) -> target(g, l, "Destroy which monster?", YgoScripts.monsterTargets(g, l.player, c, false), done))
                .then((g, c, l, done) -> {
                    if (onField(l.target())) g.destroy(l.target(), "effect", l.player, c);
                    done.run();
                })));

        def("The Eye of Timaeus", () -> new Script().add(Fx.activate("Fusion Summon using 1 \"Dark Magician\" monster you control as the entire material")
                .once("act:The Eye of Timaeus")
                .when((g, c, x) -> !timaeusTargets(g, c.controllerOrOwner(), c).isEmpty())
                .cost((g, c, l, done) -> target(g, l, "Choose a \"Dark Magician\" monster you control", timaeusTargets(g, l.player, c), done))
                .then((g, c, l, done) -> {
                    Card t = l.target();
                    if (!onField(t)) {
                        done.run();
                        return;
                    }
                    List<Card> fusions = timaeusFusions(g, l.player, t);
                    if (fusions.isEmpty()) {
                        done.run();
                        return;
                    }
                    g.pick(l.player, "Fusion Summon which monster?", fusions, false, "fusion", f -> {
                        t.fusionMaterial = true;
                        g.sendToGy(t, "material");
                        g.specialSummon(f, l.player, "fusion", false);
                        done.run();
                    }, null);
                })));

        def("Eternal Soul", () -> {
            Script s = new Script();
            s.add(Fx.activate("").then((g, c, l, done) -> {
                List<String> labels = new ArrayList<>();
                List<Runnable> acts = new ArrayList<>();
                addSoulModes(g, c, l.player, labels, acts, done);
                if (labels.isEmpty() || g.pl(l.player).once.contains("eternal_soul")) {
                    done.run();
                    return;
                }
                labels.add("No effect now");
                acts.add(done);
                g.chooseEffect(l.player, "Eternal Soul: use an effect now?", labels, acts);
            }));
            s.add(Fx.quick("Special Summon \"Dark Magician\" from hand/GY, or add \"Dark Magic Attack\"/\"Thousand Knives\"")
                    .from(Zone.SZONE).once("eternal_soul")
                    .when((g, c, x) -> {
                        List<String> labels = new ArrayList<>();
                        addSoulModes(g, c, c.controller, labels, new ArrayList<>(), () -> { });
                        return !labels.isEmpty();
                    })
                    .then((g, c, l, done) -> {
                        List<String> labels = new ArrayList<>();
                        List<Runnable> acts = new ArrayList<>();
                        addSoulModes(g, c, l.player, labels, acts, done);
                        if (labels.isEmpty()) {
                            done.run();
                            return;
                        }
                        g.chooseEffect(l.player, "Eternal Soul: choose an effect", labels, acts);
                    }));
            return s;
        });

        def("Bond Between Teacher and Student", () -> new Script().add(Fx.activate("Special Summon 1 \"Dark Magician Girl\" from your hand, Deck or GY")
                .once("act:Bond Between Teacher and Student").cats("ss_deck")
                .when((g, c, x) -> controlsDM(g, c.controllerOrOwner()) && g.freeMonsterZone(c.controllerOrOwner())
                        && !fromHandDeckGy(g, c.controllerOrOwner(), d -> d.def.name.equals(DMG)).isEmpty())
                .then((g, c, l, done) -> {
                    if (!controlsDM(g, l.player)) {
                        done.run();
                        return;
                    }
                    List<Card> girls = fromHandDeckGy(g, l.player, d -> d.def.name.equals(DMG));
                    if (girls.isEmpty()) {
                        done.run();
                        return;
                    }
                    g.pick(l.player, "Special Summon which \"Dark Magician Girl\"?", girls, false, "pick", d -> {
                        g.summonFrom(l.player, d, false);
                        Set<String> setNames = Set.of("Dark Magic Attack", "Dark Burning Attack", "Dark Burning Magic", "Dark Magic Twin Burst");
                        List<Card> sets = g.deckWhere(l.player, x -> setNames.contains(x.def.name));
                        if (sets.isEmpty() || !g.freeSpellZone(l.player)) {
                            done.run();
                            return;
                        }
                        g.pickOr(l.player, "Set 1 card directly from your Deck?", sets, "pick", x -> {
                            g.move(x, Zone.SZONE, "set");
                            x.faceUp = false;
                            x.setTurn = g.turn;
                            g.shuffleDeck(l.player);
                            g.note(g.name(l.player) + " sets a card from their Deck.");
                            done.run();
                        }, "Don't", done);
                    }, null);
                })));

        def("Sage's Stone", () -> new Script().add(Fx.activate("Special Summon 1 \"Dark Magician\" from your hand or Deck").cats("ss_deck")
                .when((g, c, x) -> g.controls(c.controllerOrOwner(), m -> g.named(m, DMG)) && g.freeMonsterZone(c.controllerOrOwner())
                        && !handOrDeck(g, c.controllerOrOwner(), d -> d.def.name.equals(DM)).isEmpty())
                .then((g, c, l, done) -> {
                    List<Card> dm = handOrDeck(g, l.player, d -> d.def.name.equals(DM));
                    if (dm.isEmpty() || !g.controls(l.player, m -> g.named(m, DMG))) {
                        done.run();
                        return;
                    }
                    g.pick(l.player, "Special Summon which \"Dark Magician\"?", dm, false, "pick", d -> {
                        g.summonFrom(l.player, d, false);
                        done.run();
                    }, null);
                })));

        def("Dark Magic Twin Burst", () -> new Script().add(Fx.activate("A \"Dark Magician\" you control gains the ATK of every \"Dark Magician Girl\"")
                .once("act:Dark Magic Twin Burst")
                .when((g, c, x) -> !targetable(g, c.controllerOrOwner(), c, g.where(g.monsters(c.controllerOrOwner()), m -> m.faceUp && g.named(m, DM))).isEmpty())
                .cost((g, c, l, done) -> target(g, l, "Choose a \"Dark Magician\"",
                        targetable(g, l.player, c, g.where(g.monsters(l.player), m -> m.faceUp && g.named(m, DM))), done))
                .then((g, c, l, done) -> {
                    Card t = l.target();
                    if (onField(t) && t.faceUp) {
                        int sum = 0;
                        for (Card m : g.allMonsters()) {
                            if (m.faceUp && g.named(m, DMG)) sum += g.atk(m);
                        }
                        for (int i = 0; i < 2; i++) {
                            for (Card d : g.pl(i).gy) {
                                if (g.named(d, DMG)) sum += d.def.atk;
                            }
                        }
                        g.boost(t, sum, 0, true);
                        g.note(g.name(t) + " gains " + sum + " ATK until the end of this turn.");
                    }
                    done.run();
                })));

        def("Dark Burning Attack", () -> new Script().add(Fx.activate("Destroy all face-up monsters your opponent controls")
                .when((g, c, x) -> g.controls(c.controllerOrOwner(), m -> g.name(m).contains(DMG)) && !g.faceUpMonsters(g.opp(c.controllerOrOwner())).isEmpty())
                .then((g, c, l, done) -> {
                    for (Card m : List.copyOf(g.faceUpMonsters(g.opp(l.player)))) g.destroy(m, "effect", l.player, c);
                    done.run();
                })));

        def("Dark Burning Magic", () -> new Script().add(Fx.activate("Destroy all cards your opponent controls")
                .when((g, c, x) -> burningMagicReady(g, c.controllerOrOwner()) && !g.cardsOnField(g.opp(c.controllerOrOwner())).isEmpty())
                .then((g, c, l, done) -> {
                    if (burningMagicReady(g, l.player)) {
                        for (Card t : List.copyOf(g.cardsOnField(g.opp(l.player)))) g.destroy(t, "effect", l.player, c);
                    }
                    done.run();
                })));

        def("Magician's Salvation", () -> {
            Script s = new Script();
            s.add(Fx.activate("You can Set 1 \"Eternal Soul\" from your Deck").once("act:Magician's Salvation")
                    .then((g, c, l, done) -> {
                        List<Card> souls = g.deckWhere(l.player, d -> d.def.name.equals("Eternal Soul"));
                        if (souls.isEmpty() || !g.freeSpellZone(l.player)) {
                            done.run();
                            return;
                        }
                        g.pickOr(l.player, "Set \"Eternal Soul\" from your Deck?", souls, "pick", d -> {
                            g.move(d, Zone.SZONE, "set");
                            d.faceUp = false;
                            d.setTurn = g.turn;
                            g.shuffleDeck(l.player);
                            g.note(g.name(l.player) + " sets a card from their Deck.");
                            done.run();
                        }, "Don't", done);
                    }));
            s.add(Fx.trigger("Special Summon \"Dark Magician\" or \"Dark Magician Girl\" from your GY", Fx.Ev.SUMMONED).from(Zone.FZONE).once("salvation_ss")
                    .when((g, c, x) -> x.player == c.controller && x.card.controller == c.controller
                            && (g.named(x.card, DM) || g.named(x.card, DMG)) && !salvationTargets(g, c.controller, x.card).isEmpty())
                    .cost((g, c, l, done) -> {
                        l.data.put("target", l.ctx.card);
                        done.run();
                    })
                    .then((g, c, l, done) -> {
                        Card summoned = l.target();
                        List<Card> rev = salvationTargets(g, l.player, summoned);
                        if (rev.isEmpty()) {
                            done.run();
                            return;
                        }
                        g.pick(l.player, "Special Summon which monster from your GY?", rev, false, "pick", d -> {
                            g.summonFrom(l.player, d, false);
                            done.run();
                        }, null);
                    }));
            return s;
        });

        def("Dark Magic Veil", () -> new Script().add(Fx.activate("Pay 1000 LP; Special Summon 1 DARK Spellcaster from your hand or GY")
                .when((g, c, x) -> g.pl(c.controllerOrOwner()).lp > 1000 && g.freeMonsterZone(c.controllerOrOwner())
                        && !veilTargets(g, c.controllerOrOwner(), c).isEmpty())
                .cost((g, c, l, done) -> {
                    g.pl(l.player).lp -= 1000;
                    g.note(g.name(l.player) + " pays 1000 LP, LP " + g.pl(l.player).lp + ".");
                    done.run();
                })
                .then((g, c, l, done) -> {
                    List<Card> t = veilTargets(g, l.player, c);
                    if (t.isEmpty()) {
                        done.run();
                        return;
                    }
                    g.pick(l.player, "Special Summon which DARK Spellcaster?", t, false, "pick", d -> {
                        g.summonFrom(l.player, d, false);
                        done.run();
                    }, null);
                })));

        // ---------------------------------------------------------------- Extra Deck

        def("Dark Magician the Dragon Knight", () -> {
            Script s = new Script();
            s.alias = DM;
            s.fusion = new ArrayList<>(List.of(c -> isDm(c), c -> c.def.isMonster() && c.def.race.equals("Dragon")));
            return s;
        });

        def("Dark Magician Girl the Dragon Knight", () -> {
            Script s = new Script();
            s.mustProperSummon = true;
            s.fusion = new ArrayList<>(List.of(c -> c.def.name.equals(DMG), c -> c.def.isMonster() && c.def.race.equals("Dragon")));
            s.add(Fx.quick("Send 1 card from your hand to the GY: destroy 1 face-up card on the field").once("dmgdk")
                    .when((g, c, x) -> !g.pl(c.controller).hand.isEmpty() && !faceUpTargets(g, c.controller, c).isEmpty())
                    .cost((g, c, l, done) -> g.discardOne(l.player, h -> true, "Send which card from your hand to the GY?",
                            h -> target(g, l, "Destroy which face-up card?", faceUpTargets(g, l.player, c), done)))
                    .then((g, c, l, done) -> {
                        if (onField(l.target()) && l.target().faceUp) g.destroy(l.target(), "effect", l.player, c);
                        done.run();
                    }));
            return s;
        });

        def("The Dark Magicians", () -> {
            Script s = new Script();
            s.fusion = new ArrayList<>(List.of(c -> isDm(c) || c.def.name.equals(DMG), c -> c.def.isMonster() && c.def.race.equals("Spellcaster")));
            s.add(Fx.trigger("Draw 1 card (you can Set it if it's a Spell/Trap)", Fx.Ev.ACTIVATED).once("tdm_draw")
                    .when((g, c, x) -> x.card != null && !g.isMonster(x.card) && x.card != c && !g.pl(c.controller).deck.isEmpty())
                    .then((g, c, l, done) -> {
                        List<Card> deck = g.pl(l.player).deck;
                        if (deck.isEmpty()) {
                            done.run();
                            return;
                        }
                        Card d = deck.get(deck.size() - 1);
                        g.drawCard(l.player);
                        if (g.isMonster(d) || !g.freeSpellZone(l.player) || d.def.stype.equals("field")) {
                            done.run();
                            return;
                        }
                        g.yesNo(l.player, "You drew " + d.def.name + ". Set it?", () -> {
                            g.move(d, Zone.SZONE, "set");
                            d.faceUp = false;
                            // a Trap or Quick-Play Spell Set this way can be activated this turn
                            boolean now = g.isTrap(d) || d.def.stype.equals("quick");
                            d.setTurn = now ? g.turn - 1 : g.turn;
                            g.note(g.name(l.player) + " sets the drawn card.");
                            done.run();
                        }, done);
                    }));
            s.add(Fx.trigger("Special Summon 1 \"Dark Magician\" and 1 \"Dark Magician Girl\" from your hand, Deck and/or GY", Fx.Ev.DESTROYED)
                    .from(Zone.GY, Zone.BANISHED).cats("ss_deck")
                    .when((g, c, x) -> x.card == c && g.canSummonSeveral() && g.pl(c.owner).monsters.size() <= YgoGame.ZONES - 2
                            && !fromHandDeckGy(g, c.owner, d -> d.def.name.equals(DM)).isEmpty()
                            && !fromHandDeckGy(g, c.owner, d -> d.def.name.equals(DMG)).isEmpty())
                    .then((g, c, l, done) -> {
                        if (!g.canSummonSeveral() || g.pl(l.player).monsters.size() > YgoGame.ZONES - 2) {
                            done.run();
                            return;
                        }
                        List<Card> dms = fromHandDeckGy(g, l.player, d -> d.def.name.equals(DM));
                        List<Card> girls = fromHandDeckGy(g, l.player, d -> d.def.name.equals(DMG));
                        if (dms.isEmpty() || girls.isEmpty()) {
                            done.run();
                            return;
                        }
                        g.pick(l.player, "Special Summon which \"Dark Magician\"?", dms, false, "pick", a ->
                                g.pick(l.player, "Special Summon which \"Dark Magician Girl\"?", girls, false, "pick", b -> {
                                    g.summonFrom(l.player, a, false);
                                    g.summonFrom(l.player, b, false);
                                    done.run();
                                }, null), null);
                    }));
            return s;
        });
    }

    // ---------------------------------------------------------------- helpers

    static boolean isDm(Card c) {
        boolean aliasApplies = c.onField() || c.zone == Zone.GY;
        return c.def.name.equals(DM) || (aliasApplies && DM.equals(c.script.alias));
    }

    private static boolean includesDmOrDmg(List<Card> materials) {
        for (Card c : materials) {
            if (isDm(c) || c.def.name.equals(DMG)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canSecretsFusion(YgoGame g, int who, Card self) {
        return !g.fusionTargetsFor(who, without(g.handAndField(who), self), f -> true, ScriptsDarkMagician::includesDmOrDmg).isEmpty();
    }

    /** Ritual mode: needs a Ritual Monster in hand, and "Dark Magician"/"Dark Magician Girl" among the Tributes. */
    private static boolean canSecretsRitual(YgoGame g, int who, Card self) {
        boolean dmAvailable = !g.where(g.handAndField(who), c -> isDm(c) || c.def.name.equals(DMG)).isEmpty();
        return dmAvailable && !g.ritualTargets(who, self, r -> true, null, true).isEmpty();
    }

    private static List<Card> soulsFodder(YgoGame g, int who) {
        List<Card> out = new ArrayList<>(g.where(g.pl(who).hand, c -> !g.isMonster(c)));
        out.addAll(g.spellsTraps(who));
        return out;
    }

    private static Card apprenticeBoostTarget(YgoGame g, Card self) {
        int me = self.controllerOrOwner();
        for (Card m : List.of(g.attacker, g.attackTarget == null ? g.attacker : g.attackTarget)) {
            if (m != null && m != self && m.controller == me && m.zone == Zone.MZONE && m.faceUp && g.attr(m, "DARK") && g.race(m, "Spellcaster")
                    && g.attackTarget != null) {
                return m;
            }
        }
        return null;
    }

    private static List<Card> timaeusTargets(YgoGame g, int who, Card source) {
        return targetable(g, who, source, g.where(g.monsters(who), m -> m.faceUp && dmMonster(g, m) && !timaeusFusions(g, who, m).isEmpty()));
    }

    /** Fusion Monsters that list this monster as material (by name). */
    private static List<Card> timaeusFusions(YgoGame g, int who, Card material) {
        List<Card> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Card f : g.pl(who).extra) {
            if (f.def.frame.equals("fusion") && f.def.text.contains("\"" + g.name(material) + "\"") && seen.add(f.def.name)) {
                out.add(f);
            }
        }
        return out;
    }

    private static void addSoulModes(YgoGame g, Card self, int who, List<String> labels, List<Runnable> acts, Runnable done) {
        List<Card> dm = new ArrayList<>(g.where(g.pl(who).hand, d -> d.def.name.equals(DM) && g.canSpecial(who, d)));
        dm.addAll(g.where(g.pl(who).gy, d -> d.def.name.equals(DM) && g.canSpecial(who, d)));
        if (!dm.isEmpty()) {
            labels.add("Special Summon 1 \"Dark Magician\" from your hand or GY");
            acts.add(() -> {
                g.pl(who).once.add("eternal_soul");
                List<Card> now = new ArrayList<>(g.where(g.pl(who).hand, d -> d.def.name.equals(DM) && g.canSpecial(who, d)));
                now.addAll(g.where(g.pl(who).gy, d -> d.def.name.equals(DM) && g.canSpecial(who, d)));
                if (now.isEmpty()) {
                    done.run();
                    return;
                }
                g.pick(who, "Special Summon which \"Dark Magician\"?", now, false, "pick", d -> {
                    g.summonFrom(who, d, false);
                    done.run();
                }, null);
            });
        }
        if (!g.deckWhere(who, d -> d.def.name.equals("Dark Magic Attack") || d.def.name.equals("Thousand Knives")).isEmpty()) {
            labels.add("Add 1 \"Dark Magic Attack\" or \"Thousand Knives\" from your Deck");
            acts.add(() -> {
                g.pl(who).once.add("eternal_soul");
                g.search(who, "Add which card?", d -> d.def.name.equals("Dark Magic Attack") || d.def.name.equals("Thousand Knives"), done);
            });
        }
    }

    private static List<Card> handOrDeck(YgoGame g, int who, Predicate<Card> pred) {
        List<Card> out = new ArrayList<>(g.where(g.pl(who).hand, d -> pred.test(d) && g.canSpecial(who, d)));
        out.addAll(g.deckWhere(who, d -> pred.test(d) && g.canSpecial(who, d)));
        return out;
    }

    private static boolean burningMagicReady(YgoGame g, int who) {
        return g.controls(who, m -> m.def.name.equals(DM)) && g.controls(who, m -> m.def.name.equals(DMG));
    }

    private static List<Card> salvationTargets(YgoGame g, int who, Card summoned) {
        String other = g.named(summoned, DM) ? DMG : DM;
        return revivable(g, who, g.pl(who).gy, d -> g.named(d, other) && d.def.name.equals(other));
    }

    private static List<Card> veilTargets(YgoGame g, int who, Card self) {
        List<Card> out = new ArrayList<>(g.where(g.pl(who).hand, d -> g.attr(d, "DARK") && g.race(d, "Spellcaster") && g.canSpecial(who, d)));
        out.addAll(g.where(g.pl(who).gy, d -> g.attr(d, "DARK") && g.race(d, "Spellcaster") && g.canSpecial(who, d)));
        return out;
    }

    private static List<Card> faceUpTargets(YgoGame g, int who, Card source) {
        List<Card> all = new ArrayList<>(g.cardsOnField(0));
        all.addAll(g.cardsOnField(1));
        return targetable(g, who, source, g.where(all, c -> c.faceUp));
    }
}
