package com.tablecards.engine.ygo;

import com.tablecards.engine.ygo.YgoGame.Card;
import com.tablecards.engine.ygo.YgoGame.Zone;

import java.util.ArrayList;
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

/** Elemental HEROes and their support, each written from its card text. */
final class ScriptsHero {
    private ScriptsHero() {
    }

    static boolean eHero(YgoGame g, Card c) {
        return g.isArch(c, "Elemental HERO");
    }

    static boolean hero(YgoGame g, Card c) {
        return g.name(c).contains("HERO");
    }

    static Predicate<Card> printed(String name) {
        return c -> c.def.name.equals(name);
    }

    static void register() {
        def("Elemental HERO Stratos", () -> new Script().add(Fx.trigger("Destroy Spells/Traps or add 1 \"HERO\" monster from your Deck", Fx.Ev.SUMMONED)
                .when((g, c, x) -> x.card == c && (canStratosDestroy(g, c) || !g.deckWhere(c.controller, d -> g.isMonster(d) && hero(g, d)).isEmpty()))
                .cost((g, c, l, done) -> {
                    List<String> labels = new ArrayList<>();
                    List<Runnable> acts = new ArrayList<>();
                    if (canStratosDestroy(g, c)) {
                        labels.add("Destroy Spells/Traps on the field, up to the number of other \"HERO\" monsters you control");
                        acts.add(() -> {
                            l.data.put("mode", 0);
                            done.run();
                        });
                    }
                    if (!g.deckWhere(l.player, d -> g.isMonster(d) && hero(g, d)).isEmpty()) {
                        labels.add("Add 1 \"HERO\" monster from your Deck to your hand");
                        acts.add(() -> {
                            l.data.put("mode", 1);
                            l.data.put("cats", Set.of("search"));
                            done.run();
                        });
                    }
                    g.chooseEffect(l.player, "Elemental HERO Stratos: choose an effect", labels, acts);
                })
                .then((g, c, l, done) -> {
                    if ((int) l.data.get("mode") == 1) {
                        g.search(l.player, "Add which \"HERO\" monster?", d -> g.isMonster(d) && hero(g, d), done);
                        return;
                    }
                    int n = stratosCount(g, c, l.player);
                    pickUpTo(g, l.player, "Destroy which Spells/Traps?", () -> allSpellsTraps(g, l.player, c), 1, Math.max(1, n), chosen -> {
                        for (Card t : chosen) g.destroy(t, "effect", l.player, c);
                        done.run();
                    });
                })));

        def("Elemental HERO Bubbleman", () -> {
            Script s = new Script();
            s.proc("Special Summon (it's the only card in your hand)", Zone.HAND,
                    (g, c, x) -> g.pl(c.owner).hand.size() == 1 && g.freeMonsterZone(c.owner),
                    (g, c, l, done) -> {
                        g.specialSummon(c, c.owner, "special", false);
                        done.run();
                    });
            s.add(Fx.trigger("Draw 2 cards (you control no other cards and have no cards in your hand)", Fx.Ev.SUMMONED)
                    .when((g, c, x) -> x.card == c && bubbleAlone(g, c))
                    .then((g, c, l, done) -> {
                        if (bubbleAlone(g, c)) g.draw(l.player, 2);
                        done.run();
                    }));
            return s;
        });

        def("Elemental HERO Wildheart", () -> {
            Script s = new Script();
            s.unaffectedByTraps = true;
            return s;
        });

        def("Winged Kuriboh", () -> new Script().add(Fx.trigger("You take no battle damage for the rest of this turn", Fx.Ev.SENT_TO_GY).from(Zone.GY)
                .when((g, c, x) -> x.card == c && x.fromZone == Zone.MZONE && (x.how.equals("battle") || x.how.equals("destroy")))
                .then((g, c, l, done) -> {
                    g.pl(l.player).noBattleDamageTurn = g.turn;
                    g.note(g.name(l.player) + " takes no battle damage for the rest of this turn.");
                    done.run();
                })));

        def("Elemental HERO Shadow Mist", () -> {
            Script s = new Script();
            s.add(Fx.trigger("Add 1 \"Change\" Quick-Play Spell from your Deck to your hand", Fx.Ev.SUMMONED).once("shadow_mist").cats("search")
                    .when((g, c, x) -> x.card == c && !x.how.equals("normal") && !x.how.equals("flip") && !g.deckWhere(c.controller, ScriptsHero::changeSpell).isEmpty())
                    .then((g, c, l, done) -> g.search(l.player, "Add which \"Change\" Quick-Play Spell?", ScriptsHero::changeSpell, done)));
            s.add(Fx.trigger("Add 1 \"HERO\" monster from your Deck to your hand, except \"Elemental HERO Shadow Mist\"", Fx.Ev.SENT_TO_GY).from(Zone.GY)
                    .once("shadow_mist").cats("search")
                    .when((g, c, x) -> x.card == c && !g.deckWhere(c.owner, d -> g.isMonster(d) && hero(g, d) && !d.def.name.equals(c.def.name)).isEmpty())
                    .then((g, c, l, done) -> g.search(l.player, "Add which \"HERO\" monster?", d -> g.isMonster(d) && hero(g, d)
                            && !d.def.name.equals("Elemental HERO Shadow Mist"), done)));
            return s;
        });

        // ---------------------------------------------------------------- spells and traps

        def("Miracle Fusion", () -> new Script().add(Fx.activate("Fusion Summon 1 \"Elemental HERO\" by banishing materials from your field or GY")
                .when((g, c, x) -> !g.fusionTargetsFor(c.controllerOrOwner(), miraclePool(g, c.controllerOrOwner()), f -> eHero(g, f), null).isEmpty())
                .then((g, c, l, done) -> g.fusionSummon(l.player, miraclePool(g, l.player), f -> eHero(g, f), null, true, done))));

        def("E - Emergency Call", () -> new Script().add(Fx.activate("Add 1 \"Elemental HERO\" monster from your Deck to your hand").cats("search")
                .when((g, c, x) -> !g.deckWhere(c.controllerOrOwner(), d -> g.isMonster(d) && eHero(g, d)).isEmpty())
                .then((g, c, l, done) -> g.search(l.player, "Add which \"Elemental HERO\" monster?", d -> g.isMonster(d) && eHero(g, d), done))));

        def("A Hero Lives", () -> new Script().add(Fx.activate("Pay half your LP; Special Summon 1 Level 4 or lower \"Elemental HERO\" from your Deck")
                .cats("ss_deck")
                .when((g, c, x) -> g.faceUpMonsters(c.controllerOrOwner()).isEmpty() && g.freeMonsterZone(c.controllerOrOwner())
                        && !g.deckWhere(c.controllerOrOwner(), d -> smallEHero(g, d) && g.canSpecial(c.controllerOrOwner(), d)).isEmpty())
                .cost((g, c, l, done) -> {
                    int half = g.pl(l.player).lp / 2;
                    g.pl(l.player).lp -= half;
                    g.note(g.name(l.player) + " pays " + half + " LP, LP " + g.pl(l.player).lp + ".");
                    done.run();
                })
                .then((g, c, l, done) -> {
                    List<Card> heroes = g.deckWhere(l.player, d -> smallEHero(g, d) && g.canSpecial(l.player, d));
                    if (heroes.isEmpty()) {
                        done.run();
                        return;
                    }
                    g.pick(l.player, "Special Summon which \"Elemental HERO\"?", heroes, false, "pick", d -> {
                        g.summonFrom(l.player, d, false);
                        done.run();
                    }, null);
                })));

        def("Hero Signal", () -> new Script().add(Fx.activate("Special Summon 1 Level 4 or lower \"Elemental HERO\" from your hand or Deck").cats("ss_deck")
                .when((g, c, x) -> x != null && x.kind.equals("battle_destroyed") && g.chain.isEmpty() && g.lastBattleDestroyed != null
                        && g.lastBattleDestroyed.player == c.controller && g.lastBattleDestroyed.card.zone == Zone.GY
                        && g.freeMonsterZone(c.controller) && !signalTargets(g, c.controller).isEmpty())
                .then((g, c, l, done) -> {
                    List<Card> heroes = signalTargets(g, l.player);
                    if (heroes.isEmpty() || !g.freeMonsterZone(l.player)) {
                        done.run();
                        return;
                    }
                    g.pick(l.player, "Special Summon which \"Elemental HERO\"?", heroes, false, "pick", d -> {
                        g.summonFrom(l.player, d, false);
                        done.run();
                    }, null);
                })));

        def("O - Oversoul", () -> new Script().add(Fx.activate("Special Summon 1 \"Elemental HERO\" Normal Monster from your GY")
                .when((g, c, x) -> !oversoulTargets(g, c.controllerOrOwner(), c).isEmpty())
                .cost((g, c, l, done) -> target(g, l, "Special Summon which \"Elemental HERO\"?", oversoulTargets(g, l.player, c), done))
                .then((g, c, l, done) -> {
                    if (l.target() != null && l.target().zone == Zone.GY) g.summonFrom(l.player, l.target(), false);
                    done.run();
                })));

        def("H - Heated Heart", () -> new Script().add(Fx.activate("A monster you control gains 500 ATK and piercing damage this turn")
                .when((g, c, x) -> !targetable(g, c.controllerOrOwner(), c, g.faceUpMonsters(c.controllerOrOwner())).isEmpty())
                .cost((g, c, l, done) -> target(g, l, "Choose a face-up monster you control", targetable(g, l.player, c, g.faceUpMonsters(l.player)), done))
                .then((g, c, l, done) -> {
                    Card t = l.target();
                    if (onField(t) && t.faceUp) {
                        g.boost(t, 500, 0, true);
                        g.flag(t, "pierce");
                        g.note(g.name(t) + " gains 500 ATK and inflicts piercing damage this turn.");
                    }
                    done.run();
                })));

        def("HERO's Bond", () -> new Script().add(Fx.activate("Special Summon 2 Level 4 or lower \"Elemental HERO\" monsters from your hand")
                .when((g, c, x) -> g.canSummonSeveral() && heroOnField(g) && g.pl(c.controllerOrOwner()).monsters.size() <= YgoGame.ZONES - 2
                        && g.where(g.pl(c.controllerOrOwner()).hand, h -> smallEHero(g, h) && g.canSpecial(c.controllerOrOwner(), h)).size() >= 2)
                .then((g, c, l, done) -> {
                    List<Card> heroes = g.where(g.pl(l.player).hand, h -> smallEHero(g, h) && g.canSpecial(l.player, h));
                    if (heroes.size() < 2 || !g.canSummonSeveral() || g.pl(l.player).monsters.size() > YgoGame.ZONES - 2 || !heroOnField(g)) {
                        done.run();
                        return;
                    }
                    g.pick(l.player, "Special Summon which \"Elemental HERO\" (1 of 2)?", heroes, false, "pick", a -> {
                        List<Card> rest = new ArrayList<>(heroes);
                        rest.remove(a);
                        g.pick(l.player, "Special Summon which \"Elemental HERO\" (2 of 2)?", rest, false, "pick", b -> {
                            g.summonFrom(l.player, a, false);
                            g.summonFrom(l.player, b, false);
                            done.run();
                        }, null);
                    }, null);
                })));

        def("Fusion Recovery", () -> new Script().add(Fx.activate("Add \"Polymerization\" and a Fusion Material monster from your GY to your hand")
                .when((g, c, x) -> !recoveryPoly(g, c.controllerOrOwner(), c).isEmpty() && !recoveryMaterial(g, c.controllerOrOwner(), c).isEmpty())
                .cost((g, c, l, done) -> g.pick(l.player, "Choose \"Polymerization\" in your GY", recoveryPoly(g, l.player, c), false, "pick", poly ->
                        g.pick(l.player, "Choose a monster that was used as Fusion Material", recoveryMaterial(g, l.player, c), false, "pick", mat -> {
                            l.data.put("poly", poly);
                            l.data.put("mat", mat);
                            done.run();
                        }, null), null))
                .then((g, c, l, done) -> {
                    for (String k : List.of("poly", "mat")) {
                        Card t = (Card) l.data.get(k);
                        if (t != null && t.zone == Zone.GY) g.toHand(t);
                    }
                    done.run();
                })));

        def("Skyscraper", () -> new Script().add(Fx.activate("")));

        def("Hero Barrier", () -> new Script().add(YgoScripts.attackTrap("Negate the attack", (g, c, l) -> {
            if (g.controls(l.player, m -> eHero(g, m))) {
                g.attackNegated = true;
                g.note("Hero Barrier negates the attack.");
            }
        }).when((g, c, x) -> x != null && x.kind.equals("attack") && g.chain.isEmpty() && onField(g.attacker)
                && g.attacker.controller != c.controller && g.controls(c.controller, m -> eHero(g, m)))));

        // ---------------------------------------------------------------- Fusion Monsters

        def("Elemental HERO Flame Wingman", () -> {
            Script s = fusion(printed("Elemental HERO Avian"), printed("Elemental HERO Burstinatrix"));
            s.add(burnOnKill("Inflict damage equal to the destroyed monster's ATK"));
            return s;
        });

        def("Elemental HERO Thunder Giant", () -> {
            Script s = fusion(printed("Elemental HERO Sparkman"), printed("Elemental HERO Clayman"));
            s.add(Fx.ignition("Discard 1 card: destroy 1 monster with original ATK lower than this card's ATK").once("thunder_giant")
                    .when((g, c, x) -> !g.pl(c.controller).hand.isEmpty() && !giantTargets(g, c).isEmpty())
                    .cost((g, c, l, done) -> g.discardOne(l.player, h -> true, "Discard a card", h ->
                            target(g, l, "Destroy which monster?", giantTargets(g, c), done)))
                    .then((g, c, l, done) -> {
                        if (onField(l.target())) g.destroy(l.target(), "effect", l.player, c);
                        done.run();
                    }));
            return s;
        });

        def("Elemental HERO Rampart Blaster", () -> {
            Script s = fusion(printed("Elemental HERO Clayman"), printed("Elemental HERO Burstinatrix"));
            s.defenseDirect = true;
            return s;
        });

        def("Elemental HERO Wild Wingman", () -> {
            Script s = fusion(printed("Elemental HERO Wildheart"), printed("Elemental HERO Avian"));
            s.add(Fx.ignition("Discard 1 card: destroy 1 Spell/Trap on the field")
                    .when((g, c, x) -> !g.pl(c.controller).hand.isEmpty() && !YgoScripts.stTargets(g, c.controller, c).isEmpty())
                    .cost((g, c, l, done) -> g.discardOne(l.player, h -> true, "Discard a card", h ->
                            target(g, l, "Destroy which Spell/Trap?", YgoScripts.stTargets(g, l.player, c), done)))
                    .then((g, c, l, done) -> {
                        if (onField(l.target())) g.destroy(l.target(), "effect", l.player, c);
                        done.run();
                    }));
            return s;
        });

        def("Elemental HERO Steam Healer", () -> {
            Script s = fusion(printed("Elemental HERO Burstinatrix"), printed("Elemental HERO Bubbleman"));
            s.add(Fx.trigger("Gain LP equal to the destroyed monster's original ATK", Fx.Ev.BATTLE_KILL).mandatory()
                    .when((g, c, x) -> x.card == c && x.other.zone == Zone.GY)
                    .then((g, c, l, done) -> {
                        if (l.ctx.other.zone == Zone.GY) g.gain(l.player, l.ctx.other.def.atk);
                        done.run();
                    }));
            return s;
        });

        def("Elemental HERO Shining Flare Wingman", () -> {
            Script s = fusion(printed("Elemental HERO Flame Wingman"), printed("Elemental HERO Sparkman"));
            s.atkBonus = (g, self) -> 300 * (int) g.pl(self.controller).gy.stream().filter(c -> eHero(g, c)).count();
            s.add(burnOnKill("Inflict damage equal to the destroyed monster's original ATK"));
            return s;
        });

        def("Elemental HERO Tempest", () -> {
            Script s = fusion(printed("Elemental HERO Avian"), printed("Elemental HERO Sparkman"), printed("Elemental HERO Bubbleman"));
            s.add(Fx.ignition("Send 1 other card you control to the GY: a monster you control can't be destroyed by battle while this is face-up")
                    .when((g, c, x) -> !without(g.cardsOnField(c.controller), c).isEmpty())
                    .cost((g, c, l, done) -> g.pick(l.player, "Send which card you control to the GY?", without(g.cardsOnField(l.player), c), false, "pick",
                            t -> {
                                g.sendToGy(t, "cost");
                                target(g, l, "Which monster can't be destroyed by battle?", targetable(g, l.player, c, g.monsters(l.player)), done);
                            }, null))
                    .then((g, c, l, done) -> {
                        Card t = l.target();
                        if (onField(t)) {
                            t.flags.put("tempest", Integer.MAX_VALUE);
                            g.note(g.name(t) + " can't be destroyed by battle while Elemental HERO Tempest is face-up.");
                        }
                        done.run();
                    }));
            return s;
        });

        def("Elemental HERO Great Tornado", () -> {
            Script s = fusion(c -> c.def.isMonster() && c.def.name.contains("Elemental HERO"), c -> c.def.isMonster() && c.def.attribute.equals("WIND"));
            s.add(Fx.trigger("Halve the ATK and DEF of all face-up monsters your opponent controls", Fx.Ev.SUMMONED).mandatory()
                    .when((g, c, x) -> x.card == c && x.how.equals("fusion"))
                    .then((g, c, l, done) -> {
                        for (Card m : g.faceUpMonsters(g.opp(l.player))) {
                            if (g.affects(l.player, c, m)) {
                                g.boost(m, -g.atk(m) / 2, -g.def(m) / 2, false);
                            }
                        }
                        g.note("The ATK and DEF of " + g.name(g.opp(l.player)) + "'s face-up monsters are halved.");
                        done.run();
                    }));
            return s;
        });

        def("Elemental HERO Absolute Zero", () -> {
            Script s = fusion(c -> c.def.isMonster() && c.def.name.contains("HERO"), c -> c.def.isMonster() && c.def.attribute.equals("WATER"));
            s.atkBonus = (g, self) -> {
                int n = 0;
                for (Card m : g.allMonsters()) {
                    if (m != self && m.faceUp && g.attr(m, "WATER") && !m.def.name.equals("Elemental HERO Absolute Zero")) n++;
                }
                return 500 * n;
            };
            s.add(Fx.trigger("Destroy all monsters your opponent controls", Fx.Ev.LEFT_FIELD).from(Zone.GY, Zone.BANISHED, Zone.HAND, Zone.DECK, Zone.EXTRA)
                    .mandatory()
                    .when((g, c, x) -> x.card == c && x.fromZone == Zone.MZONE)
                    .then((g, c, l, done) -> {
                        for (Card m : List.copyOf(g.monsters(g.opp(l.player)))) g.destroy(m, "effect", l.player, c);
                        done.run();
                    }));
            return s;
        });
    }

