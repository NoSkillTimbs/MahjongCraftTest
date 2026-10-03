package com.shandalar.engine;

import java.util.EnumMap;
import java.util.Map;

/** A mana cost such as "3WW": generic part plus one entry per colored symbol. */
public record ManaCost(int generic, Map<Color, Integer> colored) {

    public static ManaCost parse(String s) {
        int generic = 0;
        EnumMap<Color, Integer> colored = new EnumMap<>(Color.class);
        if (s != null) {
            StringBuilder digits = new StringBuilder();
            for (char c : s.toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                } else {
                    colored.merge(Color.valueOf(String.valueOf(c)), 1, Integer::sum);
                }
            }
            if (digits.length() > 0) {
                generic = Integer.parseInt(digits.toString());
            }
        }
        return new ManaCost(generic, colored);
    }

    /** Converted mana cost. */
    public int total() {
        return generic + colored.values().stream().mapToInt(Integer::intValue).sum();
    }
}
