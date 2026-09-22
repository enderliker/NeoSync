# Phase 2: discovery and requirements

This records the discovery milestone. [Phase 3](phase-3.md) extends the client
with consented installation and manual profile activation; use that document for
its installation behavior and supported-source limits. [Phase 4](phase-4.md)
adds restricted server hosting in the unreleased alpha.3 development build;
statements below describe the earlier discovery-only milestone.

NeoSync now has a discovery implementation that advertises a server manifest,
retrieves it before gameplay login, and shows required files, versions, sizes,
and sources. It compares the manifest with the client's loaded mod inventory and
file hashes. This phase does not download mods, create profiles, or restart the
game.

## Client behavior

Joining a server opens a cancellable requirements check, including Direct
Connect. A status response without NeoSync support continues to the normal
connection. A compatible advertised manifest produces a scrollable requirements
screen. If the required loader, files, and loaded mod versions match, **Continue
to server** runs the ordinary connection and NeoForge compatibility checks.
Otherwise, the screen explains what is missing or different and offers **Back**.

Malformed capabilities, invalid manifests, and HTTPS failures do not authorize
an ordinary connection automatically. Some transport failures allow an explicitly
labeled **Connect without discovery** action. This action does not synchronize
files and may still result in NeoForge rejecting the connection.

A manifest hostname resolving to a local address requires explicit approval for
that endpoint during the attempt, with **No, cancel** focused by default. TLS
certificate and hostname validation still apply. There is no persistent trust
store or consent database in this phase.

All source candidates are shown as unverified. Provider hints are checked for
valid structure but do not trigger provider lookups. No external artifact URL is
contacted, and a server-hosted source does not expose a download endpoint yet.

## Server setup

Discovery is disabled when `config/neosync-server.json` is absent or has
`"enabled": false`. Enable it only after selecting the actual client-required
JARs. For example:

```json
{
  "enabled": true,
  "displayName": "Example NeoSync Server",
  "mode": "https",
  "bindAddress": "0.0.0.0",
  "port": 8443,
  "httpsPort": 8443,
  "keyStore": "neosync.p12",
  "passwordEnvironment": "NEOSYNC_KEYSTORE_PASSWORD",
  "files": [
    {
      "fileName": "example-mod-1.0.jar",
      "sources": [{"type": "server"}]
    }
  ]
}
```

Replace the example filename with a JAR in the server's `mods` directory that is
also in its loaded FML inventory. `files` is an allowlist, not a directory scan
that sends every server mod. Include its required client dependencies as separate
entries where needed. An empty list explicitly advertises no required artifacts.
Use the `external` source form from the [protocol](protocol-v1.md) when configuring
an HTTPS source. Metadata URLs are not guessed automatically.

For `https` mode, supply a PKCS12 keystore containing the private key and
certificate chain for the logical hostname players enter in Minecraft. Relative
keystore paths resolve under `config`. Set the named password environment variable
before starting the server; do not commit the keystore or password. The client
uses its JVM's normal trust configuration. A self-signed development certificate
must be explicitly trusted in that client's JVM; certificate verification is
never disabled by LAN approval.

Alternatively, terminate TLS with a reverse proxy:

```json
{
  "enabled": true,
  "displayName": "Example NeoSync Server",
  "mode": "reverse-proxy",
  "bindAddress": "127.0.0.1",
  "port": 8088,
  "httpsPort": 443,
  "files": []
}
```

Forward `/.well-known/neosync/v1/servers/` from the server's logical HTTPS hostname
to that loopback backend, preserving the complete path and JSON response. The
plaintext mode refuses non-loopback binds. The advertised HTTPS port must be 443
or 8443. Minecraft status must be enabled. SRV installations must expose HTTPS on
the logical hostname, not only the SRV target. If SRV or port forwarding maps the
public game port to a different listening port, set the optional `gamePort` in
this configuration to the port players enter (usually `25565` for SRV). It
defaults to the Minecraft listening port and selects the manifest route; it does
not change the game listener. A reverse proxy can map additional public port
routes to that same manifest if the server has multiple aliases.

