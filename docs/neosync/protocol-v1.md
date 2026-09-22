# NeoSync protocol version 1

Status: implementation contract from [Phase 1](phase-1.md).
[Phase 2](phase-2.md) implements discovery, bounded manifest validation and serving,
and requirements reporting. [Phase 3](phase-3.md) implements reviewed external
downloads, persistent profile associations, transactional preparation, and manual
activation. [Phase 4](phase-4.md) implements restricted server artifact hosting;
automatic restart remains later work. Version 1
initially targets direct connections to Minecraft 1.21.1 servers with public HTTPS
or an explicitly approved, trusted LAN endpoint.

## Discovery

Extend the existing Minecraft status JSON with an optional `neosync` object:

```json
{
  "protocols": [1],
  "httpsPort": 8443,
  "manifestSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
```

This example shows only the value of `neosync`; the digest is illustrative. See
[discovery.schema.json](discovery.schema.json) for its structure. Preserve all
ordinary status fields and existing NeoForge `isModded` behavior. Protocol versions
are independent of Minecraft, NeoForge, and NeoSync release versions. Version 1
uses the digest above only when protocol 1 is supported; a later incompatible
protocol must define its own discovery compatibility rules.

[examples/discovery.json](examples/discovery.json) contains the actual SHA-256
of the example manifest's stored bytes. Recompute it if that example changes.

The client requests status on the game endpoint using the existing address
resolver and Minecraft's blocked-address checks. Normalize the user-selected
logical hostname to its ASCII DNS form, lowercase it, remove a final DNS dot, and
make the default game port explicit. Handle IPv4 and bracketed IPv6 literals with
an address parser, not string concatenation. Preserve the original logical
hostname and game port separately from an SRV-resolved transport address.

Automatic discovery requires the server's status service to be enabled. A proxy
must preserve the NeoSync field and expose a consistent manifest for the logical
address. Disabled status, stripped extensions, backend switching, and proxies
that serve different inventories need explicit handling or remain unsupported;
do not advertise blanket proxy compatibility.

Derive the manifest URL locally:

```text
https://<logical-host>:<httpsPort>/.well-known/neosync/v1/servers/<logical-game-port>/manifests/<manifestSha256>.json
```

No advertised arbitrary hostname, URL, path, query, credentials, or fragment is
accepted. Version 1 permits automatic discovery on HTTPS port 443 or 8443 only;
additional ports require a local endpoint setting for that selected server.
When HTTPS is served on an SRV target or a different domain, the administrator
must also expose it on the logical hostname, or the user must explicitly configure
that endpoint. Do not silently reinterpret SRV targets as HTTPS identities.

Phase 2 adds the field during status JSON serialization, preserving the ordinary
status record and its constructors.
Clients must tolerate unknown ordinary status fields, but validate recognized
NeoSync fields strictly. A malformed NeoSync field must produce a synchronization
error without crashing server-list rendering. Do not interpret malformed or
unsupported capabilities as permission to download or as a valid empty manifest.

## HTTPS service

The service is a separate listener from Minecraft's game protocol. It may use a
configured certificate directly, or sit behind an administrator-managed TLS
reverse proxy. A plaintext backend must bind to loopback and must not become a
public fallback URL. Document certificate, port, firewall, and proxy configuration
as part of server setup. Do not silently add port forwarding or weaken TLS.

The immutable manifest route returns `200 application/json` with UTF-8 bytes for
that digest. Reject a mismatching body. Return `404` for an unknown or retired
snapshot and `429` or `503` for capacity failures. Bound any error body; never
render arbitrary HTML returned by an endpoint. Control endpoints do not redirect,
use cookies, or request account credentials. The client requests
`Accept-Encoding: identity` and rejects compressed control responses.

Only advertise a snapshot after it is valid and available. Hash the exact stored
UTF-8 manifest bytes, including whitespace, with SHA-256. The digest is outside
the manifest to avoid self-reference; canonical JSON serialization is not
required. Publish the status object and its digest together, updating the cached
status JSON. Keep in-progress requests bound to their original snapshot. If it
expires, refresh status once and show any changed requirements before proceeding.

