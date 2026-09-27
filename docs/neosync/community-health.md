# Community health review

Reviewed on September 27, 2026, starting from `fe67042` on `1.21.1`.
This review covers repository presentation, contribution entry points, and
maintenance automation. It is not a security certification or a prediction of
GitHub popularity.

## Reconnaissance

NeoSync is a Minecraft 1.21.1 platform fork and client/server application based
on NeoForge 21.1.251. Its primary language is Java; it uses JDK 21 and the Gradle
8.13 wrapper. The repository includes JUnit tests, Minecraft game tests,
production self-tests, and installed acceptance tooling. GitHub correctly
identifies Java as the primary language.

The existing LGPL-2.1-only license and upstream notices remain applicable.
English documentation and external contributions are already covered by
`AGENTS.md` and the contribution guide. Five semantic-versioned prereleases and
their matching tags exist, ending with `0.1.0-beta.1`; no new release is needed
for this review.

## Initial audit and resolution

| Item | Initial state | Resolution |
| --- | --- | --- |
| README | Weak | Immediate installation command, short navigation, five factual badges, flow diagram, concise features, and collapsible reference sections. Original sections retained. |
| License | Present | Kept `LICENSE.txt`, licensing notes, and inherited notices. |
| Contribution guide | Present | Kept GitHub-recognized `docs/CONTRIBUTING.md`; linked private reporting and conduct policies. |
| Code of conduct | Missing | Added Contributor Covenant 2.1 with the maintainer's approved private contact. |
| Security policy | Missing | Added reporting instructions, supported versions, and existing trust limits. |
| Bug and feature templates | Weak | Replaced inherited templates with structured NeoSync issue forms. |
| Pull request template | Missing | Added change description, validation, and focused review checklist. |
| Changelog | Missing | Added summaries of actual releases, historical feature distinctions, and an Unreleased section. |
| README title, tagline, badges | Weak | Added concise introduction and five badges for release, CI, Minecraft, Java, and license. |
| Short README navigation | Missing | Added links to Installation, Usage, and Contribute headings. |
| Functional visual | Missing | Added a Mermaid diagram of review, consent, preparation, and restart. |
| Installation visible near the top | Weak | Linked the published installer and included real client/server commands. |
| Short feature bullets | Weak | Added six implemented capabilities; retained the detailed table. |
| Real usage examples | Present | Retained the actual join/review/prepare/activate flow and administrator guidance. |
| Collapsible long references | Missing | Moved existing reference sections into six details blocks. |
| Clear roadmap versus implementation | Present | Retained phase evidence, beta status, and platform/launcher limits. |
| Build/test CI and README badge | Weak | Added default-branch build, formatting, and unit checks; pinned actions and removed upstream publishing integrations. |
| Semantic versions and releases | Present | Verified all five existing prereleases and the beta installer asset. |
| Automated tests | Present | Ran the existing JUnit suite; no artificial documentation tests added to the product. |
| Stack-specific ignore rules | Weak | Replaced the blanket Python source exclusion with bytecode rules and ignored local credentials/environments. |
| Language attributes | Present | Kept `.gitattributes`; no language override is necessary. |

## Implementation order

1. Improve the README and release links while preserving the current license
   and immutable releases.
2. Make the existing build/test workflow suitable for the standalone fork and
   fix any build failure encountered during verification.
3. Complete community policies, templates, changelog, and ignore rules.
4. Prepare a social preview and review logo refinements separately.
5. Verify the build, tests, documentation links, workflow syntax, and assets.

Full assembly exposed an inherited changelog task that required upstream tags
and upstream version calculation. The build now packages the curated root
changelog through a declared Gradle task. Setup and compilation run in separate
Gradle invocations to respect the source-generation workflow.

## Local verification

- `./gradlew assemble checkFormatting :tests:runUnitTests --max-workers=2 --console=plain`
  passed with JDK 21 after source setup.
- JUnit: 236 tests in 27 suites; zero failures, errors, or skipped tests.
- Actionlint 1.7.12 passed for all workflows.
- `bash -n scripts/render_branding.sh` and `git diff --check` passed.
- Checked local documentation links, README navigation anchors, six matched
  details blocks, and preservation of all nine original README body sections.
