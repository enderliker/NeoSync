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
