# Phase 4 — Restricted server hosting

Work in progress. Provider integration, browser downloads, and automatic restart
remain outside this phase. Published alpha.2 does not implement hosting.

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

## Implementation sequence and evidence

1. Eligibility policy and bounded immutable inventory.
2. Full-GET service on the approved HTTPS origin, bounded requests, concurrent
   streams, bandwidth, deadlines, and publication from the loaded server inventory.
3. Exact-source review, default-negative server warning, destination validation,
   transactional preparation, and restart verification.
4. Fresh acceptance with a locally authored, unpublished fixture mod, including
   cancellations, download, restart/join, and adversarial failure cases.

No Phase 4 graphical or installed-build acceptance has run yet. Test results are
recorded here as each increment is executed; historical Phase 3 results are not
Phase 4 evidence.

Executed September 22, 2026 on Linux/JDK 21.0.2: the first inventory increment
passed all 191 unit tests (zero failures, errors, or skips), including four new
hosting tests. This exercises policy and snapshot operations, not gameplay.

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