On startup, NeoSync creates `config/neosync-server-id.txt` if needed, validates the
selection, hashes its files, validates the resulting manifest, then starts the
service and advertises the immutable digest. Preserve that identity file across
restarts. A configuration or inventory failure leaves discovery disabled and
produces a server log message. Restart the server after changing the selection;
there is no live reload command in this phase.

The only served resource is the current manifest at:

```text
/.well-known/neosync/v1/servers/<game-port>/manifests/<sha256>.json
```

Other routes return 404; methods other than GET return 405. In particular, there
is no artifact hosting route. The manifest is public and does not inherit the
Minecraft server's whitelist or authentication policy.

## Implementation boundaries

- The status serializer adds the optional field without extending the
  `ServerStatus` record or changing its constructors. A cached decoration handles
  both cached and uncached vanilla status JSON.
- Client discovery uses a bounded status connection followed by a Netty HTTPS
  connection to an approved IP, with TLS verification for the original hostname.
  It does not follow redirects or use ambient HTTP proxies.
- Parsing rejects duplicate JSON keys, unsupported fields, invalid identifiers,
  unsafe source syntax, resource-limit violations, duplicate files/mod IDs,
  reserved platform replacements, and unsatisfied required or incompatible
  dependency constraints.
- The initial server builder uses already loaded top-level server artifacts.
  Client-only distribution inventories and complex nested/library selections
  need further implementation. An unresolved dependency prevents advertisement;
  this build must not be described as supporting every modpack automatically.
- Readiness describes the loaded versions and inspected local files. It does not
  certify that a mod is safe, undo modifications to JARs during a running process,
  or create the isolated profiles planned for Phase 3.
- The manifest service uses fixed Netty event loops, at most 32 connections,
  8 KiB request headers, no request bodies, and a ten-second connection deadline.
  TLS handshakes have a five-second deadline. Client work queues are bounded.
  Artifact bandwidth quotas belong to Phase 4.
- Server identity review, persistent consent, snapshot retention, endpoint
  overrides, provider resolution, and launcher automation remain later work.

See [PORTING.md](../PORTING.md) for the integration points that must be revalidated
when updating the NeoForge base.

## Validation

Validation used JDK 21 on Linux and the generated Minecraft 1.21.1 sources:

- `./gradlew setup`, compilation, patch generation, and `checkFormatting` passed.
- `./gradlew :tests:runUnitTests` passed: **131 tests**, including strict input
  parsing, resource limits, dependency and inventory checks, destination policy,
  cancellation, real TLS connections, hostname/certificate rejection, digest
  mismatch, and restricted manifest routes.
- The generated installer produced a dedicated server that passed
  `:neoforge:testProductionServer`. A temporary mod registering a required network
  channel exercised the real status serializer and manifest builder. An empty
  client inventory reported its missing file before gameplay login. The probe
  also checked an explicitly configured public game port different from the
  listening port.
- An installed graphical client passed an automated local fixture through
  `ConnectScreen.startConnecting`: it displayed the missing mod and its server
  source without starting gameplay login, focused **No, cancel** for local
  endpoint access, returned to its parent on Back, ignored canceled asynchronous
  work, and continued to ordinary login when the capability was absent. The
  requirements screen was captured and visually inspected. The fixture's test
  certificate was trusted only in that test JVM.

The production probes used temporary test mods and isolated installations under
`projects/neoforge/build`; they are not part of the distributable. The GUI fixture
used a local status service, while the required-channel probe used a real
dedicated server. These checks do not certify public DNS/SRV deployments,
third-party proxies, every screen/input configuration, unmodified client
interoperability, or launcher profile activation. Those remain targeted
compatibility checks; no launcher is runtime-certified yet.
