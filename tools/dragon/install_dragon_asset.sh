#!/usr/bin/env bash
# =============================================================================================
#  WARNING - ASSET LICENSING IS UNRESOLVED.  READ BEFORE RUNNING.
# =============================================================================================
#  Do NOT publish, release, or distribute a build with this asset embedded until licensing is
#  confirmed directly with the rights holder, or the asset is replaced with an original/properly
#  licensed model.
#
#  Evidence points to the creature design and model originating with GundunUkan / the Wyrmroost
#  mod, whose assets are All Rights Reserved and may not be redistributed in modified form. A
#  CC Attribution claim on a third-party Sketchfab re-upload does not cure this: a re-uploader
#  cannot grant rights they do not hold. The converted GeckoLib files this script produces are a
#  derivative work and are covered by the same restriction.
#
#  Running this script is fine for LOCAL development and testing. What is not fine is committing
#  the output, or building a jar from it and shipping that jar. `./gradlew build` embeds all three
#  files into build/libs/, so do not copy that jar into dist/ or upload it anywhere.
#
#  See DRAGON_CREDITS.md for the full evidence and the two resolution paths.
# =============================================================================================
#
# Installs the dragon model, animations and texture into the resource tree.
#
# WHY THIS EXISTS: the source asset's licence is UNRESOLVED (see DRAGON_CREDITS.md). The three
# generated files are therefore gitignored and NOT committed, exactly as AUDIO_CREDITS.md
# recommends for the shipped audio. Anyone who has the right to use the asset can regenerate them
# locally by running this script; without them the mod still builds, and the dragon will simply
# render untextured/absent.
#
# Usage:
#   tools/dragon/install_dragon_asset.sh [path-to-jowla7.zip]
# With no argument it downloads the bundle the asset was supplied from.
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT="$(pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

SRC="${1:-}"
if [ -z "$SRC" ]; then
  SRC="$WORK/asset.zip"
  echo "downloading asset bundle..."
  curl -sSL -o "$SRC" "https://files.catbox.moe/jowla7.zip"
fi

echo "unpacking $SRC"
unzip -q -o "$SRC" -d "$WORK/extracted"

if [ ! -f "$WORK/extracted/source/model.gltf" ]; then
  echo "ERROR: expected source/model.gltf inside the archive" >&2
  exit 1
fi

GEO_DIR="$ROOT/src/main/resources/assets/rasengan/geo"
ANIM_DIR="$ROOT/src/main/resources/assets/rasengan/animations"
TEX_DIR="$ROOT/src/main/resources/assets/rasengan/textures/entity"
mkdir -p "$GEO_DIR" "$ANIM_DIR" "$TEX_DIR"

echo "converting glTF -> GeckoLib geometry + animations"
python3 "$ROOT/tools/dragon/convert_gltf_to_geckolib.py" "$WORK/extracted" "$WORK/out"

cp "$WORK/out/dragon.geo.json"       "$GEO_DIR/dragon.geo.json"
cp "$WORK/out/dragon.animation.json" "$ANIM_DIR/dragon.animation.json"

# The texture must come from the copy embedded INSIDE the glTF, not from textures/ in the zip.
# The standalone file is vertically flipped relative to the UVs: both hold the same 89,600 opaque
# pixels, but the standalone has its content at rows 357..1023 while the UVs address rows 0..667.
# Shipping the standalone copy would offset the whole texture.
echo "extracting the UV-correct texture from the glTF"
python3 - "$WORK/extracted/source/model.gltf" "$TEX_DIR/dragon.png" <<'PY'
import base64, json, sys
gltf = json.load(open(sys.argv[1], encoding="utf-8"))
uri = gltf["images"][0].get("uri", "")
if not uri.startswith("data:"):
    raise SystemExit("glTF image is not embedded; cannot guarantee UV orientation")
open(sys.argv[2], "wb").write(base64.b64decode(uri.split(",", 1)[1]))
print(f"  wrote {sys.argv[2]}")
PY

echo
echo "installed:"
ls -la "$GEO_DIR/dragon.geo.json" "$ANIM_DIR/dragon.animation.json" "$TEX_DIR/dragon.png"
echo
echo "=============================================================================="
echo " These files are gitignored on purpose. LOCAL USE ONLY."
echo " Do NOT commit them, and do NOT publish or distribute a jar built with them,"
echo " until licensing is confirmed with the rights holder or the asset is replaced."
echo " ./gradlew build WILL embed them into build/libs/ - do not copy that to dist/."
echo " See DRAGON_CREDITS.md."
echo "=============================================================================="
