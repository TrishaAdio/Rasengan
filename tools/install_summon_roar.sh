#!/usr/bin/env bash
# =============================================================================================
#  LOCAL-ONLY AUDIO.  THIS FILE IS NOT, AND MUST NOT BE, COMMITTED.
# =============================================================================================
#  This installs an OPTIONAL extra summoning sound - a creature roar layered under stage C - from
#  an audio file YOU supply. The output path is gitignored.
#
#  WHY IT IS NOT BUNDLED
#  The file this mechanism was built for (https://files.catbox.moe/pk2622.m4a) could not be
#  cleared for redistribution. What its metadata showed, in full:
#
#    major_brand      : M4A
#    compatible_brands: M4A isom iso2      <- no 'dash'/'dsms'/'msix', no moof/sidx/styp boxes
#    encoder          : Lavf60.16.100      <- written by FFmpeg (libavformat 60.16 = FFmpeg 6.1)
#    stream           : AAC-LC, 44100 Hz, stereo, 127 kb/s, 20.83 s, one audio track, no video
#    ID3 / iTunes tags: absent
#
#  The absence of DASH container brands is the screen that rejected two earlier candidates in this
#  project. It carries much less weight here, and that distinction matters: those files were raw
#  streaming segments, whereas this one was written by FFmpeg. An FFmpeg remux REPLACES the
#  container, so it erases exactly the brands that screen looks for. Absence of the fingerprint is
#  therefore not evidence of clean provenance in this case - only evidence that FFmpeg touched it.
#  A 127 kb/s lossy stereo AAC is also a delivery bitrate rather than anything master-like.
#
#  Nothing in the file identifies an author or a licence, and that is not determinable from a file.
#  Since this repository is public under MIT and ships a compiled jar, redistribution rights matter,
#  so the file stays out of the repository and out of the jar. See AUDIO_CREDITS.md.
#
#  The mod is fully playable without this: summon_buildup.ogg and summon_reveal.ogg are synthesised
#  from scratch by tools/generate_summon_sounds.py and cover stages A, B, C and D on their own. This
#  roar is additive only, and the code treats it as optional.
# =============================================================================================
#
# Usage:
#   tools/install_summon_roar.sh <path-or-url-to-your-audio-file>
#
# Produces: src/main/resources/assets/rasengan/sounds/summon_roar.ogg
#   mono OGG Vorbis, 44.1 kHz. Mono is not cosmetic - Minecraft plays stereo sounds
#   NON-POSITIONALLY, so a stereo file would ignore distance attenuation entirely and be as loud at
#   40 blocks as at 2.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
OUT_DIR="$ROOT/src/main/resources/assets/rasengan/sounds"
OUT="$OUT_DIR/summon_roar.ogg"

if [ $# -lt 1 ]; then
  echo "usage: tools/install_summon_roar.sh <path-or-url-to-audio>" >&2
  exit 2
fi

SRC="$1"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [[ "$SRC" =~ ^https?:// ]]; then
  echo "downloading $SRC"
  curl -sSL -o "$WORK/input" "$SRC"
  SRC="$WORK/input"
fi

if [ ! -f "$SRC" ]; then
  echo "no such file: $SRC" >&2
  exit 1
fi

# ffmpeg: prefer the system binary, fall back to the one imageio-ffmpeg bundles. There is no system
# ffmpeg in this project's dev container, hence the fallback.
FF="$(command -v ffmpeg || true)"
if [ -z "$FF" ]; then
  FF="$(python3 -c 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())' 2>/dev/null || true)"
fi
if [ -z "$FF" ]; then
  echo "ffmpeg not found. Install it, or: pip install imageio-ffmpeg" >&2
  exit 1
fi

echo
echo "--- provenance of the file you supplied, for your own records ---"
"$FF" -hide_banner -i "$SRC" 2>&1 | sed -n '/Input #0/,/^At least one output/p' | grep -vE '^At least one' || true
echo "---------------------------------------------------------------"
echo "You are responsible for confirming you have the right to use this audio."
echo

mkdir -p "$OUT_DIR"

# Loudness-normalise, force mono, and fade both ends by 40 ms so the clip cannot click on start or
# stop when the sequence is cut short by an interruption.
"$FF" -hide_banner -loglevel error -y -i "$SRC" \
  -ac 1 -ar 44100 \
  -af "loudnorm=I=-16:TP=-1.5:LRA=11,afade=t=in:st=0:d=0.04,areverse,afade=t=in:st=0:d=0.04,areverse" \
  -c:a libvorbis -q:a 5 \
  "$OUT"

SIZE=$(wc -c < "$OUT")
DUR=$("$FF" -hide_banner -i "$OUT" 2>&1 | sed -n 's/.*Duration: \([0-9:.]*\).*/\1/p')
echo "wrote $OUT"
echo "  $SIZE bytes, duration $DUR, mono 44100 Hz OGG Vorbis"
echo
echo "This file is gitignored. 'git status' should not show it - if it does, stop and check"
echo ".gitignore before committing anything."
