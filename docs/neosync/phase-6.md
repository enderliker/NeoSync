# Phase 6 — Restart, updates and recovery

Current development provides a **NeoSync profiles** button on the title screen.
It lists readable prepared and previous revisions, including completed revisions
orphaned by an interrupted pointer update. Damaged revision records are reported
and left untouched. Selecting a previous revision requires explicit review,
verifies every file and provider audit, then atomically selects it for the next
launch. Concurrent changes invalidate the recovery review. The running game
keeps its loaded files; a new launch is required.

A changed server manifest is compared with the previously selected set. Review
lists additions, version changes and omitted files. Preparation builds a new
isolated revision; removal and replacement never delete files in a running or
previous revision. After launch, NeoSync verifies the selected profile and offers
a new discovery/reconnect review. Old consent does not authorize a server update.

Recovery cannot make an old set compatible with an updated server. It can restore
a working local configuration, after which current server requirements are still
checked. It does not restore personal worlds or configurations from other game
directories. Revisions retain their separate game data.

Current unit validation covers stale recovery reviews, canceled recovery,
tampered files, damaged newest records, interrupted preparation, and preservation
of the selected revision. Installed launcher and update acceptance will be
recorded with exact build and environment information when executed.
