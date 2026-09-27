# Security policy

## Reporting a vulnerability

Email the NeoSync maintainer at [enderliker01@gmail.com](mailto:enderliker01@gmail.com)
with the subject `NeoSync security report`. Keep exploit details out of public
issues and pull requests. GitHub private vulnerability reporting is currently
disabled for this repository; email is the available private channel.

Include the affected NeoSync release or commit, Minecraft and NeoForge versions,
operating system, launcher, reproduction steps, expected and actual behavior,
and the likely impact. A small reproducer or patch is useful when available.
Remove account tokens, administrator passwords, private keys, and personal data
from logs and attachments. Test only on systems you own or have permission to test.

The maintainer will assess the report, discuss reproduction and remediation
privately, and coordinate disclosure with the reporter. This is a volunteer
project with no guaranteed response time or bug bounty. If no response arrives,
follow up by email without publishing sensitive details.

## Supported versions

| Version | Security maintenance |
| --- | --- |
| Current beta, `0.1.0-beta.2` | Reports are accepted; confirmed fixes will ship in a new version. |
| Current `1.21.1` development branch | Reports and regression fixes are accepted. |
| Earlier alpha releases | No maintained backport series; upgrade to the current beta. |

Published tags and assets are immutable. A security correction does not silently
replace an existing download. See the [release guide](docs/neosync/releases.md)
and [changelog](CHANGELOG.md) for versions and distribution details.

## Scope and trust boundaries

Reports concerning manifest validation, download destinations, file integrity,
consent, profile paths, the administrator panel, credentials, and launcher
handoff are relevant. Report suspected issues even if you cannot yet identify
which component is responsible; distinguish NeoSync behavior from inherited
NeoForge behavior when possible.

Installed mods execute with Minecraft's permissions. Isolated profiles are not
a sandbox, and a provider name or matching hash does not prove that a mod is
safe. The [beta validation matrix](docs/neosync/phase-7.md) records tested behavior
and limitations, including differences between pre-launch and post-load checks.

Use [bug reports](https://github.com/enderliker/NeoSync/issues/new?template=bug_report.yml)
for ordinary crashes and compatibility problems that do not disclose a vulnerability.
