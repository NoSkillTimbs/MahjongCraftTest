package com.tablecards.client;

import com.tablecards.engine.Json;
import java.util.*;

/** Client-only, transient cosmetics. No decisions, ownership changes or game-state mutation. */
public final class Presentation {
    public record Anchor(int seat, String zone, int slot) {
        static Anchor read(Object raw) {
            if (!(raw instanceof Map<?,?>)) return new Anchor(-1,"",-1);
            Map<String,Object> m=Json.obj(raw);
            return new Anchor(Json.num(m,"seat",-1),Json.str(m,"zone",""),Json.num(m,"slot",-1));
        }
        public String key() { return seat + ":" + zone + ":" + slot; }
    }
    public record Event(String table, String game, String kind, int source, int target,
                        ViewModel.Card card, Anchor from, Anchor to, long start, long end) {}
    private static final List<Event> EVENTS = new ArrayList<>();
    private static final long REVEAL_MS = 1000;
    private Presentation() {}
    public static void clear() { EVENTS.clear(); }
    public static void close(int[] table) { EVENTS.removeIf(e -> e.table.equals(key(table))); }
    private static String key(int[] table) { return table == null ? "" : TableViews.key(table[0], table[1], table[2]); }
    public static void accept(String json, long now) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        List<Object> t = Json.arr(m.get("table"));
        if (t.size() != 3) return;
        String table = TableViews.key(((Number)t.get(0)).intValue(), ((Number)t.get(1)).intValue(), ((Number)t.get(2)).intValue());
        // Ignore unsolicited/late events for a table we have not received or have already left.
        if (TableViews.get(((Number)t.get(0)).intValue(), ((Number)t.get(1)).intValue(), ((Number)t.get(2)).intValue()) == null) return;
        EVENTS.removeIf(e -> e.end <= now);
        long next = EVENTS.stream().filter(e -> e.kind.equals("reveal")).mapToLong(Event::end).max().orElse(now);
        for (Object raw : Json.arr(m.get("events"))) {
            Map<String,Object> e = Json.obj(raw);
            String kind = Json.str(e, "kind", "");
            boolean reveal = kind.equals("reveal");
            if (!reveal && !kind.equals("attack") && !kind.equals("target")) continue;
            long start = reveal ? Math.max(now, next) : now;
            // Bound cosmetic backlog without dropping any engine actions or delaying gameplay.
            if (start > now + 8000 || EVENTS.size() >= 128) break;
            EVENTS.add(new Event(table, Json.str(m, "game", ""), kind,
                    Json.num(e,"source",-1), Json.num(e,"target",-1),
                    reveal ? new ViewModel.Card(Json.obj(e.get("card"))) : null,
                    Anchor.read(e.get("from")), Anchor.read(e.get("to")),
                    start, start + (reveal ? REVEAL_MS : 1400)));
            if (reveal) next = start + REVEAL_MS;
        }
    }
    public static List<Event> lines(int[] table, long now) {
        EVENTS.removeIf(e -> e.end <= now);
        return EVENTS.stream().filter(e -> e.table.equals(key(table)) && !e.kind.equals("reveal") && e.start <= now).toList();
    }
    public static boolean conceals(int[] table, ViewModel.Card card, long now) {
        return card != null && EVENTS.stream().anyMatch(e -> e.end > now && e.kind.equals("reveal")
                && e.table.equals(key(table)) && card.tokens.contains(e.source));
    }
    public static void overlay(Canvas canvas, int w, int h, long now, boolean artwork) {
        EVENTS.removeIf(e -> e.end <= now);
        Event e = EVENTS.stream().filter(x -> x.card != null && x.start <= now).findFirst().orElse(null);
        if (e == null) return;
        double remaining = Math.min(1, (e.end - now) / 160.0);
        int ch = Math.max(20, (int)(h * .78 * (.75 + .25 * remaining)));
        int cw = (int)(ch * (e.game.equals("ygo") ? .686 : .716));
        canvas.fill(0, 0, w, h, ((int)(100 * remaining)) << 24);
        CardUi.draw(canvas, e.card, (w - cw) / 2, (h - ch) / 2, cw, ch, artwork);
    }
    public static void line(Canvas c, float x1, float y1, float x2, float y2, int color) {
        int steps = Math.max(1, (int)Math.hypot(x2-x1, y2-y1));
        for (int i=0; i<=steps; i++) {
            int x=Math.round(x1+(x2-x1)*i/steps), y=Math.round(y1+(y2-y1)*i/steps);
            c.fill(x-1,y-1,x+2,y+2,color);
        }
        double a=Math.atan2(y2-y1,x2-x1);
        for(int side : new int[]{-1,1}) {
            double wing=a+side*.55;
            for(int i=0;i<9;i++) {
                int x=(int)(x2-Math.cos(wing)*i), y=(int)(y2-Math.sin(wing)*i);
                c.fill(x-1,y-1,x+2,y+2,color);
            }
        }
    }
}
