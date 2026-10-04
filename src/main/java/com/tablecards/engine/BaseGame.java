package com.tablecards.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shared plumbing: a pending decision, a log, game-over bookkeeping, and the rule that after an
 * option runs, {@link #advance()} decides the next question unless the option asked one itself.
 */
public abstract class BaseGame implements CardGame {
    private final java.util.IdentityHashMap<Object, Integer> presentationIds = new java.util.IdentityHashMap<>();
    private final List<java.util.Map<String, Object>> presentation = new ArrayList<>();

    @Override public int presentationId(Object ref) {
        return ref == null ? -1 : presentationIds.computeIfAbsent(ref, ignored -> presentationIds.size() + 1);
    }

    /** Called only by semantic, accepted plays, never by generic movement or state serialization. */
    protected void reveal(Board.CardView face, Object ref) {
        if (face == null || face.faceDown) return;
        presentation.add(java.util.Map.of("kind", "reveal", "source", presentationId(ref),
                "card", ViewJson.snapshot(face)));
    }

    protected void interaction(Object source, Object target, boolean combat) {
        if (source == null || target == null || source == target) return;
        Board board = board(-1);
        var from = fieldAnchor(board, source);
        var to = fieldAnchor(board, target);
        if (from.isEmpty() || to.isEmpty()) return;
        presentation.add(java.util.Map.of("kind", combat ? "attack" : "target",
                "source", presentationId(source), "target", presentationId(target), "from", from, "to", to));
    }

    private java.util.Map<String, Object> fieldAnchor(Board board, Object ref) {
        List<Board.Side> sides = List.of(board.you, board.opp); // public board is seat 0 then seat 1
        for (int seat = 0; seat < sides.size(); seat++) for (Board.Zone zone : sides.get(seat).zones) {
            if (zone.pile) continue;
            for (int slot = 0; slot < zone.cards.size(); slot++)
                if (zone.cards.get(slot).refs.stream().anyMatch(r -> r == ref))
                    return java.util.Map.of("seat", seat, "zone", zone.id, "slot", slot);
        }
        return java.util.Map.of();
    }

    @Override public List<java.util.Map<String, Object>> drainPresentation() {
        var events = List.copyOf(presentation);
        presentation.clear();
        return events;
    }

    protected final Random rng;
    private Decision current;
    private final List<String> log = new ArrayList<>();
    private boolean over;
    private int winner = -1;
    private String endReason = "";
    private final String[] names;

    protected BaseGame(String[] playerNames, long seed) {
        this.names = playerNames.clone();
        this.rng = new Random(seed);
    }

    public String name(int player) {
        return names[player];
    }

    /** Asks {@code player} to pick one of {@code options}. */
    protected void ask(int player, String prompt, List<Option> options) {
        if (options.isEmpty()) {
            throw new IllegalStateException("No options for: " + prompt);
        }
        current = new Decision(player, prompt, List.copyOf(options));
    }

    /** Computes the next decision from the current state (called when an option didn't ask anything). */
    protected abstract void advance();

    @Override
    public Decision pending() {
        return over ? null : current;
    }

    @Override
    public boolean choose(int player, int option) {
        Decision d = pending();
        if (d == null || d.player() != player || option < 0 || option >= d.options().size()) {
            return false;
        }
        presentation.clear(); // events belong only to this accepted decision
        current = null;
        d.options().get(option).action().run();
        int guard = 0;
        while (!over && current == null) {
            advance();
            if (++guard > 1000) {
                throw new IllegalStateException("Game made no progress");
            }
        }
        return true;
    }

    /** Call from the constructor once the game is set up. */
    protected void start() {
        while (!over && current == null) {
            advance();
        }
    }

    protected void log(String line) {
        log.add(line);
        if (log.size() > 200) {
            log.remove(0);
        }
    }

    @Override
    public List<String> log() {
        return List.copyOf(log);
    }

    protected void win(int player, String reason) {
        if (over) {
            return;
        }
        over = true;
        winner = player;
        endReason = reason;
        current = null;
        log(reason);
    }

    @Override
    public void concede(int player) {
        win(1 - player, name(player) + " conceded.");
    }

    @Override
    public boolean isOver() {
        return over;
    }

    @Override
    public int winner() {
        return winner;
    }

    @Override
    public String endReason() {
        return endReason;
    }

    protected static <T> T pop(List<T> list) {
        return list.remove(list.size() - 1);
    }
}
