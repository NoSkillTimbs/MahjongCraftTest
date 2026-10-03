package com.tablecards.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shared plumbing: a pending decision, a log, game-over bookkeeping, and the rule that after an
 * option runs, {@link #advance()} decides the next question unless the option asked one itself.
 */
public abstract class BaseGame implements CardGame {
    protected final Random rng;
    private Decision current;
    private final List<String> log = new ArrayList<>();
    private boolean over;
    private int winner = -1;
    private String endReason = "";
    private final String[] names;

    protected BaseGame(String[] playerNames, long seed) {
        this.names = playerNames.clone();
        this.rng = new Random(seed);
    }

    public String name(int player) {
        return names[player];
    }

    /** Asks {@code player} to pick one of {@code options}. */
    protected void ask(int player, String prompt, List<Option> options) {
        if (options.isEmpty()) {
            throw new IllegalStateException("No options for: " + prompt);
        }
        current = new Decision(player, prompt, List.copyOf(options));
    }

    /** Computes the next decision from the current state (called when an option didn't ask anything). */
    protected abstract void advance();

    @Override
    public Decision pending() {
        return over ? null : current;
    }

    @Override
    public boolean choose(int player, int option) {
        Decision d = pending();
        if (d == null || d.player() != player || option < 0 || option >= d.options().size()) {
            return false;
        }
        current = null;
        d.options().get(option).action().run();
        int guard = 0;
        while (!over && current == null) {
            advance();
            if (++guard > 1000) {
                throw new IllegalStateException("Game made no progress");
            }
        }
        return true;
    }

    /** Call from the constructor once the game is set up. */
    protected void start() {
        while (!over && current == null) {
            advance();
        }
    }

    protected void log(String line) {
        log.add(line);
        if (log.size() > 200) {
            log.remove(0);
        }
    }

    @Override
    public List<String> log() {
        return List.copyOf(log);
    }

    protected void win(int player, String reason) {
        if (over) {
            return;
        }
        over = true;
        winner = player;
        endReason = reason;
        current = null;
        log(reason);
    }

    @Override
    public void concede(int player) {
        win(1 - player, name(player) + " conceded.");
    }

    @Override
    public boolean isOver() {
        return over;
    }

    @Override
    public int winner() {
        return winner;
    }

    @Override
    public String endReason() {
        return endReason;
    }

    protected static <T> T pop(List<T> list) {
        return list.remove(list.size() - 1);
    }
}
