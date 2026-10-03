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

    private Sessions() {
    }

    private static TableSession sessionOf(UUID uuid) {
        for (TableSession s : SESSIONS.values()) {
            if (s.seatOf(uuid) >= 0) {
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
            // let the server decide; a deck click always goes to the server
            return deck != null ? ActionResult.SUCCESS : ActionResult.PASS;
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
        TableSession created = new TableSession(world.getRegistryKey(), center, deck.type, uuid, sp.getName().getString());
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
        TableSession session = sessionOf(player.getUuid());
        if (session == null) {
            return;
        }
        int seat = session.seatOf(player.getUuid());
        if (session.choose(seat, payload.seq(), payload.option())) {
            afterChange(player.getServer(), session);
        }
    }

    // ------------------------------------------------------------------ ticking, leaving, broken tables

    public static void tick(MinecraftServer server) {
        long now = server.getTicks();
        List<Key> remove = new ArrayList<>();
        for (Map.Entry<Key, TableSession> e : SESSIONS.entrySet()) {
            TableSession s = e.getValue();
            if (s.state == TableSession.State.OVER) {
                if (s.removeAtTick < 0) {
                    s.removeAtTick = now + OVER_LINGER_TICKS;
                } else if (now >= s.removeAtTick) {
                    remove.add(e.getKey());
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
            if (now >= s.nextBotTick && s.botStep()) {
                s.nextBotTick = now + BOT_DELAY_TICKS;
                afterChange(server, s);
            }
        }
        remove.forEach(SESSIONS::remove);
    }

    public static void onDisconnect(ServerPlayerEntity player) {
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
        }
    }

    public static void clear() {
        SESSIONS.clear();
    }

    // ------------------------------------------------------------------ output

    /** Sends everyone their new view; announces the result once the game ends. */
    private static void afterChange(MinecraftServer server, TableSession s) {
        if (s.state == TableSession.State.OVER && s.removeAtTick < 0) {
            announce(server, s, Text.literal(s.resultText()).formatted(Formatting.GOLD));
            s.removeAtTick = server.getTicks() + OVER_LINGER_TICKS;
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
