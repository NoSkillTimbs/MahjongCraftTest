package doublemoon.mahjongcraft.block.enums

import net.minecraft.util.StringIdentifiable

/**
 * The Game Table's tabletop design, chosen by the wood in its recipe.
 * Bamboo makes the classic mahjong table.
 */
enum class GameTableTheme(val wood: String) : StringIdentifiable {
    CLASSIC("bamboo"),
    FOREST("oak"),
    TAIGA("spruce"),
    MEADOW("birch"),
    JUNGLE("jungle"),
    DESERT("acacia"),
    DARK_FOREST("dark_oak"),
    SWAMP("mangrove"),
    CHERRY_GROVE("cherry"),
    VOLCANO("crimson"),
    WARPED_FOREST("warped"),
    ;

    override fun toString(): String = this.name.lowercase()

    override fun asString(): String = this.name.lowercase()
}
