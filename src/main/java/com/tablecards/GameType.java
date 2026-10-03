package com.tablecards;

import com.tablecards.engine.CardGame;
import com.tablecards.engine.ptcg.PtcgCard;
import com.tablecards.engine.ptcg.PtcgGame;
import com.tablecards.engine.ygo.YgoCard;
import com.tablecards.engine.ygo.YgoGame;

import java.util.ArrayList;
import java.util.List;

/** The card games a table can host. Decks come from the current {@link CardPools} snapshot. */
public enum GameType {
    YUGIOH("Yu-Gi-Oh!", "duel") {
        @Override
        public List<String> deckNames() {
            return new ArrayList<>(CardPools.get().ygoDecks.keySet());
        }

        @Override
        public Object deck(String name) {
            return CardPools.get().ygoDecks.get(name);
        }

        @Override
        @SuppressWarnings("unchecked")
        public CardGame create(String[] names, Object deck0, Object deck1, long seed) {
            return new YgoGame(names, (List<YgoCard>) deck0, (List<YgoCard>) deck1, seed);
        }
    },
    POKEMON("Pokemon TCG", "battle") {
        @Override
        public List<String> deckNames() {
            return new ArrayList<>(CardPools.get().ptcgDecks.keySet());
        }

        @Override
        public Object deck(String name) {
            return CardPools.get().ptcgDecks.get(name);
        }

        @Override
        @SuppressWarnings("unchecked")
        public CardGame create(String[] names, Object deck0, Object deck1, long seed) {
            return new PtcgGame(names, (List<PtcgCard>) deck0, (List<PtcgCard>) deck1, seed);
        }
    };

    public final String title;
    /** What one game is called ("duel", "battle"), for chat messages. */
    public final String match;

    GameType(String title, String match) {
        this.title = title;
        this.match = match;
    }

    /** Names of the decks players can pick right now (their own lists first, starter decks last). */
    public abstract List<String> deckNames();

    /** The cards of a deck (a List of this game's card type), or null if it no longer exists. */
    public abstract Object deck(String name);

    public abstract CardGame create(String[] names, Object deck0, Object deck1, long seed);
}
