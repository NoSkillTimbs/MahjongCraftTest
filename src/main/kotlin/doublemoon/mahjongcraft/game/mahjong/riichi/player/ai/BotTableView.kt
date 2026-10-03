package doublemoon.mahjongcraft.game.mahjong.riichi.player.ai

import doublemoon.mahjongcraft.game.mahjong.riichi.player.MahjongPlayerBase

/**
 * What a bot can see of the table, provided by the game. Everything here is public information
 * to a player sitting at the table; tile codes follow [Shanten]'s layout.
 */
interface BotTableView {
    /** Counts of tiles visible outside [player]'s own hand: all discards (called ones too), all open melds, dora indicators. */
    fun visibleCounts(player: MahjongPlayerBase): IntArray

    /** Current dora tile codes (the tiles the indicators point to). */
    fun doraCodes(): List<Int>

    /** Codes of honour tiles that are worth a yaku as a triplet for [player]: dragons, seat wind, round wind. */
    fun yakuhaiCodes(player: MahjongPlayerBase): Set<Int>

    /** One set per opponent of [player] in riichi: their discards plus every tile discarded since their riichi. */
    fun riichiSafeSets(player: MahjongPlayerBase): List<Set<Int>>

    /** Whether the table's rules allow open tanyao. */
    val openTanyao: Boolean
}
