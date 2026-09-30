# Server administration

The dedicated server starts an HTTPS administrator panel by default at
**https://MACHINE-IP:6742**. It is separate from the game port and from
the manifest/download service. On the first server start, NeoSync creates
`config/neosync-server.json` with synchronization disabled and the HTTPS defaults;
an existing file is preserved. The panel starts before synchronization is enabled.
Java 21 with `keytool` is required for first-time certificate generation.

## First sign-in

Read `config/neosync-admin/password.txt` locally as the account running the server.
NeoSync generates a random 256-bit password and preserves it across restarts. The
private directory is restricted to that account (POSIX 700/600 or an owner-only
Windows ACL). Passwords never appear in server logs or HTTP responses. Do not
publish this directory, place it in a public web root, or include it in backups
available to players. Protect your server account as you would the server itself.

In HTTPS mode, the panel uses a locally generated TLS certificate. Before accepting the browser's
initial certificate warning, compare its SHA-256 fingerprint with the fingerprint
printed in the local server log. This is an explicit identity check: a warning
alone does not establish which machine you reached. The certificate includes
localhost and the IP addresses present when generated. Use a literal IPv4 address
and the configured panel port; arbitrary Host names are deliberately rejected.
A public IPv4 address forwarded through NAT is accepted by the panel, though the
generated certificate may not cover that public address. HTTPS still requires a
certificate matching the address used in the browser.

The public `config/neosync-admin/certificate.pem` may be copied through a trusted
channel for certificate enrollment. Never distribute `tls.p12`,
`keystore-password.txt`, or `password.txt`. To rotate the login password, stop the
server, delete only `password.txt`, and restart. Sessions expire after 30 minutes
and do not survive restart. Sign out when finished.

## Select client requirements

The panel lists JARs from the loaded server `mods` inventory. Enable synchronization,
choose the server name, and select only files needed by clients, including required
dependencies. NeoSync does not assume that every server mod belongs on the client.
Development automatically checks CLIENT and BOTH files in the first-run selection
and suggests newly added files in the panel. SERVER and UNKNOWN files remain
unchecked. Saved administrator choices are preserved, including unchecked files;
legacy configurations without review tracking keep their existing selection.
Review and save suggestions before restarting. Synchronization remains disabled
by default, and player download consent is unchanged.

Environment detection reads explicit local `[modproperties.<modId>]`
`neosyncSide="CLIENT"`, `"BOTH"`, or `"SERVER"` declarations for every mod in a
JAR, then tries exact Modrinth SHA-512 matches and project side metadata. If still
unknown, it tries exact CurseForge fingerprint/SHA-1/size matches and live file
environment tags whose type is identified by the live Minecraft version-type
catalog. CurseForge responses and API-derived identifiers, URLs and hashes are
never cached, logged, or persisted by detection. Only the reviewed local file
names and selection are saved. Missing tags, ambiguous data, provider restrictions
and unavailable access produce UNKNOWN and a console warning, not a guessed side.
New provider batches stop after a 30-second startup lookup budget (an in-flight
bounded request may finish later); remaining files require manual review.
Dependency `side`, entrypoint sides, mod names, and server-pack flags are not
evidence that an entire mod is client-required. Provider project tags are a
reviewable hint, not proof of runtime compatibility; required dependencies and
bundled mods must still be reviewed. Detection inspects metadata without executing
mod classes and never authorizes hosting or bypasses source restrictions.

For NeoForge JarJar dependencies declared inside a selected JAR, beta.6 includes
the bundled mod identities in that file's manifest entry. Select a separate file
only when the dependency is actually distributed as a separate JAR.

- **Modrinth → CurseForge:** development resolves the exact existing JAR through
  Modrinth SHA-512, then fresh CurseForge fingerprints, SHA-1 and size. Both server
  and client need a credential-bearing build for CurseForge. Published betas
  retain Modrinth-only resolution. Clients independently verify the live provider
  identity and both provider and manifest hashes.
