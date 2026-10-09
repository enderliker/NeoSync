# Launcher integration

Unreleased October 8, 2026 development adds consented profile preparation for
SKlauncher 3.2, SKlauncher 4.0 Beta and Modrinth App, alongside the existing Prism
and Minecraft Launcher integrations. The prepared screen shows the exact directory
and **Later** before changing the launcher. SKlauncher and Modrinth require reopening
the launcher, selecting the prepared instance and pressing Play. Source and format
checks are not full launcher gameplay acceptance; see the
[development notes](development-sources-launchers.md).

NeoSync requires its own installed runtime, not an ordinary NeoForge instance.
The Minecraft version is 1.21.1 and Java 21 is required. Launcher accounts remain
in the launcher. NeoSync never copies session tokens or reconstructs the game's
current command line to restart it.

## Prism Launcher

Open Prism once, close it, then select **Prism Launcher > Install NeoSync** in
the NeoSync installer. The runtime and instance are installed automatically;
Python scripts are not required. Standard Windows installations use
`%APPDATA%\PrismLauncher`. Use **Choose another folder...** for a portable root
containing `prismlauncher.cfg` and its executable. The root must use its normal
`instances` subdirectory.

The installer verifies library sizes and hashes, copies local classpath libraries
and retains generated game artifacts under `<launcher-root>/neosync/runtime/<version>`.
Keep that directory in place. Existing instances, accounts, worlds and settings
are preserved. Linked paths, control characters and `${...}` are rejected.
Installed Minecraft runtimes must not be exported as release assets or modpacks.

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

The normal generated paths work when the Prism application root contains spaces.
A relocated profile whose relative path contains whitespace or quotes must use
manual activation; Prism's component argument format cannot represent it directly.

## SKlauncher 3.2 and Minecraft Launcher

Close the launcher, select **SKlauncher 3.2** or **Minecraft Launcher** in the
NeoSync installer, then **Install NeoSync**. It installs the runtime and creates
a separate installation with its game directory filled automatically. Reopen the
launcher, select `NeoSync-...` and press Play. Keep Java 21 selected.
Use **Choose another folder...** for an existing custom Minecraft directory.

The built-in **Install NeoForge** option installs upstream NeoForge, so it does
not select NeoSync. Never repair a missing NeoSync runtime by selecting an upstream
universal JAR. If the custom version is not offered, check the installer target
and restart the launcher.

After accepting a server's mods, choose **Prepare in SKlauncher 3.2** or
**Prepare in Minecraft Launcher**. NeoSync adds a separate installation to
`launcher_profiles.json`, using the installed NeoSync version and the exact verified
game directory. Existing installations and account files are preserved. Reopen the
launcher and select that installation, then press Play. NeoSync verifies the profile
and offers to review current requirements and reconnect. Manual selection remains
available if the local inventory cannot be edited.

## SKlauncher 4.0 Beta and Modrinth App

Open the launcher once, close it, then select **SKlauncher 4.0 Beta** or
**Modrinth App > Install NeoSync** in the NeoSync installer. It installs and
registers the native runtime automatically; Python scripts and manual imports
are not required.

Windows defaults are `%APPDATA%\.sklauncher` and `%APPDATA%\ModrinthApp`.
`THESEUS_CONFIG_DIR` is also recognized. Select another existing root with
**Choose another folder...**. Inventory and runtime data currently need to be
under one root. Separately relocated launcher data requires manual activation
or a launcher configuration using its default layout.
Libraries are verified and installed in versioned paths. Keep
`<launcher-root>/neosync/runtime/<version>`: FML resolves the generated client
and NeoSync universal JAR there. Accounts, worlds and settings are preserved.
Do not distribute this local runtime as a modpack.
Modrinth stores the local Java path arguments in instance launch overrides so its
metadata argument parser preserves spaces. Keep those generated overrides when
editing the instance. Verified shared libraries may remain after a failed import;
runtime metadata and instance registration are rolled back on registration failure.

