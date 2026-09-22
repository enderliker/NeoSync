# Phase 5 — Exact provider files and browser imports

Status: implementation in progress on alpha.4; alpha.3 remains the published
Phase 4 release. Phase 5 acceptance is **not complete**. Automatic restart is
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
  API or SDK.” These are concrete unresolved requirements for bundled keys,
  retained manifests and provider audit records. Obtain written clarification or
  an applicable agreement permitting NeoSync's intended behavior before enabling
  a real key or distributing that integration. No permission is inferred from
  other launchers or from submission of the application form.

Metadata requests use fixed API origins, public-address validation, TLS hostname
verification, no redirects, a 2 MiB response cap, strict JSON parsing and bounded
workers/queue/deadlines. Concurrent identical requests share in-flight work;
subscriber cancellation closes unused work. There are no automatic retries.
HTTP 429 and Modrinth exhausted-budget headers establish a provider cooldown,
including `Retry-After` dates or seconds. Failures never trigger silent source
switching. Only supported provider CDN hosts may supply automatic downloads.

## CurseForge prerequisite

The maintainer confirmed on September 22 that the application form has been sent,
but **NeoSync has no API key yet**. Live CurseForge acceptance and checking the
application agreement for extractable desktop distribution remain blocked.
Do not use another application's key or require end users to obtain one. The
agreed model is a NeoSync application key injected at build time and included in
the distributed binary, excluded from Git, source archives and logs. A mandatory
backend is not part of this design. No credential has been distributed by this
work, and the application form is not provider approval.

## New execution evidence

- Provider-hint increment `e84a86d`: 206 JUnit tests passed, no failures, errors or
  skips; `applyAllFormatting` and `checkFormatting` passed on JDK 21.0.2/Linux.
- Modrinth integration increment: 217 JUnit tests passed with no failures, errors or
  skips; formatting passed on JDK 21.0.2/Linux. A separate live HTTPS metadata
  request returned the expected Clumps project/version, 18,382 bytes and SHA-512.
- Installed acceptance: pending. Service fixtures,
  live API calls and installed gameplay results will be recorded separately.
