package doublemoon.mahjongcraft.client.render

import doublemoon.mahjongcraft.entity.MahjongBotEntity
import doublemoon.mahjongcraft.id
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.render.Frustum
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.entity.EntityRenderer
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.RotationAxis

/**
 * Draws a mahjong bot with [MahjongBotModel] (the R Colo character)
 * instead of the original giant mahjong tile.
 */
@Environment(EnvType.CLIENT)
class MahjongBotEntityRenderer(
    context: EntityRendererFactory.Context
) : EntityRenderer<MahjongBotEntity>(context) {

    private val model = MahjongBotModel(context.getPart(MahjongBotModel.LAYER))

    init {
        shadowRadius = 0.3f
    }

    override fun shouldRender(entity: MahjongBotEntity, frustum: Frustum, x: Double, y: Double, z: Double): Boolean {
        return !entity.isInvisible && super.shouldRender(entity, frustum, x, y, z)
    }

    override fun render(
        entity: MahjongBotEntity,
        yaw: Float,
        tickDelta: Float,
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider,
        light: Int
    ) {
        with(matrices) {
            push()
            // Same transform vanilla uses for living-entity models: face the entity's yaw,
            // flip the model (models are authored upside down) and stand it on the ground.
            multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - entity.yaw))
            scale(-MODEL_SCALE, -MODEL_SCALE, MODEL_SCALE)
            translate(0.0, -1.501, 0.0)
            model.setAngles(entity.age + tickDelta)
            model.render(
                this,
                vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE)),
                light,
                OverlayTexture.DEFAULT_UV
            )
            pop()
        }
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light) // name tag
    }

    override fun getTexture(entity: MahjongBotEntity): Identifier = TEXTURE

    companion object {
        val TEXTURE: Identifier = id("textures/entity/mahjong_bot.png")

        /**
         * The model is about 2.9 blocks tall with its hat at full size.
         * Half size keeps the body about the bot's 1-block hitbox and the hat under the name tag.
         */
        const val MODEL_SCALE = 0.5f
    }
}
