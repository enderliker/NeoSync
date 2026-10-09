# Installed Phase 3 acceptance driver

Current development supports Modrinth, CurseForge and restricted-hosting fixtures.
The driver handles the development path-and-Later activation screen and provider
display names. The new `--curseforge` fixture retains only hashes computed from
the local JAR, not API metadata; it requires an installer with authorized embedded
provider access. It selects CurseForge explicitly to exercise that client's live
lookup and download path, not to override automatic Modrinth-first selection.
The former CurseForge/manual-import fixture was removed on September 27, 2026.
Any browser-import execution records below describe historical alpha.4 only.

This Linux harness uses a disposable production server, a real graphical client,
and Clumps 19.0.0.1 (MIT, Modrinth project `Wnxd13zP`, version `jo7lDoK4`). The
artifact stays outside Git. The Java agent drives the actual client screens and
captures only Minecraft's framebuffer. It is not packaged with NeoSync and does
not add a mod to the client's FML inventory.

Use JDK 21. Build the installed copies first:

```sh
./gradlew :neoforge:installProductionServer :neoforge:installProductionClient --max-workers=2
python3 tests/neosync/acceptance/prepare_fixture.py --root /tmp/neosync-acceptance \
  --jdk "$JAVA_HOME" --server projects/neoforge/build/production-server
```

The setup script downloads the pinned artifact with normal TLS validation,
checks its SHA-256, builds the driver, and adds a two-day fixture certificate to
a **copy** of the JDK trust store. It configures a loopback-only offline server
on game port 25575 and HTTPS port 8443. Use only a disposable server directory;
the script replaces its test configuration. Accept Minecraft's EULA in that
test server before starting it if it has not already been accepted.

With Java 21 on `PATH`, run the generated script from the installed server
directory. It selects the library path for that exact NeoSync release:

```sh
NEOSYNC_FIXTURE_PASSWORD=fixture-password ./run.sh nogui
```

Require a positive `NeoSync discovery enabled for 1 client artifacts` log before
starting the graphical run. From the repository root:

```sh
./gradlew -I tests/neosync/acceptance/client.init.gradle :neoforge:runProductionClient \
  -PneosyncAcceptanceRoot=/tmp/neosync-acceptance \
  -PneosyncAcceptanceGame=/tmp/neosync-acceptance/original \
  -PneosyncAcceptanceMode=install --max-workers=2
```

The driver dispatches mouse clicks through the screen, checks default-negative keyboard focus, declines each consent screen
with Enter in separate attempts, checks that no store/staging area was created,
then explicitly accepts both screens. It writes `install-report.txt` and the
prepared directory to `prepared.txt`, displays activation instructions, and exits.
Require a `PASS` report; a successful Gradle exit alone is insufficient.

Run again with mode `resume`, setting `neosyncAcceptanceGame` to the exact path
from `prepared.txt`. This must verify the profile at startup, refresh discovery,
run normal login, and report Clumps loaded while connected to the real server.
Mode `original` with the original directory must report activation still pending.
To check changed snapshots, change the server's `displayName`, restart the server,
and use mode `changed` with the prepared directory. This must require fresh review
and cancel by default. Every mode writes a separate report.

Mode `crash`, using the original directory after a successful installation,
starts another transaction from verified cache bytes and intentionally calls
`Runtime.halt(73)` after the revision rename but before the profile-pointer rename.
Save the current `profile.json` hash and revision directory listing first. Require
the `HALT` checkpoint report, an unchanged pointer, and one additional complete
orphan revision afterward. Gradle must report the expected nonzero process exit;
this mode does not produce an ordinary `PASS` report. Relaunch the prior revision
to verify recovery. This tests process interruption, not power loss.

Mode `space` uses `referenceGame` from its properties file to read an existing
prepared snapshot and cache. Launch it with a fresh game directory on a private
64 MiB tmpfs to exercise the actual free-space refusal. On Linux with unprivileged
user/mount namespaces, use `unshare --user --map-root-user --mount --propagation
private` to create the temporary mount; never fill the host filesystem. Run Gradle
with `--no-daemon` inside that namespace so the test inherits the mount, and set
`GRADLE_USER_HOME` to the existing cache explicitly because the namespace changes
Java's default user home. Require the `space-report.txt` PASS result confirming
the volume size and absence of a published profile. The mount disappears when
the namespace exits. The separate unit suite injects write failures after staging
and verifies preservation of an existing profile.

The agent waits for Minecraft's class initialization to finish before invoking
its instance accessor. A loaded-but-uninitialized class is not sufficient: an
earlier driver raced static initializers and deadlocked before the client started.
This is test instrumentation, not an initialization change in the product.

Inspect the generated screenshots under each game directory's `screenshots`.
Stop the test server with `stop` afterward. Keep reports, JARs, certificates,
worlds, and generated installations out of commits. Tests of an installed build's
game-directory argument do not certify the official launcher or other launchers.

## Phase 4: administrator-authored hosting

For the alpha.3 implementation, use new installation directories and
pass the same installation-root property on **every** Gradle acceptance command:

