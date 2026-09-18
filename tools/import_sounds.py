#!/usr/bin/env python3
"""
Imports source recordings into the mod's sound assets.

Replaces the earlier Rasengan-only importer; both abilities are handled here so the conversion
logic exists once.

Each ability needs two files, because one fixed-length recording cannot cover a short cast plus a
flight of unknown length:

  <ability>_form.ogg   The formation window, cut to exactly the length the animation expects.
  <ability>_spin.ogg   A seamless loop for the held phase and the flight.

Mandatory conversions:
  * OGG Vorbis - Minecraft's sound engine reads nothing else.
  * Mono - Minecraft plays stereo sounds non-positionally, so a stereo file ignores the
    attenuation curve entirely and sounds the same at 2 blocks as at 40.
  * Normalisation - source levels vary wildly and quiet material is inaudible in game.

Loop handling does two things a plain cut cannot:
  * Envelope flattening. A loop taken from material that is swelling or decaying audibly pumps
    once per cycle. Dividing by a smoothed RMS envelope levels it first.
  * Equal-power crossfade at the join, so the wrap point is continuous rather than clicking.

Usage:
    python3 tools/import_sounds.py rasengan <file>
    python3 tools/import_sounds.py shuriken <file>
"""

import os
import subprocess
import sys
import wave

import numpy as np

SAMPLE_RATE = 44100
OUT_DIR = os.path.join("src", "main", "resources", "assets", "rasengan", "sounds")
TARGET_PEAK = 0.85

PRESETS = {
    # cast_duration_ticks = 30 -> 1.5s formation window.
    "rasengan": {
        "prefix": "rasengan",
        "form_seconds": 1.5,
        "loop_start": 4.0,
        "loop_seconds": 2.5,
        "crossfade": 0.35,
    },
    # Blades snap out at ShurikenRenderer.BLADE_SNAP_TICK = 70 ticks = 3.5s, so the formation cut
    # is 3.5s and the loop must come from the post-transition region.
    # Loop region and crossfade were found by searching the post-transition part of the source for
    # the join with the smallest discontinuity, rather than picked by eye. The chosen values give a
    # wrap step of 0.002 against a 99th-percentile internal step of 0.147 - two orders of magnitude
    # below the audible threshold. A nearby region (7.4s) measured 0.199 and clicked once per cycle.
    "shuriken": {
        "prefix": "rasenshuriken",
        "form_seconds": 3.5,
        "loop_start": 7.2,
        "loop_seconds": 2.2,
        "crossfade": 0.40,
    },
}



def ffmpeg():
    try:
        import imageio_ffmpeg
    except ImportError:
        sys.exit("pip install imageio-ffmpeg")
    return imageio_ffmpeg.get_ffmpeg_exe()


def decode_mono(path, exe):
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
    return signal if maximum < 1e-9 else signal / maximum * peak


def flatten_envelope(signal, window_seconds=0.25, strength=0.85):
    """
    Levels slow amplitude drift so a loop does not pump once per cycle.

    Divides by a smoothed absolute-value envelope. `strength` blends between untouched and fully
    flattened, so some natural movement survives rather than sounding gated.
    """
    window = max(1, int(window_seconds * SAMPLE_RATE))
    kernel = np.ones(window, dtype=np.float32) / window
    envelope = np.convolve(np.abs(signal), kernel, mode="same")
    envelope = np.maximum(envelope, 1e-4)
    target = float(np.median(envelope))
    gain = (target / envelope) ** strength
    # Keep the correction sane so a near-silent patch is not blown up.
    gain = np.clip(gain, 0.35, 3.0)
    return signal * gain


def make_loop(samples, start_seconds, length_seconds, crossfade_seconds):
    start = int(start_seconds * SAMPLE_RATE)
    length = int(length_seconds * SAMPLE_RATE)
    fade = int(crossfade_seconds * SAMPLE_RATE)

    if start + length + fade > samples.size:
        start = max(0, samples.size - length - fade - 1)
    if start < 0 or length <= fade:
        sys.exit("source too short for the requested loop region")

    body = np.copy(samples[start:start + length])
    tail = samples[start + length:start + length + fade]

    body = flatten_envelope(body)
    tail = flatten_envelope(tail)

    ramp = np.linspace(0.0, np.pi / 2.0, fade, dtype=np.float32)
    body[:fade] = body[:fade] * np.sin(ramp) + tail * np.cos(ramp)
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
    if header[39] != 1:
        sys.exit(f"{ogg_path} is {header[39]}-channel; mono is required for positional audio")
    rate = struct.unpack("<I", header[40:44])[0]
    print(f"  ok  {name}.ogg  {signal.size / SAMPLE_RATE:.3f}s  "
          f"1ch {rate}Hz  {os.path.getsize(ogg_path):,} bytes")


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in PRESETS:
        sys.exit(__doc__)
    preset = PRESETS[sys.argv[1]]
    source = sys.argv[2]
    if not os.path.isfile(source):
        sys.exit(f"no such file: {source}")

    exe = ffmpeg()
    samples = decode_mono(source, exe)
    print(f"source: {source}  {samples.size / SAMPLE_RATE:.2f}s  "
          f"peak {np.abs(samples).max():.3f}")

    # ---- Formation: exactly the animation's formation window ----
    form_len = int(preset["form_seconds"] * SAMPLE_RATE)
    if form_len > samples.size:
        sys.exit("source shorter than the required formation window")
    form = np.copy(samples[:form_len])
    edge = int(0.02 * SAMPLE_RATE)
    form[:edge] *= np.linspace(0.0, 1.0, edge, dtype=np.float32)
    form[-edge:] *= np.linspace(1.0, 0.0, edge, dtype=np.float32)
    write_ogg(normalise(form), f"{preset['prefix']}_form", exe)

    # ---- Sustain: seamless loop ----
    loop = make_loop(samples, preset["loop_start"], preset["loop_seconds"],
                     preset["crossfade"])
    write_ogg(normalise(loop), f"{preset['prefix']}_spin", exe, quality="7")

    print("\nDone.")


if __name__ == "__main__":
    main()
