package com.tablecards.net;

import com.tablecards.TableCardsMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server to client: the board as this player sees it, as JSON (see TableSession#viewJson).
 * {@code open} asks the client to open the game screen even if it is closed.
 */
public record ViewPayload(String json, boolean open) implements CustomPayload {
    public static final Id<ViewPayload> ID = new Id<>(Identifier.of(TableCardsMod.MOD_ID, "view"));
    public static final PacketCodec<RegistryByteBuf, ViewPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.string(1 << 20), ViewPayload::json,
            PacketCodecs.BOOL, ViewPayload::open,
            ViewPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
