package com.shandalar.engine;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class Player {
    public final String name;
    public int life = 20;
    public final List<CardDef> library = new ArrayList<>();
    public final List<CardDef> hand = new ArrayList<>();
    public final List<Permanent> battlefield = new ArrayList<>();
    public final List<CardDef> graveyard = new ArrayList<>();
    public boolean landPlayed;
    public boolean drewFromEmptyLibrary;

    public Player(String name, List<CardDef> deck) {
        this.name = name;
        this.library.addAll(deck);
    }

    public boolean lost() {
        return life <= 0 || drewFromEmptyLibrary;
    }

    /** Draws the top card; drawing from an empty library loses the game. */
    public void draw() {
        if (library.isEmpty()) {
            drewFromEmptyLibrary = true;
            return;
        }
        hand.add(library.remove(library.size() - 1));
    }

    public List<Permanent> creatures() {
        List<Permanent> out = new ArrayList<>();
        for (Permanent p : battlefield) {
            if (p.isCreature()) {
                out.add(p);
            }
        }
        return out;
    }

    public int landCountInHand() {
        int n = 0;
        for (CardDef c : hand) {
            if (c.type == CardType.LAND) {
                n++;
            }
        }
        return n;
    }

    public boolean canPay(ManaCost cost) {
        Map<Color, Integer> avail = new EnumMap<>(Color.class);
        int total = 0;
        for (Permanent p : battlefield) {
            if (p.isLand() && !p.tapped) {
                avail.merge(p.def.landColor, 1, Integer::sum);
                total++;
            }
        }
        int coloredNeeded = 0;
        for (Map.Entry<Color, Integer> e : cost.colored().entrySet()) {
            if (avail.getOrDefault(e.getKey(), 0) < e.getValue()) {
                return false;
            }
            coloredNeeded += e.getValue();
        }
        return total - coloredNeeded >= cost.generic();
    }

    /** Taps lands to pay. Call canPay first. Colored symbols are paid before generic mana. */
    public void pay(ManaCost cost) {
        for (Map.Entry<Color, Integer> e : cost.colored().entrySet()) {
            for (int i = 0; i < e.getValue(); i++) {
                tapLand(e.getKey());
            }
        }
        for (int i = 0; i < cost.generic(); i++) {
            tapLand(null);
        }
    }

    private void tapLand(Color wanted) {
        for (Permanent p : battlefield) {
            if (p.isLand() && !p.tapped && (wanted == null || p.def.landColor == wanted)) {
                p.tapped = true;
                return;
            }
        }
        throw new IllegalStateException("Cannot pay mana for " + wanted);
    }
}
