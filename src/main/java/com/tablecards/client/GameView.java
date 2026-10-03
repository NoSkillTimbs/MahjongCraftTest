package com.tablecards.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The card game screen: a playmat with both players' zones and cards, your hand along the bottom,
 * a big preview of the card under the mouse on the left, and the question being asked on the
 * right. Cards you can use glow; click one to see what you can do with it. Choices about cards
 * that aren't on the table (searching your deck, picking from the discard pile) open a tray.
 *
 * Plain Java: it draws through {@link Canvas} and reports clicks through {@link Actions}, so it
 * runs the same in game and in tests.
 */
public final class GameView {
    /** What the screen asks the game to do. */
    public interface Actions {
        void choose(int option);

        void concede();

        void close();

        void toggleArt();

        boolean artEnabled();
    }

    // colours
    private static final int BG = 0xFF0D1117;
    private static final int PANEL = 0xE6121821;
    private static final int PANEL_EDGE = 0xFF34404F;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFA9B4C2;
    private static final int DIM = 0xFF6F7B89;
    private static final int GOOD = 0xFF7CFC9A;
    private static final int GOLD = 0xFFFFD34D;
    private static final int RED = 0xFFFF6B6B;
    private static final int SLOT = 0x1CFFFFFF;
    private static final int SLOT_EDGE = 0x45FFFFFF;

    private static final int TOP_BAR = 14;
    private static final int GAP = 3;
    private static final int MID = 10;
    private static final long CONCEDE_WINDOW_MS = 4000;
    private static final long RESEND_AFTER_MS = 4000;

    private enum Style { NORMAL, GOLD, RED, QUIET }

    private record Region(int x, int y, int w, int h, Runnable click, ViewModel.Card card, String info) {
        boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }

    private final Actions actions;
    private ViewModel v;
    private final Map<Integer, ViewModel.Card> cards = new HashMap<>();
    private final Map<Integer, List<ViewModel.Opt>> optionsByCard = new HashMap<>();
    private final List<ViewModel.Opt> general = new ArrayList<>();
    private boolean anyTray;

    private List<Region> regions = new ArrayList<>();
    private List<Region> building = new ArrayList<>();
    private final Map<Integer, int[]> cardRects = new HashMap<>();

    private int selected = -1;
    private ViewModel.Card lastPreview;
    private String lastInfo = "";
    private long concedeArmedUntil;
    private boolean waiting;
    private long waitingSince;
    private String openPile;
    private boolean showAll;
    private boolean trayHidden;
    private double trayScroll;
    private double listScroll;
    private double menuScroll;
    private long now;
    private boolean noStats;
    private double mouseX;
    private double mouseY;

    // layout of the current frame
    private int width;
    private int height;
    private int fx0;
    private int fx1;
    private int fy0;
    private int fy1;
    private int lw;
    private int rw;
    private int cw;
    private int ch;
    private Canvas c;

    public GameView(ViewModel view, Actions actions) {
        this.actions = actions;
        update(view);
    }

    public ViewModel view() {
        return v;
    }

    /** A new view arrived from the server. */
    public void update(ViewModel next) {
        boolean newQuestion = v == null || next.seq != v.seq;
        v = next;
        waiting = false;
        cards.clear();
        optionsByCard.clear();
        general.clear();
        for (ViewModel.Side s : v.sides) {
            s.hand.forEach(this::index);
            for (ViewModel.Zone z : s.zones) {
                z.cards.forEach(this::index);
            }
        }
        v.tray.forEach(this::index);
        anyTray = false;
        for (ViewModel.Opt o : v.options) {
            if (o.cards.isEmpty()) {
                general.add(o);
            } else {
                for (int id : o.cards) {
                    optionsByCard.computeIfAbsent(id, k -> new ArrayList<>()).add(o);
                }
                anyTray |= o.tray;
            }
        }
        if (newQuestion) {
            selected = -1;
            trayHidden = false;
            trayScroll = 0;
            listScroll = 0;
            showAll = false;
        }
        if (!cards.containsKey(selected)) {
            selected = -1;
        }
        if (lastPreview != null && cards.containsKey(lastPreview.id)) {
            lastPreview = cards.get(lastPreview.id);
        }
    }

    private void index(ViewModel.Card card) {
        cards.put(card.id, card);
        card.att.forEach(this::index);
    }

    /** True while a click was sent and no answer came back yet. */
    public boolean waiting() {
        return waiting && now - waitingSince < RESEND_AFTER_MS;
    }

    // =================================================================== input

