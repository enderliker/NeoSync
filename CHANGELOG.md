# Changelog

NeoSync versions are independent of the NeoForge base and synchronization protocol.
All releases below target Minecraft 1.21.1 and are prereleases. Beta.3 through
beta.6 use NeoForge 21.1.252; earlier releases use 21.1.251. Tags use
`NeoSync-<version>-neoforge-<base-version>`.

These summaries describe the behavior at each release. Historical features may
have been removed; consult the current README and linked release notes before
installing. Published artifacts and tags remain immutable.

## Unreleased

- Reserve 0.1.0-beta.8 for authorized CurseForge builds and complete Modrinth App
  runtime preparation; retain the existing beta.7 draft and installed identity.
- Finish Modrinth's client, libraries, assets and logging before registering an
  instance as ready. Verify server revision runtimes and preserve paths with spaces.
- Supply the official CurseForge key through private build inputs and validate
  credential-bearing packages explicitly, without putting the key in sources,
  logs or Gradle caches. Forks must apply for their own key.

- Add a graphical launcher selector with original icons, detected paths, disabled
  missing directories and a custom-folder option. Install and register launchers
  automatically without Python steps.
- Build and verify a Windows EXE embedding the same installer JAR, with Java 21
  detection and release-workflow packaging.
- Add **Accept all** for the complete reviewed mod set, retaining the separate
  default-negative source confirmation before downloading.

- Reserve 0.1.0-beta.7 for development; no release or published artifact is replaced.
- Update the Minecraft 1.21.1 base to NeoForge 21.1.256: FancyModLoader 4.0.45,
  Mixin 0.16.4+mixin.0.8.7, MixinExtras 0.5.5 and upstream's loading-overlay
  shader-color fix, preserving NeoSync's branding and integration hooks.
- Add consented installation creation for SKlauncher 3.2 and native server
  instances for SKlauncher 4.0 Beta and Modrinth App. The GUI installs their
  runtimes automatically, with optional developer import tooling. Existing
  profiles are preserved; accounts are not copied or queried.
- Restore exact CurseForge resolution after Modrinth without metadata caching or
  persisted API-derived provider metadata; retain explicit eligible-only hosting.
- Inject optional provider access at build time without source literals; exclude
  it from source archives and credential-bearing Gradle output caches.
- Show the future game directory and Later before launcher changes; discover
  supported local NeoSync Prism runtimes and create official-launcher installations
  with the directory filled automatically after consent.
- Preserve manual activation for launchers without a verified runtime adapter.
  Opening a launcher is not claimed as automatically launching Minecraft.

See [development validation and limits](docs/neosync/development-sources-launchers.md).

## [0.1.0-beta.6] — 2026-09-27

### Fixed

- Include declared JarJar mods such as Flywheel and Ponder in the manifest for
  their containing Create JAR, so their required dependencies no longer prevent
  server discovery from starting.
- Verify bundled mod identities and client dependencies against the reviewed
  manifest before installing the containing JAR.

[Full beta.6 notes](docs/neosync/release-notes/0.1.0-beta.6.md)

## [0.1.0-beta.5] — 2026-09-27

### Added

- Generate a disabled `config/neosync-server.json` on the first dedicated-server
  start while preserving any existing configuration.
- Allow a custom administrator panel listener port through `adminPort` in that
  JSON file; the default remains 6742.

### Fixed

- Accept a literal public IPv4 address for the administrator panel when NAT
  forwards the connection to a different local IP. Named hosts remain rejected,
  and writes still require an exact matching origin and CSRF token.

[Full beta.5 notes](docs/neosync/release-notes/0.1.0-beta.5.md)

## [0.1.0-beta.4] — 2026-09-27

### Added

- Independent HTTP/HTTPS selection for player synchronization and the administrator
  panel; HTTPS remains the default. HTTP discovery uses capability version 2 and
  requires a default-negative client confirmation. External downloads remain HTTPS.
- HTTP profile persistence keeps its origin separate from HTTPS profiles, with
  existing file verification, consent, restricted hosting and recovery checks.

### Changed

- Updated the administrator panel to the official black-and-white NeoSync mark,
  a responsive neutral palette, and separate connection controls with HTTP notices.
- Updated the NeoSync runtime identifier to 0.1.0-beta.4.

[Full beta.4 notes](docs/neosync/release-notes/0.1.0-beta.4.md)

## [0.1.0-beta.3] — 2026-09-27

### Changed

- Updated the source tree's NeoForge base to **21.1.252** for Minecraft 1.21.1.
  Published beta.2 artifacts remain on NeoForge 21.1.251.

### Fixed

- The profile marker redirection test now edits JSON fields directly, so Windows
  path escaping does not leave the supposedly modified fixture unchanged.
