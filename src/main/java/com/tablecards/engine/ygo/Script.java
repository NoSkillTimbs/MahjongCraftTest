package com.tablecards.engine.ygo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Everything special about one card: its effects, summoning rules and lasting abilities. */
public final class Script {
    /** A Special Summon procedure that isn't an effect (it doesn't start a chain). */
    public static final class Proc {
        final String label;
        final YgoGame.Zone from;
        final Fx.Cond cond;
        final Fx.Step perform;

        Proc(String label, YgoGame.Zone from, Fx.Cond cond, Fx.Step perform) {
            this.label = label;
            this.from = from;
            this.cond = cond;
            this.perform = perform;
        }
    }

    public interface Bonus {
        int of(YgoGame g, YgoGame.Card self);
    }

    public final List<Fx> effects = new ArrayList<>();
    public final List<Proc> procs = new ArrayList<>();
    /** Name while on the field or in the GY ("Blue-Eyes Alternative White Dragon" is "Blue-Eyes White Dragon"). */
    String alias;
    /** Also counts as this archetype ("Dragon Spirit of White" is always a "Blue-Eyes" card). */
    String alsoArchetype;
    boolean normalInHandGy;
    boolean tuner;
    boolean noNormalSummon;
    /** A Ritual Monster (also when the card data doesn't say so). */
    boolean ritual;
    /** "Must be Fusion/Ritual Summoned": can only come back by other effects after being properly summoned once. */
    boolean mustProperSummon;
    /** Fusion materials, one predicate per material. */
    List<Predicate<YgoGame.Card>> fusion;
    /** Synchro materials: what the non-Tuners must be. */
    Predicate<YgoGame.Card> synchroNonTuner;
    Bonus atkBonus;
    boolean cannotBeDestroyedByBattle;
    boolean unaffectedByTraps;
    /** Your opponent can't target it / destroy it with card effects (Blue-Eyes Chaos MAX Dragon). */
    boolean oppCantTarget;
    boolean oppCantDestroy;
    /** Can attack monsters twice per Battle Phase (Blue-Eyes Twin Burst Dragon). */
    int monsterAttacks = 1;
    /** Can attack directly from Defense Position at half ATK (Elemental HERO Rampart Blaster). */
    boolean defenseDirect;
    /** Doubles its ATK when battling a DARK monster (Palladium Oracle Mahad). */
    boolean doubleVsDark;
    /** Piercing damage multiplier when attacking a Defense Position monster (2 = double piercing). */
    int piercing;

    Script add(Fx fx) {
        effects.add(fx);
        return this;
    }

    Script proc(String label, YgoGame.Zone from, Fx.Cond cond, Fx.Step perform) {
        procs.add(new Proc(label, from, cond, perform));
        return this;
    }
}
