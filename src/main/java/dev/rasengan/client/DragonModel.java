package dev.rasengan.client;

import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;
import dev.rasengan.Rasengan;
import dev.rasengan.server.DragonEntity;
import net.minecraft.resources.Identifier;

/**
 * Resource bindings for the dragon.
 *
 * <p>Extends {@link GeoModel} directly and returns explicit paths rather than using
 * {@code DefaultedEntityGeoModel}, whose convention-derived paths would be one more implicit
 * assumption to get wrong. Note the 26.1 signature: the model and texture lookups take a
 * {@link GeoRenderState}, not the animatable - GUI and entity rendering moved to a render-state
 * extraction model in this version.
 */
public final class DragonModel extends GeoModel<DragonEntity> {

    private static final Identifier MODEL = Rasengan.id("geo/dragon.geo.json");
    private static final Identifier TEXTURE = Rasengan.id("textures/entity/dragon.png");
    private static final Identifier ANIMATION = Rasengan.id("animations/dragon.animation.json");

    @Override
    public Identifier getModelResource(GeoRenderState state) {
        return MODEL;
    }

    @Override
    public Identifier getTextureResource(GeoRenderState state) {
        return TEXTURE;
    }

    @Override
    public Identifier getAnimationResource(DragonEntity animatable) {
        return ANIMATION;
    }
}
