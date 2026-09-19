# Runs once per server tick via #minecraft:tick.
#
# One reading per tick is the whole point: console commands piped to the server are all drained
# inside a single tick, so piping `data get` repeatedly cannot sample a trajectory.
#
# `data get` is not used here because ServerFunctionManager executes tag functions with a
# suppressed-output command source, so command feedback never reaches the log. `say` broadcasts
# instead of reporting feedback, and DedicatedServer does log broadcasts - so the values are
# routed out through a macro-expanded `say`.
execute as @e[type=rasengan:rasengan_projectile] run function trace:log_proj with entity @s
execute as @e[type=minecraft:iron_golem] run function trace:log_golem with entity @s
execute as @e[type=rasengan:dragon] run function trace:log_dragon with entity @s

# Test-harness aid, not behaviour under test: a dragon cruising at ~0.53 blocks/tick leaves any
# forceloadable area (capped at 256 chunks) within about 20 seconds, and an entity in an unloaded
# chunk simply stops ticking - its trace then shows position, velocity AND rotation frozen at
# identical values, which reads exactly like a stuck mob but is not one. Recentring it keeps the
# observation window inside loaded chunks. Triggers rarely; the analyser discards the jump.
execute positioned 0.0 -30.0 0.0 as @e[type=rasengan:dragon,distance=90..] run tp @s 0 -30 0
