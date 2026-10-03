package com.tablecards.engine;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link Board} and the pending decision into the JSON the game screen draws: both sides
 * with numbered cards, and each option tied to the card(s) it's about. Engine objects never leave
 * the server; only the numbers do.
 */
public final class ViewJson {
    private final Map<Object, List<Integer>> idsByRef = new IdentityHashMap<>();
    private final Map<Integer, Board.CardView> byId = new LinkedHashMap<>();
    private int next;

    private ViewJson() {
    }

    /**
     * Adds "game", "phase", "help", "sides" (you first) and, if {@code decision} is this player's,
     * "options" and "tray" (cards to pick from that aren't on the table) to {@code out}.
     */
    public static void write(CardGame game, int seat, Decision decision, Map<String, Object> out) {
        new ViewJson().fill(game, seat, decision, out);
    }

    private void fill(CardGame game, int seat, Decision decision, Map<String, Object> out) {
        Board b = game.board(seat);
        out.put("game", b.game);
        out.put("phase", b.phase);
        out.put("help", b.help);
        out.put("sides", List.of(side(b.you), side(b.opp)));

        List<Object> options = new ArrayList<>();
        List<Object> tray = new ArrayList<>();
        Map<Object, Integer> trayIds = new IdentityHashMap<>();
        if (decision != null && decision.player() == seat) {
            for (Option o : decision.options()) {
                Map<String, Object> opt = new LinkedHashMap<>();
                opt.put("label", o.label());
                Object focus = game.focus(o);
                List<Integer> ids = focus == null ? null : idsByRef.get(focus);
                boolean inTray = false;
                if (focus != null && ids == null) {
                    Integer id = trayIds.get(focus);
                    if (id == null) {
                        Board.CardView v = game.cardView(focus, seat);
                        if (v != null) {
                            id = next++;
                            byId.put(id, v);
                            trayIds.put(focus, id);
                            tray.add(card(v, id));
                        }
                    }
                    if (id != null) {
                        ids = List.of(id);
                        inTray = true;
                    }
                }
                opt.put("cards", ids == null ? List.of() : ids);
                opt.put("tray", inTray);
                opt.put("short", ids == null ? o.label() : shortLabel(o.label(), ids));
                options.add(opt);
            }
        }
        out.put("options", options);
        out.put("tray", tray);
    }

    private Map<String, Object> side(Board.Side s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name);
        m.put("score", s.score);
        m.put("scoreLabel", s.scoreLabel);
        m.put("info", s.info);
        m.put("active", s.active);
        m.put("handCount", s.handCount);
        List<Object> hand = new ArrayList<>();
        for (Board.CardView c : s.hand) {
            hand.add(card(c));
        }
        m.put("hand", hand);
        List<Object> zones = new ArrayList<>();
        for (Board.Zone z : s.zones) {
            Map<String, Object> zm = new LinkedHashMap<>();
            zm.put("id", z.id);
            zm.put("label", z.label);
            zm.put("row", z.row);
            zm.put("align", z.align.name().toLowerCase());
            zm.put("slots", z.slots);
            zm.put("pile", z.pile);
            zm.put("count", z.pile ? z.count : z.cards.size());
            List<Object> cards = new ArrayList<>();
            for (Board.CardView c : z.cards) {
                // cards inside piles aren't clickable on the table; options about them use the tray
                cards.add(z.pile ? card(c, next++, false) : card(c));
            }
            zm.put("cards", cards);
            zones.add(zm);
        }
        m.put("zones", zones);
        return m;
    }

    private Map<String, Object> card(Board.CardView c) {
        return card(c, next++);
    }

    private Map<String, Object> card(Board.CardView c, int id) {
        return card(c, id, true);
    }

    private Map<String, Object> card(Board.CardView c, int id, boolean clickable) {
        byId.put(id, c);
        if (clickable) {
            for (Object r : c.refs) {
                List<Integer> ids = idsByRef.computeIfAbsent(r, k -> new ArrayList<>());
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", c.name);
        m.put("image", c.image);
        m.put("frame", c.frame);
        m.put("down", c.faceDown);
        m.put("peek", c.peek);
        m.put("side", c.sideways);
        m.put("corner", c.corner);
        m.put("stat", c.stat);
        m.put("badge", c.badge);
        m.put("tags", c.tags);
        m.put("text", c.text);
        List<Object> att = new ArrayList<>();
        for (Board.CardView a : c.attached) {
            att.add(card(a, next++, clickable));
        }
        m.put("att", att);
        return m;
    }

    /** The label without the card's own description, since the card is what was clicked. */
    private String shortLabel(String label, List<Integer> ids) {
        String best = null;
        for (int id : ids) {
            for (String a : byId.get(id).aliases) {
                if (label.contains(a) && (best == null || a.length() > best.length())) {
                    best = a;
                }
            }
        }
        if (best == null) {
            return label;
        }
        String s = label.replace(best, " ").replaceAll("\\s+", " ").replace(" :", ":").trim();
        if (s.endsWith(":")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? "Choose" : s;
    }
}
