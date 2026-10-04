package com.tablecards.client;

import com.tablecards.net.ChoosePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;

/**
 * The card game screen. Once the game is on, the cards are on the Game Table in the world and
 * this screen is a see-through layer over it (hold the right mouse button and drag to look
 * around). All drawing and click handling is in {@link GameView}; this class connects it to
 * Minecraft (mouse, keys, sounds, sending choices to the server). Everything is decided on the
 * server.
 */
public class TableGameScreen extends Screen {
    private final GameView view;
    private boolean turned;
    private boolean rightDown;
    private boolean dragged;
    private boolean hudWasHidden;
    private boolean hudChanged;

    public TableGameScreen(ViewModel model) {
        super(Text.literal(model.title));
        view = new GameView(model, new GameView.Actions() {
            @Override
            public void choose(int option) {
                ClientPlayNetworking.send(new ChoosePayload(view().seq, option));
            }

            @Override
            public void concede() {
                ClientPlayNetworking.send(new ChoosePayload(view().seq, ChoosePayload.CONCEDE));
            }

            @Override
            public void close() {
                TableGameScreen.this.close();
            }

            @Override
            public void toggleArt() {
                ClientSettings.toggleCardArt();
            }

            @Override
            public boolean artEnabled() {
                return ClientSettings.cardArt();
            }
        }, TableRenderer.WORLD);
    }

    private ViewModel view() {
        return view.view();
    }

    /** A new view arrived from the server. */
    public void update(ViewModel next) {
        view.update(next);
        worldSetup();
    }

    @Override
    protected void init() {
        super.init();
        worldSetup();
    }

    /** When the cards are on the table: hide the hotbar and turn to face the table. */
    private void worldSetup() {
        if (client == null || !view.inWorld()) {
            return;
        }
        if (!hudChanged) {
            hudChanged = true;
            hudWasHidden = client.options.hudHidden;
            client.options.hudHidden = true;
        }
        if (!turned) {
            turned = true;
            lookAtTable();
        }
    }

    private void lookAtTable() {
        ClientPlayerEntity player = client == null ? null : client.player;
        int[] t = view().table;
        if (player == null || t == null) {
            return;
        }
        Vec3d eye = player.getEyePos();
        double cx = t[0] + 0.5;
        double cz = t[2] + 0.5;
        double ox = eye.x - cx;
        double oz = eye.z - cz;
        double dist = Math.sqrt(ox * ox + oz * oz);
        if (dist > 4.5 || dist < 0.3) {
            return; // not sitting at the table
        }
        // a point a little on your side of the middle, so your own cards and hand are in view
        double tx = cx + ox / dist * 0.15;
        double tz = cz + oz / dist * 0.15;
        double ty = t[1] + TableLayout.TOP;
        double dx = tx - eye.x;
        double dy = ty - eye.y;
        double dz = tz - eye.z;
        // turned a little to the right, so the table sits in the middle of the part of the
        // screen the question panel (on the right) doesn't cover
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)) + 9f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        player.setYaw(yaw);
        player.setPitch(pitch);
        player.setHeadYaw(yaw);
        player.prevYaw = yaw;
        player.prevPitch = pitch;
    }

    @Override
    public void removed() {
        if (hudChanged && client != null) {
            client.options.hudHidden = hudWasHidden;
        }
        TableRenderer.WORLD.highlight(java.util.Set.of(), "", -1);
        super.removed();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        view.render(new McCanvas(ctx, textRenderer), width, height, mouseX, mouseY, Util.getMeasuringTimeMs());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // the game view paints what it needs; over the world nothing at all
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && view.click(mouseX, mouseY)) {
            if (client != null) {
                client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            }
            return true;
        }
        if (button == 1) {
            rightDown = true;
            dragged = false;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 1 && rightDown && view.inWorld() && client != null && client.player != null) {
            // hold the right button and drag to look around the table
            dragged = true;
            double scale = client.getWindow().getScaleFactor();
            double sens = client.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
            double k = sens * sens * sens * 8.0;
            client.player.changeLookDirection(deltaX * scale * k, deltaY * scale * k);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 1 && rightDown) {
            rightDown = false;
            if (!dragged) {
                view.back(); // a right click closes what's open on top
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        view.scroll(mouseX, mouseY, verticalAmount);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && view.back()) { // Escape closes the open pile, close-up or question first
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
