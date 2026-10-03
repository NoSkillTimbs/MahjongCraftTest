package com.tablecards.engine;

import com.tablecards.engine.ptcg.PtcgImporter;
import com.tablecards.engine.ygo.YgoImporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Converts downloaded card databases into the library files the game loads, without Minecraft
 * (the mod does the same with /tablecards import). Used by the build's engine test.
 *
 *   java ... ImportFiles ygo  cardinfo.json            ygo.json    (YGOPRODeck cardinfo.php output)
 *   java ... ImportFiles ptcg pokemon-tcg-data-folder  ptcg.json   (a copy of github.com/PokemonTCG/pokemon-tcg-data)
 */
public final class ImportFiles {
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.out.println("usage: ImportFiles ygo|ptcg <input> <output.json>");
            System.exit(2);
        }
        String json;
        if (args[0].equals("ygo")) {
            YgoImporter.Result r = YgoImporter.convert(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
            System.out.println("Yu-Gi-Oh!: " + r.monstersOk + " Normal Monsters, " + r.spellsOk + " Spells, " + r.trapsOk
                    + " Traps imported of " + r.total);
            json = r.libraryJson;
        } else {
            Path base = Path.of(args[1]);
            String sets = Files.readString(base.resolve("sets/en.json"), StandardCharsets.UTF_8);
            Map<String, String> cards = new LinkedHashMap<>();
            try (Stream<Path> s = Files.list(base.resolve("cards/en"))) {
                for (Path p : s.sorted().toList()) {
                    cards.put(p.getFileName().toString().replace(".json", ""), Files.readString(p, StandardCharsets.UTF_8));
                }
            }
            List<String> decks = new ArrayList<>();
            Path deckDir = base.resolve("decks/en");
            if (Files.isDirectory(deckDir)) {
                try (Stream<Path> s = Files.list(deckDir)) {
                    for (Path p : s.sorted().toList()) {
                        decks.add(Files.readString(p, StandardCharsets.UTF_8));
                    }
                }
            }
            PtcgImporter.Result r = PtcgImporter.convert(sets, cards, decks);
            System.out.println("Pokemon TCG: " + r.pokemonOk + " of " + r.pokemonTotal + " Pokemon, " + r.trainerOk + " of "
                    + r.trainerTotal + " Trainers, " + r.energyOk + " basic Energy, " + r.themeDecks.size() + " theme decks");
            json = r.libraryJson;
        }
        Files.writeString(Path.of(args[2]), json, StandardCharsets.UTF_8);
    }
}
