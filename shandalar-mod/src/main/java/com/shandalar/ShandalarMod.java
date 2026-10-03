package com.shandalar;

import com.shandalar.engine.CardLibrary;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entry point (Fabric). Registries (items, blocks, entities, screens, dimensions) will be
 * hooked up here in later milestones. The duel rules live in com.shandalar.engine and
 * deliberately know nothing about Minecraft.
 */
public class ShandalarMod implements ModInitializer {
    public static final String MOD_ID = "shandalar";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** The card pool, loaded from the jar at startup. */
    public static CardLibrary cards;

    @Override
    public void onInitialize() {
        cards = CardLibrary.loadResource("/data/shandalar/cards.json");
        LOGGER.info("Shandalar loaded {} cards", cards.size());
        // TODO milestone 2: register card item, deck-box item, duel screen
    }
}
