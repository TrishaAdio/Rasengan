package dev.rasengan.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

/**
 * A looping sound that rides an entity and updates every tick.
 *
 * <h2>Why a tickable instance rather than a fired-and-forgotten sound</h2>
 * {@code level.playLocalSound} places a sound at a fixed point in the world. That is fine for an
 * impact, but wrong for something in flight: the sound would stay behind at the launch position
 * while the shuriken flew away, so it would neither follow the projectile nor attenuate correctly.
 *
 * <p>{@link AbstractTickableSoundInstance} is re-read by the sound engine every tick, so updating
 * {@code x/y/z} here moves the actual audio source. Attenuation is then handled by the engine's own
 * distance model against each listener's real position - which means every nearby player hears the
 * falloff from their own vantage point, rather than one global fade applied to everyone.
 *
 * <h2>Lifetime</h2>
 * The instance stops itself the moment its source entity is gone or the tracker no longer considers
 * it active. Because the engine polls {@link #isStopped()} every tick, teardown is immediate and
 * leaves no tail - there is no scheduled task to cancel and nothing to clean up separately.
 */
public class ShurikenSoundInstance extends AbstractTickableSoundInstance {

    /** The entity the sound rides: the caster while held, the projectile while in flight. */
    private final Entity source;

    /** Ticks to ramp volume in, so starting the loop does not click. */
    private static final float FADE_IN_TICKS = 2.0F;

    private final float targetVolume;
    private int age;

    public ShurikenSoundInstance(SoundEvent event, Entity source, float volume, float pitch,
                                 boolean looping) {
        super(event, SoundSource.PLAYERS, RandomSource.create());
        this.source = source;
        this.targetVolume = volume;
        this.pitch = pitch;
        this.looping = looping;
        // No delay: the sound must be audible on the same tick it is requested, because it is
        // synchronised to a visual beat.
        this.delay = 0;
        this.volume = 0.0F;
        syncPosition();
    }

    /**
     * Permits the sound to begin at zero volume.
     *
     * <p><b>Required, not optional.</b> {@code SoundEngine} discards any sound whose volume is zero
     * at the moment it is played:
     *
     * <pre>
     *   if (volume == 0.0F) {
     *       if (!instance.canStartSilent() &amp;&amp; soundSource != SoundSource.MUSIC) {
     *           LOGGER.debug("Skipped playing sound {}, volume was zero.");
     *           return PlayResult.NOT_STARTED;
     *       }
     *   }
     * </pre>
     *
     * <p>This instance deliberately starts silent and ramps up over {@link #FADE_IN_TICKS} so the
     * loop does not begin with a click. Without this override the engine dropped every sound before
     * it started, and nothing was ever audible - the fade-in was silently defeating itself.
     */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    private void syncPosition() {
        this.x = (float) source.getX();
        this.y = (float) source.getY();
        this.z = (float) source.getZ();
    }

    @Override
    public void tick() {
        // Source gone - impact, despawn, death, unload, dimension change. Stop immediately.
        if (source.isRemoved() || !source.isAlive()) {
            stop();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.isPaused()) {
            return;
        }

        age++;
        syncPosition();

        // Short ramp so the loop does not begin with a transient.
        float fade = Math.min(1.0F, age / FADE_IN_TICKS);
        this.volume = targetVolume * fade;
    }

    /** Lets the owner tear the sound down explicitly, e.g. on a cancelled cast. */
    public void stopNow() {
        stop();
    }

    public Entity source() {
        return source;
    }
}