Beta.8 completes the Minecraft client, applicable libraries and native JARs,
assets and logging before registering a Modrinth instance as installed. Downloads
use official HTTPS locations and size/hash verification; the installer reports
progress and checks available space. A new installation can require hundreds of
megabytes of assets. The local NeoSync libraries are non-downloadable in Modrinth
metadata so a launcher repair does not replace the fork.

The installer writes `neosync-runtime.json` beside the custom version metadata.
Preparing a server revision verifies that record, the local NeoSync runtime and
required Minecraft files, then publishes the revision's client JAR and native
directory before updating the instance inventory. Missing or changed official Minecraft resources
require rerunning the installer, which repairs them using verified staging and
restores their prior bytes if registration fails. Modified local NeoSync libraries
and metadata remain rejected. The server game-directory argument uses
Modrinth's own directory placeholder and a validated relative revision path so
spaces in the launcher root survive its argument parser. Unsupported relative
paths retain manual activation.

Reopen the launcher and launch the new NeoSync instance. After accepting server
mods, choose **Prepare in SKlauncher 4.0 Beta** or **Prepare in Modrinth App**.
NeoSync creates a version and an instance for that specific revision. Their game
argument points directly to the verified prepared directory. This avoids
SKlauncher's startup rebasing of instance directories and preserves the NeoSync
profile marker's path. Reopen the launcher, select the named server instance and
press Play. Opening the launcher does not automatically start Minecraft.

SKlauncher uses its native `instances.json` inventory and custom versions, with
Compatibility Mode enabled for generated instances. Modrinth uses the installed
loader metadata fallback in `meta/versions/1.21.1-<loader-id>` and the current
`instances`/`instance_content_sets` SQLite schema. An unsupported database schema
fails transactionally; no accounts are queried. These integrations retain the
existing post-load profile verification; Prism's pre-launch verifier is separate.

SKlauncher 4.0's documented **Library > Import > From launcher > Official launcher**
route copies 3.2 profiles to new directories. A copied NeoSync server profile cannot
be activated unchanged because its marker is bound to the original path. Use the
graphical installer and prepare the server instance from NeoSync instead.

## Installer availability

Each card displays the launcher icon, name and detected directory. It is selectable
only while its directory exists. Missing launchers are disabled and show their
website and instructions to install and open the launcher once, then reopen the
installer. Directory existence alone does not prove format compatibility;
preflight rejects incompatible inventories before downloading.

Windows builds include `-installer.exe`, embedding the exact `-installer.jar`
with SHA-256 verification and installed Java 21 detection. Python is not required.
Linux and macOS use the JAR. Original `--install-client` and `--install-server`
CLI options remain available. Developer scripts are optional tooling.

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

October 8, 2026 additions also consulted:

- [SKlauncher 4.0 overview](https://docs.skmedix.pl/4.0/)
- [SKlauncher 3.2 to 4.0 migration](https://docs.skmedix.pl/4.0/getting-started/migrating)
- [Modrinth loader settings](https://support.modrinth.com/en/articles/8827653-installing-updating-mod-loaders-and-game-versions)
- [Modrinth installed loader resolution](https://github.com/modrinth/code/blob/82a7b56a35ef7bd7617553468fe93cebe9eb57ad/packages/app-lib/src/launcher/mod.rs)

SKlauncher 4.0.54's distributed main bundle and its local, non-account instance
inventory confirmed the version, arguments, rebasing and custom-instance contracts.
Modrinth's source and installed database table definitions confirmed its metadata
paths and registration schema. These checks do not certify multiplayer execution.

The adapter also follows Prism's `Library.cpp`, `OneSixVersionFormat.cpp` and
`MinecraftInstance.cpp` and `INIFile.cpp` source contracts. Components are ordered
LWJGL, Minecraft, NeoSync. Versioned QSettings serialization preserves commands,
and game-directory expansion follows Prism's split-before-variable-expansion rule. Unit tests verify isolated export,
repeated selection and rejection of modified or additional mod files. Documentation
and source inspection alone do not certify a launcher. Installed execution results,
including launcher versions and platform limits, are recorded in [Phase 7](phase-7.md).
