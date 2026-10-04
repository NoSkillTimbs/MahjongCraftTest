package com.tablecards.engine;

import com.tablecards.engine.ptcg.PtcgCard;
import com.tablecards.engine.ptcg.PtcgLibrary;
import com.tablecards.engine.ygo.YgoCard;
import com.tablecards.engine.ygo.YgoLibrary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads players' own deck lists: .ydk files for Yu-Gi-Oh! and Pokemon TCG Live / PTCGO text
 * lists for Pokemon. A list becomes a playable deck only if every card in it was imported and the
 * deck is legal here; otherwise the problems are reported.
 */
public final class DeckLists {
    private DeckLists() {
    }

    /** A parsed deck list: the cards, or what's wrong with it. */
    public record Parsed<T>(List<T> deck, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    // ------------------------------------------------------------------ Yu-Gi-Oh! (.ydk)

    /**
     * .ydk: "#main" then one passcode per line, then "#extra" and "!side". The Main and Extra Deck
     * are used; Side Deck lines are ignored.
     */
    public static Parsed<YgoCard> ydk(String text, YgoLibrary lib) {
        YdkDocument doc = readYdk(text, lib);
        List<YgoCard> deck = new ArrayList<>(doc.main());
        deck.addAll(doc.extra());
        List<String> problems = new ArrayList<>(doc.problems().stream().filter(p -> !p.startsWith("Side Deck:")).toList());
        if (problems.isEmpty()) {
            String validation = YgoLibrary.validate(deck);
            if (validation != null) problems.add("Deck " + validation);
        }
        return new Parsed<>(deck, problems);
    }

    /** Lossless section separation for the editor; gameplay continues to ignore the Side Deck. */
    public record YdkDocument(List<YgoCard> main, List<YgoCard> extra, List<YgoCard> side, List<String> problems) {}

    public static YdkDocument readYdk(String text, YgoLibrary lib) {
        List<YgoCard> main = new ArrayList<>(), extra = new ArrayList<>(), side = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<YgoCard> section = main;
        int count = 0;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#created")) continue;
            if (line.equalsIgnoreCase("#main")) { section = main; continue; }
            if (line.equalsIgnoreCase("#extra")) { section = extra; continue; }
            if (line.equalsIgnoreCase("!side")) { section = side; continue; }
            if (line.startsWith("#")) continue;
            if (++count > 1000) { problems.add("Too many card entries"); break; }
            String code = line.replaceAll("^0+(?=\\d)", "");
            YgoCard card = lib.byCode.get(code);
            if (!code.matches("\\d{1,10}") || card == null) {
                problems.add((section == side ? "Side Deck: " : "") + "Unknown or unsupported passcode: " + line);
            } else {
                // Preserve old loaders which accepted Extra Deck cards in the Main section.
                (section == side ? side : card.isExtra() ? extra : main).add(card);
            }
        }
        return new YdkDocument(main, extra, side, problems);
    }

    // ------------------------------------------------------------------ Pokemon (PTCG Live / PTCGO)

    private static final Pattern PTCG_LINE = Pattern.compile("^\\*?\\s*(\\d+)\\s+(.+?)\\s+([A-Z0-9][A-Za-z0-9-]{1,7})\\s+([A-Za-z0-9]+)\\s*$");
    private static final Pattern PTCG_NAME_ONLY = Pattern.compile("^\\*?\\s*(\\d+)\\s+(.+?)\\s*$");
    private static final Map<String, String> ENERGY_SYMBOLS = Map.of("G", "grass", "R", "fire", "W", "water", "L", "lightning",
            "P", "psychic", "F", "fighting", "D", "darkness", "M", "metal", "Y", "fairy");

