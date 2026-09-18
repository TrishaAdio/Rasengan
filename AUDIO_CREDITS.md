# Audio provenance

Recorded here so the origin of every shipped sound is documented rather than implicit.

| File | Origin | Notes |
|---|---|---|
| `rasenshuriken_form.ogg` | **Synthesised** by `tools/generate_shuriken_sound.py` | Original. Reproducible from the script. |
| `rasenshuriken_spin.ogg` | **Synthesised** by `tools/generate_shuriken_sound.py` | Original. Amplitude modulation locked to the renderer's rotation rate (10.82 Hz). |
| `rasengan_form.ogg` | **Imported from a source recording supplied by the project owner**, processed by `tools/import_rasengan_sound.py` | See the caveat below. |
| `rasengan_spin.ogg` | **Imported from a source recording supplied by the project owner**, processed by `tools/import_rasengan_sound.py` | See the caveat below. |

## Caveat on the imported Rasengan audio

The two `rasengan_*` files were derived from a third-party hosted audio file provided by the
project owner. What was and was not verified:

- **Checked:** the file carries no ID3 title/artist/album frames and none of the MP4/DASH container
  brands (`major_brand=dash`, `compatible_brands=iso6mp41`) that indicate audio demuxed from a
  streaming video. Two earlier candidate files *did* carry those brands and were rejected for that
  reason.
- **Not verified, and not verifiable from the file alone:** who authored the recording, and whether
  its licence permits redistribution inside this repository and its prebuilt jar.

The absence of a rip fingerprint is not proof of clear rights. Because this repository is public
under MIT and ships a compiled jar, redistribution rights matter. **The project owner is responsible
for confirming those rights.**

If the rights cannot be confirmed, there are two clean options:

1. Delete `rasengan_form.ogg` and `rasengan_spin.ogg`, then generate replacements the same way the
   shuriken audio was produced — `tools/generate_shuriken_sound.py` already demonstrates the
   approach and can be extended with a softer, rounder profile for Rasengan.
2. Keep the files out of version control (add them to `.gitignore`) and have each user run
   `tools/import_rasengan_sound.py <their-own-file>` locally. The mod loads whatever is present in
   `assets/rasengan/sounds/`, so a local-only asset works without the repository redistributing it.

## Other audio

Rasengan's impact and the shuriken's impact still layer **stock Minecraft sound events**
(`GENERIC_EXPLODE`, `GLASS_BREAK`, `WIND_CHARGE_BURST`, and similar). Those ship with the game and
are referenced by id, so nothing is redistributed.
