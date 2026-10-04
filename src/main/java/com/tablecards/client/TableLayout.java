package com.tablecards.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where everything of a card game goes on the Game Table in the world: each player's zones in
 * front of them, their deck as one stack, and their hand standing at their edge of the table
 * facing them. Coordinates are relative to the table's centre block (its top is at y = 1).
 *
 * Plain Java (no Minecraft classes), so the layout can be checked outside the game.
 */
public final class TableLayout {
    /** Height of the table top in the centre block. */
    public static final double TOP = 1.0;
    /** Half the size of the table top (it covers 3 x 3 blocks, minus the rim). */
    static final double HALF = 1.4375;
    static final double CARD_H = 0.40;
    static final double GAP = 0.045;
    static final double MID = 0.07;
    /** Where the bottom edge of the hand stands, from the centre. */
    static final double HAND_EDGE = 1.40;
    /** How far the hand leans back from upright (so it doesn't hide your own field). */
    static final double HAND_LEAN = Math.toRadians(62);
    static final double HAND_SCALE = 0.78;
    static final double HAND_WIDTH = 2.3;
    public static final long FLY_MS = 380;
    public static final long STAGGER_MS = 120;
    static final double[] UP = {0, 1, 0};

    public enum Kind { SLOT, CARD, PILE }

    /** One thing to draw: a zone outline, a card, or a pile of cards. */
    public static final class Item {
        public final Kind kind;
        /** The card shown (null: a plain card back). */
        public ViewModel.Card card;
        /** Draw the back instead of the face. */
        public boolean back;
        /** A standing card: its other side shows the card back. */
        public boolean twoSided;
        /** Centre, half-width vector (towards the card's right) and half-height vector (towards its top). */
        public double[] c;
        public double[] u;
        public double[] v;
        /** Which way the face looks. */
        public double[] n;
        /** Piles: the height of the stack. */
        public double thick;
        /** Slots: the zone name; piles: the number of cards. */
        public String label = "";
        /** What a click on it means ("c:<card id>", "p:<side>:<zone>", "h:<side>"), or null. */
        public String key;
        /** 0 none, 1 can be used now, 2 under the mouse, 3 selected. */
        public int glow;
        /** A card lying in a zone: show its ATK/DEF or HP, damage and energy. */
        public boolean stats;
        /** The index (0 or 1) of the side in ViewModel.sides this belongs to. */
        public int side;

        Item(Kind kind) {
            this.kind = kind;
        }

        /** The four corners (top-left, bottom-left, bottom-right, top-right of the face). */
        public double[][] corners() {
            double[] top = kind == Kind.PILE ? add(c, mul(UP, thick)) : c;
            return new double[][]{
                    add(sub(top, u), v), sub(sub(top, u), v), sub(add(top, u), v), add(add(top, u), v)
            };
        }
    }

    /** What the person at the table is pointing at and may use. */
    public static final class Highlight {
        public Set<Integer> usable = Set.of();
        public String hover = "";
        public int selected = -1;
    }

    /** Cards flying from a deck to the hand: the last {@code count} cards of that hand. */
    public static final class Anim {
        public final int count;
        public final long start;

        public Anim(int count, long start) {
            this.count = count;
            this.start = start;
        }

        boolean running(long now) {
            return now < start + STAGGER_MS * Math.max(0, count - 1) + FLY_MS;
        }
    }

    /** The frame of one side: {@code out} points from the centre to that player, {@code right} is their right. */
    static final class Frame {
        final double[] out;
        final double[] right;

        Frame(double[] out) {
            this.out = out;
            this.right = new double[]{out[2], 0, -out[0]};
        }

        double[] at(double r, double f, double y) {
            return new double[]{0.5 + r * right[0] + f * out[0], TOP + y, 0.5 + r * right[2] + f * out[2]};
        }
    }

    private TableLayout() {
    }

    public static double[] dirVec(String dir) {
        return switch (dir) {
            case "east" -> new double[]{1, 0, 0};
            case "west" -> new double[]{-1, 0, 0};
            case "north" -> new double[]{0, 0, -1};
            default -> new double[]{0, 0, 1};
        };
    }

    /** The seat (0 or 1) that sides[i] belongs to. */
    public static int seatOfSide(ViewModel v, int i) {
        return i == 0 ? v.seat : 1 - v.seat;
    }

    static Frame frame(ViewModel v, int i) {
        double[] d = dirVec(v.dir0);
        return new Frame(seatOfSide(v, i) == 0 ? d : mul(d, -1));
    }

    public static double ratio(ViewModel v) {
        return "ptcg".equals(v.game) ? 0.716 : 0.686;
    }

