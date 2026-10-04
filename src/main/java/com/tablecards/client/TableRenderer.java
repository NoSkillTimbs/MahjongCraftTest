package com.tablecards.client;

import com.tablecards.TableCardsMod;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Draws card games on the Game Table in the world, for the players and for everyone watching,
 * and works out where your own cards are on the screen so the game screen can tell what you
 * click. Called from MahjongCraft's table renderer.
 */
public final class TableRenderer {
    private static final Identifier WHITE = Identifier.of(TableCardsMod.MOD_ID, "textures/gui/white.png");
    private static final TableLayout.Highlight NONE = new TableLayout.Highlight();
    private static final double[] UP = {0, 1, 0};
    private static final int PAPER = 0xFFE4DDCC;
    private static final int GOLD = 0xFFD34D;

    private static List<TableLayout.Item> ownItems = List.of();
    private static int[] ownTable;
    private static long ownItemsAt;
    private static volatile List<GameView.Hit> hits = List.of();
    private static boolean failed;

    /** Your own table, for the game screen. */
    public static final GameView.World WORLD = new GameView.World() {
        @Override
        public List<GameView.Hit> hits() {
            return hits;
        }

        @Override
        public void highlight(Set<Integer> usable, String hover, int selected) {
            TableViews.HIGHLIGHT.usable = usable;
            TableViews.HIGHLIGHT.hover = hover;
            TableViews.HIGHLIGHT.selected = selected;
        }
    };

    private TableRenderer() {
    }

    /** True while a card game is laid out on this table (MahjongCraft then hides its own labels). */
    public static boolean hasGame(BlockEntity be) {
        BlockPos pos = be.getPos();
        TableViews.Entry e = TableViews.get(pos.getX(), pos.getY(), pos.getZ());
        return e != null && e.view.hasBoard();
    }

    /** Draws the card game on this table, if there is one. Returns true if it drew something. */
    public static boolean render(BlockEntity be, float tickDelta, MatrixStack matrices, VertexConsumerProvider vcp, int light) {
        if (failed) {
            return false;
        }
        try {
            return draw(be, matrices, vcp);
        } catch (RuntimeException | LinkageError e) {
            failed = true; // never take the whole game down over the card table
            TableCardsMod.LOGGER.error("Table Cards: drawing the card table failed; cards won't be shown on tables", e);
            return false;
        }
    }

