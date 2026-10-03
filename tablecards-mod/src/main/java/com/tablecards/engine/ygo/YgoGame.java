package com.tablecards.engine.ygo;

import com.tablecards.engine.BaseGame;
import com.tablecards.engine.Board;
import com.tablecards.engine.Bot;
import com.tablecards.engine.Move;
import com.tablecards.engine.Option;
import com.tablecards.engine.Section;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Yu-Gi-Oh! core rules for two players.
 *
 * Turn: Draw (skipped on the very first turn), Main Phase 1, Battle Phase (not on the very first
 * turn), Main Phase 2, End (hand limit 6). One Normal Summon or Set per turn; levels 5-6 need one
 * tribute, 7+ need two. Flip Summon and one position change per monster per turn (not the turn it
 * arrived). Spells: normal and equip. Traps: activated from the turn after they were set, in
 * response to an attack or a Normal Summon (a chain of one). 8000 LP; you lose at 0 LP or when you
 * must draw from an empty deck. Monster and spell/trap zones hold 5 cards each.
 */
public final class YgoGame extends BaseGame {
    public static final int START_LP = 8000;
    static final int ZONES = 5;
    static final int HAND_LIMIT = 6;

    enum Phase { MAIN1, BATTLE, MAIN2 }

    /** A physical card; {@code owner} is whose deck it came from (it always goes back to its owner's GY). */
    static final class Card {
        final YgoCard def;
        final int owner;
        Card(YgoCard def, int owner) { this.def = def; this.owner = owner; }
    }

    static final class Monster {
        final Card card;
        boolean faceUp;
        boolean attackPos;
        int atkBonus;
        int defBonus;
        int arrivedTurn;
        boolean attacked;
        boolean posChanged;
        Monster(Card card) { this.card = card; }
        int atk() { return Math.max(0, card.def.atk + atkBonus); }
        int def() { return Math.max(0, card.def.def + defBonus); }
    }

    static final class SpellTrap {
        final Card card;
        boolean faceUp;
        int setTurn;
        Monster equippedTo;
        int appliedAtk;
        int appliedDef;
        SpellTrap(Card card) { this.card = card; }
    }

    static final class Player {
        int lp = START_LP;
        final List<Card> deck = new ArrayList<>();
        final List<Card> hand = new ArrayList<>();
        final List<Card> gy = new ArrayList<>();
        final List<Monster> monsters = new ArrayList<>();
        final List<SpellTrap> st = new ArrayList<>();
    }

    final Player[] p = {new Player(), new Player()};
    int turn = 1;
    int tp;
    Phase phase = Phase.MAIN1;
    boolean normalSummoned;

