package dev.rasengan.client;

import com.geckolib.renderer.GeoEntityRenderer;
import dev.rasengan.server.DragonEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/**
 * Draws the dragon through GeckoLib.
 *
 * <p>No scale factor is applied. The asset's own proportions are already boss-sized - 29.5 blocks
 * across the wings and 4.8 tall - and the entity's hitbox was chosen to match the body at that
 * native scale, so scaling here would desynchronise the two.
 *
 * <h2>The hidden phase</h2>
 * A summoned dragon exists from the cinematic's first tick but must not be visible until the reveal.
 * {@link #shouldRender} refuses it while the server's hidden flag is set, which is what allows the
 * entity to be constructed, replicated and fully initialised under cover of the smoke instead of on
 * the frame the smoke clears.
 *
 * <p>Refused at {@code shouldRender} rather than by drawing it transparent, because a transparent
 * draw still costs a full model submission and still has to bind the texture - which would put back
 * exactly the cost the pre-spawn exists to move.
 */
public class DragonRenderer extends GeoEntityRenderer<DragonEntity, LivingEntityRenderState> {

    public DragonRenderer(EntityRendererProvider.Context context) {
        super(context, new DragonModel());
    }

    @Override
    public boolean shouldRender(DragonEntity entity, net.minecraft.client.renderer.culling.Frustum frustum,
                                double camX, double camY, double camZ) {
        if (entity.isHiddenForSummon()) {
            return false;
        }
        return super.shouldRender(entity, frustum, camX, camY, camZ);
    }

    /**
     * Bank angle, carried on the render state rather than read from the entity at draw time.
     *
     * <p>26.1 draws entities from an extracted render state, and GeckoLib's {@code addRenderData} is the
     * designed place to attach a mod's own value to it. Going through the render state rather than
     * reaching back to the live entity is what keeps the draw consistent with the frame it was extracted
     * for.
     */
    private static final com.geckolib.constant.dataticket.DataTicket<Float> BANK =
            com.geckolib.constant.dataticket.DataTicket.create("rasengan:bank", Float.class);

    @Override
    public void addRenderData(DragonEntity dragon, Void relatedObject,
                              LivingEntityRenderState renderState, float partialTick) {
        super.addRenderData(dragon, relatedObject, renderState, partialTick);
        ((com.geckolib.renderer.base.GeoRenderState) renderState)
                .getDataMap().put(BANK, dragon.bankAngle());
    }

    /**
     * Rolls the model into its turn.
     *
     * <p>Pushed here and popped in {@link #postRenderPass}, so the rotation applies to this dragon's pass
     * and cannot leak into whatever is drawn next - the same discipline the custom-geometry effects follow
     * for their pose stacks.
     *
     * <p>Deliberately visual only. {@code DragonAnchor} does not include the bank, so the rider stands
     * upright on a dragon that tilts beneath them. Rolling the rider with it would swing them sideways
     * through the air on every turn and reliably clip them into the model - and for a rider who is
     * standing rather than seated, staying level is also simply what looks right. Recorded as a
     * deliberate simplification.
     */
    // The two hooks below take a RAW RenderPassInfo, which needs explaining rather than apologising for.
    //
    // GeoRenderer declares them as RenderPassInfo<R> with R bounded by GeoRenderState, while
    // GeoEntityRenderer's own R is bounded only by EntityRenderState. That only type-checks because
    // GeckoLib MIXES GeoRenderState into EntityRenderState at runtime (see its EntityRenderStateMixin) -
    // and a mixin-injected interface is not present on the compile classpath. So there is no compile-time
    // type that satisfies both bounds, and the erasure-level raw form is the only way to state the
    // override. The cast in bankOf is the same runtime fact, made explicit.
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void preRenderPass(com.geckolib.renderer.base.RenderPassInfo info,
                              net.minecraft.client.renderer.SubmitNodeCollector collector) {
        super.preRenderPass(info, collector);
        info.poseStack().pushPose();
        float bank = bankOf(info);
        if (Math.abs(bank) >= 0.05F) {
            info.poseStack().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(bank));
        }
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void postRenderPass(com.geckolib.renderer.base.RenderPassInfo info,
                               net.minecraft.client.renderer.SubmitNodeCollector collector) {
        // Popped unconditionally, matching the unconditional push above: a bank that dropped below the
        // threshold between the two passes must not leave the pose stack unbalanced.
        info.poseStack().popPose();
        super.postRenderPass(info, collector);
    }

    @SuppressWarnings("rawtypes")
    private static float bankOf(com.geckolib.renderer.base.RenderPassInfo info) {
        Object state = info.renderState();
        if (!(state instanceof com.geckolib.renderer.base.GeoRenderState geoState)) {
            return 0.0F;
        }
        Object value = geoState.getDataMap().get(BANK);
        return value instanceof Float bank ? bank : 0.0F;
    }
}