- Imported NeoForge [#3469](https://github.com/neoforged/NeoForge/pull/3469),
  commit [`61045a61fca76876999281676a9e794688390a9e`](https://github.com/neoforged/NeoForge/commit/61045a61fca76876999281676a9e794688390a9e),
  by sciwhiz12 and Shadows_of_Fire: `StackCopySlot` accepts the underlying slot
  index, and both `ItemHandlerCopySlot` constructors propagate it. The previous
  two-argument constructor remains available, deprecated, with index zero.

## [0.1.0-beta.2] — 2026-09-27

### Changed

- Reorganized the README around installation, usage, and contributions while
  preserving its reference content.
- Added continuous build and unit checks for the `1.21.1` branch, pinned workflow
  actions, and removed inherited upstream PR publishing integrations.
- Added community conduct and security policies, structured issue forms, and a
  pull request checklist.
- Added Windows CI for assembly, formatting, unit tests, and the installed
  dedicated-server self-test using Java 21 and the Windows Gradle wrapper.
- Corrected the historical alpha.4 release link and narrowed Python ignore rules.
- Replaced the violet/mint mark with two interlocking geometric elements that
  reveal a white N between black elements. Removed the enclosing icon tile and
  internal padding across launcher and README icons. Added
  transparent exports and an interactive preview; published assets are unchanged.

### Fixed

- Full builds now package the curated NeoSync changelog without depending on
  upstream tags or the upstream version calculator.

## [0.1.0-beta.1] — 2026-09-27

### Added

- HTTPS administrator panel with authenticated inventory selection.
- Prism instance export, pre-launch verification, and restart handoff.
- Verified profile recovery and bounded download-cache eviction.

### Changed

- Expanded installed validation to multi-mod updates, removal and replacement,
  recovery, interruption, low disk space, and restricted hosting.
- Made release archives reproducible and preserved the executable installer
  manifest ordering.

### Removed

- CurseForge resolution and browser import. Modrinth is the only provider;
  direct HTTPS sources and restricted administrator hosting remain supported.

[Full beta.1 notes](docs/neosync/release-notes/0.1.0-beta.1.md) ·
[Validation and limits](docs/neosync/phase-7.md)

## [0.1.0-alpha.4] — 2026-09-23

### Added

- Exact Modrinth resolution with installed download/restart/join validation.
- The historical CurseForge adapter using administrator-owned server keys,
  plus browser import validated with synthetic Linux fixtures. Real installed
  CurseForge acceptance was not established; these features were later removed.

[Full alpha.4 notes](docs/neosync/release-notes/0.1.0-alpha.4.md)

## [0.1.0-alpha.3] — 2026-09-22

### Added

- Restricted HTTPS hosting for explicitly declared administrator-authored,
  unpublished mods unique to the server, with byte-bound declarations,
  transfer limits, and default-negative consent.

[Full alpha.3 notes](docs/neosync/release-notes/0.1.0-alpha.3.md)

## [0.1.0-alpha.2] — 2026-09-22

### Changed

- Applied NeoSync's geometric N to the installer, launcher profile, startup
  graphics, mod list, and title screen while preserving upstream notices and
  the NeoForge compatibility identity.

[Full alpha.2 notes](docs/neosync/release-notes/0.1.0-alpha.2.md)

## [0.1.0-alpha.1] — 2026-09-22

### Added

- First experimental installer for clients and dedicated servers.
- Server discovery, explicit consent, verified direct HTTPS downloads, isolated
  profiles, and manual restart instructions, with one-mod installed validation.

[Full alpha.1 notes](docs/neosync/release-notes/0.1.0-alpha.1.md)

[0.1.0-beta.1]: https://github.com/enderliker/NeoSync/releases/tag/NeoSync-0.1.0-beta.1-neoforge-21.1.251
[0.1.0-alpha.4]: https://github.com/enderliker/NeoSync/releases/tag/NeoSync-0.1.0-alpha.4-neoforge-21.1.251
[0.1.0-alpha.3]: https://github.com/enderliker/NeoSync/releases/tag/NeoSync-0.1.0-alpha.3-neoforge-21.1.251
[0.1.0-alpha.2]: https://github.com/enderliker/NeoSync/releases/tag/NeoSync-0.1.0-alpha.2-neoforge-21.1.251
[0.1.0-alpha.1]: https://github.com/enderliker/NeoSync/releases/tag/NeoSync-0.1.0-alpha.1-neoforge-21.1.251

[0.1.0-beta.2]: https://github.com/enderliker/NeoSync/releases/tag/NeoSync-0.1.0-beta.2-neoforge-21.1.251

[0.1.0-beta.3]: https://github.com/enderliker/NeoSync/releases/tag/NeoSync-0.1.0-beta.3-neoforge-21.1.252
[0.1.0-beta.4]: https://github.com/enderliker/NeoSync/releases/tag/NeoSync-0.1.0-beta.4-neoforge-21.1.252
[0.1.0-beta.5]: https://github.com/enderliker/NeoSync/releases/tag/NeoSync-0.1.0-beta.5-neoforge-21.1.252
[0.1.0-beta.6]: https://github.com/enderliker/NeoSync/releases/tag/NeoSync-0.1.0-beta.6-neoforge-21.1.252