Phase 4 implements this route only for a mod written by the administrator for that
server and not published or distributed elsewhere:

```text
https://<approved-origin>/.well-known/neosync/v1/servers/<logical-game-port>/files/<sha256>
```

Resolve hashes through the published inventory, never as filesystem paths. Serve
only immutable snapshots of eligible administrator-authored JARs, with known size and
`application/java-archive` or `application/octet-stream`. Copy and verify an
artifact into the hosting inventory before publication; changes to the server's
live `mods` folder must not alter a published response. Version 1 starts with full
GET downloads; interrupted files restart from zero. Range/resume support needs
its own validation later.

Initial discovery and manifests are public, like status. The administrator must
explicitly enable direct artifact hosting and confirm authorship, absence of other
distribution, and distribution rights. These are administrator declarations, not
facts proven by a manifest or by unsuccessful provider searches. Third-party mods,
provider outages, and author download restrictions are outside hosting scope;
general redistribution permission alone does not make a file eligible. Do not
claim that public endpoints inherit a Minecraft whitelist, ban list, online-mode
authentication, or server privacy policy. Private authenticated distribution is
outside the initial version; future short-lived tokens must be scoped to the
server, snapshot, artifact, and expiry, and kept out of logs and persistent URLs.

## Server identity

Use the tuple `(logical host, logical game port, approved HTTPS origin, serverId)`
for local association. `serverId` is a persistent server-generated UUID obtained
from the validated HTTPS manifest. It is a continuity label, not a cryptographic
identity and not a filesystem name. Assign a separate random local profile ID.
Never merge profiles solely because two servers report the same UUID.

On first contact, verify the HTTPS certificate and hostname, then display the
server and sources in the installation review. Store the association only when
the user accepts it. A different origin or UUID at a known game address requires
a new identity review; do not silently reuse the old consent or modify its profile.
Normal certificate renewal under the same valid HTTPS hostname does not change
this identity. No custom manifest signatures or certificate pinning are claimed
by the initial design.

HTTPS authenticates control of the named endpoint under the client's trust store;
it does not prove that its operator is honest, bind a Minecraft server key to the
manifest, or make the status response authentic. A forged or suppressed status
can block discovery. It cannot authorize downloading code. A server that is
unreachable or returns stale metadata must not cause automatic rollback or
installation from a different host.

## Manifest contract

The [schema](manifest.schema.json) defines structural constraints. The
[example](examples/manifest.json) uses fictitious files, versions, hashes, and
reserved `.invalid` domains; it must never be used as a live download inventory.

| Field | Meaning |
| --- | --- |
| `schemaVersion` | Manifest format, exactly `1` |
| `serverId` | Persistent server UUID for the local association |
| `revision` | Opaque snapshot label; not an ordered or security-sensitive version counter |
| `displayName` | Plain server label for review |
| `minecraftVersion` | Exactly `1.21.1` for this implementation |
| `loader` | Exact required NeoSync and base NeoForge versions; metadata only, never a loader download instruction |
| `files` | Complete set of client-required JAR artifacts, including required library/provider JARs |
| `files[].sha256`, `size` | Artifact identity and exact number of bytes |
| `files[].fileName` | Display hint only; the client writes `<sha256>.jar` |
| `files[].required` | Always `true` in the MVP; optional selections need a later contract |
| `files[].mods` | Declared mod IDs, versions, display names, and client dependency constraints associated with the artifact |
| `files[].sources` | Ordered candidates; the client determines actual trust locally |

Multiple mod IDs may map to one artifact. A library or language-provider JAR may
have an empty `mods` array, but still appears as a file in the review and requires
consent. UI totals distinguish declared mod IDs, artifact count, and bytes.
Embedded IDs must identify their enclosing artifact; installing both the outer
JAR and an extracted copy is not allowed. Confirm the effective loader-selected
set at startup rather than assuming that every nested candidate becomes active.

