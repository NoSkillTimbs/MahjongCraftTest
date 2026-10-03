package doublemoon.mahjongcraft.client.render

import doublemoon.mahjongcraft.id
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.model.ModelData
import net.minecraft.client.model.ModelPart
import net.minecraft.client.model.ModelPartBuilder
import net.minecraft.client.model.ModelTransform
import net.minecraft.client.model.TexturedModelData
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.entity.model.EntityModelLayer
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.MathHelper

/**
 * The model the mahjong bots are drawn with (the "R Colo" character),
 * ported from the NeoForge ColoLayers / ColoModel classes to Fabric (Yarn) names.
 * Texture: assets/mahjongcraft/textures/entity/mahjong_bot.png (256x256).
 */
@Environment(EnvType.CLIENT)
class MahjongBotModel(private val root: ModelPart) {
    private val head: ModelPart = root.getChild("head")
    private val stacheL: ModelPart = head.getChild("stache_l")
    private val stacheR: ModelPart = head.getChild("stache_r")
    private val braid1: ModelPart = head.getChild("braid1")
    private val braid2: ModelPart = braid1.getChild("braid2")
    private val braid3: ModelPart = braid2.getChild("braid3")
    private val armL: ModelPart
    private val armR: ModelPart

    init {
        braid1.xScale = 0.75f; braid1.yScale = 0.75f; braid1.zScale = 0.75f // the braid is 25% smaller
        val hat = head.getChild("hat")
        hat.xScale = 1.6f; hat.zScale = 1.6f; hat.yScale = 1.3f // wide brim
        val body = root.getChild("body")
        armL = body.getChild("arm_l")
        armR = body.getChild("arm_r")
    }

    /** Idle animation: the moustache and braid sway, the arms breathe. [age] is in ticks. */
    fun setAngles(age: Float) {
        val sway = MathHelper.sin(age * 0.08f) * 0.04f
        stacheL.pitch = sway
        stacheR.pitch = -sway
        stacheL.roll = MathHelper.sin(age * 0.1f) * 0.03f
        stacheR.roll = -MathHelper.sin(age * 0.1f) * 0.03f
        braid1.pitch = braid1.defaultTransform.pitch + MathHelper.sin(age * 0.07f) * 0.06f
        braid2.pitch = braid2.defaultTransform.pitch + MathHelper.sin(age * 0.07f - 0.7f) * 0.08f
        braid3.pitch = braid3.defaultTransform.pitch + MathHelper.sin(age * 0.07f - 1.4f) * 0.1f
        braid1.roll = MathHelper.sin(age * 0.05f) * 0.05f
        armR.pitch = MathHelper.sin(age * 0.09f) * 0.05f
        armL.pitch = MathHelper.sin(age * 0.09f + 1f) * 0.05f
    }

    fun render(matrices: MatrixStack, vertices: VertexConsumer, light: Int, overlay: Int) {
        root.render(matrices, vertices, light, overlay)
    }