    /** Everything on the table for this view at time {@code now}. */
    public static List<Item> build(ViewModel v, Map<Integer, Anim> anims, long now, Highlight hl) {
        List<Item> out = new ArrayList<>();
        if (!v.hasBoard()) {
            return out;
        }
        int left = sideCols(v, "left");
        int right = sideCols(v, "right");
        int total = 5 + left + right;
        double ratio = ratio(v);
        double ch = CARD_H;
        double cw = ch * ratio;
        double maxWidth = 2 * HALF - 0.16;
        if (total * (cw + GAP) > maxWidth) {
            cw = maxWidth / total - GAP;
            ch = cw / ratio;
        }
        double pitch = cw + GAP;
        for (int i = 0; i < 2; i++) {
            ViewModel.Side side = v.sides.get(i);
            Frame f = frame(v, i);
            double[] deckTop = null;
            int[] perRow = new int[4];
            for (ViewModel.Zone z : side.zones) {
                int row = Math.max(0, Math.min(1, z.row));
                double fwd = MID / 2 + ch / 2 + row * (ch + GAP);
                if (z.align.equals("left") || z.align.equals("right")) {
                    boolean isLeft = z.align.equals("left");
                    int idx = perRow[row * 2 + (isLeft ? 0 : 1)]++;
                    int col = isLeft ? left - 1 - idx : left + 5 + idx;
                    double r = (col - (total - 1) / 2.0) * pitch;
                    double[] c = f.at(r, fwd, 0);
                    out.add(slot(f, c, cw, ch, z.cards.isEmpty() && z.count == 0 ? z.label : "", i));
                    if (z.pile) {
                        if (z.count > 0 || !z.cards.isEmpty()) {
                            Item p = flat(Kind.PILE, f, c, cw, ch, false, i);
                            int count = Math.max(z.count, z.cards.size());
                            p.thick = Math.min(0.15, 0.003 * count + 0.004);
                            ViewModel.Card top = z.cards.isEmpty() ? null : z.cards.get(z.cards.size() - 1);
                            p.card = top;
                            p.back = top == null || top.hidden();
                            p.label = String.valueOf(count);
                            p.key = "p:" + i + ":" + z.id;
                            if (hl.hover.equals(p.key)) {
                                p.glow = 2;
                            }
                            out.add(p);
                            if (z.id.equals("deck")) {
                                deckTop = add(c, mul(UP, p.thick));
                            }
                        } else if (z.id.equals("deck")) {
                            deckTop = c;
                        }
                    } else if (!z.cards.isEmpty()) {
                        out.add(card(f, c, cw, ch, z.cards.get(0), hl, i, pitch));
                    }
                    continue;
                }
                double start = left + (5 - z.slots) / 2.0;
                for (int k = 0; k < z.slots; k++) {
                    double r = (start + k - (total - 1) / 2.0) * pitch;
                    String label = z.cards.isEmpty() && k == z.slots / 2 ? z.label : "";
                    out.add(slot(f, f.at(r, fwd, 0), cw, ch, label, i));
                }
                for (int k = 0; k < z.cards.size() && k < z.slots; k++) {
                    double r = (start + (z.slots == 1 ? 0 : k) - (total - 1) / 2.0) * pitch;
                    out.add(card(f, f.at(r, fwd, 0), cw, ch, z.cards.get(k), hl, i, pitch));
                }
            }
            if (deckTop == null) {
                deckTop = f.at(((left + 5) - (total - 1) / 2.0) * pitch, MID / 2 + ch * 1.5 + GAP, 0);
            }
            hand(out, v, i, f, side, cw, ch, deckTop, anims, now, hl);
        }
        return out;
    }

