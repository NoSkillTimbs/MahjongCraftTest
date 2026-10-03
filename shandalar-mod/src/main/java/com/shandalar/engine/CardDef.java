package com.shandalar.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable card definition, loaded from JSON. One instance is shared by every copy. */
public final class CardDef {
    public final String name;
    public final ManaCost cost;
    public final CardType type;
    public final Color landColor;
    public final int power;
    public final int toughness;
    public final Set<Keyword> keywords;
    public final List<Effect> effects;
    public final TargetKind target;

    @SuppressWarnings("unchecked")
    public CardDef(Map<String, Object> m) {
        this.name = (String) m.get("name");
        this.type = CardType.valueOf((String) m.get("type"));
        this.cost = ManaCost.parse((String) m.get("cost"));
        this.landColor = m.containsKey("color") ? Color.valueOf((String) m.get("color")) : null;
        this.power = intOf(m.get("power"));
        this.toughness = intOf(m.get("toughness"));

        EnumSet<Keyword> kw = EnumSet.noneOf(Keyword.class);
        if (m.get("keywords") != null) {
            for (Object k : (List<Object>) m.get("keywords")) {
                kw.add(Keyword.valueOf((String) k));
            }
        }
        this.keywords = Collections.unmodifiableSet(kw);

        List<Effect> fx = new ArrayList<>();
        if (m.get("effects") != null) {
            for (Object o : (List<Object>) m.get("effects")) {
                Map<String, Object> e = (Map<String, Object>) o;
                fx.add(new Effect(Effect.Kind.valueOf((String) e.get("kind")), intOf(e.get("amount"))));
            }
        }
        this.effects = Collections.unmodifiableList(fx);
        this.target = m.containsKey("target") ? TargetKind.valueOf((String) m.get("target")) : TargetKind.NONE;

        if (type == CardType.LAND && landColor == null) {
            throw new IllegalArgumentException("Land without color: " + name);
        }
    }

    private static int intOf(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    public boolean isSpell() {
        return type == CardType.INSTANT || type == CardType.SORCERY;
    }

    @Override
    public String toString() {
        return name;
    }
}
