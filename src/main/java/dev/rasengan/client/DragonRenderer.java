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
 */
public class DragonRenderer extends GeoEntityRenderer<DragonEntity, LivingEntityRenderState> {

    public DragonRenderer(EntityRendererProvider.Context context) {
        super(context, new DragonModel());
    }
}
