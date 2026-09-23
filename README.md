<p align="center">
  <img src="docs/assets/neosync-icon.svg" width="96" height="96" alt="NeoSync N logo">
</p>

<h1 align="center">NeoSync</h1>

<p align="center">
  Review the mods. Prepare a profile. Join the server.
</p>

<p align="center">
  <a href="https://www.minecraft.net/about-minecraft"><img src="https://img.shields.io/badge/Minecraft-1.21.1-62B47A" alt="Minecraft Java Edition 1.21.1"></a>
  <a href="gradle.properties"><img src="https://img.shields.io/badge/NeoForge-21.1.251-E88A42" alt="Based on NeoForge 21.1.251"></a>
  <a href="docs/CONTRIBUTING.md"><img src="https://img.shields.io/badge/Java-21-E69B45" alt="Requires Java 21"></a>
  <a href="docs/neosync/phase-5.md"><img src="https://img.shields.io/badge/Status-Early_development-8B78E6" alt="Status: early development"></a>
  <a href="LICENSE.txt"><img src="https://img.shields.io/badge/License-LGPL--2.1--only-4D9AC5" alt="License: LGPL-2.1-only"></a>
</p>

<p align="center">
  <a href="docs/neosync/phase-3.md#server-configuration-and-manual-activation">Setup guide</a> ·
  <a href="docs/README.md">Documentation</a> ·
  <a href="AGENTS.md#roadmap-and-exit-criteria">Roadmap</a> ·
  <a href="docs/CONTRIBUTING.md">Contributing</a>
</p>

