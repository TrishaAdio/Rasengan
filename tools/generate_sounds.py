#!/usr/bin/env python3
"""
Generates the mod's sound effects with the ElevenLabs sound-generation API and converts them
into the format Minecraft actually needs.

Every sound produced here is generated from a text prompt, so it is original audio rather than
anything sampled from another game, anime or mod - the same rule the visuals follow.

Usage:
    export ELEVENLABS_API_KEY="..."
    python3 tools/generate_sounds.py            # generate everything
    python3 tools/generate_sounds.py shuriken_impact   # just one

Requires `imageio-ffmpeg` for the bundled static ffmpeg:
    pip install imageio-ffmpeg

Two Minecraft-specific conversion details that matter:

1. Minecraft's sound engine reads **OGG Vorbis** only. The API returns MP3, so conversion is
   mandatory, not cosmetic.
2. Sounds must be **mono**. Minecraft plays stereo files non-positionally - they ignore the
   attenuation curve entirely, so a stereo impact would sound identical at 2 blocks and 40
   blocks away, with no direction. Everything here is downmixed to 1 channel.
"""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

API_URL = "https://api.elevenlabs.io/v1/sound-generation"

OUT_DIR = os.path.join("src", "main", "resources", "assets", "rasengan", "sounds")

# Prompts follow the API's own guidance: concrete acoustic language naming the source, the
# intensity and the tail, with explicit transient words for impacts and no looping on one-shots.
SOUNDS = {
    "shuriken_impact": {
        "text": (
            "A single sharp explosive crack of compressed air bursting, with a bright glassy "
            "shatter layered on top and a short crisp decay. Violent and metallic, not muffled. "
            "Tight transient, minimal reverb tail."
        ),
        "duration_seconds": 1.6,
        "prompt_influence": 0.75,
    },
    "rasengan_impact": {
        "text": (
            "A deep concussive energy boom with a low sub-bass thump and a rolling rumble tail. "
            "Rounded and heavy rather than sharp, like a dense sphere detonating. Moderate reverb."
        ),
        "duration_seconds": 2.2,
        "prompt_influence": 0.75,
    },
    "shuriken_screech": {
        "text": (
            "A seamless high-pitched howling wind screech, like air being shredded at speed by "
            "spinning blades. Continuous and steady with a faint metallic whine, no impacts."
        ),
        "duration_seconds": 3.0,
        "prompt_influence": 0.6,
        "loop": True,
    },
    "charge_hum": {
        "text": (
            "A seamless low electrical energy hum with a faint rising harmonic shimmer, steady "
            "and continuous, no transients or impacts."
        ),
        "duration_seconds": 3.0,
        "prompt_influence": 0.6,
        "loop": True,
    },
}


def ffmpeg_exe():
    try:
        import imageio_ffmpeg
    except ImportError:
        sys.exit("imageio-ffmpeg is not installed. Run: pip install imageio-ffmpeg")
    return imageio_ffmpeg.get_ffmpeg_exe()


def generate(name, spec, api_key):
    """Calls the API and returns raw MP3 bytes."""
    payload = {k: v for k, v in spec.items()}
    body = json.dumps(payload).encode("utf-8")

    request = urllib.request.Request(
        API_URL,
        data=body,
        headers={
            "xi-api-key": api_key,
            "Content-Type": "application/json",
            "Accept": "audio/mpeg",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")[:500]
        sys.exit(f"[{name}] API returned HTTP {error.code}: {detail}")
    except urllib.error.URLError as error:
        sys.exit(f"[{name}] could not reach the API: {error.reason}")


def to_minecraft_ogg(mp3_bytes, out_path, ffmpeg):
    """Converts MP3 bytes to a mono 44.1 kHz OGG Vorbis file Minecraft can load."""
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    process = subprocess.run(
        [
            ffmpeg, "-hide_banner", "-loglevel", "error",
            "-i", "pipe:0",
            "-ac", "1",            # mono: stereo would break positional attenuation
            "-ar", "44100",
            "-c:a", "libvorbis",
            "-q:a", "5",
            out_path, "-y",
        ],
        input=mp3_bytes,
        capture_output=True,
    )
    if process.returncode != 0:
        sys.exit(f"ffmpeg failed for {out_path}: {process.stderr.decode('utf-8', 'replace')[:400]}")


def verify(path):
    """Sanity-checks the Ogg header so a malformed file is caught here, not at runtime."""
    import struct
    with open(path, "rb") as handle:
        data = handle.read(64)
    if data[:4] != b"OggS":
        sys.exit(f"{path} is not a valid Ogg container")
    channels = data[39]
    rate = struct.unpack("<I", data[40:44])[0]
    if channels != 1:
        sys.exit(f"{path} has {channels} channels; Minecraft needs mono for positional audio")
    size = os.path.getsize(path)
    print(f"  ok  {path}  ({channels}ch, {rate} Hz, {size:,} bytes)")


def write_sounds_json(names):
    """
    Writes assets/rasengan/sounds.json.

    Every entry uses "stream": false - these are short files and streaming is only appropriate for
    long tracks like music discs.
    """
    path = os.path.join("src", "main", "resources", "assets", "rasengan", "sounds.json")
    entries = {}
    for name in sorted(names):
        entries[name] = {
            "category": "player",
            "sounds": [{"name": f"rasengan:{name}", "stream": False}],
        }
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(entries, handle, indent=2)
        handle.write("\n")
    print(f"  ok  {path} ({len(entries)} entries)")


def main():
    api_key = os.environ.get("ELEVENLABS_API_KEY")
    if not api_key:
        sys.exit(
            "ELEVENLABS_API_KEY is not set.\n"
            "Get a key from https://elevenlabs.io/app/settings/api-keys then:\n"
            '  export ELEVENLABS_API_KEY="..."'
        )

    requested = sys.argv[1:] or list(SOUNDS)
    unknown = [n for n in requested if n not in SOUNDS]
    if unknown:
        sys.exit(f"Unknown sound(s): {', '.join(unknown)}\nAvailable: {', '.join(SOUNDS)}")

    ffmpeg = ffmpeg_exe()
    print(f"ffmpeg: {ffmpeg}\n")

    for name in requested:
        print(f"generating {name!r} ...")
        mp3 = generate(name, SOUNDS[name], api_key)
        out = os.path.join(OUT_DIR, f"{name}.ogg")
        to_minecraft_ogg(mp3, out, ffmpeg)
        verify(out)

    write_sounds_json(requested)
    print("\nDone. Register these in RasenganSounds and reference them from ClientEffects.")


if __name__ == "__main__":
    main()
