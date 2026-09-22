# Phase 4 — Restricted server hosting

Status: implemented, with fresh installed-build acceptance on Linux/JDK 21.0.2.
The unreleased `0.1.0-alpha.3` development build completed consent, hosted download,
isolated preparation, manual restart and a real dedicated-server join with an
administrator-authored fixture generated solely for that server. An ineligible
third-party file was rejected by the installed server. Published alpha.2 does not
implement hosting. Provider integration, browser downloads and automatic restart
remain outside this phase.

## Eligibility and inventory

Hosting is disabled by default. Only administrator-authored mods created for
that server and not published or distributed anywhere else are eligible. Each
selected hosted file must have only a `server` source and an explicit declaration
of `authoredByAdministrator`, `exclusiveToServer`, and `distributionRights`, all
true, bound to its exact `sha256`. A replaced file requires a new declaration.
These statements are administrator assertions, not independently verified facts.
A third-party file, general redistribution permission, a provider failure, or an
author download restriction never establishes eligibility.

The inventory copies and hashes selected files before publication, uses generated
hash filenames, rejects symbolic links, and holds an exclusive lifetime lock.
Live `mods` changes do not change the snapshot. Disk quota applies before copying;
failed copies are removed. An interrupted lifetime's private snapshot is reclaimed
under the lock on the next start. Unexpected content causes refusal instead of
recursive deletion. Normal shutdown removes the snapshot.

## Incremental validation

On September 22, 2026, the eligibility/inventory increment passed 191 tests,
the restricted service increment passed 194, and client integration passed 197,
all with zero failures, errors or skips in their final runs. Formatting checks
passed for each increment. The final alpha.3 build also ran all 197 tests.
These are new executions; historical Phase 3 runs are not Phase 4 evidence.
Service and client test coverage appears below, separately from installed-game
acceptance.

## Server configuration

