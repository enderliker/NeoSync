# Changelog

NeoSync versions are independent of the NeoForge base and synchronization protocol.
All releases below target Minecraft 1.21.1 and NeoForge 21.1.251 and are
prereleases. Tags use `NeoSync-<version>-neoforge-<base-version>`.

These summaries describe the behavior at each release. Historical features may
have been removed; consult the current README and linked release notes before
installing. Published artifacts and tags remain immutable.

## Unreleased

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
  reveal an N in negative space, using an ink and ivory
  palette across icons, installer graphics, and documentation. Added
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
