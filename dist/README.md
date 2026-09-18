# Prebuilt jar

`rasengan-1.4.1.jar` — ready to install, no build needed.

Requires **Minecraft Java 26.1.2**, **NeoForge 26.1.2.109+**, and **Java 25**.

- **Client:** drop it into `.minecraft/mods/`
- **Server:** drop it into `mods/`

Required on both sides. To download a single file from GitHub, open it and use the
download button (or use the "Raw" link) — cloning the whole repo is not necessary.

Rebuild from source at any time with `./gradlew build`; the output lands in
`build/libs/`. This copy is committed only so the jar is downloadable directly,
since release-asset uploads were unavailable in the environment that produced it.
