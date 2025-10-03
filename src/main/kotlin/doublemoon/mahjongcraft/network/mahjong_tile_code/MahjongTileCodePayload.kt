package doublemoon.mahjongcraft.network.mahjong_tile_code

import doublemoon.mahjongcraft.id
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

data class MahjongTileCodePayload(
    val id: Int,
    val code: Int = 0,
) : CustomPayload {
    constructor(byteBuf: RegistryByteBuf) : this(
        id = byteBuf.readVarInt(),
        code = byteBuf.readVarInt()
    )

    fun writeByteBuf(byteBuf: RegistryByteBuf) {
        with(byteBuf) {
            writeVarInt(id)
            writeVarInt(code)
        }
    }

    override fun getId(): CustomPayload.Id<MahjongTileCodePayload> = ID

    companion object {
        val ID = CustomPayload.Id<MahjongTileCodePayload>(id("mahjong_tile_code_payload"))
        val CODEC: PacketCodec<RegistryByteBuf, MahjongTileCodePayload> =
            PacketCodec.of<RegistryByteBuf, MahjongTileCodePayload>(
                { payload: MahjongTileCodePayload, buf: RegistryByteBuf -> payload.writeByteBuf(buf) },
                { buf: RegistryByteBuf -> MahjongTileCodePayload(buf) }
            )
    }
}