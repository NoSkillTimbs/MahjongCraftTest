package doublemoon.mahjongcraft.game.mahjong.riichi.player.ai

/**
 * Shanten calculator: how many tile exchanges a hand is away from tenpai.
 * -1 = complete hand, 0 = tenpai, 1 = one away, ...
 *
 * Hands are 34-length count arrays indexed by mahjong4j tile code:
 * 0..8 man, 9..17 pin, 18..26 sou, 27..30 E S W N, 31..33 white, green, red dragon.
 * Red fives are counted as normal fives.
 */
object Shanten {
    const val KINDS = 34

    private val YAOCHU = intArrayOf(0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33)

    fun isNumber(code: Int) = code < 27
    fun rank(code: Int) = code % 9 + 1 // 1..9 for number tiles
    fun isYaochu(code: Int) = !isNumber(code) || rank(code) == 1 || rank(code) == 9

    /**
     * Best shanten over the regular form, seven pairs and thirteen orphans.
     * @param concealed counts of the concealed tiles
     * @param melds number of open (called) sets and kans
     */
    fun of(concealed: IntArray, melds: Int): Int {
        var best = regular(concealed, melds)
        if (melds == 0) {
            best = minOf(best, chiitoitsu(concealed), kokushi(concealed))
        }
        return best
    }

    /** Four sets and a pair. */
    fun regular(concealed: IntArray, melds: Int): Int {
        val h = concealed.copyOf()
        var best = 8
        fun dfs(start: Int, sets: Int, partials: Int, pair: Boolean) {
            var i = start
            while (i < KINDS && h[i] == 0) i++
            if (i >= KINDS) {
                val usable = minOf(partials, 4 - sets)
                val s = 8 - 2 * sets - usable - (if (pair) 1 else 0)
                if (s < best) best = s
                return
            }

            if (h[i] >= 3) {                                   // triplet
                h[i] -= 3; dfs(i, sets + 1, partials, pair); h[i] += 3
            }
            if (isNumber(i) && rank(i) <= 7 && h[i + 1] > 0 && h[i + 2] > 0) { // run
                h[i]--; h[i + 1]--; h[i + 2]--
                dfs(i, sets + 1, partials, pair)
                h[i]++; h[i + 1]++; h[i + 2]++
            }
            if (h[i] >= 2) {
                h[i] -= 2
                if (!pair) dfs(i, sets, partials, true)        // the pair
                dfs(i, sets, partials + 1, pair)              // a pair waiting to become a triplet
                h[i] += 2
            }
            if (isNumber(i) && rank(i) <= 8 && h[i + 1] > 0) { // two in a row
                h[i]--; h[i + 1]--; dfs(i, sets, partials + 1, pair); h[i]++; h[i + 1]++
            }
            if (isNumber(i) && rank(i) <= 7 && h[i + 2] > 0) { // gap (kanchan)
                h[i]--; h[i + 2]--; dfs(i, sets, partials + 1, pair); h[i]++; h[i + 2]++
            }
            h[i]--; dfs(i, sets, partials, pair); h[i]++        // leave it as a floating tile
        }
        dfs(0, melds, 0, false)
        return best
    }

    /** Seven distinct pairs. */
    fun chiitoitsu(h: IntArray): Int {
        var pairs = 0
        var kinds = 0
        for (c in h) {
            if (c >= 1) kinds++
            if (c >= 2) pairs++
        }
        return 6 - pairs + maxOf(0, 7 - kinds)
    }

    /** One of each terminal and honour plus one pair. */
    fun kokushi(h: IntArray): Int {
        var kinds = 0
        var pair = false
        for (c in YAOCHU) {
            if (h[c] >= 1) kinds++
            if (h[c] >= 2) pair = true
        }
        return 13 - kinds - (if (pair) 1 else 0)
    }
}
