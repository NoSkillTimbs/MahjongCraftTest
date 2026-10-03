package com.tablecards.engine;

/** Picks an option index for a decision that belongs to a bot. */
public interface Bot {
    int choose(CardGame game, Decision decision);
}
