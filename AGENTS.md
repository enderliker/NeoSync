# NeoSync — Development Guide

## Project language

Use English throughout the project. This applies to `AGENTS.md`, documentation,
code identifiers, comments, test names, configuration keys and descriptions,
logs, errors, warnings, dialogs, button labels, and all other user-facing text.
Write new and updated project content in English, regardless of the language
used in the conversation. Preserve external names and identifiers when required
for compatibility.

## Scope and status

NeoSync is a fork of NeoForge for Minecraft 1.21.1 intended to simplify installing
the mods needed to join a server. NeoSync is installed on both the client and the
server; the administrator places server mods in `mods`. The project is maintained
in a standalone Git repository with independent history; preserve upstream
source attribution and licenses when importing fixes.

This document records the agreed design and roadmap. The
[Phase 1 investigation](docs/neosync/phase-1.md) documents source-confirmed
integration points, protocol and profile decisions, and remaining runtime checks.
Discovery and requirements reporting are implemented in
[Phase 2](docs/neosync/phase-2.md). [Phase 3](docs/neosync/phase-3.md) adds consented
external downloads, verified isolated profiles, and manual activation instructions.
The installed-build one-mod flow has passed. [Phase 4](docs/neosync/phase-4.md)
adds restricted hosting with a newly generated administrator-authored mod passing
installed download/restart/join acceptance in the alpha.3 build;
automatic restart is not implemented.
Keep this distinction explicit as each phase
progresses; a design or source trace is not a runtime compatibility result.

## Product goal

A user who already has NeoSync should be able to attempt to join a compatible
server, review its required mods, accept their download, prepare an isolated
profile, and restart Minecraft to play. Users should not need to find and install
each mod manually.

The server proposes the set of files; the user retains control over which code is
installed. Discovery can be automatic; installation requires informed consent.

## Expected flow

1. The server prepares a manifest describing the files its clients need.
2. When the user attempts to join, the client detects synchronization support
   before checks that reject connections for incompatible or missing mods.
3. The client retrieves and validates the manifest, compares the profile, and
   determines required downloads, sizes, and sources before requesting consent.
4. The interface shows the server, the total number of required mods, files
   already available, and files to download. Users can review names, versions,
   sources, sizes, and changes, including removed or replaced files.
5. The user can cancel or accept installation of the displayed set.
6. Files from unverified sources require additional confirmation listing their
   names and sources, with a warning that they can execute code. For files hosted
   by the server, explicitly identify that server as their source. The default
   selected option must be **No, cancel**; the alternative must explicitly accept
   those files, for example **Yes, download these files**.
7. NeoSync downloads into a temporary staging area, verifies every file, and
   prepares the profile. An incomplete operation does not alter the active
   installation.
8. Ask whether the user wants to restart to apply the mods. If they postpone the
   restart, keep the profile prepared without attempting to load mods into the
   running game.
9. After restarting, verify the profile and offer to reconnect to the server.

Rejecting an installation must not modify files in the active installation. If
required mods are missing, explain why the user cannot join the server.

## Sources and trust

A URL inside a mod does not establish that the mod is safe. It might point to a
project page, a different file, or a compromised domain. The absence of a URL does
not establish that a mod is malicious either.

Classify downloads by source:

| Source | Treatment |
| --- | --- |
| File identified through a recognized provider and matching the required version | Show the provider and independently verify the file's identity when possible. |
| External URL proposed by the server or declared in mod metadata | Show the domain, warn that the source is unverified, and request additional consent. |
| File provided directly by the server | Identify the server as the source and request additional consent with **No** selected by default. |

Do not present any category as a safety guarantee. In the interface, prefer
"unverified source" or "provided by the server" over declaring a file malicious
or safe without evidence.

A hash announced by the server verifies that the download matches its manifest;
it does not prove that the file is trustworthy. Cross-check with an independent
provider when possible.

## Proposed architecture

### Discovery before joining the game

Define a protocol that works even when the client does not yet have the server's
mods. Investigate the actual NeoForge integration point before choosing classes,
events, or packets. Version the protocol independently of NeoSync and handle
incompatible versions explicitly.

### Manifest

The manifest must describe at least:

- The identity and version of the file set, together with the protocol version.
- Compatible Minecraft and NeoSync versions.
- Each mod's identifier and version, associated with the file that contains it.
- Each file's size and SHA-256 hash.
- Dependencies, whether each item is required, and whether it is needed on the
  client.
- Download sources and provider identifiers, when available.

Do not automatically send every server JAR to the client. Some mods run only on
the server, and their metadata may be insufficient to make that determination.
Allow administrators to correct or confirm the selection and download sources.

