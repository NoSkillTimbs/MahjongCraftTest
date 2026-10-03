package com.tablecards.engine;

import java.util.List;

/**
 * A two-player card game driven entirely by decisions: the game always either has a
 * {@link #pending()} decision for one player, or is over. Players (or bots) answer with
 * {@link #choose(int, int)}. No Minecraft code, so it runs and is tested with plain Java.
 */
public interface CardGame {
    /** Display name of the game ("Yu-Gi-Oh!", "Pokemon TCG"). */
    String title();

    /** The decision the game is waiting on, or null when the game is over. */
    Decision pending();

    /** Answers the pending decision. Ignored if it isn't this player's decision or the index is invalid. */
    boolean choose(int player, int option);

    boolean isOver();

    /** 0 or 1, or -1 for a draw / no winner yet. */
    int winner();

    /** Why the game ended, or "" while it is running. */
    String endReason();

    /** The board as seen by {@code player}: hidden information (opponent's hand, face-down cards) stays hidden. */
    List<Section> view(int player);

    /** Recent events, newest last. */
    List<String> log();

    /** Ends the game with {@code player} losing (they conceded or left). */
    void concede(int player);

    /** A bot that can play this game. */
    Bot bot();
}
