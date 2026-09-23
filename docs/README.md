# NeoSync documentation

[← Project overview](../README.md)

NeoSync's installation MVP supports reviewed direct HTTPS downloads, verified
isolated profiles, and manual activation. The full one-mod flow has passed with
the repository's launcher harness. Restricted hosting is available in alpha.3.
Phase 5 development in alpha.4 adds exact Modrinth resolution and manual import,
with installed Linux fixtures for watching, explicit paths and native selection.
Live CurseForge acceptance is blocked on NeoSync's own key and an applicable
provider agreement; Windows runtime acceptance is pending. Alpha.4 is unreleased.
Automatic restart remains future work. See the phase documents for exact evidence.

## Setup and development

| Guide | What it covers |
| --- | --- |
| [Releases and installation](neosync/releases.md) | Choosing the installer, client/server setup, version naming, and release validation. |
| [Server setup](neosync/phase-2.md#server-setup) | Client inventory selection, HTTPS certificates, and manifest discovery. |
| [Installation and activation](neosync/phase-3.md#server-configuration-and-manual-activation) | Direct artifact sources and selecting the prepared game directory. |
| [Contributing](CONTRIBUTING.md) | JDK 21, Gradle setup, Minecraft patches, validation, and contribution terms. |
| [Acceptance harness](../tests/neosync/acceptance/README.md) | Reproducing the installed client/server flow and recovery checks. |
| [Porting](PORTING.md) | Upstream maintenance and integration points for another Minecraft version. |

## Design and validation

| Document | Status and purpose |
| --- | --- |
| [Development guide and roadmap](../AGENTS.md) | Accepted product design, security requirements, and phase exit criteria. |
| [Phase 1](neosync/phase-1.md) | Historical design and source investigation. |
| [Phase 2](neosync/phase-2.md) | Discovery implementation, setup, and validation evidence. |
| [Phase 3](neosync/phase-3.md) | Installation implementation, complete one-mod results, and compatibility limits. |
| [Phase 4](neosync/phase-4.md) | Restricted hosting setup, consent, transfer quotas, installed acceptance, and limits. |
| [Phase 5](neosync/phase-5.md) | Provider identity, Modrinth runtime evidence, manual import and blocked CurseForge prerequisites. |
| [Protocol version 1](neosync/protocol-v1.md) | Manifest, identity, consent, transport, and profile contracts. |
| [Manual downloads](neosync/manual-downloads.md) | Browser handoff and import contract; live restricted-file and Windows acceptance pending. |

All project documentation and user-facing text are written in English.
NeoSync retains applicable NeoForge attribution and licenses; see the
[licensing notes](../README-LICENSE.md) and [LICENSE.txt](../LICENSE.txt).
