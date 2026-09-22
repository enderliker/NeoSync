# NeoSync releases

NeoSync releases identify both the fork and its compatible NeoForge platform:

```text
NeoSync-<neosync-version>-neoforge-<base-version>
NeoSync-0.1.0-alpha.1-neoforge-21.1.251
```

The first installation MVP is an **alpha prerelease** for Minecraft Java Edition
1.21.1 and Java 21. The [Phase 3 validation](phase-3.md) establishes the tested
one-mod flow; it does not certify every modpack or external launcher.

## Download selection

| Asset | Purpose |
| --- | --- |
| `NeoSync-…-installer.jar` | Recommended download. The same installer supports clients and dedicated servers. |
| `NeoSync-…-universal.jar` | NeoSync/NeoForge runtime classes for inspection and tooling. It is not a standalone server or a JAR to drop into `mods`. |
| `NeoSync-…-sources.jar` | Runtime sources, excluding Minecraft sources. |
| `SHA256SUMS` | SHA-256 checksums for the attached artifacts and release manifest. |
| `release-manifest.json` | Source commit, version identifiers, artifact names, sizes, and hashes. |

GitHub also provides source archives for the release tag. A separate mod-development
kit or Maven publication is not provided by this initial release. Generated
`client`/`server` game JARs contain Minecraft code and are created locally by the
installer rather than uploaded as downloads.

## Install a client

1. Install Java 21 and run Minecraft Java Edition 1.21.1 once through your launcher.
2. Run the downloaded installer with `java -jar NeoSync-…-installer.jar`.
3. Choose **Install client** and the Minecraft installation directory. Select the
   version named `NeoSync-…` in a launcher that supports the generated profile.
4. Use a separate game directory for testing. Both the client and the server need
   matching NeoSync and base NeoForge versions.

The installer generates a standard launcher version profile. A successful
installation and the repository's production launcher harness do not certify a
particular external launcher. Follow the [manual activation guide](phase-3.md#server-configuration-and-manual-activation)
when switching to a prepared server-specific profile.

## Install a dedicated server

Create a new server directory and run the installer with Java 21:

```bash
java -jar NeoSync-0.1.0-alpha.1-neoforge-21.1.251-installer.jar --install-server /path/to/server
```

Start the generated `run.sh` on Linux or `run.bat` on Windows. Review the Minecraft
EULA and accept it yourself if you agree. Adjust `user_jvm_args.txt` as needed.
The installer downloads Minecraft and required libraries; it does not include a
world, third-party mods, or an accepted EULA.

Place your chosen server mods in `mods`, then configure the
[HTTPS manifest service](phase-2.md#server-setup) and
[client file sources](phase-3.md#server-configuration-and-manual-activation).
Installing the server does not automatically enable or configure synchronization.

## Packaging and validation

Update `neosync_version` and `SyncManifest.NEOSYNC_VERSION` together. Retain
`neoforge_base_version` unless the actual base changes. A release correction
gets a new NeoSync version even if the base and protocol are unchanged.

The installer embeds the universal JAR and identifies its remote location as the
same NeoSync release on GitHub, never upstream NeoForge. LegacyInstaller requires
a nonempty URL even for an embedded library; an empty URL makes it skip the file.
FML 4 hard-codes the local
`net/neoforged/neoforge` layout, so installed files use a unique version suffix:
`21.1.251-neosync-0.1.0-alpha.1`. The mod metadata and `NeoForgeVersion` still
report the compatible base `21.1.251`. No modified NeoForge Maven publication
is uploaded by this release process.

With JDK 21 selected, build and validate:

```bash
./gradlew checkFormatting :tests:runUnitTests :neoforge:installerJar :neoforge:sourcesJar --max-workers=2
./gradlew :neoforge:testProductionServer :neoforge:testProductionClient --max-workers=2
```

Use an available display for the client test. Use disposable production test
directories; the server self-test temporarily accepts the EULA for its test and
removes that file afterward. Preserve any existing fixtures before testing.
Exercise the [full-flow harness](../../tests/neosync/acceptance/README.md) when
changes affect installation, profile selection, or joining.

After validation, commit and push the source, then export the exact built assets:

```bash
python3 scripts/prepare_release.py
```

The exporter requires a clean tracked tree, verifies the installer identity,
embedded runtime, source version, and absence of bundled Minecraft classes, and
writes artifacts to `build/neosync-release/<release-name>/`. Untracked personal
handoff files are not included. Checksums identify the published bytes; they are
not a code signature or a claim that downloaded code is harmless.

## Publication

Create release notes with the exact versions, installation commands, validation
results, and remaining limits. Tag the validated, pushed source commit using the
release name. Push the tag and create a GitHub prerelease with the exported files;
verify their names and checksums after upload.

Do not move published tags, overwrite release assets, upload installed game
directories, or run the inherited upstream Maven publishing tasks. A correction
must use a new version and release. The repository's release workflow builds
reviewable artifacts; it does not publish to NeoForged infrastructure.

## Initial alpha packaging checks

On September 21, 2026, the `0.1.0-alpha.1` packaging changes passed formatting,
all 187 unit tests (zero failures/errors/skips), and production client and server
startup tests on Linux with JDK 21.0.2. Both installers extracted the embedded
runtime with a valid checksum into the version-suffixed library path. Runtime mod
discovery still reported NeoForge `21.1.251`.

The first startup attempt exposed LegacyInstaller's empty-URL behavior: it
reported installation success while skipping the embedded universal JAR. The
release metadata now uses the exact NeoSync GitHub asset URL, and both startup
tests passed after correction. The exporter rejects an empty URL and an upstream
NeoForge URL; both cases were checked against modified copies of the real
installer. Archive checks and `actionlint` validation of the release-build
workflow also passed. These local results do not claim a completed remote CI run
or external-launcher certification.
