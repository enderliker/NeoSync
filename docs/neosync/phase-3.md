# Phase 3: installation MVP

Status: implementation in progress. The client still provides Phase 2 discovery;
the installation UI and full restart/join acceptance check are not available yet.

## Implemented increments

- Installation planning retains exact validated manifest bytes, digest, normalized
  server identity, selected sources, byte totals, reusable file indicators, and
  removals/replacements relative to a previous revision.
- Consent requires both installation review and the unverified-source decision.
  It belongs to one in-memory plan. Persisted decisions will be audit records,
  never authorization for a subsequent installation.
- Loader mismatches, library-only artifacts, and server-only sources fail before
  installation. Provider hints do not establish verified provenance.
- The consent-gated external downloader streams into new staging files, checks
  exact size/SHA-256, pins validated public IP addresses, verifies the logical TLS
  hostname, limits concurrency and deadlines, and removes failed partial files.
  Same-origin redirects are limited to three. A different origin stops the
  attempt and requires the administrator's direct URL followed by a new review;
  no bytes are requested from the new origin. No client screen enables it yet.
- JAR verification compares top-level javafml mod IDs, versions, language-loader
  ranges, and client dependencies with the approved artifact. It checks hashes
  before and after inspection, bounds the central directory before ZIP indexing,
  and bounds metadata expansion and TOML nesting without loading classes.
  ZIP64, nested JARs, alternate loader metadata, service providers, custom feature
  requirements, and multi-release archives are explicitly unsupported initially.

Phase 3 initially requires direct external HTTPS URLs without query parameters,
credentials, or fragments, on ports 443 or 8443. This conservative subset avoids
persisting URL credentials or transient download tokens in review/consent records.
Administrators must configure a suitable stable direct URL. Discovery continues
to understand the broader version 1 source syntax.

## Validation

Planning regression coverage checks exact snapshot binding, both consent
decisions, loader gates, server identity separation, source restrictions, review
changes, and the distinction between reusable bytes and an active profile.
On September 21, 2026, `applyAllFormatting :tests:runUnitTests` passed with JDK 21:
141 tests, zero failures/errors/skips (10 new planning cases and the 131-test
baseline). This is logic coverage, not installation or gameplay evidence.

The downloader increment passed `applyAllFormatting :tests:runUnitTests`: 158
tests, zero failures/errors/skips. The 17 added cases exercise real TLS streaming,
certificate/hostname failures, ambiguous framing, incorrect lengths, excess and
truncated bytes, digest mismatch, redirects, cancellation, existing-file
preservation, and blocked external LAN addresses. Initial test placement conflicted
with FML's module packages; tests now use the existing test namespace. A real
framing test exposed Netty's removal of ambiguous Content-Length headers; the
decoder now rejects that response before normalization.

The metadata increment passed `applyAllFormatting :tests:runUnitTests`: 172
tests, zero failures/errors/skips. Added cases cover identity/dependency and
language-loader mismatches, manifest version substitution, rejected archive paths
and arrangements, metadata expansion, parser depth, malformed ZIPs, and
cancellation. Compatibility rules were checked against the pinned FML 4.0.44
mod-file and dependency readers; this remains a deliberately limited subset.
