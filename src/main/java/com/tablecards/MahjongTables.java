package com.tablecards;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Talks to MahjongCraft's table through Minecraft's own data (block id, block state, saved NBT),
 * so this mod needs no compile-time link to MahjongCraft.
 */
public final class MahjongTables {
    public static final Identifier TABLE = Identifier.of("mahjongcraft", "mahjong_table");
    private static final String PART = "mahjong_table_part";

    private MahjongTables() {
    }

    public static boolean isTable(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).equals(TABLE);
    }

    /**
     * The table is a 3x3x2 block structure; games are keyed by its bottom-centre block.
     * Part names look like "bottom_center" or "top_northeast".
     */
    public static BlockPos center(BlockState state, BlockPos pos) {
        String part = partName(state);
        if (part == null) {
            return pos;
        }
        int x = 0;
        int y = 0;
        int z = 0;
        if (part.startsWith("top")) y = -1;
        if (part.contains("east")) x = -1;
        if (part.contains("west")) x = 1;
        if (part.contains("south")) z = -1;
        if (part.contains("north")) z = 1;
        return pos.add(x, y, z);
    }

    private static String partName(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(PART)) {
                return valueName(state, property);
            }
        }
        return null;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.name(state.get(property)).toLowerCase();
    }

    /** True if a mahjong game is running or players are seated at the table. */
    public static boolean inUseForMahjong(World world, BlockPos center) {
        BlockEntity be = world.getBlockEntity(center);
        if (be == null) {
            return false;
        }
        NbtCompound nbt = be.createNbt(world.getRegistryManager());
        if (nbt.getBoolean("Playing")) {
            return true;
        }
        for (int i = 0; i < 4; i++) {
            if (!nbt.getString("PlayerStringUUID" + i).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
