# Manual downloads for restricted mods

Status: implemented in the Phase 5 alpha.4 development tree, with installed Linux
fixtures for watching, explicit paths and native KDialog selection, each followed
by a verified restart and real-server join. **Live restricted CurseForge downloads
and Windows runtime acceptance are pending.** The server administrator supplies
their own CurseForge key; no live key or applicable agreement has been validated
here. See [Phase 5](phase-5.md) for new execution evidence, provider
terms and concrete blockers. The contract below remains the acceptance target;
implementation or fixture coverage alone does not certify a real provider flow.
The manifest retains v1 external sources with provider hints.

The agreed source preference is **Modrinth automatic download → CurseForge
automatic download when permitted → CurseForge browser download when restricted
→ hosting only for unpublished mods written by the administrator for that server**.
See the [source and hosting rules](../../AGENTS.md#source-resolution). Each route
must supply the exact required artifact; an equal version label alone is not
enough. Select and review the source before acquisition. Automatic download still
requires installation consent, and a later source change requires another review.
The final route is a separate eligibility case, not a fallback when downloading
a third-party mod fails.

## Respecting the author's choice

When a mod author disables third-party automatic downloads, direct the user to
the official CurseForge download page for the exact project and file required by
the server. Do not substitute server hosting, mirrors, scraping, or an alternate
CDN request to bypass that restriction. Phase 4 server hosting is implemented as
a separate eligibility case limited to mods written by the administrator for
that server and not published or distributed elsewhere.

New resolutions open `https://www.curseforge.com/minecraft/mc-mods/<slug>/download/<fileId>`
after consent. This is the official browser download page, not a direct CDN
request. Earlier prepared profiles with `/files/<fileId>` audit URLs remain
readable. The website or browser can still require interaction.

This is the intended browser-assisted pattern familiar from launchers such as
Prism Launcher; it is not a claim that NeoSync implements or has verified another
launcher's behavior. The browser may start the download after opening the page,
but the site or browser can require further interaction. NeoSync must not promise
automatic completion or a zero-click installation for these mods.

## Review and browser handoff

Resolve the exact CurseForge project and file identity before review, including
the mod version, Minecraft version, loader, expected size, and SHA-256. Never use
a project homepage, a search result, or a floating latest-version link as the
selected download. Build or validate the official HTTPS file-page URL using
provider-specific rules; a server must not be able to open arbitrary URLs, local
services, file paths, or custom URI schemes through this flow.

List affected mods and their versions, provider, exact file-page links, sizes,
and manual-download status in the existing installation review. Attribute the
server's reported author restriction in the review, for example:

> The server reports that the author disabled automatic downloads — your browser will open the exact file page.

The client does not independently query CurseForge. A timeout, missing download
URL, or HTTP error alone does not establish an author restriction. Until provider
identity is independently verified, retain the unverified-source classification
and its warning that mods can execute code; a matching server hash does not prove
trustworthiness.

Opening a page requires acceptance of the reviewed installation and any applicable
source warning. Keep **No, cancel** selected by default, including after mouse
actions rebuild the screen. The affirmative action must describe the browser
handoff, for example **Yes, open CurseForge and import these files**. Declining
must open no page and start no download watcher or import.

After acceptance, show the files still needed, the watched directory, and an
action to reopen each approved file page. Explain that the user may need to finish
the download in the browser. Open only reviewed pages, avoid repeated unsolicited
tabs, and provide cancellation throughout. Browser sessions, cookies, and account
credentials stay under the browser's control. NeoSync cannot enforce its HTTP
redirect policy inside an external browser; local import must still verify the
exact approved bytes.

## Detecting and importing the file

Discover the user's configured Downloads directory rather than hard-coding an
English folder name. On Linux, honor the XDG user-directory configuration,
including a relocated `XDG_DOWNLOAD_DIR`; parse it as data without executing shell
content. On Windows, use the Downloads known folder (`FOLDERID_Downloads`) so
localized and relocated folders work. If lookup fails, the directory is disabled,
or the browser saves elsewhere, let the user select a folder or the downloaded
file explicitly. No server or manifest may choose a local search path.

Register a Java `WatchService` for creation and modification events before opening
the browser. Handle downloads completed by rename, duplicate browser filenames,
and files already present when watching starts. Treat events as hints: use bounded
rescans for missed or overflowed events and provide manual file selection if
watching is unavailable. Keep directory scans nonrecursive and limited to the
chosen directory, with bounded candidate counts and I/O; do not search the user's
other files.

Ignore directories, symlinks, and known partial-download files. A filename or a
stable timestamp alone is never proof of completion or identity. For a candidate
with the expected size, copy through bounded, cancellable I/O into NeoSync-owned
staging, then verify the staged copy's exact size and SHA-256 against the consented
manifest. Reject changed, partial, oversized, or mismatched copies. Inspect JAR
metadata with the existing non-executing limits and verify the approved mod set
before accepting it. Handle files still being written or locked without treating
them as successfully imported.

The destination is the `mods` directory of a **new isolated revision** associated
with the server being joined. Use NeoSync-generated paths, never the browser's
filename as a managed path. Publish the revision only after all required files,
including ordinary direct downloads, pass the normal transactional checks. Do not
copy directly into a running profile or the user's regular game directory. Keep
the existing manual activation and restart flow.

Never move, delete, or rewrite the user's browser download, including on mismatch,
cancellation, or timeout. Close the watcher and stop pending imports when the
attempt ends; clean up only NeoSync-owned temporary copies. Bound waiting and
report expiry with an explicit retry action. Late events cannot revive a canceled
attempt. Changes to the manifest, file identity, or selected source require new
review; an earlier browser handoff is not reusable installation consent.

## Acceptance checks before claiming support

- Exercise an exact CurseForge file link and document the actual browser steps;
  verify that an author restriction triggers no server-hosting or CDN bypass.
- Check review wording, mouse and keyboard default-negative focus, both consent
  declines, browser-launch failure, cancellation, timeout, and late events.
- Run on Linux and Windows with default, localized, relocated, missing, and
  browser-specific download locations. Exercise manual selection as well as
  watcher overflow, unavailable watching, existing files, and rename completion.
- Reject partial downloads, wrong versions, wrong hashes, oversized files,
  symlinks, hostile metadata, and files changed during copying. Accept the exact
  bytes even when the browser adds a duplicate-filename suffix.
- Verify mixed manual/direct installations, disk failures, interruption, changed
  snapshots, and preservation of active profiles and original browser downloads.
- Complete review, browser download, verified preparation, manual restart, and
  joining a real server with the selected mod. Record platform and browser limits;
  existing Phase 3 direct-download results do not validate this flow.