Add `hosting` to `config/neosync-server.json` and an eligibility declaration to
**each** selected hosted file. Keep the HTTPS/keystore or loopback reverse-proxy
settings from [Phase 2](phase-2.md). Example fragment (replace the placeholder
hash with the reviewed file's real SHA-256):

```json
{
  "hosting": {
    "enabled": true,
    "maxBytes": 1073741824,
    "concurrentTransfers": 8,
    "bytesPerSecond": 8388608,
    "requestsPerMinute": 120
  },
  "files": [{
    "fileName": "my-private-server-mod.jar",
    "sources": [{"type": "server"}],
    "hosting": {
      "authoredByAdministrator": true,
      "exclusiveToServer": true,
      "distributionRights": true,
      "sha256": "REPLACE_WITH_THE_REVIEWED_FILE_SHA256"
    }
  }]
}
```

The selected file must be in the loaded server inventory and pass the same bounded
JAR metadata verification used by the client. Snapshot files live under
`config/neosync-hosting/snapshot`; do not edit that private directory. Startup
refuses discovery if any selected file or declaration is invalid. Restart to
publish a new inventory. The logical `gamePort` override also controls file routes.

Hosting makes these files **public**, independent of Minecraft authentication,
whitelists, or bans. Enable it only when public delivery of the declared mod is
intended. There are no download tokens or private-distribution claims in v1.
No third-party/provider fallback is implemented.

## Transfer limits

Full GETs use `/.well-known/neosync/v1/servers/<gamePort>/files/<sha256>` on the
manifest's HTTPS listener. Responses carry exact lengths and JAR content type.
Unknown hashes, additional path components, queries and encoded traversal do not
resolve. Ranges and request bodies are rejected. Connections close after one
request; there is no keep-alive request queue or range/resume support.

The listener bounds active connections to 32 and the OS accept backlog to 16.
Default artifact concurrency is 8 (configurable 1–32); excess requests get 503.
The aggregate artifact rate defaults to 8 MiB/s (configurable 64 KiB/s–128 MiB/s),
paced in at most 16 KiB chunks without accumulating whole files in memory.
There is at most one scheduled chunk per stream. The global fixed-window request
limit defaults to 120 per minute (1–6000), including failed paths and manifests;
excess requests get 429. Boundary bursts can span adjacent minute windows.
Limits apply to the service as a whole, not client-supplied IP headers.

TLS handshakes have 5 seconds, complete request headers 10 seconds, stalled
writes 30 seconds, and a transfer at most 15 minutes. A slow configured rate can
therefore make large files exceed the deadline; adjust the rate or reduce the
inventory. On interruption, resources are released and a retry starts at byte
zero. Configure equivalent or tighter limits and disable response buffering in
an administrator-managed reverse proxy. Plaintext backends bind only to loopback;
there is no plaintext client fallback.

The second increment passed all 194 unit tests on September 22, 2026 with
zero failures/errors/skips. New socket-based tests verified hash-only serving,
immutable responses after live-file changes, snapshot symlink refusal, unknown
routes, traversal/query/range/body rejection, ambiguous HTTP framing, request and
concurrency quotas, paced bandwidth, and retry from zero after disconnect. The
existing manifest TLS tests also passed. This is service-level execution, not
an installed server or graphical client acceptance result.

## Client consent and destination binding

A `server` source derives its full URL from the reviewed origin, logical game
port and artifact SHA-256. Review and the additional warning name the server and
its HTTPS origin as the source; both screens keep **No, cancel** focused by
default. Installed mods can execute code; neither TLS nor the manifest's hash
is a trust guarantee. Consent belongs to one immutable in-memory plan.

For LAN discovery, the endpoint permission covers the exact IP used to fetch the
manifest. A hosted download pins that same IP with TLS hostname verification for
the logical host; it never re-resolves it into a different LAN destination.
External sources retain public-destination checks, even when the server's LAN
endpoint was approved. Hosted responses cannot redirect, including to another
path on the same origin. No fallback source is selected after consent. Endpoint
permission is not restored from profile records on later launches.

Hosted files use the existing staging, size/hash/JAR checks, atomic revision
publication, and manual restart flow. The consent record's source must still
match the manifest-derived origin, port and hash when a prepared profile opens.

The client increment passed all 197 unit tests (zero failures/errors/skips) on
September 22, 2026. A fresh TLS service delivered a generated JAR through the
consented production downloader into a real isolated profile; reopening verified
its source record and files. Corruption and transfer cancellation preserved that
profile and cleared staging. Redirects, unapproved LAN downloads, external LAN
URLs even with endpoint approval, and modified consent routes were rejected.
The first test run exposed an IPv4/IPv6 fixture mismatch; the listener now uses
the same loopback family as the pinned test address. Product TLS validation was
not weakened. These are integration tests without Minecraft gameplay.

## Installed-build acceptance

Executed September 22, 2026 on Linux x86-64 (CachyOS), JDK 21.0.2, Minecraft
1.21.1, NeoForge base 21.1.251, NeoSync 0.1.0-alpha.3 and FML 4.0.44. Runtime
sources and the reproducing harness are recorded in commit `6d6d328`. The exact
installer SHA-256 was:

```text
2349211eeba79da513d6cdd463fd83be94fd46408da8f26e9413713f45baa019
```

The installer populated new `/tmp/neosync-phase4-installations/client` and
`server` directories. Both installed startup self-tests and artifact-content
validation passed. The startup-only server test had no fixture password and
correctly refused its HTTPS configuration; it was not counted as hosting
acceptance. The subsequent full-flow server ran with the fixture password,
announced its HTTPS inventory and completed the checks below.

The fixture was a newly compiled, minimal Java mod with ID
`private_fixture_b2931f3b3827`, version `1.0`, 1126 bytes, and SHA-256
`bf001f369214b36ea4fe49d3522883cd61d9146e4c506a46c71355d1d97b04f9`.
It had no external source and was generated solely for this disposable server.
No generated mod, world, installation, certificate or account data is committed
or distributed as a release asset. The test used loopback game port 25575 and
HTTPS 8443, a fresh certificate with matching SANs, and a copy of normal JDK trust
roots plus that fixture certificate. No product TLS bypass was introduced.

| Check | New execution result |
| --- | --- |
| Consent cancellation | Real mouse dispatch and Enter verified default-negative focus on both screens. Each cancellation returned without creating the original directory's `neosync` store or staging files. |
| Source review | Screenshots and driver assertions identified the server name, game address and HTTPS origin as the source; the additional warning explained executable code and unverified trust. |
| Installation | Explicit acceptance downloaded the hosted JAR, checked size/hash/metadata, prepared an isolated revision and displayed exact manual activation instructions. |
| Restart and join | A new installed client process selected that revision, verified it, refreshed discovery and joined the dedicated server with the expected mod ID loaded. The final driver waited for the loading screen to close and ten driver ticks with the game rendered; the screenshot shows the world. |
| Third-party eligibility | The installed server rejected locally available Clumps 19.0.0.1 configured with false authorship/exclusivity and true distribution rights. Status contained no NeoSync advertisement, HTTPS was not listening, and no snapshot remained. The eligible configuration was restored afterward. |
| TLS identity | Requests to the installed file route rejected the untrusted fixture certificate under normal system trust and rejected a different TLS hostname even with fixture trust. |
| Restricted routes | Unknown hashes, traversal and query variants returned 404 from the installed service. |
| Snapshot isolation | Changing the live fixture JAR left the published bytes unchanged. The live file was restored. |
| Process interruption | A fresh client deliberately halted with exit 73 after revision rename and before pointer rename. The previous pointer stayed byte-identical; one complete orphan revision remained. Relaunching the previous revision verified it and joined the server with the world rendered. This tests process interruption, not power loss. |
| Shutdown | Test servers stopped cleanly; the private hosting snapshot was removed. |

The service-level tests separately exercised disk quota refusal, snapshot and
ancestor symlinks, request/concurrency limits, aggregate pacing, disconnect/retry,
ambiguous framing, and unknown paths. Client integration tests exercised corrupted
hosted bytes, transfer cancellation, staging cleanup, previous-profile preservation,
source-record tampering, blocked destinations and forbidden hosted redirects.
These checks are not represented as additional graphical runs.

Reproduce with the [acceptance harness](../../tests/neosync/acceptance/README.md#phase-4-administrator-authored-hosting).
Local evidence was kept under `/tmp/neosync-phase4-acceptance`: `install-report.txt`,
`resume-report.txt`, `service-report.txt`, `ineligible-report.txt`,
`crash-report.txt`, `recovery-report.txt`, server logs and game screenshots.
Temporary evidence may disappear after reboot; the procedure and results above
remain in version control.

## Limits and remaining validation

No blocker remains for Phase 4's scoped acceptance criterion. This result covers
one minimal top-level javafml mod and the repository's production launcher
harness. It does not certify external launchers, Windows, macOS, public reverse
proxy deployments, real multi-mod packs, or long-duration load/DoS resistance.
The system lacked `libflite`, so narrator audio was unavailable; graphical
consent and rendering passed. No new power-loss or real full-disk test is claimed.
The existing unsupported JAR arrangements and post-FML startup-verification limit
from [Phase 3](phase-3.md) still apply.

Eligibility is enforced through mandatory, byte-bound administrator declarations
and rejection of conflicting external sources; NeoSync cannot prove an operator's
authorship or detect a dishonest declaration. Private authenticated distribution,
provider identity checks, browser import, range/resume and automatic relaunch
remain unimplemented. An eligible unpublished mod may be delivered publicly by
this server. No third-party hosting exception or CurseForge integration was added.
