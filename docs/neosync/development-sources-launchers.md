# Unreleased source and launcher changes

Current development reserves 0.1.0-beta.8 for Minecraft 1.21.1; the existing
beta.7 release and its installed version remain intact.
The September 29 work used NeoForge 21.1.252; October 8 development updates it to
21.1.256 and adds SKlauncher/Modrinth integration. They do not alter published
beta.6 or earlier artifacts. Installed acceptance below is limited to its exact
fixture and the production launcher harness, not general launcher compatibility.
See [October 8 source and validation notes](upstream-2026-10-08.md).

## Exact source priority

Automatic selection checks Modrinth SHA-512 first, then CurseForge fingerprint,
SHA-1 and size. Only successful exact misses permit explicitly declared eligible
hosting. A provider outage, unavailable access key, restriction, invalid metadata
or ambiguous match stops resolution. Third-party mods never become eligible
because both providers fail. Explicitly configured HTTPS remains independent.

CurseForge requests use its fixed HTTPS origin, public DNS destinations, trusted
TLS, bounded bodies, cancellation and deadlines. API redirects are rejected. Only
approved Forge CDN hosts and file-ID-bound paths are accepted. Null download URLs
are not reconstructed. Minecraft 1.21.1 and NeoForge must be listed for the file.
The consent warning discloses edge.forgecdn.net and mediafilez.forgecdn.net;
artifact redirects are limited to these two HTTPS hosts with an identical reviewed
path, up to three redirects, public-address checks at every hop and both hashes.

Neither CurseForge responses nor parsed metadata enter an HTTP cache or shared
in-flight response registry. Server manifests contain only `type: curseforge`,
the MurmurHash2 fingerprint and SHA-1 computed independently from the local JAR.
Clients perform fresh lookup before review. API-derived IDs, URLs and provider
hashes are used only during the live operation. Persisted consent records retain
the approved local SHA-256 and `source: curseforge`, not the API-derived URL or
identity. Installed bytes remain verified by the approved SHA-256 after restart.
Artifact caching is separate from metadata caching; this design is not a legal
finding about permission to retain downloaded artifacts.

Legacy external CurseForge provider hints remain rejected intact. The hash-only
source extends protocol v1 structurally; old clients reject it explicitly. Match
the exact NeoSync versions on server and client rather than mixing beta.6 with
these development builds. Browser import remains removed.

## Build-time access

Set only the credential file's path, never its value in a command:

```sh
NEOSYNC_CURSEFORGE_KEY_FILE=/private/path/curseforge-key \
  ./gradlew :neoforge:installerJar :neoforge:sourcesJar \
  --no-build-cache --no-configuration-cache
```

Use Java 21. Builds without this variable retain Modrinth functionality but fail
closed if CurseForge resolution becomes necessary. The generated binary resource
contains randomized interleaved masking bytes, not a literal key. It is excluded
from source archives and Git. Credential-bearing resource/archive tasks disable
Gradle output caching. Do not upload private build directories or configuration
caches. Rebuilding without the variable removes the generated resource.

Obfuscation only frustrates casual string searches: code that authenticates must
recover the key at runtime. This is not secure secret storage. On October 9, 2026
the owner supplied CurseForge's written confirmation that public NeoSync client
and server builds may embed the recoverable key and that forks must apply for
their own key. No broader permission is inferred from that confirmation.