- The prepared social preview is a valid 1280 × 640 PNG.
- Rebuilt the installer and sources after the final interlocking-logo revision.
  `python3 scripts/prepare_release.py --check` passed: current graphics are
  embedded, archive identities and licenses are preserved, and non-graphics FML
  contents match the pinned upstream digests. No release was published.
- Inspected the rendered mark, installer banner, and social preview; checked
  SVG syntax, exported PNG dimensions, and preview links. Browser interaction
  testing was not run because this environment has no installed browser engine.

All five README badge URLs returned valid SVG responses after publication.
The first remote build could not start: GitHub reported, "The job was not
started because your account is locked due to a billing issue."
[Run 36324234062](https://github.com/enderliker/NeoSync/actions/runs/36324234062)
therefore shows a failing status without executing any build step. The badge
reports this actual status; local success does not replace a remote result.

A separate Windows workflow uses PowerShell and `gradlew.bat` for assembly,
formatting, unit tests, and the installed dedicated-server self-test. Its syntax
has been checked. [Windows run 36324665935](https://github.com/enderliker/NeoSync/actions/runs/36324665935)
was also prevented from starting by the same billing lock.
These checks do not constitute a new installed gameplay acceptance run. Published
beta evidence remains in [Phase 7](phase-7.md).

## Manual GitHub settings

These are suggested values; this review does not change repository settings.

| Setting | Suggested value |
| --- | --- |
| About description | Server mod synchronization for Minecraft 1.21.1: verified downloads, isolated profiles and Prism restart. Based on NeoForge. |
| Topics | `minecraft`, `minecraft-java`, `minecraft-mod`, `neoforge`, `java`, `gradle`, `modrinth`, `mod-manager`, `mod-sync`, `dedicated-server`, `prism-launcher`, `open-source` |
| Website | `https://github.com/enderliker/NeoSync/tree/1.21.1/docs` until a dedicated website exists. |
| Social preview | Upload [`docs/assets/neosync-social.png`](../assets/neosync-social.png), 1280 × 640, in Settings → General → Social preview. |
| Discussions | Enable Q&A and Ideas for support and proposals; retain issues for actionable bugs. |
| Private vulnerability reporting | Enable in Settings → Security. It was disabled during review; `SECURITY.md` provides the approved email channel. Update the policy if this setting changes. |
| Actions account access | Resolve the GitHub billing lock, then rerun Linux and Windows workflows. |

The current About description still describes synchronization as planned, topics
are empty, and no website is configured. These settings should be updated by the
maintainer. No replacement license or additional product claims are needed.

## Files changed during this review

### Created

- `.github/ISSUE_TEMPLATE/bug_report.yml`
- `.github/ISSUE_TEMPLATE/config.yml`
- `.github/ISSUE_TEMPLATE/feature_request.yml`
- `.github/PULL_REQUEST_TEMPLATE.md`
- `.github/workflows/test-windows.yml`
- `CHANGELOG.md`
- `CODE_OF_CONDUCT.md`
- `SECURITY.md`
- `docs/assets/neosync-branding-preview.png`
- `docs/assets/neosync-branding-preview.svg`
- `docs/assets/neosync-mark.png`
- `docs/assets/neosync-mark.svg`
- `docs/assets/neosync-social.png`
- `docs/assets/neosync-social.svg`
- `docs/neosync/branding-preview.html`
- `docs/neosync/community-health.md`

### Modified

- `.github/renovate.json`
- `.github/workflows/build-prs.yml`
- `.github/workflows/check-local-changes.yml`
- `.github/workflows/release.yml`
- `.github/workflows/test-prs.yml`
- `.gitignore`
- `README.md`
- `docs/CONTRIBUTING.md`
- `docs/assets/neosync-icon-16.png`
- `docs/assets/neosync-icon-32.png`
- `docs/assets/neosync-icon.png`
- `docs/assets/neosync-icon.svg`
- `docs/assets/neosync-installer.png`
- `docs/assets/neosync-installer.svg`
- `docs/assets/neosync-startup.png`
- `docs/neosync/branding.md`
- `docs/neosync/releases.md`
- `projects/neoforge/build.gradle`
- `scripts/render_branding.sh`
- `src/main/resources/neosync_logo.png`

### Replaced or removed

- `.github/ISSUE_TEMPLATE/feature_request.md`
- `.github/ISSUE_TEMPLATE/issue_report.md`
- `.github/workflows/publish-jcc.yml`
- `.github/workflows/publish-prs.yml`
