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
}
