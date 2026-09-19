# Dragon asset — credits and licence

The dragon model, texture and animations in this mod are **third-party work used under
CC BY 4.0**. They are not original to this project. Attribution is mandatory; the details below
satisfy it and must be kept intact.

## Required attribution

> **"Demonic Wingwalker"** by **CsDani50** — source:
> <https://sketchfab.com/3d-models/demonic-wingwalker-f807bf53631e403094920b05dbebde17> —
> licensed under [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/).
> **Changes were made:** the model was exported from Blockbench as glTF 2.0 and converted by this
> project into Bedrock-format GeckoLib geometry (`dragon.geo.json`) and animations
> (`dragon.animation.json`), and the texture was re-extracted from the glTF to correct its vertical
> orientation. No change was made to the geometry, UVs or animation curves themselves.

CC BY 4.0 requires three things, all of which the paragraph above provides: appropriate credit, a
link to the licence, and an indication that changes were made. It must not be removed, and it must
not be reworded in a way that implies CsDani50 endorses this mod.

## What the licence permits

Per the [licence deed](https://creativecommons.org/licenses/by/4.0/), CC BY 4.0 allows:

- **Share** — copy and redistribute in any medium or format, for any purpose, **even commercially**
- **Adapt** — remix, transform and build upon it, for any purpose, **even commercially**

So bundling the converted asset in this public repository and shipping it inside the mod jar is
permitted. CC BY is **not** a ShareAlike or NonCommercial licence, so it does not propagate to this
project's code: the code remains MIT, the asset remains CC BY 4.0. The two coexist, and this file is
what records the distinction.

## Identity of the asset, confirmed

The supplied bundle was matched against the Sketchfab listing rather than assumed to be the same
thing:

| Sketchfab listing | This asset |
|---|---|
| Triangles 2.2k | **2,196** (183 cubes × 12 triangles) |
| Vertices 1.5k | **1,464** deduplicated (183 cubes × 8 corners) |
| "Uploaded with Blockbench" | glTF generator `Blockbench 4.12.4 glTF exporter` |

Together with the internal names `Demonic_Wingwalker` and `DemonicWingwalkerTexture.png`, that
identifies it conclusively.

## Residual risk: the creature design is not CsDani50's

This is recorded deliberately, as a known and accepted risk rather than a blocker.

The Sketchfab listing describes the model as *"The Demonic Wingwalker from the Minecraft mod
'Wyrmroost'"* and marks it **"(THIS IS FANMADE)"**. So CsDani50 authored this model themselves as
fan art, and is entitled to license their own work under CC BY 4.0 — which is what they did.

What CsDani50 cannot license is the **underlying creature design**. "Demonic Wingwalker /
*Brachipterax daemonicus*" is [an original species by the artist GundunUkan](https://ukan.artstation.com/projects/48zy3W),
and the [Wyrmroost mod's own terms are All Rights Reserved for assets](https://github.com/Shannieann/Wyrmroost/blob/master/README.md).
The CC BY grant therefore covers the model's expression, not the character it depicts. The licence
deed itself flags exactly this situation:

> No warranties are given. The license may not give you all of the permissions necessary for your
> intended use.

In practice this is ordinary fan art, which is pervasive in the Minecraft modding scene, and the
risk is low. But it is a real distinction and it is not resolved by CsDani50's licence. If
GundunUkan or the Wyrmroost maintainers object, the remedy is to replace the model — everything else
in this feature is asset-independent (see below), so only three files would change.

*Third-party sources above are paraphrased; content was rephrased for compliance with licensing
restrictions.*

### A correction

Earlier revisions of this file, written before the Sketchfab listing was available, framed this as a
likely *unauthorised re-upload of Wyrmroost's model*. That was wrong and unfair to CsDani50. The
listing shows an independently built fan model with an explicit licence, not an extraction of
someone else's files. The record is corrected here rather than quietly edited away.

## Regenerating the asset

The three generated files **are** committed now, so nothing needs to be run to build the mod.

`tools/dragon/install_dragon_asset.sh` remains the reproducible path that produced them: it fetches
the source bundle, runs `tools/dragon/convert_gltf_to_geckolib.py`, and installs the results. Keep
it — it is how the conversion is audited and how a replacement model would be swapped in.

If the model is ever replaced, only these three change:

- `src/main/resources/assets/rasengan/geo/dragon.geo.json`
- `src/main/resources/assets/rasengan/animations/dragon.animation.json`
- `src/main/resources/assets/rasengan/textures/entity/dragon.png`

The entity, config, AI, both attacks, the boss bar, the loot table, the `/spawn` command and the
renderer are all asset-independent and work against any GeckoLib model with an equivalent bone rig.

## Third-party code dependency

- **GeckoLib 5.5.2** by Tslat — **MIT licensed** (stated in its own `neoforge.mods.toml`).
  Declared as a required dependency; not bundled. <https://github.com/bernie-g/geckolib>
