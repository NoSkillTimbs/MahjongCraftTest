package com.tablecards.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The card game screen: a playmat with both players' zones and cards, your hand along the bottom,
 * a big preview of the card under the mouse on the left, and the question being asked on the
 * right. Cards you can use glow; click one to see what you can do with it. Choices about cards
 * that aren't on the table (searching your deck, picking from the discard pile) open a tray.
 *
 * In the world ({@link World}) the cards lie on the Game Table itself; this screen is then only a
 * see-through layer over it: the question and buttons on the right, the scores at the top left,
 * and a close-up of any card you click (with what you can do with it).
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

        default void deckBuilder() {}
    }

    /** The cards on the table in the world: where they are on the screen, and what to light up. */
    public interface World {
        /** Outlines on the screen, farthest first. */
        List<Hit> hits();

        void highlight(Set<Integer> usable, String hover, int selected);
    }

    /** Something on the table, as a four-cornered outline on the screen (x0, y0, ... x3, y3). */
    public record Hit(String key, float[] xy, float depth) {
        public boolean contains(double x, double y) {
            boolean in = false;
            for (int i = 0, j = 3; i < 4; j = i++) {
                double xi = xy[i * 2];
                double yi = xy[i * 2 + 1];
                double xj = xy[j * 2];
                double yj = xy[j * 2 + 1];
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                    in = !in;
                }
            }
            return in;
        }
    }

    /** A question to make sure before something that can't be taken back. */
    private record Confirm(String title, String text, String yes, Runnable action) {
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
    private static final long RESEND_AFTER_MS = 4000;

    private enum Style { NORMAL, GOLD, RED, QUIET }

    private record Region(int x, int y, int w, int h, Runnable click, ViewModel.Card card, String info, Hit hit) {
        Region(int x, int y, int w, int h, Runnable click, ViewModel.Card card, String info) {
            this(x, y, w, h, click, card, info, null);
        }

        boolean contains(double mx, double my) {
            if (hit != null) {
                return hit.contains(mx, my);
            }
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }

    private final Actions actions;
    private final World world;
    private ViewModel v;
    private final Map<Integer, ViewModel.Card> cards = new HashMap<>();
    private final Map<Integer, List<ViewModel.Opt>> optionsByCard = new HashMap<>();
    private final List<ViewModel.Opt> general = new ArrayList<>();
    private boolean anyTray;
    private int trayCount;

    private List<Region> regions = new ArrayList<>();
    private List<Region> building = new ArrayList<>();
    private final Map<Integer, int[]> cardRects = new HashMap<>();
    private final Map<String, int[]> fieldRects = new HashMap<>();

    private int selected = -1;
    private ViewModel.Card lastPreview;
    private String lastInfo = "";
    private Confirm confirm;
    private int zoomed = -1;
    private double zoomScroll;
    private String hoverKey = "";
    private ViewModel.Card hoverCard;
    private String hoverText;
    private boolean worldMode;
    private boolean duelView;
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
        this(view, actions, null);
    }

    public GameView(ViewModel view, Actions actions, World world) {
        this.actions = actions;
        this.world = world;
        update(view);
    }

    /** True when the cards are drawn on the table in the world rather than on this screen. */
    public boolean inWorld() {
        return world != null && v.hasBoard() && !(duelView && "ygo".equals(v.game));
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
        boolean boardOptions = false;
        trayCount = 0;
        for (ViewModel.Opt o : v.options) {
            if (!o.cards.isEmpty() && !o.tray) {
                boardOptions = true;
            }
            if (o.tray) {
                trayCount++;
            }
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
            // a question about cards off the table opens the tray; otherwise it waits behind a button
            trayHidden = boardOptions;
            trayScroll = 0;
            listScroll = 0;
            showAll = false;
            confirm = null;
        }
        if (!cards.containsKey(selected)) {
            selected = -1;
        }
        if (!cards.containsKey(zoomed)) {
            zoomed = -1;
        }
        if (v.over) {
            confirm = null;
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
        if (zoomed >= 0) {
            zoomScroll = Math.max(0, zoomScroll - step);
        } else if (openPile != null || (anyTray && !trayHidden && v.yourTurn)) {
            trayScroll = Math.max(0, trayScroll - step);
        } else if (!v.hasBoard()) {
            menuScroll = Math.max(0, menuScroll - step);
        } else if (mx >= width - rw) {
            listScroll = Math.max(0, listScroll - step);
        }
    }

    /** Escape: close what's open on top first. Returns true if something was closed. */
    public boolean back() {
        if (confirm != null) {
            confirm = null;
            return true;
        }
        if (zoomed >= 0) {
            zoomed = -1;
            return true;
        }
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
        if (isRetreat(o)) {
            confirm = new Confirm("Retreat?", "Are you sure? " + o.label + ".", "Retreat", () -> send(o));
            return;
        }
        send(o);
    }

    /** Pokemon TCG: retreating the Active Pokemon (asked to confirm, with its own button). */
    private boolean isRetreat(ViewModel.Opt o) {
        return "ptcg".equals(v.game) && o.label.startsWith("Retreat ");
    }

    private void send(ViewModel.Opt o) {
        if (waiting() || !v.yourTurn) {
            return;
        }
        waiting = true;
        waitingSince = now;
        selected = -1;
        zoomed = -1;
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
        if (worldMode) {
            if (opts != null && opts.size() == 1 && opts.get(0).tray && openPile == null) {
                choose(opts.get(0));
                return;
            }
            if (!card.hidden() || opts != null) {
                zoomed = card.id; // a close-up to read the card, with what you can do with it
                zoomScroll = 0;
            }
            return;
        }
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
        fieldRects.clear();

        ViewModel.Card hover = null;
        String hoverInfo = null;
        hoverKey = "";
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (r.contains(mx, my)) {
                hover = r.card;
                hoverInfo = r.info;
                hoverKey = r.hit == null ? "" : r.hit.key();
                break;
            }
        }
        hoverCard = hover;
        hoverText = hoverInfo;
        if (hover != null) {
            lastPreview = hover;
            lastInfo = hoverInfo == null ? "" : hoverInfo;
        } else if (hoverInfo != null) {
            lastInfo = hoverInfo;
        }

        worldMode = inWorld();
        if (world == null || (duelView && "ygo".equals(v.game))) {
            c.fill(0, 0, w, h, BG);
        } else if (!v.hasBoard()) {
            c.fill(0, 0, w, h, 0xB0000000);
        }
        if (!v.hasBoard()) {
            renderLobby(mx, my);
        } else if (worldMode) {
            renderWorld(mx, my);
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
        if (!worldMode && "ygo".equals(v.game)) renderInteractionLines();
        renderConfirm();
        regions = building;
    }

    private void renderInteractionLines() {
        for (Presentation.Event event : Presentation.lines(v.table, now)) {
            int[] from = fieldRects.get(event.from().key()), to = fieldRects.get(event.to().key());
            for (ViewModel.Side side : v.sides) for (ViewModel.Zone zone : side.zones) {
                if (zone.pile) continue;
                for (ViewModel.Card card : zone.cards) {
                    if (card.tokens.contains(event.source())) from = cardRects.get(card.id);
                    if (card.tokens.contains(event.target())) to = cardRects.get(card.id);
                }
            }
            if (from != null && to != null) Presentation.line(c, from[0]+from[2]/2f, from[1]+from[3]/2f,
                    to[0]+to[2]/2f, to[1]+to[3]/2f, event.kind().equals("attack") ? 0xFFFF3030 : 0xFFFFFFFF);
        }
    }

    // ------------------------------------------------------------------ in the world

    private void renderWorld(double mx, double my) {
        rw = clamp(Math.round(width * 0.24f), 104, 190);
        lw = 0;
        fx0 = 0;
        fx1 = width - rw;
        fy0 = TOP_BAR;
        fy1 = height;
        ch = clamp(height / 5, 30, 80);
        cw = Math.round(ch * ratio());

        // what's on the table, under everything else
        for (Hit hit : world.hits()) {
            String key = hit.key();
            if (key.startsWith("c:")) {
                ViewModel.Card card = cards.get(parseInt(key.substring(2)));
                if (card != null) {
                    building.add(new Region(0, 0, 0, 0, () -> cardClicked(card), card, null, hit));
                }
            } else if (key.startsWith("p:")) {
                String[] parts = key.split(":", 3);
                int sideIndex = parseInt(parts[1]);
                if (sideIndex < 0 || sideIndex > 1) {
                    continue;
                }
                ViewModel.Side side = v.sides.get(sideIndex);
                for (ViewModel.Zone z : side.zones) {
                    if (z.id.equals(parts[2])) {
                        ViewModel.Card top = z.cards.isEmpty() ? null : z.cards.get(z.cards.size() - 1);
                        boolean viewable = top != null && !top.hidden();
                        String info = side.name + "'s " + z.label + ": " + z.count + " card" + (z.count == 1 ? "" : "s")
                                + (viewable ? " (click to look)" : "");
                        String pileKey = sideIndex + ":" + z.id;
                        building.add(new Region(0, 0, 0, 0, viewable ? () -> {
                            openPile = pileKey;
                            trayScroll = 0;
                        } : null, null, info, hit));
                    }
                }
            } else if (key.startsWith("h:")) {
                int sideIndex = parseInt(key.substring(2));
                if (sideIndex >= 0 && sideIndex <= 1) {
                    ViewModel.Side side = v.sides.get(sideIndex);
                    int n = Math.max(side.handCount, side.hand.size());
                    building.add(new Region(0, 0, 0, 0, null, null, side.name + "'s hand: " + n + " card" + (n == 1 ? "" : "s"), hit));
                }
            }
        }
        Set<Integer> usable = v.yourTurn && !waiting() && !v.over ? new HashSet<>(optionsByCard.keySet()) : Set.of();
        world.highlight(usable, hoverKey, zoomed);

        renderScores();
        renderRight(mx, my);
        renderTopBar(mx, my);
        if (openPile == null && zoomed < 0 && confirm == null) {
            renderTooltip(mx, my);
        }
        if (openPile != null) {
            renderPile(mx, my);
        } else if (anyTray && v.yourTurn && !trayHidden) {
            renderTray(mx, my);
        }
        renderZoom();
        if (v.over) {
            renderResult(mx, my);
        }
    }

    /** Both players' scores in the top left corner, with the phase underneath. */
    private void renderScores() {
        int w = clamp(width / 5, 96, 150);
        int h = 34;
        scoreBox(v.sides.get(1), 4, TOP_BAR + 4, w, h);
        scoreBox(v.sides.get(0), 4, TOP_BAR + 8 + h, w, h);
        building.add(new Region(4, TOP_BAR + 4, w, 2 * h + 4, null, null, null));
        String phase = v.phase;
        if (!phase.isEmpty()) {
            float s = fitScale(phase, w, 0.7f);
            int pw = (int) (c.textWidth(phase) * s) + 6;
            int y = TOP_BAR + 12 + 2 * h;
            c.fill(4, y, 4 + pw, y + 10, 0xB0101820);
            c.text(phase, 7, y + 2, v.sides.get(0).active ? GOLD : MUTED, s, false);
        }
    }

    /** The name of what's under the mouse, next to it. */
    private void renderTooltip(double mx, double my) {
        List<String> lines = new ArrayList<>();
        if (hoverCard != null && !hoverKey.isEmpty()) {
            if (hoverCard.hidden()) {
                lines.add("Face-down card");
            } else {
                lines.add(hoverCard.name);
                if (!hoverCard.stat.isEmpty()) {
                    lines.add(hoverCard.stat);
                }
            }
            boolean usable = optionsByCard.containsKey(hoverCard.id) && v.yourTurn && !v.over;
            if (usable) {
                lines.add("Click to use or read it");
            } else if (!hoverCard.hidden()) {
                lines.add("Click to read it");
            }
        } else if (hoverText != null && !hoverKey.isEmpty()) {
            lines.add(hoverText);
        }
        if (lines.isEmpty()) {
            return;
        }
        float s = 0.7f;
        int w = 0;
        for (String l : lines) {
            w = Math.max(w, (int) (c.textWidth(l) * s));
        }
        w += 8;
        int h = lines.size() * 8 + 5;
        int x = (int) mx + 10;
        int y = (int) my + 8;
        if (x + w > width - 2) {
            x = (int) mx - w - 6;
        }
        if (y + h > height - 2) {
            y = height - h - 2;
        }
        c.fill(x, y, x + w, y + h, 0xE0101820);
        c.border(x, y, w, h, PANEL_EDGE);
        int ty = y + 3;
        for (int i = 0; i < lines.size(); i++) {
            int col = i == 0 ? TEXT : i == lines.size() - 1 && lines.size() > 1 ? GOLD : MUTED;
            c.text(lines.get(i), x + 4, ty, col, s, false);
            ty += 8;
        }
    }

    /** A close-up of the card you clicked: big enough to read, with what you can do with it. */
    private void renderZoom() {
        if (zoomed < 0) {
            return;
        }
        ViewModel.Card card = cards.get(zoomed);
        if (card == null) {
            zoomed = -1;
            return;
        }
        c.fill(0, 0, width, height, 0x99000000);
        building.add(new Region(0, 0, width, height, () -> zoomed = -1, null, null)); // a click outside closes it
        int ph = Math.min(height - 40, 300);
        int pw = Math.round(ph * ratio());
        int tw = Math.min(230, width - pw - 36);
        int total = pw + 14 + tw;
        int px = (width - total) / 2;
        int py = (height - ph) / 2 + 6;
        building.add(new Region(px - 6, py - 6, total + 12, ph + 12, null, null, null));
        noStats = true; // the stats are in the text next to it
        drawCard(card, px, py, pw, ph, true);
        noStats = false;
        c.border(px - 1, py - 1, pw + 2, ph + 2, PANEL_EDGE);

        List<ViewModel.Opt> opts = v.yourTurn && !v.over ? optionsByCard.get(card.id) : null;
        List<ViewModel.Opt> buttons = new ArrayList<>();
        ViewModel.Opt retreat = null;
        if (opts != null) {
            for (ViewModel.Opt o : opts) {
                if (isRetreat(o) && retreat == null) {
                    retreat = o;
                } else {
                    buttons.add(o);
                }
            }
        }
        if (retreat != null) {
            // Pokemon TCG: retreat sits at the bottom left of the card, away from the attacks
            ViewModel.Opt r = retreat;
            int bw = Math.max(54, buttonWidth("Retreat") + 10);
            button(px + 4, py + ph - 20, bw, 16, "Retreat", !waiting(), Style.RED, () -> choose(r));
        }

        int tx = px + pw + 14;
        c.fill(tx - 6, py - 6, tx + tw + 6, py + ph + 6, 0xF0101820);
        c.border(tx - 6, py - 6, tw + 12, ph + 12, opts != null ? GOLD : PANEL_EDGE);
        button(tx + tw - 40, py - 2, 40, 12, "Close", true, Style.QUIET, () -> zoomed = -1);
        int bh = 15;
        int btnTop = py + ph - buttons.size() * (bh + 2);
        int textBottom = btnTop - 4;
        c.scissor(tx - 4, py + 12, tx + tw + 4, textBottom);
        int ty = py + 14 - (int) zoomScroll;
        int start = ty;
        for (String l : wrap(card.hidden() ? "Face-down card" : card.name, (int) (tw / 1.1f))) {
            c.text(l, tx, ty, TEXT, 1.1f, true);
            ty += 12;
        }
        if (!card.hidden()) {
            if (!card.stat.isEmpty()) {
                c.text(card.stat, tx, ty, GOOD, 0.85f, false);
                ty += 10;
            }
            if (!card.tags.isEmpty()) {
                for (String l : wrap(String.join(" · ", card.tags), (int) (tw / 0.75f))) {
                    c.text(l, tx, ty, GOLD, 0.75f, false);
                    ty += 8;
                }
            }
            ty += 3;
            for (String t : card.text) {
                for (String l : wrap(t, (int) (tw / 0.75f))) {
                    c.text(l, tx, ty, 0xFFDCE3EA, 0.75f, false);
                    ty += 8;
                }
                ty += 3;
            }
        }
        c.noScissor();
        zoomScroll = Math.min(zoomScroll, Math.max(0, (ty + (int) zoomScroll - start) - (textBottom - py - 14)));
        if (ty > textBottom) {
            c.text("scroll for more", tx + tw - c.textWidth("scroll for more") * 0.55f, textBottom - 6, DIM, 0.55f, false);
        }
        int by = btnTop;
        for (ViewModel.Opt o : buttons) {
            button(tx, by, tw, bh, o.shortLabel, !waiting(), Style.GOLD, () -> choose(o));
            by += bh + 2;
        }
        if (opts == null && v.yourTurn && !v.over && !optionsByCard.isEmpty()) {
            c.text("Nothing to do with this card right now.", tx, py + ph - 8, DIM, 0.6f, false);
        }
    }

    /** "Are you sure?" on top of everything. */
    private void renderConfirm() {
        if (confirm == null) {
            return;
        }
        Confirm cf = confirm;
        building.add(new Region(0, 0, width, height, null, null, null));
        c.fill(0, 0, width, height, 0x99000000);
        int w = Math.min(240, width - 20);
        List<String> lines = wrap(cf.text(), (int) ((w - 16) / 0.8f));
        int h = 26 + lines.size() * 9 + 26;
        int x = width / 2 - w / 2;
        int y = height / 2 - h / 2;
        c.fill(x, y, x + w, y + h, 0xFF101820);
        c.border(x, y, w, h, RED);
        c.text(cf.title(), x + 8, y + 7, TEXT, 1.2f, true);
        int ty = y + 22;
        for (String l : lines) {
            c.text(l, x + 8, ty, MUTED, 0.8f, false);
            ty += 9;
        }
        int bw = (w - 24) / 2;
        button(x + 8, y + h - 20, bw, 14, cf.yes(), true, Style.RED, () -> {
            confirm = null;
            cf.action().run();
        });
        button(x + w - 8 - bw, y + h - 20, bw, 14, "Cancel", true, Style.NORMAL, () -> confirm = null);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
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
        int cols = 5 + sideCols(true) + sideCols(false);
        int byWidth = (int) (((fx1 - fx0) - 2 * cols * GAP) / (float) cols / ratio);
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
        int pitch = cw + 2 * GAP;
        int leftX = fx0 + 2 * GAP;
        int rightX = fx1 - 2 * GAP - cw;
        int cx = centerX();
        int leftIdx = 0;
        int rightIdx = 0;
        for (ViewModel.Zone z : side.zones) {
            if (z.row != row) {
                continue;
            }
            if (!z.align.equals("center")) {
                // side columns: piles (Deck, GY...) and single zones (Field Spell), outward from the middle
                boolean left = z.align.equals("left");
                if (opponent && "ygo".equals(v.game)) left = !left;
                int x = left ? leftX + (sideCols(true) - 1 - leftIdx++) * pitch : rightX - (sideCols(false) - 1 - rightIdx++) * pitch;
                fieldRects.put((opponent ? 1-v.seat : v.seat) + ":" + z.id + ":0",new int[]{x,y,cw,ch});
                if (z.pile) {
                    drawPile(side, z, x, y, opponent);
                } else {
                    c.fill(x, y, x + cw, y + ch, SLOT);
                    c.border(x, y, cw, ch, SLOT_EDGE);
                    if (z.cards.isEmpty()) {
                        float s = fitScale(z.label, cw - 2, 0.45f);
                        c.text(z.label, x + cw / 2f - c.textWidth(z.label) * s / 2f, y + ch / 2f - 2, 0x66FFFFFF, s, false);
                    } else {
                        drawTableCard(z.cards.get(0), x, y, mx, my, z.label);
                    }
                }
                continue;
            }
            int zw = z.slots * pitch - 2 * GAP;
            int x0 = cx - zw / 2;
            for (int i = 0; i < z.slots; i++) {
                int sx = x0 + i * pitch;
                fieldRects.put((opponent ? 1-v.seat : v.seat) + ":" + z.id + ":" + (opponent ? z.slots-1-i : i), new int[]{sx,y,cw,ch});
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

    /** How many columns of side zones (piles, Field Spell) there are on the left or right, on either side of the table. */
    private int sideCols(boolean left) {
        int max = 0;
        for (ViewModel.Side side : v.sides) {
            for (int row = 0; row < 2; row++) {
                int n = 0;
                for (ViewModel.Zone z : side.zones) {
                    if (z.row == row && z.align.equals(left ? "left" : "right")) {
                        n++;
                    }
                }
                max = Math.max(max, n);
            }
        }
        if ("ygo".equals(v.game)) {
            // Mirror side zones without shifting the central five columns.
            for (ViewModel.Side side : v.sides) for (int row = 0; row < 2; row++) {
                final int rr = row;
                for (String align : List.of("left", "right"))
                    max = Math.max(max, (int) side.zones.stream().filter(z -> z.row == rr && z.align.equals(align)).count());
            }
        }
        return Math.max(1, max);
    }

    /** The middle of the space between the side columns, where the main zones are centred. */
    private int centerX() {
        int pitch = cw + 2 * GAP;
        int from = fx0 + 2 * GAP + sideCols(true) * pitch;
        int to = fx1 - 2 * GAP - sideCols(false) * pitch;
        return (from + to) / 2;
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
        cardRects.put(card.id, new int[]{x, y, w, h});
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
        if (!large && Presentation.conceals(v.table, card, now)) return;
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

    /** A plain card face showing the real card's name and stats, while its picture loads or when pictures are off. */
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
        building.add(new Region(x0, TOP_BAR, rw, height - TOP_BAR, null, null, null));
        c.fill(x0, TOP_BAR, width, height, worldMode ? 0xB8121821 : PANEL);
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
                button(x, y, w, 15, "More choices (" + trayCount + ")", true, Style.GOLD, () -> trayHidden = false);
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
        building.add(new Region(0, 0, width, TOP_BAR, null, null, null));
        c.fill(0, 0, width, TOP_BAR, worldMode ? 0xC0161D27 : 0xFF161D27);
        c.fill(0, TOP_BAR - 1, width, TOP_BAR, PANEL_EDGE);
        int x = width - 3;
        String close = v.over ? "Close" : "Hide";
        x -= buttonWidth(close);
        button(x, 2, buttonWidth(close), 10, close, true, Style.NORMAL, actions::close);
        if ("ygo".equals(v.game)) {
            String mode = "View Mode: " + (duelView ? "Duel View" : "Default View");
            x -= buttonWidth(mode) + 3;
            button(x, 2, buttonWidth(mode), 10, mode, true, Style.QUIET, () -> {
                duelView = !duelView;
                selected = zoomed = -1;
                regions.clear();
                if (world != null) world.highlight(Set.of(), "", -1);
            });
        }
        String art = "Card art: " + (actions.artEnabled() ? "on" : "off");
        x -= buttonWidth(art) + 3;
        button(x, 2, buttonWidth(art), 10, art, true, Style.QUIET, actions::toggleArt);
        if (v.canConcede) {
            String label = "Concede";
            x -= buttonWidth(label) + 3;
            button(x, 2, buttonWidth(label), 10, label, true, Style.RED, () -> confirm = new Confirm("Concede?",
                    "Are you sure you want to concede? The game ends and your opponent wins.", "Concede", actions::concede));
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
        button(x + 72, height - 24, 85, 13, "Deck builder", true, Style.NORMAL, actions::deckBuilder);
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
