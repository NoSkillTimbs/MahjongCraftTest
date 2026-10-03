package com.tablecards.client;

import com.tablecards.net.ChoosePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * The card game screen. All drawing and click handling is in {@link GameView}; this class connects
 * it to Minecraft (mouse, keys, sounds, sending choices to the server). Everything is decided on
 * the server.
 */
public class TableGameScreen extends Screen {
    private final GameView view;

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
        });
    }

    private ViewModel view() {
        return view.view();
    }

    /** A new view arrived from the server. */
    public void update(ViewModel next) {
        view.update(next);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        view.render(new McCanvas(ctx, textRenderer), width, height, mouseX, mouseY, Util.getMeasuringTimeMs());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // the game view paints the whole screen
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
            view.back();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        view.scroll(mouseX, mouseY, verticalAmount);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && view.back()) { // Escape closes the open pile or menu first
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
