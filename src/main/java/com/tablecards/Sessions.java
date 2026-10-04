package com.tablecards;

import com.tablecards.net.ChoosePayload;
import com.tablecards.net.ViewPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** All card games running at mahjong tables, and the event handlers that drive them. */
public final class Sessions {
    /** Ticks between bot moves, so people can follow what the bot does. */
    private static final int BOT_DELAY_TICKS = 15;
    /** How long a finished game's result stays on screen before the table is freed. */
    private static final int OVER_LINGER_TICKS = 20 * 30;

    private record Key(RegistryKey<World> dimension, BlockPos pos) {
    }

    private static final Map<Key, TableSession> SESSIONS = new HashMap<>();

    /** Set by the client: whether this player has a game going at the table at that position. */
    public static volatile java.util.function.BiPredicate<BlockState, BlockPos> clientPlaysAt = (state, pos) -> false;

    private Sessions() {
    }

    /** The unfinished game this player sits at (a finished one lingering on screen doesn't count). */
    private static TableSession sessionOf(UUID uuid) {
        for (TableSession s : SESSIONS.values()) {
            if (s.state != TableSession.State.OVER && s.seatOf(uuid) >= 0) {
                return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ table clicks

    public static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        BlockState state = world.getBlockState(hit.getBlockPos());
        if (!MahjongTables.isTable(state) || player.isSpectator()) {
            return ActionResult.PASS;
        }
        ItemStack stack = player.getStackInHand(hand);
        DeckItem deck = stack.getItem() instanceof DeckItem d ? d : null;
        if (world.isClient) {
            // let the server decide; a deck click (or a click on the table you're playing at) goes to the server
            return deck != null || stack.getItem() instanceof doublemoon.mahjongcraft.item.MahjongTile
                    || clientPlaysAt.test(state, hit.getBlockPos()) ? ActionResult.SUCCESS : ActionResult.PASS;
        }
        ServerPlayerEntity sp = (ServerPlayerEntity) player;
        MinecraftServer server = sp.getServer();
        BlockPos center = MahjongTables.center(state, hit.getBlockPos());
        Key key = new Key(world.getRegistryKey(), center);
        TableSession session = SESSIONS.get(key);
        UUID uuid = sp.getUuid();

        if (session != null && session.state != TableSession.State.OVER) {
            int seat = session.seatOf(uuid);
            if (seat >= 0) {
                if (deck != null && sp.isSneaking() && seat == 0 && session.state == TableSession.State.WAITING) {
                    session.addBot();
                }
                push(server, session, uuid);
                return ActionResult.SUCCESS;
            }
            if (deck != null && deck.type == session.type && session.state == TableSession.State.WAITING && !session.isFull()) {
                if (sessionOf(uuid) != null) {
                    sp.sendMessage(Text.translatable("message.tablecards.already_playing").formatted(Formatting.YELLOW), true);
                    return ActionResult.FAIL;
                }
                session.join(uuid, sp.getName().getString());
                announce(server, session, Text.translatable("message.tablecards.joined", sp.getName().getString(), session.type.title));
                push(server, session, uuid);
                return ActionResult.SUCCESS;
            }
            sp.sendMessage(Text.translatable("message.tablecards.table_busy", session.type.title).formatted(Formatting.YELLOW), true);
            return ActionResult.FAIL;
        }

        if (deck == null) {
            if (stack.getItem() instanceof doublemoon.mahjongcraft.item.MahjongTile
                    && state.getBlock() instanceof doublemoon.mahjongcraft.block.MahjongTable table)
                return table.useWithTile(state,world,hit.getBlockPos(),player,hit);
            return ActionResult.PASS; // nothing of ours here: normal mahjong table behaviour
        }
        if (MahjongTables.inUseForMahjong(world, center)) {
            sp.sendMessage(Text.translatable("message.tablecards.mahjong_in_use").formatted(Formatting.YELLOW), true);
            return ActionResult.FAIL;
        }
        if (sessionOf(uuid) != null) {
            sp.sendMessage(Text.translatable("message.tablecards.already_playing").formatted(Formatting.YELLOW), true);
            return ActionResult.FAIL;
        }
        if (deck.type.deckNames().isEmpty()) {
            sp.sendMessage(Text.translatable(Commands.importing() ? "message.tablecards.importing" : "message.tablecards.no_cards", deck.type.title)
                    .formatted(Formatting.YELLOW), false);
            return ActionResult.FAIL;
        }
        TableSession created = new TableSession(world.getRegistryKey(), center, deck.type, uuid, sp.getName().getString());
        created.dir0 = TableWorld.sideOf(center, sp.getPos());
        TableSession old = SESSIONS.get(key);
        if (old != null) {
            TableWorld.close(server, old); // the previous game's result was still showing
        }
        SESSIONS.put(key, created);
        if (sp.isSneaking()) {
            created.addBot();
        } else {
            sp.sendMessage(Text.translatable("message.tablecards.opened", deck.type.title), false);
        }
        push(server, created, uuid);
        return ActionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ clicks on the game screen

    public static void onChoose(ServerPlayerEntity player, ChoosePayload payload) {
        MinecraftServer server = player.getServer();
        TableSession session = sessionOf(player.getUuid());
        if (session == null) {
            return;
        }
        int seat = session.seatOf(player.getUuid());
        boolean changed;
        try {
            changed = session.choose(seat, payload.seq(), payload.option());
        } catch (RuntimeException e) {
            fail(server, session, e);
            return;
        }
        if (changed) {
            afterChange(server, session);
        } else {
            // the click was for an older screen: send the current one so the buttons work again
            ServerPlayNetworking.send(player, new ViewPayload(session.viewJson(seat), false));
        }
    }

    /** A rules bug shouldn't take the server down: stop that game and tell the players. */
    private static void fail(MinecraftServer server, TableSession s, RuntimeException e) {
        TableCardsMod.LOGGER.error("Table Cards game at {} stopped by an error", s.pos, e);
        announce(server, s, Text.literal("The " + s.type.title + " game hit an error and was stopped: " + e)
                .formatted(Formatting.RED));
        s.cancel();
        afterChange(server, s);
    }

    // ------------------------------------------------------------------ ticking, leaving, broken tables

    public static void tick(MinecraftServer server) {
        long now = server.getTicks();
        List<Key> remove = new ArrayList<>();
        for (Map.Entry<Key, TableSession> e : SESSIONS.entrySet()) {
            TableSession s = e.getValue();
            if (now % 20 == 0) {
                TableWorld.pushWatchers(server, s); // people walking up to the table
            }
            if (s.state == TableSession.State.OVER) {
                if (s.removeAtTick < 0) {
                    s.removeAtTick = now + OVER_LINGER_TICKS;
                } else if (now >= s.removeAtTick) {
                    remove.add(e.getKey());
                    TableWorld.close(server, s);
                }
                continue;
            }
            ServerWorld world = server.getWorld(s.dimension);
            boolean loaded = world != null && world.getChunkManager().isChunkLoaded(s.pos.getX() >> 4, s.pos.getZ() >> 4);
            if (world == null || (loaded && !MahjongTables.isTable(world.getBlockState(s.pos)))) {
                s.cancel();
                afterChange(server, s); // tells the players; the session is removed after the linger time
                continue;
            }
            try {
                if (now >= s.nextBotTick && s.botStep()) {
                    s.nextBotTick = now + BOT_DELAY_TICKS;
                    afterChange(server, s);
                }
            } catch (RuntimeException ex) {
                fail(server, s, ex);
            }
        }
        remove.forEach(SESSIONS::remove);
    }

    static boolean useBuiltDeck(ServerPlayerEntity player, GameType type, Object cards, String name) {
        TableSession s = sessionOf(player.getUuid());
        if (s == null || s.type != type || !s.dimension.equals(player.getWorld().getRegistryKey())
                || player.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(s.pos)) > 64) return false;
        if (!s.useBuiltDeck(s.seatOf(player.getUuid()),cards,name)) return false;
        afterChange(player.getServer(),s);
        return true;
    }

    public static void onDisconnect(ServerPlayerEntity player) {
        DeckBuilderService.forget(player.getUuid());
        TableSession s = sessionOf(player.getUuid());
        if (s == null || s.state == TableSession.State.OVER) {
            return;
        }
        int seat = s.seatOf(player.getUuid());
        boolean started = s.state == TableSession.State.PLAYING;
        s.forfeit(seat);
        afterChange(player.getServer(), s);
        if (!started) {
            SESSIONS.values().remove(s);
            TableWorld.close(player.getServer(), s);
        }
    }

    public static void clear() {
        SESSIONS.values().forEach(TableWorld::removeBot);
        SESSIONS.clear();
    }

    /** Whether this entity is the bot figure of a game that's still on. */
    public static boolean isActiveBot(net.minecraft.entity.Entity e) {
        for (TableSession s : SESSIONS.values()) {
            if (s.botEntity == e) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ output

    /** Sends everyone their new view; announces the result once the game ends. */
    private static void afterChange(MinecraftServer server, TableSession s) {
        if (s.state == TableSession.State.OVER && s.removeAtTick < 0) {
            announce(server, s, Text.literal(s.resultText()).formatted(Formatting.GOLD));
            s.removeAtTick = server.getTicks() + OVER_LINGER_TICKS;
        }
        // Send live cosmetics only to existing recipients, before the resulting board snapshot.
        // New watchers receive only the snapshot from pushWatchers, never these drained events.
        if (s.game != null) {
            var events = s.game.drainPresentation();
            if (!events.isEmpty()) {
                String json = com.tablecards.engine.JsonWriter.write(java.util.Map.of(
                        "table", List.of(s.pos.getX(), s.pos.getY(), s.pos.getZ()),
                        "game", s.type == GameType.YUGIOH ? "ygo" : "ptcg", "events", events));
                var payload = new com.tablecards.net.PresentationPayload(json);
                var recipients = new java.util.HashSet<UUID>(s.watchers.keySet());
                for (UUID id : s.uuids) if (id != null) recipients.add(id);
                for (UUID id : recipients) {
                    var player = server.getPlayerManager().getPlayer(id);
                    if (player != null && player.getWorld().getRegistryKey().equals(s.dimension)
                            && (s.seatOf(id) >= 0 || player.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(s.pos)) <= 48 * 48))
                        ServerPlayNetworking.send(player, payload);
                }
            }
        }
        push(server, s, null);
    }

    /** Sends each human at the table their view; {@code openFor} gets their screen opened. */
    private static void push(MinecraftServer server, TableSession s, UUID openFor) {
        for (int seat = 0; seat < 2; seat++) {
            if (s.bot[seat] || s.uuids[seat] == null) {
                continue;
            }
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(s.uuids[seat]);
            if (p == null) {
                continue;
            }
            boolean open = s.uuids[seat].equals(openFor) || s.isWaitingOnHuman(seat);
            ServerPlayNetworking.send(p, new ViewPayload(s.viewJson(seat), open));
        }
        TableWorld.stage(server, s);
        TableWorld.pushWatchers(server, s);
    }

    private static void announce(MinecraftServer server, TableSession s, Text text) {
        for (int seat = 0; seat < 2; seat++) {
            if (s.bot[seat] || s.uuids[seat] == null) {
                continue;
            }
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(s.uuids[seat]);
            if (p != null) {
                p.sendMessage(text, false);
            }
        }
    }
}
