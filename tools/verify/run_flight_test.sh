#!/usr/bin/env bash
# Flight-quality verification for the dragon.
#
# Three scenarios, all on a dedicated server:
#   A  open sky           - sustained smooth flight, no freezing
#   B  dense terrain      - does not wedge against geometry
#   C  three dragons      - independent flight, no shared state
set -u

cd "$(dirname "$0")/../.."
JP="/opt/toolchains/.local/share/mise/installs/java/21.0.2,/opt/toolchains/.local/share/mise/installs/java/25.0.2"

mkdir -p run/server
# Start from a clean world every time. The saved world persists between runs, so terrain built by a
# previous run's obstacle test was still standing during the next run's "open sky" test - the same
# class of contamination as a leftover iron golem skewing a damage measurement.
rm -rf run/server/world
printf 'eula=true\n' > run/server/eula.txt
printf 'online-mode=false\nlevel-name=world\npause-when-empty-seconds=0\nlevel-type=minecraft:flat\nspawn-protection=0\n' > run/server/server.properties
mkdir -p run/server/world/datapacks/trace
cp -r tools/verify/trace-datapack/. run/server/world/datapacks/trace/

emit() {
  sleep 130
  echo "say MARK_BOOT"
  # -112..112 is chunks -7..7 = 225 chunks, just under the 256-chunk forceload cap.
  echo "forceload add -112 -112 112 112"
  echo "gamerule randomTickSpeed 0"
  sleep 3
  echo "kill @e[type=rasengan:dragon]"
  sleep 1

  # ---------------- A: open sky ----------------
  echo "say MARK_OPENSKY"
  echo "summon rasengan:dragon 0 -30 0"
  sleep 45
  echo "kill @e[type=rasengan:dragon]"
  sleep 2

  # ---------------- B: dense terrain ----------------
  # Build obstacles in the flat world: a long tall wall, a second crossing wall, and pillars.
  # This is what a cliff face / building cluster looks like to the pathing and look-ahead code.
  echo "say MARK_BUILD"
  echo "fill 20 -60 -60 26 -10 60 minecraft:stone"
  echo "fill -60 -60 20 60 -10 26 minecraft:stone"
  echo "fill -40 -60 -40 -34 -20 -34 minecraft:stone"
  echo "fill 40 -60 -40 46 -25 -34 minecraft:stone"
  echo "fill -10 -60 -10 -4 -15 -4 minecraft:stone"
  sleep 4
  echo "say MARK_TERRAIN"
  # Spawn low and close to the wall, facing it, so it must climb or turn to survive.
  echo "summon rasengan:dragon 10 -52 0"
  sleep 55
  echo "kill @e[type=rasengan:dragon]"
  sleep 2

  # ---------------- C: three dragons at once ----------------
  echo "say MARK_MULTI"
  echo "summon rasengan:dragon -30 -30 -30"
  echo "summon rasengan:dragon 0 -35 30"
  echo "summon rasengan:dragon 30 -28 0"
  sleep 45
  echo "say MARK_MULTI_END"
  echo "kill @e[type=rasengan:dragon]"
  sleep 2

  echo "say MARK_DONE"
  sleep 2
  echo "stop"
  sleep 30
}

emit | ./gradlew runServer --no-daemon --console=plain \
  -Porg.gradle.java.installations.paths="$JP" \
  > /projects/sandbox/logs/flight-server.log 2>&1

echo "server exited $?"
wc -l /projects/sandbox/logs/flight-server.log
