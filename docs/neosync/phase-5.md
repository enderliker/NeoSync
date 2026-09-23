# Phase 5 — Exact provider files and browser imports

Status: implementation in progress in the published alpha.4 prerelease;
alpha.3 remains the published Phase 4 milestone. Phase 5 acceptance is
**not complete**. Automatic restart is
still deferred to Phase 6. Recorded Phase 3/4 runs do not validate these changes.

## Provider identity and protocol compatibility

Protocol and manifest schema remain v1. Provider identifiers already accepted on
external sources are retained as immutable **server hints**, never trust claims.
The server can select `{"fileName":"example.jar","resolveProviders":true}`
in `neosync-server.json` instead of supplying `sources`. It hashes that existing,
explicitly selected server file and emits a normal v1 external source with exact
provider IDs. Automatic resolution cannot be combined with hosting declarations
or configured sources. Lookup failure cannot make a third-party mod eligible for
hosting. Configured direct HTTPS sources remain supported.

Modrinth uses SHA-512 lookup of existing server bytes, batched in groups of 32.
Clients batch exact version IDs and require the project, exact file URL, size,
Minecraft version and loader to match. A version label or the first/primary file
is never a substitute for the selected file. The client has no independent
SHA-256 binding before download: the server's SHA-256 and provider's SHA-512 are
both checked on the approved bytes before publication. Review keeps the
unverified-source warning and states this limitation. Provider identification is
not a malware-safety guarantee.

Provider evidence is immutable within the installation plan; consent is tied to
that plan object. Provider URLs and hashes are included in the local audit, which
is validated against the manifest's provider hint when reopening a revision.
Existing v1 profile records remain readable. Audit records never authorize new
requests. Redirects from provider artifact URLs are rejected. Active files and
previous revisions retain the existing transactional guarantees.

New provider audits use local consent schema 2; schema 1 records, including the
first Modrinth increment, remain readable. Startup rechecks retained provider
hashes against installed bytes as well as the manifest SHA-256.

New manifests require NeoSync alpha.4. The existing exact loader-version check
prevents alpha.3 installation of an alpha.4 manifest, including future manual
page sources. No unknown v1 fields or source types are introduced. The new client
resolves hinted sources before review; legacy direct URLs without hints retain
their unverified classification.

## Official documentation checked on September 22, 2026

Fetched the following official documentation over HTTPS during this work:

