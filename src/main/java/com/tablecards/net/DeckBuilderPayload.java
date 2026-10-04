package com.tablecards.net;

import com.tablecards.TableCardsMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Paged public catalog requests and bounded deck submissions. No filesystem paths cross the wire. */
public record DeckBuilderPayload(String json) implements CustomPayload {
    public static final Id<DeckBuilderPayload> ID = new Id<>(Identifier.of(TableCardsMod.MOD_ID, "deck_builder"));
    public static final PacketCodec<RegistryByteBuf, DeckBuilderPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.string(262144), DeckBuilderPayload::json, DeckBuilderPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
