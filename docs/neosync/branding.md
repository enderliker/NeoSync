# NeoSync visual identity

NeoSync uses an original geometric N with a violet body, a mint accent, and a
dark background. The installer, launcher profile, mod list, and repository share
this identity. The wordmark uses DejaVu Sans. Keep it flat and legible; avoid
mascots, gradients, textures, and decorative effects.

| Asset | Purpose |
| --- | --- |
| `docs/assets/neosync-icon.svg` | Editable square mark and repository header. |
| `docs/assets/neosync-icon.png` | Installer's launcher-profile icon. |
| `docs/assets/neosync-icon-16.png`, `neosync-icon-32.png` | Small installer window icons. |
| `docs/assets/neosync-startup.png` | Static N repeated across FML's 28-frame startup texture. |
| `docs/assets/neosync-installer.svg` | Editable wordmark and installer banner. |
| `docs/assets/neosync-installer.png` | Installer banner. |
| `src/main/resources/neosync_logo.png` | Mod-list banner, generated from the same wordmark. |

After editing the SVGs, run `scripts/render_branding.sh` with librsvg, ImageMagick,
and DejaVu Sans installed. Commit the generated PNGs so normal Java builds need no
graphics tools. Inspect both the square icon and the banner at their display
sizes before publishing.

The mod retains the technical identifier `neoforge` and the NeoForge base version
for dependency checks. Its visible name is NeoSync, with upstream attribution
in its description and authors. Existing license and copyright notices remain
intact. Distinct product branding does not replace the fork's license obligations
or imply endorsement by NeoForged or Mojang.

The installed build uses a resource-only variant of FML `earlydisplay` 4.0.44.
`brandEarlyDisplay` and `brandEarlyDisplaySources` replace its three graphics
while preserving every class, source file, service registration, font, and module
identifier. The historical resource names stay because FML loads them directly
before game resources are available. The installer similarly retains its expected
icon paths while replacing their contents. Minecraft's own graphics and
third-party credits remain intact.

The startup library uses the separate local Maven identity
`io.github.enderliker.neosync:earlydisplay:4.0.44-neosync-<version>`. It is embedded
in the installer with a nonempty download URL pointing to the same NeoSync GitHub
release. Neither the upstream dependency nor its shared cache is overwritten.
Both the binary and matching source archive carry the license and change notice.
The release validator compares all unchanged archive contents against pinned
upstream digests and checks every replacement image and installed library path.

Development compilation still uses the original FML dependency. Use
`runProductionClient` or the installed release to review startup branding; an IDE
development launch is not evidence of the installed graphics. The title screen
identifies both NeoSync's version and its NeoForge base.

Published release assets are immutable. Branding updates apply to subsequent
builds; do not replace the installer attached to an existing release.
