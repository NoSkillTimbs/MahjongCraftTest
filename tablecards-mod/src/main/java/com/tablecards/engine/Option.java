package com.tablecards.engine;

/**
 * One choice offered to a player.
 *
 * @param label  what the player sees on the button
 * @param action what happens when it is chosen
 * @param move   a machine-readable description of the choice, used by bots to score it
 */
public record Option(String label, Runnable action, Move move) {
    public Option(String label, Runnable action) {
        this(label, action, Move.of("other"));
    }
}
