package com.tablecards.client;

import com.tablecards.net.ViewPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

public class TableCardsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ViewPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            ViewModel view = ViewModel.parse(payload.json());
            if (client.currentScreen instanceof TableGameScreen screen) {
                screen.update(view);
            } else if (payload.open() && client.currentScreen == null) {
                // only open over the game world, never on top of another screen (chat, inventory...)
                client.setScreen(new TableGameScreen(view));
            }
        });
    }
}
