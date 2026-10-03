package doublemoon.mahjongcraft.game.mahjong.riichi.player

import doublemoon.mahjongcraft.entity.MahjongBotEntity
import doublemoon.mahjongcraft.game.mahjong.riichi.MahjongGame
import doublemoon.mahjongcraft.game.mahjong.riichi.model.ClaimTarget
import doublemoon.mahjongcraft.game.mahjong.riichi.model.MahjongGameBehavior
import doublemoon.mahjongcraft.game.mahjong.riichi.model.MahjongRule
import doublemoon.mahjongcraft.game.mahjong.riichi.model.MahjongTile
import doublemoon.mahjongcraft.game.mahjong.riichi.player.ai.BotBrain
import doublemoon.mahjongcraft.game.mahjong.riichi.player.ai.BotTableView
import doublemoon.mahjongcraft.logger
import java.util.EnumSet
import net.minecraft.server.world.ServerWorld
import net.minecraft.network.packet.s2c.play.PositionFlag
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

/**
 * 麻將機器人,
 * 使用 [MahjongGame.addBot] 的時候就會生成 [entity] 了 (並不會遊戲開始才生成)
 *
 * Decisions come from [BotBrain]: tile-efficiency discards, riichi when ready, calls only when the
 * hand keeps a yaku, and defence against riichi. If a decision ever throws, the bot falls back to
 * the simple default behaviour so the game never stalls.
 *
 * @param world 預設生成的世界
 * @param pos 預設生成的位置
 * @param gamePos 生成這個機器人的遊戲的方塊座標,
 * @param view 機器人能看到的牌桌資訊 / what the bot can see of the table
 * */
class MahjongBot(
    val world: ServerWorld,
    pos: Vec3d,
    gamePos: BlockPos,
    private val view: BotTableView,
) : MahjongPlayerBase() {

    override val entity: MahjongBotEntity = MahjongBotEntity(world = world).apply {
        code = MahjongTile.random().code //隨機決定外觀
        isSpawnedByGame = true
        gameBlockPos = gamePos
        isInvisible = true //先隱形, 等開始遊戲才解除隱形
        refreshPositionAfterTeleport(pos)  //再傳送到預設位置上
        world.spawnEntity(this) //最後才在世界上生成
    }

    override var ready: Boolean = true

    override fun teleport(targetWorld: ServerWorld, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        with(entity) {
            this.teleport(
                targetWorld,
                x,
                y,
                z,
                EnumSet.noneOf(PositionFlag::class.java),
                yaw,
                pitch
            )
        }
    }

    // ------------------------------------------------------------------ decisions

    private val MahjongTile.tileCode: Int get() = mahjong4jTile.code

    /** A snapshot of the bot's hand and the table, for one decision. */
    private fun brain(): BotBrain {
        val counts = IntArray(34)
        val reds = mutableListOf<Int>()
        hands.forEach {
            counts[it.mahjong4jTile.code]++
            if (it.mahjongTile.isRed) reds += it.mahjong4jTile.code
        }
        return BotBrain(
            hand = counts,
            melds = fuuroList.map { fuuro -> fuuro.tileMjEntities.map { it.mahjong4jTile.code } },
            visible = view.visibleCounts(this),
            dora = view.doraCodes(),
            redCodes = reds,
            yakuhai = view.yakuhaiCodes(this),
            riichiSafe = view.riichiSafeSets(this),
            openTanyao = view.openTanyao,
        )
    }

    /** Of the tiles with [code], prefer giving up a normal five over a red one. */
    private fun List<MahjongTile>.pick(code: Int): MahjongTile? =
        filter { it.tileCode == code }.let { same -> same.firstOrNull { !it.isRed } ?: same.firstOrNull() }

    private inline fun <T> decide(what: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            logger.warn("Mahjong bot failed to decide $what, using the default", e)
            fallback
        }

    override suspend fun askToDiscardTile(
        timeoutTile: MahjongTile,
        cannotDiscardTiles: List<MahjongTile>,
        skippable: Boolean,
    ): MahjongTile {
        val candidates = hands.map { it.mahjongTile }.filter { it !in cannotDiscardTiles }
        if (candidates.isEmpty()) return timeoutTile
        val fallback = candidates.last()
        return decide("a discard", fallback) {
            val code = brain().chooseDiscard(candidates.map { it.tileCode })
            candidates.pick(code) ?: fallback
        }
    }

    override suspend fun askToRiichi(
        tilePairsForRiichi: List<Pair<MahjongTile, List<MahjongTile>>>,
    ): MahjongTile? {
        if (tilePairsForRiichi.isEmpty()) return null
        return decide("riichi", null) {
            val options = tilePairsForRiichi.map { (discard, waits) -> discard.tileCode to waits.map { it.tileCode } }
            val code = brain().chooseRiichiDiscard(options) ?: return@decide null
            tilePairsForRiichi.map { it.first }.pick(code)
        }
    }

    override suspend fun askToPon(
        tile: MahjongTile,
        tilePairForPon: Pair<MahjongTile, MahjongTile>,
        target: ClaimTarget,
    ): Boolean = decide("a pon", false) { brain().shouldPon(tile.tileCode) }

    override suspend fun askToChii(
        tile: MahjongTile,
        tilePairs: List<Pair<MahjongTile, MahjongTile>>,
        target: ClaimTarget,
    ): Pair<MahjongTile, MahjongTile>? = decide("a chii", null) { chooseChii(tile, tilePairs) }

    private fun chooseChii(
        tile: MahjongTile,
        tilePairs: List<Pair<MahjongTile, MahjongTile>>,
    ): Pair<MahjongTile, MahjongTile>? {
        if (tilePairs.isEmpty()) return null
        val chosen = brain().chooseChii(tile.tileCode, tilePairs.map { it.first.tileCode to it.second.tileCode })
            ?: return null
        return tilePairs.firstOrNull { it.first.tileCode == chosen.first && it.second.tileCode == chosen.second }
    }

    override suspend fun askToPonOrChii(
        tile: MahjongTile,
        tilePairsForChii: List<Pair<MahjongTile, MahjongTile>>,
        tilePairForPon: Pair<MahjongTile, MahjongTile>,
        target: ClaimTarget,
    ): Pair<MahjongTile, MahjongTile>? = decide("a pon or chii", null) {
        if (brain().shouldPon(tile.tileCode)) tilePairForPon else chooseChii(tile, tilePairsForChii)
    }

    /** An open kan gives everyone a new dora and slows the hand, so the bot pons instead when the pon is worth it. */
    override suspend fun askToMinkanOrPon(
        tile: MahjongTile,
        target: ClaimTarget,
        rule: MahjongRule,
    ): MahjongGameBehavior = decide("a kan", MahjongGameBehavior.SKIP) {
        if (brain().shouldPon(tile.tileCode)) MahjongGameBehavior.PON else MahjongGameBehavior.SKIP
    }

    override suspend fun askToAnkanOrKakan(
        canAnkanTiles: Set<MahjongTile>,
        canKakanTiles: Set<Pair<MahjongTile, ClaimTarget>>,
        rule: MahjongRule,
    ): MahjongTile? = decide("a closed or added kan", null) {
        // adding a fourth tile to an existing pon never costs anything
        canKakanTiles.firstOrNull()?.first
            ?: brain().let { b -> canAnkanTiles.firstOrNull { b.shouldAnkan(it.tileCode) } }
    }

    override suspend fun askToKyuushuKyuuhai(): Boolean =
        decide("nine terminals", true) { brain().shouldAbortKyuushu() }
}
