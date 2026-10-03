package com.tablecards.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** The board view sent by the server (see TableSession#viewJson), parsed for the screen. */
public final class ViewModel {
    public record Section(String title, List<String> lines) {
    }

    public final String title;
    public final int seq;
    public final List<Section> sections = new ArrayList<>();
    public final List<String> options = new ArrayList<>();
    public final List<String> log = new ArrayList<>();
    public final String prompt;
    public final boolean yourTurn;
    public final boolean over;
    public final boolean canConcede;

    private ViewModel(JsonObject o) {
        title = o.get("title").getAsString();
        seq = o.get("seq").getAsInt();
        for (JsonElement e : o.getAsJsonArray("sections")) {
            JsonObject s = e.getAsJsonObject();
            sections.add(new Section(s.get("title").getAsString(), strings(s.getAsJsonArray("lines"))));
        }
        options.addAll(strings(o.getAsJsonArray("options")));
        log.addAll(strings(o.getAsJsonArray("log")));
        prompt = o.get("prompt").getAsString();
        yourTurn = o.get("yourTurn").getAsBoolean();
        over = o.get("over").getAsBoolean();
        canConcede = o.get("canConcede").getAsBoolean();
    }

    public static ViewModel parse(String json) {
        return new ViewModel(JsonParser.parseString(json).getAsJsonObject());
    }

    private static List<String> strings(JsonArray arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            for (JsonElement e : arr) {
                out.add(e.getAsString());
            }
        }
        return out;
    }
}
