# Installed Phase 3 acceptance driver

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

From the installed server directory, run:

```sh
NEOSYNC_FIXTURE_PASSWORD=fixture-password "$JAVA_HOME/bin/java" -Xmx1G \
  @libraries/net/neoforged/neoforge/21.1.251/unix_args.txt nogui
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
