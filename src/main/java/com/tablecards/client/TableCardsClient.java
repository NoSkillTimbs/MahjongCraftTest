package com.tablecards.client;

import com.tablecards.MahjongTables;
import com.tablecards.Sessions;
import com.tablecards.net.ViewPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

public class TableCardsClient implements ClientModInitializer {
    /** The last view the server sent, while a game is on. */
    private static volatile ViewModel current;

    @Override
    public void onInitializeClient() {
        ClientSettings.load();
        ClientPlayNetworking.registerGlobalReceiver(ViewPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            ViewModel view;
            try {
                view = ViewModel.parse(payload.json());
            } catch (RuntimeException e) {
                return;
            }
            current = view.over ? null : view;
            if (client.currentScreen instanceof TableGameScreen screen) {
                screen.update(view);
            } else if (payload.open() && client.currentScreen == null) {
                // only open over the game world, never on top of another screen (chat, inventory...)
                client.setScreen(new TableGameScreen(view));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> current = null);
        // right-clicking the table you're playing at reopens the game (the server sends it);
        // without this the mahjong table's own menu would open on this side first
        Sessions.clientPlaysAt = (state, pos) -> {
            ViewModel v = current;
            if (v == null || v.table == null || !MahjongTables.isTable(state)) {
                return false;
            }
            BlockPos center = MahjongTables.center(state, pos);
            return center.getX() == v.table[0] && center.getY() == v.table[1] && center.getZ() == v.table[2];
        };
    }
}
