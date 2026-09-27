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

- **Modrinth:** resolve the exact existing JAR by SHA-512 at the next server start.
  The client independently verifies provider metadata and both hashes.
- **Provided by this server:** only for your own unpublished server-specific mod.
  Check all three authorship, exclusive-distribution and distribution-rights
  declarations for its displayed hash each time you save it. Third-party mods do
  not qualify. A lookup failure does not enable hosting.
- **Keep configured external source:** preserves an existing explicitly configured
  HTTPS source; the panel does not accept arbitrary download URLs.

Save updates `config/neosync-server.json` atomically. Restart the server to apply
it. Until restart, clients continue to see the current published manifest. A stale
browser view or a changed JAR is rejected without replacing the configuration.
The startup process validates dependencies, sources and snapshots before
advertising discovery. Check the server log for success; saving alone does not
prove that Modrinth has the selected files or that the set is compatible.

For an initial setup the panel selects `managed-https` on port 8443, using the same
local TLS identity. Clients must trust that certificate through explicit enrollment
in their launcher's Java trust store; NeoSync does not disable TLS verification.
For public servers, configure a publicly trusted certificate using `https` mode
or the loopback `reverse-proxy` mode from [Phase 2](phase-2.md#server-setup).
The panel preserves that existing transport configuration when saving selections.
The generated local certificate is not a public certificate authority.

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
Modrinth and external URLs still use HTTPS, and HTTPS failures never retry as HTTP.
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