### Source resolution

Identify exact files through supported providers. Treat metadata URLs as hints,
never as automatic authorization to download. Allow sources explicitly configured
by the administrator and respect provider and mod download and redistribution
restrictions.

The planned source preference for each exact required artifact is:

| Priority | Source | Planned acquisition |
| --- | --- | --- |
| 1 | Modrinth | Automatic download through the provider when the exact file is available and downloads are permitted. |
| 2 | CurseForge with third-party downloads enabled | Automatic download through the provider for the exact file. |
| 3 | CurseForge with third-party downloads disabled | Open the exact file page in the browser after a clear notice and consent, then detect and verify the local download. |
| 4 | Server hosting | Only a mod written by the administrator for that server and not published or distributed anywhere else. |

This is a source-selection policy, not permission to silently switch sources
after consent. Automatic acquisition still follows installation review and
acceptance; it means no additional browser step. Match the exact approved bytes,
not just a project name or version label. Phase 5 implementation now includes
exact Modrinth resolution and a CurseForge/manual-import adapter. Modrinth has
passed a new installed flow. Live, in-memory CurseForge metadata probes passed
with an administrator-owned key, but full installed CurseForge acceptance remains
blocked on an applicable provider agreement for retained metadata. The key stays
on the server; clients treat its reported CurseForge metadata as unverified.
Configured direct HTTPS
downloads and restricted server sources remain available. See
[Phase 5](docs/neosync/phase-5.md) for current evidence and limits.

When a mod author disables third-party automatic downloads, the planned fallback
is a browser download from the exact CurseForge project/file page, followed by
local verification and import. Do not use server hosting to work around that
choice. Explain the manual step during review before opening the browser; retain
**No, cancel** as the default for unverified sources. Watch the user's actual
Downloads directory on Linux or Windows, verify the approved size and SHA-256,
and copy into the new isolated revision through normal transactional preparation.
The import flow is implemented, with installed Linux fixture validation; real
restricted CurseForge downloads and Windows runtime acceptance remain pending.
See the [manual download contract](docs/neosync/manual-downloads.md) for consent, platform
handling, verification, and acceptance requirements.

### Server hosting

Restrict hosting to mods written by the administrator specifically
for that server and not published or distributed anywhere else. Require explicit
administrator enablement and confirmation of authorship, distribution status,
and distribution rights. A provider lookup failure does not establish eligibility.
General redistribution permission alone does not expand this product scope.
Never use hosting for third-party mods, author download restrictions, or provider
outages. Serve only eligible files included in the manifest through controlled
identifiers; never allow access to arbitrary filesystem paths. Clients must see
the server as the source and explicitly accept the default-negative warning.

### Isolated profiles

Maintain a separate game directory and mod set for each server. Define how a
server is identified and associated with its profile before implementing
persistence. Do not overwrite the user's regular `mods` directory, mix
incompatible dependencies, or delete personal mods to synchronize with a server.

Profile isolation protects the organization of the installation. It is not a
sandbox: loaded mods retain the permissions of the Minecraft process.

### Restart and launcher support

First guarantee a "profile prepared, close and reopen" flow with instructions
that let the user activate that profile. Add automatic restart and reconnection
where launcher integration is reliable. Do not assume the process can relaunch
correctly with every launcher. Do not copy or log session credentials to implement
restart support.

### Fork maintenance

Keep changes inside NeoForge concentrated in a small number of integration points
and separate synchronization logic into modules. Keep the base NeoForge, NeoSync,
and protocol versions identifiable to simplify upstream updates and compatibility
diagnostics.

## Security rules

- **Specific consent:** show files and sources before downloading. Adding files
  or changing their hashes invalidates previous consent for those changes.
  Previous trust in a server does not grant unlimited permission for future
  installations. Switching to an unverified source requires the corresponding
  confirmation; never make that switch silently.
- **Transport and integrity:** use HTTPS for external sources, enforce time and
  size limits, and verify hashes before activating files. Also define and verify
  transport protection and server identity for files hosted directly by a server.
- **Untrusted URLs:** restrict schemes, ports, destinations, and redirects;
  validate every effective destination to prevent induced access to `localhost`,
  routers, or internal services. Design explicit handling for legitimate LAN
  servers without allowing arbitrary access to the local network.
- **Untrusted input:** bound manifest size, entry count, and downloads. Validate
  identifiers, duplicates, and paths. No server-supplied name may allow writes
  outside the profile, including through `../../` or symbolic links.
- **Transactional installation:** download and verify in a temporary staging
  area; activate the set only after completion. Preserve the previous profile
  when connections fail, disk space runs out, or verification fails.
