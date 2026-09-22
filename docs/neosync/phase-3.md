# Phase 3: installation MVP

Status: the installation MVP and its installed-build one-mod acceptance flow are
implemented and verified on Linux with JDK 21. The client reviewed and downloaded
Clumps 19.0.0.1 from its public HTTPS CDN, prepared an isolated revision, relaunched
with that game directory, verified the selected profile, refreshed discovery,
and joined a real dedicated server. External launcher certification, automatic
restart, server-hosted artifacts, and broader modpack compatibility remain outside
this result.

## Implemented increments

- Installation planning retains exact validated manifest bytes, digest, normalized
  server identity, selected sources, byte totals, reusable file indicators, and
  removals/replacements relative to a previous revision.
- Consent requires both installation review and the unverified-source decision.
  It belongs to one in-memory plan. Persisted decisions will be audit records,
  never authorization for a subsequent installation.
- Loader mismatches, library-only artifacts, and server-only sources fail before
  installation. Provider hints do not establish verified provenance.
- The consent-gated external downloader streams into new staging files, checks
  exact size/SHA-256, pins validated public IP addresses, verifies the logical TLS
  hostname, limits concurrency and deadlines, and removes failed partial files.
  Same-origin redirects are limited to three. A different origin stops the
  attempt and requires the administrator's direct URL followed by a new review;
  no bytes are requested from the new origin.
- JAR verification compares top-level javafml mod IDs, versions, language-loader
  ranges, and client dependencies with the approved artifact. It checks hashes
  before and after inspection, bounds the central directory before ZIP indexing,
  and bounds metadata expansion and TOML nesting without loading classes.
  ZIP64, nested JARs, alternate loader metadata, loader service providers, custom feature
  requirements, and multi-release archives are explicitly unsupported initially.
- The profile store now prepares fresh revision directories under a store lock,
  rehashes reusable bytes, uses independent cache copies, and publishes a verified
  revision followed by its pointer with atomic renames. It checks free space for
  three copies plus 64 MiB. Review also binds the prior prepared manifest, so a
  concurrent update requires another review. No current or prior revision is
  edited to install mods.
- Bounded local records bind the identity, manifest, sources, and consent. An
  active marker must derive the actual selected game directory before its storage
  root is used. Verification rejects extra/missing/changed mod files. It runs
  after FML loading and provides no guarantee against execution of local tampering.
- The client displays review, default-negative unverified-source confirmation,
  cancellable progress, prepared state, and manual restart instructions. It
  rechecks an active profile before discovery, including ordinary servers.
  Startup reports the selected profile and offers fresh discovery/reconnection.
  Download work has a separate one-hour transaction timeout; each artifact still
  has its own 15-minute total and 30-second idle limit.

## Server configuration and manual activation

