package com.shandalar.engine;

/**
 * One step of a spell. DRAW and GAIN_LIFE apply to the caster; DAMAGE, DESTROY and PUMP
 * apply to the chosen target.
 */
public record Effect(Kind kind, int amount) {
    public enum Kind { DAMAGE, DRAW, GAIN_LIFE, DESTROY, PUMP }
}
