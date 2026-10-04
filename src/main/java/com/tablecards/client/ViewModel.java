package com.tablecards.client;

import com.tablecards.engine.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The table as the server sent it (see TableSession#viewJson and engine/ViewJson). Plain Java, no
 * Minecraft classes, so the screen logic can be tested outside the game.
 */
public final class ViewModel {
    public static final class Card {
        public final int id;
        public final String name;
        public final String image;
        public final String frame;
        public final boolean down;
        public final boolean peek;
        public final boolean side;
        public final String corner;
        public final String stat;
        public final String badge;
        public final List<String> tags;
        public final List<String> text;
        public final List<Card> att = new ArrayList<>();

        Card(Map<String, Object> m) {
            id = Json.num(m, "id", -1);
            name = Json.str(m, "name", "");
            image = Json.str(m, "image", "");
            frame = Json.str(m, "frame", "");
            down = bool(m, "down");
            peek = bool(m, "peek");
            side = bool(m, "side");
            corner = Json.str(m, "corner", "");
            stat = Json.str(m, "stat", "");
            badge = Json.str(m, "badge", "");
            tags = strings(m.get("tags"));
            text = strings(m.get("text"));
            for (Object o : Json.arr(m.get("att"))) {
                att.add(new Card(Json.obj(o)));
            }
        }

        /** Face-down and not ours to look at. */
        public boolean hidden() {
            return down && !peek;
        }
    }

    public static final class Zone {
        public final String id;
        public final String label;
        public final int row;
        public final String align;
        public final int slots;
        public final boolean pile;
        public final int count;
        public final List<Card> cards = new ArrayList<>();

        Zone(Map<String, Object> m) {
            id = Json.str(m, "id", "");
            label = Json.str(m, "label", "");
            row = Json.num(m, "row", 0);
            align = Json.str(m, "align", "center");
            slots = Math.max(1, Json.num(m, "slots", 1));
            pile = bool(m, "pile");
            count = Json.num(m, "count", 0);
            for (Object o : Json.arr(m.get("cards"))) {
                cards.add(new Card(Json.obj(o)));
            }
        }
    }

    public static final class Side {
        public final String name;
        public final String score;
        public final String scoreLabel;
        public final List<String> info;
        public final boolean active;
        public final int handCount;
        public final List<Card> hand = new ArrayList<>();
        public final List<Zone> zones = new ArrayList<>();

        Side(Map<String, Object> m) {
            name = Json.str(m, "name", "");
            score = Json.str(m, "score", "");
            scoreLabel = Json.str(m, "scoreLabel", "");
            info = strings(m.get("info"));
            active = bool(m, "active");
            handCount = Json.num(m, "handCount", 0);
            for (Object o : Json.arr(m.get("hand"))) {
                hand.add(new Card(Json.obj(o)));
            }
            for (Object o : Json.arr(m.get("zones"))) {
                zones.add(new Zone(Json.obj(o)));
            }
        }
    }

    /** One answer to the current question; {@code cards} are the ids of the cards it's about. */
    public static final class Opt {
        public final int index;
        public final String label;
        public final String shortLabel;
        public final List<Integer> cards = new ArrayList<>();
        public final boolean tray;

        Opt(int index, Map<String, Object> m) {
            this.index = index;
            label = Json.str(m, "label", "");
            shortLabel = Json.str(m, "short", label);
            for (Object o : Json.arr(m.get("cards"))) {
                cards.add(((Number) o).intValue());
            }
            tray = bool(m, "tray");
        }
    }

    public final String title;
    public final int seq;
    /** waiting, choosing_decks, playing or over. */
    public final String state;
    public final int[] table;
    public final String you;
    /** Plain choices before the game starts (play a bot, the deck list). */
    public final List<String> menu;
    public final String info;
    public final String prompt;
    public final boolean yourTurn;
    public final boolean over;
    public final boolean canConcede;
    /** "ygo" or "ptcg"; "" before the game starts. */
    public final String game;
    public final String phase;
    public final String help;
    /** You first, then your opponent; empty before the game starts. */
    public final List<Side> sides = new ArrayList<>();
    public final List<Opt> options = new ArrayList<>();
    public final List<Card> tray = new ArrayList<>();
    public final List<String> log;
    public final String winner;
    /** The seat whose side is sides[0] (0 for people watching). */
    public final int seat;
    /** A view for someone watching: no hidden cards, no choices. */
    public final boolean watcher;
    /** Which side of the table seat 0 sits on: north, south, east or west. */
    public final String dir0;
    /** Seat 1 is the bot. */
    public final boolean bot1;
    /** The table is free again ("closed"). */
    public final boolean closed;

    private ViewModel(Map<String, Object> o) {
        title = Json.str(o, "title", "");
        seq = Json.num(o, "seq", 0);
        state = Json.str(o, "state", "");
        List<Object> t = Json.arr(o.get("table"));
        table = t.size() == 3
                ? new int[]{((Number) t.get(0)).intValue(), ((Number) t.get(1)).intValue(), ((Number) t.get(2)).intValue()}
                : null;
        you = Json.str(o, "you", "");
        menu = strings(o.get("menu"));
        info = Json.str(o, "info", "");
        prompt = Json.str(o, "prompt", "");
        yourTurn = bool(o, "yourTurn");
        over = bool(o, "over");
        canConcede = bool(o, "canConcede");
        game = Json.str(o, "game", "");
        phase = Json.str(o, "phase", "");
        help = Json.str(o, "help", "");
        for (Object s : Json.arr(o.get("sides"))) {
            sides.add(new Side(Json.obj(s)));
        }
        List<Object> opts = Json.arr(o.get("options"));
        for (int i = 0; i < opts.size(); i++) {
            options.add(new Opt(i, Json.obj(opts.get(i))));
        }
        for (Object c : Json.arr(o.get("tray"))) {
            tray.add(new Card(Json.obj(c)));
        }
        log = strings(o.get("log"));
        winner = Json.str(o, "winner", "");
        seat = Json.num(o, "seat", 0);
        watcher = bool(o, "public");
        dir0 = Json.str(o, "dir0", "south");
        bot1 = bool(o, "bot1");
        closed = state.equals("closed");
    }

    public static ViewModel parse(String json) {
        return new ViewModel(Json.obj(Json.parse(json)));
    }

    public boolean hasBoard() {
        return sides.size() == 2;
    }

    static boolean bool(Map<String, Object> m, String key) {
        return m.get(key) instanceof Boolean b && b;
    }

    static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        for (Object x : Json.arr(o)) {
            out.add(String.valueOf(x));
        }
        return out;
    }
}