Use of NeoSync's CurseForge API functionality is subject to the
[CurseForge 3rd Party API Terms and Conditions](https://support.curseforge.com/en/support/solutions/articles/9000207405).
An API key embedded in an authorized NeoSync build does not grant permission to
reuse or redistribute that key. Forks and third-party distributions must obtain
their own applicable authorization and apply for their own key.

The official release workflow obtains `NEOSYNC_CURSEFORGE_KEY` from the private
repository secret, creates a temporary owner-only key file and removes it after
packaging. Pull-request and ordinary fork builds do not receive this secret.
Use `scripts/prepare_release.py --curseforge-key-file /private/path/curseforge-key`
to validate an authorized package. The validator compares decoded access in
memory and rejects literal credentials in artifacts. Source archives exclude the
resource. Validation without the option rejects credential-bearing archives.

## Launcher consent and limits

October 8 development extends launcher support and imports NeoForge 1.21.1
upstream through `a2d6402a3c1eec093aef7e7d10ac5145906c199e` (base 21.1.256).
The September 29 validation below remains historical evidence for its own build.

The prepared-profile screen shows the detected launcher, exact future game
directory, isolation explanation, and **Later**, focused by default. Later does
not create launcher installations or close Minecraft. A separate action prepares
the installation; a second consent action closes/restarts the game.

| Launcher | Development behavior |
| --- | --- |
| Prism | Discovers a local descriptor or a supported local NeoSync component plus the actual ancestor executable. Can create the descriptor after consent, export a verified server instance and use the existing `--dir` / `--launch` handoff. No command-line sessions are copied. |
| Minecraft Launcher | Detects brand/executable and the installed library directory. Creates a distinct installation with its game directory filled automatically after consent. Preserves existing profiles/settings and never reads account files. Select and press Play in the launcher; automatic game launch is not claimed. |
| SKlauncher 3.2 | Reuses the existing bounded, atomic official profile writer after consent; adds an isolated installation and preserves unrelated profiles. Reopen and select it, then press Play. |
| SKlauncher 4.0 Beta | Distinguishes the 4.x launcher version, installs the local runtime through the GUI, then prepares native custom instances and per-revision version metadata after consent. Uses an explicit game argument because the launcher rebases instance directories on startup. Reopen, select and press Play. |
| Modrinth App | Recognizes both Modrinth and its `theseus` brand. The GUI installs NeoSync metadata and libraries; consented preparation registers a native instance and content set in a SQLite transaction. Unsupported schemas fail without changing existing instances. Reopen, select and press Play. |
| Lunar Client | Detected and explicitly reports no verified NeoSync 1.21.1 custom-runtime adapter. Offers the supported launcher route instead. |
| MultiMC, ATLauncher, CurseForge App | Identified where brand or native ancestor names provide evidence; no automatic runtime compatibility claim. |
| Unknown / detached launcher | Does not guess the launcher from unrelated profile files or relaunch Java with session arguments. Manual instructions remain available. |

Prism still requires an actually installed NeoSync runtime, not ordinary NeoForge.
Runtime library verification, no-symbolic-link paths, atomic instance publication
and complete pre-launch inventory checks remain enforced. Paths not representable
in Prism's split-before-expansion argument format retain manual activation.
Minecraft installation edits use bounded strict JSON, private staging, a local
lock, repeated concurrent-change checks and an atomic rename. Its launcher does
not honor NeoSync's lock; concurrent changes outside the checks remain a limit.
Legacy records containing authentication data are refused rather than copied.

## Validation scope

- On September 29, 2026 the Java 21 full unit suite passed 268 tests, with no
  failures, errors or skipped tests; `checkFormatting` passed. Tests cover exact
  source priority, missing access, provider errors, restricted/hostile URLs, fresh metadata, hash mismatch,
  absence of persisted CurseForge metadata and isolated verified profile creation.
- A compiled production-JAR probe completed live exact CurseForge lookup,
  independent client resolution and consent-bound Clumps 19.0.0.1 download through
  its restricted CDN redirect, verifying provider SHA-1 and manifest SHA-256.
  No API response or credential was printed or persisted. This is not installed
  launcher or multiplayer acceptance evidence.
- The development installer installed and started both a graphical Linux client
  and a dedicated Linux server; both self-test reports were freshly written.
  The inherited Gradle server-install harness required a temporary external init
  script setting plain installation paths and `serverLauncher`; product source
  was not changed for that unrelated task-provider issue.
- Installed graphical `install` and `resume` runs passed with Clumps 19.0.0.1
  downloaded from CurseForge and a real loopback dedicated server. The driver
  verified default-negative installation and source consent, declined both with
  Enter and found no profile/staging changes, then accepted and downloaded the
  exact file. The prepared screen focused Later and displayed the isolated path.
  A second production-harness launch verified that profile and joined the server
  with Clumps loaded through normal NeoForge negotiation. Screenshots were
  inspected. This verifies directory selection, not a launcher-controlled restart.
- The first installed review exposed a null-URL assumption in the requirements
  report for hash-only sources. The report now names CurseForge without a stored
  URL; the unit regression and subsequent installed runs passed.
- Archive inspection found no literal credential in JAR entries, source files
  or validation logs. The masked universal-JAR resource decoded to the supplied
  private key and was absent from the source archive. These checks do not make an
  embedded key secret or authorize distribution.
- Unit tests exercise launcher detection, installation creation with spaces,
  preservation of unrelated profiles/account files, idempotency, repeated Prism
  exports without duplicated game-directory suffixes, and edited or unreviewed-file
  rejection. Prism preflight detects changed launcher settings and helper/runtime
  records as well as changed mod inventories. Published Prism acceptance remains
  separate evidence.
- New automatic Prism descriptor creation/restart and official-launcher profile
  creation still need installed GUI acceptance using those actual launchers.
  Windows/macOS and other launcher runtime flows have not been newly validated.

Primary references: [CurseForge API](https://docs.curseforge.com/rest-api/),
[Prism CLI](https://prismlauncher.org/wiki/getting-started/command-line-interface/),
[SKlauncher directories](https://docs.skmedix.pl/faq/launcher-related), and
[Lunar's mod-loading guidance](https://www.lunarclient.com/news/how-lunar-client-simplifies-minecraft-modding).