    public YgoGame(String[] names, List<YgoCard> deck0, List<YgoCard> deck1, long seed) {
        super(names, seed);
        List<List<YgoCard>> decks = List.of(deck0, deck1);
        for (int i = 0; i < 2; i++) {
            for (YgoCard c : decks.get(i)) {
                p[i].deck.add(new Card(c, i));
            }
            Collections.shuffle(p[i].deck, rng);
            for (int k = 0; k < 5; k++) {
                draw(i);
            }
        }
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

    // ------------------------------------------------------------------ turn structure

    @Override
    protected void advance() {
        if (phase == Phase.BATTLE) {
            battleMenu();
        } else {
            mainMenu();
        }
    }

    private boolean draw(int player) {
        Player pl = p[player];
        if (pl.deck.isEmpty()) {
            win(opp(player), name(player) + " has no cards left to draw. " + name(opp(player)) + " wins!");
            return false;
        }
        pl.hand.add(pop(pl.deck));
        return true;
    }

    private void endTurn() {
        Player me = p[tp];
        if (me.hand.size() > HAND_LIMIT) {
            List<Option> opts = new ArrayList<>();
            for (Card c : me.hand) {
                opts.add(new Option("Discard " + c.def.summary(), () -> {
                    me.hand.remove(c);
                    me.gy.add(c);
                    log(name(tp) + " discards " + c.def.name + " (hand limit).");
                    endTurn();
                }, Move.of("discard", c)));
            }
            ask(tp, "Hand limit is " + HAND_LIMIT + ": discard a card", opts);
            return;
        }
        tp = opp(tp);
        turn++;
        normalSummoned = false;
        phase = Phase.MAIN1;
        for (Monster m : p[tp].monsters) {
            m.attacked = false;
            m.posChanged = false;
        }
        log("Turn " + turn + ": " + name(tp) + ".");
        draw(tp);
    }

    // ------------------------------------------------------------------ main phase

    private void mainMenu() {
        Player me = p[tp];
        List<Option> opts = new ArrayList<>();

        if (!normalSummoned) {
            for (Card c : distinct(me.hand)) {
                if (!c.def.isMonster()) {
                    continue;
                }
                int t = c.def.tributesNeeded();
                if (me.monsters.size() < t || me.monsters.size() - t >= ZONES) {
                    continue;
                }
                String tribute = t > 0 ? " (tribute " + t + ")" : "";
                opts.add(new Option("Summon " + c.def.summary() + tribute,
                        () -> startSummon(c, true), Move.of("summon", c, null, t)));
                opts.add(new Option("Set face-down " + c.def.summary() + tribute,
                        () -> startSummon(c, false), Move.of("set_monster", c, null, t)));
            }
        }
        for (Card c : distinct(me.hand)) {
            if (c.def.isMonster() || me.st.size() >= ZONES) {
                continue;
            }
            if (c.def.kind == YgoCard.Kind.SPELL && canActivate(tp, c.def)) {
                opts.add(new Option("Activate " + c.def.summary(), () -> activateSpell(c, null), Move.of("spell", c)));
            }
            opts.add(new Option("Set " + c.def.summary(), () -> setSpellTrap(c), Move.of("set_st", c)));
        }
        for (SpellTrap s : List.copyOf(me.st)) {
            if (!s.faceUp && s.card.def.kind == YgoCard.Kind.SPELL && canActivate(tp, s.card.def) && hasTargetBesides(s)) {
                opts.add(new Option("Activate set " + s.card.def.summary(), () -> activateSpell(s.card, s), Move.of("spell", s.card)));
            }
        }
        for (Monster m : me.monsters) {
            if (m.arrivedTurn == turn) {
                continue;
            }
            if (!m.faceUp) {
                opts.add(new Option("Flip Summon " + m.card.def.summary(), () -> flipSummon(m), Move.of("flip", m)));
            } else if (!m.posChanged) {
                String to = m.attackPos ? "defense" : "attack";
                opts.add(new Option("Switch " + m.card.def.name + " to " + to + " position", () -> {
                    m.attackPos = !m.attackPos;
                    m.posChanged = true;
                    log(name(tp) + " switches " + m.card.def.name + " to " + to + " position.");
                }, Move.of("position", m)));
            }
        }
        if (phase == Phase.MAIN1 && turn > 1) {
            opts.add(new Option("Go to Battle Phase", () -> {
                phase = Phase.BATTLE;
                log(name(tp) + " enters the Battle Phase.");
            }, Move.of("battle")));
        }
        opts.add(new Option("End turn", this::endTurn, Move.of("end")));
        ask(tp, (phase == Phase.MAIN1 ? "Main Phase 1" : "Main Phase 2") + ": choose an action", opts);
    }

    /** One entry per card definition, so three copies don't make three identical buttons. */
    private static List<Card> distinct(List<Card> cards) {
        List<Card> out = new ArrayList<>();
        List<YgoCard> seen = new ArrayList<>();
        for (Card c : cards) {
            if (!seen.contains(c.def)) {
                seen.add(c.def);
                out.add(c);
            }
        }
        return out;
    }

    private void startSummon(Card c, boolean faceUpAttack) {
        int needed = c.def.tributesNeeded();
        chooseTributes(c, faceUpAttack, needed);
    }

    private void chooseTributes(Card c, boolean faceUpAttack, int remaining) {
        Player me = p[tp];
        if (remaining == 0) {
            completeSummon(c, faceUpAttack);
            return;
        }
        List<Option> opts = new ArrayList<>();
        for (Monster m : me.monsters) {
            opts.add(new Option("Tribute " + describe(m, true), () -> {
                destroy(tp, m, "tributed");
                chooseTributes(c, faceUpAttack, remaining - 1);
            }, Move.of("tribute", m)));
        }
        ask(tp, "Choose a monster to tribute for " + c.def.name + " (" + remaining + " more)", opts);
    }

    private void completeSummon(Card c, boolean faceUpAttack) {
        Player me = p[tp];
        me.hand.remove(c);
        Monster m = new Monster(c);
        m.faceUp = faceUpAttack;
        m.attackPos = faceUpAttack;
        m.arrivedTurn = turn;
        me.monsters.add(m);
        normalSummoned = true;
        if (faceUpAttack) {
            log(name(tp) + " Normal Summons " + c.def.summary() + ".");
            offerSummonResponse(m, false);
        } else {
            log(name(tp) + " sets a monster face-down.");
        }
    }

    private void flipSummon(Monster m) {
        m.faceUp = true;
        m.attackPos = true;
        m.posChanged = true;
        log(name(tp) + " Flip Summons " + m.card.def.summary() + ".");
        offerSummonResponse(m, true);
    }

    private void setSpellTrap(Card c) {
        Player me = p[tp];
        me.hand.remove(c);
        SpellTrap s = new SpellTrap(c);
        s.setTurn = turn;
        me.st.add(s);
        log(name(tp) + " sets a card in the spell & trap zone.");
    }

    // ------------------------------------------------------------------ spells

    boolean canActivate(int player, YgoCard def) {
        Player me = p[player];
        Player op = p[opp(player)];
        return switch (def.effect) {
            case "destroy_one", "destroy_opp_all" -> !op.monsters.isEmpty();
            case "destroy_lowest" -> op.monsters.stream().anyMatch(m -> m.faceUp);
            case "destroy_all", "destroy_any" -> !op.monsters.isEmpty() || !me.monsters.isEmpty();
            case "draw" -> me.deck.size() >= Math.max(1, def.value);
            case "gain", "burn" -> true;
            // a card can't target itself; for set cards the caller passes the zone count without it
            case "destroy_st" -> !op.st.isEmpty() || !me.st.isEmpty();
            case "destroy_st_opp_all" -> !op.st.isEmpty();
            case "destroy_st_all" -> !op.st.isEmpty() || !me.st.isEmpty();
            case "revive" -> me.monsters.size() < ZONES && (me.gy.stream().anyMatch(c -> c.def.isMonster())
                    || ("either".equals(def.scope) && op.gy.stream().anyMatch(c -> c.def.isMonster())));
            case "equip" -> equipTargets(def).size() > 0;
            default -> false;
        };
    }

    /** Spell/Trap-destroying spells need a card to destroy other than themselves. */
    private boolean hasTargetBesides(SpellTrap self) {
        String e = self.card.def.effect;
        if (!e.equals("destroy_st") && !e.equals("destroy_st_all")) {
            return true;
        }
        return p[0].st.size() + p[1].st.size() > 1;
    }

    /** Face-up monsters on either side this equip spell may be equipped to. */
    List<Monster> equipTargets(YgoCard def) {
        List<Monster> out = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            for (Monster m : p[i].monsters) {
                if (m.faceUp && (!def.equipStrict || def.equipMatches(m.card.def))) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** Activates a spell from the hand ({@code fromField} null) or from a set card. */
    private void activateSpell(Card c, SpellTrap fromField) {
        Player me = p[tp];
        Player op = p[opp(tp)];
        YgoCard d = c.def;
        // the card stays where it is until the effect resolves, so cancelling a target choice is free
        Runnable announce = () -> log(name(tp) + " activates " + d.name + ".");
        Runnable toGraveyard = () -> {
            if (fromField != null) {
                me.st.remove(fromField);
            } else {
                me.hand.remove(c);
            }
            me.gy.add(c);
        };
        Option cancel = new Option("Cancel", () -> { }, Move.of("cancel"));
        switch (d.effect) {
            case "destroy_one" -> {
                List<Option> opts = new ArrayList<>();
                for (Monster m : op.monsters) {
                    opts.add(new Option("Destroy " + describe(m, false), () -> {
                        announce.run();
                        toGraveyard.run();
                        destroy(opp(tp), m, "destroyed by " + d.name);
                    }, Move.of("destroy_target", m)));
                }
                opts.add(cancel);
                ask(tp, d.name + ": choose a monster to destroy", opts);
            }
            case "destroy_any" -> {
                List<Option> opts = new ArrayList<>();
                for (int side = 0; side < 2; side++) {
                    final int owner = side;
                    for (Monster m : p[side].monsters) {
                        opts.add(new Option("Destroy " + (owner == tp ? "your " : "") + describe(m, owner == tp), () -> {
                            announce.run();
                            toGraveyard.run();
                            destroy(owner, m, "destroyed by " + d.name);
                        }, Move.of("destroy_target", m, owner == tp ? "own" : "opp")));
                    }
                }
                opts.add(cancel);
                ask(tp, d.name + ": choose a monster to destroy", opts);
            }
            case "destroy_opp_all" -> {
                announce.run();
                toGraveyard.run();
                for (Monster m : List.copyOf(op.monsters)) {
                    destroy(opp(tp), m, "destroyed by " + d.name);
                }
            }
            case "destroy_lowest" -> {
                int low = op.monsters.stream().filter(m -> m.faceUp).mapToInt(Monster::atk).min().orElse(0);
                List<Option> opts = new ArrayList<>();
                for (Monster m : op.monsters) {
                    if (m.faceUp && m.atk() == low) {
                        opts.add(new Option("Destroy " + describe(m, false), () -> {
                            announce.run();
                            toGraveyard.run();
                            destroy(opp(tp), m, "destroyed by " + d.name);
                        }, Move.of("destroy_target", m)));
                    }
                }
                ask(tp, d.name + ": destroy the face-up monster with the lowest ATK", opts);
            }
            case "destroy_st_opp_all", "destroy_st_all" -> {
                announce.run();
                toGraveyard.run();
                for (int i = 0; i < 2; i++) {
                    if (i == tp && d.effect.equals("destroy_st_opp_all")) {
                        continue;
                    }
                    for (SpellTrap s : List.copyOf(p[i].st)) {
                        destroySpellTrap(i, s);
                    }
                }
            }
            case "destroy_all" -> {
                announce.run();
                toGraveyard.run();
                for (int i = 0; i < 2; i++) {
                    for (Monster m : List.copyOf(p[i].monsters)) {
                        destroy(i, m, "destroyed by " + d.name);
                    }
                }
            }
            case "draw" -> {
                announce.run();
                toGraveyard.run();
                for (int i = 0; i < d.value && !isOver(); i++) {
                    draw(tp);
                }
                log(name(tp) + " draws " + d.value + ".");
            }
            case "gain" -> {
                announce.run();
                toGraveyard.run();
                me.lp += d.value;
                log(name(tp) + " gains " + d.value + " LP.");
            }
            case "burn" -> {
                announce.run();
                toGraveyard.run();
                damage(opp(tp), d.value, d.name);
            }
            case "destroy_st" -> {
                List<Option> opts = new ArrayList<>();
                for (int side = 0; side < 2; side++) {
                    final int owner = side;
                    for (SpellTrap s : p[side].st) {
                        if (s == fromField) {
                            continue;
                        }
                        String label = owner == tp ? "your " + s.card.def.name : s.faceUp ? s.card.def.name : "a set card";
                        opts.add(new Option("Destroy " + label, () -> {
                            announce.run();
                            toGraveyard.run();
                            destroySpellTrap(owner, s);
                        }, Move.of("st_target", s, owner == tp ? "own" : "opp")));
                    }
                }
                opts.add(cancel);
                ask(tp, d.name + ": choose a spell or trap to destroy", opts);
            }
            case "revive" -> {
                List<Option> opts = new ArrayList<>();
                List<Card> pool = new ArrayList<>(distinct(me.gy));
                if ("either".equals(d.scope)) {
                    pool.addAll(distinct(op.gy));
                }
                for (Card g : pool) {
                    if (!g.def.isMonster()) {
                        continue;
                    }
                    boolean mine = me.gy.contains(g);
                    opts.add(new Option("Special Summon " + g.def.summary() + (mine ? "" : " from your opponent's GY"), () -> {
                        announce.run();
                        if (!me.gy.remove(g)) {
                            op.gy.remove(g);
                        }
                        Monster m = new Monster(g);
                        m.faceUp = true;
                        m.attackPos = true;
                        m.arrivedTurn = turn;
                        me.monsters.add(m);
                        log(name(tp) + " Special Summons " + g.def.name + " from the graveyard.");
                        toGraveyard.run();
                    }, Move.of("revive_target", g)));
                }
                opts.add(cancel);
                ask(tp, d.name + ": choose a monster in your graveyard", opts);
            }
            case "equip" -> {
                List<Option> opts = new ArrayList<>();
                for (Monster m : equipTargets(d)) {
                    boolean mine = me.monsters.contains(m);
                    opts.add(new Option("Equip to " + (mine ? "" : "your opponent's ") + describe(m, mine), () -> {
                        announce.run();
                        SpellTrap s = fromField != null ? fromField : new SpellTrap(c);
                        s.faceUp = true;
                        s.equippedTo = m;
                        if (fromField == null) {
                            me.hand.remove(c);
                            me.st.add(s);
                        }
                        boolean bonus = (d.equipRace == null && d.equipAttr == null) || d.equipMatches(m.card.def);
                        s.appliedAtk = bonus ? d.equipAtk : 0;
                        s.appliedDef = bonus ? d.equipDef : 0;
                        m.atkBonus += s.appliedAtk;
                        m.defBonus += s.appliedDef;
                        log(d.name + " is equipped to " + m.card.def.name + (bonus ? "." : " (no effect on it)."));
                    }, Move.of("equip_target", m, mine ? "own" : "opp")));
                }
                opts.add(cancel);
                ask(tp, d.name + ": choose a monster to equip", opts);
            }
            default -> {
                announce.run();
                toGraveyard.run();
            }
        }
    }

    // ------------------------------------------------------------------ battle

    private void battleMenu() {
        Player me = p[tp];
        List<Option> opts = new ArrayList<>();
        for (Monster m : me.monsters) {
            if (m.faceUp && m.attackPos && !m.attacked) {
                opts.add(new Option("Attack with " + describe(m, true), () -> chooseAttackTarget(m), Move.of("attack", m)));
            }
        }
        opts.add(new Option("Go to Main Phase 2", () -> phase = Phase.MAIN2, Move.of("main2")));
        opts.add(new Option("End turn", this::endTurn, Move.of("end")));
        ask(tp, "Battle Phase: choose an attacker", opts);
    }

    private void chooseAttackTarget(Monster attacker) {
        Player op = p[opp(tp)];
        List<Option> opts = new ArrayList<>();
        if (op.monsters.isEmpty()) {
            opts.add(new Option("Attack directly", () -> declareAttack(attacker, null), Move.of("target", attacker, null)));
        } else {
            for (Monster t : op.monsters) {
                opts.add(new Option("Attack " + describe(t, false), () -> declareAttack(attacker, t), Move.of("target", attacker, t)));
            }
        }
        opts.add(new Option("Cancel", () -> { }, Move.of("cancel")));
        ask(tp, "Choose a target for " + attacker.card.def.name, opts);
    }

    private void declareAttack(Monster attacker, Monster target) {
        attacker.attacked = true;
        int defender = opp(tp);
        log(name(tp) + "'s " + attacker.card.def.name + " attacks "
                + (target == null ? name(defender) + " directly" : describe(target, false)) + ".");
        List<SpellTrap> traps = readyTraps(defender, "negate_attack", "destroy_attacker", "mirror_force", "magic_cylinder");
        if (traps.isEmpty()) {
            resolveAttack(attacker, target);
            return;
        }
        List<Option> opts = new ArrayList<>();
        for (SpellTrap s : traps) {
            opts.add(new Option("Activate " + s.card.def.summary(), () -> {
                activateTrap(defender, s);
                YgoCard t = s.card.def;
                switch (t.effect) {
                    case "negate_attack" -> {
                        log("The attack is negated.");
                        if (t.endBattle) {
                            log("The Battle Phase ends.");
                            phase = Phase.MAIN2;
                        }
                    }
                    case "magic_cylinder" -> {
                        log("The attack is negated.");
                        damage(tp, attacker.atk(), t.name);
                    }
                    case "mirror_force" -> {
                        for (Monster m : List.copyOf(p[tp].monsters)) {
                            if (m.attackPos) {
                                destroy(tp, m, "destroyed by " + t.name);
                            }
                        }
                    }
                    default -> {
                        destroy(tp, attacker, "destroyed by " + t.name);
                        if (t.burn > 0) {
                            damage(tp, t.burn, t.name);
                        }
                    }
                }
            }, Move.of("trap_response", s, attacker)));
        }
        opts.add(new Option("Don't respond", () -> resolveAttack(attacker, target), Move.of("no_response", attacker, target)));
        ask(defender, name(tp) + "'s " + attacker.card.def.name + " (" + attacker.atk() + " ATK) is attacking"
                + (target == null ? " you directly" : " " + target.card.def.name) + ". Respond?", opts);
    }

    private void resolveAttack(Monster attacker, Monster target) {
        int defender = opp(tp);
        if (!p[tp].monsters.contains(attacker)) {
            return;
        }
        if (target == null) {
            damage(defender, attacker.atk(), attacker.card.def.name);
            return;
        }
        if (!p[defender].monsters.contains(target)) {
            log("The attack target is gone.");
            return;
        }
        if (!target.faceUp) {
            target.faceUp = true;
            log("The face-down monster is " + target.card.def.summary() + ".");
        }
        int a = attacker.atk();
        if (target.attackPos) {
            int t = target.atk();
            if (a > t) {
                destroy(defender, target, "destroyed in battle");
                damage(defender, a - t, "battle");
            } else if (a < t) {
                destroy(tp, attacker, "destroyed in battle");
                damage(tp, t - a, "battle");
            } else {
                destroy(tp, attacker, "destroyed in battle");
                destroy(defender, target, "destroyed in battle");
            }
        } else {
            int d = target.def();
            if (a > d) {
                destroy(defender, target, "destroyed in battle");
            } else if (a < d) {
                damage(tp, d - a, "battle");
            } else {
                log("Neither monster is destroyed.");
            }
        }
    }

    private void offerSummonResponse(Monster summoned, boolean flip) {
        int responder = opp(tp);
        List<SpellTrap> traps = new ArrayList<>();
        for (SpellTrap s : readyTraps(responder, "destroy_summoned")) {
            if (summoned.atk() >= s.card.def.value && (!flip || s.card.def.flipToo)) {
                traps.add(s);
            }
        }
        if (traps.isEmpty()) {
            return;
        }
        List<Option> opts = new ArrayList<>();
        for (SpellTrap s : traps) {
            opts.add(new Option("Activate " + s.card.def.summary(), () -> {
                activateTrap(responder, s);
                destroy(tp, summoned, "destroyed by " + s.card.def.name);
            }, Move.of("trap_response", s, summoned)));
        }
        opts.add(new Option("Don't respond", () -> { }, Move.of("no_response", summoned, null)));
        ask(responder, name(tp) + " summoned " + summoned.card.def.summary() + ". Respond?", opts);
    }

    /** Set traps of {@code player} with one of {@code effects} that were set before this turn. */
    private List<SpellTrap> readyTraps(int player, String... effects) {
        List<SpellTrap> out = new ArrayList<>();
        for (SpellTrap s : p[player].st) {
            if (s.faceUp || s.card.def.kind != YgoCard.Kind.TRAP || s.setTurn >= turn) {
                continue;
            }
            for (String e : effects) {
                if (s.card.def.effect.equals(e)) {
                    out.add(s);
                    break;
                }
            }
        }
        return out;
    }

    private void activateTrap(int player, SpellTrap s) {
        p[player].st.remove(s);
        p[s.card.owner].gy.add(s.card);
        log(name(player) + " activates " + s.card.def.name + ".");
    }

    // ------------------------------------------------------------------ damage and destruction

    private void damage(int player, int amount, String source) {
        if (amount <= 0) {
            return;
        }
        p[player].lp = Math.max(0, p[player].lp - amount);
        log(name(player) + " takes " + amount + " damage (" + source + "), LP " + p[player].lp + ".");
        if (p[player].lp == 0) {
            win(opp(player), name(player) + "'s LP reached 0. " + name(opp(player)) + " wins!");
        }
    }

    private void destroy(int owner, Monster m, String how) {
        Player pl = p[owner];
        if (!pl.monsters.remove(m)) {
            return;
        }
        p[m.card.owner].gy.add(m.card);
        log(m.card.def.name + " is " + how + ".");
        for (int i = 0; i < 2; i++) {
            for (SpellTrap s : List.copyOf(p[i].st)) {
                if (s.equippedTo == m) {
                    p[i].st.remove(s);
                    p[s.card.owner].gy.add(s.card);
                }
            }
        }
    }

    private void destroySpellTrap(int owner, SpellTrap s) {
        Player pl = p[owner];
        if (!pl.st.remove(s)) {
            return;
        }
        p[s.card.owner].gy.add(s.card);
        if (s.equippedTo != null) {
            s.equippedTo.atkBonus -= s.appliedAtk;
            s.equippedTo.defBonus -= s.appliedDef;
        }
        log(s.card.def.name + " is destroyed.");
    }

    // ------------------------------------------------------------------ visual board

    @Override
    public Object focus(Option option) {
        Move m = option.move();
        return switch (m.type()) {
            case "target" -> m.b();
            case "no_response", "battle", "main2", "end", "cancel" -> null;
            default -> m.a();
        };
    }

    @Override
    public Board.CardView cardView(Object focus, int viewer) {
        return focus instanceof Card c ? cardView(c) : null;
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
        Board b = new Board("ygo", side(viewer, true), side(opp(viewer), false));
        b.phase = "Turn " + turn + " \u00b7 " + name(tp) + " \u00b7 " + phaseName();
        b.help = help(viewer);
        return b;
    }

    private String help(int viewer) {
        com.tablecards.engine.Decision d = pending();
        if (d == null) {
            return "";
        }
        if (d.player() != viewer) {
            return tp == viewer ? "Waiting for your opponent to respond." : "Your opponent's turn. Your set traps light up when you can activate them.";
        }
        java.util.Set<String> types = new java.util.HashSet<>();
        d.options().forEach(o -> types.add(o.move().type()));
        if (types.contains("trap_response")) return "Click your glowing trap to activate it, or choose Don't respond.";
        if (types.contains("target")) return "Click the glowing monster to attack it.";
        if (types.contains("tribute")) return "Click one of your glowing monsters to tribute it.";
        if (types.contains("revive_target")) return "Click a monster in the tray to Special Summon it.";
        if (types.contains("destroy_target") || types.contains("st_target") || types.contains("equip_target")) return "Click a glowing card to choose it.";
        if (types.contains("discard")) return "Click a card in your hand to discard it.";
        if (phase == Phase.BATTLE) return "Click one of your glowing monsters to attack with it.";
        return "Click a glowing card to use it. Set traps can be activated from your next turn, when your opponent attacks or summons.";
    }

    private Board.Side side(int who, boolean you) {
        Player pl = p[who];
        Board.Side s = new Board.Side(name(who));
        s.active = tp == who;
        s.score = String.valueOf(pl.lp);
        s.scoreLabel = "LP";
        s.info.add("Hand " + pl.hand.size());
        s.info.add("Deck " + pl.deck.size());

        Board.Zone mz = s.zone(new Board.Zone("monsters", "Monster Zone", 0, Board.Align.CENTER, ZONES, false));
        for (Monster m : pl.monsters) {
            Board.CardView v;
            if (!m.faceUp && !you) {
                v = Board.CardView.hidden("ygo");
            } else {
                v = cardView(m.card);
                v.faceDown = !m.faceUp;
                v.peek = !m.faceUp;
                v.stat = m.atk() + "/" + m.def();
                if (m.atkBonus != 0 || m.defBonus != 0) {
                    v.tags.add("Equipped");
                }
                if (!m.faceUp) {
                    v.tags.add("Set");
                }
            }
            v.sideways = !m.attackPos;
            v.tags.add(0, m.attackPos ? "ATK position" : "DEF position");
            v.ref(m, m.card).alias(describe(m, you), describe(m, !you), m.card.def.summary(), m.card.def.name);
            if (you && m.faceUp) {
                if (m.attacked) v.tags.add("Attacked");
            }
            mz.cards.add(v);
        }
        Board.Zone sz = s.zone(new Board.Zone("spells", "Spell & Trap Zone", 1, Board.Align.CENTER, ZONES, false));
        for (SpellTrap st : pl.st) {
            Board.CardView v;
            if (!st.faceUp && !you) {
                v = Board.CardView.hidden("ygo");
            } else {
                v = cardView(st.card);
                v.faceDown = !st.faceUp;
                v.peek = !st.faceUp;
                if (st.equippedTo != null) {
                    v.tags.add("Equipped to " + st.equippedTo.card.def.name);
                }
                if (!st.faceUp) {
                    v.tags.add(st.setTurn >= turn ? "Set this turn" : "Set");
                }
            }
            v.ref(st, st.card).alias(st.card.def.summary(), "your " + st.card.def.name, st.card.def.name, "a set card");
            sz.cards.add(v);
        }
        Board.Zone gy = s.zone(new Board.Zone("gy", "Graveyard", 0, Board.Align.RIGHT, 1, true));
        for (Card c : pl.gy) {
            gy.cards.add(cardView(c));
        }
        gy.count = pl.gy.size();
        Board.Zone deck = s.zone(new Board.Zone("deck", "Deck", 1, Board.Align.RIGHT, 1, true));
        deck.count = pl.deck.size();
        if (!pl.deck.isEmpty()) {
            deck.cards.add(Board.CardView.hidden("ygo"));
        }

        s.handCount = pl.hand.size();
        if (you) {
            for (Card c : pl.hand) {
                Card first = pl.hand.stream().filter(x -> x.def == c.def).findFirst().orElse(c);
                s.hand.add(cardView(c).ref(first));
            }
        }
        return s;
    }

    /** The face of a card. */
    Board.CardView cardView(Card c) {
        YgoCard d = c.def;
        Board.CardView v = new Board.CardView(d.name);
        v.ref(c).alias(d.summary(), d.name);
        if (!d.codes.isEmpty() && d.codes.get(0).matches("\\d{1,10}")) {
            v.image = "ygo:" + d.codes.get(0);
        }
        switch (d.kind) {
            case MONSTER -> {
                v.frame = "ygo_monster";
                v.corner = "Lv" + d.level;
                v.stat = d.atk + "/" + d.def;
                v.text.add("Normal Monster \u00b7 Level " + d.level);
                if (!d.race.isEmpty()) {
                    v.text.add(d.attribute + " \u00b7 " + d.race);
                }
                v.text.add("ATK " + d.atk + " / DEF " + d.def);
                if (d.level >= 5) {
                    v.text.add("Needs " + d.tributesNeeded() + " tribute" + (d.tributesNeeded() > 1 ? "s" : "") + " to summon.");
                }
            }
            case SPELL -> {
                v.frame = "ygo_spell";
                v.text.add(d.quick ? "Quick-Play Spell" : d.effect.equals("equip") ? "Equip Spell" : "Spell Card");
            }
            case TRAP -> {
                v.frame = "ygo_trap";
                v.text.add("Trap Card");
            }
        }
        if (!d.text.isEmpty()) {
            v.text.add(d.text);
        }
        return v;
    }

    // ------------------------------------------------------------------ text view

    String describe(Monster m, boolean owner) {
        if (!m.faceUp && !owner) {
            return "face-down monster (defense)";
        }
        String pos = m.attackPos ? "attack" : "defense";
        String hidden = m.faceUp ? "" : ", face-down";
        return m.card.def.name + " " + m.atk() + "/" + m.def() + " (" + pos + hidden + ")";
    }

    @Override
    public List<Section> view(int viewer) {
        int other = opp(viewer);
        List<Section> out = new ArrayList<>();
        String phaseName = switch (phase) {
            case MAIN1 -> "Main Phase 1";
            case BATTLE -> "Battle Phase";
            case MAIN2 -> "Main Phase 2";
        };
        out.add(new Section("Turn " + turn + ": " + name(tp) + ", " + phaseName, List.of()));
        out.add(playerSection(other, false));
        out.add(new Section("Opponent's monsters", monsterLines(other, false)));
        out.add(new Section("Opponent's spells & traps", stLines(other, false)));
        out.add(new Section("Your monsters", monsterLines(viewer, true)));
        out.add(new Section("Your spells & traps", stLines(viewer, true)));
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
        String title = (you ? "You" : "Opponent") + ": " + name(who);
        List<String> lines = new ArrayList<>();
        lines.add("LP " + pl.lp + " | Hand " + pl.hand.size() + " | Deck " + pl.deck.size());
        List<String> gy = new ArrayList<>();
        for (Card c : pl.gy) {
            gy.add(c.def.name);
        }
        lines.add("Graveyard: " + (gy.isEmpty() ? "empty" : String.join(", ", gy)));
        return new Section(title, lines);
    }

    private List<String> monsterLines(int who, boolean owner) {
        List<String> lines = new ArrayList<>();
        for (Monster m : p[who].monsters) {
            lines.add(describe(m, owner));
        }
        if (lines.isEmpty()) {
            lines.add("none");
        }
        return lines;
    }

    private List<String> stLines(int who, boolean owner) {
        List<String> lines = new ArrayList<>();
        for (SpellTrap s : p[who].st) {
            if (s.faceUp) {
                lines.add(s.card.def.name + (s.equippedTo != null ? " (equipped to " + s.equippedTo.card.def.name + ")" : ""));
            } else {
                lines.add(owner ? s.card.def.summary() + " (set)" : "Set card");
            }
        }
        if (lines.isEmpty()) {
            lines.add("none");
        }
        return lines;
    }
}
