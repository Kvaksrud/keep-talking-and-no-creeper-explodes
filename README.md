# Keep Talking and No Creeper Explodes

A mod for Minecraft (Fabric and NeoForge). Get within 1 block of a creeper and right-click it with an empty hand or a tool (a sword, axe, pickaxe, shovel or hoe, by default). A bomb panel opens with three wires and a 15-second timer, and only one wire is safe (the odds are a [setting](#settings)).

- **Right wire:** the creeper is defused for good. It never hisses or explodes again, even if lit with flint and steel or charged by lightning. It keeps following you around, with a tear under its right eye.
- **Wrong wire, or the timer runs out:** it explodes on the spot, 5× bigger than a normal creeper (a charged one hits 10× a normal creeper's blast).
- **Walking away (Esc, or getting out of reach) while there's still time:** the bomb beeps frantically and goes off within 3 seconds, just as big. Run! Right-clicking it again won't help.

While the panel is open:
- You and the creeper are both frozen in place, and the creeper stares at you.
- The bomb beeps like a CS bomb, speeding up as time runs out. Everyone nearby hears it.
- Only one player can defuse a creeper at a time. If you die or disconnect, the creeper is released straight away.

Right-clicking an already-defused creeper just tells you so. The game doesn't pause while the panel is open.

## Settings
The first start writes `config/nocreeperexplodes.properties`. Only the server's copy counts (in singleplayer, your own). Changes take effect the next time a world is opened or the server starts. Values that aren't numbers fall back to the default, out-of-range values are clamped, and list entries that aren't item ids or tags are skipped.

| Setting | Default | Allowed | What it does |
|---|---|---|---|
| `defuse_distance` | `1.0` | 0–16 | Blocks of empty space allowed between you and the creeper to start cutting. Moving more than half a block further away while the panel is open counts as walking away. You still need to reach the creeper with a right-click (3 blocks in survival). |
| `defuse_success_chance_percent` | `33.33` | 0–100 | Chance that the wire you cut is the safe one. The default is one wire out of three. |
| `explosion_size_multiplier` | `5.0` | 0–20 | How many times bigger than a normal creeper's blast a failed defuse is (wrong wire, out of time, or walking away). `1.0` is a normal creeper's blast. A charged creeper doubles it. |
| `defuse_tools` | `#minecraft:swords, #minecraft:axes, #minecraft:pickaxes, #minecraft:shovels, #minecraft:hoes` | Item ids (`minecraft:shears`) and item tags (`#minecraft:swords`), separated by commas | What you can hold in your main hand, besides nothing, when you right-click a creeper to cut its wires. Leave it empty to allow only an empty hand. Avoid items that do something when used on a creeper, like flint and steel. |

## Download
Get the jar for your Minecraft version and mod loader from [Releases](https://github.com/Kvaksrud/keep-talking-and-no-creeper-explodes/releases): `ktance-<fabric or neoforge>-<mod version>+mc<minecraft version>.jar`. Put it in your `mods` folder (on Fabric, together with [Fabric API](https://modrinth.com/mod/fabric-api)). Install it on the server and on every player's client.

| Minecraft | Loader | Branch | Needs | Java |
|---|---|---|---|---|
| 26.3 | Fabric | `main` | Fabric Loader 0.19.5+, Fabric API 0.162.0+26.3+ | 25 |
| 26.2 | Fabric | `mc/26.2` | Fabric Loader 0.19.5+, Fabric API 0.161.0+26.2+ | 25 |
| 26.1.x | Fabric | `mc/26.1` | Fabric Loader 0.19.5+, Fabric API 0.145.1+26.1+ | 25 |
| 26.3 | NeoForge | `neoforge/26.3` | NeoForge 26.3.0.77+ | 25 |
| 1.21.1 | Fabric | `mc/1.21.1` | Fabric Loader 0.16.10+, Fabric API 0.116.17+1.21.1+ | 21 |
| 1.21.1 | NeoForge | `neoforge/1.21.1` | NeoForge 21.1.259+ | 21 |

## Development requirements
- JDK 25 (`brew install openjdk@25`, then `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`)

## Building and running
On this branch (`neoforge/1.21.1`):
```bash
./gradlew build              # build/libs/ktance-neoforge-<version>+mc1.21.1.jar; also runs the unit tests
./gradlew runClient          # dev client with the mod loaded
./gradlew runGameTestServer  # automated in-game tests on a headless test server (no window)
```

The 1.21.1 branches download Java 21 for Gradle on their own the first time, and here `./gradlew runClientSmokeTest` plays through the client side in a game window (screenshots in `build/run/clientSmokeTest/screenshots`). On `mc/1.21.1` the in-game tests run with `./gradlew runGameTest`. On the 26.x branches it's `./gradlew runClientGameTest`, which opens a game window, and the jar is `ktance-fabric-<version>+mc<mc>.jar`.

The unit tests (`./gradlew test`) cover the settings file (defaults, custom values, typos, out-of-range values, missing keys) and the bomb timer (the 3-second walk-away fuse, the beep speed). The game tests play through:
- the settings file being written with the defaults;
- being too far away, and a longer `defuse_distance` letting you start from further away;
- an item on the `defuse_tools` list (a shovel) starting the minigame, one that isn't (a stick) doing nothing, and a custom list;
- a frozen, beeping creeper that a second player can't take over;
- a defuser who leaves the game or dies, which releases the creeper;
- defusing (at a 100% chance);
- trying to relight it or defuse it again;
- saving and reloading;
- the 5× blasts, for a wrong wire (at a 0% chance) and for running out of time;
- walking away with Esc, and getting pushed out of reach: frantic beeping, no way back, and a 5× blast within 3 seconds;
- a 1× `explosion_size_multiplier` giving an ordinary creeper blast.

The headless test server has no sound, so on this branch the game tests don't count the beeps. On 26.x the client game test saves screenshots to `build/run/clientGameTest/screenshots/nce_*.png`.

## Releases
`main` always targets the newest Minecraft version on Fabric, and new work happens there. Older Fabric versions live on `mc/<version>` branches, and NeoForge on `neoforge/<version>` branches (see the table above), for as long as they're supported. When a new Minecraft version comes out, branch the current `main` off as `mc/<old version>`, then update `main`. CI builds every push and pull request and runs the unit tests (on this branch, the game tests too). On `main`, `mc/*` and `neoforge/*`, the first time it sees a new `mod_version` it does three things:
- tags the commit `v<mod_version>+mc<minecraft_version>` (with `-neoforge` on the end for NeoForge);
- creates a GitHub release with the jar;
- lists the Minecraft, mod loader and Java versions in the release notes.

To release, add the new version to `CHANGELOG.md` (CI puts its section in the release notes), bump `mod_version` in `gradle.properties`, and push the branch. Port gameplay changes to the other version branches with `git cherry-pick` (the 1.21.1 branches need hand-porting, since the APIs differ).

## Art and sound
- **Textures:** `tools/gen_textures.py` draws every texture using only the Python standard library. After changing it, run `python3 tools/gen_textures.py` to rewrite the PNGs in `src/main/resources/assets/nocreeperexplodes/`. It also writes enlarged previews to `build/texture-preview/`. The wire layout constants in `WireCutScreen` mirror the ones in the script.
- **Logo:** `tools/gen_logo.py` draws `branding/logo.png`, the 512×512 project logo used on CurseForge (a crying creeper behind a "no" sign).
- **Sound:** `tools/gen_sounds.py` synthesizes the bomb beep, then encodes it to mono Ogg Vorbis with `oggenc` (`brew install vorbis-tools`). Run `python3 tools/gen_sounds.py` to regenerate it.

## How it works
- `NoCreeperExplodes`: the right-click handler, the defuse session, and the two network payloads. The server rolls whether a cut is safe (`defuse_success_chance_percent`), so the client can't cheat.
- `NoCreeperExplodesConfig`: reads and writes `config/nocreeperexplodes.properties`.
- `DefuseSession`: the bomb timer on a creeper. It holds who is cutting, when it goes off, and whether they walked away (then it goes off at most 3 seconds later).
- The defused flag is a data attachment. It is saved with the creeper and synced to clients.
- `CreeperMixin`:
  - forces the swell direction to -1 and blocks `ignite()` and `explodeCreeper()` while the creeper is defused or a bomb timer is running on it;
  - plays the beep, faster and faster, and frantically once walked away from;
  - adds `HoldStillWhileDefusingGoal`, which freezes the creeper;
  - runs the bomb timer, blowing the creeper up at zero, and notices when the defuser gets out of reach;
  - scales the explosion radius by `explosion_size_multiplier` when a defuse fails.
- `CreeperTearLayer` redraws the creeper model with a mostly transparent tear texture, so it works with any creeper skin.