```sh
./gradlew -I tests/neosync/acceptance/client.init.gradle \
  -PneosyncAcceptanceInstallationRoot=/tmp/neosync-phase4-installations \
  :neoforge:installProductionServer :neoforge:installProductionClient --max-workers=2
python3 tests/neosync/acceptance/prepare_fixture.py --hosting \
  --root /tmp/neosync-phase4-acceptance --jdk "$JAVA_HOME" \
  --server /tmp/neosync-phase4-installations/server
```

`--hosting` creates a minimal Java mod for this disposable server, with a random
mod ID and a freshly compiled JAR. It does not download Clumps or another external
artifact. It requires an empty server `mods` directory, declares authorship,
exclusive distribution and rights for the generated file's SHA-256, and configures
only a `server` source. Keep that generated mod, its sources, certificates, worlds,
and reports outside Git and release assets. This fixture is not a third-party
redistribution test artifact.

Start the generated server script with the fixture password as described above.
Require both `NeoSync publicly hosts 1` and `NeoSync discovery enabled for 1`
in the current startup log. Then run the same install/resume modes:

```sh
./gradlew -I tests/neosync/acceptance/client.init.gradle \
  -PneosyncAcceptanceInstallationRoot=/tmp/neosync-phase4-installations \
  -PneosyncAcceptanceRoot=/tmp/neosync-phase4-acceptance \
  -PneosyncAcceptanceGame=/tmp/neosync-phase4-acceptance/original \
  -PneosyncAcceptanceMode=install :neoforge:runProductionClient --max-workers=2
```

Require a fresh `PASS` report and inspect the screenshots. The expected source
text names the server, game address and HTTPS origin on both consent screens.
Both Enter cancellations must leave no profile store; only explicit acceptance
may download. Run `resume` with the path from `prepared.txt`, require startup
verification and a real join with the generated mod ID loaded. `original`,
`changed`, and `crash` retain their meanings above.

For a server eligibility rejection probe, stop the disposable server, select a
known third-party mod already available locally, and configure `server` as its
only source while declaring `authoredByAdministrator: false` and
`exclusiveToServer: false` (even if distribution rights are true). Restart and
require explicit rejection, no discovery advertisement, and no hosted snapshot.
Never declare a third-party file to be administrator-authored to make the test
pass. Restore the eligible configuration before continuing client acceptance.

## Phase 5: exact Modrinth resolution

Use a fresh installation root as in Phase 4, then run `prepare_fixture.py` with
`--providers` instead of `--hosting`. It selects the pinned Clumps JAR with
`resolveProviders: true`, supplies no URL/IDs to the server, and requires the
client review to identify Modrinth and pending byte checks. Run installed
`install` and `resume` modes. These are live Modrinth calls, followed by a real
server join. Record the exact source commit and installer hash for each run.

## Development CurseForge acceptance

On September 29, 2026 the new `--curseforge` fixture passed installed `install` and
`resume` runs on Linux with Clumps 19.0.0.1 and the repository's production
launcher harness. Both consent cancellations left the store untouched; explicit
acceptance downloaded verified bytes, Later was focused, and the next launch
joined the real server. This is not actual-launcher automatic restart evidence.

The driver also supports Windows absolute prepared paths. For Modrinth acceptance,
`bootstrap` with `expectedLauncher=modrinth` and `expectedVersion=<build>` checks
the local launcher arguments and the title screen. Set `nativePrepare=true` and
`launcherName=Modrinth App` for `install` to prepare the server instance through
the actual consented action. Its activation remains focused on Later. Launch the
generated instance separately and require the `resume` join report. These modes
do not automate account login or copy credentials.

## Prism restart and update acceptance

Use a fresh installed client and `scripts/configure_prism.py` to create a new
instance. Do not edit an existing user's instance. Add the external driver and
fixture trust store to that test instance's JVM arguments. For `install` mode,
set `prismRestart=true` and `prismInstance=/absolute/path/to/instance-id.txt`.
The driver declines both consent stages, prepares the profile through the real
screens, exports a revision instance, and requests **Close and launch**.

Put the `resume` driver arguments in the test-only local launcher's descriptor
`component.+jvmArgs` so the newly generated instance writes a separate resume
report. NeoSync never transfers account credentials. A real launcher account or
its normal account selection is required for automatic handoff; an explicit
`--offline NeoSyncTest` test launch must be recorded separately when used.
Require both installation and resumed-join reports. Verify the generated
pre-launch command rejects an added, missing or altered mod before launching.

`update` mode runs from an existing prepared revision and accepts a newly
published set after exercising both default-negative cancellations. It checks
that declining preserves the pointer and running mod bytes, and that successful
preparation preserves the previous revision's mods. `expectedChanges` can list
semicolon-separated review text. `additionalMods` and `absentMods` list
comma-separated mod IDs checked by `resume` after a real join.

`recovery` mode opens **NeoSync profiles** and selects the directory supplied by
`recoveryTarget`. It checks default-negative recovery consent, verifies the
selection and displays activation instructions. Launch that directory separately
and verify the corresponding server set to complete recovery acceptance.

Historical alpha.4 browser-import evidence remains in the Phase 5 document. That
provider and its fixture have been removed from the current source tree.
