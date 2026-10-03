package com.shandalar.engine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A two-player duel. Turn structure: untap, draw, main, combat, main, discard, cleanup.
 * Spells resolve immediately (no stack or priority yet) and are cast in main phases only.
 */
public final class Game {
    public static final int HAND_LIMIT = 7;
    public static final int STARTING_HAND = 7;

    public final Player[] players = new Player[2];
    public final Agent[] agents = new Agent[2];
    public final Random rng;
    public int turn;
    public int active;
    public boolean over;
    public Player winner;
    private final PrintStream log;

    public Game(String name0, List<CardDef> deck0, Agent agent0,
                String name1, List<CardDef> deck1, Agent agent1,
                long seed, PrintStream log) {
        this.players[0] = new Player(name0, deck0);
        this.players[1] = new Player(name1, deck1);
        this.agents[0] = agent0;
        this.agents[1] = agent1;
        this.rng = new Random(seed);
        this.log = log;
    }

    public Player opponent(Player p) {
        return players[0] == p ? players[1] : players[0];
    }

    private void log(String msg) {
        if (log != null) {
            log.println(msg);
        }
    }

    /** Plays until someone wins or maxTurns passes. Returns the winner, or null for a draw. */
    public Player run(int maxTurns) {
        for (Player p : players) {
            Collections.shuffle(p.library, rng);
            for (int i = 0; i < STARTING_HAND; i++) {
                p.draw();
            }
        }
        while (!over && turn < maxTurns) {
            playTurn();
            active = 1 - active;
        }
        return winner;
    }

    private void playTurn() {
        turn++;
        Player me = players[active];
        Player opp = opponent(me);
        log("=== Turn " + turn + ": " + me.name + " (life " + me.life + ", opp " + opp.life + ") ===");

        for (Permanent p : me.battlefield) {
            p.tapped = false;
            p.summoningSick = false;
        }
        me.landPlayed = false;
        if (turn > 1) {
            me.draw();
        }
        checkState();
        if (over) {
            return;
        }

        mainPhase(me);
        if (over) {
            return;
        }
        combat(me, opp);
        if (over) {
            return;
        }
        mainPhase(me);
        if (over) {
            return;
        }

        while (me.hand.size() > HAND_LIMIT) {
            CardDef d = agents[active].chooseDiscard(this, me);
            me.hand.remove(d);
            me.graveyard.add(d);
            log(me.name + " discards " + d);
        }
        for (Player p : players) {
            for (Permanent perm : p.battlefield) {
                perm.damage = 0;
                perm.pumpPower = 0;
                perm.pumpToughness = 0;
            }
        }
    }

    // ---------------------------------------------------------------- main phase

    private void mainPhase(Player me) {
        for (int guard = 0; guard < 60 && !over; guard++) {
            List<Action> options = legalActions(me);
            if (options.isEmpty()) {
                return;
            }
            Action a = agents[active].chooseMainAction(this, me, options);
            if (a == null) {
                return;
            }
            perform(me, a);
        }
    }

    public List<Action> legalActions(Player me) {
        List<Action> out = new ArrayList<>();
        Set<CardDef> seen = new HashSet<>();
        for (CardDef c : me.hand) {
            if (!seen.add(c)) {
                continue; // identical copies give identical options
            }
            if (c.type == CardType.LAND) {
                if (!me.landPlayed) {
                    out.add(new Action(c, null));
                }
                continue;
            }
            if (!me.canPay(c.cost)) {
                continue;
            }
            switch (c.target) {
                case NONE -> out.add(new Action(c, null));
                case CREATURE -> {
                    for (Player p : players) {
                        for (Permanent perm : p.creatures()) {
                            out.add(new Action(c, Target.of(perm)));
                        }
                    }
                }
                case ANY -> {
                    for (Player p : players) {
                        out.add(new Action(c, Target.of(p)));
                        for (Permanent perm : p.creatures()) {
                            out.add(new Action(c, Target.of(perm)));
                        }
                    }
                }
            }
        }
        return out;
    }

    private void perform(Player me, Action a) {
        CardDef c = a.card();
        me.hand.remove(c);
        if (c.type == CardType.LAND) {
            Permanent land = new Permanent(c, me);
            land.summoningSick = false;
            me.battlefield.add(land);
            me.landPlayed = true;
            log(me.name + " plays " + c);
            return;
        }
        me.pay(c.cost);
        if (c.type == CardType.CREATURE) {
            me.battlefield.add(new Permanent(c, me));
            log(me.name + " casts " + c + " " + c.power + "/" + c.toughness);
            return;
        }
        log(me.name + " casts " + a);
        for (Effect e : c.effects) {
            resolve(me, e, a.target());
        }
        me.graveyard.add(c);
        checkState();
    }