- [Modrinth API](https://docs.modrinth.com/api/) and its
  [published OpenAPI source](https://github.com/modrinth/code/blob/main/apps/docs/public/openapi.yaml):
  `POST /v2/version_files` accepts SHA-1 or SHA-512, not SHA-256;
  `GET /v2/versions?ids=...` batches exact IDs. File metadata supplies size,
  direct URL and SHA-512/SHA-1. Requests require a distinctive User-Agent.
  Documentation currently lists 300 requests/minute/IP and response limit,
  remaining and reset headers; this is provider documentation, not a measured
  entitlement or a promise of future limits.
- [Modrinth terms, API Usage](https://modrinth.com/legal/terms): conditional,
  revocable API license for querying and downloading content; third-party rights
  remain applicable. NeoSync does not operate a metadata mirror or persistent
  provider response cache.
- [CurseForge REST API](https://docs.curseforge.com/rest-api/): exact project/file
  endpoints, batch files and fingerprint matches; file hashes include SHA-1 and
  MD5, while `allowModDistribution` is a nullable project field. A null URL or
  HTTP failure alone is not proof of an author restriction. The terms section
  embedded in this page is expressly for **CurseForge for Studios** and does not
  establish approval for NeoSync desktop key distribution.
- [CurseForge API application guidance](https://support.curseforge.com/support/solutions/articles/9000208346-about-the-curseforge-api)
  links the applicable [third-party API terms](https://support.curseforge.com/support/solutions/articles/9000207405-curse-forge-3rd-party-api-terms-and-conditions).
  Under **Use of Platform API**, the key is “non-transferable and may not be
  shared with any third party.” Under **Restrictions and Obligations of
  Developer**, developers may not “save or cache any data obtained through the
  API or SDK.” The administrator-owned key now remains on the server, but retained
  manifests and provider audit records still need an applicable permission or
  written clarification. No permission is inferred from another launcher, a
  submitted application or possession of a key.

Metadata requests use fixed API origins, public-address validation, TLS hostname
verification, no redirects, a 2 MiB response cap, strict JSON parsing and bounded
workers/queue/deadlines. Concurrent identical requests share in-flight work;
subscriber cancellation closes unused work. There are no automatic retries.
HTTP 429 and Modrinth exhausted-budget headers establish a provider cooldown,
including `Retry-After` dates or seconds. Failures never trigger silent source
switching. Only supported provider CDN hosts may supply automatic downloads.

After alpha.4, development builds serve HTTP 200 Modrinth metadata responses
from a process-local cache for up to ten minutes. It is keyed by the complete
API path and request body, limited to 256 responses and 16 MiB, and stores neither
HTTP 404 results nor failed requests. Entries are copied on read and write.
The cache is lost when the process exits and does not store CurseForge responses.
Provider metadata is still checked against the exact reviewed source, size and
downloaded hashes; a cache hit does not grant consent or authorize a source change.
This change has not had a new installed-game acceptance run.

## CurseForge key ownership

Server administrators use their **own** CurseForge application keys in the
`NEOSYNC_CURSEFORGE_API_KEY` environment variable of the NeoSync server process.
The key is never written to a manifest, sent to a client, included in a release
binary or read from a client environment. Only the server can query CurseForge.
Missing keys leave Modrinth resolution active. If a selected file has no exact
Modrinth match and needs CurseForge, server startup reports the missing variable
to the administrator instead of advertising an incomplete inventory. A client's
legacy CurseForge hint without server evidence also asks for administrator action.
Administrators must use an applicable key and meet the provider's terms for this
use and any retained metadata; obtaining a key alone does not resolve the
documented storage restriction. A local administrator-owned key was later used
only for in-memory metadata probes; no provider grant for retaining API data is
recorded, so the installed CurseForge flows are not certified.

## New execution evidence

- Provider-hint increment `e84a86d`: 206 JUnit tests passed, no failures, errors or
  skips; `applyAllFormatting` and `checkFormatting` passed on JDK 21.0.2/Linux.
- Modrinth integration increment: 217 JUnit tests passed with no failures, errors or
  skips; formatting passed on JDK 21.0.2/Linux. A separate live HTTPS metadata
  request returned the expected Clumps project/version, 18,382 bytes and SHA-512.
- Installed Modrinth acceptance at `d434fb0` on Linux x86-64, JDK 21.0.2:
  installer SHA-256 `c29a6d31506988d6dccf5eed99fc1660eee0c23a2151d1cff5a205420b3ff6fd`
  populated fresh client/server installations. Server-selected Clumps 19.0.0.1
  (`Wnxd13zP` / `jo7lDoK4`, 18,382 bytes) resolved automatically from its existing
  SHA-512. Client review named Modrinth and explained pending byte checks. Real
  mouse dispatch followed by Enter declined both default-negative screens without
  creating a profile store. Explicit acceptance downloaded, verified and prepared
  the isolated revision. A new client process verified that revision and joined
  the real dedicated server with Clumps loaded; the loading screen closed and
  the world rendered for ten driver ticks. Screenshots were inspected.
  Reports: `/tmp/neosync-phase5-modrinth-acceptance/{install,resume}-report.txt`.
  This is the repository's production launcher harness, not external launcher or
  Windows certification. Later code changes do not inherit this exact-binary run.

## CurseForge and manual import implementation

The server requests CurseForge exact IDs through documented `POST /v1/mods` and
`POST /v1/mods/files` batches of at most 32. The adapter checks project/file IDs,
Minecraft game/category, available status, Minecraft 1.21.1/NeoForge tags, size
and SHA-1. Explicit `allowModDistribution=true` plus a supported CDN URL selects
automatic acquisition. Explicit `false` selects a locally constructed official
project/file page and ignores any CDN URL. A missing/null permission or missing
permitted URL fails explicitly; neither is interpreted as an author restriction.
The server includes the resulting SHA-1 and manual-download decision in the
bounded manifest. Clients validate the shape and source, treat these values as
**server-reported** rather than independently checked provider evidence, and
verify downloaded bytes against the reviewed SHA-256 and reported SHA-1. The
review explicitly names this limit of provenance and retains default-negative
unverified-source consent. Neither the server's claim nor its hash is a safety
guarantee.

Automatic server configuration can add an exact CurseForge hint:

```json
{"fileName":"example.jar","resolveProviders":true,
 "curseforge":{"projectId":"123","fileId":"456"}}
```

These are illustrative identifiers. Modrinth SHA-512 matching is attempted first.
If no Modrinth match exists, the explicit CurseForge IDs are resolved and SHA-1
and size must match the already-selected server bytes. NeoSync does not implement
CurseForge fuzzy matching, project-name search, or an undocumented fingerprint
algorithm. Thus the CurseForge branch still needs two exact IDs when Modrinth
cannot identify the artifact. Provider outages fail; no silent fallback follows
review. Manifests may also carry already configured provider hints.

After both consent screens, the importer registers a watcher before launching the
reviewed page. Linux uses XDG user directories parsed as data; Windows calls the
Downloads known-folder API. The screen also supports a native file chooser and
an explicit absolute file/folder path. Original downloads stay untouched. Scans
are nonrecursive, limited to 1,024 directory entries and 2,048 tracked candidates;
rescans are paced to one per second, with a 15-minute deadline and bounded I/O.
Partial names, symlinks, wrong size/hash, changing files and invalid JAR metadata
cannot enter a revision. Owned staged copies are reverified against both hashes
and the approved mod set. Browser waiting occurs outside the profile-store lock;
publication uses the existing all-files transaction. Canceled/expired attempts
close watchers and delete their temporary copies. Interrupted processes may leave
unpublished temporary copies; they are not activated or reusable consent.

Pages are opened one at a time for the next needed file. A user can explicitly
reopen the displayed page. Browser failure retains file-selection fallback.
The external browser manages its own redirects, account and cookies; NeoSync
never scrapes a restricted page or requests a CDN alternative for it.

New manual resolutions now construct the official `/download/<fileId>` browser
page instead of `/files/<fileId>`. Local audits using the earlier details-page
route remain readable. The September 22 installed fixtures recorded later in
this document predate this route change. The alpha.4 release-candidate fixture
exercises the new route through a controlled browser; neither run validates the
real website's behavior at the new URL.
The route change passed 243 JUnit tests with zero failures, errors or skips and
formatting checks on September 22, 2026 (Linux, JDK 21.0.2), including legacy audit
compatibility, exact-file URL rejection, consent and manual-import regressions.

## Server credential handling

The build-time credential generator and embedded resource have been removed.
`ProviderHttpClient` on a client has no CurseForge key. A server instance reads
`NEOSYNC_CURSEFORGE_API_KEY` from its own environment and sends it only as an
`x-api-key` header to the fixed CurseForge API origin; it is never placed in
queries, logs, manifests, browser URLs, CDNs or a release asset. The value must
be 1–512 printable ASCII characters without whitespace. A server that does not
need CurseForge can run without the variable. Rotate a revoked or compromised
administrator key in that server's environment; no client update is required.

## Alpha.4 release-candidate validation on September 23, 2026

The final release-candidate build on Linux x86-64 with JDK 21.0.2 passed
`applyAllFormatting`, `checkFormatting`, all 246 JUnit tests (zero failures,
errors or skips), `:neoforge:installerJar`, `:neoforge:sourcesJar`, and
`scripts/prepare_release.py --check`. Its installer SHA-256 is
`4d9defee368167d63e7cc313e5db9083e031bdd28997698f97fc114b1850d6c4`.
The embedded universal has no provider credential resource. The build log is
`/tmp/neosync-alpha4-final-build.log`. The exact installer also passed
`:neoforge:testProductionServer` and `:neoforge:testProductionClient`; their
self-test reports were produced under `projects/neoforge/build/tmp`, and the
combined run ended `BUILD SUCCESSFUL` in 2m 39s. Its log is
`/tmp/neosync-alpha4-final-production-tests.log`.

That exact installer populated a fresh dedicated server and client under
`/tmp/neosync-alpha4-final-installations-20260923`. The first client install
encountered a read timeout downloading Sponge Mixin from the NeoForged Maven
repository; a retry with JDK 21 completed with checksum validation. The server
installed successfully on the first attempt. This was a dependency download
failure, not a passing install on the first attempt.

The installed final build passed live Modrinth acceptance with Clumps 19.0.0.1:
both default-negative cancellations left no profile store, explicit acceptance
downloaded and verified the selected file, and a new graphical client process
verified the prepared revision and joined the real dedicated server with Clumps
loaded. The loading screen closed and the world rendered. Review, warning,
activation and joined-world screenshots were inspected. The reports are
`/tmp/neosync-alpha4-final-modrinth-acceptance-20260923/{install,resume}-report.txt`.
This uses the repository's production launcher harness on Linux, not an external
launcher certification.

The same final client/server installations passed a separate **synthetic**
CurseForge manual-import fixture. The server received a fixture-only value in
`NEOSYNC_CURSEFORGE_API_KEY` to satisfy the configured-source gate; it made no
CurseForge API request. The client showed the exact `/download/456` page and
server-reported provenance, canceled both consent screens by default without
opening the controlled browser, then accepted and imported the exact file from a
relocated XDG Downloads directory. A new process verified the profile and joined
the real server with Clumps loaded and the world rendered. The reports are
`/tmp/neosync-alpha4-final-manual-acceptance-20260923/{install,resume}-report.txt`.
This does not validate the real CurseForge website or author restrictions.

A separate installed server with a newly generated, unpublished test mod and
configured exact CurseForge IDs ran with no key. Modrinth had no exact match;
the server logged the required administrator setting and did not start its HTTPS
discovery listener on port 8443. The log is
`/tmp/neosync-alpha4-final-no-key-server.log`. The earlier final Modrinth run
demonstrates that no key is needed when Modrinth finds the exact file. No real
CurseForge credential, metadata request or restricted download was exercised.

## Post-release in-memory CurseForge probes on September 23, 2026

After alpha.4 publication, the administrator supplied a free key from the
CurseForge console in a local mode-600 file outside Git. The installed alpha.4
runtime from installer SHA-256
`4d9defee368167d63e7cc313e5db9083e031bdd28997698f97fc114b1850d6c4`
queried the official API through `ProviderHttpClient` and `CurseForgeProvider` on
Linux/JDK 21.0.2. The key and API responses were not printed, written to a
manifest/audit or included in release assets. The disposable probe source was
kept outside the repository at `/tmp/neosync-cf-live-probe-20260923`.

- The exact file ID `8897017` supplied as an example was rejected because the
  API did not list it for Minecraft 1.21.1 and NeoForge. This is a correct
  compatibility refusal, not a provider outage.
- An exact compatible Clumps file passed the live project/file lookup, required
  identity, game, loader, availability, SHA-1, size and supported CDN URL checks.
  The adapter selected the permitted automatic branch. No mod bytes were
  downloaded in this probe.
- A bounded search of compatible projects found a project with third-party
  downloads disabled and an exact available 1.21.1/NeoForge file. The adapter
  constructed its official `/download/<fileId>` page, selected manual import,
  and passed URL validation without selecting a CDN bypass. The browser and
  local import were not exercised with that real website.

These were new **metadata-only** probes after the release. They do not change
the alpha.4 tag or convert its earlier synthetic installed fixtures into live
CurseForge acceptance. Current published third-party terms prohibit saving or
caching API data; the free console key does not grant an exception. The product's
server manifest and local audit retain provider values, so full installed
CurseForge flows still need an applicable agreement or written clarification
before they are exercised or represented as supported.

## Remaining acceptance blockers

- Current published third-party terms still require specific clarification or an
  applicable agreement for saved provider data. The local key validates API
  access but does not authorize retention in manifests or audits.
- A real permitted CurseForge download and a real author-restricted file/browser
  flow, including the website's actual steps, remain untested end to end. The
  metadata-only probes and synthetic installed fixtures do not certify them.
- Windows known-folder relocation, file locking, chooser/browser behavior and
  cancellation need actual Windows execution; this workspace is Linux.

A real desktop browser and Windows known-folder behavior are not certified by
Linux watcher, KDialog or controlled-launcher fixtures. Native Wayland chooser
behavior was not exercised; the Linux chooser run explicitly used Qt's X11 backend.

Phase 5 remains incomplete until those required branches have current runtime
acceptance. The Modrinth run and synthetic/manual tests must remain separately
identified in release notes.

## Additional local validation

Before the administrator-key decision, the credential build gate rejected a fictitious key without the agreement
assertion. With an explicitly fictitious packaging credential, the universal
archive contained exactly the generated resource; the source archive contained
neither that resource nor the credential bytes. Neither task log contained the
credential. A subsequent build without inputs replaced the old generated value
with an empty runtime resource. This validates packaging mechanics, **not**
provider permission or a working CurseForge key. That earlier build-time mechanism
has been removed; the report is historical. The temporary inspection report
is `/tmp/neosync-phase5-key-packaging-report.json`.

The final provider/manual increment passed 240 JUnit tests on Linux/JDK 21.0.2,
with zero failures, errors or skips, plus formatting checks. This includes
provider TLS/header confinement, redirect/encoding/size rejection, explicit
permission handling, source/byte mismatches, changed audit evidence, staged
browser imports, cancellation, timeout, explicit selection, XDG parsing and a
mixed manual/HTTPS-hosted fixture transaction with failed-replacement recovery.
The HTTPS-hosted file in that test is a separate generated private artifact;
it is not a fallback for the manual file.

During the next installer build, a Mojang repository HEAD request failed with EOF;
a retry then stalled in the inherited unbounded `LibraryCollector`. Both standalone
HTTP/1.1 and HTTP/2 probes subsequently returned 200, so this was not treated as
proof of an HTTP-version incompatibility. Installer repository discovery now uses
four workers, bounded connect/request/response waits, one transient retry, and
cleanup of outstanding requests. The resulting installer populated fresh client
and server directories and passed `checkFormatting` and
`scripts/prepare_release.py --check`. No upstream coordinates or library bytes
were substituted to work around the failure. Its SHA-256 is
`7632469f643ba9ef37581406fdfd0e9a75de58e3dfb60e16601ba74f36983970`;
its embedded universal contains an empty provider credential resource.

A subsequent source-priority correction passed 242 JUnit tests with zero failures,
errors or skips and formatting checks. Before consent, a confirmed absent
Modrinth version can proceed to a configured exact CurseForge hint; transport or
metadata errors still fail. When multiple CurseForge candidates exist, a
permitted automatic file precedes a manual candidate regardless of manifest
source order. This does not authorize any change after consent. The installed
runs with installer `7632469f...` precede this small selection correction; its
new scenarios were validated by regression tests, not another installed run.

## Installed Linux manual-import fixtures

The following new September 22 runs used installer `7632469f...` above, built
from the tree committed as `7c9ef8b` (provider/manual implementation `e2f420c`
plus the bounded installer probes). Its exact client and server installations
were reused across disposable acceptance game directories. These are synthetic
CurseForge project/file records for the public Clumps 19.0.0.1 bytes and a
controlled `xdg-open` executable, **not** live CurseForge calls, author-restricted
downloads or a real browser session. The actual installed resolver, product
screens, watcher/importer, profile transaction and dedicated server were used.

- `/tmp/neosync-phase5-manual-acceptance`: both default-negative declines opened
  no browser and created no store. After explicit acceptance, the controlled
  launcher opened the one approved fixture page and completed a duplicate-named
  `mod (1).jar.part` by rename in a relocated XDG Downloads directory. The watcher
  imported the exact bytes and prepared an isolated profile. The original file
  remained intact. A new client process verified the prepared profile, loaded
  Clumps and joined the real server; the loading screen closed and the world
  rendered for ten driver ticks. Both install/resume reports ended in `PASS`.
- `/tmp/neosync-phase5-selection-acceptance`: the controlled launcher recorded
  the approved page and deliberately exited with failure. The installed screen
  reported that failure and retained selection. The driver entered the path of
  `Outside browser folder/selected (1).jar` and clicked **Use path**. Preparation
  and the restarted real-server join both passed. The watched Downloads folder
  stayed empty; the selected original retained SHA-256
  `b524ccdace2ef8fd19f5b2074f7de1103ac5065c52553f064c00e098346c293e`.
  There was exactly one approved browser-launch attempt.
- `/tmp/neosync-phase5-chooser-acceptance`: with the same controlled browser
  failure, the product's **Choose downloaded file...** button opened native
  KDialog. Targeted X11 mouse/keyboard input selected a local fixture JAR. Both
  verified preparation and a restarted real-server join passed; the original
  remained intact and Downloads stayed empty. This ran on the KDE desktop with
  `QT_QPA_PLATFORM=xcb`, not native Wayland. The first automation attempt used
  the location bar, navigated to the directory without selecting the file, and
  exceeded the driver's 15-second tick deadline. Its `first-attempt-*` reports
  are failures. After closing that dialog and using a fresh game directory,
  input in the filename field completed the second run. No product change was
  required. The native dialog screenshot was inspected.

Review, manual-selection and joined-world screenshots were inspected. These
results validate Linux fixture behavior with JDK 21.0.2 and the repository's
production launcher harness. They do not certify Windows, external launchers or
the real CurseForge website's download steps. A separate invocation of the built
directory detector found this Linux desktop's configured localized Downloads
directory; the installed watcher runs above used disposable relocated directories.
Temporary reports and screenshots are local evidence and may be removed by the OS.
