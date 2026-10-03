package doublemoon.mahjongcraft.game.mahjong.riichi.player.ai

import kotlin.random.Random

/**
 * What a bot says in chat after beating real players. `{name}` is replaced with the loser's name
 * (or several names joined together after a self-drawn win).
 */
object BotTrashTalk {

    /** A bot won off this player's discard. */
    private val dealtIn = listOf(
        "{name} really dealt into me. Certified chud behavior.",
        "Chud alert: {name} just handed me the win.",
        "{name}, that discard was so chud I felt it through the table.",
        "Bro discarded my wait. {name} is a chud, no cap.",
        "{name} really thought that tile was safe. Chud.",
        "Imagine dealing into a bot. Couldn't be me. Could only be a chud like {name}.",
        "{name} said \"this one's probably fine\" like a true chud.",
        "Thank you for your donation, chud {name}.",
        "{name} speedran dealing in. Chud%.",
        "That discard was so chud it should be a yaku, {name}.",
    )

    /** A bot won by its own draw (everyone pays) or by nagashi mangan. */
    private val selfDraw = listOf(
        "Tsumo, chuds. {name}, pay up.",
        "Didn't even need your tiles. {name}, you're all chuds.",
        "Self-drawn. {name} couldn't stop me, chuds can't defend.",
        "Tsumo. {name}, the chud tax is due.",
        "I drew it myself because {name} is too chud to deal in fast enough.",
    )

    /** Mixed in with both. */
    private val general = listOf(
        "{name} is the chud-est player at this table.",
        "Skill issue, {name}. Chud diff.",
        "{name} has negative aura. Pure chud energy.",
        "Sit down, chud. Bot diff, {name}.",
        "It's giving chud, {name}.",
        "{name}'s mahjong IQ is in the basement with the other chuds.",
        "No thoughts, just chud. GG {name}.",
        "{name} is cooked. Fully chud-cooked.",
        "Touch grass, {name}. Chud.",
        "Bot 1, chud {name} 0.",
        "{name} got bodied by a bot. Chud of the year.",
        "Delulu if you thought you'd win that, {name}. Chud.",
        "{name}, your whole hand was NPC. Chud NPC.",
        "Ratio + L + {name} is a chud.",
        "{name} fell off. Chud arc.",
        "Mid player, chud discards, L. Sorry {name}.",
        "Who let {name} cook? Chud kitchen.",
        "{name} the type of chud to lose to a bot. Oh wait.",
    )

    fun afterRon(name: String, random: Random = Random.Default): String =
        (dealtIn + general).random(random).replace("{name}", name)

    fun afterSelfDraw(name: String, random: Random = Random.Default): String =
        (selfDraw + general).random(random).replace("{name}", name)
}
