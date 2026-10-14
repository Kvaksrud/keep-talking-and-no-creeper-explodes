# Changelog

## 1.1.0

### New
- **Defuse with a tool.** Besides an empty hand, you can now right-click a creeper while holding a sword, axe, pickaxe, shovel or hoe. Which items count is a setting.
- **Walking away sets it off.** With time still on the clock, pressing Esc or getting pushed out of reach no longer calls it off. The bomb beeps frantically and goes off within 3 seconds, just as big as a failed defuse. Nobody can pick it back up: "Too late, it's about to blow!"
- **Settings file.** The first start writes `config/nocreeperexplodes.properties`. Only the server's copy counts (in singleplayer, your own). The defaults play like 1.0.0.
  - `defuse_distance`: how close you must be to start cutting (default 1 block).
  - `defuse_success_chance_percent`: the chance that the wire you cut is safe (default 33.33, one wire in three).
  - `explosion_size_multiplier`: how much bigger than a normal creeper's blast a failed defuse is (default 5).
  - `defuse_tools`: what you may hold besides an empty hand (default: swords, axes, pickaxes, shovels and hoes).
- **More versions:** Minecraft 1.21.1 on Fabric and NeoForge, and Minecraft 26.3 on NeoForge.

## 1.0.0

First release, for Minecraft 26.1, 26.2 and 26.3 on Fabric.

- Get within 1 block of a creeper and right-click it with an empty hand: a bomb panel opens with three wires and a 15-second timer.
- **The right wire** defuses the creeper for good. It never hisses or explodes again, and it gets a tear under its right eye.
- **A wrong wire, or running out of time,** blows it up 5× bigger than a normal creeper.
- You and the creeper are frozen while you work, and the bomb beeps faster and faster as time runs out.
- Only one player can defuse a creeper at a time.
