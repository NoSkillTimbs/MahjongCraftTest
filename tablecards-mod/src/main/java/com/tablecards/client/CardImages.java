package com.tablecards.client;

import com.tablecards.TableCardsMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Card art for real (imported) cards. Each picture is downloaded once by this client from the
 * card database's image server, kept in .minecraft/tablecards-cache, and turned into a texture.
 * Only two hosts are ever contacted, and only for card ids that look like card ids, whatever a
 * server sends. Cards without art are drawn by the mod instead.
 */
public final class CardImages {
    private static final Pattern YGO = Pattern.compile("ygo:(\\d{1,10})");
    private static final Pattern PTCG = Pattern.compile("ptcg:([a-z0-9.]{1,24})/([A-Za-z0-9-]{1,12})");
    /** Field cards are drawn small; a reduced copy looks much smoother there than the full image. */
    private static final int SMALL_WIDTH = 72;

    enum State { LOADING, READY, FAILED }

    static final class Entry {
        volatile State state = State.LOADING;
        Identifier full;
        Identifier small;
        int fullW;
        int fullH;
        int smallW;
        int smallH;
    }

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "TableCards card art");
        t.setDaemon(true);
        return t;
    });
    private static HttpClient http;

    private CardImages() {
    }

    /** The art for {@code key}, starting its download the first time. Null for keys we don't fetch. */
    static Entry get(String key) {
        if (url(key) == null) {
            return null;
        }
        return ENTRIES.computeIfAbsent(key, k -> {
            Entry e = new Entry();
            POOL.submit(() -> load(k, e));
            return e;
        });
    }

    static String url(String key) {
        Matcher m = YGO.matcher(key);
        if (m.matches()) {
            return "https://images.ygoprodeck.com/images/cards_small/" + m.group(1) + ".jpg";
        }
        m = PTCG.matcher(key);
        if (m.matches()) {
            return "https://images.pokemontcg.io/" + m.group(1) + "/" + m.group(2) + ".png";
        }
        return null;
    }

    private static Path file(String key) {
        String name = key.replace(':', '/').replace('/', '_');
        String ext = key.startsWith("ygo:") ? ".jpg" : ".png";
        return FabricLoader.getInstance().getGameDir().resolve("tablecards-cache")
                .resolve(key.substring(0, key.indexOf(':'))).resolve(name + ext);
    }

    private static synchronized HttpClient http() {
        if (http == null) {
            http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return http;
    }

    private static void load(String key, Entry e) {
        try {
            Path f = file(key);
            byte[] bytes = null;
            if (Files.isRegularFile(f)) {
                bytes = Files.readAllBytes(f);
            }
            BufferedImage img = bytes == null ? null : ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {
                bytes = download(url(key));
                img = ImageIO.read(new ByteArrayInputStream(bytes));
                if (img == null) {
                    throw new IOException("not an image");
                }
                Files.createDirectories(f.getParent());
                Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            BufferedImage small = shrink(img, SMALL_WIDTH);
            NativeImage fullImage = toNative(img);
            NativeImage smallImage = toNative(small);
            int fw = img.getWidth();
            int fh = img.getHeight();
            int sw = small.getWidth();
            int sh = small.getHeight();
            MinecraftClient.getInstance().execute(() -> {
                String path = "card_art/" + key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
                Identifier full = Identifier.of(TableCardsMod.MOD_ID, path);
                Identifier smallId = Identifier.of(TableCardsMod.MOD_ID, path + "_small");
                NativeImageBackedTexture fullTex = new NativeImageBackedTexture(fullImage);
                NativeImageBackedTexture smallTex = new NativeImageBackedTexture(smallImage);
                fullTex.setFilter(true, false);
                smallTex.setFilter(true, false);
                MinecraftClient.getInstance().getTextureManager().registerTexture(full, fullTex);
                MinecraftClient.getInstance().getTextureManager().registerTexture(smallId, smallTex);
                e.full = full;
                e.small = smallId;
                e.fullW = fw;
                e.fullH = fh;
                e.smallW = sw;
                e.smallH = sh;
                e.state = State.READY;
            });
        } catch (Exception | LinkageError ex) {
            e.state = State.FAILED;
            TableCardsMod.LOGGER.debug("No card art for {}: {}", key, ex.toString());
        }
    }

    private static byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "TableCards-Minecraft-mod (card art cache)")
                .GET().build();
        HttpResponse<byte[]> r = http().send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode());
        }
        if (r.body().length > 4 * 1024 * 1024) {
            throw new IOException("image too large");
        }
        return r.body();
    }

    /** Box-filter downscale (pure pixel maths, no AWT drawing). */
    static BufferedImage shrink(BufferedImage src, int width) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw <= width) {
            return src;
        }
        int w = width;
        int h = Math.max(1, Math.round(sh * (width / (float) sw)));
        int[] in = src.getRGB(0, 0, sw, sh, null, 0, sw);
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = y * sh / h;
            int y1 = Math.max(y0 + 1, (y + 1) * sh / h);
            for (int x = 0; x < w; x++) {
                int x0 = x * sw / w;
                int x1 = Math.max(x0 + 1, (x + 1) * sw / w);
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int p = in[yy * sw + xx];
                        a += (p >>> 24) & 0xFF;
                        r += (p >>> 16) & 0xFF;
                        g += (p >>> 8) & 0xFF;
                        b += p & 0xFF;
                        n++;
                    }
                }
                out[y * w + x] = (int) (a / n) << 24 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        }
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        dst.setRGB(0, 0, w, h, out, 0, w);
        return dst;
    }

    /** Re-encodes as PNG, which Minecraft's own image loader reads. */
    private static NativeImage toNative(BufferedImage img) throws IOException {
        BufferedImage argb = img;
        if (img.getType() != BufferedImage.TYPE_INT_ARGB) {
            argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            argb.setRGB(0, 0, img.getWidth(), img.getHeight(), img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth()), 0, img.getWidth());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(argb, "png", out);
        return NativeImage.read(new ByteArrayInputStream(out.toByteArray()));
    }
}
