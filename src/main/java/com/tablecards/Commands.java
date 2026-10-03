package com.tablecards;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * /tablecards import &lt;yugioh|pokemon|all&gt;  (operators) download real cards into config/tablecards/imported
 * /tablecards reload                           (operators) reload imported cards and deck lists
 * /tablecards decks                            list the decks each game can use, and problems with deck lists
 */
public final class Commands {
    private static final AtomicBoolean IMPORTING = new AtomicBoolean();

    private Commands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("tablecards")
                .then(CommandManager.literal("import")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.argument("game", StringArgumentType.word())
                                .suggests((ctx, builder) -> CommandSource.suggestMatching(List.of("yugioh", "pokemon", "all"), builder))
                                .executes(Commands::runImport)))
                .then(CommandManager.literal("reload")
                        .requires(src -> src.hasPermissionLevel(2))
                        .executes(ctx -> {
                            CardPools.Snapshot s = CardPools.load(CardPools.dir());
                            ctx.getSource().sendFeedback(() -> Text.literal(summary(s)).formatted(Formatting.GREEN), true);
                            sendProblems(ctx.getSource(), s);
                            return 1;
                        }))
                .then(CommandManager.literal("decks").executes(ctx -> {
                    CardPools.Snapshot s = CardPools.get();
                    ServerCommandSource src = ctx.getSource();
                    src.sendFeedback(() -> Text.literal(summary(s)).formatted(Formatting.GOLD), false);
                    src.sendFeedback(() -> Text.literal("Yu-Gi-Oh! decks: " + String.join(", ", s.ygoDecks.keySet())), false);
                    src.sendFeedback(() -> Text.literal("Pokemon TCG decks: " + String.join(", ", s.ptcgDecks.keySet())), false);
                    sendProblems(src, s);
                    if (s.realYgoCards == 0 || s.realPtcgCards == 0) {
                        src.sendFeedback(() -> Text.literal("Real cards aren't imported yet: an operator can run /tablecards import all.")
                                .formatted(Formatting.GRAY), false);
                    }
                    return 1;
                })));
    }

    static String summary(CardPools.Snapshot s) {
        return "Table Cards: " + s.ygoDecks.size() + " Yu-Gi-Oh! decks (" + s.realYgoCards + " real cards), "
                + s.ptcgDecks.size() + " Pokemon TCG decks (" + s.realPtcgCards + " real cards).";
    }

    private static void sendProblems(ServerCommandSource src, CardPools.Snapshot s) {
        for (String p : s.problems) {
            src.sendFeedback(() -> Text.literal(p).formatted(Formatting.RED), false);
        }
    }

    private static int runImport(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        String game = StringArgumentType.getString(ctx, "game").toLowerCase();
        if (!List.of("yugioh", "pokemon", "all").contains(game)) {
            src.sendError(Text.literal("Use yugioh, pokemon or all."));
            return 0;
        }
        Path dir = CardPools.dir();
        if (dir == null) {
            src.sendError(Text.literal("The config folder isn't set up yet."));
            return 0;
        }
        if (!IMPORTING.compareAndSet(false, true)) {
            src.sendError(Text.literal("An import is already running."));
            return 0;
        }
        MinecraftServer server = src.getServer();
        src.sendFeedback(() -> Text.literal("Importing real cards in the background. Card data stays on this server, for private play. "
                + "Each player's game downloads the pictures of the cards it shows (they can turn that off on the game screen).").formatted(Formatting.GRAY), true);
        Thread t = new Thread(() -> {
            List<String> results = new ArrayList<>();
            try {
                if (!game.equals("pokemon")) {
                    results.add(step(server, src, () -> CardImport.importYugioh(dir, msg -> say(server, src, msg, Formatting.GRAY))));
                }
                if (!game.equals("yugioh")) {
                    results.add(step(server, src, () -> CardImport.importPokemon(dir, msg -> say(server, src, msg, Formatting.GRAY))));
                }
                CardPools.Snapshot s = CardPools.load(dir);
                for (String r : results) {
                    if (r != null) {
                        say(server, src, r, Formatting.GREEN);
                    }
                }
                say(server, src, summary(s), Formatting.GOLD);
                say(server, src, "Players can now pick the new decks at a table. Put your own deck lists in config/tablecards/decks "
                        + "(see the README there), then run /tablecards reload.", Formatting.GRAY);
                for (String p : s.problems) {
                    say(server, src, p, Formatting.RED);
                }
            } finally {
                IMPORTING.set(false);
            }
        }, "TableCards import");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    private interface Step {
        String run() throws Exception;
    }

    private static String step(MinecraftServer server, ServerCommandSource src, Step step) {
        try {
            return step.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Import interrupted.";
        } catch (Exception e) {
            TableCardsMod.LOGGER.warn("Card import failed", e);
            say(server, src, "Import failed: " + e.getMessage() + " (is the server online?)", Formatting.RED);
            return null;
        }
    }

    private static void say(MinecraftServer server, ServerCommandSource src, String msg, Formatting color) {
        server.execute(() -> src.sendFeedback(() -> Text.literal(msg).formatted(color), false));
    }
}
