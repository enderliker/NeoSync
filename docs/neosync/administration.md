# Server administration

Current development starts an HTTPS administrator panel on every dedicated
server at **https://MACHINE-IP:6742**. It is separate from the game port and from
the manifest/download service. It starts even before synchronization is configured.
Java 21 with `keytool` is required for first-time certificate generation.

## First sign-in

Read `config/neosync-admin/password.txt` locally as the account running the server.
NeoSync generates a random 256-bit password and preserves it across restarts. The
private directory is restricted to that account (POSIX 700/600 or an owner-only
Windows ACL). Passwords never appear in server logs or HTTP responses. Do not
publish this directory, place it in a public web root, or include it in backups
available to players. Protect your server account as you would the server itself.

The panel uses a locally generated TLS certificate. Before accepting the browser's
initial certificate warning, compare its SHA-256 fingerprint with the fingerprint
printed in the local server log. This is an explicit identity check: a warning
alone does not establish which machine you reached. The certificate includes
localhost and the IP addresses present when generated. Use the machine's literal
IP and port; arbitrary Host names are deliberately rejected. A changed address
may require regenerating the TLS identity while the server is stopped.

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

Save writes `config/neosync-server.json` atomically. Restart the server to apply
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

## Access limits

The panel requires same-origin JSON POSTs, a Secure/HttpOnly/SameSite session
cookie and a session-specific CSRF token for changes. It allows at most 32
connections, 16 queued operations, 16 sessions, 300 requests/minute and 10 login
attempts/minute globally. Requests and responses have time/size limits. Pages use
text-only mod labels and a restrictive Content Security Policy. There is no remote
shell, file upload, arbitrary file browser, account export or game restart endpoint.

The panel binds IPv4 on all interfaces at port 6742. Configure the machine's
firewall according to who should administer the server. It does not inherit
Minecraft's allowlist or player permissions. IPv6 panel binding and remote DNS
names are not currently configurable.
