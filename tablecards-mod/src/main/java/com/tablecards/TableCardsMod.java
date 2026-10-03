package com.tablecards;

import com.tablecards.net.ChoosePayload;
import com.tablecards.net.ViewPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Table Cards: lets MahjongCraft's mahjong table host Yu-Gi-Oh! and Pokemon TCG games.
 * Right-click a table with a deck to open a game; a second player joins the same way, or the
 * host plays a bot.
 */
public class TableCardsMod implements ModInitializer {
    public static final String MOD_ID = "tablecards";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final DeckItem YUGIOH_DECK = register("yugioh_deck", new DeckItem(GameType.YUGIOH, new Item.Settings().maxCount(1)));
    public static final DeckItem POKEMON_DECK = register("pokemon_deck", new DeckItem(GameType.POKEMON, new Item.Settings().maxCount(1)));

    private static DeckItem register(String name, DeckItem item) {
        return Registry.register(Registries.ITEM, Identifier.of(MOD_ID, name), item);
    }

    @Override
    public void onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(YUGIOH_DECK);
            entries.add(POKEMON_DECK);
        });

        PayloadTypeRegistry.playS2C().register(ViewPayload.ID, ViewPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ChoosePayload.ID, ChoosePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ChoosePayload.ID,
                (payload, context) -> Sessions.onChoose(context.player(), payload));

        UseBlockCallback.EVENT.register(Sessions::onUseBlock);
        ServerTickEvents.END_SERVER_TICK.register(Sessions::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Sessions.onDisconnect(handler.getPlayer()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> Sessions.clear());
        // only real cards are used, so fetch them the first time a server (or single-player world) starts
        ServerLifecycleEvents.SERVER_STARTED.register(Commands::autoImport);

        Commands.register();
        // load the card pools now so a broken card file shows up at startup, not mid-game
        CardPools.Snapshot pools = CardPools.load(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID));
        LOGGER.info(Commands.summary(pools));
        pools.problems.forEach(p -> LOGGER.warn("Table Cards: {}", p));
    }
}
