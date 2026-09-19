# Dragon asset — licence UNRESOLVED, do not ship publicly yet

> ## ⚠ DO NOT DISTRIBUTE
>
> **Do not publish, release, or distribute a build with this asset embedded until licensing is
> confirmed directly with the rights holder, or the asset is replaced with an original/properly
> licensed model.**
>
> This applies to jars, modpacks, CurseForge/Modrinth uploads, `dist/`, GitHub releases, and any
> server distribution. It applies to the *converted* GeckoLib files as much as to the originals — a
> format conversion is a derivative work, not a clean-room reimplementation.

**Status: blocked.** The dragon model and texture are **not committed** to this repository, and must
not be, until the rights are confirmed. The code that uses them is committed and builds without
them.

## The licensing conflict, in one paragraph

A Sketchfab re-upload of this model reportedly carries a **CC Attribution** licence (reported by the
project owner; I was unable to locate that listing to verify it independently). That claim does not
resolve anything, because **a re-uploader cannot grant rights they do not hold.** The underlying
creature design and model appear to originate with GundunUkan / the Wyrmroost mod, whose assets are
**All Rights Reserved** and explicitly may not be redistributed in modified form. Where an upstream
All-Rights-Reserved work has been re-published by a third party under a permissive licence, the
permissive label is ineffective as to the parts the re-uploader did not own — the upstream terms
govern. So the CC Attribution claim, if it exists, is best read as evidence of an unauthorised
re-upload rather than as a grant of rights to us.

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

## The two resolution paths

**(a) Obtain explicit permission from the rights holder.** Written permission from GundunUkan and/or
the Wyrmroost maintainers, specifically covering redistribution of a *converted* form (Bedrock
geometry + animation JSON derived from their model) inside an MIT-licensed public repository that
ships a jar. Record the grant and the required attribution text in this file, then remove the three
paths from `.gitignore`. Note that a CC Attribution claim on a third-party Sketchfab re-upload is
**not** a substitute for this — see the conflict section above.

**(b) Commission or build a replacement** dragon model that is **not derived from their design** —
not a retopology, recolour, cube-for-cube copy or tracing of it, and not a "close enough" variant of
the same creature. An independently designed dragon, swapped in through the same
`tools/dragon/install_dragon_asset.sh` mechanism. Everything else in this changeset is
asset-independent: the entity, config, AI, both attacks, the boss bar, the loot table, the `/spawn`
command and the renderer all work unchanged against any GeckoLib model with an equivalent bone rig.
Only the three generated files change.

If neither has happened, the answer is **no distribution** — see the warning at the top of this file.

### If you are the author

If you are GundunUkan, or already hold written permission, say so: drop the three paths from
`.gitignore`, record the attribution required by the licence here, and this file's warning can come
down.

### Build-time guard

`.gitignore` keeps these files out of **git**; it does nothing about **jars**. `processResources`
copies whatever is in `src/main/resources`, so once `install_dragon_asset.sh` has run locally, every
`./gradlew build` embeds all three files — this was observed happening even on a branch containing no
dragon code at all, which is precisely how an unlicensed asset escapes unnoticed.

So `build.gradle` adds a `verifyNoUnlicensedAssets` task wired into `publish`:

- `./gradlew build` still works, because testing the mob requires the asset present
- `./gradlew publish` **fails** while any of the three files exist, listing them

Remove the files to publish, or resolve the licence and delete the guard.

### Note on `dist/`

This repo commits a built jar to `dist/` because release-asset upload is blocked in the development
environment. **That jar has deliberately not been refreshed for this change.** Once the asset files
are installed locally, `./gradlew build` embeds all three into `build/libs/rasengan-1.4.1.jar`, so
copying that into `dist/` would commit the unlicensed asset via the jar and defeat the `.gitignore`
entries entirely. Leave `dist/` alone until the licence is resolved.

## Third-party code dependency (this one is clear)

- **GeckoLib 5.5.2** by Tslat — **MIT licensed** (stated in its own `neoforge.mods.toml`).
  Declared as a required dependency; not bundled. https://github.com/bernie-g/geckolib
