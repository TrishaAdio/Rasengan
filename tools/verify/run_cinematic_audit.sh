#!/usr/bin/env bash
# Pure-maths audit of the summoning cinematic rebuild.
#
# Needs no server, no display and no sound device - everything it measures is a pure function of
# elapsed time. That is the whole reason this is a separate harness from run_summon_test.sh: it runs
# in seconds instead of needing a ~135 s server boot, so it is the one to run while iterating.
#
# Covers: the five-stage timeline, the wing-downbeat alignment, the camera release blend and distance
# taper, the smoke particle's size and opacity curves, and the obscuration measurement.
#
# The harness is NOT part of the mod jar - it is compiled to a scratch directory against
# build/classes and run from there, never staged into src/.
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT="$(pwd)"
JP="/opt/toolchains/.local/share/mise/installs/java/21.0.2,/opt/toolchains/.local/share/mise/installs/java/25.0.2"
OUT="${1:-tools/verify/evidence/cinematic-audit.txt}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "building the mod classes..."
./gradlew classes --no-daemon --console=plain \
  -Porg.gradle.java.installations.paths="$JP" -q

# The audit needs the same classpath the mod compiles against. GeckoLib is resolved as a mod
# dependency and is not on sourceSets.main.runtimeClasspath, so it is appended explicitly - without
# it, loading DragonEntity fails with "cannot access GeoEntity".
CP="$(./gradlew printAuditClasspath -q --no-daemon --console=plain \
      -Porg.gradle.java.installations.paths="$JP" | tail -1)"
GECKOLIB="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}" -name 'geckolib-*.jar' ! -name '*sources*' \
            2>/dev/null | head -1)"
if [ -n "$GECKOLIB" ]; then
  CP="$CP:$GECKOLIB"
fi

JAVA_HOME_25="/opt/toolchains/.local/share/mise/installs/java/25.0.2"
"$JAVA_HOME_25/bin/javac" -nowarn -cp "$CP" -d "$WORK" tools/verify/SummonCinematicAudit.java

mkdir -p "$(dirname "$OUT")"
set +e
"$JAVA_HOME_25/bin/java" -cp "$WORK:$CP" SummonCinematicAudit | tee "$OUT"
RC=${PIPESTATUS[0]}
set -e

echo
echo "evidence written to $OUT"
exit "$RC"
