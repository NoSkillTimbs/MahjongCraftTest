package com.tablecards.engine.ygo;

import java.util.EnumSet;
import java.util.Set;

/**
 * One effect of a card, as the engine runs it.
 *
 * <ul>
 *   <li>ACTIVATE: activating a Spell/Trap Card (from the hand or set on the field).</li>
 *   <li>IGNITION: a monster (or other card) effect used in your Main Phase with an empty chain.</li>
 *   <li>QUICK: usable whenever you get the chance to respond (Spell Speed 2), in either turn.</li>
 *   <li>TRIGGER: activates when its event happens ({@link #on}), once the current chain ends.</li>
 * </ul>
 * {@link #cost} runs when the effect is activated (pay costs, choose targets, remember them in the
 * link); {@link #resolve} runs when its chain link resolves. Both must call {@code done} exactly
 * once when they finish (possibly after asking the player something).
 */
public final class Fx {
    public enum Type { ACTIVATE, IGNITION, QUICK, TRIGGER }

    /** Things that happen in a duel, for trigger effects. */
    public enum Ev { SUMMONED, SENT_TO_GY, DESTROYED, BATTLE_KILL, BATTLE_SURVIVED, LEFT_FIELD, DRAWN, END_PHASE, STANDBY, ACTIVATED }

    /** Whether the effect can be used now (also checked again when a trigger is about to activate). */
    public interface Cond {
        boolean test(YgoGame g, YgoGame.Card self, YgoGame.Ctx ctx);
    }

    public interface Step {
        void run(YgoGame g, YgoGame.Card self, YgoGame.Link link, Runnable done);
    }

    public final Type type;
    public final String label;
    /** Where the card must be for IGNITION/QUICK/TRIGGER effects. */
    EnumSet<YgoGame.Zone> from = EnumSet.of(YgoGame.Zone.MZONE);
    /** Once-per-turn key (per player), or null. */
    String once;
    Ev on;
    boolean mandatory;
    Cond cond = (g, c, x) -> true;
    Step cost = (g, c, l, d) -> d.run();
    Step resolve = (g, c, l, d) -> d.run();
    /** What the effect does, for cards that respond to kinds of effects (Ash Blossom): search, ss_deck, send_deck. */
    Set<String> cats = Set.of();
    /** 0: the default for its type. */
    int speed;
    /** Only usable during your own turn / opponent's turn. */
    boolean ownTurnOnly;
    boolean oppTurnOnly;

    private Fx(Type type, String label) {
        this.type = type;
        this.label = label;
    }

    public static Fx activate(String label) {
        return new Fx(Type.ACTIVATE, label);
    }

    public static Fx ignition(String label) {
        return new Fx(Type.IGNITION, label);
    }

    public static Fx quick(String label) {
        return new Fx(Type.QUICK, label);
    }

    public static Fx trigger(String label, Ev on) {
        Fx f = new Fx(Type.TRIGGER, label);
        f.on = on;
        return f;
    }

    public Fx from(YgoGame.Zone... zones) {
        from = EnumSet.copyOf(java.util.List.of(zones));
        return this;
    }

    public Fx once(String key) {
        once = key;
        return this;
    }

    public Fx mandatory() {
        mandatory = true;
        return this;
    }

    public Fx when(Cond c) {
        cond = c;
        return this;
    }

    public Fx cost(Step s) {
        cost = s;
        return this;
    }

    public Fx then(Step s) {
        resolve = s;
        return this;
    }

    public Fx cats(String... c) {
        cats = Set.of(c);
        return this;
    }

    public Fx speed(int s) {
        speed = s;
        return this;
    }

    public Fx ownTurn() {
        ownTurnOnly = true;
        return this;
    }

    public Fx oppTurn() {
        oppTurnOnly = true;
        return this;
    }
}