Keep the [Phase 2 HTTPS setup](phase-2.md#server-setup). Select each client-required
file explicitly in `config/neosync-server.json`, including client dependencies.
Supply a stable external source, for example the tested MIT-licensed artifact:

```json
{
  "fileName": "Clumps-neoforge-1.21.1-19.0.0.1.jar",
  "sources": [{
    "type": "external",
    "url": "https://cdn.modrinth.com/data/Wnxd13zP/versions/jo7lDoK4/Clumps-neoforge-1.21.1-19.0.0.1.jar"
  }]
}
```

This is a `files` entry, not the complete server configuration. The JAR must
already be in the server's loaded inventory. Check the actual mod's client needs
and distribution terms before selecting it; the example is a controlled test,
not a rule to send every server mod. Restart the server to publish changes.
Provider hints remain unverified. Server-only sources are reported as unsupported.

On joining, review the exact files, sources, changes, and total download bytes.
Accept installation, then separately accept the unverified-source warning. Either
cancel action leaves the current installation unchanged. After preparation,
choose **Restart instructions** or **Later**. Close Minecraft and create/edit a
launcher installation using the displayed NeoSync/base NeoForge version and
the exact absolute **Game Directory** displayed by NeoSync. Launch it, then use
**Review and reconnect**. Simply relaunching the original directory does not
activate the prepared mods. NeoSync never edits launcher accounts or credentials.

The tested repository harness selects this directory before FML scans mods. An
official-launcher installation was unavailable on this machine, so no external
launcher is certified by this test.

The MVP serializes preparation across each storage root. It preserves orphaned
completed revisions after interrupted publication and does not automatically
prune cache/revisions or retry against different sources. Managed ancestors are
checked for symlinks; this does not protect against arbitrary concurrent writes
by other software running with the same user privileges. Process-interruption
recovery and power-loss durability are different guarantees; hardware/filesystem
power-loss behavior has not been tested.

Phase 3 initially requires direct external HTTPS URLs without query parameters,
credentials, or fragments, on ports 443 or 8443. This conservative subset avoids
persisting URL credentials or transient download tokens in review/consent records.
Administrators must configure a suitable stable direct URL. Discovery continues
to understand the broader version 1 source syntax.

## Validation

Planning regression coverage checks exact snapshot binding, both consent
decisions, loader gates, server identity separation, source restrictions, review
changes, and the distinction between reusable bytes and an active profile.
On September 21, 2026, `applyAllFormatting :tests:runUnitTests` passed with JDK 21:
141 tests, zero failures/errors/skips (10 new planning cases and the 131-test
baseline). This is logic coverage, not installation or gameplay evidence.

The downloader increment passed `applyAllFormatting :tests:runUnitTests`: 158
tests, zero failures/errors/skips. The 17 added cases exercise real TLS streaming,
certificate/hostname failures, ambiguous framing, incorrect lengths, excess and
truncated bytes, digest mismatch, redirects, cancellation, existing-file
preservation, and blocked external LAN addresses. Initial test placement conflicted
with FML's module packages; tests now use the existing test namespace. A real
framing test exposed Netty's removal of ambiguous Content-Length headers; the
decoder now rejects that response before normalization.

The metadata increment passed `applyAllFormatting :tests:runUnitTests`: 172
tests, zero failures/errors/skips. Added cases cover identity/dependency and
language-loader mismatches, manifest version substitution, rejected archive paths
and arrangements, metadata expansion, parser depth, malformed ZIPs, and
cancellation. Compatibility rules were checked against the pinned FML 4.0.44
mod-file and dependency readers; this remains a deliberately limited subset.

The profile increment passed `applyAllFormatting :tests:runUnitTests`: 178 tests,
zero failures/errors/skips. Six additional scenarios cover isolated copying and
activation records, cancellation before/during preparation and between publication
renames, injected disk-write failure, stale reviews, competing filesystem locks,
symlinked roots, marker redirection/traversal, missing markers, cache corruption,
and extra active files. Disk-write failure is injected as an I/O failure. The
subsequent real-volume and process-interruption checks are recorded below.

Inspection of the MIT-licensed Clumps 19.0.0.1 artifact showed ordinary internal
application service descriptors. These do not introduce a language loader or
discovery provider, so the verifier permits them while rejecting NeoForge and
ModLauncher service-provider descriptors. A regression case checks that those
application descriptors are read as data without instantiating their providers.
The correction passed the complete 179-test suite and `checkFormatting`.

The first real Clumps server run found that Maven serializes an exact range as
`[1.21.1,1.21.1]`, which its parser rejects. The server now emits a valid exact
range (`[1.21.1]`), with round-trip coverage for exact, bounded, union, and
recommended versions. That initial server boot did not advertise NeoSync and
does not count as a successful installation acceptance run.
The serialization correction passed all 184 unit tests and `checkFormatting`.

## Installed-build acceptance evidence

On September 21, 2026, the generated production server loaded Clumps and positively
advertised one artifact. The graphical client driver entered through the real
`ConnectScreen.startConnecting` hook and passed:

- The installation review and source warning focus **No, cancel**. Enter cancels
  each independently, with no profile store or artifact staging area created.
- Review shows Clumps, its version, 18,382 bytes, the public CDN URL, and its
  unverified status. The warning explains that installed mods execute code.
- Explicit acceptance downloads the actual file, verifies SHA-256
  `b524ccdace2ef8fd19f5b2074f7de1103ac5065c52553f064c00e098346c293e`, validates
  metadata, and prepares the fresh game directory. Instructions show the exact
  path and installed loader version. Three screenshots were visually inspected.
- A second installed client process selects that revision's game directory.
  Startup verification passes, discovery runs again, FML reports Clumps loaded,
  and normal login reaches the dedicated server. Its log records the player's
  successful login and join. No required-channel rejection probe is claimed for
  Clumps; the separate Phase 2 probe covers that earlier integration point.
- A third process launched with the original game directory reports pending
  activation, preserving the distinction between prepared and active profiles.
- Changing the server's display name produced a new manifest digest with the same
  mod bytes. The active client required fresh review and canceled by default;
  matching hashes did not reuse consent for a changed snapshot.

Follow-up acceptance checks found and corrected two issues:

- Real mouse dispatch restored focus to the old installation button after a
  screen rebuild. The screen now restores **No, cancel** after that transition.
  The graphical flow was repeated using mouse events and Enter: both declines
  and the accepted download/preparation path passed. Earlier direct button calls
  did not exercise this focus behavior.
- The test agent could trigger Minecraft static initialization too early and
  deadlock with the main thread. A thread dump identified this race; the agent
  now waits for class initialization without initiating it. This affected the
  test harness, not the product's startup hook.

The final expanded unit run passed **187 tests, zero failures/errors/skips**,
including an actual stalled TLS response reaching the 30-second idle timeout,
expired total-deadline cleanup, and preservation of symlink targets.
`checkFormatting` passed for the corrected client.

An installed-client transaction was terminated with `Runtime.halt(73)` after its
revision rename and before its pointer update. The previous `profile.json` bytes
were unchanged and one completed orphan revision remained; no finally-block
cleanup ran. The prior revision subsequently passed startup verification in a new
installed client process and still required review of the changed server snapshot.
An additional installed client ran in an isolated 64 MiB tmpfs
namespace and rejected preparation through the actual free-space check without
publishing a profile. This is real low-space preflight evidence; disk-write
failure during preparation is covered by fault injection, not physical disk
exhaustion during a transfer. Power-loss behavior remains untested.

The checked-in [driver and setup](../../tests/neosync/acceptance/README.md) explain
reproduction. Certificates, downloaded JARs, worlds, screenshots, and local reports
are fixture output, not distributable project files. Narrator native-library
loading failed in this environment; keyboard behavior was tested, spoken
narration was not. These tests used synthetic launcher credentials and a
loopback-only offline test server, not a personal Minecraft account.

## Acceptance limits and next work

The one-mod Phase 3 exit flow has passed. Current concrete compatibility limits
are unsupported server-only sources, query-bearing artifact URLs, redirects to
another origin, nested/library/alternate-loader arrangements, and external
launcher certification. The official launcher and its GUI are not installed on
this machine. No automatic restart, provider resolution, or server file hosting
is claimed. Public SRV/proxy deployments, broader mod combinations, power-loss
durability, and spoken narration still need targeted validation.

Published increments use scoped Conventional Commits, starting at `4a45dff`
(planning/consent), `0dcc6b6` (HTTPS transfer), `0b306cb` (metadata), `8f321be`
(profiles), `9c65ff6` (application services), `954c0ec` (exact version ranges), and
`ff119a5` (client/manual activation and installed-build harness), and `353b851`
(mouse focus and expanded runtime acceptance driver). Corrections are
published as follow-ups, preserving history. GitHub's inherited Release workflow
was skipped for these pushes; no remote test result is claimed.
