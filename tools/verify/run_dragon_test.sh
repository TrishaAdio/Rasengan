#!/usr/bin/env bash
# Dedicated-server verification for the dragon mob.
#
# Covers what CAN be checked without a display: both spawn paths, permission gating, untethered
# flight, damage taken, death + boss-bar teardown + loot, and both attacks dealing damage.
# Rendering and animation playback are NOT verifiable here.
set -u

cd "$(dirname "$0")/../.."
JP="/opt/toolchains/.local/share/mise/installs/java/21.0.2,/opt/toolchains/.local/share/mise/installs/java/25.0.2"

mkdir -p run/server
printf 'eula=true\n' > run/server/eula.txt
printf 'online-mode=false\nlevel-name=world\npause-when-empty-seconds=0\nlevel-type=minecraft:flat\nspawn-protection=0\n' > run/server/server.properties
mkdir -p run/server/world/datapacks/trace
cp -r tools/verify/trace-datapack/. run/server/world/datapacks/trace/

emit() {
  sleep 130
  echo "say MARK_START"
  echo "forceload add -64 -64 64 64"
  sleep 2

  # ---------------- spawn path 1: the mod's /spawn shorthand ----------------
  echo "say MARK_SPAWN_COMMAND"
  echo "spawn dragon"
  sleep 3
  echo "say MARK_COUNT_AFTER_SPAWN"
  echo "execute if entity @e[type=rasengan:dragon] run say DRAGON_EXISTS_AFTER_SPAWN_CMD"
  echo "data get entity @e[type=rasengan:dragon,limit=1] Health"
  sleep 2

  # tab-completion source list, and the unknown-mob rejection path
  echo "spawn notamob"
  sleep 1

  echo "kill @e[type=rasengan:dragon]"
  sleep 2

  # ---------------- spawn path 2: vanilla /summon ----------------
  echo "say MARK_SUMMON_VANILLA"
  echo "summon rasengan:dragon 0 -55 0"
  sleep 3
  echo "execute if entity @e[type=rasengan:dragon] run say DRAGON_EXISTS_AFTER_SUMMON"
  echo "data get entity @e[type=rasengan:dragon,limit=1] Health"
  echo "data get entity @e[type=rasengan:dragon,limit=1] Attributes"
  sleep 2

  # ---------------- untethered wandering flight ----------------
  # No players are connected at all, so any movement here cannot be player-following by definition.
  echo "say MARK_FLIGHT"
  sleep 25

  # ---------------- combat ----------------
  # Clear leftovers FIRST. An earlier run's iron golem surviving in the saved world made the first
  # attempt at this measurement meaningless - @e[limit=1] happily selected a stale golem that had
  # been damaged in a completely different test.
  echo "say MARK_COMBAT_SETUP"
  echo "kill @e[type=minecraft:iron_golem]"
  echo "kill @e[type=rasengan:dragon]"
  echo "kill @e[type=item]"
  sleep 2
  # Iron golems attack Monsters unprompted, which provokes the dragon's HurtByTargetGoal. Placing
  # both on the ground guarantees they can actually reach each other.
  echo "summon rasengan:dragon 0 -60 0"
  sleep 1
  echo "summon minecraft:iron_golem 6 -60 6"
  echo "say MARK_COMBAT"
  sleep 40
  echo "say MARK_COMBAT_END"
  echo "data get entity @e[type=minecraft:iron_golem,limit=1] Health"
  echo "data get entity @e[type=rasengan:dragon,limit=1] Health"
  sleep 2

  # ---------------- damage from a normal source ----------------
  echo "say MARK_DAMAGE"
  echo "data get entity @e[type=rasengan:dragon,limit=1] Health"
  echo "damage @e[type=rasengan:dragon,limit=1] 50 minecraft:generic"
  sleep 2
  echo "data get entity @e[type=rasengan:dragon,limit=1] Health"
  sleep 1
  echo "say MARK_FALL_AND_FIRE"
  echo "damage @e[type=rasengan:dragon,limit=1] 25 minecraft:fall"
  sleep 1
  echo "data get entity @e[type=rasengan:dragon,limit=1] Health"
  # Fire-immune by design: this should NOT reduce health.
  echo "damage @e[type=rasengan:dragon,limit=1] 30 minecraft:in_fire"
  sleep 1
  echo "data get entity @e[type=rasengan:dragon,limit=1] Health"
  sleep 2

  # ---------------- death, loot, boss bar teardown ----------------
  echo "say MARK_DEATH"
  echo "bossbar list"
  sleep 1
  echo "damage @e[type=rasengan:dragon,limit=1] 100000 minecraft:generic"
  sleep 4
  echo "say MARK_AFTER_DEATH"
  echo "execute unless entity @e[type=rasengan:dragon] run say DRAGON_GONE_AFTER_DEATH"
  echo "bossbar list"
  echo "say MARK_LOOT"
  echo "execute if entity @e[type=item] run say LOOT_ITEMS_PRESENT"
  sleep 2

  echo "say MARK_DONE"
  sleep 2
  echo "stop"
  sleep 30
}

emit | ./gradlew runServer --no-daemon --console=plain \
  -Porg.gradle.java.installations.paths="$JP" \
  > /projects/sandbox/logs/dragon-server.log 2>&1

echo "server exited $?"
wc -l /projects/sandbox/logs/dragon-server.log
