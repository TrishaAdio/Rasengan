#!/usr/bin/env python3
"""Generates the two Summoning Jutsu sounds from scratch.

Fully original: every sample is computed here from oscillators and shaped noise using only the
Python standard library. Nothing is sampled, ripped or imported, so there is no licensing question
to resolve - unlike the shipped ability audio, which is still flagged in AUDIO_CREDITS.md.

  summon_buildup : rising drone + swelling noise, ending on an abrupt cut so the reveal lands in the
                   gap. Its length is the reveal tick, so it stops exactly on the reveal beat.
  summon_reveal  : low impact, a body thump with a descending sweep and a debris tail. Its length
                   covers the silhouette-reveal and wing-dispersal stages.

The two durations below are DERIVED FROM THE TIMELINE, not chosen independently. They were 3.5 s and
1.6 s when the cinematic's reveal sat at tick 70; the five-stage rebuild moved the reveal to tick 32,
and a buildup that still ran 3.5 s would have kept droning through the reveal, the dispersal and the
settle - the one thing its abrupt cut exists to avoid. Keep these in step with
dev.rasengan.SummonTimeline if the stage layout changes again.

Writes 16-bit mono 44.1 kHz WAV, then encodes to OGG with the ffmpeg bundled by imageio_ffmpeg -
matching the format the mod's other audio uses.
"""
import math
import os
import random
import struct
import subprocess
import sys
import wave

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), "..",
                   "src/main/resources/assets/rasengan/sounds")

# ---- Timeline, mirroring dev.rasengan.SummonTimeline's reference layout ----
TICK = 1.0 / 20.0
REVEAL_TICK = 32            # stage A + stage B: seal 12 + eruption 20
REVEAL_TO_SETTLE_TICKS = 29  # stage C (~17) + stage D (12), to the start of the settle

BUILDUP_SECONDS = REVEAL_TICK * TICK                # 1.60 s, cuts on the reveal
REVEAL_SECONDS = REVEAL_TO_SETTLE_TICKS * TICK      # 1.45 s, covers reveal + dispersal


def clamp(v):
    return max(-1.0, min(1.0, v))


def write_wav(path, samples):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(clamp(s) * 32000)) for s in samples))


def buildup(duration=3.5):
    """Rising drone: a slow pitch sweep, a shimmering fifth, and noise that swells then cuts."""
    n = int(RATE * duration)
    rng = random.Random(20260919)
    out = []
    # One-pole low-pass state for the noise bed.
    lp = 0.0
    for i in range(n):
        t = i / RATE
        p = t / duration  # 0..1 progress

        # Fundamental sweeps 55 Hz -> 190 Hz, accelerating (p^1.7) so it feels like it is winding up.
        f = 55.0 + 135.0 * (p ** 1.7)
        phase = 2.0 * math.pi * f * t
        drone = math.sin(phase) * 0.55 + math.sin(phase * 1.5) * 0.18 + math.sin(phase * 2.0) * 0.09

        # Slow tremolo, quickening with the sweep, for a "charging" pulse.
        trem = 0.82 + 0.18 * math.sin(2.0 * math.pi * (2.0 + 9.0 * p) * t)

        # Filtered noise bed, brightening as it builds.
        white = rng.uniform(-1.0, 1.0)
        cutoff = 0.02 + 0.25 * p
        lp += cutoff * (white - lp)
        noise = lp * (0.10 + 0.42 * p ** 2)

        # Envelope: smooth swell, then a hard 30 ms cut so the reveal impact lands in silence.
        if p < 0.94:
            env = 0.5 - 0.5 * math.cos(math.pi * min(1.0, p / 0.55))
        else:
            env = max(0.0, 1.0 - (p - 0.94) / 0.06)

        out.append((drone * trem + noise) * env * 0.85)
    return out


def reveal(duration=1.6):
    """Impact: a pitched-down body thump, a descending sweep, and a scattering debris tail."""
    n = int(RATE * duration)
    rng = random.Random(770077)
    out = []
    lp = 0.0
    for i in range(n):
        t = i / RATE
        p = t / duration

        # Body: 70 Hz falling to 38 Hz with a fast exponential decay.
        f_body = 70.0 * math.exp(-2.1 * t)
        body = math.sin(2.0 * math.pi * f_body * t) * math.exp(-4.5 * t) * 0.95

        # Descending sweep over the first third, giving the "arrival" gesture.
        f_sweep = 900.0 * math.exp(-6.0 * t) + 60.0
        sweep = math.sin(2.0 * math.pi * f_sweep * t) * math.exp(-7.0 * t) * 0.30

        # Debris: noise through a closing filter, lingering after the thump.
        white = rng.uniform(-1.0, 1.0)
        lp += (0.35 * math.exp(-1.4 * t) + 0.02) * (white - lp)
        debris = lp * math.exp(-2.0 * t) * 0.45

        # 4 ms attack so it does not click, then let the components' own decays shape it.
        attack = min(1.0, t / 0.004)
        tail = 1.0 if p < 0.90 else max(0.0, 1.0 - (p - 0.90) / 0.10)

        out.append((body + sweep + debris) * attack * tail)
    return out


def encode(wav_path, ogg_path):
    try:
        import imageio_ffmpeg
        ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        ffmpeg = "ffmpeg"
    subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                    "-i", wav_path, "-ac", "1", "-ar", str(RATE),
                    "-c:a", "libvorbis", "-q:a", "5", ogg_path], check=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, samples in (("summon_buildup", buildup(BUILDUP_SECONDS)),
                          ("summon_reveal", reveal(REVEAL_SECONDS))):
        wav = os.path.join(OUT, name + ".wav")
        ogg = os.path.join(OUT, name + ".ogg")
        write_wav(wav, samples)
        encode(wav, ogg)
        os.remove(wav)
        print(f"  {name}.ogg  {os.path.getsize(ogg):,} bytes  "
              f"{len(samples)/RATE:.2f}s  mono {RATE} Hz  (fully synthesised)")


if __name__ == "__main__":
    sys.exit(main())
