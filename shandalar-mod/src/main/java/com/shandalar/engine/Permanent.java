package com.shandalar.engine;

/** A card on the battlefield. */
public final class Permanent {
    public final CardDef def;
    public final Player owner;
    public boolean tapped;
    public boolean summoningSick = true;
    public int damage;
    public int pumpPower;
    public int pumpToughness;

    public Permanent(CardDef def, Player owner) {
        this.def = def;
        this.owner = owner;
    }

    public int power() {
        return def.power + pumpPower;
    }

    public int toughness() {
        return def.toughness + pumpToughness;
    }

    public boolean has(Keyword k) {
        return def.keywords.contains(k);
    }

    public boolean isCreature() {
        return def.type == CardType.CREATURE;
    }

    public boolean isLand() {
        return def.type == CardType.LAND;
    }

    @Override
    public String toString() {
        return isCreature() ? def.name + " " + power() + "/" + toughness() : def.name;
    }
}
