#!/usr/bin/env bash
# Drives a headless dedicated server through the physical-accuracy trials.
#
# Commands are emitted with real sleeps between them: the server drains its whole console queue
# inside one tick, so piping them as a block would run every trial simultaneously.
set -u

cd "$(dirname "$0")/../.."

JP="/opt/toolchains/.local/share/mise/installs/java/21.0.2,/opt/toolchains/.local/share/mise/installs/java/25.0.2"

mkdir -p run/server
printf 'eula=true\n' > run/server/eula.txt
# pause-when-empty-seconds=0 is mandatory: an empty server otherwise stops ticking entities, so
# nothing moves and every selector comes back empty.
printf 'online-mode=false\nlevel-name=world\npause-when-empty-seconds=0\nlevel-type=minecraft:flat\nspawn-protection=0\n' > run/server/server.properties

# Install the trace datapack. It lives under tools/ because run/ is gitignored, and it has to be
# in place before the world loads for the #minecraft:tick tag to be picked up.
mkdir -p run/server/world/datapacks/trace
cp -r tools/verify/trace-datapack/. run/server/world/datapacks/trace/

emit() {
  # Startup takes ~95s on a cold JVM with the mod loading.
  sleep 120
  echo "datapack list"
  sleep 1

  # Narrow forceload corridor: 2 chunks wide by 23 long = 46 chunks, well under the 256 cap,
  # and long enough to cover a ~230 block knockback flight.
  echo "forceload add 0 -16 31 336"
  sleep 2

  # ---------------- Trial 1: straight-line flight, Rasen Shuriken ----------------
  echo "say MARK_TRAJECTORY_SHURIKEN"
  sleep 1
  echo "summon rasengan:rasengan_projectile 8 220 2 {Ability:1,Motion:[0.0,0.0,1.05]}"
  sleep 6
  echo "kill @e[type=rasengan:rasengan_projectile]"
  sleep 1

  # ---------------- Trial 2: straight-line flight, Rasengan ----------------
  echo "say MARK_TRAJECTORY_RASENGAN"
  sleep 1
  echo "summon rasengan:rasengan_projectile 8 220 2 {Ability:0,Motion:[0.0,0.0,0.85]}"
  sleep 6
  echo "kill @e[type=rasengan:rasengan_projectile]"
  sleep 1

  # ---------------- Trial 3: horizontal convergence with gravity disabled ----------------
  # NoGravity WITHOUT NoAI: NoAI stops travel(), so the launch velocity would be set but never
  # integrated. With gravity off the entity never lands, so this trial measures ONLY the
  # horizontal convergence limit vx/(1-0.91); it cannot show a parabola.
  echo "say MARK_KNOCKBACK_NOGRAVITY"
  sleep 1
  echo "kill @e[type=minecraft:iron_golem]"
  sleep 1
  echo "summon minecraft:iron_golem 8 230 8 {NoGravity:1b}"
  sleep 1
  echo "summon rasengan:rasengan_projectile 8 230 2 {Ability:1,Motion:[0.0,0.0,1.05]}"
  sleep 12
  echo "say MARK_KNOCKBACK_NOGRAVITY_END"
  sleep 1
  echo "kill @e[type=minecraft:iron_golem]"
  sleep 1

  # ---------------- Trials: real ballistic arc, four independent trials ----------------
  # Gravity ENABLED and standing on the superflat surface (top of grass = y -60), so the arc is
  # a genuine projectile-motion curve and the entity actually lands. This is the only setup that
  # can verify the parabola and a real landing distance.
  for trial in 1 2 3 4; do
    echo "say MARK_BALLISTIC_TRIAL_${trial}"
    sleep 1
    echo "kill @e[type=minecraft:iron_golem]"
    sleep 1
    echo "summon minecraft:iron_golem 8 -60 8 {}"
    sleep 2
    echo "summon rasengan:rasengan_projectile 8 -59 2 {Ability:1,Motion:[0.0,0.0,1.05]}"
    # Peak ~20 blocks, airtime ~2.3s; allow generous margin for the descent and settling.
    sleep 12
    echo "say MARK_BALLISTIC_TRIAL_${trial}_END"
    sleep 2
  done

  echo "say MARK_DONE"
  sleep 2
  echo "stop"
  sleep 25
}

emit | ./gradlew runServer --no-daemon --console=plain \
  -Porg.gradle.java.installations.paths="$JP" \
  > /projects/sandbox/logs/server-trace.log 2>&1

echo "server exited with $?"
wc -l /projects/sandbox/logs/server-trace.log