- **Providers, then eligible server hosting:** only for your own unpublished server-specific mod.
  Check all three authorship, exclusive-distribution and distribution-rights
  declarations for its displayed hash each time you save it. Third-party mods do
  not qualify. Both providers must return successful exact misses before this
  development fallback can host. An outage, missing credential or denied download
  blocks resolution instead. Existing explicit hosting configurations retain
  their byte-bound eligibility checks.
- **Keep configured external source:** preserves an existing explicitly configured
  HTTPS source; the panel does not accept arbitrary download URLs.

Save updates `config/neosync-server.json` atomically. Restart the server to apply
it. Until restart, clients continue to see the current published manifest. A stale
browser view or a changed JAR is rejected without replacing the configuration.
The startup process validates dependencies, sources and snapshots before
advertising discovery. Check the server log for success; saving alone does not
prove that providers have the selected files or that the set is compatible.
Before inviting a client, confirm the log says `NeoSync discovery enabled` and
that the advertised manifest port accepts connections from the client network.
If the log says `NeoSync discovery could not start`, fix the reported inventory or
source error and restart the server.

For an initial setup the panel selects `managed-https` on port 8443, using the same
local TLS identity. Clients must trust that certificate through explicit enrollment
in their launcher's Java trust store; NeoSync does not disable TLS verification.
For public servers, configure a publicly trusted certificate using `https` mode
or the loopback `reverse-proxy` mode from [Phase 2](phase-2.md#server-setup).
The panel preserves that existing transport configuration when saving selections.
The generated local certificate is not a public certificate authority.

## Client inventory revision

Development writes `config/neosync-client-inventory.json` at server startup using
only the JARs in the saved client download selection (`files` in
`config/neosync-server.json`). CLIENT and BOTH detection supplies defaults and
suggestions; the administrator's saved selection remains authoritative. Unchecked
mods, server-only files, and non-JAR files do not affect this record. Suggested
new files join the record only after they are saved and the server restarts.

The record contains each selected filename, locally computed SHA-256 and size,
`firstSeenAt`, the set's `inventorySha256` and `changedAt`, and the last batch's
`added`, `replaced` and `removed` filenames. Timestamps are UTC detection times at
startup, not a claim to know when files were copied while the server was stopped.
Hashing detects replacements even when file sizes and modification times match.
Selecting or unselecting a JAR changes the set even without changing the folder.
Unchanged sets preserve the record's exact bytes and timestamps. Updates replace
it atomically; unsafe, missing selected, or corrupted files block synchronization
rather than publishing a partial set. The administrator panel remains available
to correct the selection. Running servers keep their published snapshot until
restart; no hot-loading is attempted.

After status discovery and the existing HTTPS/LAN or explicit HTTP transport
checks, the first NeoSync HTTP resource requested by the client is
`/.well-known/neosync/v1/servers/<game-port>/revision.json`. This bounded public
JSON projection contains the selected inventory and change times, the server ID,
and the current manifest digest; it never includes unselected mods, filesystem
paths, credentials, or CurseForge API-derived data. It is served from an immutable
startup snapshot, not by exposing the configuration directory.

If that revision matches the verified active server profile and the actual loaded
JAR hashes, mod versions and loader requirements, NeoSync proceeds directly to
normal Minecraft connection checks without fetching the full manifest or querying
providers. Otherwise it fetches the full manifest and retains the review,
installation consent and restart flow. A legacy server's HTTP 404 for the revision
uses full manifest discovery; malformed, inconsistent, or failed revision
responses are errors, not permission to skip verification. Dates alone never
authorize joining or installation. Source or loader changes also invalidate the
fast path through the manifest digest, even if the selected JAR inventory matches.
CurseForge still uses fresh lookups when installation requires provider metadata;
neither this record nor the fast path caches its responses. Startup source
resolution is unchanged and may still require provider access.

Validation uses unit and local HTTPS transport tests. An installed client/server
multiplayer acceptance run for this new revision flow has not been performed.

## HTTP and HTTPS settings

Beta.4 adds independent transport controls under **Connections** in
this panel. Both default to HTTPS. Choose the player and panel transports, select
**Save changes**, then restart the dedicated server. The panel shows its address
for the next start. A running beta.3 installation must first be updated to a build
with this feature; editing the source tree does not update installed JARs.

For local configuration, this enables HTTP for both services:

```json
{
  "enabled": true,
  "displayName": "Local server",
  "mode": "http",
  "bindAddress": "0.0.0.0",
  "port": 8080,
  "httpPort": 8080,
  "adminTransport": "http",
  "adminPort": 7654,
  "files": []
}
```

Keep your reviewed `files` and `hosting` entries when editing an existing
configuration. `port` is the manifest listener; `httpPort` is the port advertised
to players. HTTP accepts advertised ports 80 or 8080. HTTPS accepts 443 or 8443,
using `httpsPort` instead of `httpPort`. Do not specify both port fields.
`adminTransport` is `https` by default. `adminPort` selects the panel listener
port, defaults to 6742, and accepts any free TCP port from 1 to 65535. It must
differ from the enabled manifest listener's `port`. With the example above, open
`http://MACHINE-IP:7654/` after restarting and allow or forward TCP 7654 to that
same server port. These settings do not change Minecraft's game port.

The panel switches player transport between `http` on 8080 and `managed-https`
on 8443. An existing `https` or `reverse-proxy` configuration is preserved when
HTTPS remains selected. To return from HTTP to a custom certificate or reverse
proxy, configure that mode in the JSON file. Plaintext reverse-proxy backends
still bind only to loopback and still advertise HTTPS to players.

HTTP is unencrypted. It cannot authenticate the server or prevent someone on the
network from replacing both a manifest and its announced hashes. Every HTTP
connection attempt shows a default-negative client warning before fetching the
manifest; installation and unverified-file consent remain separate. HTTP does
not bypass destination restrictions, hash/size checks, or restricted-hosting rules.
Modrinth, CurseForge and external URLs still use HTTPS, and HTTPS failures never retry as HTTP.
HTTP and HTTPS profiles have different origins and do not share implicit trust.

An HTTP panel also exposes login passwords and session cookies to the network.
Its banner states this before sign-in. Authentication, exact-origin checks, CSRF,
HttpOnly cookies, SameSite=Strict and request limits remain active; HTTP uses a
separate cookie name because the HTTPS `__Host-` cookie requires Secure transport.
The panel does not redirect or change protocol until the server restarts.

## Validation

The installed Linux panel passed browser authentication, selection, injection and
responsive-layout checks. See [Phase 7](phase-7.md) for the tested build and platform
limits. Windows ACL handling is implemented; beta.4 CI exercised installed dedicated-server startup. Interactive Windows panel usage remains unvalidated.

## Access limits

The panel requires same-origin JSON POSTs, a Secure/HttpOnly/SameSite session
cookie and a session-specific CSRF token for changes. It allows at most 32
connections, 16 queued operations, 16 sessions, 300 requests/minute and 10 login
attempts/minute globally. Requests and responses have time/size limits. Pages use
text-only mod labels and a restrictive Content Security Policy. There is no remote
shell, file upload, arbitrary file browser, account export or game restart endpoint.

The panel binds IPv4 on all interfaces at port 6742 by default, or the configured
`adminPort`. Configure the machine's
firewall according to who should administer the server. It does not inherit
Minecraft's allowlist or player permissions. IPv6 panel binding and remote DNS
names are not currently configurable.

### Beta.4 development transport and panel checks

The September 27, 2026 local checks passed 244 JUnit cases, including both
transports for administrator authentication/origin/CSRF/logout and hosted
profile download, integrity, interruption and recovery. Existing HTTPS profile
records and external-source restrictions remain covered. New cases reject mixed
HTTP/HTTPS advertisements and invalid transport configurations and verify
transactional changes back to HTTPS.

A separate real `AdminService` fixture was exercised in Chromium over both HTTP
and HTTPS. Login/logout, persisted transport selections, hosting declarations,
filtering, empty results, and transport notices passed. Desktop (1440 pixels) and
mobile (390 pixels) screenshots were visually inspected with no horizontal overflow.
The fixture used generated credentials and synthetic inventory, not a live server.
Local evidence is under `build/neosync-admin-preview/` and is not a release asset.
These are source-level service and browser results, not an installed HTTP multiplayer
acceptance result. The running server and existing
Prism instances are not updated by a source build.
