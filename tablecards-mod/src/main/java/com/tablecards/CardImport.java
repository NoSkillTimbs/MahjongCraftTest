package com.tablecards;

import com.tablecards.engine.Json;
import com.tablecards.engine.ptcg.PtcgImporter;
import com.tablecards.engine.ygo.YgoImporter;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Downloads real card data and converts it with the importers. The data is saved in the server's
 * config folder and only used there; nothing is redistributed, and no card images are downloaded.
 *
 * Yu-Gi-Oh!: one request to the YGOPRODeck API (its terms ask apps to download once and keep a
 * local copy). Pokemon: the open data set at github.com/PokemonTCG/pokemon-tcg-data (one file per
 * set), plus its preconstructed theme decks.
 */
public final class CardImport {
    static final String YGO_URL = "https://db.ygoprodeck.com/api/v7/cardinfo.php";
    static final String PTCG_RAW = "https://raw.githubusercontent.com/PokemonTCG/pokemon-tcg-data/master/";
    static final String PTCG_DECKS_API = "https://api.github.com/repos/PokemonTCG/pokemon-tcg-data/contents/decks/en";
    private static final String AGENT = "TableCards-Minecraft-mod (private play)";

    private CardImport() {
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).header("User-Agent", AGENT).GET().build();
    }

    private static String fetch(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(get(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode() + " from " + url);
        }
        return r.body();
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Downloads and converts Yu-Gi-Oh! cards. Returns a one-line summary. */
    public static String importYugioh(Path configDir, Consumer<String> progress) throws IOException, InterruptedException {
        progress.accept("Downloading the Yu-Gi-Oh! card database from YGOPRODeck (one request, this can take a minute)...");
        String json = fetch(client(), YGO_URL);
        progress.accept("Converting " + (json.length() / 1024 / 1024) + " MB of Yu-Gi-Oh! card data...");
        YgoImporter.Result r = YgoImporter.convert(json);
        writeAtomically(configDir.resolve("imported/ygo.json"), r.libraryJson);
        writeAtomically(configDir.resolve("imported/ygo-report.txt"), report("Yu-Gi-Oh!", r.total,
                r.monstersOk + " Normal Monsters, " + r.spellsOk + " Spells, " + r.trapsOk + " Traps", r.rejected));
        return "Yu-Gi-Oh!: imported " + (r.monstersOk + r.spellsOk + r.trapsOk) + " of " + r.total + " real cards ("
                + r.monstersOk + " Normal Monsters, " + r.spellsOk + " Spells, " + r.trapsOk + " Traps).";
    }

    /** Downloads and converts Pokemon cards. Returns a one-line summary. */
    public static String importPokemon(Path configDir, Consumer<String> progress) throws IOException, InterruptedException {
        HttpClient http = client();
        progress.accept("Downloading the Pokemon TCG set list...");
        String setsJson = fetch(http, PTCG_RAW + "sets/en.json");
        List<String> ids = new ArrayList<>();
        for (Object o : Json.arr(Json.parse(setsJson))) {
            ids.add(Json.str(Json.obj(o), "id", ""));
        }
        progress.accept("Downloading " + ids.size() + " Pokemon TCG sets...");
        Map<String, String> setCards = new LinkedHashMap<>();
        Semaphore limit = new Semaphore(6);
        Map<String, CompletableFuture<HttpResponse<String>>> futures = new LinkedHashMap<>();
        for (String id : ids) {
            limit.acquire();
            CompletableFuture<HttpResponse<String>> f = http.sendAsync(get(PTCG_RAW + "cards/en/" + id + ".json"),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            f.whenComplete((r, e) -> limit.release());
            futures.put(id, f);
        }
        int missing = 0;
        for (Map.Entry<String, CompletableFuture<HttpResponse<String>>> e : futures.entrySet()) {
            try {
                HttpResponse<String> r = e.getValue().join();
                if (r.statusCode() == 200) {
                    setCards.put(e.getKey(), r.body());
                } else {
                    missing++;
                }
            } catch (RuntimeException ex) {
                missing++;
            }
        }
        if (setCards.isEmpty()) {
            throw new IOException("none of the Pokemon card files could be downloaded");
        }
        List<String> themeDecks = new ArrayList<>();
        try {
            progress.accept("Downloading the official theme decks...");
            for (Object o : Json.arr(Json.parse(fetch(http, PTCG_DECKS_API)))) {
                String name = Json.str(Json.obj(o), "name", "");
                if (name.endsWith(".json")) {
                    themeDecks.add(fetch(http, PTCG_RAW + "decks/en/" + name));
                }
            }
        } catch (IOException | RuntimeException e) {
            progress.accept("(Theme decks skipped: " + e.getMessage() + ")");
        }
        progress.accept("Converting " + setCards.size() + " sets of Pokemon cards...");
        PtcgImporter.Result r = PtcgImporter.convert(setsJson, setCards, themeDecks);
        writeAtomically(configDir.resolve("imported/ptcg.json"), r.libraryJson);
        writeAtomically(configDir.resolve("imported/ptcg-report.txt"), report("Pokemon TCG", r.pokemonTotal + r.trainerTotal,
                r.pokemonOk + " Pokemon, " + r.trainerOk + " Trainers, " + r.energyOk + " basic Energy, " + r.themeDecks.size() + " theme decks"
                        + (missing > 0 ? " (" + missing + " set files couldn't be downloaded)" : ""), r.rejected));
        return "Pokemon TCG: imported " + r.pokemonOk + " of " + r.pokemonTotal + " real Pokemon, " + r.trainerOk + " of " + r.trainerTotal
                + " Trainers and " + r.energyOk + " basic Energy printings" + (r.themeDecks.isEmpty() ? "" : ", plus " + r.themeDecks.size() + " theme deck(s)")
                + (missing > 0 ? " (" + missing + " sets couldn't be downloaded)" : "") + ".";
    }

    private static String report(String game, int total, String imported, Map<String, Integer> rejected) {
        StringBuilder b = new StringBuilder(game).append(" import\n\nImported: ").append(imported).append(" (of ").append(total).append(" cards)\n");
        b.append("Only cards the game can play exactly as printed are imported.\n\nNot imported:\n");
        rejected.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue())
                .forEach(e -> b.append("  ").append(e.getValue()).append("  ").append(e.getKey()).append('\n'));
        return b.toString();
    }
}