    // ---------------------------------------------------------------- helpers

    @SafeVarargs
    private static Script fusion(Predicate<Card>... materials) {
        Script s = new Script();
        s.mustProperSummon = true;
        s.fusion = new ArrayList<>(List.of(materials));
        return s;
    }

    private static Fx burnOnKill(String label) {
        return Fx.trigger(label, Fx.Ev.BATTLE_KILL).mandatory()
                .when((g, c, x) -> x.card == c && x.other.zone == Zone.GY)
                .then((g, c, l, done) -> {
                    if (l.ctx.other.zone == Zone.GY) g.damage(g.opp(l.player), l.ctx.other.def.atk, g.name(c));
                    done.run();
                });
    }

    private static boolean changeSpell(Card d) {
        return d.def.kind == YgoCard.Kind.SPELL && d.def.stype.equals("quick") && d.def.name.contains("Change");
    }

    private static boolean smallEHero(YgoGame g, Card c) {
        return g.isMonster(c) && eHero(g, c) && g.level(c) <= 4;
    }

    private static boolean heroOnField(YgoGame g) {
        for (Card m : g.allMonsters()) {
            if (m.faceUp && hero(g, m)) {
                return true;
            }
        }
        return false;
    }

    private static int stratosCount(YgoGame g, Card self, int who) {
        int n = 0;
        for (Card m : g.monsters(who)) {
            if (m != self && m.faceUp && hero(g, m)) n++;
        }
        return n;
    }

