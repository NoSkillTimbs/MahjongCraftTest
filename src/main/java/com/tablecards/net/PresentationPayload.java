package com.tablecards.net;

import com.tablecards.TableCardsMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Live server events only. Never included in a board snapshot or replayed on joining. */
public record PresentationPayload(String json) implements CustomPayload {
    public static final Id<PresentationPayload> ID = new Id<>(Identifier.of(TableCardsMod.MOD_ID, "presentation"));
    public static final PacketCodec<RegistryByteBuf, PresentationPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.string(1 << 20), PresentationPayload::json, PresentationPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
