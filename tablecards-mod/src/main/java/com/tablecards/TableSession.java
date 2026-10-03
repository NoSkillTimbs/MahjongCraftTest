package com.tablecards;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tablecards.engine.CardGame;
import com.tablecards.engine.Decision;
import com.tablecards.engine.Section;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * One card game hosted at one mahjong table: two seats (players or a bot), deck choice, the game,
 * and the JSON view each player's screen shows. Seat 0 is the host.
 */
public final class TableSession {
    public enum State { WAITING, CHOOSING_DECKS, PLAYING, OVER }

    public static final String BOT_NAME = "Table Bot";
    private static final int LOG_LINES = 10;

    public final RegistryKey<World> dimension;
    public final BlockPos pos;
    public final GameType type;
    final UUID[] uuids = new UUID[2];
    final String[] names = new String[2];
    final boolean[] bot = new boolean[2];
    /** Chosen deck names, and their cards (captured when chosen, so a reload can't change them). */
    final String[] decks = new String[2];
    private final Object[] deckCards = new Object[2];
    /** The deck names offered at this table, fixed when deck choice starts. */
    private List<String> deckChoices = List.of();
    State state = State.WAITING;
    CardGame game;
    /** Bumped on every change; clicks on an older view are ignored. */
    int seq;
    long nextBotTick;
    long removeAtTick = -1;
    private final Random random = new Random();

    TableSession(RegistryKey<World> dimension, BlockPos pos, GameType type, UUID host, String hostName) {
        this.dimension = dimension;
        this.pos = pos;
        this.type = type;
        uuids[0] = host;
        names[0] = hostName;
    }

    int seatOf(UUID uuid) {
        for (int i = 0; i < 2; i++) {
            if (!bot[i] && uuid.equals(uuids[i])) {
                return i;
            }
        }
        return -1;
    }

    boolean isFull() {
        return uuids[1] != null || bot[1];
    }

    void join(UUID uuid, String name) {
        uuids[1] = uuid;
        names[1] = name;
        startDeckChoice();
    }

    void addBot() {
        bot[1] = true;
        names[1] = BOT_NAME;
        startDeckChoice();
        pickDeck(1, random.nextInt(deckChoices.size()));
    }

    private void startDeckChoice() {
        deckChoices = type.deckNames();
        state = State.CHOOSING_DECKS;
        seq++;
    }

    private boolean pickDeck(int seat, int option) {
        if (option < 0 || option >= deckChoices.size()) {
            return false;
        }
        Object cards = type.deck(deckChoices.get(option));
        if (cards == null) {
            return false;
        }
        decks[seat] = deckChoices.get(option);
        deckCards[seat] = cards;
        return true;
    }

    /** Starts the game once both decks are chosen. */
    private void maybeStart() {
        if (decks[0] == null || decks[1] == null) {
            return;
        }
        game = type.create(names.clone(), deckCards[0], deckCards[1], random.nextLong());
        state = State.PLAYING;
    }

    /**
     * Handles a click from {@code seat}. Returns true if anything changed.
     * Option {@code -1} concedes (or closes the table before the game starts).
     */
    boolean choose(int seat, int clickSeq, int option) {
        if (clickSeq != seq || state == State.OVER) {
            return false;
        }
        boolean changed = switch (state) {
            case WAITING -> {
                if (seat != 0) yield false;
                if (option == 0) {
                    addBot();
                    yield true;
                }
                if (option == 1 || option == -1) {
                    state = State.OVER;
                    yield true;
                }
                yield false;
            }
            case CHOOSING_DECKS -> {
                if (option == -1) {
                    state = State.OVER;
                    yield true;
                }
                if (decks[seat] != null || !pickDeck(seat, option)) yield false;
                maybeStart();
                yield true;
            }
            case PLAYING -> {
                if (option == -1) {
                    game.concede(seat);
                    yield true;
                }
                yield game.choose(seat, option);
            }
            case OVER -> false;
        };
        if (changed) {
            seq++;
            if (state == State.PLAYING && game.isOver()) {
                state = State.OVER;
            }
        }
        return changed;
    }

