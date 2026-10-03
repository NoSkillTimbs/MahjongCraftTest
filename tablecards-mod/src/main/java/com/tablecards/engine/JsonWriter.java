package com.tablecards.engine;

import java.util.List;
import java.util.Map;

/** Writes Maps, Lists, Strings, Numbers and Booleans as JSON (the inverse of {@link Json}). */
public final class JsonWriter {
    private JsonWriter() {
    }

    public static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String s) {
            string(b, s);
        } else if (v instanceof Number || v instanceof Boolean) {
            if (v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
                b.append(d.longValue());
            } else {
                b.append(v);
            }
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                string(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List<?> l) {
            b.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) b.append(',');
                write(b, l.get(i));
            }
            b.append(']');
        } else {
            string(b, v.toString());
        }
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
