package com.tablecards;

import com.tablecards.engine.DeckLists;
import com.tablecards.engine.ptcg.PtcgAutoDecks;
import com.tablecards.engine.ptcg.PtcgCard;
import com.tablecards.engine.ptcg.PtcgLibrary;
import com.tablecards.engine.ygo.YgoAutoDecks;
import com.tablecards.engine.ygo.YgoCard;
import com.tablecards.engine.ygo.YgoLibrary;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Every card and deck the tables can use: the built-in starter cards, real cards imported with
 * /tablecards import (config/tablecards/imported/), decks auto-built from them, and players' own
 * deck lists (config/tablecards/decks/*.ydk and *.txt). Reloading builds a new snapshot and swaps
 * it in; games already running keep their own cards.
 */
public final class CardPools {
    public static final String README = """
            Put your own deck lists in this folder, then run /tablecards reload (or restart the server).

            Yu-Gi-Oh!: .ydk files (the format YGOPRODeck, Duelingbook exports, EDOPro and Master Duel tools use).
              Only the Main Deck is used. Every card must be one the game can play exactly as printed:
              Normal Monsters and the supported Spells and Traps (see /tablecards decks).

            Pokemon TCG: .txt files in the Pokemon TCG Live export format, for example
              Pokémon: 16
              4 Scorbunny SSH 30
              ...
              Trainer: 24
              4 Professor's Research SVI 189
              ...
              Energy: 20
              20 Basic {R} Energy SVE 2
              Exactly 60 cards; every card must be one the game can play exactly as printed.

            Real cards have to be imported first, with /tablecards import (needs operator permission).
            /tablecards decks lists the decks that loaded and explains any that didn't.
            """;

    /** One consistent set of libraries and decks. */
    public static final class Snapshot {
        public final YgoLibrary ygo = new YgoLibrary();
        public final PtcgLibrary ptcg = new PtcgLibrary();
        public final Map<String, List<YgoCard>> ygoDecks = new LinkedHashMap<>();
        public final Map<String, List<PtcgCard>> ptcgDecks = new LinkedHashMap<>();
        public final List<String> problems = new ArrayList<>();
        public int realYgoCards;
        public int realPtcgCards;
    }

    private static volatile Snapshot current;
    private static volatile Path dir;

    private CardPools() {
    }

    public static Snapshot get() {
        Snapshot s = current;
        if (s == null) {
            s = load(dir);
        }
        return s;
    }

    public static Path dir() {
        return dir;
    }

    /** Loads everything from {@code configDir} (null: built-in cards only) and makes it current. */
    public static synchronized Snapshot load(Path configDir) {
        dir = configDir;
        Snapshot s = new Snapshot();
        // built-in starter decks (always present)
        YgoLibrary ygoStarter = YgoLibrary.parse(resource("/tablecards/ygo_cards.json"));
        PtcgLibrary ptcgStarter = PtcgLibrary.parse(resource("/tablecards/ptcg_cards.json"));
        s.ygo.cards.putAll(ygoStarter.cards);
        s.ptcg.cards.putAll(ptcgStarter.cards);

        Map<String, List<YgoCard>> ygoUser = new LinkedHashMap<>();
        Map<String, List<PtcgCard>> ptcgUser = new LinkedHashMap<>();
        Map<String, List<YgoCard>> ygoAuto = new LinkedHashMap<>();
        Map<String, List<PtcgCard>> ptcgAuto = new LinkedHashMap<>();
        if (configDir != null) {
            ensureFolders(configDir);
            Path imported = configDir.resolve("imported");
            try {
                Path y = imported.resolve("ygo.json");
                if (Files.exists(y)) {
                    int before = s.ygo.cards.size();
                    s.ygo.add(Files.readString(y, StandardCharsets.UTF_8), "");
                    s.realYgoCards = s.ygo.cards.size() - before;
                    ygoAuto.putAll(YgoAutoDecks.build(s.ygo));
                }
            } catch (IOException | RuntimeException e) {
                s.problems.add("Imported Yu-Gi-Oh! cards couldn't be loaded (" + e.getMessage() + "). Run /tablecards import yugioh again.");
            }
            try {
                Path pk = imported.resolve("ptcg.json");
                if (Files.exists(pk)) {
                    int before = s.ptcg.cards.size();
                    s.ptcg.add(Files.readString(pk, StandardCharsets.UTF_8), "");
                    s.realPtcgCards = s.ptcg.cards.size() - before;
                    ptcgAuto.putAll(s.ptcg.decks); // official theme decks that are fully playable
                    ptcgAuto.putAll(PtcgAutoDecks.build(s.ptcg));
                }
            } catch (IOException | RuntimeException e) {
                s.problems.add("Imported Pokemon cards couldn't be loaded (" + e.getMessage() + "). Run /tablecards import pokemon again.");
            }
            loadDeckLists(configDir.resolve("decks"), s, ygoUser, ptcgUser);
        }
        // order: your decks, then auto/theme decks, then the starter decks
        s.ygoDecks.putAll(ygoUser);
        s.ygoDecks.putAll(ygoAuto);
        ygoStarter.decks.forEach((k, v) -> s.ygoDecks.put("Starter: " + k, v));
        s.ptcgDecks.putAll(ptcgUser);
        s.ptcgDecks.putAll(ptcgAuto);
        ptcgStarter.decks.forEach((k, v) -> s.ptcgDecks.put("Starter: " + k, v));
        current = s;
        return s;
    }

    private static void loadDeckLists(Path decks, Snapshot s, Map<String, List<YgoCard>> ygoUser, Map<String, List<PtcgCard>> ptcgUser) {
        if (!Files.isDirectory(decks)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(decks)) {
            files = list.sorted().toList();
        } catch (IOException e) {
            s.problems.add("Couldn't read the decks folder: " + e.getMessage());
            return;
        }
        for (Path f : files) {
            String file = f.getFileName().toString();
            String lower = file.toLowerCase();
            if (!lower.endsWith(".ydk") && !(lower.endsWith(".txt") && !lower.equals("readme.txt"))) {
                continue;
            }
            String name = "Your deck: " + file.substring(0, file.lastIndexOf('.'));
            try {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                if (lower.endsWith(".ydk")) {
                    DeckLists.Parsed<YgoCard> p = DeckLists.ydk(text, s.ygo);
                    if (p.ok()) ygoUser.put(name, p.deck());
                    else s.problems.add(file + ": " + String.join("; ", limit(p.problems())));
                } else {
                    DeckLists.Parsed<PtcgCard> p = DeckLists.ptcg(text, s.ptcg);
                    if (p.ok()) ptcgUser.put(name, p.deck());
                    else s.problems.add(file + ": " + String.join("; ", limit(p.problems())));
                }
            } catch (IOException | RuntimeException e) {
                s.problems.add(file + ": couldn't be read (" + e.getMessage() + ")");
            }
        }
    }

    private static List<String> limit(List<String> problems) {
        if (problems.size() <= 5) {
            return problems;
        }
        List<String> out = new ArrayList<>(problems.subList(0, 5));
        out.add("and " + (problems.size() - 5) + " more");
        return out;
    }

    static void ensureFolders(Path configDir) {
        try {
            Files.createDirectories(configDir.resolve("imported"));
            Path decks = Files.createDirectories(configDir.resolve("decks"));
            Path readme = decks.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, README, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String resource(String path) {
        try (InputStream in = CardPools.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