    private void resolve(Player caster, Effect e, Target t) {
        switch (e.kind()) {
            case DRAW -> {
                for (int i = 0; i < e.amount(); i++) {
                    caster.draw();
                }
            }
            case GAIN_LIFE -> caster.life += e.amount();
            case DAMAGE -> {
                if (t.player() != null) {
                    t.player().life -= e.amount();
                } else {
                    t.permanent().damage += e.amount();
                }
            }
            case DESTROY -> destroy(t.permanent());
            case PUMP -> {
                t.permanent().pumpPower += e.amount();
                t.permanent().pumpToughness += e.amount();
            }
        }
    }

    private void destroy(Permanent p) {
        if (p.owner.battlefield.remove(p)) {
            p.owner.graveyard.add(p.def);
            log(p + " is destroyed");
        }
    }

    /** Removes dead creatures and decides whether the game is over. */
    public void checkState() {
        for (Player p : players) {
            Iterator<Permanent> it = p.battlefield.iterator();
            while (it.hasNext()) {
                Permanent x = it.next();
                if (x.isCreature() && (x.damage >= x.toughness() || x.toughness() <= 0)) {
                    it.remove();
                    p.graveyard.add(x.def);
                    log(x + " dies");
                }
            }
        }
        boolean lost0 = players[0].lost();
        boolean lost1 = players[1].lost();
        if (lost0 || lost1) {
            over = true;
            winner = (lost0 && lost1) ? null : (lost0 ? players[1] : players[0]);
            log(winner == null ? "Game drawn" : winner.name + " wins");
        }
    }

    // -------------------------------------------------------------------- combat

    public boolean canBlock(Permanent blocker, Permanent attacker) {
        if (attacker.has(Keyword.FLYING)) {
            return blocker.has(Keyword.FLYING) || blocker.has(Keyword.REACH);
        }
        return true;
    }

    private void combat(Player me, Player opp) {
        List<Permanent> eligible = new ArrayList<>();
        for (Permanent p : me.creatures()) {
            if (!p.tapped && (!p.summoningSick || p.has(Keyword.HASTE))) {
                eligible.add(p);
            }
        }
        if (eligible.isEmpty()) {
            return;
        }
        List<Permanent> attackers = new ArrayList<>();
        for (Permanent p : agents[active].chooseAttackers(this, me, eligible)) {
            if (eligible.contains(p) && !attackers.contains(p)) {
                attackers.add(p);
            }
        }
        if (attackers.isEmpty()) {
            return;
        }
        for (Permanent a : attackers) {
            if (!a.has(Keyword.VIGILANCE)) {
                a.tapped = true;
            }
        }
        log(me.name + " attacks with " + attackers);

        List<Permanent> blockers = new ArrayList<>();
        for (Permanent p : opp.creatures()) {
            if (!p.tapped) {
                blockers.add(p);
            }
        }
        Map<Permanent, Permanent> proposed = agents[1 - active].chooseBlocks(this, opp, attackers, blockers);
        Map<Permanent, Permanent> blocks = new HashMap<>();
        Set<Permanent> used = new HashSet<>();
        for (Map.Entry<Permanent, Permanent> e : proposed.entrySet()) {
            Permanent a = e.getKey();
            Permanent b = e.getValue();
            if (attackers.contains(a) && blockers.contains(b) && !used.contains(b) && canBlock(b, a)) {
                blocks.put(a, b);
                used.add(b);
                log(b + " blocks " + a);
            }
        }
        dealCombatDamage(attackers, blocks, opp);
    }

    private boolean onBattlefield(Permanent p) {
        return p.owner.battlefield.contains(p);
    }

    private void dealCombatDamage(List<Permanent> attackers, Map<Permanent, Permanent> blocks, Player defender) {
        boolean anyFirstStrike = false;
        for (Permanent a : attackers) {
            Permanent b = blocks.get(a);
            if (a.has(Keyword.FIRST_STRIKE) || (b != null && b.has(Keyword.FIRST_STRIKE))) {
                anyFirstStrike = true;
            }
        }
        // Step 0: first strikers deal damage. Step 1: everyone else who is still alive.
        for (int step = anyFirstStrike ? 0 : 1; step < 2; step++) {
            List<Runnable> hits = new ArrayList<>();
            for (Permanent a : attackers) {
                Permanent b = blocks.get(a);
                boolean blocked = blocks.containsKey(a);
                boolean aStrikes = a.has(Keyword.FIRST_STRIKE) ? step == 0 : step == 1;
                if (aStrikes && onBattlefield(a) && a.power() > 0) {
                    if (!blocked) {
                        int dmg = a.power();
                        hits.add(() -> defender.life -= dmg);
                    } else if (onBattlefield(b)) {
                        int dmg = a.power();
                        hits.add(() -> b.damage += dmg);
                    }
                }
                if (b != null) {
                    boolean bStrikes = b.has(Keyword.FIRST_STRIKE) ? step == 0 : step == 1;
                    if (bStrikes && onBattlefield(b) && onBattlefield(a) && b.power() > 0) {
                        int dmg = b.power();
                        hits.add(() -> a.damage += dmg);
                    }
                }
            }
            for (Runnable r : hits) {
                r.run();
            }
            checkState();
            if (over) {
                return;
            }
        }
    }
}
