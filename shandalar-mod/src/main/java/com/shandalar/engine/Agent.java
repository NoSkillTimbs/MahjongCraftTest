package com.shandalar.engine;

import java.util.List;
import java.util.Map;

/**
 * Whoever controls a player: the AI now, a Minecraft GUI session for the human later.
 * The engine only asks questions through this interface.
 */
public interface Agent {
    /** Pick one of the legal actions, or null to end the main phase. */
    Action chooseMainAction(Game game, Player me, List<Action> options);

    /** Pick which eligible creatures attack. */
    List<Permanent> chooseAttackers(Game game, Player me, List<Permanent> eligible);

    /** Map attacker to the blocker assigned to it. Each blocker may be used once. */
    Map<Permanent, Permanent> chooseBlocks(Game game, Player me, List<Permanent> attackers,
                                           List<Permanent> eligibleBlockers);

    /** Pick a card to discard when over the hand limit. */
    CardDef chooseDiscard(Game game, Player me);
}