NeoSync is an open-source fork of [NeoForge](https://github.com/neoforged/NeoForge)
for **Minecraft Java Edition 1.21.1**. It helps players install the exact mods a
server requires through informed consent, verified downloads, and a separate
game directory for each server.

Install NeoSync on both the client and the server. The administrator selects the
required client files and their sources; the player reviews and accepts the set
before installation.

> [!IMPORTANT]
> NeoSync is in early development. The complete one-mod installation and join
> flow has passed on Linux using the repository's production launcher harness.
> Activation requires setting the prepared game directory in the launcher and
> restarting manually. No external launcher is certified yet.

## Compatibility and build

| Component | Current version | What it means |
| --- | --- | --- |
| Minecraft Java Edition | **1.21.1** | The Minecraft version targeted by this branch. |
| NeoForge base build | **21.1.251** | The platform build used by NeoSync; mods must be compatible with this NeoForge/Minecraft combination. |
| NeoSync development identifier | **0.1.0-alpha.4** | Current source tree; Phase 5 remains incomplete. |
| Published NeoSync release | **0.1.0-alpha.4** | Current alpha prerelease with exact Modrinth resolution and browser-assisted import. |
| Synchronization protocol | **1** | The version used for server discovery and manifests. |
| Java | **21** | Required for running and developing this build; use a JDK for development. |
| Gradle wrapper | **8.13** | Included in the repository; no separate Gradle installation is needed. |

The current installation flow requires the client's NeoSync and NeoForge versions
to match those declared by the server. Both sides need NeoSync's synchronization
code; installing ordinary NeoForge 21.1.251 alone does not provide it. These
versions do not guarantee compatibility with every mod or launcher.

The values come from [gradle.properties](gradle.properties), the
[NeoSync manifest implementation](src/main/java/net/neoforged/neoforge/neosync/protocol/SyncManifest.java),
and the [wrapper configuration](gradle/wrapper/gradle-wrapper.properties).
When reporting a problem, include the release name or Git commit as well as
these versions. Published releases are pinned to a specific source commit.

## Downloads

Get the alpha installer from [GitHub Releases](https://github.com/enderliker/NeoSync/releases).
Release names include both versions, for example
**`NeoSync-0.1.0-alpha.4-neoforge-21.1.251`**.

Download the **`-installer.jar`** for either a client or a dedicated server. The
same installer supports both; a separate universal JAR is not a standalone game
or a mod to put in `mods`. Release assets include SHA-256 checksums and source
information. Follow the [client and server installation guide](docs/neosync/releases.md).

These are experimental prereleases. Use separate test directories and read the
release notes for the actual validation results and remaining limitations.
The [alpha.4 prerelease](https://github.com/enderliker/NeoSync/releases/tag/NeoSync-0.1.0-alpha.4-neoforge-21.1.251)
adds exact Modrinth resolution and browser-assisted import. Restricted hosting
remains available for eligible administrator-authored mods.

## How it works

1. **Connect.** NeoSync discovers a compatible server's requirements before
   gameplay login and compares them with the current profile.
2. **Review.** See mod names, versions, sources, sizes, and changes. Accept the
   installation and the additional warning for unverified sources, or cancel.
3. **Prepare.** NeoSync downloads the approved files, verifies their hashes and
   metadata, and prepares a new isolated revision. Your regular mod directory
   stays untouched.
4. **Activate and join.** Follow the displayed instructions to select the new
   game directory in your launcher, restart, and review the refreshed server
   requirements before reconnecting.

You can postpone activation and leave the profile prepared for later.

## What works today

| Capability | Current behavior |
| --- | --- |
| Discovery | Retrieves a bounded HTTPS manifest before gameplay login and reports missing or incompatible requirements. |
| Consent | Binds acceptance to the exact reviewed files and sources. Unverified-source warnings default to **No, cancel**. |
| Downloads | Uses configured direct HTTPS URLs or restricted server hosting, with destination restrictions, transfer limits, and SHA-256 verification. |
| Provider resolution | Resolves exact Modrinth files before review and checks both provider and manifest hashes. Current development builds reject CurseForge provider hints because the API data flow would retain metadata. |
| Manual import | The importer verifies a watched or selected local file in synthetic tests. New CurseForge provider plans are disabled pending terms resolution; real CurseForge and Windows acceptance remain pending. |
| Server hosting | Alpha.3 serves only explicitly declared administrator-authored mods unique to that server through a bounded HTTPS snapshot service. |
| Preparation | Inspects JAR metadata without executing it and prepares a new revision while preserving existing profiles. |
| Manual activation | Shows the exact game directory to select in the launcher; checks the selected profile and offers reconnection after restart. |

The recorded Phase 3 validation includes **187 passing unit tests**, graphical
consent checks, cancellation and recovery checks, and a complete installation of
**Clumps 19.0.0.1** followed by joining a real dedicated server. See the
[validation record](docs/neosync/phase-3.md#installed-build-acceptance-evidence)
for the test environment and limits. This is evidence for the tested flow, not
a claim of compatibility with every modpack or launcher.

The recorded [Phase 4 validation](docs/neosync/phase-4.md#installed-build-acceptance)
passed **197 tests** and an installed hosted-mod flow including both consent
cancellations, download, manual restart, a real server join and process-interruption
recovery. The installed server also rejected a third-party hosting selection.

The [Phase 5 validation record](docs/neosync/phase-5.md) includes **246 passing
unit tests** and, with the exact alpha.4 installer, installed live Modrinth and
synthetic CurseForge manual-import flows followed by restart and a real-server
join on Linux. Earlier installed fixtures also exercised explicit paths and
native KDialog selection. No real CurseForge website flow or Windows runtime
acceptance has been recorded.

Post-release, the installed alpha.4 adapter also passed live, in-memory
CurseForge metadata probes for both permitted and restricted exact files. These
did not download mod bytes, open the real website, or save provider responses.

## Build from source

Use **JDK 21** and the included **Gradle 8.13 wrapper**. Make sure `JAVA_HOME`
points to JDK 21 before running:

```bash
git clone https://github.com/enderliker/NeoSync.git
cd NeoSync
./gradlew setup
```

Setup prepares the Minecraft development sources and downloads dependencies;
the first run can take some time. On Windows, use `gradlew.bat`.

- **Developers:** follow the [contribution guide](docs/CONTRIBUTING.md) for IDE
  setup, checks, and the Minecraft patch workflow.
- **Server administrators:** follow the [HTTPS and manifest setup](docs/neosync/phase-2.md#server-setup),
  then configure the [artifact sources](docs/neosync/phase-3.md#server-configuration-and-manual-activation).
  Configure direct HTTPS URLs or, for your own unpublished server-specific mods,
  the [restricted hosting declarations](docs/neosync/phase-4.md#server-configuration).
  Alpha.4 also supports [exact provider resolution](docs/neosync/phase-5.md#provider-identity-and-protocol-compatibility)
  with `resolveProviders: true`. All routes require explicit client-file selection
  in `config/neosync-server.json`.
- **Testing the full flow:** use the [installed-build acceptance harness](tests/neosync/acceptance/README.md)
  to reproduce installation, activation, and joining in a controlled environment.

## Provider source handling

The alpha.4 prerelease adds exact Modrinth resolution, with an installed
review/download/restart/join result. Manual import has installed Linux fixture
coverage with synthetic CurseForge metadata; **Phase 5 remains incomplete**.
An administrator-owned key has passed live metadata-only probes. Full CurseForge
acceptance still needs an applicable agreement for retained API data and an
installed download/browser run. Windows runtime acceptance is pending.
Current development builds disable CurseForge API lookup and reject new
CurseForge provider hints. The published alpha.4 binary retains its original
behavior and is not changed by this source guard.
See [Phase 5](docs/neosync/phase-5.md) for implementation status and evidence.
The provider preference for each exact required file is:

| Priority | Source | Acquisition policy |
| --- | --- | --- |
| 1 | Modrinth | Automatic download when the exact file is available and permitted. |
| 2 | CurseForge with third-party downloads enabled | Automatic download through the provider. |
| 3 | CurseForge with third-party downloads disabled | A clear notice, the exact browser download page, and detection and verification of the user's download. |
| 4 | Server hosting | Only unpublished mods written by the administrator for that server and unavailable elsewhere. |

Automatic downloads still require review and consent. A provider outage or author
restriction never authorizes rehosting a third-party mod. The published alpha.4
CurseForge integration uses the administrator's own
`NEOSYNC_CURSEFORGE_API_KEY` on the server; the key does not authorize retaining
API metadata. Current development builds do not read this key or request
CurseForge metadata. A selected file without an exact Modrinth match needs a
permitted direct HTTPS source. A browser may require additional user interaction
when that flow is supported again.

After consent, the manual flow opens
`https://www.curseforge.com/minecraft/mc-mods/<slug>/download/<fileId>`.
NeoSync then verifies the downloaded file before importing it into the isolated
profile. The website or browser may still require a click; automatic completion
is not guaranteed.

See the [manual download contract](docs/neosync/manual-downloads.md) and
[source policy](AGENTS.md#source-resolution). Reliable launcher integration,
update recovery, and broader compatibility testing remain on the
[roadmap](AGENTS.md#roadmap-and-exit-criteria).

## Trust and isolation

The server proposes files; **the player decides what to install**. A changed file
set or source requires new review. Failed or canceled preparation preserves the
existing installation.

HTTPS and matching hashes establish transport identity and byte consistency;
they do not prove that a mod is trustworthy. Separate game directories organize
mods and data, but **are not a sandbox**: accepted mods run with Minecraft's
permissions. Startup verification runs after FML loads mods and cannot prevent
locally tampered code from executing. Read the
[security requirements](AGENTS.md#security-rules) and
[current limitations](docs/neosync/phase-3.md#acceptance-limits-and-next-work).

## Contribute

Bug reports, focused fixes, and reproducible compatibility results are welcome
through this repository's [issues](https://github.com/enderliker/NeoSync/issues)
and pull requests. Include the NeoSync build, operating system, launcher, mod set,
and relevant logs with credentials removed.

Start with [CONTRIBUTING.md](docs/CONTRIBUTING.md). All project code,
documentation, and user-facing text are written in English. For work on another
Minecraft version, see [PORTING.md](docs/PORTING.md).

## Upstream and license

NeoSync is maintained independently and builds on
[NeoForge](https://github.com/neoforged/NeoForge). Existing package names and
development tooling reflect that origin. The
[NeoForge documentation](https://docs.neoforged.net/) remains useful for its APIs;
NeoSync-specific support belongs in this repository.

Licensed under **LGPL-2.1-only**, except where individual files state otherwise.
Upstream copyright and license notices remain applicable. See
[LICENSE.txt](LICENSE.txt) and the [licensing notes](README-LICENSE.md).
