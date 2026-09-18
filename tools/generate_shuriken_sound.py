#!/usr/bin/env python3
"""
Synthesises the Rasen Shuriken audio from scratch.

Why synthesised rather than sourced: the effect ships inside a publicly redistributed MIT jar, so
the audio has to be originally authored. Generating it also buys exact control over *when* the
character changes, which a pre-made file cannot give - the formation-to-blade transition has to
land on a specific game tick, and here it lands on a specific audio sample.

Two files are produced:

  rasenshuriken_form.ogg   3.5s one-shot. Rising formation swell, no blade character. Played at
                           the first tick of the cast.
  rasenshuriken_spin.ogg   Seamless loop. Harsh shredding-air screech with a metallic whine.
                           Started on the exact tick the blades snap out, then follows the
                           shuriken through hold and flight.

The loop is *mathematically* seamless rather than crossfaded: every partial is an integer multiple
of 1/duration, so the waveform is exactly periodic over the file length and the join is sample-exact.

The screech's amplitude modulation is locked to the animation. The renderer spins at
MAX_SPIN = 0.85 rad/tick, which is 0.85 * 20 / (2*pi) = 2.706 rev/s; with four blades that is
10.82 blade passes per second. Modulating at that rate means you hear the blades chop the air at the
speed you see them rotate.

Run:  python3 tools/generate_shuriken_sound.py
Needs: pip install numpy imageio-ffmpeg
"""

import os
import subprocess
import sys
import wave

import numpy as np

SAMPLE_RATE = 44100
OUT_DIR = os.path.join("src", "main", "resources", "assets", "rasengan", "sounds")

# Locked to ShurikenRenderer.MAX_SPIN = 0.85 rad/tick at 20 ticks/s, times BLADE_COUNT = 4.
BLADE_PASS_HZ = 0.85 * 20.0 / (2.0 * np.pi) * 4.0  # ~10.82 Hz

FORM_SECONDS = 3.5


def normalise(signal, peak=0.89):
    """Scales to a fixed peak, leaving headroom so Vorbis encoding cannot clip."""
    maximum = np.max(np.abs(signal))
    if maximum < 1e-9:
        return signal
    return signal / maximum * peak


