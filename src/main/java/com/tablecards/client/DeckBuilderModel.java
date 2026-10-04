package com.tablecards.client;

import com.tablecards.engine.Json;
import java.util.*;

/** Small editor state; catalog definitions are supplied by the server, not duplicated here. */
public final class DeckBuilderModel {
    public record Entry(String key, String code, String section, boolean extra, ViewModel.Card card) {
        static Entry read(Map<String,Object> row) {
            return new Entry(Json.str(row,"key",""),Json.str(row,"code",""),Json.str(row,"section","main"),
                    Boolean.TRUE.equals(row.get("extra")),new ViewModel.Card(Json.obj(row.get("card"))));
        }
        Entry in(String section) { return new Entry(key,code,section,extra,card); }
    }
    public String game = "ygo";
    public final List<Entry> deck = new ArrayList<>();
    public final List<Entry> catalog = new ArrayList<>();
    public int page, total;
    public boolean dirty;
    public void add(Entry entry, boolean side) {
        if (entry.code.isBlank()) throw new IllegalArgumentException("This card has no export ID");
        // Safety bound only; legality remains the existing game's validation policy.
        if (deck.size() >= 1000) throw new IllegalArgumentException("Editor safety limit reached");
        deck.add(entry.in(game.equals("ygo") ? side ? "side" : entry.extra ? "extra" : "main" : "main"));
        dirty = true;
    }
    public String serialize() {
        StringBuilder text = new StringBuilder();
        if (game.equals("ygo")) {
            text.append("#created by MahjongCraft\n");
            for (String section : List.of("main","extra","side")) {
                text.append(section.equals("side")?"!side\n":"#"+section+"\n");
                for (Entry e : deck) if (e.section.equals(section)) {
                    if (!e.code.matches("\\d{1,10}")) throw new IllegalArgumentException("Invalid YDK passcode");
                    text.append(e.code).append('\n');
                }
            }
        } else {
            Map<String,Integer> counts = new LinkedHashMap<>();
            for (Entry e : deck) counts.merge(e.code,1,Integer::sum);
            counts.forEach((code,n) -> text.append(n).append(' ').append(code).append('\n'));
        }
        return text.toString();
    }
    public void readDeck(List<Object> rows) {
        deck.clear();
        for (Object raw : rows) {
            Map<String,Object> row = Json.obj(raw);
            Entry entry = Entry.read(row);
            int count = Math.max(0,Math.min(1000-deck.size(),Json.num(row,"count",1)));
            for (int n=0;n<count;n++) deck.add(entry);
        }
        dirty = false;
    }
}
