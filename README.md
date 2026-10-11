# Keep Talking and No Creeper Explodes

A Fabric mod for Minecraft 26.2. Get within 1 block of a creeper and right-click it with an empty hand. A bomb panel opens with three wires and a 15-second timer, and only one wire is safe.

- **Right wire:** the creeper is defused for good. It never hisses or explodes again, even if lit with flint and steel or charged by lightning. It keeps following you around, with a tear under its right eye.
- **Wrong wire, or the timer runs out:** it explodes on the spot, 5× bigger than a normal creeper (a charged one hits 10× a normal creeper's blast).
- **Esc while there's still time:** you back out, and the creeper is a creeper again.

While the panel is open:
- You and the creeper are both frozen in place, and the creeper stares at you.
- The bomb beeps like a CS bomb, speeding up as time runs out. Everyone nearby hears it.
- Only one player can defuse a creeper at a time. If you die or disconnect, the creeper is released straight away.

Right-clicking an already-defused creeper just tells you so. The game doesn't pause while the panel is open.

## Download
Get the jar from [Releases](https://github.com/Kvaksrud/keep-talking-and-no-creeper-explodes/releases). Put it in your `mods` folder together with [Fabric API](https://modrinth.com/mod/fabric-api).

| Requirement | Version |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.5 or newer |
| Fabric API | 0.161.0+26.2 or newer |
| Java | 25 |

## Development requirements
- JDK 25 (`brew install openjdk@25`, then `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`)

## Building and running
```bash
./gradlew build              # build/libs/keep-talking-and-no-creeper-explodes-fabric-<version>+mc<mc>.jar
./gradlew runClient          # dev client with the mod loaded
./gradlew runClientGameTest  # automated in-game test (opens a game window)
```

The client game test plays through:
- being too far away,
- cancelling,
- a frozen, beeping creeper that a second player can't take over,
- defusing,
- trying to relight it or defuse it again,
- saving and reloading,
- the 5× blasts, for a wrong wire and for running out of time.

It saves screenshots to `build/run/clientGameTest/screenshots/nce_*.png`.

## Releases
CI builds every push and pull request. On `main`, the first time it sees a new `mod_version` it does three things:
- tags the commit `v<mod_version>+mc<minecraft_version>`;
- creates a GitHub release with the jar;
- lists the Minecraft, Fabric Loader, Fabric API and Java versions in the release notes.

To release, bump `mod_version` in `gradle.properties` and push to `main`.

## Art and sound
- **Textures:** `tools/gen_textures.py` draws every texture using only the Python standard library. After changing it, run `python3 tools/gen_textures.py` to rewrite the PNGs in `src/main/resources/assets/nocreeperexplodes/`. It also writes enlarged previews to `build/texture-preview/`. The wire layout constants in `WireCutScreen` mirror the ones in the script.
- **Sound:** `tools/gen_sounds.py` synthesizes the bomb beep, then encodes it to mono Ogg Vorbis with `oggenc` (`brew install vorbis-tools`). Run `python3 tools/gen_sounds.py` to regenerate it.

## How it works
- `NoCreeperExplodes`: the right-click handler, the defuse session, and the two network payloads. The server picks the safe wire, so the client can't cheat.
- The defused flag is a Fabric data attachment. It is saved with the creeper and synced to clients.
- `CreeperMixin`:
  - forces the swell direction to -1 and blocks `ignite()` and `explodeCreeper()` while the creeper is defused or being defused;
  - plays the beep;
  - adds `HoldStillWhileDefusingGoal`, which freezes the creeper;
  - runs the bomb timer, blowing the creeper up at zero;
  - scales the explosion radius by 5 when a defuse fails.
- `CreeperTearLayer` redraws the creeper model with a mostly transparent tear texture, so it works with any creeper skin.
