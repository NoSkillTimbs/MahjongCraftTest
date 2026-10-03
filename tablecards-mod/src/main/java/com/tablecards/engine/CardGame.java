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

    /** The table as {@code player} sees it, for the visual screen. */
    Board board(int player);

    /**
     * The card an option is about (an engine object that appears in some {@link Board.CardView#refs}),
     * or null for options like "End turn". Clicking that card on the screen offers this option.
     */
    default Object focus(Option option) {
        return option.move().a();
    }

    /**
     * A card for {@code focus} when it isn't on the board (a card in the deck or discard pile that
     * an option lets you pick), or null.
     */
    default Board.CardView cardView(Object focus, int player) {
        return null;
    }
}
