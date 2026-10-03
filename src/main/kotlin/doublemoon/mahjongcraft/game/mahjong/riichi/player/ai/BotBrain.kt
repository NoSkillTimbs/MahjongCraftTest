package doublemoon.mahjongcraft.game.mahjong.riichi.player.ai

/**
 * Decision making for mahjong bots. Pure logic over tile codes (see [Shanten] for the code layout),
 * so it can be tested without Minecraft.
 *
 * @param hand counts of the bot's concealed tiles
 * @param melds the bot's open melds (and kans), each as the tile codes it contains
 * @param visible counts of tiles the bot can see outside its own hand:
 *   every discard (including called ones), every open meld, the dora indicators
 * @param dora dora tile codes (not the indicators); a code can appear more than once
 * @param redCodes codes of red fives currently in the bot's hand
 * @param yakuhai codes of honour tiles worth a yaku as a triplet for this bot (dragons, seat wind, round wind)
 * @param riichiSafe one set per opponent in riichi: codes that are safe against that opponent
 *   (their own discards and every tile discarded since their riichi)
 * @param openTanyao whether all-simples counts with an open hand
 */
class BotBrain(
    private val hand: IntArray,
    private val melds: List<List<Int>>,
    private val visible: IntArray,
    private val dora: List<Int>,
    private val redCodes: Collection<Int>,
    private val yakuhai: Set<Int>,
    private val riichiSafe: List<Set<Int>>,
    private val openTanyao: Boolean,
) {
    private val meldCount get() = melds.size

    /** Copies of [code] that could still be drawn or discarded by others, as far as the bot knows. */
    fun remaining(code: Int): Int = maxOf(0, 4 - visible[code] - hand[code])

    fun shanten(h: IntArray = hand, m: Int = meldCount): Int = Shanten.of(h, m)

    /** Live tiles that would lower the shanten of [h] (a hand waiting for a draw). */
    fun ukeire(h: IntArray, m: Int = meldCount): Int {
        val base = Shanten.of(h, m)
        var total = 0
        for (t in 0 until Shanten.KINDS) {
            if (h[t] >= 4) continue
            h[t]++
            if (Shanten.of(h, m) < base) total += remaining(t)
            h[t]--
        }
        return total
    }

    // ------------------------------------------------------------------ discarding

    private class Option(val code: Int, val shanten: Int, val ukeire: Int, val value: Double, val safety: Int)

    /**
     * Picks the tile code to discard from [candidates] (codes the bot is allowed to discard).
     */
    fun chooseDiscard(candidates: Collection<Int>): Int {
        val options = candidates.distinct().map { c ->
            val h = hand.copyOf().also { it[c]-- }
            val s = Shanten.of(h, meldCount)
            Option(c, s, ukeire(h), discardPreference(c), safety(c))
        }
        val bestShanten = options.minOf { it.shanten }
        val score: (Option) -> Double = when {
            riichiSafe.isEmpty() -> { o -> efficiency(o) }
            bestShanten >= 2 -> { o -> o.safety * 100.0 + efficiency(o) / 100.0 }   // fold
            bestShanten == 1 -> { o -> efficiency(o) + o.safety * 15.0 }            // careful
            else -> { o -> efficiency(o) + o.safety * 3.0 }                          // tenpai: push
        }
        return options.maxBy(score).code
    }

    private fun efficiency(o: Option): Double = -o.shanten * 1000.0 + o.ukeire * 4.0 + o.value

    /** Positive = happier to throw this tile away. */
    private fun discardPreference(c: Int): Double {
        var v = 0.0
        if (!Shanten.isNumber(c)) {
            v += when {
                c in yakuhai && hand[c] >= 2 -> -30.0           // keep a pair of value honours
                c in yakuhai -> 4.0
                hand[c] == 1 -> 12.0                            // lone guest wind: first to go
                else -> 2.0
            }
            if (remaining(c) == 0 && hand[c] == 1) v += 6.0     // no partner can come
        } else {
            when (Shanten.rank(c)) {
                1, 9 -> v += 3.0
                2, 8 -> v += 1.0
            }
        }
        v -= 12.0 * dora.count { it == c }
        if (c in redCodes && hand[c] == 1) v -= 12.0            // the only copy is the red five
        return v
    }

    /** 0..100, how safe [c] is against everyone in riichi (the worst case counts). */
    fun safety(c: Int): Int {
        if (riichiSafe.isEmpty()) return 100
        return riichiSafe.minOf { safe -> safetyAgainst(c, safe) }
    }

    private fun safetyAgainst(c: Int, safe: Set<Int>): Int {
        if (c in safe) return 100                               // genbutsu
        val seen = visible[c] + hand[c]
        if (!Shanten.isNumber(c)) return when {
            seen >= 3 -> 90                                    // nobody can wait on it but a single
            seen == 2 -> 70
            else -> 45
        }
        val r = Shanten.rank(c)
        val low = if (r - 3 >= 1) c - 3 in safe else true      // suji on the lower side
        val high = if (r + 3 <= 9) c + 3 in safe else true     // suji on the upper side
        val base = when (r) {
            1, 9 -> 30
            2, 8 -> 20
            else -> 5
        }
        val suji = when {
            r in 4..6 && low && high -> 70
            r in 4..6 && (low || high) -> 40
            r !in 4..6 && low && high -> 65
            else -> 0
        }
        return maxOf(base, suji)
    }

    // ------------------------------------------------------------------ riichi

    /**
     * Chooses the riichi discard: the one leaving the most live winning tiles.
     * @param options (discard code, wait codes) pairs offered by the game
     * @return the discard code, or null to stay quiet (every wait is dead)
     */
    fun chooseRiichiDiscard(options: List<Pair<Int, List<Int>>>): Int? {
        val best = options.maxByOrNull { (discard, waits) -> liveWaits(discard, waits) } ?: return null
        return if (liveWaits(best.first, best.second) > 0) best.first else null
    }

    private fun liveWaits(discard: Int, waits: List<Int>): Int =
        waits.distinct().sumOf { w -> remaining(w) + if (w == discard) 1 else 0 }

    // ------------------------------------------------------------------ calls

    /** Shanten of the current hand while waiting for a draw (13-tile equivalent). */
    private fun currentShanten(): Int = Shanten.of(hand, meldCount)

    /** Best shanten reachable after a call that uses [fromHand] and must then discard. */
    private fun shantenAfterCall(fromHand: List<Int>): Int {
        val h = hand.copyOf()
        for (t in fromHand) {
            if (h[t] <= 0) return 99
            h[t]--
        }
        var best = 99
        for (d in 0 until Shanten.KINDS) {
            if (h[d] == 0) continue
            h[d]--
            best = minOf(best, Shanten.of(h, meldCount + 1))
            h[d]++
        }
        return best
    }

    /**
     * Whether an open hand that includes [newMeld] still has a yaku the bot can win with:
     * a value-honour triplet (open or concealed), or all simples when open tanyao is allowed.
     * @param fromHand the tiles of [newMeld] that come out of the bot's hand
     */
    private fun hasOpenYaku(newMeld: List<Int>, fromHand: List<Int>): Boolean {
        val allMelds = melds + listOf(newMeld)
        if (allMelds.any { m -> m.size >= 3 && m.all { it == m[0] } && m[0] in yakuhai }) return true
        val h = hand.copyOf().also { fromHand.forEach { t -> it[t]-- } }
        if (yakuhai.any { h[it] >= 3 }) return true
        if (openTanyao && allMelds.all { m -> m.none(Shanten::isYaochu) }) {
            val terminalsLeft = (0 until Shanten.KINDS).filter { Shanten.isYaochu(it) }.sumOf { h[it] }
            if (terminalsLeft <= 1) return true                 // one stray terminal/honour is easy to drop
        }
        return false
    }

    /** Pon [code] using two copies from the hand? */
    fun shouldPon(code: Int): Boolean {
        val meld = listOf(code, code, code)
        if (!hasOpenYaku(meld, fromHand = listOf(code, code))) return false
        val after = shantenAfterCall(listOf(code, code))
        val now = currentShanten()
        return if (code in yakuhai) after <= now else after < now
    }

    /**
     * Chii [code] with one of [pairs] (two codes from the hand each)?
     * @return the pair to use, or null to pass
     */
    fun chooseChii(code: Int, pairs: List<Pair<Int, Int>>): Pair<Int, Int>? {
        val now = currentShanten()
        return pairs
            .filter { (a, b) -> hasOpenYaku(listOf(code, a, b).sorted(), fromHand = listOf(a, b)) }
            .map { it to shantenAfterCall(listOf(it.first, it.second)) }
            .filter { (_, after) -> after < now }
            .minByOrNull { it.second }
            ?.first
    }

    /** Declare a closed kan of [code] (four in hand)? Only if it doesn't slow the hand down. */
    fun shouldAnkan(code: Int): Boolean {
        if (hand[code] < 4) return false
        var before = 99
        for (d in 0 until Shanten.KINDS) {
            if (hand[d] == 0) continue
            val h = hand.copyOf().also { it[d]-- }
            before = minOf(before, Shanten.of(h, meldCount))
        }
        val h = hand.copyOf().also { it[code] -= 4 }
        return Shanten.of(h, meldCount + 1) <= before
    }

    /** Nine kinds of terminals: abort the hand unless thirteen orphans looks reachable. */
    fun shouldAbortKyuushu(): Boolean = Shanten.kokushi(hand) > 3
}
