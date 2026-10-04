package com.tablecards.engine.ptcg;

import com.tablecards.engine.BaseGame;
import com.tablecards.engine.Board;
import com.tablecards.engine.Bot;
import com.tablecards.engine.Move;
import com.tablecards.engine.Option;
import com.tablecards.engine.Section;
import com.tablecards.engine.ptcg.PtcgCard.Attack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pokemon TCG rules for two players (current rules where older cards differ).
 *
 * Setup: draw 7 (mulligan until you have a Basic; the opponent draws 1 per mulligan), choose an
 * Active and up to 5 Benched Basics, 6 Prize cards each. Turn: draw (you lose if you can't), then
 * any of: bench Basics, evolve (not on your first turn, not a Pokemon that arrived this turn, once
 * per Pokemon per turn), attach 1 Energy, play Items, play 1 Supporter (not on the first player's
 * first turn), retreat once, then attack (not on the first player's first turn), which ends the
 * turn. Weakness is x2 (or +N on older cards), Resistance -30 (or the printed value).
 * Special Conditions: Asleep, Confused and Paralyzed replace each other; Poisoned and Burned
 * stack with them. Pokemon Checkup between turns: Poisoned 10 damage; Burned 20 damage then flip,
 * heads cures; Asleep flip, heads wakes; Paralyzed ends after its owner's turn. Asleep and
 * Paralyzed Pokemon can't attack or retreat; a Confused Pokemon flips to attack and on tails takes
 * 30 damage instead. Moving to the Bench or evolving removes all Special Conditions and effects.
 * Knocked Out Pokemon give the opponent 1 Prize (2 for ex/V/GX/EX, 3 for VMAX/TAG TEAM). You win
 * by taking all your Prizes, or when your opponent has no Pokemon in play or can't draw.
 */
public final class PtcgGame extends BaseGame {
    static final int BENCH = 5;
    static final int PRIZES = 6;
    static final int HAND_START = 7;
    static final int CONFUSION_DAMAGE = 30;

    static final class Card {
        final PtcgCard def;
        Card(PtcgCard def) { this.def = def; }
    }

    /** A Pokemon in play: its evolution stack (top card is the current form), damage, energy and state. */
    static final class Mon {
        final List<Card> stack = new ArrayList<>();
        final List<Card> energy = new ArrayList<>();
        int damage;
        int arrivedTurn;
        int evolvedTurn = -1;
        /** asleep, confused or paralyzed (they replace each other), or null. */
        String rotation;
        boolean poisoned;
        boolean burned;
        int paralyzedTurn;
        /** Turn numbers on which an effect applies (0 = none). */
        int cantAttackTurn;
        int noRetreatTurn;
        int protectedTurn;
        int reduceTurn;
        int reduceAmount;
        int blockedTurn;
        int smokescreenTurn;

        Mon(Card basic, int turn) {
            stack.add(basic);
            arrivedTurn = turn;
        }
        PtcgCard top() { return stack.get(stack.size() - 1).def; }
        int hpLeft() { return Math.max(0, top().hp - damage); }
        boolean asleep() { return "asleep".equals(rotation); }
        boolean paralyzed() { return "paralyzed".equals(rotation); }
        boolean confused() { return "confused".equals(rotation); }

        /** Leaving the Active Spot or evolving ends Special Conditions and attack effects. */
        void clearActiveState() {
            rotation = null;
            poisoned = false;
            burned = false;
            cantAttackTurn = 0;
            noRetreatTurn = 0;
            protectedTurn = 0;
            reduceTurn = 0;
            reduceAmount = 0;
            blockedTurn = 0;
            smokescreenTurn = 0;
        }

        List<String> conditions() {
            List<String> c = new ArrayList<>();
            if (rotation != null) c.add(PtcgCard.typeName(rotation));
            if (poisoned) c.add("Poisoned");
            if (burned) c.add("Burned");
            return c;
        }
    }

    static final class Player {
        final List<Card> deck = new ArrayList<>();
        final List<Card> hand = new ArrayList<>();
        final List<Card> discard = new ArrayList<>();
        final List<Card> prizes = new ArrayList<>();
        Mon active;
        final List<Mon> bench = new ArrayList<>();
        boolean energyAttached;
        boolean supporterPlayed;
        boolean retreated;
        int turnsTaken;
        boolean setupDone;
        List<Mon> inPlay() {
            List<Mon> all = new ArrayList<>();
            if (active != null) all.add(active);
            all.addAll(bench);
            return all;
        }
    }

    final Player[] p = {new Player(), new Player()};
    /** Every card definition in either deck, by name (for Rare Candy). */
    private final Map<String, PtcgCard> byName = new HashMap<>();
    int turn;
    int tp;
    int first;
    private boolean turnEnding;
    private boolean checkupDone;
    private final Deque<Integer> promotions = new ArrayDeque<>();
    /** Remaining parts of an attack or effect that is resolving (some ask a question first). */
    private final Deque<Runnable> steps = new ArrayDeque<>();

    public PtcgGame(String[] names, List<PtcgCard> deck0, List<PtcgCard> deck1, long seed) {
        super(names, seed);
        List<List<PtcgCard>> decks = List.of(deck0, deck1);
        int[] mulligans = new int[2];
        for (int i = 0; i < 2; i++) {
            for (PtcgCard c : decks.get(i)) {
                p[i].deck.add(new Card(c));
                byName.putIfAbsent(c.name, c);
            }
            while (true) {
                Collections.shuffle(p[i].deck, rng);
                for (int k = 0; k < HAND_START; k++) {
                    p[i].hand.add(pop(p[i].deck));
                }
                if (p[i].hand.stream().anyMatch(c -> c.def.isBasic())) {
                    break;
                }
                mulligans[i]++;
                p[i].deck.addAll(p[i].hand);
                p[i].hand.clear();
            }
        }
        for (int i = 0; i < 2; i++) {
            if (mulligans[i] > 0) {
                log(name(i) + " took " + mulligans[i] + " mulligan(s); " + name(1 - i) + " draws " + mulligans[i] + " extra.");
                for (int k = 0; k < mulligans[i] && !p[1 - i].deck.isEmpty(); k++) {
                    p[1 - i].hand.add(pop(p[1 - i].deck));
                }
            }
            for (int k = 0; k < PRIZES; k++) {
                p[i].prizes.add(pop(p[i].deck));
            }
        }
        first = rng.nextInt(2);
        tp = first;
        log(name(first) + " goes first.");
        start();
    }

    @Override
    public String title() {
        return "Pokemon TCG";
    }

    @Override
    public Bot bot() {
        return new PtcgBot();
    }

    int opp(int player) {
        return 1 - player;
    }

    /** The first player may not attack or play a Supporter on the very first turn of the game. */
    boolean isVeryFirstTurn() {
        return turn == 1;
    }

    /** True while an attack or effect is still resolving (knock-outs are checked when it finishes). */
    boolean resolving() {
        return !steps.isEmpty();
    }

    boolean coin() {
        boolean heads = rng.nextBoolean();
        log("Coin flip: " + (heads ? "heads" : "tails") + ".");
        return heads;
    }

    // ------------------------------------------------------------------ flow

    @Override
    protected void advance() {
        for (int i = 0; i < 2; i++) {
            if (!p[i].setupDone) {
                setupMenu(i);
                return;
            }
        }
        if (turn == 0) {
            beginTurn(first);
            return;
        }
        if (!steps.isEmpty()) {
            steps.removeFirst().run();
            return;
        }
        if (!promotions.isEmpty()) {
            promoteMenu(promotions.peekFirst());
            return;
        }
        if (turnEnding) {
            if (!checkupDone) {
                checkupDone = true;
                checkup();
                return;
            }
            turnEnding = false;
            checkupDone = false;
            beginTurn(opp(tp));
            return;
        }
        mainMenu();
    }

    private void setupMenu(int who) {
        Player me = p[who];
        List<Option> opts = new ArrayList<>();
        if (me.active == null) {
            for (Card c : distinct(me.hand)) {
                if (c.def.isBasic()) {
                    opts.add(new Option("Make " + c.def.summary() + " your Active Pokemon", () -> {
                        me.hand.remove(c);
                        me.active = new Mon(c, 0);
                    }, Move.of("setup_active", c)));
                }
            }
            ask(who, "Setup: choose your Active Pokemon", opts);
            return;
        }
        if (me.bench.size() < BENCH) {
            for (Card c : distinct(me.hand)) {
                if (c.def.isBasic()) {
                    opts.add(new Option("Put " + c.def.summary() + " on your Bench", () -> {
                        me.hand.remove(c);
                        me.bench.add(new Mon(c, 0));
                    }, Move.of("setup_bench", c)));
                }
            }
        }
        opts.add(new Option("Done setting up", () -> {
            me.setupDone = true;
            log(name(who) + " is ready: " + me.active.top().name + " is Active"
                    + (me.bench.isEmpty() ? "." : ", " + me.bench.size() + " on the Bench."));
        }, Move.of("setup_done")));
        ask(who, "Setup: put more Basic Pokemon on your Bench?", opts);
    }

    private void beginTurn(int who) {
        tp = who;
        turn++;
        Player me = p[tp];
        me.energyAttached = false;
        me.supporterPlayed = false;
        me.retreated = false;
        me.turnsTaken++;
        log("Turn " + turn + ": " + name(tp) + ".");
        if (me.deck.isEmpty()) {
            win(opp(tp), name(tp) + " can't draw a card. " + name(opp(tp)) + " wins!");
            return;
        }
        me.hand.add(pop(me.deck));
    }

    private void endTurn() {
        turnEnding = true;
        steps.addLast(this::knockOuts);
    }

    /** Pokemon Checkup between turns. */
    private void checkup() {
        for (int who : new int[]{tp, opp(tp)}) {
            Mon m = p[who].active;
            if (m == null) {
                continue;
            }
            String n = m.top().name;
            if (m.poisoned) {
                m.damage += 10;
                log(n + " takes 10 Poison damage.");
            }
            if (m.burned) {
                m.damage += 20;
                log(n + " takes 20 Burn damage.");
                if (coin()) {
                    m.burned = false;
                    log(n + " is no longer Burned.");
                }
            }
            if (m.asleep()) {
                if (coin()) {
                    m.rotation = null;
                    log(n + " wakes up.");
                } else {
                    log(n + " is still Asleep.");
                }
            }
            if (m.paralyzed() && who == tp && m.paralyzedTurn < turn) {
                m.rotation = null;
                log(n + " is no longer Paralyzed.");
            }
        }
        knockOuts();
    }

    private void mainMenu() {
        Player me = p[tp];
        List<Option> opts = new ArrayList<>();
        boolean firstTurnForMe = me.turnsTaken <= 1;

        if (me.bench.size() < BENCH) {
            for (Card c : distinct(me.hand)) {
                if (c.def.isBasic()) {
                    opts.add(new Option("Put " + c.def.summary() + " on your Bench", () -> {
                        me.hand.remove(c);
                        me.bench.add(new Mon(c, turn));
                        log(name(tp) + " puts " + c.def.name + " on the Bench.");
                    }, Move.of("bench", c)));
                }
            }
        }
        if (!firstTurnForMe) {
            for (Card c : distinct(me.hand)) {
                if (c.def.kind != PtcgCard.Kind.POKEMON || c.def.stage == 0) {
                    continue;
                }
                for (Mon m : me.inPlay()) {
                    if (canEvolve(m, c.def)) {
                        opts.add(new Option("Evolve " + m.top().name + " into " + c.def.summary(), () -> evolve(me, m, c),
                                Move.of("evolve", c, m)));
                    }
                }
            }
        }
        if (!me.energyAttached) {
            for (Card c : distinct(me.hand)) {
                if (c.def.kind == PtcgCard.Kind.ENERGY) {
                    opts.add(new Option("Attach " + c.def.summary(), () -> chooseEnergyTarget(c), Move.of("energy", c)));
                }
            }
        }
        for (Card c : distinct(me.hand)) {
            if (c.def.kind == PtcgCard.Kind.ITEM && canPlayTrainer(tp, c.def)) {
                opts.add(new Option("Play " + c.def.summary(), () -> playTrainer(c), Move.of("item", c)));
            }
            if (c.def.kind == PtcgCard.Kind.SUPPORTER && !me.supporterPlayed && !isVeryFirstTurn() && canPlayTrainer(tp, c.def)) {
                opts.add(new Option("Play " + c.def.summary(), () -> playTrainer(c), Move.of("supporter", c)));
            }
        }
        Mon a = me.active;
        if (canRetreat(a, me)) {
            int cost = a.top().retreat;
            opts.add(new Option("Retreat " + a.top().name + (cost > 0 ? " (discard " + cost + " Energy)" : ""),
                    this::chooseRetreatTarget, Move.of("retreat")));
        }
        if (canAttackNow(a)) {
            for (Attack at : a.top().attacks) {
                if (canPay(a, at)) {
                    int dmg = damageAgainst(a, p[opp(tp)].active, at);
                    opts.add(new Option("Attack: " + at.name + " (" + dmg + " damage)", () -> attack(at), Move.of("attack", at, null, dmg)));
                }
            }
        }
        opts.add(new Option("End turn", () -> {
            log(name(tp) + " ends the turn.");
            endTurn();
        }, Move.of("end")));
        ask(tp, "Your turn: choose an action", opts);
    }

    boolean canEvolve(Mon m, PtcgCard evo) {
        return m.top().name.equals(evo.evolvesFromName()) && m.arrivedTurn < turn && m.evolvedTurn < turn;
    }

    private void evolve(Player me, Mon m, Card c) {
        me.hand.remove(c);
        String from = m.top().name;
        m.stack.add(c);
        m.evolvedTurn = turn;
        m.clearActiveState();
        log(name(tp) + " evolves " + from + " into " + c.def.name + ".");
    }

    boolean canAttackNow(Mon a) {
        return a != null && !isVeryFirstTurn() && !a.asleep() && !a.paralyzed()
                && a.cantAttackTurn != turn && a.blockedTurn != turn;
    }

    boolean canRetreat(Mon a, Player me) {
        return a != null && !me.retreated && !me.bench.isEmpty() && !a.asleep() && !a.paralyzed()
                && a.noRetreatTurn != turn && a.energy.size() >= a.top().retreat;
    }

    private static List<Card> distinct(List<Card> cards) {
        List<Card> out = new ArrayList<>();
        List<PtcgCard> seen = new ArrayList<>();
        for (Card c : cards) {
            if (!seen.contains(c.def)) {
                seen.add(c.def);
                out.add(c);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ energy, trainers, retreat

    private void chooseEnergyTarget(Card energy) {
        Player me = p[tp];
        List<Option> opts = new ArrayList<>();
        for (Mon m : me.inPlay()) {
            opts.add(new Option("Attach to " + describe(m), () -> {
                me.hand.remove(energy);
                m.energy.add(energy);
                me.energyAttached = true;
                log(name(tp) + " attaches " + energy.def.summary() + " to " + m.top().name + ".");
            }, Move.of("energy_target", energy, m)));
        }
        opts.add(new Option("Cancel", () -> { }, Move.of("cancel")));
        ask(tp, "Attach " + energy.def.summary() + " to which Pokemon?", opts);
    }

    private static boolean isBasicEnergy(Card c) {
        return c.def.kind == PtcgCard.Kind.ENERGY;
    }

    boolean canPlayTrainer(int who, PtcgCard t) {
        Player me = p[who];
        Player op = p[opp(who)];
        return switch (t.effect) {
            case "heal" -> me.inPlay().stream().anyMatch(m -> m.damage > 0);
            case "switch" -> !me.bench.isEmpty();
            case "gust" -> !op.bench.isEmpty();
            case "search_basic" -> me.deck.stream().anyMatch(c -> c.def.isBasic());
            case "nest_ball" -> me.bench.size() < BENCH && me.deck.stream().anyMatch(c -> c.def.isBasic());
            case "search_pokemon" -> me.deck.stream().anyMatch(c -> c.def.kind == PtcgCard.Kind.POKEMON)
                    && me.hand.size() - 1 >= t.value;
            case "pokeball_coin" -> !me.deck.isEmpty();
            case "search_energy" -> me.deck.stream().anyMatch(PtcgGame::isBasicEnergy);
            case "energy_retrieval" -> me.discard.stream().anyMatch(PtcgGame::isBasicEnergy);
            case "energy_switch" -> me.inPlay().size() >= 2 && me.inPlay().stream().anyMatch(m -> !m.energy.isEmpty());
            case "draw", "shuffle_draw", "shuffle_in_draw", "coin_draw", "look_top_pokemon", "look_top_supporter" -> !me.deck.isEmpty();
            case "coin_discard_opp_energy" -> op.inPlay().stream().anyMatch(m -> !m.energy.isEmpty());
            case "marnie" -> true;
            case "full_heal" -> me.active != null && !me.active.conditions().isEmpty();
            case "scoop_coin" -> me.inPlay().size() >= 2;
            case "draw_until" -> !me.deck.isEmpty() && me.hand.size() - 1 < t.value;
            case "judge", "n_shuffle", "iono" -> true;
            case "rare_candy" -> !isVeryFirstTurn() && me.turnsTaken > 1 && !rareCandyPairs(me, t.value == 1).isEmpty();
            default -> false;
        };
    }

    private void playTrainer(Card c) {
        Player me = p[tp];
        PtcgCard t = c.def;
        me.hand.remove(c);
        me.discard.add(c);
        if (t.kind == PtcgCard.Kind.SUPPORTER) {
            me.supporterPlayed = true;
        }
        log(name(tp) + " plays " + t.name + ".");
        switch (t.effect) {
            case "heal" -> {
                List<Option> opts = new ArrayList<>();
                for (Mon m : me.inPlay()) {
                    if (m.damage > 0) {
                        opts.add(new Option("Heal " + describe(m), () -> heal(m, t.value), Move.of("heal_target", m)));
                    }
                }
                ask(tp, t.name + ": heal which Pokemon?", opts);
            }
            case "switch" -> chooseSwitch(tp, t.name + ": which Benched Pokemon becomes Active?", "switch_target");
            case "gust" -> {
                Player op = p[opp(tp)];
                List<Option> opts = new ArrayList<>();
                for (Mon m : op.bench) {
                    opts.add(new Option("Bring out " + describe(m), () -> {
                        swapActive(opp(tp), m);
                        log(name(tp) + " brings " + m.top().name + " into the Active Spot.");
                    }, Move.of("gust_target", m)));
                }
                ask(tp, t.name + ": which of your opponent's Benched Pokemon becomes Active?", opts);
            }
            case "search_basic" -> searchDeck(t.name, x -> x.def.isBasic(), false);
            case "search_energy" -> searchMany(t.name, PtcgGame::isBasicEnergy, Math.max(1, t.value));
            case "shuffle_in_draw" -> {
                shuffleHandIn(tp, false);
                drawCards(tp, t.value);
            }
            case "coin_draw" -> {
                int heads = 0;
                while (coin()) {
                    heads++;
                }
                drawCards(tp, heads);
            }
            case "coin_discard_opp_energy" -> {
                if (coin()) {
                    chooseAnyOppEnergyDiscard(tp);
                }
            }
            case "look_top_pokemon", "look_top_supporter" -> lookTop(t.name, t.value,
                    t.effect.equals("look_top_pokemon") ? x -> x.def.kind == PtcgCard.Kind.POKEMON : x -> x.def.kind == PtcgCard.Kind.SUPPORTER);
            case "marnie" -> {
                boolean any = !p[0].hand.isEmpty() || !p[1].hand.isEmpty();
                shuffleHandIn(0, true);
                shuffleHandIn(1, true);
                if (any) {
                    drawCards(tp, 5);
                    drawCards(opp(tp), 4);
                }
            }
            case "full_heal" -> {
                me.active.rotation = null;
                me.active.poisoned = false;
                me.active.burned = false;
                log(me.active.top().name + " is cured of Special Conditions.");
            }
            case "scoop_coin" -> {
                if (coin()) {
                    scoopUp(t.name);
                }
            }
            case "nest_ball" -> searchDeck(t.name, x -> x.def.isBasic(), true);
            case "search_pokemon" -> discardThen(t.value, t.name, () -> searchDeck(t.name, x -> x.def.kind == PtcgCard.Kind.POKEMON, false));
            case "pokeball_coin" -> {
                if (coin()) {
                    searchDeck(t.name, x -> x.def.kind == PtcgCard.Kind.POKEMON, false);
                } else {
                    Collections.shuffle(me.deck, rng);
                }
            }
            case "energy_retrieval" -> retrieveEnergy(t.name, Math.max(1, t.value));
            case "energy_switch" -> energySwitch(t.name);
            case "draw" -> drawCards(tp, t.value);
            case "draw_until" -> drawCards(tp, t.value - me.hand.size());
            case "shuffle_draw" -> {
                me.discard.addAll(me.hand);
                me.hand.clear();
                drawCards(tp, t.value);
            }
            case "judge" -> {
                for (int i = 0; i < 2; i++) {
                    shuffleHandIn(i, false);
                    drawCards(i, t.value);
                }
            }
            case "n_shuffle", "iono" -> {
                boolean any = !p[0].hand.isEmpty() || !p[1].hand.isEmpty();
                for (int i = 0; i < 2; i++) {
                    shuffleHandIn(i, t.effect.equals("iono"));
                }
                if (t.effect.equals("n_shuffle") || any) {
                    for (int i = 0; i < 2; i++) {
                        drawCards(i, p[i].prizes.size());
                    }
                }
            }
            case "rare_candy" -> rareCandy(t.name, t.value == 1);
            default -> { }
        }
    }

    private void heal(Mon m, int amount) {
        int healed = Math.min(amount, m.damage);
        m.damage -= healed;
        log(m.top().name + " heals " + healed + " damage.");
    }

    private void shuffleHandIn(int who, boolean bottom) {
        Player pl = p[who];
        if (bottom) {
            Collections.shuffle(pl.hand, rng);
            pl.deck.addAll(0, pl.hand);
        } else {
            pl.deck.addAll(pl.hand);
            Collections.shuffle(pl.deck, rng);
        }
        pl.hand.clear();
    }

    private void searchDeck(String what, java.util.function.Predicate<Card> filter, boolean toBench) {
        Player me = p[tp];
        List<Option> opts = new ArrayList<>();
        for (Card d : distinct(me.deck)) {
            if (filter.test(d)) {
                opts.add(new Option((toBench ? "Bench " : "Take ") + d.def.summary(), () -> {
                    me.deck.remove(d);
                    if (toBench) {
                        me.bench.add(new Mon(d, turn));
                        log(name(tp) + " puts " + d.def.name + " from the deck onto the Bench.");
                    } else {
                        me.hand.add(d);
                        log(name(tp) + " takes " + d.def.name + " from the deck.");
                    }
                    Collections.shuffle(me.deck, rng);
                }, Move.of("search_target", d)));
            }
        }
        if (opts.isEmpty()) {
            Collections.shuffle(me.deck, rng);
            log(name(tp) + " finds nothing.");
            return;
        }
        ask(tp, what + ": choose a card from your deck", opts);
    }

    /** Asks the player to discard {@code count} cards from their hand, then runs {@code then}. */
    private void discardThen(int count, String what, Runnable then) {
        Player me = p[tp];
        if (count <= 0) {
            then.run();
            return;
        }
        List<Option> opts = new ArrayList<>();
        for (Card c : distinct(me.hand)) {
            opts.add(new Option("Discard " + c.def.summary(), () -> {
                me.hand.remove(c);
                me.discard.add(c);
                discardThen(count - 1, what, then);
            }, Move.of("discard_cost", c)));
        }
        ask(tp, what + ": discard " + count + " more card(s) from your hand", opts);
    }

    private void retrieveEnergy(String what, int left) {
        Player me = p[tp];
        List<Option> opts = new ArrayList<>();
        for (Card c : distinct(me.discard)) {
            if (isBasicEnergy(c)) {
                opts.add(new Option("Take " + c.def.summary(), () -> {
                    me.discard.remove(c);
                    me.hand.add(c);
                    log(name(tp) + " takes " + c.def.summary() + " from the discard pile.");
                    if (left > 1 && me.discard.stream().anyMatch(PtcgGame::isBasicEnergy)) {
                        retrieveEnergy(what, left - 1);
                    }
                }, Move.of("retrieve_target", c)));
            }
        }
        if (opts.isEmpty()) {
            return;
        }
        opts.add(new Option("Done", () -> { }, Move.of("done")));
        ask(tp, what + ": take up to " + left + " basic Energy", opts);
    }

    private void energySwitch(String what) {
        Player me = p[tp];
        List<Option> opts = new ArrayList<>();
        for (Mon from : me.inPlay()) {
            for (Card e : distinct(from.energy)) {
                for (Mon to : me.inPlay()) {
                    if (to == from) {
                        continue;
                    }
                    opts.add(new Option("Move " + e.def.summary() + " from " + from.top().name + " to " + to.top().name, () -> {
                        from.energy.remove(e);
                        to.energy.add(e);
                        log(name(tp) + " moves " + e.def.summary() + " from " + from.top().name + " to " + to.top().name + ".");
                    }, Move.of("energy_move", e, to)));
                }
            }
        }
        ask(tp, what + ": move a basic Energy", opts);
    }

    /** (Basic in play, Stage 2 card in hand) pairs Rare Candy can use; older wording also allows Stage 1. */
    private List<Object[]> rareCandyPairs(Player me, boolean stage1Too) {
        List<Object[]> out = new ArrayList<>();
        for (Mon m : me.inPlay()) {
            if (m.stack.size() != 1 || m.arrivedTurn >= turn || m.evolvedTurn >= turn) {
                continue;
            }
            for (Card c : distinct(me.hand)) {
                if (stage1Too && c.def.kind == PtcgCard.Kind.POKEMON && c.def.stage == 1 && m.top().name.equals(c.def.evolvesFromName())) {
                    out.add(new Object[]{m, c});
                }
                if (c.def.kind == PtcgCard.Kind.POKEMON && c.def.stage == 2) {
                    PtcgCard stage1 = byName.get(c.def.evolvesFromName());
                    String root = stage1 != null ? stage1.evolvesFromName() : c.def.rootName;
                    if (m.top().name.equals(root)) {
                        out.add(new Object[]{m, c});
                    }
                }
            }
        }
        return out;
    }

    private void rareCandy(String what, boolean stage1Too) {
        Player me = p[tp];
        List<Option> opts = new ArrayList<>();
        for (Object[] pair : rareCandyPairs(me, stage1Too)) {
            Mon m = (Mon) pair[0];
            Card c = (Card) pair[1];
            opts.add(new Option("Evolve " + m.top().name + " straight into " + c.def.summary(), () -> evolve(me, m, c),
                    Move.of("evolve", c, m)));
        }
        ask(tp, what + ": choose a Basic Pokemon and a Stage 2", opts);
    }

    /** Takes up to {@code max} matching cards from the deck, one choice at a time. */
    private void searchMany(String what, java.util.function.Predicate<Card> filter, int max) {
        Player me = p[tp];
        List<Option> opts = new ArrayList<>();
        for (Card d : distinct(me.deck)) {
            if (filter.test(d)) {
                opts.add(new Option("Take " + d.def.summary(), () -> {
                    me.deck.remove(d);
                    me.hand.add(d);
                    log(name(tp) + " takes " + d.def.summary() + " from the deck.");
                    if (max > 1) {
                        searchMany(what, filter, max - 1);
                    } else {
                        Collections.shuffle(me.deck, rng);
                    }
                }, Move.of("search_target", d)));
            }
        }
        if (opts.isEmpty()) {
            Collections.shuffle(me.deck, rng);
            return;
        }
        opts.add(new Option("Done", () -> Collections.shuffle(me.deck, rng), Move.of("done")));
        ask(tp, what + ": take up to " + max + " more", opts);
    }

    /** Looks at the top {@code n} cards and may take one matching card; the rest are shuffled back. */
    private void lookTop(String what, int n, java.util.function.Predicate<Card> filter) {
        Player me = p[tp];
        List<Card> top = new ArrayList<>(me.deck.subList(Math.max(0, me.deck.size() - n), me.deck.size()));
        List<Option> opts = new ArrayList<>();
        for (Card d : distinct(top)) {
            if (filter.test(d)) {
                opts.add(new Option("Take " + d.def.summary(), () -> {
                    me.deck.remove(d);
                    me.hand.add(d);
                    Collections.shuffle(me.deck, rng);
                    log(name(tp) + " takes " + d.def.name + " from the top of the deck.");
                }, Move.of("search_target", d)));
            }
        }
        opts.add(new Option("Take nothing", () -> Collections.shuffle(me.deck, rng), Move.of("done")));
        List<String> names = new ArrayList<>();
        for (Card c : top) {
            names.add(c.def.name);
        }
        ask(tp, what + ": your top " + top.size() + " cards are " + String.join(", ", names), opts);
    }

    private void chooseAnyOppEnergyDiscard(int me) {
        List<Option> opts = new ArrayList<>();
        for (Mon m : p[opp(me)].inPlay()) {
            for (Card e : distinct(m.energy)) {
                opts.add(new Option("Discard " + e.def.summary() + " from " + m.top().name, () -> {
                    m.energy.remove(e);
                    p[opp(me)].discard.add(e);
                    log(m.top().name + " loses " + e.def.summary() + ".");
                }, Move.of("opp_energy", e, m)));
            }
        }
        if (!opts.isEmpty()) {
            ask(me, "Discard an Energy from 1 of your opponent's Pokemon", opts);
        }
    }

    private void scoopUp(String what) {
        Player me = p[tp];
        List<Option> opts = new ArrayList<>();
        for (Mon m : me.inPlay()) {
            opts.add(new Option("Return " + describe(m), () -> {
                boolean wasActive = me.active == m;
                if (wasActive) {
                    me.active = null;
                } else {
                    me.bench.remove(m);
                }
                me.hand.addAll(m.stack);
                me.hand.addAll(m.energy);
                log(name(tp) + " returns " + m.top().name + " and its cards to their hand.");
                if (wasActive && !promotions.contains(tp)) {
                    promotions.addLast(tp);
                }
            }, Move.of("scoop_target", m)));
        }
        ask(tp, what + ": return which Pokemon?", opts);
    }

    private void benchSearch(int me, int max) {
        Player pl = p[me];
        if (max <= 0 || pl.bench.size() >= BENCH || pl.deck.stream().noneMatch(c -> c.def.isBasic())) {
            Collections.shuffle(pl.deck, rng);
            return;
        }
        List<Option> opts = new ArrayList<>();
        for (Card d : distinct(pl.deck)) {
            if (d.def.isBasic()) {
                opts.add(new Option("Bench " + d.def.summary(), () -> {
                    pl.deck.remove(d);
                    pl.bench.add(new Mon(d, turn));
                    log(name(me) + " puts " + d.def.name + " from the deck onto the Bench.");
                    benchSearch(me, max - 1);
                }, Move.of("search_target", d)));
            }
        }
        opts.add(new Option("Done", () -> Collections.shuffle(pl.deck, rng), Move.of("done")));
        ask(me, "Put up to " + max + " Basic Pokemon from your deck onto your Bench", opts);
    }

    private void drawCards(int who, int n) {
        int drawn = 0;
        for (int i = 0; i < n && !p[who].deck.isEmpty(); i++) {
            p[who].hand.add(pop(p[who].deck));
            drawn++;
        }
        log(name(who) + " draws " + drawn + ".");
    }

    private void chooseRetreatTarget() {
        Player me = p[tp];
        List<Option> opts = new ArrayList<>();
        for (Mon m : me.bench) {
            opts.add(new Option("Switch in " + describe(m), () -> {
                Mon old = me.active;
                for (int i = 0; i < old.top().retreat; i++) {
                    me.discard.add(old.energy.remove(old.energy.size() - 1));
                }
                swapActive(tp, m);
                me.retreated = true;
                log(name(tp) + " retreats " + old.top().name + " and sends out " + m.top().name + ".");
            }, Move.of("retreat_target", m)));
        }
        opts.add(new Option("Cancel", () -> { }, Move.of("cancel")));
        ask(tp, "Retreat: which Benched Pokemon becomes Active?", opts);
    }

    /** Lets {@code who} pick a Benched Pokemon to swap with their Active. */
    private void chooseSwitch(int who, String prompt, String moveType) {
        Player me = p[who];
        List<Option> opts = new ArrayList<>();
        for (Mon m : me.bench) {
            opts.add(new Option("Switch in " + describe(m), () -> {
                swapActive(who, m);
                log(name(who) + " switches in " + m.top().name + ".");
            }, Move.of(moveType, m)));
        }
        ask(who, prompt, opts);
    }

    private void swapActive(int who, Mon fromBench) {
        Player me = p[who];
        me.bench.remove(fromBench);
        if (me.active != null) {
            me.active.clearActiveState();
            me.bench.add(me.active);
        }
        me.active = fromBench;
    }

    // ------------------------------------------------------------------ attacking

    boolean canPay(Mon m, Attack a) {
        Map<String, Integer> have = new HashMap<>();
        for (Card e : m.energy) {
            have.merge(e.def.type, 1, Integer::sum);
        }
        int colorless = 0;
        for (String t : a.cost) {
            if (t.equals("colorless")) {
                colorless++;
            } else {
                int n = have.getOrDefault(t, 0);
                if (n == 0) {
                    return false;
                }
                have.put(t, n - 1);
            }
        }
        return have.values().stream().mapToInt(Integer::intValue).sum() >= colorless;
    }

    /** Damage from {@code base} after Weakness, Resistance and the defender's damage reduction. */
    int applyModifiers(Mon attacker, Mon defender, Attack a, int base, boolean isActiveTarget) {
        if (base <= 0 || defender == null) {
            return Math.max(0, base);
        }
        int dmg = base;
        if (isActiveTarget) {
            String type = attacker.top().type;
            PtcgCard d = defender.top();
            if (type.equals(d.weakness)) {
                dmg = d.weaknessPlus > 0 ? dmg + d.weaknessPlus : dmg * 2;
            }
            if (type.equals(d.resistance) && !a.ignoreResistance) {
                dmg -= d.resistanceValue;
            }
            if (defender.reduceTurn == turn && !a.ignoreEffects) {
                dmg -= defender.reduceAmount;
            }
        }
        return Math.max(0, dmg);
    }

    /** The damage the attack does before coin flips (shown on the button). */
    int damageAgainst(Mon attacker, Mon defender, Attack a) {
        int base = a.coinTimesN > 0 || a.coinUntilTailsPer > 0 ? 0 : a.damage + a.perCounter * (attacker.damage / 10);
        return applyModifiers(attacker, defender, a, base, true);
    }

    private void attack(Attack a) {
        int me = tp;
        int o = opp(tp);
        Mon attacker = p[me].active;
        Mon defender = p[o].active;
        log(attacker.top().name + " uses " + a.name + ".");
        endTurn(); // the attack ends the turn once its effects finish (knock-outs are checked last)
        List<Runnable> plan = new ArrayList<>();

        if (attacker.confused()) {
            log(attacker.top().name + " is Confused...");
            if (!coin()) {
                attacker.damage += CONFUSION_DAMAGE;
                log("The attack fails and " + attacker.top().name + " takes " + CONFUSION_DAMAGE + " damage.");
                return;
            }
        }
        if (attacker.smokescreenTurn == turn) {
            log(attacker.top().name + " has to flip to attack...");
            if (!coin()) {
                log("The attack does nothing.");
                return;
            }
        }
        if (a.tailsNothing && !coin()) {
            log("The attack does nothing.");
            return;
        }
        if (a.eitherTailsNothing && !(coin() & coin())) {
            log("The attack does nothing.");
            return;
        }
        int base = a.damage + a.perCounter * (attacker.damage / 10);
        if (a.coinTimesN > 0) {
            int heads = 0;
            for (int i = 0; i < a.coinTimesN; i++) {
                if (coin()) heads++;
            }
            base = a.coinTimesPer * heads;
        }
        if (a.coinUntilTailsPer > 0 || a.coinUntilTailsBonus > 0) {
            int heads = 0;
            while (coin()) {
                heads++;
            }
            base = a.coinUntilTailsPer > 0 ? a.coinUntilTailsPer * heads : base + a.coinUntilTailsBonus * heads;
        }
        if (a.headsBonus > 0 && coin()) {
            base += a.headsBonus;
        }
        if (a.coinBonusN > 0) {
            for (int i = 0; i < a.coinBonusN; i++) {
                if (coin()) base += a.coinBonusPer;
            }
        }
        boolean shielded = defender.protectedTurn == turn && !a.ignoreEffects;
        int dealt = 0;
        if (shielded) {
            log(defender.top().name + " is protected from the attack.");
        } else {
            dealt = applyModifiers(attacker, defender, a, base, true);
            if (dealt > 0) {
                defender.damage += dealt;
                log(defender.top().name + " takes " + dealt + " damage.");
            }
            // effects on the Defending Pokemon
            for (String s : a.status) {
                applyStatus(defender, s);
            }
            if (!a.coinStatus.isEmpty() && coin()) {
                for (String s : a.coinStatus) {
                    applyStatus(defender, s);
                }
            }
            if (a.noRetreat) {
                defender.noRetreatTurn = turn + 1;
            }
            if (a.smokescreen) {
                defender.smokescreenTurn = turn + 1;
            }
            if (a.coinDefenderCantAttack && coin()) {
                defender.blockedTurn = turn + 1;
                log(defender.top().name + " can't attack next turn.");
            }
            if (a.discardOppEnergy || (a.coinDiscardOppEnergy && coin())) {
                plan.add(() -> chooseOppEnergyDiscard(me, defender));
            }
        }
        // effects on the attacker and the players
        if (a.heal > 0) heal(attacker, a.heal);
        if (a.healDealt && dealt > 0) heal(attacker, dealt);
        if (a.selfDamage > 0) {
            attacker.damage += a.selfDamage;
            log(attacker.top().name + " does " + a.selfDamage + " damage to itself.");
        }
        if (a.discardEnergy > 0) {
            discardOwnEnergy(me, attacker, a.discardEnergy);
        }
        if (a.cantAttackNext) attacker.cantAttackTurn = turn + 2;
        if (a.coinProtect && coin()) {
            attacker.protectedTurn = turn + 1;
            log(attacker.top().name + " will be protected during the next turn.");
        }
        if (a.reduceNext > 0) {
            attacker.reduceTurn = turn + 1;
            attacker.reduceAmount = a.reduceNext;
        }
        if (a.draw > 0) drawCards(me, a.draw);
        if (a.mill > 0) {
            for (int i = 0; i < a.mill && !p[o].deck.isEmpty(); i++) {
                p[o].discard.add(pop(p[o].deck));
            }
            log(name(o) + " discards the top " + a.mill + " card(s) of their deck.");
        }
        if (a.revealHand) {
            List<String> names = new ArrayList<>();
            for (Card c : p[o].hand) {
                names.add(c.def.name);
            }
            log(name(o) + "'s hand: " + (names.isEmpty() ? "empty" : String.join(", ", names)) + ".");
        }
        if (a.discardRandom && !p[o].hand.isEmpty()) {
            Card c = p[o].hand.remove(rng.nextInt(p[o].hand.size()));
            p[o].discard.add(c);
            log(name(o) + " discards " + c.def.name + " from their hand at random.");
        }
        // effects that need a choice, in order
        if (a.drawUntil > 0) plan.add(() -> offerDrawUntil(me, a.drawUntil));
        if (a.benchSearch > 0) plan.add(() -> benchSearch(me, a.benchSearch));
        if (a.benchSnipe > 0) plan.add(() -> chooseSnipe(me, attacker, a, a.benchSnipe, false));
        if (a.snipeAny > 0) plan.add(() -> chooseSnipe(me, attacker, a, a.snipeAny, true));
        if (a.healOne > 0) plan.add(() -> chooseHealOne(me, a.healOne));
        if ("must".equals(a.selfSwitch)) plan.add(() -> selfSwitch(me, false));
        if ("may".equals(a.selfSwitch)) plan.add(() -> selfSwitch(me, true));
        if (a.forceSwitch && !shielded) plan.add(() -> forceSwitch(o));
        // run the planned steps before the knock-out check that endTurn() queued
        for (int i = plan.size() - 1; i >= 0; i--) {
            steps.addFirst(plan.get(i));
        }
    }

    private void applyStatus(Mon m, String status) {
        switch (status) {
            case "asleep", "confused", "paralyzed" -> {
                m.rotation = status;
                if (status.equals("paralyzed")) {
                    m.paralyzedTurn = turn;
                }
            }
            case "poisoned" -> m.poisoned = true;
            case "burned" -> m.burned = true;
            default -> {
                return;
            }
        }
        log(m.top().name + " is now " + PtcgCard.typeName(status) + ".");
    }

    private void discardOwnEnergy(int who, Mon m, int count) {
        for (int i = 0; i < count && !m.energy.isEmpty(); i++) {
            Card e = m.energy.stream().filter(c -> c.def.type.equals(m.top().type)).findFirst()
                    .orElse(m.energy.get(m.energy.size() - 1));
            m.energy.remove(e);
            p[who].discard.add(e);
        }
        log(m.top().name + " discards " + (count >= 99 ? "all its" : String.valueOf(Math.min(count, 99))) + " Energy.");
    }

    private void chooseOppEnergyDiscard(int me, Mon defender) {
        if (defender.energy.isEmpty() || !p[opp(me)].inPlay().contains(defender)) {
            return;
        }
        List<Option> opts = new ArrayList<>();
        for (Card e : distinct(defender.energy)) {
            opts.add(new Option("Discard " + e.def.summary() + " from " + defender.top().name, () -> {
                defender.energy.remove(e);
                p[opp(me)].discard.add(e);
                log(defender.top().name + " loses " + e.def.summary() + ".");
            }, Move.of("opp_energy", e)));
        }
        ask(me, "Discard an Energy from your opponent's Active Pokemon", opts);
    }

    private void offerDrawUntil(int me, int n) {
        int need = n - p[me].hand.size();
        if (need <= 0 || p[me].deck.isEmpty()) {
            return;
        }
        ask(me, "Draw cards until you have " + n + " in your hand?", List.of(
                new Option("Draw " + need, () -> drawCards(me, need), Move.of("draw_yes", null, null, need)),
                new Option("Don't draw", () -> { }, Move.of("draw_no"))));
    }

    private void chooseSnipe(int me, Mon attacker, Attack a, int amount, boolean anyTarget) {
        Player op = p[opp(me)];
        List<Option> opts = new ArrayList<>();
        List<Mon> targets = anyTarget ? op.inPlay() : op.bench;
        for (Mon m : targets) {
            boolean active = m == op.active;
            opts.add(new Option("Do " + amount + " damage to " + describe(m), () -> {
                if (!active && m.top().benchProtected) {
                    log(m.top().name + " is protected while on the Bench.");
                    return;
                }
                if (active && m.protectedTurn == turn && !a.ignoreEffects) {
                    log(m.top().name + " is protected from the attack.");
                    return;
                }
                int dmg = active ? applyModifiers(attacker, m, a, amount, true) : amount;
                m.damage += dmg;
                log(m.top().name + " takes " + dmg + " damage.");
            }, Move.of("snipe_target", m, null, amount)));
        }
        if (!opts.isEmpty()) {
            ask(me, "Choose which of your opponent's Pokemon takes " + amount + " damage", opts);
        }
    }

    private void chooseHealOne(int me, int amount) {
        List<Option> opts = new ArrayList<>();
        for (Mon m : p[me].inPlay()) {
            if (m.damage > 0) {
                opts.add(new Option("Heal " + describe(m), () -> heal(m, amount), Move.of("heal_target", m)));
            }
        }
        if (!opts.isEmpty()) {
            ask(me, "Heal " + amount + " damage from which Pokemon?", opts);
        }
    }

    private void selfSwitch(int me, boolean optional) {
        Player pl = p[me];
        if (pl.bench.isEmpty() || pl.active == null) {
            return;
        }
        List<Option> opts = new ArrayList<>();
        for (Mon m : pl.bench) {
            opts.add(new Option("Switch in " + describe(m), () -> {
                swapActive(me, m);
                log(name(me) + " switches in " + m.top().name + ".");
            }, Move.of("switch_target", m)));
        }
        if (optional) {
            opts.add(new Option("Don't switch", () -> { }, Move.of("no_switch")));
        }
        ask(me, "Switch your Active Pokemon with a Benched one" + (optional ? "?" : ""), opts);
    }

    private void forceSwitch(int who) {
        Player pl = p[who];
        if (pl.bench.isEmpty() || pl.active == null || pl.active.damage >= pl.active.top().hp) {
            return;
        }
        chooseSwitch(who, "Your Active Pokemon must switch out: choose a Benched Pokemon", "promote");
    }

    /** Knocks out every Pokemon with damage >= HP, hands out Prizes and checks for a winner. */
    private void knockOuts() {
        if (isOver()) {
            return;
        }
        for (int owner : new int[]{opp(tp), tp}) {
            Player pl = p[owner];
            for (Mon m : List.copyOf(pl.inPlay())) {
                if (m.damage < m.top().hp) {
                    continue;
                }
                boolean wasActive = pl.active == m;
                if (wasActive) {
                    pl.active = null;
                } else {
                    pl.bench.remove(m);
                }
                pl.discard.addAll(m.stack);
                pl.discard.addAll(m.energy);
                log(m.top().name + " is Knocked Out!");
                int taker = opp(owner);
                Player tk = p[taker];
                int prizes = Math.min(m.top().prizes, tk.prizes.size());
                for (int i = 0; i < prizes; i++) {
                    tk.hand.add(tk.prizes.remove(tk.prizes.size() - 1));
                }
                log(name(taker) + " takes " + prizes + " Prize card" + (prizes == 1 ? "" : "s") + " (" + tk.prizes.size() + " left).");
                if (wasActive && !pl.bench.isEmpty() && !promotions.contains(owner)) {
                    promotions.addLast(owner);
                }
            }
        }
        boolean[] noPrizes = {p[0].prizes.isEmpty(), p[1].prizes.isEmpty()};
        boolean[] empty = {p[0].inPlay().isEmpty(), p[1].inPlay().isEmpty()};
        boolean[] won = {noPrizes[0] || empty[1], noPrizes[1] || empty[0]};
        if (won[0] && won[1]) {
            win(-1, "Both players met a win condition at once. It's a draw.");
        } else if (won[0] || won[1]) {
            int w = won[0] ? 0 : 1;
            win(w, noPrizes[w]
                    ? name(w) + " took all their Prize cards. " + name(w) + " wins!"
                    : name(opp(w)) + " has no Pokemon left in play. " + name(w) + " wins!");
        }
    }

    private void promoteMenu(int who) {
        Player me = p[who];
        if (me.bench.isEmpty() || me.active != null) {
            promotions.removeFirst();
            return;
        }
        List<Option> opts = new ArrayList<>();
        for (Mon m : me.bench) {
            opts.add(new Option("Send out " + describe(m), () -> {
                promotions.removeFirst();
                me.bench.remove(m);
                me.active = m;
                log(name(who) + " sends out " + m.top().name + ".");
            }, Move.of("promote", m)));
        }
        ask(who, "Your Active Pokemon was Knocked Out: choose a new one", opts);
    }

    // ------------------------------------------------------------------ visual board

    @Override
    public Object focus(Option option) {
        Move mv = option.move();
        return switch (mv.type()) {
            case "attack", "retreat" -> p[tp].active;
            case "energy_target", "energy_move" -> mv.b();
            case "opp_energy" -> holderOf(mv.a());
            case "draw_yes", "draw_no", "done", "setup_done", "end", "cancel", "no_switch" -> null;
            default -> mv.a();
        };
    }

    /** The Pokemon an attached Energy card is on. */
    private Mon holderOf(Object energy) {
        for (Player pl : p) {
            for (Mon m : pl.inPlay()) {
                if (m.energy.contains(energy)) {
                    return m;
                }
            }
        }
        return null;
    }

    @Override
    public Board.CardView cardView(Object focus, int viewer) {
        return focus instanceof Card c ? cardView(c.def).ref(c) : null;
    }

    @Override
    public Board board(int viewer) {
        // viewer -1: someone watching the table, who sees no hidden cards
        Board b = viewer < 0 ? new Board("ptcg", side(0, false), side(1, false))
                : new Board("ptcg", side(viewer, true), side(opp(viewer), false));
        if (viewer < 0) {
            viewer = 0;
        }
        b.phase = turn == 0 ? "Setting up" : "Turn " + turn + " \u00b7 " + name(tp);
        b.help = help(viewer);
        return b;
    }

    private String help(int viewer) {
        com.tablecards.engine.Decision d = pending();
        if (d == null) {
            return "";
        }
        if (d.player() != viewer) {
            return turn == 0 ? "Your opponent is setting up." : "Your opponent's turn.";
        }
        java.util.Set<String> types = new java.util.HashSet<>();
        d.options().forEach(o -> types.add(o.move().type()));
        if (types.contains("setup_active")) return "Click a Basic Pokemon in your hand to make it your Active Pokemon.";
        if (types.contains("setup_bench") || types.contains("setup_done")) return "Click Basic Pokemon in your hand to put them on your Bench, then choose Done.";
        if (types.contains("search_target") || types.contains("retrieve_target")) return "Click a card in the tray to take it.";
        if (types.contains("discard_cost")) return "Click a glowing card in your hand to discard it.";
        if (types.contains("energy_target")) return "Click the Pokemon to attach the Energy to.";
        if (types.contains("promote")) return "Click a Benched Pokemon to make it your new Active Pokemon.";
        if (types.contains("heal_target") || types.contains("switch_target") || types.contains("gust_target")
                || types.contains("retreat_target") || types.contains("snipe_target") || types.contains("scoop_target")
                || types.contains("energy_move") || types.contains("opp_energy")) return "Click a glowing Pokemon to choose it.";
        if (types.contains("end")) return "Click a glowing card to use it. Click your Active Pokemon to attack or retreat; attacking ends your turn.";
        return "Make your choice.";
    }

    private Board.Side side(int who, boolean you) {
        Player pl = p[who];
        Board.Side s = new Board.Side(name(who));
        s.active = turn > 0 && tp == who;
        s.score = String.valueOf(pl.prizes.size());
        s.scoreLabel = "Prizes left";
        s.info.add("Hand " + pl.hand.size());
        s.info.add("Deck " + pl.deck.size());
        if (s.active) {
            if (pl.energyAttached) s.info.add("Energy attached");
            if (pl.supporterPlayed) s.info.add("Supporter played");
            if (pl.retreated) s.info.add("Retreated");
        }
        boolean hidden = !you && !pl.setupDone;
        Board.Zone active = s.zone(new Board.Zone("active", "Active", 0, Board.Align.CENTER, 1, false));
        if (pl.active != null) {
            active.cards.add(hidden ? Board.CardView.hidden("ptcg").ref(pl.active) : monView(pl.active, true));
        }
        Board.Zone bench = s.zone(new Board.Zone("bench", "Bench", 1, Board.Align.CENTER, BENCH, false));
        for (Mon m : pl.bench) {
            bench.cards.add(hidden ? Board.CardView.hidden("ptcg").ref(m) : monView(m, false));
        }
        Board.Zone prizes = s.zone(new Board.Zone("prizes", "Prizes", 0, Board.Align.LEFT, 1, true));
        prizes.count = pl.prizes.size();
        if (!pl.prizes.isEmpty()) {
            prizes.cards.add(Board.CardView.hidden("ptcg"));
        }
        Board.Zone discard = s.zone(new Board.Zone("discard", "Discard", 0, Board.Align.RIGHT, 1, true));
        for (Card c : pl.discard) {
            discard.cards.add(cardView(c.def).ref(c));
        }
        discard.count = pl.discard.size();
        Board.Zone deck = s.zone(new Board.Zone("deck", "Deck", 1, Board.Align.RIGHT, 1, true));
        deck.count = pl.deck.size();
        if (!pl.deck.isEmpty()) {
            deck.cards.add(Board.CardView.hidden("ptcg"));
        }
        s.handCount = pl.hand.size();
        if (you) {
            for (Card c : pl.hand) {
                Card first = pl.hand.stream().filter(x -> x.def == c.def).findFirst().orElse(c);
                s.hand.add(cardView(c.def).ref(c, first));
            }
        }
        return s;
    }

    private Board.CardView monView(Mon m, boolean isActive) {
        PtcgCard t = m.top();
        Board.CardView v = cardView(t);
        v.ref(m, m.stack.get(m.stack.size() - 1)).alias(describe(m), t.summary(), t.name);
        v.stat = m.hpLeft() + "/" + t.hp;
        if (m.damage > 0) {
            v.badge = String.valueOf(m.damage);
        }
        v.tags.addAll(m.conditions());
        if (isActive) {
            if (m.cantAttackTurn == turn || m.blockedTurn == turn) v.tags.add("Can't attack");
            if (m.noRetreatTurn == turn) v.tags.add("Can't retreat");
            if (m.protectedTurn == turn) v.tags.add("Protected");
        }
        for (Card e : m.energy) {
            v.attached.add(cardView(e.def).ref(e));
        }
        List<String> extra = new ArrayList<>();
        extra.add("HP " + m.hpLeft() + " of " + t.hp + (m.damage > 0 ? " (" + m.damage + " damage)" : ""));
        if (m.stack.size() > 1) {
            List<String> names = new ArrayList<>();
            for (Card c : m.stack.subList(0, m.stack.size() - 1)) {
                names.add(c.def.name);
            }
            extra.add("Evolved from " + String.join(" > ", names));
        }
        if (!m.energy.isEmpty()) {
            Map<String, Integer> counts = new HashMap<>();
            for (Card e : m.energy) {
                counts.merge(PtcgCard.typeName(e.def.type), 1, Integer::sum);
            }
            List<String> parts = new ArrayList<>();
            counts.forEach((ty, n) -> parts.add(n + " " + ty));
            Collections.sort(parts);
            extra.add("Energy: " + String.join(", ", parts));
        }
        v.text.addAll(1, extra);
        return v;
    }

    /** The face of a card. */
    static Board.CardView cardView(PtcgCard d) {
        Board.CardView v = new Board.CardView(d.kind == PtcgCard.Kind.ENERGY ? PtcgCard.typeName(d.type) + " Energy" : d.name);
        v.alias(d.summary(), d.name);
        if (!d.image.isEmpty()) {
            v.image = "ptcg:" + d.image;
        }
        switch (d.kind) {
            case POKEMON -> {
                v.frame = "ptcg_" + d.type;
                v.corner = d.hp + " HP";
                v.text.add((d.stage == 0 ? "Basic" : "Stage " + d.stage) + " " + PtcgCard.typeName(d.type) + " Pokemon"
                        + (d.evolvesFromName() != null && d.stage > 0 ? ", evolves from " + d.evolvesFromName() : ""));
                for (Attack at : d.attacks) {
                    v.text.add(at.summary());
                }
                String weak = d.weakness == null ? "none" : PtcgCard.typeName(d.weakness) + (d.weaknessPlus > 0 ? " +" + d.weaknessPlus : " x2");
                String res = d.resistance == null ? "none" : PtcgCard.typeName(d.resistance) + " -" + d.resistanceValue;
                v.text.add("Weakness " + weak + " \u00b7 Resistance " + res + " \u00b7 Retreat " + d.retreat);
                if (d.prizes > 1) {
                    v.text.add("Gives " + d.prizes + " Prizes when Knocked Out.");
                }
            }
            case ENERGY -> {
                v.frame = "ptcg_energy_" + d.type;
                v.text.add("Basic Energy");
            }
            case ITEM, SUPPORTER -> {
                v.frame = "ptcg_trainer";
                v.corner = d.kind == PtcgCard.Kind.ITEM ? "Item" : "Supporter";
                v.text.add("Trainer \u00b7 " + (d.kind == PtcgCard.Kind.ITEM ? "Item" : "Supporter")
                        + (d.aceSpec ? " \u00b7 ACE SPEC" : ""));
                if (!d.text.isEmpty()) {
                    v.text.add(d.text);
                }
            }
        }
        return v;
    }

    // ------------------------------------------------------------------ text view

    String describe(Mon m) {
        StringBuilder b = new StringBuilder(m.top().name)
                .append(" ").append(m.hpLeft()).append("/").append(m.top().hp).append(" HP");
        if (!m.energy.isEmpty()) {
            Map<String, Integer> counts = new HashMap<>();
            for (Card e : m.energy) {
                counts.merge(PtcgCard.typeName(e.def.type), 1, Integer::sum);
            }
            List<String> parts = new ArrayList<>();
            counts.forEach((t, n) -> parts.add(n + " " + t));
            Collections.sort(parts);
            b.append(", energy: ").append(String.join(", ", parts));
        }
        List<String> cond = m.conditions();
        if (!cond.isEmpty()) {
            b.append(" [").append(String.join(", ", cond)).append("]");
        }
        return b.toString();
    }

    @Override
    public List<Section> view(int viewer) {
        int other = opp(viewer);
        List<Section> out = new ArrayList<>();
        out.add(new Section(turn == 0 ? "Setting up" : "Turn " + turn + ": " + name(tp), List.of()));
        out.add(playerSection(other, false));
        out.add(new Section("Opponent's Active", activeLines(other, false)));
        out.add(new Section("Opponent's Bench", benchLines(other)));
        out.add(new Section("Your Active", activeLines(viewer, true)));
        out.add(new Section("Your Bench", benchLines(viewer)));
        List<String> hand = new ArrayList<>();
        for (Card c : p[viewer].hand) {
            hand.add(c.def.summary());
        }
        out.add(new Section("Your hand (" + hand.size() + ")", hand));
        out.add(playerSection(viewer, true));
        return out;
    }

    private Section playerSection(int who, boolean you) {
        Player pl = p[who];
        return new Section((you ? "You" : "Opponent") + ": " + name(who), List.of(
                "Prizes " + pl.prizes.size() + " | Hand " + pl.hand.size() + " | Deck " + pl.deck.size() + " | Discard " + pl.discard.size()));
    }

    private List<String> activeLines(int who, boolean owner) {
        Mon a = p[who].active;
        if (a == null) {
            return List.of(p[who].setupDone || owner ? "none" : "(choosing)");
        }
        if (!p[who].setupDone && !owner) {
            return List.of("(face-down until setup ends)");
        }
        List<String> lines = new ArrayList<>();
        lines.add(describe(a));
        PtcgCard t = a.top();
        String weak = t.weakness == null ? "none" : PtcgCard.typeName(t.weakness) + (t.weaknessPlus > 0 ? " +" + t.weaknessPlus : " x2");
        String res = t.resistance == null ? "none" : PtcgCard.typeName(t.resistance) + " -" + t.resistanceValue;
        lines.add("Weakness " + weak + " | Resistance " + res + " | Retreat " + t.retreat
                + (t.prizes > 1 ? " | worth " + t.prizes + " Prizes" : ""));
        for (Attack at : t.attacks) {
            lines.add("  " + at.summary());
        }
        return lines;
    }

    private List<String> benchLines(int who) {
        List<String> lines = new ArrayList<>();
        for (Mon m : p[who].bench) {
            lines.add(describe(m));
        }
        if (lines.isEmpty()) {
            lines.add("empty");
        }
        return lines;
    }
}
