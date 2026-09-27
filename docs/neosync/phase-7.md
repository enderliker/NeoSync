# Phase 7 — Beta validation and compatibility

NeoSync 0.1.0-beta.1 targets Minecraft 1.21.1, NeoForge 21.1.251, protocol 1 and
Java 21. Modrinth is the only provider. The beta adds server administration,
verified recovery and Prism integration while retaining manual activation.
The beta prerelease uses the validated local candidate's exact JARs. Published
alpha.4 remains unchanged.

## Environment and identity

Validation was performed on September 27, 2026, on CachyOS Linux x86-64 with
Oracle OpenJDK 21.0.2, a graphical desktop, Prism Launcher 11.0.3 and Chromium
through Playwright. Dedicated test servers used loopback game port 25575 and
explicitly trusted fixture HTTPS certificates. These tests use disposable
installations and offline test identities, not a public production server.

The runtime implementation is recorded at commit `34d788c`; later acceptance,
documentation and packaging commits preserve that runtime source. The exported
release's `release-manifest.json` identifies the complete source commit and
all artifact hashes. Packaging-only changes preserve every runtime class and
resource byte used by the update, recovery and hosting acceptance runs; the
manifest order and archive timestamps were made deterministic. Keep the matching
installer, universal, sources,
earlydisplay and earlydisplay-sources archives together.

Final installer SHA-256:
`51bca0c9d112331e751ad3ec5cfbddd682e55739a3cfc70463492eec2d67ce87`.
The final candidate installation and Prism reports are kept under
`/tmp/neosync-beta1-candidate`; the wider runtime acceptance is under
`/tmp/neosync-beta1-verified` and `/tmp/neosync-beta1-hosting`.

## Executed acceptance

| Area | Result |
| --- | --- |
| Unit suite | 236 tests, no failures, errors or skipped tests. Formatting passed. |
| Installer | Fresh client and dedicated-server installation; matching NeoSync runtime on both sides. |
| Modrinth | Live exact lookup, review, both default-negative consent declines, verified Clumps 19.0.0.1 download, isolated preparation and real-server join. |
| Prism | Real instance startup, revision export, pre-launch verification, close/launch handoff and a separate successful resumed-client report. Accounts remain under Prism's control. |
| Updates | Added Ferrite Core 7.0.2 to Clumps and joined with both loaded. Then removed Clumps and replaced Ferrite Core with 7.0.3; review named both changes and the restarted client joined with Clumps absent. |
| Declined updates | Both consent declines preserved the selected pointer and running mod bytes. Accepted updates preserved previous revisions. |
| Recovery | The title-screen browser selected and verified the original Clumps revision. Relaunch joined its restored server configuration with Ferrite Core absent. |
| Interruption | Intentional process halt with exit 73 after revision rename preserved the pointer and left exactly one complete, hash-verified orphan revision. |
| Insufficient space | A real private 64 MiB tmpfs caused the free-space check to refuse preparation without publishing a profile. The host filesystem was not filled. |
| Pre-launch tampering | The exported installed bridge accepted the exact revision, rejected an additional unapproved mod, and accepted the original set after its removal. |
| Administrator panel | Trusted local TLS identity, unauthenticated denial, login/logout, default-negative hosting declarations, refused incomplete declarations, atomic Modrinth selection and text-only rendering of an injected HTML label. Desktop and mobile layouts passed visual inspection. |
| Restricted hosting | A newly generated administrator-authored fixture completed both consent declines, HTTPS download, isolated preparation, restart and real-server join. |
| Hosting rejection | The installed server rejected third-party Clumps with false authorship/exclusivity declarations and advertised no synchronization or hosted artifacts. |
| Packaging | Runtime classes/resources match the installed acceptance builds; archive and embedded-library inspection passed. Repeated forced archive builds in separate Gradle JVMs produced identical bytes. |
| Private files | Installed POSIX directory/file modes were 700/600; the password and TLS identity were not printed or packaged. |

The full unit suite additionally exercises malformed/ambiguous JSON, bounds,
duplicate identities, dependency errors, unsafe destinations, TLS/hostname
failures, redirects, truncated/corrupt downloads, cancellation, deadlines,
symlinks, stale consent, concurrent preparation, write failure, invalid provider
identity, tampered audits and unsafe cache/hosting cleanup. HTTP administrator
tests cover origin, Host, session and CSRF enforcement. These checks do not claim
complete vulnerability detection.

## Launcher and platform matrix

| Launcher/platform | Supported route and evidence |
| --- | --- |
| Prism 11.0.3 / Linux | Installed runtime and complete download/restart/reconnect flow tested. Configure a new local NeoSync instance with `scripts/configure_prism.py`. |
| Production harness / Linux | Explicit game-directory activation, multi-mod updates, replacement/removal, recovery and failure paths tested. This is an acceptance harness, not a consumer launcher. |
| SKlauncher | Manual installed-custom-version/game-directory route documented from official instructions. No runtime certification: the official website's download buttons remained unavailable during the unattended run. The official 3.2.18 Windows installer matched its published hash, but the available Linux extractors could not read its format. No unofficial launcher binary was substituted. |
| Minecraft Launcher | Manual installed-version/game-directory route documented; its GUI was not runtime-tested in this environment. |
| Other launchers | Require installed custom-version metadata, the full JVM/module arguments and an explicit game directory. No generic automatic restart claim. |
| Windows and macOS | No runtime acceptance on these operating systems. Windows secret ACL and path handling are implemented; that is not a Windows compatibility result. |

See [launcher setup](launchers.md). Prism may display its own account/login
prompts. Generated instances do not carry credentials. Relocated profile paths
with whitespace or quotes in the relative suffix require manual activation.
The original installed NeoSync runtime must remain available to its Prism instances.

## Limits of the beta

The panel binds IPv4 port 6742 and applies selections after a server restart.
Its generated certificate requires a local fingerprint check or explicit trust
enrollment. Public manifest/download endpoints should use a publicly trusted
certificate. IPv6 panel binding, remote DNS names for the panel, public SRV/proxy
deployments and public authenticated download distribution were not certified.

Hosting is restricted to administrator-authored, unpublished server-specific
mods with explicit declarations bound to the exact bytes. It never substitutes
for a failed provider lookup or third-party download restriction. Direct configured
HTTPS downloads remain an explicitly reviewed unverified source.

Nested/library/alternate-loader mod arrangements, broad modpack compatibility,
power-loss durability, interrupted-download byte-range resume and accessibility
narration remain outside the tested scope. Previous revisions retain their own
game data; recovery does not merge personal worlds or configurations. Cache
cleanup preserves revisions and only evicts unnecessary downloaded copies.

Prism pre-launch verification detects changed files before FML starts. Other
launcher routes retain post-load verification. Neither mechanism sandboxes mods
or defeats an attacker who controls the local launcher and verification records.

## Reproduction and local evidence

Use the [installed acceptance driver](../../tests/neosync/acceptance/README.md)
and [release checks](releases.md). Local reports are under
`/tmp/neosync-beta1-verified`, with build and run logs named
`/tmp/neosync-beta1-*.log`. These temporary files, fixture mods, certificates,
worlds and installed Minecraft artifacts are excluded from Git and release assets.
After acceptance, the owned test Prism instances were moved out of the launcher
to `build/neosync-launcher-acceptance/`; `locations.json` maps the original report
paths to their archived locations. They are evidence archives, not relocatable
launchable profiles. A separate clean NeoSync beta instance was configured without
acceptance agents. The user's existing Prism instance was preserved.