    private static void hand(List<Item> out, ViewModel v, int i, Frame f, ViewModel.Side side, double cw, double ch,
                             double[] deckTop, Map<Integer, Anim> anims, long now, Highlight hl) {
        int n = Math.max(side.handCount, side.hand.size());
        if (n == 0) {
            return;
        }
        double hw = cw * HAND_SCALE;
        double hh = ch * HAND_SCALE;
        double step = n == 1 ? 0 : Math.min(hw * 0.84, (HAND_WIDTH - hw) / (n - 1));
        double a = HAND_LEAN;
        double[] vDir = add(mul(UP, Math.cos(a)), mul(f.out, -Math.sin(a)));
        double[] nDir = add(mul(f.out, Math.cos(a)), mul(UP, Math.sin(a)));
        Anim anim = anims == null ? null : anims.get(seatOfSide(v, i));
        int flyFrom = anim != null && anim.running(now) ? Math.max(0, n - anim.count) : n;
        for (int k = 0; k < n; k++) {
            ViewModel.Card card = k < side.hand.size() ? side.hand.get(k) : null;
            double r = (k - (n - 1) / 2.0) * step;
            double[] c = f.at(r, HAND_EDGE - Math.sin(a) * hh / 2, Math.cos(a) * hh / 2 + 0.004);
            c = add(c, mul(nDir, 0.0035 * k)); // later cards in front
            Item it = new Item(Kind.CARD);
            it.side = i;
            it.card = card;
            it.back = card == null || card.hidden();
            it.twoSided = true;
            it.u = mul(f.right, hw / 2);
            it.v = mul(vDir, hh / 2);
            it.n = nDir;
            if (card != null) {
                it.key = "c:" + card.id;
                it.glow = glow(card, hl);
                if (it.glow >= 2) {
                    c = add(c, mul(vDir, hh * 0.18)); // lift the card you point at
                }
            } else {
                it.key = "h:" + i;
            }
            it.c = c;
            if (k >= flyFrom) {
                long t0 = anim.start + STAGGER_MS * (k - flyFrom);
                if (now < t0) {
                    continue; // still in the deck
                }
                if (now < t0 + FLY_MS) {
                    double t = (now - t0) / (double) FLY_MS;
                    t = t * t * (3 - 2 * t);
                    Item fly = new Item(Kind.CARD);
                    fly.side = i;
                    fly.back = true;
                    fly.twoSided = true;
                    double[] c0 = add(deckTop, mul(UP, 0.006));
                    double[] u0 = mul(f.right, cw / 2);
                    double[] v0 = mul(f.out, -ch / 2);
                    fly.c = add(lerp(c0, c, t), mul(UP, 0.22 * Math.sin(Math.PI * t)));
                    fly.u = lerp(u0, it.u, t);
                    double[] vv = lerp(v0, it.v, t);
                    double len = (ch + (hh - ch) * t) / 2;
                    fly.v = mul(norm(vv), len);
                    fly.n = norm(lerp(UP, nDir, t));
                    out.add(fly);
                    continue;
                }
            }
            out.add(it);
        }
    }

    private static Item slot(Frame f, double[] c, double cw, double ch, String label, int side) {
        Item s = flat(Kind.SLOT, f, add(c, mul(UP, 0.001)), cw, ch, false, side);
        s.label = label;
        return s;
    }

    private static Item flat(Kind kind, Frame f, double[] c, double cw, double ch, boolean sideways, int side) {
        Item it = new Item(kind);
        it.side = side;
        it.c = c;
        it.n = UP;
        if (sideways) {
            it.u = mul(f.out, -cw / 2);
            it.v = mul(f.right, -ch / 2);
        } else {
            it.u = mul(f.right, cw / 2);
            it.v = mul(f.out, -ch / 2);
        }
        return it;
    }

    private static Item card(Frame f, double[] c, double cw, double ch, ViewModel.Card card, Highlight hl, int side, double pitch) {
        double w = cw;
        double h = ch;
        if (card.side) {
            // defence position: turned sideways, and a little smaller so it stays inside its zone
            double k = Math.min(1, (pitch - 0.012) / ch);
            w *= k;
            h *= k;
        }
        Item it = flat(Kind.CARD, f, add(c, mul(UP, 0.003)), w, h, card.side, side);
        it.card = card;
        it.back = card.hidden();
        it.key = "c:" + card.id;
        it.glow = glow(card, hl);
        it.stats = true;
        return it;
    }

    private static int glow(ViewModel.Card card, Highlight hl) {
        if (card.id == hl.selected) {
            return 3;
        }
        if (hl.hover.equals("c:" + card.id)) {
            return 2;
        }
        return hl.usable.contains(card.id) ? 1 : 0;
    }

    /** How many side columns (piles, Field Spell) there are on the left or right, on either side of the table. */
    static int sideCols(ViewModel v, String align) {
        int max = 0;
        for (ViewModel.Side side : v.sides) {
            for (int row = 0; row < 2; row++) {
                int n = 0;
                for (ViewModel.Zone z : side.zones) {
                    if (z.row == row && z.align.equals(align)) {
                        n++;
                    }
                }
                max = Math.max(max, n);
            }
        }
        return Math.max(1, max);
    }

    // ------------------------------------------------------------------ small vector maths

    static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static double[] mul(double[] a, double k) {
        return new double[]{a[0] * k, a[1] * k, a[2] * k};
    }

    static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    static double[] norm(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return l < 1e-9 ? new double[]{0, 1, 0} : mul(a, 1 / l);
    }

    public static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
