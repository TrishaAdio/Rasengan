package dev.rasengan;

import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Sound events for the Rasen Shuriken.
 *
 * <p>Both files are synthesised by {@code tools/generate_shuriken_sound.py} - original audio, mono
 * OGG Vorbis at 44.1 kHz. Mono matters: Minecraft plays stereo sounds non-positionally, so a stereo
 * file would ignore the attenuation curve entirely and sound the same at 2 blocks as at 40.
 *
 * <p>Rasengan's audio is deliberately left alone; it still uses the layered stock sounds.
 */
public final class RasenganSounds {

    public static final DeferredRegister<SoundEvent> REGISTRY =
            DeferredRegister.create(Registries.SOUND_EVENT, Rasengan.MOD_ID);

    /**
     * The 3.5 second formation swell. One-shot, started on the first tick of the cast.
     *
     * <p>Registered with a fixed range rather than a variable one so attenuation is predictable and
     * matches the visual effect distance.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> SHURIKEN_FORM =
            REGISTRY.register("rasenshuriken_form",
                    id -> SoundEvent.createVariableRangeEvent(id));

    /**
     * The seamless blade screech loop. Started on the exact tick the blades snap out, then follows
     * the shuriken through hold and flight.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> SHURIKEN_SPIN =
            REGISTRY.register("rasenshuriken_spin",
                    id -> SoundEvent.createVariableRangeEvent(id));

    /**
     * Rasengan's formation hum. One-shot covering exactly the cast window, played on the first tick
     * of the cast so it is synchronised with the core beginning to form.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> RASENGAN_FORM =
            REGISTRY.register("rasengan_form",
                    id -> SoundEvent.createVariableRangeEvent(id));

    /**
     * Rasengan's sustained loop. Starts once the sphere reaches full formation, then rides the
     * sphere through hold and flight and stops the moment it is gone.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> RASENGAN_SPIN =
            REGISTRY.register("rasengan_spin",
                    id -> SoundEvent.createVariableRangeEvent(id));

    /**
     * Summoning buildup: a 3.5 second rising drone that cuts to silence exactly on the reveal beat,
     * so the impact lands in the gap. Synthesised by {@code tools/generate_summon_sounds.py} - fully
     * original, so unlike the imported ability audio there is no licence question attached to it.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> SUMMON_BUILDUP =
            REGISTRY.register("summon_buildup",
                    id -> SoundEvent.createVariableRangeEvent(id));

    /** The arrival impact: a low thump with a descending sweep and debris tail. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SUMMON_REVEAL =
            REGISTRY.register("summon_reveal",
                    id -> SoundEvent.createVariableRangeEvent(id));

    /**
     * An OPTIONAL creature roar, layered under the silhouette reveal.
     *
     * <h2>This event usually has no sound file, and that is deliberate</h2>
     * The audio it was built for could not be cleared for redistribution, so it is installed locally
     * by {@code tools/install_summon_roar.sh} and is gitignored. See {@code AUDIO_CREDITS.md} for the
     * metadata that led to that decision.
     *
     * <p>Nothing needs to guard the call. {@code SoundManager.validateSoundResource} logs
     * <em>"File ... does not exist, cannot add it to event ..."</em> once at resource load and then
     * simply does not add the sound to the event, so playing it is a silent no-op for anyone who has
     * not installed a file. That one warning line in the client log is the entire cost of the feature
     * being absent, and is the reason this needs no config flag and no existence check.
     *
     * <p>Registered with a variable range like the rest, and played positionally at the dragon so it
     * attenuates with distance.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> SUMMON_ROAR =
            REGISTRY.register("summon_roar",
                    id -> SoundEvent.createVariableRangeEvent(id));

    private RasenganSounds() {}
}
