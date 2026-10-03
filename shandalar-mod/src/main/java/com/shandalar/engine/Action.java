package com.shandalar.engine;

/** Play a land or cast a spell from hand. Target is null when the card needs none. */
public record Action(CardDef card, Target target) {
    @Override
    public String toString() {
        return target == null ? card.name : card.name + " -> " + target;
    }
}
