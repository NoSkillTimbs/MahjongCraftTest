package com.tablecards.engine.ygo;

import com.tablecards.engine.BaseGame;
import com.tablecards.engine.Board;
import com.tablecards.engine.Bot;
import com.tablecards.engine.Decision;
import com.tablecards.engine.Move;
import com.tablecards.engine.Option;
import com.tablecards.engine.Section;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Yu-Gi-Oh! for two players, with card effects.
 *
 * Turn: Draw (not on the very first turn), Standby, Main Phase 1, Battle Phase (not on the very
 * first turn), Main Phase 2, End Phase (hand limit 6). One Normal Summon or Set per turn (Level
 * 5-6 need 1 Tribute, 7+ need 2). Special Summons: by card effects, by a card's own procedure, and
 * Fusion, Ritual and Synchro Summons. Effects follow the chain rules: when a card or effect is
 * activated, the other player may respond with a faster one (Quick Effects, Quick-Play Spells,
 * Traps), and the chain resolves last-in-first-out; trigger effects activate once the chain has
 * resolved (the turn player's first). Each effect is written for its card in {@link YgoScripts}.
 * 8000 LP; you lose at 0 LP or when you must draw from an empty Deck.
 */
public final class YgoGame extends BaseGame {
    public static final int START_LP = 8000;
    static final int ZONES = 5;
    static final int HAND_LIMIT = 6;
    static final int EXTRA_MAX = 15;

    enum Phase { MAIN1, BATTLE, MAIN2 }

    public enum Zone { DECK, HAND, MZONE, SZONE, FZONE, GY, BANISHED, EXTRA }

    /** A physical card. It moves between zones; {@code owner} is whose Deck it came from. */
    public static final class Card {
        final YgoCard def;
        final int owner;
        final int uid;
        final Script script;
        Zone zone = Zone.DECK;
        int controller;
        boolean faceUp;
        boolean attackPos;
        int arrivedTurn = -1;
        int attacksMade;
        boolean posChanged;
        int setTurn = -1;
        /** How it got onto the field: normal, set, flip, special, fusion, ritual, synchro. */
        String summon = "";
        /** Has been Fusion/Ritual/Synchro Summoned properly at least once (may then be revived). */
        boolean properOnce;
        Card equipTarget;
        int appliedAtk;
        int appliedDef;
        int sentToGyTurn = -1;
        boolean fusionMaterial;
        /** Effects negated until the end of this turn number. */
        int negatedTurn = -1;
        /** Temporary or lasting stat changes: {atk, def, last turn they apply}. */
        final List<int[]> boosts = new ArrayList<>();
        /** Per-turn markers (name -> turn number). */
        final Map<String, Integer> flags = new HashMap<>();

        Card(YgoCard def, int owner, int uid) {
            this.def = def;
            this.owner = owner;
            this.controller = owner;
            this.uid = uid;
            this.script = YgoScripts.of(def);
        }

        boolean onField() {
            return zone == Zone.MZONE || zone == Zone.SZONE || zone == Zone.FZONE;
        }

        int controllerOrOwner() {
            return onField() ? controller : owner;
        }
    }

    /** What just happened, for responses and triggers. */
    public static final class Ctx {
        final String kind;
        Card card;
        Card other;
        int player = -1;
        String how = "";
        Zone fromZone;
        /** The chain link being responded to. */
        Link link;
        int amount;

        Ctx(String kind) {
            this.kind = kind;
        }
    }

    /** One link of a chain. */
    public static final class Link {
        final Fx fx;
        final Card card;
        final int player;
        final Ctx ctx;
        final Map<String, Object> data = new HashMap<>();
        boolean negated;
        boolean cardActivation;
        boolean fromGy;
        int speed;

        Link(Fx fx, Card card, int player, Ctx ctx) {
            this.fx = fx;
            this.card = card;
            this.player = player;
            this.ctx = ctx;
        }

        Card target() {
            return (Card) data.get("target");
        }
    }

    private record Pending(Card card, Fx fx, Ctx ctx, int player) {
    }

    static final class Player {
        int lp = START_LP;
        final List<Card> deck = new ArrayList<>();
        final List<Card> hand = new ArrayList<>();
        final List<Card> gy = new ArrayList<>();
        final List<Card> banished = new ArrayList<>();
        final List<Card> extra = new ArrayList<>();
        final List<Card> monsters = new ArrayList<>();
        final List<Card> st = new ArrayList<>();
        Card field;
        /** Once-per-turn keys used this turn. */
        final Set<String> once = new HashSet<>();
        int noBattleDamageTurn = -1;
        /** Hero Barrier: the next attack this turn is negated. */
        int barrierTurn = -1;
    }

    final Player[] p = {new Player(), new Player()};
    int turn = 1;
    int tp;
    Phase phase = Phase.MAIN1;
    boolean normalSummoned;
    private int nextUid;
    final List<Link> chain = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    /** The last Normal/Flip Summon, while players may still respond to it. */
    Ctx lastSummon;
    /** The attack being made. */
    Card attacker;
    Card attackTarget;
    boolean attackNegated;
    /** Extra ATK applied during this damage calculation only. */
    final Map<Card, Integer> calcBonus = new HashMap<>();
    /** How often each card's effects were activated this turn (card uid -> count). */
    final Map<Integer, Integer> uses = new HashMap<>();
    /** Monsters that are destroyed in the End Phase (Blue-Eyes Spirit Dragon). */
    final List<Card> destroyAtEnd = new ArrayList<>();

    public YgoGame(String[] names, List<YgoCard> deck0, List<YgoCard> deck1, long seed) {
        super(names, seed);
        List<List<YgoCard>> decks = List.of(deck0, deck1);
        for (int i = 0; i < 2; i++) {
            for (YgoCard c : decks.get(i)) {
                Card card = new Card(c, i, nextUid++);
                if (c.isExtra()) {
                    card.zone = Zone.EXTRA;
                    p[i].extra.add(card);
                } else {
                    p[i].deck.add(card);
                }
            }
            Collections.shuffle(p[i].deck, rng);
            for (int k = 0; k < 5; k++) {
                drawCard(i);
            }
        }
        pending.clear(); // drawing the opening hand doesn't trigger "when you draw this card"
        tp = rng.nextInt(2);
        log(name(tp) + " goes first.");
        start();
    }

    @Override
    public String title() {
        return "Yu-Gi-Oh!";
    }

    @Override
    public Bot bot() {
        return new YgoBot();
    }

    int opp(int player) {
        return 1 - player;
    }

    /** Number of links in the chain being built (0 when none). */
    public int chainSize() {
        return chain.size();
    }

    Player pl(int who) {
        return p[who];
    }

    // =================================================================== card facts

    /** The card's current name ("Blue-Eyes Alternative White Dragon" is "Blue-Eyes White Dragon" on the field and in the GY). */
    String name(Card c) {
        if (c == null) {
            return "?";
        }
        if (c.script.alias != null && (c.onField() || c.zone == Zone.GY)) {
            return c.script.alias;
        }
        return c.def.name;
    }

    boolean named(Card c, String n) {
        return name(c).equals(n);
    }

    boolean isArch(Card c, String arch) {
        return name(c).contains(arch) || c.def.name.contains(arch) || arch.equals(c.script.alsoArchetype);
    }

    boolean isMonster(Card c) {
        return c.def.isMonster();
    }

    boolean isSpell(Card c) {
        return c.def.kind == YgoCard.Kind.SPELL;
    }

    boolean isTrap(Card c) {
        return c.def.kind == YgoCard.Kind.TRAP;
    }

    boolean isNormalMonster(Card c) {
        if (!isMonster(c)) {
            return false;
        }
        if (c.script.normalInHandGy && (c.zone == Zone.HAND || c.zone == Zone.GY)) {
            return true;
        }
        return c.def.isNormalMonster();
    }

    boolean isEffectMonster(Card c) {
        return isMonster(c) && !isNormalMonster(c);
    }

    boolean isTuner(Card c) {
        return c.def.tuner || c.script.tuner;
    }

    boolean race(Card c, String race) {
        return isMonster(c) && c.def.race.equalsIgnoreCase(race);
    }

    boolean attr(Card c, String attribute) {
        return isMonster(c) && c.def.attribute.equalsIgnoreCase(attribute);
    }

    int level(Card c) {
        return c.def.level;
    }

    boolean effectsNegated(Card c) {
        return c.negatedTurn >= turn;
    }

    int atk(Card c) {
        if (!isMonster(c)) {
            return 0;
        }
        int v = c.def.atk;
        for (int[] b : c.boosts) {
            if (b[2] >= turn) {
                v += b[0];
            }
        }
        v += c.appliedAtk;
        if (c.script.atkBonus != null && c.zone == Zone.MZONE && c.faceUp && !effectsNegated(c)) {
            v += c.script.atkBonus.of(this, c);
        }
        return Math.max(0, v);
    }

    /** ATK as the bot sees it (face-down cards' real values are hidden from it elsewhere). */
    int atkOf(Card c) {
        return c == null ? 0 : atk(c);
    }

    int def(Card c) {
        if (!isMonster(c)) {
            return 0;
        }
        int v = c.def.def;
        for (int[] b : c.boosts) {
            if (b[2] >= turn) {
                v += b[1];
            }
        }
        v += c.appliedDef;
        return Math.max(0, v);
    }

    void boost(Card c, int atk, int def, boolean untilEndOfTurn) {
        c.boosts.add(new int[]{atk, def, untilEndOfTurn ? turn : Integer.MAX_VALUE});
    }

    void flag(Card c, String f) {
        c.flags.put(f, turn);
    }

    boolean flagged(Card c, String f) {
        return Integer.valueOf(turn).equals(c.flags.get(f));
    }

    List<Card> monsters(int who) {
        return p[who].monsters;
    }

    List<Card> faceUpMonsters(int who) {
        List<Card> out = new ArrayList<>();
        for (Card c : p[who].monsters) {
            if (c.faceUp) {
                out.add(c);
            }
        }
        return out;
    }

    List<Card> allMonsters() {
        List<Card> out = new ArrayList<>(p[tp].monsters);
        out.addAll(p[opp(tp)].monsters);
        return out;
    }

    /** Spells/Traps on a player's field, including the Field Spell. */
    List<Card> spellsTraps(int who) {
        List<Card> out = new ArrayList<>(p[who].st);
        if (p[who].field != null) {
            out.add(p[who].field);
        }
        return out;
    }

    List<Card> cardsOnField(int who) {
        List<Card> out = new ArrayList<>(p[who].monsters);
        out.addAll(spellsTraps(who));
        return out;
    }

    boolean controls(int who, Predicate<Card> pred) {
        for (Card c : p[who].monsters) {
            if (c.faceUp && pred.test(c)) {
                return true;
            }
        }
        return false;
    }

    boolean freeMonsterZone(int who) {
        return p[who].monsters.size() < ZONES;
    }

    boolean freeSpellZone(int who) {
        return p[who].st.size() < ZONES;
    }

    /** A Blue-Eyes Spirit Dragon on the field stops summoning 2+ monsters at once. */
    boolean canSummonSeveral() {
        for (Card c : allMonsters()) {
            if (c.faceUp && named(c, "Blue-Eyes Spirit Dragon") && !effectsNegated(c)) {
                return false;
            }
        }
        return true;
    }

    // =================================================================== protection

    /** Whether an effect of {@code source} (activated by {@code player}) affects {@code target}. */
    boolean affects(int player, Card source, Card target) {
        if (target.zone == Zone.MZONE && target.faceUp && !effectsNegated(target)) {
            if (target.script.unaffectedByTraps && source != null && isTrap(source)) {
                return false;
            }
            if (player != target.controller && named(target, "Dark Magician") && hasFaceUp(target.controller, "Eternal Soul")) {
                return false;
            }
        }
        return true;
    }

    boolean canTarget(int player, Card source, Card target) {
        if (!affects(player, source, target)) {
            return false;
        }
        if (player == target.controller) {
            return true;
        }
        if (target.zone == Zone.MZONE && target.faceUp && !effectsNegated(target) && target.script.oppCantTarget) {
            return false;
        }
        if (Integer.valueOf(turn).compareTo(target.flags.getOrDefault("azure", -9)) <= 0 && race(target, "Dragon") && target.zone == Zone.MZONE) {
            return false;
        }
        if ((target.zone == Zone.SZONE || target.zone == Zone.FZONE) && dragonKnightProtects(target.controller)) {
            return false;
        }
        return true;
    }

    private boolean dragonKnightProtects(int who) {
        for (Card m : p[who].monsters) {
            if (m.faceUp && m.def.name.equals("Dark Magician the Dragon Knight") && !effectsNegated(m)) {
                return true;
            }
        }
        return false;
    }

    boolean hasFaceUp(int who, String cardName) {
        for (Card c : cardsOnField(who)) {
            if (c.faceUp && named(c, cardName)) {
                return true;
            }
        }
        return false;
    }

    /** Whether an effect activated by {@code player} can destroy {@code target}. */
    boolean canDestroyByEffect(int player, Card source, Card target) {
        if (!affects(player, source, target)) {
            return false;
        }
        if (player != target.controller) {
            if (target.zone == Zone.MZONE && target.faceUp && !effectsNegated(target) && target.script.oppCantDestroy) {
                return false;
            }
            if ((target.zone == Zone.SZONE || target.zone == Zone.FZONE) && dragonKnightProtects(target.controller)) {
                return false;
            }
        }
        if (target.zone == Zone.MZONE && race(target, "Dragon") && Integer.valueOf(turn).compareTo(target.flags.getOrDefault("azure", -9)) <= 0) {
            return false;
        }
        return true;
    }

    // =================================================================== moving cards

    private List<Card> listOf(Zone z, int who) {
        Player pl = p[who];
        return switch (z) {
            case DECK -> pl.deck;
            case HAND -> pl.hand;
            case GY -> pl.gy;
            case BANISHED -> pl.banished;
            case EXTRA -> pl.extra;
            case MZONE -> pl.monsters;
            case SZONE -> pl.st;
            case FZONE -> null;
        };
    }

    private void detach(Card c) {
        if (c.zone == Zone.FZONE) {
            if (p[c.controller].field == c) {
                p[c.controller].field = null;
            }
        } else {
            List<Card> l = listOf(c.zone, c.zone == Zone.MZONE || c.zone == Zone.SZONE ? c.controller : c.owner);
            l.remove(c);
        }
    }

    /**
     * Moves a card. Leaving the field resets it and takes its equips with it; Extra Deck monsters
     * that would go to the hand or Deck go back to the Extra Deck. Fires the events.
     */
    void move(Card c, Zone to, String why) {
        Zone from = c.zone;
        boolean wasOnField = c.onField();
        boolean wasFaceUp = c.faceUp;
        int controller = c.controller;
        detach(c);
        if (c.def.isExtra() && (to == Zone.HAND || to == Zone.DECK)) {
            to = Zone.EXTRA;
        }
        if (wasOnField) {
            resetOnLeave(c);
        }
        c.zone = to;
        c.controller = (to == Zone.MZONE || to == Zone.SZONE || to == Zone.FZONE) ? c.controller : c.owner;
        if (to == Zone.FZONE) {
            p[c.controller].field = c;
        } else {
            List<Card> l = listOf(to, (to == Zone.MZONE || to == Zone.SZONE) ? c.controller : c.owner);
            l.add(c);
        }
        if (to == Zone.GY || to == Zone.BANISHED || to == Zone.HAND || to == Zone.DECK || to == Zone.EXTRA) {
            c.faceUp = to != Zone.DECK && to != Zone.HAND && to != Zone.EXTRA;
        }
        if (to == Zone.GY) {
            c.sentToGyTurn = turn;
            Ctx x = new Ctx("gy");
            x.card = c;
            x.fromZone = from;
            x.how = why;
            x.player = controller;
            fire(Fx.Ev.SENT_TO_GY, x);
        }
        if (wasOnField && !(to == Zone.MZONE || to == Zone.SZONE || to == Zone.FZONE)) {
            Ctx x = new Ctx("left");
            x.card = c;
            x.how = why;
            x.player = controller;
            x.fromZone = from;
            fire(Fx.Ev.LEFT_FIELD, x);
            leftField(c, controller, wasFaceUp, from);
        }
    }

    private void resetOnLeave(Card c) {
        c.faceUp = false;
        c.attackPos = false;
        c.attacksMade = 0;
        c.posChanged = false;
        c.boosts.clear();
        c.appliedAtk = 0;
        c.appliedDef = 0;
        c.negatedTurn = -1;
        c.flags.clear();
        c.summon = "";
        Card target = c.equipTarget;
        c.equipTarget = null;
        if (target != null) {
            target.appliedAtk -= 0; // bonuses are recomputed below
        }
        destroyAtEnd.remove(c);
    }

    /** Things that happen when a card leaves the field. */
    private void leftField(Card c, int controller, boolean wasFaceUp, Zone from) {
        // equips on a monster that left go to the GY; an equip that left stops its bonus
        for (int i = 0; i < 2; i++) {
            for (Card s : List.copyOf(p[i].st)) {
                if (s.equipTarget == c) {
                    s.equipTarget = null;
                    move(s, Zone.GY, "equip");
                }
            }
        }
        recomputeEquips();
        if (wasFaceUp && c.def.name.equals("Eternal Soul") && from == Zone.SZONE) {
            log("Eternal Soul left the field: all monsters " + name(controller) + " controls are destroyed.");
            for (Card m : List.copyOf(p[controller].monsters)) {
                destroy(m, "effect", controller, c);
            }
        }
    }

    /** Equip spell bonuses (the imported simple equips) are applied from scratch. */
    void recomputeEquips() {
        for (Card m : allMonsters()) {
            m.appliedAtk = 0;
            m.appliedDef = 0;
        }
        for (int i = 0; i < 2; i++) {
            for (Card s : p[i].st) {
                Card t = s.equipTarget;
                if (t != null && t.zone == Zone.MZONE && s.def.effect.equals("equip")) {
                    boolean bonus = (s.def.equipRace == null && s.def.equipAttr == null) || s.def.equipMatches(t.def);
                    if (bonus) {
                        t.appliedAtk += s.def.equipAtk;
                        t.appliedDef += s.def.equipDef;
                    }
                }
            }
        }
    }

    boolean drawCard(int who) {
        Player pl = p[who];
        if (pl.deck.isEmpty()) {
            win(opp(who), name(who) + " has no cards left to draw. " + name(opp(who)) + " wins!");
            return false;
        }
        Card c = pl.deck.get(pl.deck.size() - 1);
        move(c, Zone.HAND, "draw");
        Ctx x = new Ctx("draw");
        x.card = c;
        x.player = who;
        fire(Fx.Ev.DRAWN, x);
        return true;
    }

    void draw(int who, int n) {
        for (int i = 0; i < n && !isOver(); i++) {
            drawCard(who);
        }
    }

    void shuffleDeck(int who) {
        Collections.shuffle(p[who].deck, rng);
    }

    /** Adds a card from the Deck or GY to its owner's hand. */
    void toHand(Card c) {
        boolean fromDeck = c.zone == Zone.DECK;
        move(c, Zone.HAND, "add");
        log(name(c.owner) + " adds " + name(c) + " to their hand" + (fromDeck ? " from the Deck." : "."));
        if (fromDeck) {
            shuffleDeck(c.owner);
        }
    }

    void sendToGy(Card c, String why) {
        move(c, Zone.GY, why);
    }

    void banish(Card c, String why) {
        move(c, Zone.BANISHED, why);
    }

    /**
     * Destroys a card. Returns false if it couldn't be. "Return of the Dragon Lords" in the GY is
     * banished instead when a Dragon would be destroyed (players always take that option).
     */
    boolean destroy(Card c, String how, int byPlayer, Card source) {
        if (!c.onField()) {
            return false;
        }
        if (how.equals("effect") && !canDestroyByEffect(byPlayer, source, c)) {
            log(name(c) + " is not destroyed.");
            return false;
        }
        if (c.zone == Zone.MZONE && race(c, "Dragon") && c.faceUp) {
            for (Card g : p[c.controller].gy) {
                if (g.def.name.equals("Return of the Dragon Lords")) {
                    banish(g, "replace");
                    log(name(c.controller) + " banishes Return of the Dragon Lords instead of " + name(c) + " being destroyed.");
                    return false;
                }
            }
        }
        int controller = c.controller;
        Zone from = c.zone;
        log(name(c) + " is destroyed" + (how.equals("battle") ? " by battle." : "."));
        move(c, Zone.GY, how.equals("battle") ? "battle" : "destroy");
        Ctx x = new Ctx("destroyed");
        x.card = c;
        x.how = how;
        x.player = controller;
        x.fromZone = from;
        fire(Fx.Ev.DESTROYED, x);
        return true;
    }

    void damage(int who, int amount, String source) {
        if (amount <= 0 || isOver()) {
            return;
        }
        p[who].lp = Math.max(0, p[who].lp - amount);
        log(name(who) + " takes " + amount + " damage (" + source + "), LP " + p[who].lp + ".");
        if (p[who].lp == 0) {
            win(opp(who), name(who) + "'s LP reached 0. " + name(opp(who)) + " wins!");
        }
    }

    void gain(int who, int amount) {
        if (amount > 0) {
            p[who].lp += amount;
            log(name(who) + " gains " + amount + " LP, LP " + p[who].lp + ".");
        }
    }

    // =================================================================== summoning

    /** Whether {@code c} may be Special Summoned by an effect right now. */
    boolean canSpecial(int who, Card c) {
        if (!isMonster(c) || !freeMonsterZone(who)) {
            return false;
        }
        if (c.script.mustProperSummon && !c.properOnce) {
            return false;
        }
        return true;
    }

    /** Puts a monster onto the field by a Special Summon and fires the event. */
    void specialSummon(Card c, int who, String how, boolean defense) {
        if (!freeMonsterZone(who)) {
            log("There is no free Monster Zone for " + c.def.name + ".");
            return;
        }
        move(c, Zone.MZONE, "summon");
        c.controller = who;
        if (!p[who].monsters.contains(c)) {
            listOf(Zone.MZONE, c.owner).remove(c);
            p[who].monsters.add(c);
        }
        c.faceUp = true;
        c.attackPos = !defense;
        c.arrivedTurn = turn;
        c.summon = how;
        if (how.equals("fusion") || how.equals("ritual") || how.equals("synchro")) {
            c.properOnce = true;
        }
        reveal(faceView(c), c);
        log(name(who) + " Special Summons " + name(c) + (how.equals("special") ? "" : " (" + how + " Summon)") + ".");
        Ctx x = new Ctx("summon");
        x.card = c;
        x.player = who;
        x.how = how;
        fire(Fx.Ev.SUMMONED, x);
    }

    private void normalSummon(Card c, boolean set, Runnable then) {
        int need = c.def.tributesNeeded();
        chooseTributes(c, need, () -> {
            Player me = p[tp];
            move(c, Zone.MZONE, "summon");
            c.faceUp = !set;
            c.attackPos = !set;
            c.arrivedTurn = turn;
            c.summon = set ? "set" : "normal";
            normalSummoned = true;
            if (set) {
                log(name(tp) + " sets a monster.");
                then.run();
                return;
            }
            reveal(faceView(c), c);
            log(name(tp) + " Normal Summons " + name(c) + ".");
            Ctx x = new Ctx("summon");
            x.card = c;
            x.player = tp;
            x.how = "normal";
            fire(Fx.Ev.SUMMONED, x);
            afterSummon(x, then);
        });
    }

    private void chooseTributes(Card c, int remaining, Runnable then) {
        if (remaining == 0) {
            then.run();
            return;
        }
        pick(tp, "Choose a monster to tribute for " + c.def.name + " (" + remaining + " more)", p[tp].monsters, false, "tribute", m -> {
            log(name(tp) + " tributes " + name(m) + ".");
            sendToGy(m, "tribute");
            chooseTributes(c, remaining - 1, then);
        }, null);
    }

    private void flipSummon(Card c, Runnable then) {
        c.faceUp = true;
        c.attackPos = true;
        c.posChanged = true;
        c.summon = "flip";
        reveal(faceView(c), c);
        log(name(tp) + " Flip Summons " + name(c) + ".");
        Ctx x = new Ctx("summon");
        x.card = c;
        x.player = tp;
        x.how = "flip";
        fire(Fx.Ev.SUMMONED, x);
        afterSummon(x, then);
    }

    /** After a Normal/Flip Summon: triggers, and a chance to respond with summon traps. */
    private void afterSummon(Ctx summon, Runnable then) {
        lastSummon = summon;
        Runnable done = () -> {
            lastSummon = null;
            then.run();
        };
        if (!pending.isEmpty()) {
            processTriggers(done);
        } else {
            window(summon, done);
        }
    }

    // =================================================================== asking players

    /** Asks {@code who} to pick one of {@code cards}; {@code onCancel} null means they must pick. */
    void pick(int who, String prompt, List<Card> cards, boolean optional, String kind, Consumer<Card> then, Runnable onCancel) {
        List<Option> opts = new ArrayList<>();
        for (Card c : List.copyOf(cards)) {
            opts.add(new Option(pickLabel(who, c), () -> then.accept(c), Move.of(kind, c)));
        }
        if (optional || onCancel != null) {
            opts.add(new Option(onCancel != null ? "Cancel" : "None", () -> {
                if (onCancel != null) {
                    onCancel.run();
                }
            }, Move.of("none")));
        }
        if (opts.isEmpty()) {
            if (onCancel != null) {
                onCancel.run();
            }
            return;
        }
        ask(who, prompt, opts);
    }

    String pickLabel(int who, Card c) {
        if (c.onField() && !c.faceUp && c.controller != who) {
            return "Choose a face-down card";
        }
        String where = switch (c.zone) {
            case HAND -> "";
            case DECK -> " (Deck)";
            case GY -> " (GY)";
            case BANISHED -> " (banished)";
            case EXTRA -> " (Extra Deck)";
            default -> c.controller == who ? "" : " (opponent's)";
        };
        return "Choose " + c.def.summary() + where;
    }

    /** Like {@link #pick} with one more choice that isn't a card ("Done", "Don't"). */
    void pickOr(int who, String prompt, List<Card> cards, String kind, Consumer<Card> then, String otherLabel, Runnable other) {
        List<Option> opts = new ArrayList<>();
        for (Card c : List.copyOf(cards)) {
            opts.add(new Option(pickLabel(who, c), () -> then.accept(c), Move.of(kind, c)));
        }
        opts.add(new Option(otherLabel, other, Move.of("none")));
        ask(who, prompt, opts);
    }

    void note(String line) {
        log(line);
    }

    void endBattlePhase() {
        if (phase == Phase.BATTLE) {
            phase = Phase.MAIN2;
            log("The Battle Phase ends.");
        }
    }

    /** What a chain link's effect does (for Ash Blossom): its effect's categories plus any it set itself. */
    @SuppressWarnings("unchecked")
    static Set<String> catsOf(Link l) {
        Set<String> out = new HashSet<>(l.fx.cats);
        Object extra = l.data.get("cats");
        if (extra instanceof Set<?> set) {
            out.addAll((Set<String>) set);
        }
        return out;
    }

    void yesNo(int who, String prompt, Runnable yes, Runnable no) {
        ask(who, prompt, List.of(new Option("Yes", yes, Move.of("yes")), new Option("No", no, Move.of("no"))));
    }

    /** Asks {@code who} to choose one of several effects; labels and actions line up. */
    void chooseEffect(int who, String prompt, List<String> labels, List<Runnable> actions) {
        List<Option> opts = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            opts.add(new Option(labels.get(i), actions.get(i), Move.of("mode", null, null, i)));
        }
        ask(who, prompt, opts);
    }

    // =================================================================== turn structure

    void pendingClearForTest() {
        pending.clear();
    }

    /** Asks the turn player's menu again (tests change the state directly). */
    void reprompt() {
        advance();
    }

    @Override
    protected void advance() {
        if (phase == Phase.BATTLE) {
            battleMenu();
        } else {
            mainMenu();
        }
    }

    private void endTurn() {
        Player me = p[tp];
        if (me.hand.size() > HAND_LIMIT) {
            pick(tp, "Hand limit is " + HAND_LIMIT + ": discard a card", me.hand, false, "discard", c -> {
                sendToGy(c, "discard");
                log(name(tp) + " discards " + name(c) + " (hand limit).");
                endTurn();
            }, null);
            return;
        }
        // End Phase: "destroy it during the End Phase" and End Phase triggers
        for (Card c : List.copyOf(destroyAtEnd)) {
            destroyAtEnd.remove(c);
            if (c.zone == Zone.MZONE) {
                destroy(c, "rule", c.controller, c);
            }
        }
        Ctx x = new Ctx("end");
        x.player = tp;
        fire(Fx.Ev.END_PHASE, x);
        processTriggers(this::nextTurn);
    }

    private void nextTurn() {
        if (isOver()) {
            return;
        }
        tp = opp(tp);
        turn++;
        normalSummoned = false;
        phase = Phase.MAIN1;
        for (Player pl : p) {
            pl.once.clear();
        }
        uses.clear();
        for (Card m : p[tp].monsters) {
            m.attacksMade = 0;
            m.posChanged = false;
        }
        log("Turn " + turn + ": " + name(tp) + ".");
        drawCard(tp);
        if (isOver()) {
            return;
        }
        Ctx x = new Ctx("standby");
        x.player = tp;
        fire(Fx.Ev.STANDBY, x);
        processTriggers(() -> { });
    }

    // =================================================================== main phase

    private void mainMenu() {
        Player me = p[tp];
        List<Option> opts = new ArrayList<>();
        Runnable back = () -> { };

        for (Card c : distinct(me.hand)) {
            if (isMonster(c)) {
                int t = c.def.tributesNeeded();
                boolean canNormal = !normalSummoned && !c.script.noNormalSummon
                        && (c.def.frame.equals("normal") || c.def.frame.equals("effect"));
                if (canNormal && me.monsters.size() >= t && me.monsters.size() - t < ZONES) {
                    String tribute = t > 0 ? " (tribute " + t + ")" : "";
                    opts.add(new Option("Normal Summon " + c.def.summary() + tribute, () -> normalSummon(c, false, back), Move.of("summon", c, null, t)));
                    opts.add(new Option("Set " + c.def.summary() + tribute, () -> normalSummon(c, true, back), Move.of("set_monster", c, null, t)));
                }
            } else if (c.def.stype.equals("field") || freeSpellZone(tp)) {
                for (Fx fx : c.script.effects) {
                    if (fx.type == Fx.Type.ACTIVATE && canActivateCard(tp, c, fx, null)) {
                        opts.add(new Option("Activate " + c.def.name + (fx.label.isEmpty() ? "" : ": " + fx.label),
                                () -> activate(tp, c, fx, null, back), Move.of("activate", c)));
                    }
                }
                if (!c.def.stype.equals("field")) {
                    opts.add(new Option("Set " + c.def.summary(), () -> setSpellTrap(c), Move.of("set_st", c)));
                }
            }
        }
        // Special Summon procedures (from the hand, and the Extra Deck)
        List<Card> procCards = new ArrayList<>(distinct(me.hand));
        procCards.addAll(distinct(me.extra));
        for (Card c : procCards) {
            for (Script.Proc pr : c.script.procs) {
                if (pr.from == c.zone && freeOrWillFree(tp) && pr.cond.test(this, c, null)) {
                    opts.add(new Option(pr.label + ": " + c.def.name, () -> {
                        uses.merge(c.uid, 1, Integer::sum);
                        pr.perform.run(this, c, null, () -> afterProc(c, back));
                    }, Move.of("ss_proc", c)));
                }
            }
        }
        // Synchro Summons
        for (Card c : distinct(me.extra)) {
            if (c.def.frame.equals("synchro") && canSynchro(tp, c)) {
                opts.add(new Option("Synchro Summon " + c.def.summary(), () -> synchroSummon(tp, c, back), Move.of("synchro", c)));
            }
        }
        // effects of cards on the field, in the hand and in the GY
        for (Card c : effectSources(tp)) {
            for (Fx fx : c.script.effects) {
                if ((fx.type == Fx.Type.IGNITION || fx.type == Fx.Type.QUICK) && canUse(tp, c, fx, null, true)) {
                    opts.add(new Option("Use " + c.def.name + (fx.label.isEmpty() ? "" : ": " + fx.label) + where(c),
                            () -> activate(tp, c, fx, null, back), Move.of("effect", c)));
                }
            }
        }
        // set Spells and Traps
        for (Card s : List.copyOf(me.st)) {
            if (s.faceUp) {
                continue;
            }
            for (Fx fx : s.script.effects) {
                if (fx.type == Fx.Type.ACTIVATE && canActivateCard(tp, s, fx, null)) {
                    opts.add(new Option("Activate set " + s.def.name + (fx.label.isEmpty() ? "" : ": " + fx.label),
                            () -> activate(tp, s, fx, null, back), Move.of("activate", s)));
                }
            }
        }
        for (Card m : me.monsters) {
            if (m.arrivedTurn == turn || m.posChanged) {
                continue;
            }
            if (!m.faceUp) {
                opts.add(new Option("Flip Summon " + m.def.summary(), () -> flipSummon(m, back), Move.of("flip", m)));
            } else {
                String to = m.attackPos ? "Defense" : "Attack";
                opts.add(new Option("Change " + name(m) + " to " + to + " Position", () -> {
                    m.attackPos = !m.attackPos;
                    m.posChanged = true;
                    log(name(tp) + " changes " + name(m) + " to " + to + " Position.");
                }, Move.of("position", m)));
            }
        }
        if (phase == Phase.MAIN1 && turn > 1) {
            opts.add(new Option("Go to Battle Phase", () -> {
                phase = Phase.BATTLE;
                log(name(tp) + " enters the Battle Phase.");
                window(new Ctx("phase"), back);
            }, Move.of("battle")));
        }
        opts.add(new Option("End turn", () -> {
            log(name(tp) + " ends the turn.");
            window(new Ctx("phase"), this::endTurn);
        }, Move.of("end")));
        ask(tp, (phase == Phase.MAIN1 ? "Main Phase 1" : "Main Phase 2") + ": choose an action", opts);
    }

    private static String where(Card c) {
        return switch (c.zone) {
            case HAND -> " (from hand)";
            case GY -> " (from GY)";
            default -> "";
        };
    }

    private boolean freeOrWillFree(int who) {
        return true; // procedures check their own space (some tribute or send monsters first)
    }

    private void afterProc(Card c, Runnable then) {
        if (!pending.isEmpty()) {
            processTriggers(then);
        } else {
            Ctx x = new Ctx("summon");
            x.card = c;
            x.player = tp;
            x.how = "special";
            window(x, then);
        }
    }

    /** One entry per card name, so three copies don't make three identical buttons. */
    private List<Card> distinct(List<Card> cards) {
        List<Card> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Card c : cards) {
            if (seen.add(c.def.name)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Cards whose effects a player could use: their field, hand and GY. */
    private List<Card> effectSources(int who) {
        List<Card> out = new ArrayList<>(p[who].monsters);
        for (Card s : spellsTraps(who)) {
            if (s.faceUp) {
                out.add(s);
            }
        }
        out.addAll(distinct(p[who].hand));
        out.addAll(distinct(p[who].gy));
        return out;
    }

    private void setSpellTrap(Card c) {
        move(c, Zone.SZONE, "set");
        c.faceUp = false;
        c.setTurn = turn;
        log(name(tp) + " sets a card in the Spell & Trap Zone.");
    }

    // =================================================================== activating effects

    int speedOf(Card c, Fx fx) {
        if (fx.speed > 0) {
            return fx.speed;
        }
        return switch (fx.type) {
            case ACTIVATE -> isTrap(c) ? (c.def.stype.equals("counter") ? 3 : 2) : c.def.stype.equals("quick") ? 2 : 1;
            case QUICK -> 2;
            case IGNITION, TRIGGER -> 1;
        };
    }

    /** Can {@code who} activate the Spell/Trap card {@code c} (in hand or set) now? */
    boolean canActivateCard(int who, Card c, Fx fx, Ctx ctx) {
        if (fx.type != Fx.Type.ACTIVATE || isOver()) {
            return false;
        }
        boolean fromHand = c.zone == Zone.HAND;
        if (!fromHand && !(c.zone == Zone.SZONE && !c.faceUp && c.controller == who)) {
            return false;
        }
        if (fromHand && c.owner != who) {
            return false;
        }
        int speed = speedOf(c, fx);
        boolean openState = chain.isEmpty() && ctx == null && who == tp && phase != Phase.BATTLE;
        if (isTrap(c)) {
            if (fromHand || c.setTurn >= turn) {
                return false;
            }
        } else if (c.def.stype.equals("quick")) {
            if (!fromHand && c.setTurn >= turn) {
                return false;
            }
            if (fromHand && who != tp) {
                return false;
            }
        } else if (!openState) {
            return false; // other Spells: your own Main Phase, empty chain
        }
        if (!chain.isEmpty() && speed < Math.max(2, chain.get(chain.size() - 1).speed)) {
            return false;
        }
        if (fromHand && !c.def.stype.equals("field") && !freeSpellZone(who)) {
            return false;
        }
        if (fx.once != null && p[who].once.contains(fx.once)) {
            return false;
        }
        return fx.cond.test(this, c, ctx);
    }

    /** Can {@code who} use the monster/continuous effect {@code fx} of {@code c} now? */
    boolean canUse(int who, Card c, Fx fx, Ctx ctx, boolean openState) {
        if (isOver() || (fx.type != Fx.Type.IGNITION && fx.type != Fx.Type.QUICK)) {
            return false;
        }
        if (!fx.from.contains(c.zone)) {
            return false;
        }
        boolean mine = c.onField() ? c.controller == who : c.owner == who;
        if (!mine) {
            return false;
        }
        if (c.onField() && !c.faceUp) {
            return false;
        }
        if (c.zone == Zone.MZONE && effectsNegated(c)) {
            return false;
        }
        if (fx.type == Fx.Type.IGNITION && (!openState || who != tp || phase == Phase.BATTLE || !chain.isEmpty())) {
            return false;
        }
        if (fx.ownTurnOnly && who != tp || fx.oppTurnOnly && who == tp) {
            return false;
        }
        if (!chain.isEmpty() && speedOf(c, fx) < Math.max(2, chain.get(chain.size() - 1).speed)) {
            return false;
        }
        if (fx.once != null && p[who].once.contains(fx.once)) {
            return false;
        }
        return fx.cond.test(this, c, ctx);
    }

    /**
     * Activates {@code fx} of {@code c}: puts a Spell/Trap card face-up on the field, pays the cost
     * and chooses targets, adds the chain link and gives the other player a chance to respond. When
     * the chain and the triggers it caused are done, {@code then} runs.
     */
    void activate(int who, Card c, Fx fx, Ctx ctx, Runnable then) {
        Link l = new Link(fx, c, who, ctx);
        l.speed = speedOf(c, fx);
        l.fromGy = c.zone == Zone.GY;
        if (fx.type == Fx.Type.ACTIVATE) {
            l.cardActivation = true;
            if (c.zone == Zone.HAND) {
                if (c.def.stype.equals("field")) {
                    if (p[who].field != null) {
                        sendToGy(p[who].field, "field");
                    }
                    move(c, Zone.FZONE, "activate");
                } else {
                    move(c, Zone.SZONE, "activate");
                }
            }
            c.faceUp = true;
        }
        if (fx.once != null) {
            p[who].once.add(fx.once);
        }
        uses.merge(c.uid, 1, Integer::sum);
        log(name(who) + " activates " + c.def.name + (fx.label.isEmpty() ? "" : ": " + fx.label) + ".");
        reveal(faceView(c), c);
        fx.cost.run(this, c, l, () -> {
            showTarget(l);
            chain.add(l);
            Ctx act = new Ctx("activated");
            act.card = c;
            act.player = who;
            act.link = l;
            fire(Fx.Ev.ACTIVATED, act);
            respond(opp(who), chainCtx(l), 0, then);
        });
    }

    private Ctx chainCtx(Link l) {
        Ctx x = new Ctx("chain");
        x.link = l;
        x.card = l.card;
        x.player = l.player;
        return x;
    }

    /** What {@code who} could activate in response right now. */
    List<Option> responses(int who, Ctx ctx, Runnable then) {
        List<Option> opts = new ArrayList<>();
        // Spells/Traps set on the field
        for (Card s : List.copyOf(p[who].st)) {
            if (s.faceUp) {
                continue;
            }
            for (Fx fx : s.script.effects) {
                if (canActivateCard(who, s, fx, ctx)) {
                    opts.add(new Option("Activate " + s.def.name + (fx.label.isEmpty() ? "" : ": " + fx.label),
                            () -> activate(who, s, fx, ctx, then), Move.of("respond", s)));
                }
            }
        }
        // Quick-Play Spells from the hand (your own turn)
        for (Card h : distinct(p[who].hand)) {
            if (isSpell(h) && h.def.stype.equals("quick")) {
                for (Fx fx : h.script.effects) {
                    if (canActivateCard(who, h, fx, ctx)) {
                        opts.add(new Option("Activate " + h.def.name + (fx.label.isEmpty() ? "" : ": " + fx.label),
                                () -> activate(who, h, fx, ctx, then), Move.of("respond", h)));
                    }
                }
            }
        }
        // Quick Effects of monsters, face-up continuous cards, the hand and the GY
        for (Card c : effectSources(who)) {
            for (Fx fx : c.script.effects) {
                if (fx.type == Fx.Type.QUICK && canUse(who, c, fx, ctx, false)) {
                    opts.add(new Option("Use " + c.def.name + (fx.label.isEmpty() ? "" : ": " + fx.label) + where(c),
                            () -> activate(who, c, fx, ctx, then), Move.of("respond", c)));
                }
            }
        }
        return opts;
    }

    /**
     * A moment where the other player may respond (after a summon, an attack, a phase change).
     * The turn player acts from their menu instead, except in the damage step.
     */
    void window(Ctx ctx, Runnable then) {
        respond(opp(tp), ctx, 1, then);
    }

    /** Like {@link #window} but both players get a chance (the turn player first). */
    void windowBoth(Ctx ctx, Runnable then) {
        respond(tp, ctx, 0, then);
    }

    private void respond(int who, Ctx ctx, int passes, Runnable then) {
        if (isOver()) {
            return;
        }
        List<Option> opts = responses(who, ctx, then);
        if (opts.isEmpty()) {
            pass(who, ctx, passes, then);
            return;
        }
        opts.add(new Option(chain.isEmpty() ? "Don't respond" : "Don't chain", () -> pass(who, ctx, passes, then), Move.of("pass")));
        ask(who, respondPrompt(who, ctx), opts);
    }

    private String respondPrompt(int who, Ctx ctx) {
        if (!chain.isEmpty()) {
            Link top = chain.get(chain.size() - 1);
            return name(top.player) + " activated " + name(top.card) + " (chain link " + chain.size() + "). Respond?";
        }
        return switch (ctx.kind) {
            case "attack" -> name(tp) + "'s " + name(attacker) + " (" + atk(attacker) + " ATK) attacks "
                    + (attackTarget == null ? "directly" : attackTarget.faceUp ? name(attackTarget) : "a face-down monster") + ". Respond?";
            case "summon" -> ctx.card == null ? "Respond?" : name(ctx.player) + " summoned " + name(ctx.card) + ". Respond?";
            case "calc" -> "Damage calculation: " + name(attacker) + " vs " + (attackTarget == null ? "direct" : name(attackTarget)) + ". Use an effect?";
            case "battle_destroyed" -> ctx.card == null ? "After the battle: respond?" : name(ctx.card) + " was destroyed by battle. Respond?";
            default -> "Respond with an effect?";
        };
    }

    private void pass(int who, Ctx ctx, int passes, Runnable then) {
        if (passes + 1 >= 2) {
            if (chain.isEmpty()) {
                then.run();
            } else {
                resolveChain(then);
            }
        } else {
            respond(opp(who), ctx, passes + 1, then);
        }
    }

    private void resolveChain(Runnable then) {
        if (isOver()) {
            return;
        }
        if (chain.isEmpty()) {
            processTriggers(then);
            return;
        }
        Link l = chain.remove(chain.size() - 1);
        Runnable next = () -> {
            finishLink(l);
            resolveChain(then);
        };
        if (l.negated) {
            log(name(l.card) + "'s effect is negated.");
            next.run();
        } else if (l.cardActivation && !l.card.onField()) {
            next.run(); // the card left the field before it could resolve
        } else {
            l.fx.resolve.run(this, l.card, l, next);
        }
    }

    /** After a link resolves, a Normal/Quick-Play/Ritual Spell or Normal/Counter Trap goes to the GY. */
    private void finishLink(Link l) {
        Card c = l.card;
        if (!l.cardActivation || !c.onField()) {
            return;
        }
        String st = c.def.stype;
        boolean stays = st.equals("continuous") || st.equals("field") || (st.equals("equip") && c.equipTarget != null && !l.negated);
        if (!stays) {
            sendToGy(c, "resolved");
        }
    }

    /** Negates the activation/effect of the link being responded to. */
    void negate(Link l) {
        l.negated = true;
    }

    // =================================================================== triggers

    void fire(Fx.Ev ev, Ctx ctx) {
        for (int who : new int[]{tp, opp(tp)}) {
            Player pl = p[who];
            List<Card> sources = new ArrayList<>(pl.monsters);
            sources.addAll(spellsTraps(who));
            sources.addAll(pl.gy);
            sources.addAll(pl.hand);
            sources.addAll(pl.banished);
            for (Card c : sources) {
                for (Fx fx : c.script.effects) {
                    if (fx.type != Fx.Type.TRIGGER || fx.on != ev || !fx.from.contains(c.zone)) {
                        continue;
                    }
                    if (c.zone == Zone.MZONE && (effectsNegated(c) || !c.faceUp)) {
                        continue;
                    }
                    if ((c.zone == Zone.SZONE || c.zone == Zone.FZONE) && !c.faceUp) {
                        continue;
                    }
                    if (fx.cond.test(this, c, ctx)) {
                        pending.add(new Pending(c, fx, ctx, c.onField() ? c.controller : c.owner));
                    }
                }
            }
        }
    }

    /** Activates pending trigger effects (the turn player's first) as a chain, then {@code then}. */
    void processTriggers(Runnable then) {
        if (isOver()) {
            return;
        }
        if (pending.isEmpty()) {
            then.run();
            return;
        }
        List<Pending> list = new ArrayList<>();
        for (Pending pd : pending) {
            if (pd.player == tp) list.add(pd);
        }
        for (Pending pd : pending) {
            if (pd.player != tp) list.add(pd);
        }
        pending.clear();
        startTriggers(list, 0, then);
    }

    private void startTriggers(List<Pending> list, int i, Runnable then) {
        if (isOver()) {
            return;
        }
        if (i >= list.size()) {
            if (chain.isEmpty()) {
                processTriggers(then);
            } else {
                Link last = chain.get(chain.size() - 1);
                respond(opp(last.player), chainCtx(last), 0, then);
            }
            return;
        }
        Pending pd = list.get(i);
        Runnable skip = () -> startTriggers(list, i + 1, then);
        boolean ok = (pd.fx.once == null || !p[pd.player].once.contains(pd.fx.once)) && pd.fx.cond.test(this, pd.card, pd.ctx)
                && pd.fx.from.contains(pd.card.zone);
        if (!ok) {
            skip.run();
            return;
        }
        Runnable go = () -> activateTrigger(pd, () -> startTriggers(list, i + 1, then));
        if (pd.fx.mandatory) {
            go.run();
        } else {
            yesNo(pd.player, "Activate " + name(pd.card) + ": " + pd.fx.label + "?", go, skip);
        }
    }

    /** Puts a trigger effect on the chain (no response yet: all triggers go on first). */
    private void activateTrigger(Pending pd, Runnable next) {
        Link l = new Link(pd.fx, pd.card, pd.player, pd.ctx);
        l.speed = speedOf(pd.card, pd.fx);
        l.fromGy = pd.card.zone == Zone.GY;
        if (pd.fx.once != null) {
            p[pd.player].once.add(pd.fx.once);
        }
        log(name(pd.player) + " activates " + name(pd.card) + ": " + pd.fx.label + ".");
        reveal(faceView(pd.card), pd.card);
        pd.fx.cost.run(this, pd.card, l, () -> {
            showTarget(l);
            chain.add(l);
            next.run();
        });
    }

    // =================================================================== Fusion, Ritual, Synchro

    /** Monsters a player could use as Fusion/Synchro/Ritual material from the hand and field. */
    List<Card> handAndField(int who) {
        List<Card> out = new ArrayList<>(p[who].monsters);
        for (Card c : p[who].hand) {
            if (isMonster(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Whether some of {@code pool} can fill all {@code slots} (each card once). */
    static boolean canFill(List<Predicate<Card>> slots, List<Card> pool, Set<Card> used, int i) {
        if (i == slots.size()) {
            return true;
        }
        for (Card c : pool) {
            if (!used.contains(c) && slots.get(i).test(c)) {
                used.add(c);
                if (canFill(slots, pool, used, i + 1)) {
                    used.remove(c);
                    return true;
                }
                used.remove(c);
            }
        }
        return false;
    }

    /** Fusion Monsters in {@code who}'s Extra Deck that could be made from {@code pool}. */
    List<Card> fusionTargets(int who, List<Card> pool, Predicate<Card> filter, Predicate<List<Card>> mustInclude) {
        List<Card> out = new ArrayList<>();
        for (Card f : distinct(p[who].extra)) {
            if (!f.def.frame.equals("fusion") || f.script.fusion == null || !filter.test(f)) {
                continue;
            }
            if (mustInclude == null ? canFill(f.script.fusion, pool, new HashSet<>(), 0) : fillIncluding(f.script.fusion, pool, mustInclude)) {
                out.add(f);
            }
        }
        return out;
    }

    private boolean fillIncluding(List<Predicate<Card>> slots, List<Card> pool, Predicate<List<Card>> must) {
        // try every way to fill the slots until one satisfies the requirement (pools are small)
        return search(slots, pool, new ArrayList<>(), must);
    }

    private boolean search(List<Predicate<Card>> slots, List<Card> pool, List<Card> chosen, Predicate<List<Card>> must) {
        if (chosen.size() == slots.size()) {
            return must.test(chosen);
        }
        Predicate<Card> slot = slots.get(chosen.size());
        for (Card c : pool) {
            if (!chosen.contains(c) && slot.test(c)) {
                chosen.add(c);
                if (search(slots, pool, chosen, must)) {
                    chosen.remove(chosen.size() - 1);
                    return true;
                }
                chosen.remove(chosen.size() - 1);
            }
        }
        return false;
    }

    /**
     * Fusion Summons: {@code who} picks a Fusion Monster, then its materials one by one from
     * {@code pool}; materials are sent to the GY (or banished), then it is Special Summoned.
     */
    void fusionSummon(int who, List<Card> pool, Predicate<Card> filter, Predicate<List<Card>> mustInclude0, boolean banishMaterials, Runnable done) {
        // with no free Monster Zone, a material must come from the field to make room
        Predicate<List<Card>> mustInclude = freeMonsterZone(who) ? mustInclude0
                : (mats -> mats.stream().anyMatch(m -> m.zone == Zone.MZONE && m.controller == who) && (mustInclude0 == null || mustInclude0.test(mats)));
        List<Card> targets = fusionTargets(who, pool, filter, mustInclude);
        if (targets.isEmpty() || !freeMonsterZoneAfter(who, pool)) {
            log("No Fusion Summon is possible.");
            done.run();
            return;
        }
        pick(who, "Choose a Fusion Monster to Fusion Summon", targets, false, "fusion", f -> {
            List<Card> chosen = new ArrayList<>();
            chooseMaterials(who, f, f.script.fusion, pool, chosen, mustInclude, () -> {
                for (Card m : chosen) {
                    m.fusionMaterial = true;
                    if (banishMaterials) {
                        banish(m, "material");
                    } else {
                        sendToGy(m, "material");
                    }
                }
                log(name(who) + " uses " + names(chosen) + " as Fusion Material.");
                specialSummon(f, who, "fusion", false);
                done.run();
            });
        }, null);
    }

    List<Card> fusionTargetsFor(int who, List<Card> pool, Predicate<Card> filter, Predicate<List<Card>> mustInclude0) {
        Predicate<List<Card>> mustInclude = freeMonsterZone(who) ? mustInclude0
                : (mats -> mats.stream().anyMatch(m -> m.zone == Zone.MZONE && m.controller == who) && (mustInclude0 == null || mustInclude0.test(mats)));
        return fusionTargets(who, pool, filter, mustInclude);
    }

    private boolean freeMonsterZoneAfter(int who, List<Card> pool) {
        return freeMonsterZone(who) || pool.stream().anyMatch(c -> c.zone == Zone.MZONE && c.controller == who);
    }

    private void chooseMaterials(int who, Card target, List<Predicate<Card>> slots, List<Card> pool, List<Card> chosen,
                                 Predicate<List<Card>> must, Runnable done) {
        if (chosen.size() == slots.size()) {
            done.run();
            return;
        }
        Predicate<Card> slot = slots.get(chosen.size());
        List<Card> options = new ArrayList<>();
        for (Card c : pool) {
            if (chosen.contains(c) || !slot.test(c)) {
                continue;
            }
            chosen.add(c);
            boolean ok = must == null ? canFill(slots.subList(chosen.size(), slots.size()), pool, new HashSet<>(chosen), 0)
                    : search(slots, pool, new ArrayList<>(chosen), must);
            chosen.remove(chosen.size() - 1);
            if (ok) {
                options.add(c);
            }
        }
        pick(who, "Choose Fusion Material " + (chosen.size() + 1) + " of " + slots.size() + " for " + target.def.name, options, false,
                "material", c -> {
                    chosen.add(c);
                    chooseMaterials(who, target, slots, pool, chosen, must, done);
                }, null);
    }

    String names(List<Card> cards) {
        List<String> n = new ArrayList<>();
        for (Card c : cards) {
            n.add(name(c));
        }
        return String.join(", ", n);
    }

    // ---- Synchro

    private List<Card> synchroTuners(int who, Card s) {
        List<Card> out = new ArrayList<>();
        for (Card t : p[who].monsters) {
            if (t.faceUp && isTuner(t) && level(t) < level(s)) {
                List<Card> others = synchroNonTuners(who, s, t);
                if (subsetSum(others, level(s) - level(t), 0, true)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private List<Card> synchroNonTuners(int who, Card s, Card tuner) {
        List<Card> out = new ArrayList<>();
        for (Card m : p[who].monsters) {
            if (m != tuner && m.faceUp && !isTuner(m) && (s.script.synchroNonTuner == null || s.script.synchroNonTuner.test(m))) {
                out.add(m);
            }
        }
        return out;
    }

    /** Whether some non-empty subset (if {@code needOne}) of {@code cards} from index i sums to {@code target} Levels. */
    private boolean subsetSum(List<Card> cards, int target, int i, boolean needOne) {
        if (target == 0) {
            return !needOne;
        }
        if (target < 0 || i >= cards.size()) {
            return false;
        }
        return subsetSum(cards, target - level(cards.get(i)), i + 1, false) || subsetSum(cards, target, i + 1, needOne);
    }

    boolean canSynchro(int who, Card s) {
        return s.zone == Zone.EXTRA && !synchroTuners(who, s).isEmpty();
    }

    private void synchroSummon(int who, Card s, Runnable then) {
        pick(who, "Choose the Tuner for " + s.def.name, synchroTuners(who, s), false, "material", tuner -> {
            List<Card> chosen = new ArrayList<>();
            chooseNonTuners(who, s, tuner, chosen, level(s) - level(tuner), () -> {
                chosen.add(0, tuner);
                for (Card m : chosen) {
                    sendToGy(m, "material");
                }
                log(name(who) + " uses " + names(chosen) + " as Synchro Material.");
                specialSummon(s, who, "synchro", false);
                afterProc(s, then);
            });
        }, null);
    }

    private void chooseNonTuners(int who, Card s, Card tuner, List<Card> chosen, int left, Runnable done) {
        if (left == 0) {
            done.run();
            return;
        }
        List<Card> pool = synchroNonTuners(who, s, tuner);
        pool.removeAll(chosen);
        List<Card> options = new ArrayList<>();
        for (Card c : pool) {
            List<Card> rest = new ArrayList<>(pool);
            rest.remove(c);
            if (left - level(c) == 0 || subsetSum(rest, left - level(c), 0, true)) {
                options.add(c);
            }
        }
        pick(who, "Choose a non-Tuner for " + s.def.name + " (" + left + " more Levels)", options, false, "material", c -> {
            chosen.add(c);
            chooseNonTuners(who, s, tuner, chosen, left - level(c), done);
        }, null);
    }

    // ---- Ritual (Chaos Form)

    /**
     * Ritual Summons a Ritual Monster from the hand matching {@code ritualFilter}: Tribute monsters
     * from the hand/field (and banish {@code gyMaterial} from the GY, if given) whose Levels add up
     * to exactly its Level ({@code orMore}: at least).
     */
    void ritualSummon(int who, Card spell, Predicate<Card> ritualFilter, Predicate<Card> gyMaterial, boolean orMore, Runnable done) {
        List<Card> rituals = ritualTargets(who, spell, ritualFilter, gyMaterial, orMore);
        if (rituals.isEmpty()) {
            log("No Ritual Summon is possible.");
            done.run();
            return;
        }
        pick(who, "Choose a Ritual Monster to Ritual Summon", rituals, false, "ritual", r -> {
            List<Card> chosen = new ArrayList<>();
            chooseRitualMaterials(who, r, ritualPool(who, r, gyMaterial), chosen, level(r), orMore, !freeMonsterZone(who), () -> {
                for (Card m : chosen) {
                    if (m.zone == Zone.GY) {
                        banish(m, "material");
                    } else {
                        sendToGy(m, "tribute");
                    }
                }
                log(name(who) + " uses " + names(chosen) + " for the Ritual Summon.");
                specialSummon(r, who, "ritual", false);
                done.run();
            });
        }, null);
    }

    List<Card> ritualTargets(int who, Card spell, Predicate<Card> ritualFilter, Predicate<Card> gyMaterial, boolean orMore) {
        List<Card> out = new ArrayList<>();
        for (Card r : distinct(p[who].hand)) {
            if (r == spell || !isMonster(r) || !(r.def.frame.equals("ritual") || r.script.ritual) || !ritualFilter.test(r)) {
                continue;
            }
            List<Card> pool = ritualPool(who, r, gyMaterial);
            boolean ok = freeMonsterZone(who) ? levelsReach(pool, level(r), 0, orMore) : pool.stream().anyMatch(c -> {
                if (c.zone != Zone.MZONE) return false;
                List<Card> after = new ArrayList<>(pool);
                after.remove(c);
                return levelsReach(after, level(r) - level(c), 0, orMore);
            });
            if (ok) {
                out.add(r);
            }
        }
        return out;
    }

    private List<Card> ritualPool(int who, Card ritual, Predicate<Card> gyMaterial) {
        List<Card> pool = new ArrayList<>();
        for (Card c : handAndField(who)) {
            if (c != ritual && level(c) > 0) {
                pool.add(c);
            }
        }
        if (gyMaterial != null) {
            for (Card c : p[who].gy) {
                if (gyMaterial.test(c)) {
                    pool.add(c);
                }
            }
        }
        return pool;
    }

    private boolean levelsReach(List<Card> pool, int target, int i, boolean orMore) {
        if (target == 0 || (orMore && target < 0)) {
            return true;
        }
        if (target < 0 || i >= pool.size()) {
            return false;
        }
        return levelsReach(pool, target - level(pool.get(i)), i + 1, orMore) || levelsReach(pool, target, i + 1, orMore);
    }

    private void chooseRitualMaterials(int who, Card ritual, List<Card> pool, List<Card> chosen, int left, boolean orMore, boolean needField,
                                       Runnable done) {
        if (left == 0 || (orMore && left < 0)) {
            done.run();
            return;
        }
        List<Card> rest = new ArrayList<>(pool);
        rest.removeAll(chosen);
        List<Card> options = new ArrayList<>();
        for (Card c : rest) {
            if (needField && chosen.isEmpty() && c.zone != Zone.MZONE) {
                continue;
            }
            List<Card> after = new ArrayList<>(rest);
            after.remove(c);
            if (levelsReach(after, left - level(c), 0, orMore)) {
                options.add(c);
            }
        }
        pick(who, "Choose Ritual material for " + ritual.def.name + " (" + left + " more Levels)", options, false, "material", c -> {
            chosen.add(c);
            chooseRitualMaterials(who, ritual, pool, chosen, left - level(c), orMore, needField, done);
        }, null);
    }

    // =================================================================== battle

    private void battleMenu() {
        Player me = p[tp];
        List<Option> opts = new ArrayList<>();
        Runnable back = () -> { };
        for (Card m : me.monsters) {
            if (canAttack(m)) {
                opts.add(new Option("Attack with " + name(m) + " (" + atk(m) + " ATK)", () -> chooseAttackTarget(m), Move.of("attack", m)));
            }
        }
        for (Card c : effectSources(tp)) {
            for (Fx fx : c.script.effects) {
                if (fx.type == Fx.Type.QUICK && canUse(tp, c, fx, null, false)) {
                    opts.add(new Option("Use " + c.def.name + (fx.label.isEmpty() ? "" : ": " + fx.label) + where(c),
                            () -> activate(tp, c, fx, null, back), Move.of("effect", c)));
                }
            }
        }
        for (Card h : distinct(me.hand)) {
            if (isSpell(h) && h.def.stype.equals("quick")) {
                for (Fx fx : h.script.effects) {
                    if (canActivateCard(tp, h, fx, new Ctx("battle"))) {
                        opts.add(new Option("Activate " + h.def.name, () -> activate(tp, h, fx, new Ctx("battle"), back), Move.of("activate", h)));
                    }
                }
            }
        }
        opts.add(new Option("Go to Main Phase 2", () -> {
            phase = Phase.MAIN2;
            log(name(tp) + " enters Main Phase 2.");
        }, Move.of("main2")));
        opts.add(new Option("End turn", () -> {
            log(name(tp) + " ends the turn.");
            window(new Ctx("phase"), this::endTurn);
        }, Move.of("end")));
        ask(tp, "Battle Phase: choose an attacker", opts);
    }

    boolean canAttack(Card m) {
        if (!m.faceUp || flagged(m, "noattack")) {
            return false;
        }
        boolean defDirect = !m.attackPos && m.script.defenseDirect && !effectsNegated(m) && m.attacksMade == 0;
        if (!m.attackPos && !defDirect) {
            return false;
        }
        int max = m.script.monsterAttacks > 1 && !effectsNegated(m) && !p[opp(m.controller)].monsters.isEmpty() ? m.script.monsterAttacks : 1;
        return m.attacksMade < max;
    }

    private void chooseAttackTarget(Card a) {
        Player op = p[opp(tp)];
        List<Option> opts = new ArrayList<>();
        boolean defDirect = !a.attackPos && a.script.defenseDirect;
        boolean secondAttackOnMonstersOnly = a.attacksMade >= 1;
        if (op.monsters.isEmpty() || defDirect) {
            if (!secondAttackOnMonstersOnly) {
                opts.add(new Option("Attack directly", () -> declareAttack(a, null), Move.of("target", a, null)));
            }
        }
        if (!defDirect) {
            for (Card t : op.monsters) {
                opts.add(new Option("Attack " + (t.faceUp ? name(t) + " (" + (t.attackPos ? atk(t) + " ATK" : def(t) + " DEF") + ")"
                        : "a face-down monster"), () -> declareAttack(a, t), Move.of("target", a, t)));
            }
        }
        opts.add(new Option("Cancel", () -> { }, Move.of("cancel")));
        ask(tp, "Choose a target for " + name(a), opts);
    }

    private void showTarget(Link link) {
        Card target = link.target();
        if (onTable(link.card) && onTable(target)) interaction(link.card, target, false);
    }

    private boolean onTable(Card c) {
        return c != null && (c.zone == Zone.MZONE || c.zone == Zone.SZONE || c.zone == Zone.FZONE);
    }

    private void declareAttack(Card a, Card target) {
        interaction(a, target, true);
        a.attacksMade++;
        lastBattleDestroyed = null;
        attacker = a;
        attackTarget = target;
        attackNegated = false;
        int defender = opp(tp);
        log(name(tp) + "'s " + name(a) + " attacks " + (target == null ? name(defender) + " directly"
                : target.faceUp ? name(target) : "a face-down monster") + ".");
        if (p[defender].barrierTurn == turn && controls(defender, c -> isArch(c, "Elemental HERO"))) {
            p[defender].barrierTurn = -1;
            log("Hero Barrier negates the attack.");
            endAttack();
            return;
        }
        Ctx x = new Ctx("attack");
        x.card = a;
        x.other = target;
        x.player = tp;
        window(x, () -> {
            if (attackNegated || isOver() || a.zone != Zone.MZONE || phase != Phase.BATTLE) {
                endAttack();
                return;
            }
            if (target != null && target.zone != Zone.MZONE) {
                log("The attack target is gone.");
                endAttack();
                return;
            }
            calcBonus.clear();
            Ctx calc = new Ctx("calc");
            calc.card = a;
            calc.other = target;
            windowBoth(calc, () -> damageStep(a, target));
        });
    }

    private void endAttack() {
        attacker = null;
        attackTarget = null;
        attackNegated = false;
        calcBonus.clear();
    }

    /** ATK in this damage calculation, with the damage-calculation-only effects. */
    private int battleAtk(Card a, Card other, boolean attacking) {
        int v = atk(a) + calcBonus.getOrDefault(a, 0);
        if (attacking && other != null && isArch(a, "Elemental HERO") && atk(other) > atk(a) && fieldHas("Skyscraper")) {
            v += 1000;
        }
        if (other != null && a.script.doubleVsDark && !effectsNegated(a) && attr(other, "DARK")) {
            v *= 2;
        }
        return v;
    }

    private boolean fieldHas(String fieldSpell) {
        for (Player pl : p) {
            if (pl.field != null && pl.field.faceUp && pl.field.def.name.equals(fieldSpell)) {
                return true;
            }
        }
        return false;
    }

    private void damageStep(Card a, Card target) {
        if (isOver() || a.zone != Zone.MZONE) {
            endAttack();
            return;
        }
        int defender = opp(tp);
        List<Card> destroyedTargets = new ArrayList<>();
        if (target == null) {
            int dmg = battleAtk(a, null, true);
            if (!a.attackPos && a.script.defenseDirect) {
                dmg /= 2;
            }
            battleDamage(defender, dmg, name(a));
        } else {
            if (!target.faceUp) {
                target.faceUp = true;
                log("The face-down monster is " + target.def.summary() + ".");
            }
            int av = battleAtk(a, target, true);
            if (target.attackPos) {
                int tv = battleAtk(target, a, false);
                if (av > tv) {
                    if (battleDestroy(target)) destroyedTargets.add(target);
                    battleDamage(defender, av - tv, "battle");
                } else if (av < tv) {
                    battleDestroy(a);
                    battleDamage(tp, tv - av, "battle");
                } else if (av > 0) {
                    battleDestroy(a);
                    if (battleDestroy(target)) destroyedTargets.add(target);
                }
            } else {
                int dv = def(target) + calcBonus.getOrDefault(target, 0);
                if (av > dv) {
                    if (battleDestroy(target)) destroyedTargets.add(target);
                    int pierce = piercing(a);
                    if (pierce > 0) {
                        battleDamage(defender, (av - dv) * pierce, "piercing");
                    }
                } else if (av < dv) {
                    battleDamage(tp, dv - av, "battle");
                } else {
                    log("Neither monster is destroyed.");
                }
            }
            for (Card t : destroyedTargets) {
                if (a.zone == Zone.MZONE) {
                    Ctx x = new Ctx("battle_kill");
                    x.card = a;
                    x.other = t;
                    x.player = tp;
                    fire(Fx.Ev.BATTLE_KILL, x);
                }
            }
            if (destroyedTargets.isEmpty() && target.zone == Zone.MZONE && a.zone == Zone.MZONE) {
                Ctx x = new Ctx("survived");
                x.card = a;
                x.other = target;
                x.player = tp;
                fire(Fx.Ev.BATTLE_SURVIVED, x);
            }
        }
        calcBonus.clear();
        Ctx after = new Ctx("battle_destroyed");
        after.card = destroyedTargets.isEmpty() ? null : destroyedTargets.get(0);
        after.player = defender;
        Runnable finish = this::endAttack;
        boolean ownLoss = lastBattleDestroyed != null && lastBattleDestroyed.player == tp && lastBattleDestroyed.card.zone == Zone.GY;
        Ctx afterCtx = ownLoss ? lastBattleDestroyed : after;
        Runnable respondWindow = () -> {
            if (ownLoss) {
                windowBoth(afterCtx, finish);
            } else {
                window(afterCtx, finish);
            }
        };
        if (!pending.isEmpty()) {
            processTriggers(respondWindow);
        } else {
            respondWindow.run();
        }
    }

    private int piercing(Card a) {
        int pierce = a.script.piercing > 0 && !effectsNegated(a) ? a.script.piercing : 0;
        if (flagged(a, "pierce")) {
            pierce = Math.max(pierce, 1);
        }
        return pierce;
    }

    private boolean battleDestroy(Card c) {
        if (c.script.cannotBeDestroyedByBattle && !effectsNegated(c)) {
            log(name(c) + " can't be destroyed by battle.");
            return false;
        }
        if (c.flags.containsKey("tempest") && controls(c.controller, m -> m.def.name.equals("Elemental HERO Tempest") && !effectsNegated(m))) {
            log(name(c) + " can't be destroyed by battle.");
            return false;
        }
        if (flagged(c, "battleproof")) {
            log(name(c) + " can't be destroyed by battle.");
            return false;
        }
        int controller = c.controller;
        boolean ok = destroy(c, "battle", opp(controller), null);
        if (ok) {
            Ctx x = new Ctx("battle_destroyed");
            x.card = c;
            x.player = controller;
            lastBattleDestroyed = x;
        }
        return ok;
    }

    /** The last monster destroyed by battle (for "when a monster you control is destroyed by battle" traps). */
    Ctx lastBattleDestroyed;

    private void battleDamage(int who, int amount, String source) {
        if (p[who].noBattleDamageTurn == turn) {
            log(name(who) + " takes no battle damage.");
            return;
        }
        damage(who, amount, source);
    }

    // =================================================================== effects used by scripts

    /** Special Summons from the Deck/hand/GY with the usual checks. */
    void summonFrom(int who, Card c, boolean defense) {
        if (canSpecial(who, c)) {
            boolean fromDeck = c.zone == Zone.DECK;
            specialSummon(c, who, "special", defense);
            if (fromDeck) {
                shuffleDeck(who);
            }
        }
    }

    List<Card> deckWhere(int who, Predicate<Card> pred) {
        List<Card> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Card c : p[who].deck) {
            if (pred.test(c) && seen.add(c.def.name)) {
                out.add(c);
            }
        }
        return out;
    }

    List<Card> where(List<Card> cards, Predicate<Card> pred) {
        List<Card> out = new ArrayList<>();
        for (Card c : cards) {
            if (pred.test(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Searches: pick 1 card matching {@code pred} from the Deck and add it to the hand. */
    void search(int who, String prompt, Predicate<Card> pred, Runnable done) {
        List<Card> found = deckWhere(who, pred);
        if (found.isEmpty()) {
            log("There's nothing to add.");
            done.run();
            return;
        }
        pick(who, prompt, found, false, "search", c -> {
            toHand(c);
            done.run();
        }, null);
    }

    void discardOne(int who, Predicate<Card> pred, String prompt, Consumer<Card> then) {
        pick(who, prompt, where(p[who].hand, pred), false, "discard", c -> {
            sendToGy(c, "discard");
            log(name(who) + " discards " + name(c) + ".");
            then.accept(c);
        }, null);
    }

    // =================================================================== text view (unused by the visual screen)

    @Override
    public List<Section> view(int viewer) {
        List<Section> out = new ArrayList<>();
        for (int who : new int[]{opp(viewer), viewer}) {
            List<String> lines = new ArrayList<>();
            lines.add("LP " + p[who].lp + " | Hand " + p[who].hand.size() + " | Deck " + p[who].deck.size());
            for (Card m : p[who].monsters) {
                lines.add((m.faceUp || who == viewer ? name(m) + " " + atk(m) + "/" + def(m) : "face-down monster")
                        + (m.attackPos ? " (ATK)" : " (DEF)"));
            }
            for (Card s : spellsTraps(who)) {
                lines.add(s.faceUp || who == viewer ? name(s) + (s.faceUp ? "" : " (set)") : "Set card");
            }
            out.add(new Section(name(who), lines));
        }
        return out;
    }

    // =================================================================== visual board

    @Override
    public Object focus(Option option) {
        Move m = option.move();
        return switch (m.type()) {
            case "target" -> m.b();
            case "pass", "battle", "main2", "end", "cancel", "none", "yes", "no", "mode" -> null;
            default -> m.a();
        };
    }

    @Override
    public Board.CardView cardView(Object focus, int viewer) {
        return focus instanceof Card c ? faceView(c).ref(c) : null;
    }

    private String phaseName() {
        return switch (phase) {
            case MAIN1 -> "Main Phase 1";
            case BATTLE -> "Battle Phase";
            case MAIN2 -> "Main Phase 2";
        };
    }

    @Override
    public Board board(int viewer) {
        // viewer -1: someone watching the table, who sees no hidden cards
        Board b = viewer < 0 ? new Board("ygo", side(0, false), side(1, false))
                : new Board("ygo", side(viewer, true), side(opp(viewer), false));
        if (viewer < 0) {
            viewer = 0;
        }
        b.phase = "Turn " + turn + " · " + name(tp) + " · " + phaseName();
        StringBuilder help = new StringBuilder(help(viewer));
        if (!chain.isEmpty()) {
            help.append(" Chain: ");
            for (int i = 0; i < chain.size(); i++) {
                Link l = chain.get(i);
                help.append(i == 0 ? "" : ", ").append(i + 1).append(". ").append(name(l.card)).append(" (").append(name(l.player)).append(")");
            }
            help.append(".");
        }
        b.help = help.toString();
        return b;
    }

    private String help(int viewer) {
        Decision d = pending();
        if (d == null) {
            return "";
        }
        if (d.player() != viewer) {
            return d.player() == tp ? "Your opponent's turn. You'll be asked if you can respond with something."
                    : "Waiting for your opponent to respond.";
        }
        Set<String> types = new HashSet<>();
        d.options().forEach(o -> types.add(o.move().type()));
        if (types.contains("respond")) return "Click a glowing card to activate it in response, or choose not to respond.";
        if (types.contains("target")) return "Click the glowing monster to attack it.";
        if (types.contains("tribute")) return "Click one of your glowing monsters to tribute it.";
        if (types.contains("material")) return "Click a glowing card to use it as material.";
        if (types.contains("fusion") || types.contains("ritual")) return "Pick the monster to summon from the tray.";
        if (types.contains("search")) return "Pick a card from the tray.";
        if (types.contains("discard")) return "Click a card in your hand to discard it.";
        if (types.contains("yes")) return "Choose whether to use this effect.";
        if (types.contains("pick")) return "Click a glowing card to choose it.";
        if (phase == Phase.BATTLE) return "Click one of your glowing monsters to attack with it.";
        return "Click a glowing card to use it. Extra Deck and Graveyard plays are under \"More choices\". Set traps can be "
                + "activated from your next turn when you get the chance to respond.";
    }

    private Board.Side side(int who, boolean you) {
        Player pl = p[who];
        Board.Side s = new Board.Side(name(who));
        s.active = tp == who;
        s.score = String.valueOf(pl.lp);
        s.scoreLabel = "LP";
        s.info.add("Hand " + pl.hand.size());
        s.info.add("Deck " + pl.deck.size());
        s.info.add("Extra " + pl.extra.size());

        Board.Zone mz = s.zone(new Board.Zone("monsters", "Monster Zone", 0, Board.Align.CENTER, ZONES, false));
        for (Card m : pl.monsters) {
            Board.CardView v;
            if (!m.faceUp && !you) {
                v = Board.CardView.hidden("ygo");
            } else {
                v = faceView(m);
                v.faceDown = !m.faceUp;
                v.peek = !m.faceUp;
                v.stat = atk(m) + "/" + def(m);
                if (!m.faceUp) {
                    v.tags.add("Set");
                }
            }
            v.sideways = !m.attackPos;
            v.tags.add(0, m.attackPos ? "ATK position" : "DEF position");
            if (m.faceUp && effectsNegated(m)) v.tags.add("Effects negated");
            if (m.faceUp && m.attacksMade > 0) v.tags.add("Attacked");
            v.ref(m).alias(m.def.summary(), m.def.name, name(m), "a face-down monster");
            mz.cards.add(v);
        }
        Board.Zone sz = s.zone(new Board.Zone("spells", "Spell & Trap Zone", 1, Board.Align.CENTER, ZONES, false));
        for (Card st : pl.st) {
            Board.CardView v;
            if (!st.faceUp && !you) {
                v = Board.CardView.hidden("ygo");
            } else {
                v = faceView(st);
                v.faceDown = !st.faceUp;
                v.peek = !st.faceUp;
                if (st.equipTarget != null) {
                    v.tags.add("Equipped to " + name(st.equipTarget));
                }
                if (!st.faceUp) {
                    v.tags.add(st.setTurn >= turn ? "Set this turn" : "Set");
                }
            }
            v.ref(st).alias(st.def.summary(), st.def.name, "a face-down card");
            sz.cards.add(v);
        }
        Board.Zone fz = s.zone(new Board.Zone("field", "Field Spell", 0, Board.Align.LEFT, 1, false));
        if (pl.field != null) {
            fz.cards.add(faceView(pl.field).ref(pl.field).alias(pl.field.def.summary(), pl.field.def.name));
        }
        Board.Zone gy = s.zone(new Board.Zone("gy", "Graveyard", 0, Board.Align.RIGHT, 1, true));
        for (Card c : pl.gy) {
            gy.cards.add(faceView(c));
        }
        gy.count = pl.gy.size();
        Board.Zone ban = s.zone(new Board.Zone("banished", "Banished", 0, Board.Align.RIGHT, 1, true));
        for (Card c : pl.banished) {
            ban.cards.add(faceView(c));
        }
        ban.count = pl.banished.size();
        Board.Zone deck = s.zone(new Board.Zone("deck", "Deck", 1, Board.Align.RIGHT, 1, true));
        deck.count = pl.deck.size();
        if (!pl.deck.isEmpty()) {
            deck.cards.add(Board.CardView.hidden("ygo"));
        }
        Board.Zone extra = s.zone(new Board.Zone("extra", "Extra Deck", 1, Board.Align.LEFT, 1, true));
        extra.count = pl.extra.size();
        if (you) {
            for (Card c : pl.extra) {
                extra.cards.add(faceView(c));
            }
        } else if (!pl.extra.isEmpty()) {
            extra.cards.add(Board.CardView.hidden("ygo"));
        }

        s.handCount = pl.hand.size();
        if (you) {
            for (Card c : pl.hand) {
                Card first = pl.hand.stream().filter(x -> x.def == c.def).findFirst().orElse(c);
                s.hand.add(faceView(c).ref(c, first));
            }
        }
        return s;
    }

    /** The face of a card. */
    Board.CardView faceView(Card c) {
        Board.CardView view = definitionView(c.def);
        if (c.def.isMonster()) {
            view.stat = c.zone == Zone.MZONE ? atk(c) + "/" + def(c) : c.def.atk + "/" + c.def.def;
            view.text.set(0, YgoCard.capital(c.def.frame) + (isTuner(c) ? " Tuner" : "") + " Monster · Level " + c.def.level);
        }
        return view;
    }

    public static Board.CardView definitionView(YgoCard d) {
        Board.CardView v = new Board.CardView(d.name);
        v.alias(d.summary(), d.name);
        if (!d.codes.isEmpty() && d.codes.get(0).matches("\\d{1,10}")) {
            v.image = "ygo:" + d.codes.get(0);
        }
        switch (d.kind) {
            case MONSTER -> {
                v.frame = "ygo_monster";
                v.corner = "Lv" + d.level;
                v.stat = d.atk + "/" + d.def;
                v.text.add(YgoCard.capital(d.frame) + (d.tuner ? " Tuner" : "") + " Monster · Level " + d.level);
                if (!d.race.isEmpty()) {
                    v.text.add(d.attribute + " · " + d.race);
                }
                v.text.add("ATK " + d.atk + " / DEF " + d.def);
            }
            case SPELL -> {
                v.frame = "ygo_spell";
                v.text.add(YgoCard.capital(d.stype) + " Spell");
            }
            case TRAP -> {
                v.frame = "ygo_trap";
                v.text.add(YgoCard.capital(d.stype) + " Trap");
            }
        }
        if (!d.text.isEmpty()) {
            v.text.add(d.text);
        }
        return v;
    }
}
