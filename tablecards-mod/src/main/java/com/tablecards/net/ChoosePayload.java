package com.tablecards.net;

import com.tablecards.TableCardsMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client to server: the player picked {@code option} on the view numbered {@code seq}
 * (stale clicks on an older view are ignored). {@link #CONCEDE} concedes the game.
 */
public record ChoosePayload(int seq, int option) implements CustomPayload {
    public static final int CONCEDE = -1;
    public static final Id<ChoosePayload> ID = new Id<>(Identifier.of(TableCardsMod.MOD_ID, "choose"));
    public static final PacketCodec<RegistryByteBuf, ChoosePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, ChoosePayload::seq,
            PacketCodecs.VAR_INT, ChoosePayload::option,
            ChoosePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
