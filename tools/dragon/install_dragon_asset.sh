#!/usr/bin/env bash
# =============================================================================================
#  ATTRIBUTION IS REQUIRED.  READ BEFORE REDISTRIBUTING ANYTHING THIS PRODUCES.
# =============================================================================================
#  The source model is third-party work used under CC BY 4.0:
#
#    "Demonic Wingwalker" by CsDani50
#    https://sketchfab.com/3d-models/demonic-wingwalker-f807bf53631e403094920b05dbebde17
#    Licensed CC BY 4.0 - https://creativecommons.org/licenses/by/4.0/
#    Changes were made: converted from glTF to Bedrock GeckoLib geometry + animations.
#
#  CC BY 4.0 permits redistribution and modification, including commercially, PROVIDED you give
#  appropriate credit, link the licence, and indicate that changes were made. Do not strip the
#  attribution in DRAGON_CREDITS.md, and do not imply CsDani50 endorses this mod.
#
#  Known residual: the listing marks the model "(THIS IS FANMADE)" of the Wyrmroost mod's creature.
#  CsDani50 licensed their own model, but the underlying creature design belongs to GundunUkan and
#  Wyrmroost's assets are All Rights Reserved. See DRAGON_CREDITS.md for the full analysis.
# =============================================================================================
#
# Installs the dragon model, animations and texture into the resource tree.
#
# WHY THIS EXISTS: the three generated files ARE committed, so you do not need this to build the
# mod. It is kept as the reproducible, auditable path that produced them - and as the mechanism for
# swapping in a replacement model, should the one documented in DRAGON_CREDITS.md ever need to go.
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
echo " Source model: \"Demonic Wingwalker\" by CsDani50, licensed CC BY 4.0."
echo " Redistribution is permitted, but ATTRIBUTION IS MANDATORY - keep the credit"
echo " in DRAGON_CREDITS.md intact, including the note that changes were made."
echo "=============================================================================="