    companion object {
        val LAYER = EntityModelLayer(id("mahjong_bot"), "main")

        /** Geometry and UVs copied one-to-one from ColoLayers.rColo(). */
        fun texturedModelData(): TexturedModelData {
            val data = ModelData()
            val root = data.root
            val body = root.addChild(
                "body",
                ModelPartBuilder.create().uv(68, 0).cuboid(-8f, 0f, -5f, 16f, 15f, 10f),
                ModelTransform.pivot(0f, 3f, 0f)
            )
            val head = root.addChild(
                "head",
                ModelPartBuilder.create()
                    .uv(0, 0).cuboid(-9f, -15f, -8f, 18f, 15f, 16f)
                    .uv(74, 51).cuboid(-8.5f, -12f, -9f, 17f, 7f, 1f)
                    .uv(194, 51).cuboid(-6f, -4.5f, -9f, 12f, 2f, 1f)
                    .uv(186, 51).cuboid(-3f, -2.5f, -8.6f, 3f, 3f, 1f)
                    .uv(186, 51).cuboid(0f, -2.5f, -8.6f, 3f, 3f, 1f)
                    .uv(150, 51).cuboid(-1.5f, 0.5f, -8.3f, 3f, 5f, 1f),
                ModelTransform.pivot(0f, 3f, 0f)
            )
            head.addChild(
                "hat",
                ModelPartBuilder.create()
                    .uv(120, 0).cuboid(-11f, -1f, -11f, 22f, 1f, 22f)
                    .uv(0, 31).cuboid(-9.5f, -2f, -9.5f, 19f, 1f, 19f)
                    .uv(76, 31).cuboid(-8f, -3f, -8f, 16f, 1f, 16f)
                    .uv(144, 31).cuboid(-6.5f, -4f, -6.5f, 13f, 1f, 13f)
                    .uv(0, 51).cuboid(-5f, -5f, -5f, 10f, 1f, 10f)
                    .uv(110, 51).cuboid(-3.5f, -6f, -3.5f, 7f, 1f, 7f)
                    .uv(158, 51).cuboid(-2f, -7f, -2f, 4f, 1f, 4f)
                    .uv(220, 51).cuboid(-1f, -8f, -1f, 2f, 1f, 2f),
                ModelTransform.pivot(0f, -15f, 0f)
            )
            head.addChild(
                "stache_l",
                ModelPartBuilder.create().uv(140, 31).cuboid(-0.5f, 0f, -0.5f, 1f, 15f, 1f),
                ModelTransform.pivot(6f, -4.5f, -9.1f)
            )
            head.addChild(
                "stache_r",
                ModelPartBuilder.create().uv(140, 31).cuboid(-0.5f, 0f, -0.5f, 1f, 15f, 1f),
                ModelTransform.pivot(-6f, -4.5f, -9.1f)
            )
            val braid1 = head.addChild(
                "braid1",
                ModelPartBuilder.create().uv(138, 51).cuboid(-1.5f, 0f, -1.5f, 3f, 5f, 3f),
                ModelTransform.of(0f, -4f, 8f, 0.5f, 0f, 0f)
            )
            val braid2 = braid1.addChild(
                "braid2",
                ModelPartBuilder.create().uv(138, 51).cuboid(-1.5f, 0f, -1.5f, 3f, 5f, 3f),
                ModelTransform.of(0f, 5f, 0f, -0.6f, 0f, 0f)
            )
            braid2.addChild(
                "braid3",
                ModelPartBuilder.create()
                    .uv(174, 51).cuboid(-1.5f, 0f, -1.5f, 3f, 2f, 3f)
                    .uv(58, 51).cuboid(-2f, 1.5f, -2f, 4f, 5f, 4f),
                ModelTransform.of(0f, 5f, 0f, -0.15f, 0f, 0f)
            )
            root.addChild(
                "leg_l",
                ModelPartBuilder.create().uv(218, 31).cuboid(-3f, 0f, -3.5f, 6f, 6f, 7f),
                ModelTransform.pivot(3.3f, 18f, 0f)
            )
            root.addChild(
                "leg_r",
                ModelPartBuilder.create().uv(218, 31).cuboid(-3f, 0f, -3.5f, 6f, 6f, 7f),
                ModelTransform.pivot(-3.3f, 18f, 0f)
            )
            body.addChild(
                "arm_l",
                ModelPartBuilder.create()
                    .uv(196, 31).cuboid(-2.5f, -1f, -3f, 5f, 8f, 6f)
                    .uv(40, 51).cuboid(-2f, 7f, -2.5f, 4f, 5f, 5f),
                ModelTransform.of(8f, 2f, 0f, 0f, 0f, -0.35f)
            )
            body.addChild(
                "arm_r",
                ModelPartBuilder.create()
                    .uv(196, 31).cuboid(-2.5f, -1f, -3f, 5f, 8f, 6f)
                    .uv(40, 51).cuboid(-2f, 7f, -2.5f, 4f, 5f, 5f),
                ModelTransform.of(-8f, 2f, 0f, 0f, 0f, 0.35f)
            )
            return TexturedModelData.of(data, 256, 256)
        }
    }
}