    /** Lets the bot answer if the game is waiting on it. Returns true if it moved. */
    boolean botStep() {
        if (state != State.PLAYING) {
            return false;
        }
        Decision d = game.pending();
        if (d == null || !bot[d.player()]) {
            return false;
        }
        game.choose(d.player(), game.bot().choose(game, d));
        seq++;
        if (game.isOver()) {
            state = State.OVER;
        }
        return true;
    }

    /** Ends the game because {@code seat} left. */
    void forfeit(int seat) {
        if (state == State.PLAYING) {
            game.concede(seat);
        }
        state = State.OVER;
        seq++;
    }

    /** Ends the session without a winner (e.g. the table was broken). */
    void cancel() {
        state = State.OVER;
        seq++;
    }

    boolean isWaitingOnHuman(int seat) {
        return switch (state) {
            case WAITING -> seat == 0 && !isFull();
            case CHOOSING_DECKS -> decks[seat] == null;
            case PLAYING -> game.pending() != null && game.pending().player() == seat;
            case OVER -> false;
        };
    }

    /** Text for chat when the game ends. */
    String resultText() {
        if (game == null || !game.isOver()) {
            return "The " + type.title + " table was closed.";
        }
        return game.endReason();
    }

    /** The view for {@code seat}'s screen. */
    String viewJson(int seat) {
        JsonObject o = new JsonObject();
        o.addProperty("title", type.title + ": " + names[0] + " vs " + (names[1] == null ? "?" : names[1]));
        o.addProperty("seq", seq);
        JsonArray sections = new JsonArray();
        JsonArray options = new JsonArray();
        JsonArray log = new JsonArray();
        String prompt;
        boolean yourTurn = false;
        switch (state) {
            case WAITING -> {
                sections.add(section("Waiting for an opponent", List.of(
                        "Another player can right-click this table with a " + type.title + " deck to join.",
                        "Or play against a bot (you can also sneak + right-click with your deck).")));
                if (seat == 0) {
                    prompt = "Waiting for an opponent";
                    options.add("Play against a bot");
                    options.add("Close the table");
                    yourTurn = true;
                } else {
                    prompt = "Waiting";
                }
            }
            case CHOOSING_DECKS -> {
                sections.add(section("Choose a deck", List.of(
                        "Each player picks one of the starter decks.",
                        names[0] + ": " + (decks[0] == null ? "choosing..." : "ready"),
                        names[1] + ": " + (decks[1] == null ? "choosing..." : "ready"))));
                if (decks[seat] == null) {
                    prompt = "Choose your deck";
                    deckChoices.forEach(options::add);
                    yourTurn = true;
                } else {
                    prompt = "Waiting for " + names[1 - seat] + " to choose a deck";
                }
            }
            case PLAYING, OVER -> {
                if (game != null) {
                    for (Section s : game.view(seat)) {
                        sections.add(section(s.title(), s.lines()));
                    }
                    List<String> lines = game.log();
                    lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size()).forEach(log::add);
                }
                Decision d = game == null ? null : game.pending();
                if (state == State.OVER || d == null) {
                    prompt = resultText();
                } else if (d.player() == seat) {
                    prompt = d.prompt();
                    d.labels().forEach(options::add);
                    yourTurn = true;
                } else {
                    prompt = "Waiting for " + names[d.player()] + "...";
                }
            }
            default -> prompt = "";
        }
        o.add("sections", sections);
        o.add("options", options);
        o.add("log", log);
        o.addProperty("prompt", prompt);
        o.addProperty("yourTurn", yourTurn);
        o.addProperty("over", state == State.OVER);
        o.addProperty("canConcede", state == State.PLAYING);
        return o.toString();
    }

    private static JsonObject section(String title, List<String> lines) {
        JsonObject s = new JsonObject();
        s.addProperty("title", title);
        JsonArray arr = new JsonArray();
        lines.forEach(arr::add);
        s.add("lines", arr);
        return s;
    }
}