    private static boolean draw(BlockEntity be, MatrixStack matrices, VertexConsumerProvider vcp) {
        World world = be.getWorld();
        if (world == null) {
            return false;
        }
        BlockPos pos = be.getPos();
        TableViews.Entry e = TableViews.get(pos.getX(), pos.getY(), pos.getZ());
        if (e == null || !e.view.hasBoard() || !e.dimension.equals(world.getRegistryKey().getValue().toString())) {
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = Util.getMeasuringTimeMs();
        List<TableLayout.Item> items = TableLayout.build(e.view, e.anims, now, e.own ? TableViews.HIGHLIGHT : NONE);
        if (e.own) {
            ownItems = items;
            ownTable = new int[]{pos.getX(), pos.getY(), pos.getZ()};
            ownItemsAt = now;
        }
        Vec3d cam = mc.gameRenderer.getCamera().getPos();
        double[] vOut = TableLayout.norm(new double[]{cam.x - (pos.getX() + 0.5), 0, cam.z - (pos.getZ() + 0.5)});
        if (Math.abs(vOut[1]) > 0.5) {
            vOut = new double[]{0, 0, 1};
        }
        double[] vRight = {vOut[2], 0, -vOut[0]};
        int lt = WorldRenderer.getLightmapCoordinates(world, pos.up());
        Ctx x = new Ctx(matrices.peek(), vcp, lt, mc.textRenderer, vOut, vRight,
                McCanvas.texture("back_" + ("ptcg".equals(e.view.game) ? "ptcg" : "ygo")), now);

        for (TableLayout.Item it : items) {
            if (it.kind == TableLayout.Kind.SLOT) {
                x.quad(RenderLayer.getEntityTranslucent(WHITE), it.c, it.u, it.v, it.n, 0x24FFFFFF);
                x.border(it, it.c, 0.006, 0x50FFFFFF);
            }
        }
        for (TableLayout.Item it : items) {
            if (it.kind == TableLayout.Kind.PILE) {
                x.pile(it);
            } else if (it.kind == TableLayout.Kind.CARD) {
                x.card(it);
            }
        }
        for (TableLayout.Item it : items) {
            x.labels(it);
        }
        x.seatLabels(e, matrices, mc);
        return true;
    }

    /** Works out where your own table's cards are on the screen (after the world is drawn). */
    public static void computeHits(Matrix4f projection, Matrix4f view, Vec3d cam) {
        TableViews.Entry own = TableViews.own();
        int[] t = ownTable;
        if (own == null || t == null || own.view.table == null || Util.getMeasuringTimeMs() - ownItemsAt > 250
                || t[0] != own.view.table[0] || t[1] != own.view.table[1] || t[2] != own.view.table[2]) {
            hits = List.of();
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        float sw = mc.getWindow().getScaledWidth();
        float sh = mc.getWindow().getScaledHeight();
        List<GameView.Hit> out = new ArrayList<>();
        Vector4f p = new Vector4f();
        for (TableLayout.Item it : ownItems) {
            if (it.key == null) {
                continue;
            }
            double[][] corners = it.corners();
            float[] xy = new float[8];
            float depth = 0;
            boolean ok = true;
            for (int k = 0; k < 4 && ok; k++) {
                double[] c = corners[k];
                p.set((float) (t[0] + c[0] - cam.x), (float) (t[1] + c[1] - cam.y), (float) (t[2] + c[2] - cam.z), 1f);
                view.transform(p);
                depth += -p.z;
                projection.transform(p);
                if (p.w <= 0.01f) {
                    ok = false;
                    break;
                }
                xy[k * 2] = (p.x / p.w * 0.5f + 0.5f) * sw;
                xy[k * 2 + 1] = (0.5f - p.y / p.w * 0.5f) * sh;
            }
            if (ok) {
                out.add(new GameView.Hit(it.key, xy, depth / 4));
            }
        }
        out.sort(Comparator.comparingDouble((GameView.Hit h) -> h.depth()).reversed()); // nearest last (on top)
        hits = out;
    }

    public static void forget() {
        hits = List.of();
        ownItems = List.of();
        ownTable = null;
    }

    /** One frame's drawing state. */
    private static final class Ctx {
        final MatrixStack.Entry en;
        final VertexConsumerProvider vcp;
        final int light;
        final TextRenderer font;
        final double[] vOut;
        final double[] vRight;
        final Identifier back;
        final long now;

        Ctx(MatrixStack.Entry en, VertexConsumerProvider vcp, int light, TextRenderer font, double[] vOut, double[] vRight, Identifier back, long now) {
            this.en = en;
            this.vcp = vcp;
            this.light = light;
            this.font = font;
            this.vOut = vOut;
            this.vRight = vRight;
            this.back = back;
            this.now = now;
        }

        void quad(RenderLayer layer, double[] c, double[] u, double[] v, double[] n, int argb) {
            VertexConsumer vc = vcp.getBuffer(layer);
            Matrix4f m = en.getPositionMatrix();
            int a = argb >>> 24;
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            float nx = (float) n[0];
            float ny = (float) n[1];
            float nz = (float) n[2];
            vert(vc, m, c[0] - u[0] + v[0], c[1] - u[1] + v[1], c[2] - u[2] + v[2], 0, 0, r, g, b, a, nx, ny, nz);
            vert(vc, m, c[0] - u[0] - v[0], c[1] - u[1] - v[1], c[2] - u[2] - v[2], 0, 1, r, g, b, a, nx, ny, nz);
            vert(vc, m, c[0] + u[0] - v[0], c[1] + u[1] - v[1], c[2] + u[2] - v[2], 1, 1, r, g, b, a, nx, ny, nz);
            vert(vc, m, c[0] + u[0] + v[0], c[1] + u[1] + v[1], c[2] + u[2] + v[2], 1, 0, r, g, b, a, nx, ny, nz);
        }

        private void vert(VertexConsumer vc, Matrix4f m, double x, double y, double z, float tu, float tv,
                          int r, int g, int b, int a, float nx, float ny, float nz) {
            vc.vertex(m, (float) x, (float) y, (float) z).color(r, g, b, a).texture(tu, tv)
                    .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(en, nx, ny, nz);
        }

        Identifier face(ViewModel.Card card) {
            if (!card.image.isEmpty() && ClientSettings.cardArt()) {
                CardImages.Entry a = CardImages.get(card.image);
                if (a != null && a.state == CardImages.State.READY) {
                    return a.small;
                }
            }
            return McCanvas.texture("frame_" + (card.frame.isEmpty() ? "default" : card.frame));
        }

        boolean hasArt(ViewModel.Card card) {
            if (card.image.isEmpty() || !ClientSettings.cardArt()) {
                return false;
            }
            CardImages.Entry a = CardImages.get(card.image);
            return a != null && a.state == CardImages.State.READY;
        }

        void card(TableLayout.Item it) {
            double[] n = it.n;
            double[] c = it.c;
            boolean showFace = !it.back && it.card != null;
            if (it.twoSided) {
                double[] front = TableLayout.add(c, TableLayout.mul(n, 0.0015));
                double[] rear = TableLayout.add(c, TableLayout.mul(n, -0.0015));
                face(it, front, showFace);
                quad(RenderLayer.getEntityCutoutNoCull(back), rear, TableLayout.mul(it.u, -1), it.v, TableLayout.mul(n, -1), 0xFFFFFFFF);
                if (it.glow > 0) {
                    border(it, TableLayout.add(c, TableLayout.mul(n, 0.003)), 0.008, glowColor(it.glow));
                }
            } else {
                face(it, c, showFace);
                if (it.glow > 0) {
                    border(it, TableLayout.add(c, TableLayout.mul(UP, 0.001)), it.glow == 3 ? 0.014 : 0.01, glowColor(it.glow));
                }
            }
        }

        private void face(TableLayout.Item it, double[] c, boolean showFace) {
            if (!showFace) {
                quad(RenderLayer.getEntityCutoutNoCull(back), c, it.u, it.v, it.n, 0xFFFFFFFF);
                return;
            }
            quad(RenderLayer.getEntityCutoutNoCull(face(it.card)), c, it.u, it.v, it.n, 0xFFFFFFFF);
            if (it.card.down) {
                // your own face-down card: you see it, darkened, marked SET
                quad(RenderLayer.getEntityTranslucent(WHITE), TableLayout.add(c, TableLayout.mul(it.n, 0.0008)), it.u, it.v, it.n, 0x8C000000);
            }
        }

        int glowColor(int glow) {
            if (glow == 1) {
                int a = (int) (170 + 70 * Math.sin(now / 220.0));
                return a << 24 | GOLD;
            }
            return glow == 3 ? 0xFFFFFFFF : 0xD8FFFFFF;
        }

        void pile(TableLayout.Item it) {
            double[] top = TableLayout.add(it.c, TableLayout.mul(UP, it.thick));
            double[] h = TableLayout.mul(UP, it.thick / 2);
            double[] mid = TableLayout.add(it.c, h);
            RenderLayer paper = RenderLayer.getEntityCutoutNoCull(WHITE);
            double[] un = TableLayout.norm(it.u);
            double[] vn = TableLayout.norm(it.v);
            quad(paper, TableLayout.add(mid, it.v), it.u, h, vn, PAPER);
            quad(paper, TableLayout.sub(mid, it.v), it.u, h, TableLayout.mul(vn, -1), PAPER);
            quad(paper, TableLayout.add(mid, it.u), it.v, h, un, 0xFFD2CAB6);
            quad(paper, TableLayout.sub(mid, it.u), it.v, h, TableLayout.mul(un, -1), 0xFFD2CAB6);
            boolean showFace = !it.back && it.card != null;
            if (showFace) {
                quad(RenderLayer.getEntityCutoutNoCull(face(it.card)), top, it.u, it.v, UP, 0xFFFFFFFF);
            } else {
                quad(RenderLayer.getEntityCutoutNoCull(back), top, it.u, it.v, UP, 0xFFFFFFFF);
            }
            if (it.glow > 0) {
                border(it, TableLayout.add(top, TableLayout.mul(UP, 0.001)), 0.01, glowColor(it.glow));
            }
        }

        /** A frame just outside the edges of the item, lying in its plane. */
        void border(TableLayout.Item it, double[] c, double w, int argb) {
            RenderLayer layer = RenderLayer.getEntityTranslucent(WHITE);
            double[] un = TableLayout.norm(it.u);
            double[] vn = TableLayout.norm(it.v);
            double ul = len(it.u);
            double vl = len(it.v);
            double[] alongU = TableLayout.mul(un, ul + w);
            double[] thinV = TableLayout.mul(vn, w / 2);
            double[] alongV = TableLayout.mul(vn, vl);
            double[] thinU = TableLayout.mul(un, w / 2);
            quad(layer, TableLayout.add(c, TableLayout.mul(vn, vl + w / 2)), alongU, thinV, it.n, argb);
            quad(layer, TableLayout.add(c, TableLayout.mul(vn, -(vl + w / 2))), alongU, thinV, it.n, argb);
            quad(layer, TableLayout.add(c, TableLayout.mul(un, ul + w / 2)), thinU, alongV, it.n, argb);
            quad(layer, TableLayout.add(c, TableLayout.mul(un, -(ul + w / 2))), thinU, alongV, it.n, argb);
        }

        // ------------------------------------------------------------------ text and small marks

        void labels(TableLayout.Item it) {
            switch (it.kind) {
                case SLOT -> {
                    if (!it.label.isEmpty()) {
                        double[] xs = TableLayout.norm(it.u);
                        double[] ys = TableLayout.mul(TableLayout.norm(it.v), -1);
                        text(it.label, TableLayout.add(it.c, TableLayout.mul(UP, 0.002)), xs, ys, 0.026, 0x99FFFFFF, 0, 0.6);
                    }
                }
                case PILE -> {
                    double[] top = TableLayout.add(it.c, TableLayout.mul(UP, it.thick + 0.003));
                    double[] edge = nearestEdge(it);
                    double[] at = TableLayout.add(top, TableLayout.mul(edge, 0.62));
                    text(it.label, at, vRight, vOut, 0.045, 0xFFFFFFFF, 0xB0000000, 1);
                }
                case CARD -> cardLabels(it);
            }
        }

        private void cardLabels(TableLayout.Item it) {
            if (it.back || it.card == null) {
                return;
            }
            ViewModel.Card card = it.card;
            double[] lift = TableLayout.mul(it.n, it.twoSided ? 0.004 : 0.003);
            double[] c = TableLayout.add(it.c, lift);
            double[] xs = TableLayout.norm(it.u);
            double[] ys = TableLayout.mul(TableLayout.norm(it.v), -1);
            double cw = len(it.u) * 2;
            double ch = len(it.v) * 2;
            if (!hasArt(card)) {
                // the plain frame: write the name in it
                text(card.name, TableLayout.add(c, TableLayout.mul(it.v, 0.86)), xs, ys, ch * 0.055, 0xFF1A1A1A, 0, cw * 0.86);
            }
            if (card.down) {
                text("SET", c, xs, ys, ch * 0.16, 0xFFFFE9A0, 0, cw * 0.8);
            }
            if (!it.stats) {
                return;
            }
            double[] edge = nearestEdge(it);
            double up = 0.004;
            if (!card.stat.isEmpty()) {
                double[] at = TableLayout.add(TableLayout.add(c, TableLayout.mul(edge, 0.84)), TableLayout.mul(UP, up));
                text(card.stat, at, vRight, vOut, 0.034, 0xFFFFFFFF, 0xC8000000, Math.max(cw, ch) * 0.98);
            }
            if (!card.badge.isEmpty()) {
                double[] at = TableLayout.add(TableLayout.add(c, TableLayout.mul(edge, -0.25)), TableLayout.mul(UP, up));
                text(card.badge, at, vRight, vOut, 0.04, 0xFFFFFFFF, 0xE8C62828, cw);
            }
            if (!card.att.isEmpty()) {
                double size = Math.min(0.05, cw / 4.2);
                int k = card.att.size();
                double step = Math.min(size * 1.05, (cw - size) / Math.max(1, k - 1));
                double[] base = TableLayout.add(TableLayout.add(c, TableLayout.mul(edge, 0.55)), TableLayout.mul(UP, 0.002));
                for (int i = 0; i < k; i++) {
                    ViewModel.Card en = card.att.get(i);
                    String type = en.frame.startsWith("ptcg_energy_") ? en.frame.substring("ptcg_energy_".length()) : "colorless";
                    double off = (i - (k - 1) / 2.0) * step;
                    double[] at = TableLayout.add(base, TableLayout.mul(vRight, off));
                    quad(RenderLayer.getEntityCutoutNoCull(McCanvas.texture("energy_" + type)), at,
                            TableLayout.mul(vRight, size / 2), TableLayout.mul(vOut, -size / 2), UP, 0xFFFFFFFF);
                }
            }
        }

        /** The half-size vector of the card's edge that's nearest to whoever is looking. */
        private double[] nearestEdge(TableLayout.Item it) {
            double[][] dirs = {it.u, TableLayout.mul(it.u, -1), it.v, TableLayout.mul(it.v, -1)};
            double[] best = it.v;
            double bestDot = -2;
            for (double[] d : dirs) {
                double dot = dot(TableLayout.norm(d), vOut);
                if (dot > bestDot) {
                    bestDot = dot;
                    best = d;
                }
            }
            return best;
        }

        /** Text centred at {@code at}, lying in the plane of {@code xs} (reading direction) and {@code ys} (down the lines). */
        void text(String s, double[] at, double[] xs, double[] ys, double height, int color, int bg, double maxWidth) {
            if (s.isEmpty()) {
                return;
            }
            int w = font.getWidth(s);
            float scale = (float) (height / 9.0);
            if (w * scale > maxWidth) {
                scale = (float) (maxWidth / w);
            }
            double[] zs = TableLayout.cross(xs, ys);
            Matrix4f basis = new Matrix4f(
                    (float) xs[0] * scale, (float) xs[1] * scale, (float) xs[2] * scale, 0,
                    (float) ys[0] * scale, (float) ys[1] * scale, (float) ys[2] * scale, 0,
                    (float) zs[0] * scale, (float) zs[1] * scale, (float) zs[2] * scale, 0,
                    (float) at[0], (float) at[1], (float) at[2], 1);
            Matrix4f m = new Matrix4f(en.getPositionMatrix()).mul(basis);
            font.draw(s, -w / 2f, -4f, color, false, m, vcp, TextRenderer.TextLayerType.POLYGON_OFFSET, bg, light);
        }

        /** Name and score floating above the other player (or both players, for people watching). */
        void seatLabels(TableViews.Entry e, MatrixStack matrices, MinecraftClient mc) {
            ViewModel v = e.view;
            for (int i = 0; i < 2; i++) {
                if (i == 0 && e.own) {
                    continue;
                }
                ViewModel.Side side = v.sides.get(i);
                TableLayout.Frame f = TableLayout.frame(v, i);
                double[] at = f.at(0, 2.05, 1.5);
                String s = side.name + "  " + side.score + " " + side.scoreLabel;
                matrices.push();
                matrices.translate(at[0], at[1], at[2]);
                matrices.multiply(mc.getEntityRenderDispatcher().getRotation());
                matrices.scale(0.025f, -0.025f, 0.025f);
                int w = font.getWidth(s);
                font.draw(s, -w / 2f, 0, side.active ? 0xFFFFD34D : 0xFFFFFFFF, false, matrices.peek().getPositionMatrix(), vcp,
                        TextRenderer.TextLayerType.NORMAL, 0x60000000, light);
                matrices.pop();
            }
        }
    }

    private static double len(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