- **Restricted file service:** enforce request, concurrency, and bandwidth
  limits; scope access to the manifest and use temporary tokens where appropriate.
  Never expose configurations, worlds, or credentials.
- **Analysis without execution:** read JAR metadata without loading classes or
  running initializers. Also bound compressed file and metadata processing.
- **Transparency:** explain changes to the set and limits of trust. Do not promise
  complete malware detection or execution isolation.

## Roadmap and exit criteria

### Phase 1 — Design and feasibility

Define the protocol, threat model, manifest, server identity, profiles, and
launcher compatibility. Review actual NeoForge integration points.

**Exit criterion:** demonstrate how to detect NeoSync before rejection for missing
mods and how to activate the prepared profile with a supported launcher.

**Design status:** documented in [Phase 1](docs/neosync/phase-1.md) and the
[version 1 protocol](docs/neosync/protocol-v1.md), with structural schemas and an
example manifest. Source inspection establishes the pre-login status path and
the launcher game-directory path into FML. The corresponding runtime acceptance
checks remain assigned to Phases 2 and 3; no launcher is runtime-certified yet.

### Phase 2 — Discovery

Advertise server support and request, bound, and validate the manifest. Compare
it with the client and present the requirements.

**Exit criterion:** show what is missing and why the user cannot join, without
downloading mods.

**Implementation:** a client join hook requests status before gameplay login,
fetches a bounded HTTPS manifest, and reports required files and versions. The
server advertises an explicitly selected inventory and serves only its manifest.
See [Phase 2](docs/neosync/phase-2.md) for setup, validation, and current limits.

### Phase 3 — Installation MVP

Implement sources explicitly configured by the administrator, consent, verified
downloads, transactional preparation, and isolated profiles. Include instructions
for a manual restart.

**Exit criterion:** complete the flow with one mod: discover, review its source,
accept, download, verify, prepare the profile, restart, and join.

**Implementation:** the installed graphical client completed this flow with
Clumps 19.0.0.1 and a real dedicated server using the repository's production
launcher harness. This verifies game-directory selection, not a specific external
launcher. See [Phase 3](docs/neosync/phase-3.md) for supported sources/artifacts,
security coverage, manual activation, and remaining validation limits.

### Phase 4 — Server hosting

Implement restricted file transfer, administrator enablement, additional
confirmation, quotas, and failure recovery for administrator-authored mods unique
to that server, under the hosting eligibility rules above.

**Exit criterion:** install an eligible administrator-authored mod unavailable
elsewhere while respecting consent, integrity, and distribution requirements;
reject ineligible third-party files from the hosting inventory.

**Implementation:** the alpha.3 build passed the installed flow with a
fresh administrator-authored fixture and rejected ineligible third-party hosting.
Byte-bound eligibility declarations, verified snapshots, bounded HTTPS transfers,
default-negative consent and transactional profile preparation are implemented.
See [Phase 4](docs/neosync/phase-4.md) for actual execution evidence and limits;
public hosting does not inherit Minecraft login restrictions or prove authorship.

### Phase 5 — Automatic source resolution

Integrate providers, identify exact files, and handle download restrictions. Do
not confuse a project page with a direct file link.

Include the [browser-assisted manual download flow](docs/neosync/manual-downloads.md)
for author restrictions. Keep those restrictions visible and preserve explicit
consent; this subset cannot promise a zero-click installation.

**Exit criterion:** reduce manual configuration without substituting different
file versions or hiding changes in source.

**Implementation status:** in progress in the published alpha.4 prerelease.
Modrinth passed installed lookup/review/download/restart/join acceptance.
Installed Linux browser-import fixtures using synthetic CurseForge metadata passed
preparation/restart/join, including watcher, explicit-path and native KDialog
selection. CurseForge lookup
now uses the server administrator's own environment key and reports a missing key
to that administrator. A real administrator-owned key passed in-memory permitted
and restricted file metadata probes. No provider agreement for retained metadata
or installed CurseForge download/browser acceptance is recorded. Do not mark
Phase 5 complete from metadata probes or fixture tests.
See [Phase 5](docs/neosync/phase-5.md).

### Phase 6 — Restart and updates

Integrate supported launchers, reconnection, comparison of file sets, mod removal
and replacement, and restoration of the previous profile.

**Exit criterion:** rejoin updated servers through a clear, recoverable flow;
retain manual restart when automatic restart is unsupported.

### Phase 7 — Hardening and beta

Test malicious manifests, URLs and redirects, manipulated paths, corrupted
downloads, interruptions, insufficient disk space, and real mod combinations.
Also verify declined consent and profile recovery.

