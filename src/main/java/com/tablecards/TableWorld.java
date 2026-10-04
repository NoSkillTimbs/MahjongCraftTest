package com.tablecards;

import com.tablecards.net.ViewPayload;
import doublemoon.mahjongcraft.entity.MahjongBotEntity;
import doublemoon.mahjongcraft.registry.EntityTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a card game does in the world around its table: the Mahjong bot figure sitting in for a
 * bot player, moving the players to their sides of the table, and sending the table to people
 * nearby so everyone can watch the cards being played.
 */
final class TableWorld {
    /** Command tag on bot figures, so leftovers (after a server stop) can be cleaned up. */
    static final String BOT_TAG = "tablecards_bot";
    private static final double WATCH_RANGE = 48;
    /** How far from the table's centre the players (and the bot) stand. */
    private static final double SEAT_DISTANCE = 2.05;

    private TableWorld() {
    }

    /** The side of the table (from its centre) a player is on. */
    static String sideOf(BlockPos center, Vec3d p) {
        double dx = p.x - (center.getX() + 0.5);
        double dz = p.z - (center.getZ() + 0.5);
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? "east" : "west";
        }
        return dz > 0 ? "south" : "north";
    }

    static double[] dirVec(String dir) {
        return switch (dir) {
            case "east" -> new double[]{1, 0};
            case "west" -> new double[]{-1, 0};
            case "north" -> new double[]{0, -1};
            default -> new double[]{0, 1};
        };
    }

    /** Where seat 0 or 1 stands, and the yaw that faces the table. */
    static Vec3d seatPos(TableSession s, int seat) {
        double[] d = dirVec(s.dir0);
        double k = seat == 0 ? SEAT_DISTANCE : -SEAT_DISTANCE;
        return new Vec3d(s.pos.getX() + 0.5 + d[0] * k, s.pos.getY(), s.pos.getZ() + 0.5 + d[1] * k);
    }

    static float seatYaw(TableSession s, int seat) {
        double[] d = dirVec(s.dir0);
        double fx = seat == 0 ? -d[0] : d[0];
        double fz = seat == 0 ? -d[1] : d[1];
        return (float) Math.toDegrees(Math.atan2(-fx, fz));
    }

    /** Puts the bot figure at the table and the players at their seats when the game starts. */
    static void stage(MinecraftServer server, TableSession s) {
        ServerWorld world = server.getWorld(s.dimension);
        if (world == null) {
            return;
        }
        if (s.bot[1] && s.botEntity == null && s.state != TableSession.State.OVER) {
            spawnBot(world, s);
        }
        if (s.state == TableSession.State.PLAYING && !s.seated) {
            s.seated = true;
            for (int seat = 0; seat < 2; seat++) {
                if (s.bot[seat] || s.uuids[seat] == null) {
                    continue;
                }
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(s.uuids[seat]);
                if (p == null || p.getWorld() != world) {
                    continue;
                }
                Vec3d at = seatPos(s, seat);
                BlockPos feet = BlockPos.ofFloored(at);
                boolean free = world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                        && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty();
                if (free) {
                    p.teleport(world, at.x, at.y, at.z, seatYaw(s, seat), 28f);
                }
            }
        }
    }

    private static void spawnBot(ServerWorld world, TableSession s) {
        MahjongBotEntity bot = EntityTypeRegistry.INSTANCE.getMahjongBot().create(world);
        if (bot == null) {
            return;
        }
        Vec3d at = seatPos(s, 1);
        float yaw = seatYaw(s, 1);
        bot.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0f);
        bot.setHeadYaw(yaw);
        bot.setCustomName(Text.literal(TableSession.BOT_NAME));
        bot.setCustomNameVisible(true);
        bot.setGameBlockPos(s.pos);
        bot.setSpawnedByGame(true);
        bot.addCommandTag(BOT_TAG);
        s.botEntity = bot;
        world.spawnEntity(bot);
        // Only the intended seat block and its immediate neighbours qualify; never scan the room.
        BlockPos feet = BlockPos.ofFloored(at);
        BlockPos stool = java.util.stream.Stream.of(feet, feet.north(), feet.south(), feet.east(), feet.west())
                .filter(p -> world.getBlockState(p).getBlock() instanceof doublemoon.mahjongcraft.block.MahjongStool)
                .filter(p -> Vec3d.ofCenter(p).squaredDistanceTo(at.x, at.y + 0.5, at.z) <= 1.25 * 1.25)
                .filter(p -> doublemoon.mahjongcraft.entity.SeatEntity.Companion.canSpawnAt(world, p, 2, true))
                .min(java.util.Comparator.comparingDouble(p -> Vec3d.ofCenter(p).squaredDistanceTo(at)))
                .orElse(null);
        if (stool != null) {
            // This bot's renderer has a standing pose, unlike the player's bent sitting pose.
            // Place its feet at the stool surface (MahjongStool.SHAPE is 10/16 high).
            doublemoon.mahjongcraft.entity.SeatEntity.Companion.spawnAt(world, stool, bot, 10.0 / 16.0, 0.8);
            float stoolYaw = (float) Math.toDegrees(Math.atan2(stool.getX() - s.pos.getX(), s.pos.getZ() - stool.getZ()));
            bot.setYaw(stoolYaw);
            bot.setHeadYaw(stoolYaw);
        }
    }

    /** Sends the table to people nearby who aren't playing (only what everyone may see). */
    static void pushWatchers(MinecraftServer server, TableSession s) {
        ServerWorld world = server.getWorld(s.dimension);
        if (world == null) {
            return;
        }
        Vec3d center = Vec3d.ofCenter(s.pos);
        String json = null;
        List<UUID> seen = new ArrayList<>();
        for (ServerPlayerEntity p : world.getPlayers()) {
            UUID id = p.getUuid();
            if (s.seatOf(id) >= 0) {
                continue;
            }
            Integer last = s.watchers.get(id);
            if (p.squaredDistanceTo(center) <= WATCH_RANGE * WATCH_RANGE) {
                seen.add(id);
                if (last == null || last != s.seq) {
                    if (json == null) {
                        json = s.viewJson(-1);
                    }
                    ServerPlayNetworking.send(p, new ViewPayload(json, false));
                    s.watchers.put(id, s.seq);
                }
            } else if (last != null) {
                ServerPlayNetworking.send(p, new ViewPayload(closedJson(s), false));
                s.watchers.remove(id);
            }
        }
        s.watchers.keySet().retainAll(seen);
    }

    /** The game is over and the table is free again: clear it for everyone and send the bot home. */
    static void close(MinecraftServer server, TableSession s) {
        String json = closedJson(s);
        List<UUID> told = new ArrayList<>(s.watchers.keySet());
        for (int seat = 0; seat < 2; seat++) {
            if (!s.bot[seat] && s.uuids[seat] != null) {
                told.add(s.uuids[seat]);
            }
        }
        for (UUID id : told) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p != null) {
                ServerPlayNetworking.send(p, new ViewPayload(json, false));
            }
        }
        s.watchers.clear();
        removeBot(s);
    }

    static void removeBot(TableSession s) {
        Entity bot = s.botEntity;
        s.botEntity = null;
        if (bot != null && !bot.isRemoved()) {
            bot.discard();
        }
    }

    private static String closedJson(TableSession s) {
        return "{\"state\":\"closed\",\"table\":[" + s.pos.getX() + "," + s.pos.getY() + "," + s.pos.getZ() + "]}";
    }
}
