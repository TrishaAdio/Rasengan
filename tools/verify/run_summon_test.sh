#!/usr/bin/env bash
# Summoning Jutsu verification on a real dedicated server.
#
# The harness (SummonAudit.java) is NOT part of the mod. It is staged into
# src/main/java/dev/rasengan/verify/ for the duration of this run and deleted again afterwards,
# including on failure, so it can never leak into a commit or a published jar. It self-registers
# through @EventBusSubscriber, so no shipped file is edited.
set -u

cd "$(dirname "$0")/../.."
ROOT="$(pwd)"
JP="/opt/toolchains/.local/share/mise/installs/java/21.0.2,/opt/toolchains/.local/share/mise/installs/java/25.0.2"
STAGE="$ROOT/src/main/java/dev/rasengan/verify"
LOG="${1:-/projects/sandbox/logs/summon-server.log}"

cleanup() {
  rm -rf "$STAGE"
  python3 tools/verify/edit_lines.py restore 2>/dev/null || true
  echo "harness removed from src/"
}
trap cleanup EXIT

mkdir -p "$STAGE" "$(dirname "$LOG")" run/server
cp tools/verify/SummonAudit.java "$STAGE/SummonAudit.java"

# Clean world every run: leftover dragons or terrain from a previous run would contaminate the
# dragon counts and the flight measurement.
rm -rf run/server/world
printf 'eula=true\n' > run/server/eula.txt
printf 'online-mode=false\nlevel-name=world\npause-when-empty-seconds=0\nlevel-type=minecraft:flat\nspawn-protection=0\n' > run/server/server.properties

emit() {
  sleep 135
  echo "say MARK_BOOT"
  # Chunks -7..7 = 225 forceloaded chunks, under the 256 cap. Without this the synthetic players
  # sit in unloaded chunks and the dragon stops being ticked - which looks exactly like a bug.
  echo "forceload add -112 -112 112 112"
  echo "gamerule randomTickSpeed 0"
  echo "gamerule doMobSpawning false"
  sleep 4

  echo "say MARK_LINES"
  echo "summonaudit lines"
  sleep 2
  echo "say MARK_CODEC"
  echo "summonaudit codec"
  sleep 2

  echo "say MARK_RUN"
  echo "summonaudit run"
  # 0..8 setup, summon at 8, end at 78, fear expires ~180, interruption ~194,
  # teardown at 199 ticks. Plus the 140-tick fear duration that has to run out inside that.
  # ~200 ticks = 10s; 60s is a generous margin for a loaded server.
  sleep 95
  echo "say MARK_RUN_DONE"
  sleep 2

  # ---- /reload must genuinely RE-READ the file, not just still report 64 ----
  # Proving that requires changing the file while the server is up. Re-running "lines" after a
  # no-op reload would prove nothing: 64 before and 64 after is consistent with never reading it
  # again at all. So add a line, reload, and require the count to move.
  echo "say MARK_RELOAD_EDIT"
  python3 tools/verify/edit_lines.py add "MARK a line added while the server was running." >&2
  echo "reload"
  sleep 8
  echo "summonaudit lines 65"
  sleep 2

  # ---- a datapack must be able to override the file, and an extra file must stay silent ----
  # The datapack replaces rasengan:awakening with 3 lines and also adds an unrelated audit:extra
  # with 2. Expected outcome: 3 speakable, 5 loaded. That is the documented rule - lines_resource
  # picks one file - so the 2 extras must be loaded and NOT spoken.
  echo "say MARK_RELOAD_DATAPACK"
  python3 tools/verify/edit_lines.py datapack >&2
  echo "reload"
  sleep 8
  echo "datapack enable \"file/audit_extra\""
  sleep 2
  echo "reload"
  sleep 8
  echo "summonaudit lines 3"
  sleep 2

  # Put the shipped file back before anything else reads it.
  python3 tools/verify/edit_lines.py restore >&2

  echo "say MARK_REPORT"
  echo "summonaudit report"
  sleep 3
  echo "say MARK_DONE"
  sleep 2
  echo "stop"
  sleep 30
}

emit | ./gradlew runServer --no-daemon --console=plain \
  -Porg.gradle.java.installations.paths="$JP" \
  > "$LOG" 2>&1

echo "server exited $?"
wc -l "$LOG"
echo
grep -c "SUMMONAUDIT" "$LOG" || true
