package com.tablecards;

import com.tablecards.engine.*;
import com.tablecards.engine.ygo.*;
import com.tablecards.engine.ptcg.*;
import com.tablecards.net.DeckBuilderPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.*;

/** Server catalog and validation against the same snapshot/rules used by actual games. */
public final class DeckBuilderService {
    public static final int PAGE_SIZE = 12;
    private static final Map<UUID, Long> LAST = new HashMap<>();
    public static void forget(UUID id) { LAST.remove(id); }
    private DeckBuilderService() {}
    public static void receive(ServerPlayerEntity player, DeckBuilderPayload packet) {
        long tick = player.getServer().getTicks();
        Long last = LAST.put(player.getUuid(), tick);
        if (last != null && tick == last) return;
        Map<String,Object> result = new LinkedHashMap<>();
        try {
            requireFlatRequest(packet.json());
            Map<String,Object> request = Json.obj(Json.parse(packet.json()));
            String game = Json.str(request,"game","");
            if (!game.equals("ygo") && !game.equals("ptcg")) return;
            String op = Json.str(request,"op","");
            result.put("request",Json.num(request,"request",0));
            result.put("game",game); result.put("op",op);
            CardPools.Snapshot pool = CardPools.get();
            boolean ygo = game.equals("ygo");
            if (op.equals("browse")) {
                String query = Json.str(request,"query","").toLowerCase(Locale.ROOT);
                if (query.length() > 100) return;
                int page = Math.max(0, Math.min(10000, Json.num(request,"page",0)));
                List<Map<String,Object>> entries = new ArrayList<>();
                // Lightweight card definitions only; instantiate UI rows for one page on the client.
                if (ygo) {
                    var cards = pool.ygo.cards.values().stream().filter(c ->
                            (c.name+" "+c.kind+" "+c.frame+" "+c.attribute+" "+c.text).toLowerCase(Locale.ROOT).contains(query))
                            .sorted(Comparator.comparing(c -> c.name)).toList();
                    result.put("total",cards.size());
                    cards.stream().skip((long)page*PAGE_SIZE).limit(PAGE_SIZE).forEach(c -> entries.add(entry(c,"main")));
                } else {
                    var cards = pool.ptcg.cards.values().stream().filter(c ->
                            (c.name+" "+c.kind+" "+c.type+" "+c.set).toLowerCase(Locale.ROOT).contains(query))
                            .sorted(Comparator.comparing(c -> c.name)).toList();
                    result.put("total",cards.size());
                    cards.stream().skip((long)page*PAGE_SIZE).limit(PAGE_SIZE).forEach(c -> entries.add(entry(c)));
                }
                result.put("entries",entries); result.put("page",page);
            } else if (op.equals("load") || op.equals("use")) {
                String text = Json.str(request,"text","");
                if (text.length() > 65536) throw new IllegalArgumentException("Deck file exceeds 64 KiB");
                List<String> problems;
                Object deck;
                List<Map<String,Object>> rows = new ArrayList<>();
                if (ygo) {
                    var parsed = DeckLists.ydk(text,pool.ygo);
                    problems = parsed.problems(); deck = parsed.deck();
                    var doc = DeckLists.readYdk(text,pool.ygo);
                    if (op.equals("load")) {
                        problems = new ArrayList<>(problems);
                        for (String problem : doc.problems()) if (!problems.contains(problem)) problems.add(problem);
                    }
                    doc.main().forEach(c -> rows.add(entry(c,"main")));
                    doc.extra().forEach(c -> rows.add(entry(c,"extra")));
                    doc.side().forEach(c -> rows.add(entry(c,"side")));
                } else {
                    var parsed = DeckLists.ptcg(text,pool.ptcg);
                    problems = parsed.problems(); deck = parsed.deck();
                    parsed.deck().forEach(c -> rows.add(entry(c)));
                }
                // A grouped deck response stays small even for malformed repeated entries.
                Map<String,Map<String,Object>> grouped = new LinkedHashMap<>();
                for (var row : rows) {
                    String key = row.get("section")+":"+row.get("key");
                    var previous = grouped.get(key);
                    if (previous == null) { row.put("count",1); grouped.put(key,row); }
                    else previous.put("count",((Number)previous.get("count")).intValue()+1);
                }
                if (grouped.size() > 100) throw new IllegalArgumentException("Too many distinct cards to edit; reduce the file first");
                result.put("entries",new ArrayList<>(grouped.values()));
                result.put("problems",problems.stream().limit(20).toList());
                if (op.equals("use")) {
                    boolean accepted = problems.isEmpty() && Sessions.useBuiltDeck(player,
                            ygo ? GameType.YUGIOH : GameType.POKEMON, deck, Json.str(request,"name","Local deck"));
                    result.put("accepted",accepted);
                    if (!accepted && problems.isEmpty()) result.put("problems",List.of("Open your table's deck-selection lobby first; your deck must not already be selected."));
                }
            } else return;
        } catch (RuntimeException ex) {
            result.put("problems",List.of("Could not read deck: " + ex.getMessage()));
        }
        String response = JsonWriter.write(result);
        // Leave room for packet framing/UTF-8 expansion. Imported descriptions are unbounded data.
        if (response.length() > 240000 || response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 800000) {
            result.remove("entries");
            result.put("problems",List.of("Card descriptions exceed the response limit; narrow the search or reduce this deck file."));
            response = JsonWriter.write(result);
        }
        ServerPlayNetworking.send(player,new DeckBuilderPayload(response));
    }
    /** Requests only contain scalars. Reject nested user JSON before the recursive parser sees it. */
    private static void requireFlatRequest(String json) {
        boolean quoted=false, escaped=false;
        int depth=0;
        for (int i=0;i<json.length();i++) {
            char c=json.charAt(i);
            if (quoted) {
                if (escaped) escaped=false;
                else if (c=='\\') escaped=true;
                else if (c=='"') quoted=false;
            } else if (c=='"') quoted=true;
            else if (c=='[' || (c=='{' && ++depth>1)) throw new IllegalArgumentException("Nested request");
            else if (c=='}') depth--;
        }
    }

    private static Map<String,Object> entry(YgoCard c, String section) {
        var row = new LinkedHashMap<String,Object>();
        row.put("key",c.id); row.put("code",c.codes.isEmpty()?"":c.codes.get(0));
        row.put("extra",c.isExtra()); row.put("section",section.equals("side")?"side":c.isExtra()?"extra":"main");
        row.put("card",ViewJson.snapshot(YgoGame.definitionView(c)));
        return row;
    }
    private static Map<String,Object> entry(PtcgCard c) {
        var row = new LinkedHashMap<String,Object>();
        row.put("key",c.id); row.put("code",c.name + (c.set.isEmpty()?"":" " + c.set + " " + c.number));
        row.put("extra",false); row.put("section","main");
        row.put("card",ViewJson.snapshot(PtcgGame.cardView(c)));
        return row;
    }
}
