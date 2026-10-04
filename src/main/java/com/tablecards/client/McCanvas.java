package com.tablecards.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.tablecards.TableCardsMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

import java.util.HashMap;
import java.util.Map;

/** {@link Canvas} on top of Minecraft's DrawContext. */
final class McCanvas implements Canvas {
    private static final Map<String, Identifier> TEXTURES = new HashMap<>();

    private final DrawContext ctx;
    private final TextRenderer font;

    McCanvas(DrawContext ctx, TextRenderer font) {
        this.ctx = ctx;
        this.font = font;
    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        ctx.fill(x1, y1, x2, y2, argb);
    }

    @Override
    public void gradient(int x1, int y1, int x2, int y2, int topArgb, int bottomArgb) {
        ctx.fillGradient(x1, y1, x2, y2, topArgb, bottomArgb);
    }

    @Override
    public void text(String s, float x, float y, int argb, float scale, boolean shadow) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x, y, 0);
        ctx.getMatrices().scale(scale, scale, 1);
        ctx.drawText(font, s, 0, 0, argb, shadow);
        ctx.getMatrices().pop();
    }

    @Override
    public int textWidth(String s) {
        return font.getWidth(s);
    }

    /** Our GUI textures, or the plain frame for an unknown name (say, a new Pokemon type). */
    static Identifier texture(String name) {
        return TEXTURES.computeIfAbsent(name, n -> {
            Identifier id = Identifier.of(TableCardsMod.MOD_ID, "textures/gui/" + n + ".png");
            if (MinecraftClient.getInstance().getResourceManager().getResource(id).isPresent()) {
                return id;
            }
            String fallback = n.startsWith("energy_") ? "energy_colorless" : n.startsWith("back_") ? "back_ygo" : "frame_default";
            return Identifier.of(TableCardsMod.MOD_ID, "textures/gui/" + fallback + ".png");
        });
    }

    private static int[] size(Identifier id) {
        String p = id.getPath();
        if (p.contains("playmat_")) return new int[]{256, 256};
        if (p.contains("energy_")) return new int[]{32, 32};
        if (p.endsWith("back_ptcg.png")) return new int[]{250, 379};
        if (p.endsWith("back_ygo.png")) return new int[]{196, 292};
        return new int[]{128, 186};
    }

    @Override
    public void texture(String name, int x, int y, int w, int h) {
        Identifier id = texture(name);
        int[] s = size(id);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        ctx.drawTexture(id, x, y, w, h, 0, 0, s[0], s[1], s[0], s[1]);
        RenderSystem.disableBlend();
    }

    @Override
    public boolean art(String key, int x, int y, int w, int h, boolean large) {
        CardImages.Entry e = CardImages.get(key);
        if (e == null || e.state != CardImages.State.READY) {
            return false;
        }
        boolean full = large || w > 60;
        Identifier id = full ? e.full : e.small;
        int tw = full ? e.fullW : e.smallW;
        int th = full ? e.fullH : e.smallH;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        ctx.drawTexture(id, x, y, w, h, 0, 0, tw, th, tw, th);
        RenderSystem.disableBlend();
        return true;
    }

    @Override
    public void push() {
        ctx.getMatrices().push();
    }

    @Override
    public void pop() {
        ctx.getMatrices().pop();
    }

    @Override
    public void translate(float x, float y) {
        ctx.getMatrices().translate(x, y, 0);
    }

    @Override
    public void rotate90() {
        ctx.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90f));
    }

    @Override
    public void scissor(int x1, int y1, int x2, int y2) {
        ctx.enableScissor(x1, y1, x2, y2);
    }

    @Override
    public void noScissor() {
        ctx.disableScissor();
    }
}