Version 1 requires an exact installed loader match before downloads. Show a loader
upgrade requirement when it differs; do not replace NeoSync, NeoForge, Minecraft,
Java, launch arguments, or bootstrap libraries through this protocol. A future
compatible-version policy requires explicit tests and a documented version rule.

Dependency entries specify a mod ID, a Maven version range, and `required`,
`optional`, `incompatible`, or `discouraged`, normalized for the client side. They
are constraints, not additional URLs. Resolve required dependencies within the
selected artifact set or the verified platform inventory; an optional dependency
does not request an automatic download. Reject an unsatisfied required constraint
or an incompatible pair. Surface discouraged combinations for review. Account
for language-loader and embedded-library requirements during inventory validation
and startup, not through guessed entries in a dependency graph.

Each source is one of:

- `external`: an HTTPS URL, optionally with a `provider` hint containing a supported
  provider ID, project ID, and file ID. A hint is a server claim. Only an independent
  provider lookup matching the exact bytes may classify it as identified through
  that provider. Until Phase 5 implements this, external URLs are unverified.
- `server`: no supplied URL. Derive the artifact URL from the already approved
  manifest origin and the artifact hash. Always require the additional warning.

Do not execute metadata, fetch arbitrary project/update pages, or treat a mod's
`displayURL` or update JSON as a direct artifact location. Fetching provider
metadata never authorizes a JAR download. Provider resolution, where available,
occurs before the download review.

The planned preference is Modrinth automatic acquisition, then CurseForge
automatic acquisition when permitted, then a CurseForge browser handoff for
restricted downloads. Hosting is reserved for eligible administrator-authored
mods unavailable elsewhere. Every route must match the exact required bytes and
be covered by consent; this order never authorizes a silent source change or a
different build. Provider automation remains future work.

For author-disabled third-party downloads, the accepted future fallback is an
explicit browser handoff to the exact CurseForge file page and verified local
import, not server hosting. See the [manual download design](manual-downloads.md).
This is not implemented and introduces no valid source fields in the current
schema. Do not configure an HTML file page as an `external` artifact URL. Define
and validate any required protocol/schema extension before implementing the flow;
existing clients must continue to reject unsupported sources explicitly.

## Validation beyond JSON Schema

Enforce these in code even when structural validation passes:

- Strict UTF-8, duplicate JSON key rejection, depth and byte limits, safe integer
  arithmetic, and total counts. Reject unknown fields in version 1 rather than
  accidentally acting on a future instruction.
- Unique file hashes and unique effective mod IDs; permit several distinct IDs
  in one file. Check the dependency graph and built-in platform requirements.
- No control characters, terminal escapes, formatting codes, clickable commands,
  or markup interpretation in labels. Truncate display text safely in the UI.
- URI parsing, TLS identity, destination checks, redirect policy, normalized
  source comparison, and size/hash verification. Schema patterns are not URL
  security checks.
- JAR structure and bounded metadata analysis without executing code. Do not
  extract arbitrary entries to disk. Check declared metadata against the artifact;
  reject mismatches instead of silently editing the approved manifest.
- SHA-256 and actual sizes for local reuse, downloaded files, and server snapshots.
  A filename, timestamp, provider label, or equal mod version is insufficient.

## Destination policy

Use a shared destination validator for client downloads and server-side source
resolution. External sources must use HTTPS, no user information or fragments,
and port 443 or 8443 unless the user explicitly configures an endpoint. Reject
loopback, private, link-local, multicast, unspecified, reserved, and metadata
service destinations, including IPv4-mapped IPv6 forms.

Validate DNS results and bind the connection to an approved address while still
checking TLS for the original hostname. Do not validate one resolution and let an
HTTP client connect using an unchecked second resolution. Disable automatic
redirects and ambient proxy routing unless the same policy can be enforced.
External artifact redirects are limited to three hops, with all checks repeated
at each hop. A changed source origin must be included in consent before fetching
artifact bytes there; if it is discovered only after acceptance, pause and request
an updated decision. Do not forward credentials or cookies across origins.

