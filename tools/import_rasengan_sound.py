#!/usr/bin/env python3
"""
Imports a source recording into the two Rasengan sound assets.

The source is a long, largely steady hum, which does not map onto Minecraft's sound system
directly: a cast is short and a flight is of unknown length, so one fixed-length file cannot cover
both. This splits it into the two pieces the sync system needs:

  rasengan_form.ogg   The cast window exactly (cast_duration_ticks = 30 -> 1.5s). One-shot, played
                      on the first tick of the cast.
  rasengan_spin.ogg   A seamless loop cut from the steadiest region. Starts when the sphere reaches
                      full formation, then rides the projectile and stops when it is gone.

Three conversions are mandatory, not cosmetic:
  * OGG Vorbis - Minecraft's sound engine reads nothing else.
  * Mono - Minecraft plays stereo sounds non-positionally, so a stereo file would ignore the
    attenuation curve entirely and sound identical at 2 blocks and 40.
  * Normalisation - the source peaks at about 0.18, which is far too quiet to hear over ambient
    game audio.

The loop is made seamless with an equal-power crossfade: the tail is mixed into the head so the
wrap point is continuous. A raw cut would click audibly once per cycle.

Usage:
    python3 tools/import_rasengan_sound.py <source-audio-file>
"""

import os
import subprocess
import sys
import wave

import numpy as np

SAMPLE_RATE = 44100
OUT_DIR = os.path.join("src", "main", "resources", "assets", "rasengan", "sounds")

# Must match RasenganConfig cast_duration_ticks (30 ticks at 20 tps).
FORM_SECONDS = 1.5

# Loop cut from a steady region, avoiding the swells at the start and around 13-15s.
LOOP_START_SECONDS = 4.0
LOOP_SECONDS = 2.5
CROSSFADE_SECONDS = 0.35

TARGET_PEAK = 0.85


def ffmpeg():
    try:
        import imageio_ffmpeg
    except ImportError:
        sys.exit("pip install imageio-ffmpeg")
    return imageio_ffmpeg.get_ffmpeg_exe()


def decode_mono(path, exe):
    """Decodes any input to mono float32 at SAMPLE_RATE."""
    process = subprocess.run(
        [exe, "-hide_banner", "-loglevel", "error", "-i", path,
         "-f", "s16le", "-ac", "1", "-ar", str(SAMPLE_RATE), "pipe:1"],
        capture_output=True,
    )
    if process.returncode != 0:
        sys.exit(f"decode failed: {process.stderr.decode('utf-8', 'replace')[:300]}")
    return np.frombuffer(process.stdout, dtype="<i2").astype(np.float32) / 32768.0


def normalise(signal, peak=TARGET_PEAK):
    maximum = float(np.max(np.abs(signal)))
    if maximum < 1e-9:
        return signal
    return signal / maximum * peak


def make_loop(samples):
    """
    Cuts a seamless loop.

    Takes LOOP_SECONDS plus a crossfade tail, then mixes that tail back over the head with
    complementary raised-cosine gains. Equal-power rather than linear so the level does not dip
    through the join.
    """
    start = int(LOOP_START_SECONDS * SAMPLE_RATE)
    length = int(LOOP_SECONDS * SAMPLE_RATE)
    fade = int(CROSSFADE_SECONDS * SAMPLE_RATE)

    if start + length + fade > samples.size:
        # Source shorter than expected: fall back to using it from the beginning.
        start = 0
        length = max(1, min(length, samples.size - fade - 1))

    body = np.copy(samples[start:start + length])
    tail = samples[start + length:start + length + fade]

    ramp = np.linspace(0.0, np.pi / 2.0, fade, dtype=np.float32)
    fade_in = np.sin(ramp)
    fade_out = np.cos(ramp)

    body[:fade] = body[:fade] * fade_in + tail * fade_out
    return body


def write_ogg(signal, name, exe, quality="6"):
    os.makedirs(OUT_DIR, exist_ok=True)
    wav_path = os.path.join(OUT_DIR, f"_{name}.wav")
    ogg_path = os.path.join(OUT_DIR, f"{name}.ogg")

    pcm = (np.clip(signal, -1.0, 1.0) * 32767.0).astype("<i2")
    with wave.open(wav_path, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        handle.writeframes(pcm.tobytes())

    process = subprocess.run(
        [exe, "-hide_banner", "-loglevel", "error", "-i", wav_path,
         "-ac", "1", "-ar", str(SAMPLE_RATE), "-c:a", "libvorbis", "-q:a", quality,
         ogg_path, "-y"],
        capture_output=True,
    )
    os.remove(wav_path)
    if process.returncode != 0:
        sys.exit(f"encode failed: {process.stderr.decode('utf-8', 'replace')[:300]}")

    import struct
    with open(ogg_path, "rb") as handle:
        header = handle.read(64)
    if header[:4] != b"OggS":
        sys.exit(f"{ogg_path} is not valid Ogg")
    channels = header[39]
    if channels != 1:
        sys.exit(f"{ogg_path} is {channels}-channel; mono is required for positional audio")
    rate = struct.unpack("<I", header[40:44])[0]
    print(f"  ok  {name}.ogg  {signal.size / SAMPLE_RATE:.3f}s  "
          f"{channels}ch {rate}Hz  {os.path.getsize(ogg_path):,} bytes")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    source = sys.argv[1]
    if not os.path.isfile(source):
        sys.exit(f"no such file: {source}")

    exe = ffmpeg()
    samples = decode_mono(source, exe)
    print(f"source: {source}  {samples.size / SAMPLE_RATE:.2f}s  peak {np.abs(samples).max():.3f}")

    # ---- Formation: exactly the cast window, with a short fade at each end ----
    form = np.copy(samples[:int(FORM_SECONDS * SAMPLE_RATE)])
    edge = int(0.02 * SAMPLE_RATE)
    form[:edge] *= np.linspace(0.0, 1.0, edge, dtype=np.float32)
    form[-edge:] *= np.linspace(1.0, 0.0, edge, dtype=np.float32)
    write_ogg(normalise(form), "rasengan_form", exe)

    # ---- Sustain: seamless loop for hold and flight ----
    write_ogg(normalise(make_loop(samples)), "rasengan_spin", exe, quality="7")

    print("\nDone.")


if __name__ == "__main__":
    main()
