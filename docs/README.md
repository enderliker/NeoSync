# NeoSync

NeoSync is an open-source project based on NeoForge for Minecraft 1.21.1,
maintained in a standalone repository with its own Git history. It aims to let
players review and install the mods required by a compatible server, prepare an
isolated profile, and restart Minecraft to join.

[Phase 2 discovery](neosync/phase-2.md) checks server requirements before gameplay
login and reports missing or incompatible files. Server configuration is required
to select its client inventory and expose an HTTPS manifest. Mod downloads,
profile installation, and restart automation are not available yet.

See the [Phase 1 design](neosync/phase-1.md) and [AGENTS.md](../AGENTS.md) for the
roadmap and security requirements.

## Contributing

Use this repository's issues and pull requests. Start with the
[NeoSync contribution guide](CONTRIBUTING.md) for JDK requirements, setup, the
Minecraft patch workflow, validation, and contribution terms.

[PORTING.md](PORTING.md) covers upstream maintenance and the integration points
that must be checked when adding support for another Minecraft version.

All project documentation, code, comments, logs, and user-facing messages are
written in English.

## Upstream and licensing

NeoSync builds on [NeoForge](https://github.com/neoforged/NeoForge). Existing
package names and development tooling still reflect that origin. Upstream
[NeoForge documentation](https://docs.neoforged.net/) remains useful for its APIs;
NeoSync-specific support and proposals belong in this repository.

The project retains the LGPL-2.1-only license and applicable upstream notices.
See [LICENSE.txt](../LICENSE.txt) and [licensing notes](../README-LICENSE.md).