**Exit criterion:** a beta with documented compatibility, limitations, and
validation. Security checks must accompany every phase; this phase expands
coverage before release.

## Code comments

- Write comments only when they provide necessary context that the code does not
  clearly express. Keep all comments in English.
- Prefer clear names, straightforward control flow, and small functions before
  adding a comment to explain confusing code.
- Explain why a decision exists rather than restating what the code does. Useful
  cases include subtle algorithms, security assumptions, invariants, concurrency
  constraints, protocol requirements, and compatibility workarounds.
- For workarounds, explain the underlying limitation and, when known, the
  condition for removing the workaround. Reference a relevant issue or
  specification when it adds useful context.
- Use Javadoc where callers need a contract that is not clear from the signature,
  such as side effects, ownership, thread requirements, or failure behavior, and
  where required by repository conventions. Do not add boilerplate documentation
  to every class, method, parameter, or accessor.
- Keep comments concise, accurate, and close to the code they explain. Update or
  remove them when changing the related behavior.
- Avoid narration of obvious steps, decorative section banners, commented-out
  code, and change history in comments. Version control records past changes.
- Add TODO or FIXME comments only for concrete unresolved work, with enough
  context to act on it and an issue reference when available. Do not use them as
  substitutes for completing required work.
- Preserve required license headers and legal notices.

## Working practices

### Visual identity

- Use NeoSync's geometric N and wordmark for the product, installer, and project
  documentation. The editable SVGs in `docs/assets` are the source of truth;
  regenerate raster assets with `scripts/render_branding.sh`.
- Keep the identity simple: flat colors, clear typography, and no decorative
  mascots or generated illustrations. See [the branding notes](docs/neosync/branding.md).
- Preserve upstream copyright, license, and contributor notices. Keep NeoForge's
  name where it identifies the underlying API, dependency, or compatible version;
  branding changes must not alter technical identifiers required by mods.

### Releases

- Name release tags and titles `NeoSync-<neosync-version>-neoforge-<base-version>`,
  for example `NeoSync-0.1.0-alpha.1-neoforge-21.1.251`. Use the same prefix for
  downloadable assets, followed by `-installer.jar`, `-universal.jar`, or
  `-sources.jar`. The startup graphics library uses `-earlydisplay.jar` and
  `-earlydisplay-sources.jar`; ship its matching sources and upstream notices.
  Keep the Minecraft version explicit in release notes.
- Version NeoSync independently of its NeoForge base and synchronization protocol.
  Keep `neosync_version` in `gradle.properties` and the runtime NeoSync identifier
  aligned. Alpha, beta, and release-candidate versions must be GitHub prereleases.
  A successful MVP acceptance flow is not a stable-release compatibility claim.
- Use one installer for both the client and dedicated server, as NeoForge does.
  Bundle NeoSync's own universal JAR and binary patches; never distribute
  generated Minecraft game JARs, installed game directories, test mods, worlds,
  accounts, certificates, or credentials as release assets.
- Give launcher profiles and installed fork artifacts unique versioned paths.
  Preserve the base NeoForge version presented to mod dependency checks. FML's
  inherited local Maven layout may be retained for compatibility, but do not
  upload modified artifacts to upstream Maven coordinates or fall back to an
  upstream universal JAR when installing NeoSync.
- Before publishing, validate the exact installer on both client and server,
  run relevant tests and formatting checks, inspect asset contents, and provide
  SHA-256 checksums and the source commit. State which operating systems,
  launchers, and gameplay flows were actually tested, and any concrete limits.
- Publish from a pushed commit and an immutable tag. Never replace released
  binaries or move a published tag; issue a new NeoSync version for corrections.
  See the [release guide](docs/neosync/releases.md) for packaging and publication.

### Contributions

- Follow [NeoSync's contribution guide](docs/CONTRIBUTING.md) for this fork's
  development workflow and contribution terms. Preserve inherited licenses and
  ownership notices; upstream CLA and Discord requirements are not prerequisites
  for new NeoSync contributions.
- Use this document as the guide to the accepted design, always distinguishing
  planned behavior from implemented and verified behavior.
- Prioritize the complete flow with one mod before expanding automatic resolution
  or extensively polishing the interface.
- When implementing, review applicable instructions and actual code before
  deciding on integrations. Do not invent APIs or assume feasibility is proven.
- Keep changes focused and document decisions that alter this design.
- Run checks appropriate to each change and report results and material
  limitations. Do not claim compatibility without evidence.
- Keep internal implementation details out of product screens unless they help
  users decide what to install or how to resolve a problem.
