# Maintaining and porting NeoSync

NeoSync is an open-source project based on NeoForge, targeting Minecraft 1.21.1.
Its Git repository starts from a source snapshot and has independent history.
Use this guide when integrating upstream changes or investigating support for a
new Minecraft version. Follow [CONTRIBUTING.md](CONTRIBUTING.md) for contribution
terms, setup, code style, and pull requests. Preserve existing LGPL-2.1-only and
copyright notices; new NeoSync work does not require the upstream CLA.

## Keep version changes distinct

Track Minecraft, the base NeoForge revision, FancyModLoader/ModLauncher, the
NeoSync build, and the synchronization protocol separately. A Minecraft or
NeoForge update does not automatically require a protocol change, and an
unchanged protocol does not establish binary or mod compatibility.

The current version 1 manifest explicitly targets Minecraft 1.21.1 and an exact
loader version. Update and test that policy deliberately when adding a new target.
Do not make old clients accept a new mod set by changing only its advertised
version string.

## Integrating upstream fixes for 1.21.1

1. Start from a clean branch or isolated checkout. Record the current upstream
   commit and preserve any local changes. Generate patches for outstanding edits
   to generated Minecraft sources before running setup again.
2. Inspect the desired revision of
   [NeoForge](https://github.com/neoforged/NeoForge) in a separate checkout. Record
   the original commit IDs rather than importing upstream branches and tags.
3. Apply the reviewed fixes as patches on a working branch, retaining authorship
   and recording their upstream commits. Do not merge unrelated histories. Resolve
   conflicts in the context of NeoSync's behavior, especially around connection
   and loading hooks.
4. With the JDK required by the target branch selected, run `./gradlew setup`.
   Review failures under `rejects` and reapply the intended changes to the prepared
   sources in `projects/neoforge/src/main/java`. Never repair a rejected hook by
   simply dropping the behavior it provided.
5. Compile, run relevant tests, and regenerate patches with `./gradlew genPatches`.
   Review the resulting patch diff, then run `./gradlew applyAllFormatting` and
   `./gradlew checkFormatting`.
6. Record the new upstream baseline, dependency changes, compatibility results,
   and remaining limits in the pull request and design notes.

`setup` regenerates Minecraft development sources. Do not use it as a routine
compile command after editing those sources unless the edits have already been
captured in patches. The reference sources under `projects/base/src/main/java`
remain unmodified.

## Moving to a new Minecraft version

First verify that an appropriate
[NeoForm](https://github.com/neoforged/NeoForm) release and NeoForge base exist.
Prefer a NeoForge baseline already supporting the target version, then reapply
NeoSync's focused changes. If no such baseline exists, treat porting NeoForge
itself as separate prerequisite work with its own scope and tests.

Review `minecraft_version`, `neoform_version`, `java_version`, FML, ModLauncher,
build plugins, mappings, and libraries in [gradle.properties](../gradle.properties)
and the Gradle projects. The explicit `neoforge_base_version` replaces upstream
Git-tag version calculation; update it when the compatible platform base changes.
Use the actual target requirements; JDK 21 and Gradle
8.13 are the current branch's setup, not permanent requirements for every port.

This fork's workflow does not depend on NeoForged's internal Kits repository,
privileged GitHub actions, or upstream publication credentials. Prepare sources
with the local Gradle tasks available in the chosen baseline. Task names may
change upstream; inspect them before copying commands from another version.

Never commit generated Minecraft source trees, downloaded game binaries, local
launch installations, or credentials. Check the proposed commits as well as the
working tree before publishing a port branch. Resolve source changes into the
repository's patch format and retain applicable upstream notices.

## Revalidate NeoSync integration points

Use the [Phase 1 evidence](neosync/phase-1.md) as a baseline, not as proof that a
hook still works after a port. The current implementation keeps its runtime logic
under `src/main/java/net/neoforged/neoforge/neosync`.

| Area | What must remain true |
| --- | --- |
| `ConnectScreen.startConnecting` | Discovery runs before the gameplay connector starts; cancellation cannot later connect or change an unrelated screen |
| Status packet serialization | Both cached and uncached responses advertise the same ready manifest; ordinary status fields and clients remain compatible |
| Status handshake | The request selects status, never login/configuration; Direct Connect also performs discovery |
| Address resolution and HTTPS | Minecraft address restrictions remain respected; SRV transport resolution does not replace HTTPS identity; connections use the validated IP and hostname; the manifest route uses the public game port |
| Mod negotiation | Required channel, registry, and configuration checks still run on normal login; discovery never disables them |
| Server lifecycle | The manifest is prepared before advertisement, disabled on failure, and its listener is closed when the server stops |
| Mod inventory | Client selection remains explicit; nested JARs, multi-mod files, language providers, dependency sides, and file identity are rechecked against the new FML APIs |
| Client loading and profiles | Any future activation still selects the game directory before discovery/loading; downloading a file cannot load it into the running game |
| Screens | Requirements, source warnings, scrolling, keyboard focus, cancellation, and English messages remain usable |
| Packaging | Dedicated-server startup does not resolve client-only classes; required JDK modules and dependencies exist in an installed build |

FML is an external dependency in this repository. If a new requirement needs an
earlier loader hook, inspect that dependency and document the necessary change
instead of assuming a late NeoForge callback can affect mod discovery.

## Validation and release readiness

Run checks appropriate to the port:

- `./gradlew checkFormatting` and `./gradlew :tests:runUnitTests`.
- `./gradlew :tests:runGameTestServer` and client tests or an interactive client run
  for gameplay and screen changes.
- `./gradlew :neoforge:testProductionClient` and
  `./gradlew :neoforge:testProductionServer` for packaging or bootstrap changes.
- A NeoSync client with a missing required server mod: requirements must appear
  before channel rejection. Test a matching client and an ordinary NeoForge
  server/client as well.
- Malformed and oversized manifests, digest mismatch, invalid TLS identity,
  local-network permission, status/service failure, canceled requests, and a
  changed inventory after server restart.

When profile installation exists, add interrupted-update, wrong-profile, restart,
and rollback checks. Revalidate each launcher and operating system before listing
it as supported. Source inspection, development runs, and installed-build tests
provide different evidence; report which were actually performed.

Update [AGENTS.md](../AGENTS.md), protocol/schema documentation, setup examples,
and compatibility notes when their assumptions change. Use NeoSync-owned release
coordinates and infrastructure. Publishing a port is a separate action from
preparing and reviewing it; inherited upstream release workflows must be adapted
before use.

The [Phase 2 validation record](neosync/phase-2.md#validation) distinguishes the
current automated tests and installed-build probes from compatibility work that
still needs to be repeated for a port.
