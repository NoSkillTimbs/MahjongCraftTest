package com.tablecards.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The card games this client can see on tables in the world: your own game, and games nearby
 * that you're watching (the server only sends what everyone may see). Also notices cards being
 * drawn, so they can fly from the deck to the hand.
 *
 * Plain Java; only used from the client thread.
 */
public final class TableViews {
    public static final class Entry {
        public final ViewModel view;
        /** Your own game (not just watching). */
        public final boolean own;
        /** The world the table is in. */
        public final String dimension;
        /** Draw animations by seat. */
        public final Map<Integer, TableLayout.Anim> anims;

        Entry(ViewModel view, boolean own, String dimension, Map<Integer, TableLayout.Anim> anims) {
            this.view = view;
            this.own = own;
            this.dimension = dimension;
            this.anims = anims;
        }
    }

    private static final Map<String, Entry> TABLES = new HashMap<>();
    /** What you're pointing at in your own game (set by the game screen every frame). */
    public static final TableLayout.Highlight HIGHLIGHT = new TableLayout.Highlight();

    private TableViews() {
    }

    public static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    public static Entry get(int x, int y, int z) {
        return TABLES.get(key(x, y, z));
    }

    /** A view arrived from the server. */
    public static void accept(ViewModel v, String dimension, long now) {
        if (v.table == null) {
            return;
        }
        String k = key(v.table[0], v.table[1], v.table[2]);
        if (v.closed) {
            TABLES.remove(k);
            return;
        }
        Entry prev = TABLES.get(k);
        boolean own = !v.watcher;
        if (!own && prev != null && prev.own && !prev.view.over) {
            return; // you're playing here; your own view shows more
        }
        if (own) {
            // you only play at one table at a time
            TABLES.entrySet().removeIf(e -> e.getValue().own && !e.getKey().equals(k));
        }
        Map<Integer, TableLayout.Anim> anims = new HashMap<>();
        if (prev != null) {
            anims.putAll(prev.anims);
            anims.values().removeIf(a -> !a.running(now));
            if (v.hasBoard()) {
                for (int i = 0; i < 2; i++) {
                    int seat = TableLayout.seatOfSide(v, i);
                    int drawn = drawn(prev.view, v, seat);
                    if (drawn > 0) {
                        anims.put(seat, new TableLayout.Anim(drawn, now));
                    }
                }
            }
        }
        TABLES.put(k, new Entry(v, own, dimension, anims));
    }

    /** How many cards seat {@code seat} drew from their deck between two views. */
    static int drawn(ViewModel before, ViewModel after, int seat) {
        ViewModel.Side now = sideOfSeat(after, seat);
        if (now == null) {
            return 0;
        }
        int handNow = handSize(now);
        ViewModel.Side was = sideOfSeat(before, seat);
        if (was == null) {
            // the game just started: the opening hand comes off the deck
            return before.hasBoard() ? 0 : handNow;
        }
        int deckDrop = deckCount(was) - deckCount(now);
        int handGain = handNow - handSize(was);
        return Math.max(0, Math.min(deckDrop, handGain));
    }

    static ViewModel.Side sideOfSeat(ViewModel v, int seat) {
        if (!v.hasBoard()) {
            return null;
        }
        return v.sides.get(TableLayout.seatOfSide(v, 0) == seat ? 0 : 1);
    }

    static int handSize(ViewModel.Side s) {
        return Math.max(s.handCount, s.hand.size());
    }

    static int deckCount(ViewModel.Side s) {
        for (ViewModel.Zone z : s.zones) {
            if (z.id.equals("deck")) {
                return Math.max(z.count, z.cards.size());
            }
        }
        return 0;
    }

    /**
     * Forgets games you can no longer see: watched tables in another world or out of range (the
     * server sends them again when you come back), and your own game when you're asked to.
     */
    public static void prune(String dimension, double x, double y, double z, double range) {
        Iterator<Map.Entry<String, Entry>> it = TABLES.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.own) {
                continue;
            }
            int[] t = e.view.table;
            double dx = t[0] + 0.5 - x;
            double dy = t[1] + 0.5 - y;
            double dz = t[2] + 0.5 - z;
            if (!e.dimension.equals(dimension) || dx * dx + dy * dy + dz * dz > range * range) {
                it.remove();
            }
        }
    }

    public static void clear() {
        TABLES.clear();
    }

    /** Your own game, if one is on the table. */
    public static Entry own() {
        for (Entry e : TABLES.values()) {
            if (e.own) {
                return e;
            }
        }
        return null;
    }
}
