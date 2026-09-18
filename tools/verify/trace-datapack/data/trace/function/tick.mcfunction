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