    public boolean click(double mx, double my) {
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (r.contains(mx, my)) {
                if (r.click != null) {
                    r.click.run();
                }
                return true;
            }
        }
        selected = -1;
        return false;
    }

    public void scroll(double mx, double my, double amount) {
        double step = amount * 18;
        if (openPile != null || (anyTray && !trayHidden && v.yourTurn)) {
            trayScroll = Math.max(0, trayScroll - step);
        } else if (!v.hasBoard()) {
            menuScroll = Math.max(0, menuScroll - step);
        } else if (mx >= width - rw) {
            listScroll = Math.max(0, listScroll - step);
        }
    }

    /** Escape: close what's open on top first. Returns true if something was closed. */
    public boolean back() {
        if (openPile != null) {
            openPile = null;
            return true;
        }
        if (selected >= 0) {
            selected = -1;
            return true;
        }
        return false;
    }

    private void choose(ViewModel.Opt o) {
        if (waiting() || !v.yourTurn) {
            return;
        }
        waiting = true;
        waitingSince = now;
        selected = -1;
        actions.choose(o.index);
    }

    private void chooseMenu(int index) {
        if (waiting() || !v.yourTurn) {
            return;
        }
        waiting = true;
        waitingSince = now;
        actions.choose(index);
    }

    private void cardClicked(ViewModel.Card card) {
        List<ViewModel.Opt> opts = optionsByCard.get(card.id);
        lastPreview = card;
        if (opts != null && opts.size() == 1 && opts.get(0).tray) {
            choose(opts.get(0)); // picking from the tray is a single click
            return;
        }
        selected = selected == card.id ? -1 : card.id;
    }

    // =================================================================== drawing

    public void render(Canvas canvas, int w, int h, double mx, double my, long nowMs) {
        c = canvas;
        width = w;
        height = h;
        now = nowMs;
        mouseX = mx;
        mouseY = my;
        building = new ArrayList<>();
        cardRects.clear();

        ViewModel.Card hover = null;
        String hoverInfo = null;
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (r.contains(mx, my)) {
                hover = r.card;
                hoverInfo = r.info;
                break;
            }
        }
        if (hover != null) {
            lastPreview = hover;
            lastInfo = hoverInfo == null ? "" : hoverInfo;
        } else if (hoverInfo != null) {
            lastInfo = hoverInfo;
        }

        c.fill(0, 0, w, h, BG);
        if (!v.hasBoard()) {
            renderLobby(mx, my);
        } else {
            layout();
            renderField(mx, my);
            renderLeft(mx, my);
            renderRight(mx, my);
            renderTopBar(mx, my);
            if (openPile != null) {
                renderPile(mx, my);
            } else if (anyTray && v.yourTurn && !trayHidden) {
                renderTray(mx, my);
            }
            renderPopup(mx, my);
            if (v.over) {
                renderResult(mx, my);
            }
        }
        regions = building;
    }

    private void layout() {
        lw = clamp(Math.round(width * 0.21f), 84, 170);
        rw = clamp(Math.round(width * 0.24f), 104, 200);
        fx0 = lw;
        fx1 = width - rw;
        fy0 = TOP_BAR;
        fy1 = height;
        float ratio = ratio();
        int byHeight = (int) ((fy1 - fy0 - MID - 10 * GAP) / 5.45f);
        int byWidth = (int) (((fx1 - fx0) - 14 * GAP) / 7f / ratio);
        ch = Math.max(16, Math.min(byHeight, byWidth));
        cw = Math.max(11, Math.round(ch * ratio));
    }

    private float ratio() {
        return "ptcg".equals(v.game) ? 0.716f : 0.686f;
    }

    // ------------------------------------------------------------------ the playmat

    private void renderField(double mx, double my) {
        c.texture("playmat_" + ("ptcg".equals(v.game) ? "ptcg" : "ygo"), fx0, fy0, fx1 - fx0, fy1 - fy0);
        int handPeek = Math.round(ch * 0.45f);
        int total = 5 * ch + handPeek + MID + 10 * GAP;
        int y = fy0 + Math.max(0, (fy1 - fy0 - total) / 2);

        ViewModel.Side you = v.sides.get(0);
        ViewModel.Side opp = v.sides.get(1);

        // opponent's hand: the bottom of their card backs peeks in at the top
        c.scissor(fx0, fy0, fx1, fy1);
        drawOpponentHand(opp, y + GAP + handPeek - ch);
        c.noScissor();
        int oppRow1 = y + GAP + handPeek + GAP;
        int oppRow0 = oppRow1 + ch + GAP;
        int mid = oppRow0 + ch + GAP;
        int youRow0 = mid + MID + GAP;
        int youRow1 = youRow0 + ch + GAP;
        int handY = youRow1 + ch + 2 * GAP;

        drawRow(opp, 1, oppRow1, true, mx, my);
        drawRow(opp, 0, oppRow0, true, mx, my);
        // middle line with the turn and phase
        int cx = (fx0 + fx1) / 2;
        c.fill(fx0 + 6, mid + MID / 2, fx1 - 6, mid + MID / 2 + 1, 0x55FFFFFF);
        String phase = v.phase;
        float ps = fitScale(phase, fx1 - fx0 - 20, 0.75f);
        int pw = (int) (c.textWidth(phase) * ps);
        c.fill(cx - pw / 2 - 4, mid, cx + pw / 2 + 4, mid + MID, 0xCC0D1117);
        c.text(phase, cx - pw / 2f, mid + (MID - 9 * ps) / 2f + 0.5f, you.active ? GOLD : MUTED, ps, false);
        drawRow(you, 0, youRow0, false, mx, my);
        drawRow(you, 1, youRow1, false, mx, my);
        drawHand(you, handY, mx, my);
    }

    private void drawOpponentHand(ViewModel.Side opp, int y) {
        int n = opp.handCount;
        if (n == 0) {
            return;
        }
        int avail = fx1 - fx0 - 8 * GAP;
        int step = n == 1 ? 0 : Math.min(cw + GAP, (avail - cw) / (n - 1));
        int x = (fx0 + fx1) / 2 - ((n - 1) * step + cw) / 2;
        for (int i = 0; i < n; i++) {
            c.texture(cardBack(), x + i * step, y, cw, ch);
        }
        String count = String.valueOf(n);
        building.add(new Region(x, Math.max(fy0, y), (n - 1) * step + cw, y + ch - Math.max(fy0, y), null, null,
                opp.name + "'s hand: " + n + " card" + (n == 1 ? "" : "s")));
        smallBadge(x + (n - 1) * step + cw - 2, y + ch - 7, count, 0xFF3A4654);
    }

    private void drawRow(ViewModel.Side side, int row, int y, boolean opponent, double mx, double my) {
        int cx = (fx0 + fx1) / 2;
        int leftX = fx0 + 2 * GAP;
        int rightX = fx1 - 2 * GAP - cw;
        for (ViewModel.Zone z : side.zones) {
            if (z.row != row) {
                continue;
            }
            String align = z.align;
            if (opponent && !align.equals("center")) {
                align = align.equals("left") ? "right" : "left"; // mirrored, like sitting across the table
            }
            if (z.pile) {
                drawPile(side, z, align.equals("left") ? leftX : rightX, y, opponent);
                continue;
            }
            int pitch = cw + 2 * GAP;
            int zw = z.slots * pitch - 2 * GAP;
            int x0 = cx - zw / 2;
            for (int i = 0; i < z.slots; i++) {
                int sx = x0 + i * pitch;
                c.fill(sx, y, sx + cw, y + ch, SLOT);
                c.border(sx, y, cw, ch, SLOT_EDGE);
            }
            if (z.cards.isEmpty()) {
                float s = fitScale(z.label, zw - 4, 0.5f);
                c.text(z.label, cx - c.textWidth(z.label) * s / 2f, y + ch / 2f - 2, 0x66FFFFFF, s, false);
            }
            // the opponent's cards fill their zones from their left (our right)
            for (int i = 0; i < z.cards.size() && i < z.slots; i++) {
                int slot = opponent ? z.slots - 1 - i : i;
                if (z.slots == 1) {
                    slot = 0;
                }
                drawTableCard(z.cards.get(i), x0 + slot * pitch, y, mx, my, z.label);
            }
        }
    }

    private void drawPile(ViewModel.Side side, ViewModel.Zone z, int x, int y, boolean opponent) {
        c.fill(x, y, x + cw, y + ch, SLOT);
        c.border(x, y, cw, ch, SLOT_EDGE);
        String info = z.label + (opponent ? " (" + side.name + ")" : "") + ": " + z.count + " card" + (z.count == 1 ? "" : "s");
        ViewModel.Card top = z.cards.isEmpty() ? null : z.cards.get(z.cards.size() - 1);
        if (z.count > 1) {
            // a little depth for a stack of cards
            c.fill(x + 2, y + 2, x + cw + 2, y + ch + 2, 0x66000000);
        }
        if (top != null) {
            noStats = true;
            drawCard(top, x, y, cw, ch, false);
            noStats = false;
        } else {
            float s = fitScale(z.label, cw - 2, 0.5f);
            c.text(z.label, x + cw / 2f - c.textWidth(z.label) * s / 2f, y + ch / 2f - 2, 0x66FFFFFF, s, false);
        }
        if (z.count > 0) {
            smallBadge(x + cw - 2, y + ch - 7, String.valueOf(z.count), 0xFF2B3542);
        }
        boolean viewable = top != null && !top.hidden();
        String key = (opponent ? "1:" : "0:") + z.id;
        building.add(new Region(x, y, cw, ch, viewable ? () -> {
            openPile = key;
            trayScroll = 0;
        } : null, top != null && !top.hidden() ? top : null, info + (viewable ? " · click to look" : "")));
    }

    private void drawTableCard(ViewModel.Card card, int x, int y, double mx, double my, String where) {
        int dx = x;
        int dy = y;
        int dw = cw;
        int dh = ch;
        if (card.side) {
            // defense position: turned sideways and shrunk to fit the zone
            dw = Math.round(cw * cw / (float) ch);
            dh = cw;
            int ccx = x + cw / 2;
            int ccy = y + ch / 2;
            c.push();
            c.translate(ccx, ccy);
            c.rotate90();
            drawCard(card, -dh / 2, -dw / 2, dh, dw, false);
            c.pop();
            dx = ccx - dh / 2;
            dy = ccy - dw / 2;
            int t = dw;
            dw = dh;
            dh = t;
        } else {
            drawCard(card, x, y, cw, ch, false);
        }
        drawAttached(card, x, y + ch);
        outline(card, dx, dy, dw, dh);
        building.add(new Region(dx, dy, dw, dh, () -> cardClicked(card), card, where));
    }

    /** Energy attached to a Pokemon: little coloured discs along the bottom edge. */
    private void drawAttached(ViewModel.Card card, int x, int bottom) {
        if (card.att.isEmpty()) {
            return;
        }
        int d = Math.max(5, cw / 4);
        int step = Math.min(d, (cw - d) / Math.max(1, card.att.size() - 1));
        int y = bottom - Math.max(6, Math.round(ch * 0.15f)) - d - 1;
        for (int i = 0; i < card.att.size(); i++) {
            ViewModel.Card e = card.att.get(i);
            String type = e.frame.startsWith("ptcg_energy_") ? e.frame.substring("ptcg_energy_".length()) : "colorless";
            c.texture("energy_" + type, x + 1 + i * step, y, d, d);
        }
    }

    private void drawHand(ViewModel.Side you, int y, double mx, double my) {
        List<ViewModel.Card> hand = you.hand;
        int n = hand.size();
        if (n == 0) {
            return;
        }
        int avail = fx1 - fx0 - 4 * GAP;
        int step = n == 1 ? 0 : Math.min(cw + GAP, (avail - cw) / (n - 1));
        int x0 = (fx0 + fx1) / 2 - ((n - 1) * step + cw) / 2;
        // which card is under the mouse (the right-most one on top wins)
        int hovered = -1;
        for (int i = n - 1; i >= 0; i--) {
            int x = x0 + i * step;
            int w = i == n - 1 ? cw : step;
            if (mx >= x && mx < x + w && my >= y - ch / 3 && my < y + ch) {
                hovered = i;
                break;
            }
        }
        for (int i = 0; i < n; i++) {
            if (i == hovered) {
                continue;
            }
            ViewModel.Card card = hand.get(i);
            int x = x0 + i * step;
            int lift = card.id == selected ? ch / 4 : 0;
            drawCard(card, x, y - lift, cw, ch, false);
            outline(card, x, y - lift, cw, ch);
            int w = i == n - 1 ? cw : Math.max(2, step);
            building.add(new Region(x, y - lift, w, ch + lift, () -> cardClicked(card), card, "Your hand"));
        }
        if (hovered >= 0) {
            ViewModel.Card card = hand.get(hovered);
            int x = x0 + hovered * step;
            int lift = ch / 4;
            drawCard(card, x, y - lift, cw, ch, false);
            outline(card, x, y - lift, cw, ch);
            building.add(new Region(x, y - lift, cw, ch + lift, () -> cardClicked(card), card, "Your hand"));
        }
    }

    /** Gold glow on cards you can use now, white on the selected one. */
    private void outline(ViewModel.Card card, int x, int y, int w, int h) {
        if (card.id == selected) {
            cardRects.put(card.id, new int[]{x, y, w, h});
            c.border(x - 2, y - 2, w + 4, h + 4, 0xFFFFFFFF);
            c.border(x - 1, y - 1, w + 2, h + 2, 0xFFFFFFFF);
        } else if (optionsByCard.containsKey(card.id) && v.yourTurn && !waiting()) {
            cardRects.put(card.id, new int[]{x, y, w, h});
            int a = (int) (200 + 55 * Math.sin(now / 220.0));
            int col = (a << 24) | (GOLD & 0xFFFFFF);
            c.border(x - 3, y - 3, w + 6, h + 6, (a / 3 << 24) | (GOLD & 0xFFFFFF));
            c.border(x - 2, y - 2, w + 4, h + 4, col);
            c.border(x - 1, y - 1, w + 2, h + 2, col);
        }
    }

    // ------------------------------------------------------------------ one card

    private String cardBack() {
        return "back_" + ("ptcg".equals(v.game) ? "ptcg" : "ygo");
    }

    /** Draws a card face (or its back) into the box. */
    void drawCard(ViewModel.Card card, int x, int y, int w, int h, boolean large) {
        if (card.hidden()) {
            c.texture(cardBack(), x, y, w, h);
            return;
        }
        boolean art = !card.image.isEmpty() && actions.artEnabled() && c.art(card.image, x, y, w, h, large);
        if (!art) {
            drawFrame(card, x, y, w, h, large);
        }
        if (card.down) {
            // your own face-down card: you can see it, dimmed
            c.fill(x, y, x + w, y + h, 0x88000000);
            float s = Math.max(0.4f, Math.min(1.5f, h / 50f));
            String set = "SET";
            c.text(set, x + w / 2f - c.textWidth(set) * s / 2f, y + h / 2f - 4 * s, 0xFFFFE9A0, s, true);
        }
        if (!card.stat.isEmpty() && w >= 18 && !noStats) {
            int sh = Math.max(6, Math.round(h * 0.15f));
            c.fill(x, y + h - sh, x + w, y + h, 0xC8000000);
            float s = fitScale(card.stat, w - 2, Math.min(1.25f, (sh - 1) / 8f));
            c.text(card.stat, x + w / 2f - c.textWidth(card.stat) * s / 2f, y + h - sh + (sh - 8 * s) / 2f, TEXT, s, false);
        }
        if (!card.badge.isEmpty()) {
            float s = Math.max(0.45f, Math.min(1.25f, h / 70f));
            int bw = (int) (c.textWidth(card.badge) * s) + 4;
            int bh = (int) (8 * s) + 3;
            int bx = x + w - bw - 1;
            int by = y + Math.round(h * 0.27f); // below the name and HP, over the picture
            c.fill(bx, by, bx + bw, by + bh, 0xF0C62828);
            c.border(bx, by, bw, bh, 0xFFFFB3B3);
            c.text(card.badge, bx + 2, by + 2, TEXT, s, false);
        }
    }

    /** The mod's own card face, for its starter cards (and while downloaded art is loading). */
    private void drawFrame(ViewModel.Card card, int x, int y, int w, int h, boolean large) {
        c.texture("frame_" + (card.frame.isEmpty() ? "default" : card.frame), x, y, w, h);
        float ns = fitScale(card.name, w - Math.max(4, w / 8), h / 95f);
        c.text(card.name, x + Math.max(2, w / 16f), y + h * 0.055f, 0xFF1A1A1A, ns, false);
        if (!card.corner.isEmpty() && w >= 24) {
            float s = fitScale(card.corner, w / 2f, h / 120f);
            c.text(card.corner, x + w - Math.max(2, w / 16f) - c.textWidth(card.corner) * s, y + h * 0.145f, 0xFF2A2A2A, s, false);
        }
        if (large) {
            // a few lines of rules text in the frame's text box
            float s = h / 190f;
            int lineH = Math.max(4, Math.round(9 * s));
            int ty = (int) (y + h * 0.63f);
            int maxY = (int) (y + h * 0.84f);
            for (String line : card.text) {
                for (String part : wrap(line, (int) ((w - w / 6f) / s))) {
                    if (ty + lineH > maxY) {
                        return;
                    }
                    c.text(part, x + w / 12f, ty, 0xFF202020, s, false);
                    ty += lineH;
                }
            }
        }
    }

    private void smallBadge(int right, int y, String text, int bg) {
        float s = 0.6f;
        int w = (int) (c.textWidth(text) * s) + 4;
        c.fill(right - w, y, right, y + 7, bg);
        c.border(right - w, y, w, 7, 0xFF8796A8);
        c.text(text, right - w + 2, y + 1.3f, TEXT, s, false);
    }

    // ------------------------------------------------------------------ left: scores and preview

    private void renderLeft(double mx, double my) {
        c.fill(0, TOP_BAR, lw, height, PANEL);
        c.fill(lw - 1, TOP_BAR, lw, height, PANEL_EDGE);
        int boxH = 40;
        scoreBox(v.sides.get(1), 4, TOP_BAR + 3, lw - 8, boxH);
        scoreBox(v.sides.get(0), 4, height - boxH - 3, lw - 8, boxH);

        int top = TOP_BAR + boxH + 8;
        int bottom = height - boxH - 8;
        ViewModel.Card card = lastPreview != null && cards.containsKey(lastPreview.id) ? lastPreview : null;
        if (card == null && selected >= 0) {
            card = cards.get(selected);
        }
        int pw = lw - 12;
        int ph = Math.round(pw / ratio());
        int maxPh = (int) ((bottom - top) * 0.62f);
        if (ph > maxPh) {
            ph = maxPh;
            pw = Math.round(ph * ratio());
        }
        int px = (lw - pw) / 2;
        int ty;
        if (card != null) {
            drawCard(card, px, top, pw, ph, true);
            ty = top + ph + 4;
        } else {
            c.fill(px, top, px + pw, top + ph, SLOT);
            c.border(px, top, pw, ph, SLOT_EDGE);
            List<String> hint = wrap("Point at a card to see it here.", (int) ((pw - 6) / 0.6f));
            int hy = top + ph / 2 - hint.size() * 3;
            for (String l : hint) {
                c.text(l, px + 3, hy, DIM, 0.6f, false);
                hy += 6;
            }
            ty = top + ph + 4;
        }
        c.scissor(0, ty, lw, bottom);
        float s = 0.6f;
        int maxW = (int) ((lw - 8) / s);
        int line = 6;
        if (!lastInfo.isEmpty()) {
            for (String l : wrap(lastInfo, maxW)) {
                c.text(l, 4, ty, DIM, s, false);
                ty += line;
            }
        }
        if (card != null) {
            for (String l : wrap(card.hidden() ? "Face-down card" : card.name, (int) ((lw - 8) / 0.75f))) {
                c.text(l, 4, ty, TEXT, 0.75f, true);
                ty += 8;
            }
            if (!card.hidden()) {
                if (!card.tags.isEmpty()) {
                    for (String l : wrap(String.join(" · ", card.tags), maxW)) {
                        c.text(l, 4, ty, GOLD, s, false);
                        ty += line;
                    }
                }
                for (String t : card.text) {
                    for (String l : wrap(t, maxW)) {
                        c.text(l, 4, ty, MUTED, s, false);
                        ty += line;
                    }
                    ty += 1;
                }
                List<ViewModel.Opt> opts = optionsByCard.get(card.id);
                if (opts != null && v.yourTurn) {
                    c.text("Click the card to use it.", 4, ty + 1, GOLD, s, false);
                }
            }
        }
        c.noScissor();
    }

    private void scoreBox(ViewModel.Side side, int x, int y, int w, int h) {
        c.fill(x, y, x + w, y + h, side.active ? 0x33FFD34D : 0x22000000);
        c.border(x, y, w, h, side.active ? GOLD : PANEL_EDGE);
        String name = (side.active ? "▶ " : "") + side.name;
        float ns = fitScale(name, w - 6, 0.75f);
        c.text(name, x + 3, y + 3, side.active ? GOLD : MUTED, ns, false);
        float ss = fitScale(side.score + " " + side.scoreLabel, w - 6, 1.5f);
        c.text(side.score, x + 3, y + 10, TEXT, ss, true);
        c.text(side.scoreLabel, x + 5 + c.textWidth(side.score) * ss, y + 10 + 9 * ss - 6.5f, MUTED, 0.6f, false);
        List<String> info = wrap(String.join(" · ", side.info), (int) ((w - 6) / 0.6f));
        int iy = y + h - 7 - 6 * (Math.min(2, info.size()) - 1);
        for (int i = 0; i < Math.min(2, info.size()); i++) {
            c.text(info.get(i), x + 3, iy, DIM, 0.6f, false);
            iy += 6;
        }
    }

    // ------------------------------------------------------------------ right: question, buttons, log

    private void renderRight(double mx, double my) {
        int x0 = width - rw;
        c.fill(x0, TOP_BAR, width, height, PANEL);
        c.fill(x0, TOP_BAR, x0 + 1, height, PANEL_EDGE);
        int x = x0 + 5;
        int w = rw - 10;
        int y = TOP_BAR + 4;

        int promptColor = v.over ? GOLD : v.yourTurn ? GOOD : MUTED;
        for (String l : wrap(v.prompt + (waiting() ? " ..." : ""), w)) {
            c.text(l, x, y, promptColor, 1f, true);
            y += 10;
        }
        y += 2;
        if (!v.help.isEmpty() && !v.over) {
            for (String l : wrap(v.help, (int) (w / 0.6f))) {
                c.text(l, x, y, DIM, 0.6f, false);
                y += 6;
            }
            y += 3;
        }

        int logTop = height - Math.max(52, (height - TOP_BAR) * 2 / 5);
        if (v.yourTurn && !v.over) {
            boolean busy = waiting();
            if (selected >= 0 && cards.containsKey(selected) && !optionsByCard.containsKey(selected)) {
                for (String l : wrap("Nothing to do with this card right now.", (int) (w / 0.6f))) {
                    c.text(l, x, y, DIM, 0.6f, false);
                    y += 6;
                }
                y += 2;
            }
            if (anyTray && trayHidden) {
                button(x, y, w, 15, "Back to the choices", true, Style.GOLD, () -> trayHidden = false);
                y += 17;
            }
            for (ViewModel.Opt o : general) {
                if (y + 15 > logTop) {
                    break;
                }
                button(x, y, w, 15, o.label, !busy, Style.NORMAL, () -> choose(o));
                y += 17;
            }
            int cardOptions = v.options.size() - general.size();
            if (cardOptions > 0 && y + 13 <= logTop) {
                button(x, y, w, 13, (showAll ? "▾ " : "▸ ") + "All " + v.options.size() + " actions as a list",
                        true, Style.QUIET, () -> {
                            showAll = !showAll;
                            listScroll = 0;
                        });
                y += 15;
                if (showAll) {
                    int listTop = y;
                    int listBottom = logTop - 2;
                    int rowH = 14;
                    int content = v.options.size() * rowH;
                    listScroll = Math.min(listScroll, Math.max(0, content - (listBottom - listTop)));
                    c.scissor(x0, listTop, width, listBottom);
                    int ly = listTop - (int) listScroll;
                    for (ViewModel.Opt o : v.options) {
                        if (ly + rowH > listTop && ly < listBottom) {
                            button(x, ly, w, rowH - 1, o.label, !busy, Style.NORMAL, () -> choose(o), listTop, listBottom);
                        }
                        ly += rowH;
                    }
                    c.noScissor();
                }
            }
        }

        // the log, newest at the bottom
        c.fill(x0 + 4, logTop - 1, width - 4, logTop, PANEL_EDGE);
        c.text("Recent events", x, logTop + 2, DIM, 0.6f, false);
        float s = 0.6f;
        int line = 6;
        int maxW = (int) (w / s);
        List<String> lines = new ArrayList<>();
        for (String l : v.log) {
            List<String> parts = wrap(l, maxW);
            for (int i = 0; i < parts.size(); i++) {
                lines.add((i == 0 ? "" : "  ") + parts.get(i));
            }
        }
        int fit = (height - 3 - (logTop + 10)) / line;
        int start = Math.max(0, lines.size() - fit);
        int ly = logTop + 10;
        for (int i = start; i < lines.size(); i++) {
            c.text(lines.get(i), x, ly, i == lines.size() - 1 ? TEXT : MUTED, s, false);
            ly += line;
        }
    }

    // ------------------------------------------------------------------ top bar

    private void renderTopBar(double mx, double my) {
        c.fill(0, 0, width, TOP_BAR, 0xFF161D27);
        c.fill(0, TOP_BAR - 1, width, TOP_BAR, PANEL_EDGE);
        int x = width - 3;
        String close = v.over ? "Close" : "Hide";
        x -= buttonWidth(close);
        button(x, 2, buttonWidth(close), 10, close, true, Style.NORMAL, actions::close);
        String art = "Card art: " + (actions.artEnabled() ? "on" : "off");
        x -= buttonWidth(art) + 3;
        button(x, 2, buttonWidth(art), 10, art, true, Style.QUIET, actions::toggleArt);
        if (v.canConcede) {
            boolean armed = now < concedeArmedUntil;
            String label = armed ? "Click again to concede" : "Concede";
            x -= buttonWidth(label) + 3;
            button(x, 2, buttonWidth(label), 10, label, true, Style.RED, () -> {
                if (now < concedeArmedUntil) {
                    concedeArmedUntil = 0;
                    actions.concede();
                } else {
                    concedeArmedUntil = now + CONCEDE_WINDOW_MS;
                }
            });
        }
        float s = fitScale(v.title, x - 10, 0.75f);
        c.text(v.title, 5, 4, TEXT, s, false);
    }

    private int buttonWidth(String label) {
        return (int) (c.textWidth(label) * 0.65f) + 8;
    }

    // ------------------------------------------------------------------ overlays

    private void renderTray(double mx, double my) {
        List<ViewModel.Card> items = v.tray;
        Map<Integer, ViewModel.Opt> first = new HashMap<>();
        for (ViewModel.Opt o : v.options) {
            if (o.tray) {
                first.putIfAbsent(o.cards.get(0), o);
            }
        }
        grid(v.prompt, items, mx, my, card -> {
            ViewModel.Opt o = first.get(card.id);
            return o == null ? "" : o.shortLabel;
        }, () -> trayHidden = true, "Look at the table");
    }

    private void renderPile(double mx, double my) {
        String[] parts = openPile.split(":", 2);
        ViewModel.Side side = v.sides.get(Integer.parseInt(parts[0]));
        ViewModel.Zone zone = null;
        for (ViewModel.Zone z : side.zones) {
            if (z.id.equals(parts[1])) {
                zone = z;
            }
        }
        if (zone == null) {
            openPile = null;
            return;
        }
        List<ViewModel.Card> list = new ArrayList<>(zone.cards);
        java.util.Collections.reverse(list); // newest first
        grid(side.name + "'s " + zone.label + " (" + zone.count + ")", list, mx, my, card -> "", () -> openPile = null, "Close");
    }

    private interface Caption {
        String of(ViewModel.Card card);
    }

    /** A panel over the playmat showing cards in a scrolling grid. */
    private void grid(String title, List<ViewModel.Card> items, double mx, double my, Caption caption, Runnable dismiss, String dismissLabel) {
        int x0 = fx0 + 6;
        int x1 = fx1 - 6;
        int y0 = fy0 + 6;
        int y1 = fy1 - 6;
        building.add(new Region(fx0, fy0, fx1 - fx0, fy1 - fy0, null, null, null)); // blocks the table underneath
        c.fill(x0, y0, x1, y1, 0xF00D1117);
        c.border(x0, y0, x1 - x0, y1 - y0, GOLD);
        int ty = y0 + 4;
        for (String l : wrap(title, (int) ((x1 - x0 - 10) / 0.85f))) {
            c.text(l, x0 + 5, ty, TEXT, 0.85f, true);
            ty += 9;
        }
        int bw = buttonWidth(dismissLabel) + 4;
        button(x1 - bw - 4, ty + 1, bw, 11, dismissLabel, true, Style.NORMAL, dismiss);
        ty += 15;

        int gh = Math.min(Math.round(ch * 1.35f), Math.max(30, (y1 - ty - 30) / 1));
        int gw = Math.round(gh * ratio());
        int capH = 13;
        int cellW = gw + 8;
        int cellH = gh + capH + 6;
        int cols = Math.max(1, (x1 - x0 - 8) / cellW);
        int rows = (items.size() + cols - 1) / cols;
        int areaTop = ty;
        int areaBottom = y1 - 3;
        trayScroll = Math.min(trayScroll, Math.max(0, rows * cellH - (areaBottom - areaTop)));
        int gx0 = (x0 + x1) / 2 - Math.min(cols, Math.max(1, items.size())) * cellW / 2;
        c.scissor(x0, areaTop, x1, areaBottom);
        for (int i = 0; i < items.size(); i++) {
            ViewModel.Card card = items.get(i);
            int cx = gx0 + (i % cols) * cellW + 4;
            int cy = areaTop + (i / cols) * cellH - (int) trayScroll;
            if (cy + cellH < areaTop || cy > areaBottom) {
                continue;
            }
            drawCard(card, cx, cy, gw, gh, false);
            outline(card, cx, cy, gw, gh);
            String cap = caption.of(card);
            if (!cap.isEmpty()) {
                List<String> lines = wrap(cap, (int) ((cellW - 2) / 0.55f));
                int ly = cy + gh + 2;
                for (int k = 0; k < Math.min(2, lines.size()); k++) {
                    String l = lines.get(k);
                    c.text(l, cx + gw / 2f - c.textWidth(l) * 0.55f / 2f, ly, MUTED, 0.55f, false);
                    ly += 6;
                }
            }
            int visTop = Math.max(cy, areaTop);
            int visBottom = Math.min(cy + gh, areaBottom);
            if (visBottom > visTop) {
                building.add(new Region(cx, visTop, gw, visBottom - visTop, () -> cardClicked(card), card, null));
            }
        }
        c.noScissor();
        if (rows * cellH > areaBottom - areaTop) {
            c.text("scroll for more", x1 - 4 - c.textWidth("scroll for more") * 0.55f, y1 - 9, DIM, 0.55f, false);
        }
    }

    /** The options for the selected card, next to it. */
    private void renderPopup(double mx, double my) {
        if (selected < 0 || !v.yourTurn) {
            return;
        }
        List<ViewModel.Opt> opts = optionsByCard.get(selected);
        int[] r = cardRects.get(selected);
        if (opts == null || r == null) {
            return;
        }
        float s = 0.7f;
        int bw = 40;
        for (ViewModel.Opt o : opts) {
            bw = Math.max(bw, (int) (c.textWidth(o.shortLabel) * s) + 10);
        }
        bw = Math.min(bw, Math.max(110, (fx1 - fx0) * 7 / 10));
        int bh = 13;
        int ph = opts.size() * (bh + 2) + 2;
        int x = r[0] + r[2] + 5;
        if (x + bw + 4 > width) {
            x = r[0] - bw - 7;
        }
        int y = Math.max(TOP_BAR + 2, Math.min(r[1], height - ph - 2));
        building.add(new Region(x - 2, y - 2, bw + 4, ph + 2, null, null, null));
        c.fill(x - 2, y - 2, x + bw + 2, y + ph, 0xF0101820);
        c.border(x - 2, y - 2, bw + 4, ph + 2, GOLD);
        int by = y;
        for (ViewModel.Opt o : opts) {
            button(x, by, bw, bh, o.shortLabel, !waiting(), Style.GOLD, () -> choose(o));
            by += bh + 2;
        }
    }

    private void renderResult(double mx, double my) {
        int w = Math.min(fx1 - fx0 - 20, 240);
        int x = (fx0 + fx1) / 2 - w / 2;
        String head = v.winner.isEmpty() ? "Game over" : v.winner.equals(v.you) ? "You win!" : "You lose";
        List<String> lines = wrap(v.prompt, (int) ((w - 12) / 0.75f));
        int h = 40 + lines.size() * 8;
        int y = (fy0 + fy1) / 2 - h / 2;
        building.add(new Region(fx0, fy0, fx1 - fx0, fy1 - fy0, null, null, null));
        c.fill(fx0, fy0, fx1, fy1, 0x66000000);
        c.fill(x, y, x + w, y + h, 0xFF101820);
        c.border(x, y, w, h, GOLD);
        float hs = 1.6f;
        c.text(head, x + w / 2f - c.textWidth(head) * hs / 2f, y + 5, v.winner.equals(v.you) ? GOLD : TEXT, hs, true);
        int ty = y + 22;
        for (String l : lines) {
            c.text(l, x + w / 2f - c.textWidth(l) * 0.75f / 2f, ty, MUTED, 0.75f, false);
            ty += 8;
        }
        button(x + w / 2 - 30, y + h - 15, 60, 12, "Close", true, Style.NORMAL, actions::close);
    }

    // ------------------------------------------------------------------ before the game: lobby and deck choice

    private void renderLobby(double mx, double my) {
        int w = Math.min(width - 20, 320);
        int x = width / 2 - w / 2;
        int y = 12;
        c.fill(x, y, x + w, height - 8, PANEL);
        c.border(x, y, w, height - 8 - y, PANEL_EDGE);
        int ty = y + 6;
        c.text(v.title, x + 6, ty, MUTED, fitScale(v.title, w - 12, 0.75f), false);
        ty += 10;
        for (String l : wrap(v.prompt, (int) ((w - 12) / 1.25f))) {
            c.text(l, x + 6, ty, v.yourTurn ? GOOD : TEXT, 1.25f, true);
            ty += 13;
        }
        for (String l : wrap(v.info, (int) ((w - 12) / 0.7f))) {
            c.text(l, x + 6, ty, MUTED, 0.7f, false);
            ty += 8;
        }
        ty += 4;
        int bottom = height - 36;
        int rowH = 17;
        int content = v.menu.size() * rowH;
        menuScroll = Math.min(menuScroll, Math.max(0, content - (bottom - ty)));
        c.scissor(x, ty, x + w, bottom);
        int by = ty - (int) menuScroll;
        for (int i = 0; i < v.menu.size(); i++) {
            final int index = i;
            if (by + rowH > ty && by < bottom) {
                button(x + 6, by, w - 12, rowH - 2, v.menu.get(i), v.yourTurn && !waiting(), Style.NORMAL, () -> chooseMenu(index), ty, bottom);
            }
            by += rowH;
        }
        c.noScissor();
        if (content > bottom - ty) {
            c.text("scroll for more decks", x + 6, bottom + 2, DIM, 0.6f, false);
        }
        String close = v.over ? "Close" : v.state.equals("waiting") ? "Close the table" : "Leave";
        Runnable onClose = v.over ? actions::close : () -> {
            if (v.state.equals("waiting") || v.state.equals("choosing_decks")) {
                actions.concede();
            } else {
                actions.close();
            }
        };
        button(x + w - 86, height - 24, 80, 13, close, true, Style.RED, onClose);
        button(x + 6, height - 24, 60, 13, "Hide", true, Style.NORMAL, actions::close);
    }

    // ------------------------------------------------------------------ widgets and text

    private void button(int x, int y, int w, int h, String label, boolean enabled, Style style, Runnable click) {
        button(x, y, w, h, label, enabled, style, click, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private void button(int x, int y, int w, int h, String label, boolean enabled, Style style, Runnable click, int clipTop, int clipBottom) {
        boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, clipTop) && mouseY < Math.min(y + h, clipBottom);
        int bg;
        int edge;
        int fg;
        switch (style) {
            case GOLD -> {
                bg = 0xFF4A3A10;
                edge = GOLD;
                fg = 0xFFFFF1C4;
            }
            case RED -> {
                bg = 0xFF4A1C1C;
                edge = 0xFFB54A4A;
                fg = 0xFFFFD6D6;
            }
            case QUIET -> {
                bg = 0xFF1A222C;
                edge = 0xFF3A4654;
                fg = MUTED;
            }
            default -> {
                bg = 0xFF2B3542;
                edge = 0xFF56677A;
                fg = TEXT;
            }
        }
        if (hover) {
            bg = brighten(bg);
            edge = 0xFFFFFFFF;
        }
        if (!enabled) {
            bg = 0xFF1A1F26;
            edge = 0xFF2E3640;
            fg = DIM;
        }
        c.fill(x, y, x + w, y + h, bg);
        c.border(x, y, w, h, edge);
        float s = Math.min(h >= 14 ? 0.85f : 0.65f, (h - 3) / 8f);
        String text = ellipsize(label, (int) ((w - 6) / s));
        c.text(text, x + w / 2f - c.textWidth(text) * s / 2f, y + (h - 8 * s) / 2f, fg, s, false);
        int top = Math.max(y, clipTop);
        int bottom = Math.min(y + h, clipBottom);
        if (bottom > top) {
            building.add(new Region(x, top, w, bottom - top, enabled ? click : null, null, null));
        }
    }

    private float fitScale(String s, float maxW, float wanted) {
        int tw = c.textWidth(s);
        if (tw == 0) {
            return wanted;
        }
        return Math.max(0.25f, Math.min(wanted, maxW / tw));
    }

    private String ellipsize(String s, int maxW) {
        if (c.textWidth(s) <= maxW) {
            return s;
        }
        String dots = "...";
        int end = s.length();
        while (end > 0 && c.textWidth(s.substring(0, end) + dots) > maxW) {
            end--;
        }
        return s.substring(0, end) + dots;
    }

    /** Word-wraps {@code s} to lines at most {@code maxW} wide (at scale 1). */
    List<String> wrap(String s, int maxW) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (c.textWidth(candidate) <= maxW || line.length() == 0) {
                if (line.length() == 0 && c.textWidth(word) > maxW) {
                    // a single very long word: cut it
                    String w = word;
                    while (c.textWidth(w) > maxW && w.length() > 1) {
                        int cut = w.length();
                        while (cut > 1 && c.textWidth(w.substring(0, cut)) > maxW) {
                            cut--;
                        }
                        out.add(w.substring(0, cut));
                        w = w.substring(cut);
                    }
                    line = new StringBuilder(w);
                } else {
                    line = new StringBuilder(candidate);
                }
            } else {
                out.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    private static int brighten(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 28);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 28);
        int b = Math.min(255, (argb & 0xFF) + 28);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
