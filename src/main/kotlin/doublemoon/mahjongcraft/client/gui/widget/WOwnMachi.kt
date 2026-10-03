package doublemoon.mahjongcraft.client.gui.widget

import doublemoon.mahjongcraft.MOD_ID
import doublemoon.mahjongcraft.MahjongCraftClient
import doublemoon.mahjongcraft.blockentity.MahjongTableBlockEntity
import doublemoon.mahjongcraft.client.ModConfig
import doublemoon.mahjongcraft.entity.MahjongTileEntity
import doublemoon.mahjongcraft.game.mahjong.riichi.model.MahjongTile
import io.github.cottonmc.cotton.gui.widget.WLabel
import io.github.cottonmc.cotton.gui.widget.WPlainPanel
import io.github.cottonmc.cotton.gui.widget.data.Color
import io.github.cottonmc.cotton.gui.widget.data.HorizontalAlignment
import io.github.cottonmc.cotton.gui.widget.data.VerticalAlignment
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * Always-on HUD panel that shows the player's own waits while they are tenpai:
 * each waiting tile, how many of it are still unseen, "No Yaku" and furiten.
 * The server sends the data after every discard (see MahjongGameBehavior.OWN_MACHI).
 */
@Environment(EnvType.CLIENT)
class WOwnMachi(
    private val tileHints: ModConfig.TileHints,
) : WPlainPanel() {
    private val client = MinecraftClient.getInstance()
    private val titleLabel = WLabel(TITLE, Color.YELLOW_DYE.toRgb()).apply {
        darkmodeColor = Color.YELLOW_DYE.toRgb()
        horizontalAlignment = HorizontalAlignment.CENTER
        verticalAlignment = VerticalAlignment.CENTER
    }
    private val items = mutableListOf<Pair<MahjongTile, WTileHints.TileHintItem>>()
    private var lastCountRefreshTick = -1L

    /** Nothing to show: not tenpai, not playing, or turned off. */
    val isEmpty: Boolean get() = machi.isEmpty() || !MahjongCraftClient.playing

    override fun paint(context: DrawContext, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        if (isEmpty) return
        // keep the "x left" counts current as tiles are revealed (about once a second)
        val time = client.world?.time ?: 0L
        if (time - lastCountRefreshTick >= 20) {
            lastCountRefreshTick = time
            refreshCounts()
        }
        super.paint(context, x, y, mouseX, mouseY)
    }

    /** Rebuilds the tile items from [machi] and resizes the panel. */
    fun rebuild() {
        children.toList().forEach { remove(it) }
        items.clear()
        if (isEmpty) {
            setSize(0, 0)
            return
        }
        val table = findTable()
        val scale = tileHints.hudAttribute.scale
        machi.forEach { (tile, info) ->
            val (han, furiten) = info
            val item = WTileHints.TileHintItem(
                scale = scale,
                tile = tile,
                furiten = furiten,
                remainingAmount = remainingOf(table, tile),
                remainingAmountVisible = true,
                noYaku = han == 0,
                machi = true
            )
            items += tile to item
        }
        val titleHeight = client.textRenderer.fontHeight + INSET
        var x = INSET
        items.forEach { (_, item) ->
            add(item, x, titleHeight + INSET, item.width, item.height)
            x += item.width + INTERVAL
        }
        val width = maxOf(x - INTERVAL + INSET, client.textRenderer.getWidth(TITLE) + INSET * 2)
        val height = titleHeight + INSET + (items.maxOfOrNull { it.second.height } ?: 0) + INSET
        add(titleLabel, 0, INSET, width, client.textRenderer.fontHeight)
        setSize(width, height)
        lastCountRefreshTick = client.world?.time ?: 0L
    }

    private fun refreshCounts() {
        val table = findTable() ?: return
        items.forEach { (tile, item) ->
            val (han, _) = machi[tile] ?: return@forEach
            item.setRemainingTiles(
                amount = remainingOf(table, tile),
                visible = true,
                noYaku = han == 0,
                machi = true
            )
        }
    }

    /** The table the player is sitting at: found through one of their own tiles nearby. */
    private fun findTable(): MahjongTableBlockEntity? {
        val player = client.player ?: return null
        val world = client.world ?: return null
        val uuid = player.uuidAsString
        return world.getEntitiesByClass(
            MahjongTileEntity::class.java,
            player.boundingBox.expand(SEARCH_RADIUS)
        ) { it.ownerUUID == uuid }.firstNotNullOfOrNull { it.mahjongTable }
    }

    private fun remainingOf(table: MahjongTableBlockEntity?, tile: MahjongTile): Int {
        val code = tile.mahjong4jTile.code
        table ?: return 0
        table.calculateRemainingTiles(code)
        return table.remainingTiles[code] ?: 0
    }

    companion object {
        private const val INSET = 4
        private const val INTERVAL = 4
        private const val SEARCH_RADIUS = 4.0
        private val TITLE: Text get() = Text.translatable("$MOD_ID.hud.your_waits")

        /** Current waits: tile -> (han, furiten). han == 0 means no yaku. Set from the network listener. */
        var machi: Map<MahjongTile, Pair<Int, Boolean>> = emptyMap()
            set(value) {
                field = value
                MahjongCraftClient.hud?.refresh()
                MahjongCraftClient.hud?.reposition()
            }
    }
}
