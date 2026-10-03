package com.shandalar.engine;

/** Either a player or a permanent (exactly one is non-null). */
public record Target(Player player, Permanent permanent) {
    public static Target of(Player p) {
        return new Target(p, null);
    }

    public static Target of(Permanent p) {
        return new Target(null, p);
    }

    @Override
    public String toString() {
        return player != null ? player.name : permanent.toString();
    }
}
