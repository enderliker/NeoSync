# NeoSync releases

NeoSync releases identify both the fork and its compatible NeoForge platform:

```text
NeoSync-<neosync-version>-neoforge-<base-version>
NeoSync-0.1.0-alpha.3-neoforge-21.1.251
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
| `NeoSync-…-earlydisplay.jar` | FML startup library with NeoSync graphics, already included in the installer. Not a mod. |
| `NeoSync-…-earlydisplay-sources.jar` | Matching FML startup sources, graphics, license, and change notice. |
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
java -jar NeoSync-0.1.0-alpha.3-neoforge-21.1.251-installer.jar --install-server /path/to/server
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

The installer embeds the universal JAR and, from alpha.2, the startup graphics
library. Their remote locations point to the same NeoSync release on GitHub,
never to replacement binaries from upstream NeoForge. LegacyInstaller requires
a nonempty URL even for an embedded library; an empty URL makes it skip the file.
FML 4 hard-codes the local
`net/neoforged/neoforge` layout, so installed files use a unique version suffix:
`21.1.251-neosync-0.1.0-alpha.3`. The mod metadata and `NeoForgeVersion` still
report the compatible base `21.1.251`. No modified NeoForge Maven publication
is uploaded by this release process.

The startup library has its own `io.github.enderliker.neosync:earlydisplay`
coordinates, preserving FML's module identity and byte-for-byte Java classes.
Both client and server profiles use its unique versioned path. The build also
produces corresponding sources; see [branding](branding.md) for regeneration,
attribution, and the difference between development and installed startup assets.

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
embedded libraries, branding, unchanged FML code, source version, and absence of bundled Minecraft classes, and
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

The same installer then passed a fresh graphical one-mod acceptance run: both
default-negative consent decisions canceled without creating a profile store,
explicit acceptance downloaded and verified Clumps 19.0.0.1, and a new client
process selected the prepared directory and joined the dedicated server. The
server was started through its generated `run.sh`; the client and server used
the alpha's isolated runtime paths. Activation instructions were visually checked
and showed NeoSync `0.1.0-alpha.1` and NeoForge `21.1.251`.

A separate client reinstallation preserved an existing launcher-profile entry
and sentinel files in the ordinary `neoforge-21.1.251` version and library paths.
This checks installation isolation, not a full external-launcher coexistence
certification. The installer tested in these checks has SHA-256
`ce9f266d6d7333a85228b1e2fee0008bdcfd569e236fd5ee56b1d83768f9291e`.
See the [first alpha release notes](release-notes/0.1.0-alpha.1.md) for that build.

### Alpha.2 branding validation

The alpha.2 installer passed client and dedicated-server installation and startup
on Linux with JDK 21.0.2. Both sides extracted the separate startup library from
the installer with a valid checksum. All 187 unit tests and formatting checks
passed. Package validation confirmed unchanged FML classes and sources, expected
graphics, isolated library paths, matching source archives, and upstream notices.
Negative archive probes rejected changed code, old graphics, an incorrect startup
download URL, and a missing source license.

A graphical client run verified the loaded startup icon and library path, and
screenshots confirmed the startup N, mod-list banner, and both title-screen
versions. A fresh Clumps 19.0.0.1 run then passed both consent cancellations,
download, verification, isolated preparation, restart, and a real dedicated-server
join. These are production-harness results, not external launcher certification.

The inherited client test launcher initially rejected the new library because it
only indexed Gradle-resolved dependencies. It now verifies and uses the installed
NeoSync library. Do not repair a missing embedded library from build outputs in
this test: that would hide an installer failure.

See the [alpha.2 release notes](release-notes/0.1.0-alpha.2.md) for downloads and limits.

### Alpha.3 hosting release

[NeoSync-0.1.0-alpha.3-neoforge-21.1.251](https://github.com/enderliker/NeoSync/releases/tag/NeoSync-0.1.0-alpha.3-neoforge-21.1.251)
was published as a prerelease on September 22, 2026 for Minecraft 1.21.1 and
Java 21. Its immutable annotated tag points to source commit
`ab0725278c28d6f0d69776ccc63e7fa7a097fdd2`, matching `release-manifest.json`.

The five JARs, source-commit manifest and `SHA256SUMS` were uploaded to a draft,
downloaded from GitHub, and compared byte for byte with the verified export
before publication. Every checksum passed. Publication retained the exact
installer tested during [Phase 4 acceptance](phase-4.md#installed-build-acceptance),
with SHA-256 `2349211eeba79da513d6cdd463fd83be94fd46408da8f26e9413713f45baa019`.
No runtime or packaging code changed during release preparation. These upload
checks are not new Minecraft gameplay runs.

Alpha.1 and alpha.2 tags and assets were not changed. See the
[alpha.3 release notes](release-notes/0.1.0-alpha.3.md) for hosting eligibility,
installation instructions, the recorded 197-test and installed-build results,
and limits. Subsequent documentation commits do not move the release tag or
change its binaries.
