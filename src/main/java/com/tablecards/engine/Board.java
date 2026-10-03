package com.tablecards.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * The table as one player sees it, for the visual game screen: each side's zones and the cards in
 * them, plus life points or prizes. Hidden information (the opponent's hand, face-down cards they
 * own, decks) is never put in. Engine objects behind each card ({@link CardView#refs}) let the
 * session work out which options belong to which card; they are not sent to clients.
 */
public final class Board {
    /** Where a zone sits on its side: row 0 is next to the middle line, row 1 behind it. */
    public enum Align { CENTER, LEFT, RIGHT }

    public static final class CardView {
        public final String name;
        /** Card art to download: "ygo:&lt;passcode&gt;" or "ptcg:&lt;set&gt;/&lt;number&gt;", or "" for cards drawn by the mod. */
        public String image = "";
        /**
         * What frame to draw when there's no art: ygo_monster, ygo_spell, ygo_trap, or
         * ptcg_&lt;type&gt; (fire, water...), ptcg_trainer, ptcg_energy_&lt;type&gt;.
         */
        public String frame = "";
        public boolean faceDown;
        /** Face-down but its owner may look at it (own set cards). */
        public boolean peek;
        /** Turned sideways (Yu-Gi-Oh! defense position). */
        public boolean sideways;
        /** Short stat lines drawn on the card: top-right (Level, HP) and bottom (ATK/DEF, damage). */
        public String corner = "";
        public String stat = "";
        /** Red badge, e.g. damage on a Pokemon. */
        public String badge = "";
        /** Status words shown under the card (Asleep, Poisoned, Equipped...). */
        public final List<String> tags = new ArrayList<>();
        /** Full text for the big preview. */
        public final List<String> text = new ArrayList<>();
        /** Cards attached to this one (Energy, or the cards under an evolved Pokemon). */
        public final List<CardView> attached = new ArrayList<>();
        /** Engine objects this card stands for (the card, its zone object...). Not sent. */
        public final List<Object> refs = new ArrayList<>();
        /** Strings option labels use for this card; removed to make short button labels. Not sent. */
        public final List<String> aliases = new ArrayList<>();

        public CardView(String name) {
            this.name = name;
        }

        public CardView ref(Object... objects) {
            for (Object o : objects) {
                if (o != null) {
                    refs.add(o);
                }
            }
            return this;
        }

        public CardView alias(String... strings) {
            for (String s : strings) {
                if (s != null && !s.isEmpty()) {
                    aliases.add(s);
                }
            }
            return this;
        }

        /** A face-down card nobody at the table may look at. */
        public static CardView hidden(String frameGame) {
            CardView c = new CardView("Face-down card");
            c.faceDown = true;
            c.frame = frameGame;
            return c;
        }
    }

    public static final class Zone {
        public final String id;
        public final String label;
        public final int row;
        public final Align align;
        /** Fixed number of card spaces (5 monster zones); 1 for a pile. */
        public final int slots;
        /** A pile shows only its top card and a count. */
        public final boolean pile;
        /** Number of cards in a pile (also when they're hidden, like a deck). */
        public int count;
        public final List<CardView> cards = new ArrayList<>();

        public Zone(String id, String label, int row, Align align, int slots, boolean pile) {
            this.id = id;
            this.label = label;
            this.row = row;
            this.align = align;
            this.slots = slots;
            this.pile = pile;
        }
    }

    public static final class Side {
        public final String name;
        /** Big number next to the field: LP, or Prizes left. */
        public String score = "";
        public String scoreLabel = "";
        /** Small lines under the score (deck size, "Supporter played"...). */
        public final List<String> info = new ArrayList<>();
        public final List<Zone> zones = new ArrayList<>();
        /** The hand; for the opponent only a count (cards are hidden). */
        public final List<CardView> hand = new ArrayList<>();
        public int handCount;
        /** Whose turn it is. */
        public boolean active;

        public Side(String name) {
            this.name = name;
        }

        public Zone zone(Zone z) {
            zones.add(z);
            return z;
        }
    }

    /** "ygo" or "ptcg": picks the layout, card shape and card backs. */
    public final String game;
    /** Seat 0 is the player looking, seat 1 the opponent. */
    public final Side you;
    public final Side opp;
    /** Turn and phase line. */
    public String phase = "";
    /** One-line hint about what the player can do right now. */
    public String help = "";

    public Board(String game, Side you, Side opp) {
        this.game = game;
        this.you = you;
        this.opp = opp;
    }
}