    private static boolean canStratosDestroy(YgoGame g, Card self) {
        return stratosCount(g, self, self.controller) > 0 && !allSpellsTraps(g, self.controller, self).isEmpty();
    }

    private static List<Card> allSpellsTraps(YgoGame g, int who, Card source) {
        List<Card> out = new ArrayList<>(g.spellsTraps(0));
        out.addAll(g.spellsTraps(1));
        return out;
    }

    private static boolean bubbleAlone(YgoGame g, Card self) {
        int who = self.controller;
        return g.pl(who).hand.isEmpty() && g.cardsOnField(who).size() == 1 && g.cardsOnField(who).contains(self);
    }

    private static List<Card> miraclePool(YgoGame g, int who) {
        List<Card> pool = new ArrayList<>(g.monsters(who));
        pool.addAll(g.where(g.pl(who).gy, g::isMonster));
        return pool;
    }

    private static List<Card> signalTargets(YgoGame g, int who) {
        List<Card> out = new ArrayList<>(g.where(g.pl(who).hand, h -> smallEHero(g, h) && g.canSpecial(who, h)));
        out.addAll(g.deckWhere(who, d -> smallEHero(g, d) && g.canSpecial(who, d)));
        return out;
    }

    private static List<Card> oversoulTargets(YgoGame g, int who, Card source) {
        return targetable(g, who, source, revivable(g, who, g.pl(who).gy, c -> eHero(g, c) && g.isNormalMonster(c)));
    }

    private static List<Card> recoveryPoly(YgoGame g, int who, Card source) {
        return targetable(g, who, source, g.where(g.pl(who).gy, c -> c.def.name.equals("Polymerization")));
    }

    private static List<Card> recoveryMaterial(YgoGame g, int who, Card source) {
        return targetable(g, who, source, g.where(g.pl(who).gy, c -> g.isMonster(c) && c.fusionMaterial));
    }

    private static List<Card> giantTargets(YgoGame g, Card self) {
        int a = g.atk(self);
        return targetable(g, self.controller, self, g.where(g.allMonsters(), m -> m.faceUp && m.def.atk < a));
    }
}