    /**
     * Lines like "4 Pikachu ex SVI 63" (PTCG Live) or "* 4 Pikachu ex SVI 63" (PTCGO), with
     * section headers ("Pokémon: 12", "##Trainer Cards - 30") ignored. Pokemon are matched by set
     * code and number; Trainers and basic Energy may also be matched by name.
     */
    public static Parsed<PtcgCard> ptcg(String text, PtcgLibrary lib) {
        Map<String, PtcgCard> bySetNumber = new HashMap<>();
        Map<String, List<PtcgCard>> byName = new HashMap<>();
        Map<String, PtcgCard> energyByType = new HashMap<>();
        for (PtcgCard c : lib.cards.values()) {
            if (!c.set.isEmpty()) {
                bySetNumber.put(c.set.toUpperCase(Locale.ROOT) + " " + c.number.toUpperCase(Locale.ROOT), c);
            }
            byName.computeIfAbsent(c.name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(c);
            if (c.kind == PtcgCard.Kind.ENERGY) {
                energyByType.putIfAbsent(c.type, c);
            }
        }
        List<PtcgCard> deck = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.matches("(?i)^(pok[eé]mon|trainer|energy|total cards).*:\\s*\\d+\\s*$")) {
                continue;
            }
            Matcher m = PTCG_LINE.matcher(line);
            Matcher n = PTCG_NAME_ONLY.matcher(line);
            int count;
            String name;
            PtcgCard card = null;
            if (m.matches()) {
                count = safeCount(m.group(1));
                name = m.group(2);
                card = bySetNumber.get(m.group(3).toUpperCase(Locale.ROOT) + " " + m.group(4).toUpperCase(Locale.ROOT));
            } else if (n.matches()) {
                count = safeCount(n.group(1));
                name = n.group(2);
            } else {
                problems.add("Can't read line: " + line);
                continue;
            }
            if (count < 1 || deck.size() + count > 1000) {
                problems.add("Invalid or excessive card count: " + line);
                continue;
            }
            if (card == null) {
                card = byName(name, byName, energyByType);
            }
            if (card == null) {
                List<PtcgCard> versions = byName.get(name.toLowerCase(Locale.ROOT));
                if (versions != null && versions.size() > 1 && !m.matches()) {
                    PtcgCard example = versions.get(versions.size() - 1);
                    problems.add(line + ": this card has versions that play differently, so add its set code and number (e.g. \""
                            + count + " " + example.name + " " + example.set + " " + example.number + "\")");
                } else {
                    problems.add(line + " is not a card this game can play exactly");
                }
                continue;
            }
            for (int i = 0; i < count; i++) {
                deck.add(card);
            }
        }
        if (problems.isEmpty()) {
            String v = PtcgLibrary.validate(deck);
            if (v != null) {
                problems.add("Deck " + v);
            }
        }
        return new Parsed<>(deck, problems);
    }

    private static int safeCount(String value) {
        try { int n = Integer.parseInt(value); return n > 0 && n <= 1000 ? n : -1; }
        catch (NumberFormatException e) { return -1; }
    }

    private static final Pattern ENERGY_LINE = Pattern.compile("(?i)^(?:basic\\s+)?(?:\\{([A-Z])\\}|([a-z]+))\\s+energy$");

    /** Name-only match: basic Energy, or a Trainer whose printings all play the same. */
    private static PtcgCard byName(String name, Map<String, List<PtcgCard>> byName, Map<String, PtcgCard> energyByType) {
        Matcher e = ENERGY_LINE.matcher(name.trim());
        if (e.matches()) {
            String type = e.group(1) != null ? ENERGY_SYMBOLS.get(e.group(1).toUpperCase(Locale.ROOT)) : e.group(2).toLowerCase(Locale.ROOT);
            return type == null ? null : energyByType.get(type);
        }
        List<PtcgCard> same = byName.get(name.toLowerCase(Locale.ROOT));
        if (same == null || same.isEmpty()) {
            return null;
        }
        PtcgCard first = same.get(0);
        if (first.kind == PtcgCard.Kind.ITEM || first.kind == PtcgCard.Kind.SUPPORTER) {
            for (PtcgCard c : same) {
                if (!c.effect.equals(first.effect) || c.value != first.value || c.kind != first.kind) {
                    return null; // different printings do different things: the set code is needed
                }
            }
            return first;
        }
        return same.size() == 1 ? first : null;
    }
}
