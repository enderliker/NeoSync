# Launcher integration

NeoSync requires its own installed runtime, not an ordinary NeoForge instance.
The Minecraft version is 1.21.1 and Java 21 is required. Launcher accounts remain
in the launcher. NeoSync never copies session tokens or reconstructs the game's
current command line to restart it.

## Prism Launcher

Install the NeoSync client with the project's installer into a stable local game
installation. Then create a fresh Prism instance using Python 3:

```sh
python3 scripts/configure_prism.py \
  --installation /path/to/installed-client \
  --prism-root /path/to/PrismLauncher \
  --java /path/to/java-21/bin/java \
  --prism-executable /path/to/prismlauncher
```

On Windows use `python`, `java.exe` and `prismlauncher.exe`. Use `--version` if
multiple NeoSync versions are installed and `--instance-id` to choose a new
instance directory. Existing instances are never replaced. The application root
must use its normal `instances` subdirectory. Paths containing symbolic links,
control characters or `${...}` placeholders are rejected.

The tool verifies the installed library sizes and hashes, copies runtime
classpath libraries into the new instance, and references the original versioned
installation for generated game artifacts and module paths. Keep that installation
in place. This is local setup: do not export the resulting runtime as a modpack or
release artifact. It may refer to generated Minecraft artifacts. No account files,
worlds or personal settings are copied.

Launch the new NeoSync instance from Prism. After accepting a server's mods, open
**Restart instructions > Prepare Prism instance**. NeoSync verifies the profile,
creates an instance for that exact revision, then offers **Later** or **Close and
launch**. Later leaves the instance available for manual selection. Automatic
handoff waits up to two minutes for the current Minecraft process to exit, verifies
again, and invokes Prism's supported `--dir` and `--launch` options. Prism retains
control of account selection and login. A launcher prompt may require interaction.

Generated server instances have a pre-launch command using a small JDK-only helper.
It checks the approved manifest, consent record, marker and complete mod inventory
before FML loads mods. An extra, missing, modified or linked file blocks that launch.
The check does not sandbox mods or defeat a local attacker who can modify both the
launcher and its verification record. Other launcher paths still perform the
existing post-load verification. Do not remove the pre-launch command if you want
this check. Source edits made in Prism may require manual launch or a new instance.

Prism instances for earlier revisions remain available. Use **NeoSync profiles**
to verify and select an older revision, then select its existing Prism instance.
Current server requirements are checked again before reconnecting.

## SKlauncher and Minecraft Launcher

Run the NeoSync installer against the launcher's Minecraft installation directory.
Restart the launcher so it discovers the custom installed version. In SKlauncher,
open **Installations Manager > New Installation**, select the installed version
named `NeoSync-...`, and set **Game Directory** to the exact prepared directory
shown by NeoSync. In Minecraft Launcher use **Installations > New installation**
and the same custom version and game-directory setting. Keep Java 21 selected.

The built-in **Install NeoForge** option installs upstream NeoForge, so it does
not select NeoSync. Never repair a missing NeoSync runtime by selecting an upstream
universal JAR. If the custom version is not offered, check the installer target
and restart the launcher.

These launchers use the manual close/reopen flow. No undocumented restart command
or automatic profile-file mutation is used. On a new launch NeoSync verifies the
selected profile and offers to review current requirements and reconnect.

## Other launchers

A launcher can use the manual route when it supports installed Mojang-style
custom version metadata, its JVM/module arguments and an explicit game directory.
Do not claim support merely because it supports NeoForge. MultiMC-derived launchers
may use Prism-like components but require their own runtime acceptance; the
automatic adapter intentionally invokes Prism only.

## Sources and validation scope

Official pages consulted with Tavily on September 27, 2026:

- [Prism command-line interface](https://prismlauncher.org/wiki/getting-started/command-line-interface/)
- [Prism component editor](https://prismlauncher.org/wiki/help-pages/instance-version/)
- [Prism pre-launch commands](https://prismlauncher.org/wiki/help-pages/custom-commands/)
- [SKlauncher manual NeoForge installation](https://docs.skmedix.pl/modding/mod-loaders/neoforge)
- [SKlauncher game directories](https://docs.skmedix.pl/faq/launcher-related)

The adapter also follows Prism's `Library.cpp`, `OneSixVersionFormat.cpp` and
`MinecraftInstance.cpp` source contracts. Unit tests verify isolated export,
repeated selection and rejection of modified or additional mod files. Documentation
and source inspection alone do not certify a launcher. Installed execution results,
including launcher versions and platform limits, are recorded in [Phase 7](phase-7.md).
