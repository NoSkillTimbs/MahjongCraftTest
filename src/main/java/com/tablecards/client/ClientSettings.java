package com.tablecards.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** This player's own Table Cards settings (config/tablecards-client.properties). */
public final class ClientSettings {
    private static boolean cardArt = true;

    private ClientSettings() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("tablecards-client.properties");
    }

    static void load() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file())) {
            p.load(r);
        } catch (IOException ignored) {
            // first start: defaults
        }
        cardArt = !"false".equalsIgnoreCase(p.getProperty("cardArt", "true"));
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("cardArt", String.valueOf(cardArt));
        try (Writer w = Files.newBufferedWriter(file())) {
            p.store(w, "Table Cards: cardArt=true downloads real card pictures (once, into .minecraft/tablecards-cache)");
        } catch (IOException ignored) {
            // not worth bothering the player about
        }
    }

    /** Whether to download and show real card pictures. */
    public static boolean cardArt() {
        return cardArt;
    }

    public static void toggleCardArt() {
        cardArt = !cardArt;
        save();
    }
}
