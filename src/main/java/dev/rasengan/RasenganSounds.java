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

    private RasenganSounds() {}
}