The Phase 3 MVP accepts stable direct URLs without query parameters. It follows
only same-origin redirects; a different origin stops the attempt and requires an
administrator-configured direct URL followed by a new review. It does not persist
query tokens or silently extend the accepted source set.

An explicitly selected private/LAN server may use its exact approved endpoint
for the manifest and server files if TLS identity is valid. This does not grant
external URLs access to other local destinations. A hostname resolving to a
private address without an existing local endpoint decision requires explicit
review before HTTP access. Never implement LAN support by globally accepting
self-signed certificates. Out-of-band certificate enrollment is future work;
the initial LAN setup needs a certificate trusted by the client.

## Consent and state transitions

```text
IDLE -> DISCOVERING -> VALIDATING -> REVIEW_REQUIRED
REVIEW_REQUIRED -> CONSENTED -> DOWNLOADING -> VERIFIED -> PREPARED
PREPARED -> RESTART_PENDING -> ACTIVE_PROFILE_CHECK -> NORMAL_LOGIN
```

Discovery without a capability may continue to ordinary login. A validated
matching active profile can skip download review and proceed to normal login.
An empty file set is valid only after successful manifest validation; remove any
previously managed files only through a reviewed new revision. A matching set in
a different profile still requires activation rather than loading it in place.

From any pre-activation state, cancellation or failure leaves the active profile
unchanged. Temporary unverified downloads can be removed. Cancellation is final
for that attempt even if background I/O completes afterward.

Bind consent to the server identity, exact manifest digest, hashes, file changes,
and effective sources. New files, new hashes, removals, replacements, or changed
sources require a new review. A source fallback to server hosting never inherits
permission from an external URL. The additional warning lists every affected
artifact and selects **No, cancel** by default; acceptance uses **Yes, download
these files**. Pressing Enter on the default must not approve the download.

Show **Later** and **Restart instructions** for the manual MVP. Reserve **Restart**
for a launcher integration that can actually perform the action. A prepared
revision is not active until a new process selects its game directory and verifies
its local record. After a restart, refresh discovery; if requirements changed,
repeat review instead of recursively restarting or downloading without consent.

## Initial resource limits

These are client-enforced defaults and server implementation targets. A server
cannot raise client limits in a manifest. Exceeding a limit gives a clear error;
it never truncates an inventory into an apparently valid set.

| Resource | Limit |
| --- | --- |
| NeoSync status capability | 512 UTF-8 bytes; at most 8 protocol IDs |
| Status attempt | 5 seconds total; preserve Minecraft packet/string bounds |
| Manifest response | 1 MiB, no content compression, nesting depth at most 16 |
| Manifest entries | 2,048 artifacts; 8,192 mod IDs total; 64 IDs per artifact |
| Labels and identifiers | Bounds in the schema; URLs at most 2,048 characters |
| Artifact bytes | 512 MiB each; 4 GiB total per selected set |
| Client transfer concurrency | 4 streams |
| HTTPS connection / manifest deadline | 5 seconds / 10 seconds |
| Artifact idle / total deadline | 30 seconds / 15 minutes, with cancellation |
| External artifact redirects | 3; no control-endpoint redirects |
| Metadata read from one artifact | 512 KiB per metadata entry; 4 MiB total |
| Nested archive inspection | Depth 4, 10,000 entries total, 16 MiB inspection budget |
| Server service defaults | 8 concurrent artifact streams and a configurable total bandwidth cap; bounded request queue |

Check space for temporary downloads, cache copies, the prepared profile, and a
reserve before starting; enforce byte limits during streaming even with an absent
or misleading `Content-Length`. Use overflow-safe accounting. Persistent-cache
quotas and cleanup must preserve active, prepared, and recoverable revisions.
No amount of parsing or transfer validation makes accepted mod code sandboxed.
