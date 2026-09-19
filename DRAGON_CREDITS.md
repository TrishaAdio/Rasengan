# Dragon asset — licence UNRESOLVED, do not ship publicly yet

**Status: blocked.** The dragon model and texture are **not committed** to this repository, and must
not be, until the rights are confirmed. The code that uses them is committed and builds without
them.

## What was supplied

A zip containing exactly two files, and nothing else:

```
source/model.gltf                  1,181,476 bytes   glTF 2.0, generator "Blockbench 4.12.4 glTF exporter"
textures/gltf_embedded_0.png         100,769 bytes   1024x1024 RGBA
```

No licence file. No readme. No copyright, author or attribution metadata anywhere:

- `asset` block contains only `{"version": "2.0", "generator": "Blockbench 4.12.4 glTF exporter"}`
- no `asset.copyright`, no `extras`
- no `tEXt`/`iTXt`/`zTXt` chunks in the PNG
- a string search for `licen`, `copyright`, `author`, `cc-by`, `cc0`, `sketchfab`, `attribution`
  and `credit` found only one hit, `cc0`, which was a false positive inside base64 buffer data

## Why this looks like All Rights Reserved third-party work

The asset is not anonymous. Identifying details inside it:

- the rig's root bone is named `Demonic_Wingwalker`
- the texture is referenced as `DemonicWingwalkerTexture.png`

Following that up:

- "Demonic Wingwalker / *Brachipterax daemonicus*" is
  [an original dragon species by the artist GundunUkan](https://ukan.artstation.com/projects/48zy3W),
  described by them as their own creature design
- a [**"Demonic Wingwalker (Wyrmroost edition)"**](https://www.deviantart.com/gundunukan/art/Demonic-Wingwalker-Wyrmroost-edition-865410707)
  exists — Wyrmroost being a well-known Minecraft dragon mod
- Wyrmroost's README states the project is
  [**All Rights Reserved with respect to assets**](https://github.com/Shannieann/Wyrmroost/blob/master/README.md)
  (code is LGPL, assets are not), and
  [that asset material may not be redistributed in any modified form, including transformations](https://github.com/MWall541/Wyrmroost/blob/master/README.md),
  unless explicitly permitted. CurseForge lists the mod as All Rights Reserved.

*Sources paraphrased; content was rephrased for compliance with licensing restrictions.*

This is circumstantial but consistent: the name matches, a Wyrmroost edition of that exact creature
exists, and a Blockbench glTF re-export is what you would get from opening a mod's model file and
exporting it.

**If that identification is right, then this repository — public, MIT, and shipping a jar — cannot
legally include the asset, and the GeckoLib conversion produced by
`tools/dragon/convert_gltf_to_geckolib.py` is exactly the kind of "transformation" that licence
names.**

## What was done about it

Following the same remediation already documented in `AUDIO_CREDITS.md` for the shipped audio:

- the three generated asset files are listed in `.gitignore` and are **not** committed:
  - `src/main/resources/assets/rasengan/geo/dragon.geo.json`
  - `src/main/resources/assets/rasengan/animations/dragon.animation.json`
  - `src/main/resources/assets/rasengan/textures/entity/dragon.png`
- `tools/dragon/install_dragon_asset.sh` regenerates all three locally from the source bundle, for
  anyone who has the right to use it
- the mod builds and loads without them; the dragon will simply have no model or texture

## To resolve

One of:

1. **You hold the rights** (you are the author, or have written permission). Then say so, drop the
   three paths from `.gitignore`, and record the required attribution here.
2. **Permission is obtained** from GundunUkan and/or the Wyrmroost maintainers, in writing, covering
   redistribution of a converted form. Record it here.
3. **Replace the asset** with an original cuboid dragon, using this one only as visual reference.
   The entity, AI, combat, command and renderer are asset-independent and need no changes beyond
   swapping the three files.

Until one of those happens: **do not publish a build containing these files.**

### Note on `dist/`

This repo commits a built jar to `dist/` because release-asset upload is blocked in the development
environment. **That jar has deliberately not been refreshed for this change.** Once the asset files
are installed locally, `./gradlew build` embeds all three into `build/libs/rasengan-1.4.1.jar`, so
copying that into `dist/` would commit the unlicensed asset via the jar and defeat the `.gitignore`
entries entirely. Leave `dist/` alone until the licence is resolved.

## Third-party code dependency (this one is clear)

- **GeckoLib 5.5.2** by Tslat — **MIT licensed** (stated in its own `neoforge.mods.toml`).
  Declared as a required dependency; not bundled. https://github.com/bernie-g/geckolib
