package com.tablecards.engine;

/**
 * Describes an option for bots. {@code type} names the kind of move (e.g. "summon", "attack");
 * {@code a} and {@code b} are the cards or targets involved (game specific, may be null);
 * {@code n} is a number such as damage or a count.
 */
public record Move(String type, Object a, Object b, int n) {
    public static Move of(String type) { return new Move(type, null, null, 0); }
    public static Move of(String type, Object a) { return new Move(type, a, null, 0); }
    public static Move of(String type, Object a, Object b) { return new Move(type, a, b, 0); }
    public static Move of(String type, Object a, Object b, int n) { return new Move(type, a, b, n); }
}
