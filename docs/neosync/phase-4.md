# Phase 4 — Restricted server hosting

Work in progress. Provider integration, browser downloads, and automatic restart
remain outside this phase. Published alpha.2 does not implement hosting.

## Eligibility and inventory

Hosting is disabled by default. Only administrator-authored mods created for
that server and not published or distributed anywhere else are eligible. Each
selected hosted file must have only a `server` source and an explicit declaration
of `authoredByAdministrator`, `exclusiveToServer`, and `distributionRights`, all
true, bound to its exact `sha256`. A replaced file requires a new declaration.
These statements are administrator assertions, not independently verified facts.
A third-party file, general redistribution permission, a provider failure, or an
author download restriction never establishes eligibility.

The inventory copies and hashes selected files before publication, uses generated
hash filenames, rejects symbolic links, and holds an exclusive lifetime lock.
Live `mods` changes do not change the snapshot. Disk quota applies before copying;
failed copies are removed. An interrupted lifetime's private snapshot is reclaimed
under the lock on the next start. Unexpected content causes refusal instead of
recursive deletion. Normal shutdown removes the snapshot.

## Implementation sequence and evidence

1. Eligibility policy and bounded immutable inventory.
2. Full-GET service on the approved HTTPS origin, bounded requests, concurrent
   streams, bandwidth, deadlines, and publication from the loaded server inventory.
3. Exact-source review, default-negative server warning, destination validation,
   transactional preparation, and restart verification.
4. Fresh acceptance with a locally authored, unpublished fixture mod, including
   cancellations, download, restart/join, and adversarial failure cases.

No Phase 4 graphical or installed-build acceptance has run yet. Test results are
recorded here as each increment is executed; historical Phase 3 results are not
Phase 4 evidence.

Executed September 22, 2026 on Linux/JDK 21.0.2: the first inventory increment
passed all 191 unit tests (zero failures, errors, or skips), including four new
hosting tests. This exercises policy and snapshot operations, not gameplay.
