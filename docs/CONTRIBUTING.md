# Contributing to NeoSync

NeoSync is an open-source fork of NeoForge for Minecraft 1.21.1. Its goal is to
help players install a server's required mods through explicit consent, verified
downloads, isolated profiles, and a restart before loading new code.

Read [AGENTS.md](../AGENTS.md) for the accepted design, security rules, roadmap,
and comment policy. The [Phase 1 design](neosync/phase-1.md) records the current
integration decisions and their validation status. Planned features must not be
presented as already available.

## Issues and proposals

Use this repository's issues and pull requests for NeoSync bugs, proposals, and
design discussions. A NeoForged Discord discussion is not a prerequisite.
Include the exact Minecraft and NeoSync build or commit, reproduction steps,
expected behavior, actual behavior, and relevant logs with credentials removed.
For connection or profile problems, include the launcher, operating system,
server setup, and mod set when relevant.

Discuss substantial protocol, trust, profile storage, or launcher changes in an
issue or draft pull request before expanding the implementation. Small fixes and
documentation improvements can go directly to a pull request. Keep upstream
NeoForge bugs distinguishable from behavior introduced by this fork.

For sensitive security reports, use this repository's private vulnerability
reporting feature if enabled. If it is unavailable, request a private reporting
channel in an issue without disclosing exploit details or credentials. Do not
send NeoSync security reports to an unrelated upstream support channel.

## Development setup

1. Install a JDK 21 and select it for both Gradle and your IDE. The repository uses
   Gradle 8.13 through the wrapper; a newer installed JDK is not automatically a
   compatible Gradle runtime.
2. Clone this repository or your contribution fork and create a branch for the
   change. NeoSync has an independent Git history. Its platform build version is
   set by `neoforge_base_version` in `gradle.properties`; upstream history and
   tags are not required to build it.
3. Run `./gradlew setup` from the repository root to prepare Minecraft sources and
   apply the existing patches. On Windows, use `gradlew.bat`.
4. Import or reload the Gradle project in your IDE.

Keeping an additional remote for the original NeoForge repository is useful for
upstream maintenance, but is not required for every contribution. Do not publish
NeoSync changes or artifacts under upstream project coordinates as part of a
routine contribution.

## Source and patch workflow

- NeoForge and NeoSync runtime sources belong in `src/main/java`; resources belong
  in `src/main/resources`. Keep synchronization logic separate from integration
  hooks, following the Phase 1 design.
- Edit patched Minecraft sources in `projects/neoforge/src/main/java` after
  setup. Do not edit the reference sources in `projects/base/src/main/java`.
- Run `./gradlew genPatches` after editing Minecraft sources and review the changes
  under `patches`. Do not commit generated Minecraft source trees.
- Keep Minecraft patches small and grouped around the relevant hook. Place
  substantial logic in project classes. Follow existing patch rules: use fully
  qualified names rather than changing imports, use access transformers for
  access widening, and use the repository's interface injection mechanism when
  needed.
- Write all project content in English. Follow the surrounding code style and
  formatter. Prefer clear code over explanations of obvious steps; comment only
  when a decision, constraint, or behavior needs context.
- Preserve existing copyright and license notices. Do not commit downloaded mods,
  account data, server worlds, generated installations, or credentials.

## Validation

Choose checks that exercise the changed behavior. Documentation-only changes
need link, example, and formatting review; they do not require launching Minecraft.
For code changes, run the relevant checks below and record their results:

| Change | Relevant validation |
| --- | --- |
| Java source or Minecraft patches | `./gradlew checkFormatting` and compilation through the applicable test or build task |
| Pure logic and contracts | `./gradlew :tests:runUnitTests` |
| Server game behavior | `./gradlew :tests:runGameTestServer` |
| Client behavior and screens | IDE client run or `./gradlew :tests:runGameTestClient`, with manual interaction where needed |
| Installer or bootstrap | `./gradlew :neoforge:testProductionClient` and, when server packaging changes, `./gradlew :neoforge:testProductionServer` |

Use `./gradlew applyAllFormatting` for changed Java sources or patches and inspect
its output before committing. On headless Linux, the existing CI runs the
production client self-test through `xvfb-run`.

Synchronization changes need coverage of their relevant failure cases: missing
mods, incompatible versions, rejected consent, malformed metadata, blocked URL
destinations, corrupted downloads, interruption, and preservation of the previous
profile. Add meaningful regression tests for behavior changes; avoid tests that
only reproduce implementation details. Do not claim launcher or platform support
based solely on source inspection or an unrelated test.

## Pull requests

Explain the concrete problem and resulting behavior. Include the applicable
roadmap phase, important design decisions, checks performed, and known limits.
Screenshots help when a screen's behavior or wording changes. Keep unrelated
refactoring separate and update design documents when their contracts change.

Publishing releases and changing distribution coordinates are separate from
submitting a contribution. This fork's release infrastructure must be configured
for NeoSync before publishing; inherited upstream workflows are not evidence of a
working NeoSync release process.

## License and attribution

Contributions to NeoSync are accepted under the repository's existing
**LGPL-2.1-only** license unless a file has an explicitly different applicable
license. By contributing, you confirm that you have the right to submit the work
under those terms. Contributors retain copyright to their own contributions.
NeoSync does not require a separate CLA or assignment of new contributions to
NeoForged.

Existing upstream ownership and license notices remain intact. The historical
NeoForge contribution agreement is not the submission policy for this fork. See
[the licensing notes](../README-LICENSE.md) and [LICENSE.txt](../LICENSE.txt).
