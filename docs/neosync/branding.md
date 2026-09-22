# NeoSync visual identity

NeoSync uses an original geometric N with a violet body, a mint accent, and a
dark background. The installer, launcher profile, mod list, and repository share
this identity. The wordmark uses DejaVu Sans. Keep it flat and legible; avoid
mascots, gradients, textures, and decorative effects.

| Asset | Purpose |
| --- | --- |
| `docs/assets/neosync-icon.svg` | Editable square mark and repository header. |
| `docs/assets/neosync-icon.png` | Installer's launcher-profile icon. |
| `docs/assets/neosync-installer.svg` | Editable wordmark and installer banner. |
| `docs/assets/neosync-installer.png` | Installer banner. |
| `src/main/resources/neosync_logo.png` | Mod-list banner, generated from the same wordmark. |

After editing the SVGs, run `scripts/render_branding.sh` with librsvg and
DejaVu Sans installed. Commit the generated PNGs so normal Java builds need no
graphics tools. Inspect both the square icon and the banner at their display
sizes before publishing.

The mod retains the technical identifier `neoforge` and the NeoForge base version
for dependency checks. Its visible name is NeoSync, with upstream attribution
in its description and authors. Existing license and copyright notices remain
intact. Distinct product branding does not replace the fork's license obligations
or imply endorsement by NeoForged or Mojang.

The external FML early-display library also contains startup graphics. These
are separate from the mod-list resource and require their own packaging change;
replacing the banner alone does not change them. Minecraft's own graphics and
third-party credits are outside NeoSync's product identity.

Published release assets are immutable. Branding updates apply to subsequent
builds; do not replace the installer attached to an existing release.
