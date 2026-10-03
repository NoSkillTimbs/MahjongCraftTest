package com.shandalar.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON reader so the engine runs without any library. Produces Map, List, String,
 * Double, Boolean or null. (Inside Minecraft you could swap this for Gson.)
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != text.length()) {
            throw j.err("Trailing characters");
        }
        return v;
    }

    private RuntimeException err(String m) {
        return new IllegalArgumentException(m + " at index " + i);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw err("Unexpected end of input");
        }
        return s.charAt(i);
    }

    private Object value() {
        switch (peek()) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default: return number();
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, i)) {
            throw err("Expected " + word);
        }
        i += word.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            if (peek() != ':') {
                throw err("Expected ':'");
            }
            i++;
            ws();
            m.put(key, value());
            ws();
            char c = peek();
            i++;
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw err("Expected ',' or '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; // [
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            i++;
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw err("Expected ',' or ']'");
            }
        }
    }

    private String string() {
        if (peek() != '"') {
            throw err("Expected string");
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = peek();
            i++;
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = peek();
            i++;
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: sb.append(e);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (start == i) {
            throw err("Unexpected character '" + s.charAt(i) + "'");
        }
        return Double.valueOf(s.substring(start, i));
    }
}
