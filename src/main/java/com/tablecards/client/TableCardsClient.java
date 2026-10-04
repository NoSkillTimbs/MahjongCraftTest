package com.tablecards.client;

import com.tablecards.MahjongTables;
import com.tablecards.Sessions;
import com.tablecards.TableCardsMod;
import com.tablecards.net.ViewPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.item.ModelPredicateProviderRegistry;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;

public class TableCardsClient implements ClientModInitializer {
    /** The last view of your own game the server sent, while it's on. */
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
            if (client.world != null) {
                TableViews.accept(view, client.world.getRegistryKey().getValue().toString(), Util.getMeasuringTimeMs());
            }
            if (view.closed || view.watcher) {
                // a game nearby that you're watching (it's drawn on its table), or a table that's free again
                ViewModel cur = current;
                if (view.closed && cur != null && sameTable(cur, view)) {
                    current = null;
                }
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
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            current = null;
            TableViews.clear();
            TableRenderer.forget();
        });
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

        // where your cards are on the screen, so the game screen knows what you click
        WorldRenderEvents.END.register(context ->
                TableRenderer.computeHits(context.projectionMatrix(), context.positionMatrix(), context.camera().getPos()));
        // forget games you watched once you're far away or in another world
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && client.world != null && client.world.getTime() % 20 == 0) {
                TableViews.prune(client.world.getRegistryKey().getValue().toString(),
                        client.player.getX(), client.player.getY(), client.player.getZ(), 64);
            }
        });
        // a reminder when it's your move and the game screen is closed
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ViewModel v = current;
            if (v == null || !v.yourTurn || v.over || client.currentScreen != null || client.options.hudHidden) {
                return;
            }
            Text hint = Text.translatable("message.tablecards.screen_hint").formatted(Formatting.GOLD);
            int w = client.textRenderer.getWidth(hint);
            ctx.drawTextWithShadow(client.textRenderer, hint, ctx.getScaledWindowWidth() / 2 - w / 2, 12, 0xFFFFFFFF);
        });

        // the Game Table item: its design picks the item model, and shows in the tooltip
        Item table = Registries.ITEM.get(Identifier.of("mahjongcraft", "mahjong_table"));
        ModelPredicateProviderRegistry.register(table, Identifier.of("mahjongcraft", "theme"),
                (stack, world, entity, seed) -> TableCardsMod.THEMES.indexOf(TableCardsMod.themeOf(stack)) / 16f);
        ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipType, lines) -> {
            if (stack.getItem() == table) {
                lines.add(Text.translatable("tablecards.theme." + TableCardsMod.themeOf(stack)).formatted(Formatting.GRAY));
                lines.add(Text.translatable("tablecards.table.tooltip").formatted(Formatting.DARK_GRAY));
            }
        });
    }

    private static boolean sameTable(ViewModel a, ViewModel b) {
        return a.table != null && b.table != null
                && a.table[0] == b.table[0] && a.table[1] == b.table[1] && a.table[2] == b.table[2];
    }
}