def write_wav(path, mono):
    """Writes 16-bit mono PCM. Mono is required: Minecraft plays stereo sounds non-positionally."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    pcm = np.clip(mono, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype("<i2")
    with wave.open(path, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        handle.writeframes(pcm.tobytes())


def build_formation():
    """
    The 3.5s formation swell: energy gathering, no blades yet.

    Deliberately round and smooth in character so it reads as the same lineage as Rasengan -
    the brief asks for the formation window to feel related, with the divergence arriving
    only when the blades appear.
    """
    n = int(SAMPLE_RATE * FORM_SECONDS)
    t = np.arange(n) / SAMPLE_RATE
    progress = t / FORM_SECONDS

    signal = np.zeros(n)

    # Rising fundamental plus harmonics. The sweep is exponential so the climb accelerates,
    # which matches the ease-out shell expansion rather than sounding linear.
    base = 110.0 * (2.0 ** (1.45 * progress))
    phase = 2.0 * np.pi * np.cumsum(base) / SAMPLE_RATE
    for harmonic, weight in ((1, 1.0), (2, 0.42), (3, 0.20), (5, 0.09)):
        signal += weight * np.sin(harmonic * phase)

    # Airy noise bed, growing as the shell forms. Filtered by a simple one-pole smoother so it
    # is a wash rather than hiss.
    rng = np.random.default_rng(20260418)
    noise = rng.standard_normal(n)
    smoothed = np.copy(noise)
    alpha = 0.06
    for _ in range(3):
        smoothed = alpha * smoothed + (1.0 - alpha) * np.concatenate(([smoothed[0]], smoothed[:-1]))
    signal += 0.55 * smoothed * progress ** 1.6

    # Shimmer: a faint high partial that pulses, hinting at instability to come.
    signal += 0.10 * progress ** 2 * np.sin(2.0 * np.pi * 2300.0 * t) * (
        0.5 + 0.5 * np.sin(2.0 * np.pi * 5.5 * t))

    # Overall swell, and a short fade-in so there is no click on the first sample.
    envelope = progress ** 1.25
    fade = np.minimum(1.0, t / 0.02)
    signal *= envelope * fade

    return normalise(signal, 0.80)


def build_spin(loop_cycles=26):
    """
    The seamless blade screech.

    Every component is periodic over the file, so the loop point is sample-exact:
      - the AM is an integer number of blade-pass cycles
      - each partial frequency is an integer multiple of 1/duration
      - the noise bed is additive sinusoids on that same harmonic grid, not random samples,
        which is what lets noise loop perfectly
    """
    duration = loop_cycles / BLADE_PASS_HZ
    n = int(round(SAMPLE_RATE * duration))
    t = np.arange(n) / SAMPLE_RATE

    # Snap duration to a whole number of samples so the grid stays exact.
    duration = n / SAMPLE_RATE
    fundamental = 1.0 / duration

    rng = np.random.default_rng(7717)
    signal = np.zeros(n)

    # --- Shredding-air bed -------------------------------------------------
    # Built from many partials on the harmonic grid across a wide band, with random phases.
    # Sonically this is broadband noise; mathematically it is perfectly periodic.
    low_bin = int(round(700.0 / fundamental))
    high_bin = int(round(7200.0 / fundamental))
    bins = np.arange(low_bin, high_bin, 3)
    phases = rng.uniform(0.0, 2.0 * np.pi, bins.size)
    # 1/f tilt so it is airy rather than harsh white noise.
    weights = 1.0 / np.sqrt(bins.astype(float))
    for bin_index, phase, weight in zip(bins, phases, weights):
        signal += weight * np.sin(2.0 * np.pi * bin_index * fundamental * t + phase)
    signal = normalise(signal, 1.0) * 0.72

    # --- Metallic whine ----------------------------------------------------
    # A few slightly inharmonic-sounding partials, each snapped to the grid so they still loop.
    for target, weight in ((1850.0, 0.26), (2790.0, 0.17), (4130.0, 0.10), (5570.0, 0.06)):
        bin_index = max(1, int(round(target / fundamental)))
        signal += weight * np.sin(2.0 * np.pi * bin_index * fundamental * t
                                  + rng.uniform(0, 2 * np.pi))

    # --- Blade-pass amplitude modulation -----------------------------------
    # Locked to the visual rotation rate. Sharpened so each pass is a distinct chop rather than
    # a gentle wobble.
    am_phase = 2.0 * np.pi * BLADE_PASS_HZ * t
    chop = 0.5 + 0.5 * np.sin(am_phase)
    chop = chop ** 2.2
    signal *= 0.42 + 0.58 * chop

    # --- Slow breathing so a long hold does not feel static ----------------
    # Two cycles across the loop, so it is also periodic.
    signal *= 0.88 + 0.12 * np.sin(2.0 * np.pi * (2.0 / duration) * t)

    return normalise(signal, 0.85), duration


def to_ogg(wav_path, ogg_path, ffmpeg, quality="5"):
    process = subprocess.run(
        [ffmpeg, "-hide_banner", "-loglevel", "error",
         "-i", wav_path,
         "-ac", "1", "-ar", str(SAMPLE_RATE),
         "-c:a", "libvorbis", "-q:a", quality,
         ogg_path, "-y"],
        capture_output=True,
    )
    if process.returncode != 0:
        sys.exit(f"ffmpeg failed: {process.stderr.decode('utf-8', 'replace')[:400]}")
    os.remove(wav_path)


def verify(path):
    import struct
    with open(path, "rb") as handle:
        header = handle.read(64)
    if header[:4] != b"OggS":
        sys.exit(f"{path} is not a valid Ogg file")
    channels = header[39]
    rate = struct.unpack("<I", header[40:44])[0]
    if channels != 1:
        sys.exit(f"{path} is {channels}-channel; Minecraft needs mono for positional audio")
    print(f"  ok  {os.path.basename(path)}  {channels}ch {rate}Hz  {os.path.getsize(path):,} bytes")


def main():
    try:
        import imageio_ffmpeg
    except ImportError:
        sys.exit("pip install imageio-ffmpeg")
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()

    print(f"blade-pass rate locked to the renderer: {BLADE_PASS_HZ:.2f} Hz")

    os.makedirs(OUT_DIR, exist_ok=True)

    print(f"\nformation ({FORM_SECONDS}s one-shot):")
    form = build_formation()
    wav = os.path.join(OUT_DIR, "_form.wav")
    write_wav(wav, form)
    to_ogg(wav, os.path.join(OUT_DIR, "rasenshuriken_form.ogg"), ffmpeg)
    verify(os.path.join(OUT_DIR, "rasenshuriken_form.ogg"))

    spin, spin_duration = build_spin()
    print(f"\nspin loop ({spin_duration:.4f}s, seamless):")
    wav = os.path.join(OUT_DIR, "_spin.wav")
    write_wav(wav, spin)
    # Higher quality on the loop: encoding artefacts at the join would be audible every cycle.
    to_ogg(wav, os.path.join(OUT_DIR, "rasenshuriken_spin.ogg"), ffmpeg, quality="7")
    verify(os.path.join(OUT_DIR, "rasenshuriken_spin.ogg"))

    print("\nDone.")


if __name__ == "__main__":
    main()
