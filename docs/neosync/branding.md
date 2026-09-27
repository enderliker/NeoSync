# NeoSync visual identity

NeoSync uses an abstract geometric mark made from two interlocking elements.
One element is rotated 180 degrees to form its matching counterpart, suggesting
synchronization between client and server. The shared negative space reveals
an N. The outer silhouette is a compact loop with two openings; the letter is
the secondary reading. The N is filled white so it remains white on dark pages;
only the area outside the symbol is transparent.
The installer, launcher profile, mod list, and repository share this identity.
The wordmark uses DejaVu Sans Bold with compact spacing.

The palette is pure black `#000000` and pure white `#ffffff`. The two interlocking
pieces are black and the N between them is white on every background. Keep the identity
flat and legible; avoid mascots, gradients, textures, and decorative effects.
The previous violet/mint mark remains part of historical release binaries.

| Asset | Purpose |
| --- | --- |
| `docs/assets/neosync-mark.svg`, `neosync-mark.png` | Black-and-white mark with transparent exterior; repository header and 1024 × 1024 PNG export. |
| `docs/assets/neosync-icon.svg` | Editable launcher mark, cropped to its silhouette without an enclosing tile. |
| `docs/assets/neosync-icon.png` | Installer's launcher-profile icon. |
| `docs/assets/neosync-icon-16.png`, `neosync-icon-32.png` | Small installer window icons. |
| `docs/assets/neosync-startup.png` | Static N repeated across FML's 28-frame startup texture. |
| `docs/assets/neosync-installer.svg` | Editable wordmark and installer banner. |
| `docs/assets/neosync-installer.png` | Installer banner. |
| `docs/assets/neosync-social.svg`, `neosync-social.png` | Editable 1280 × 640 GitHub social preview and rendered upload. |
| `docs/assets/neosync-branding-preview.svg`, `neosync-branding-preview.png` | Two monochrome applications and small-size review. |
| `src/main/resources/neosync_logo.png` | Mod-list banner, generated from the same wordmark. |

After editing the SVGs, run `scripts/render_branding.sh` with librsvg, ImageMagick,
and DejaVu Sans installed. Commit the generated PNGs so normal Java builds need no
graphics tools. Inspect both the square icon and the banner at their display
sizes before publishing.

The icon viewBox follows the symbol's bounds: no internal padding, background
tile, or border. Place any layout spacing outside the image. Do not recolor the
white N, add outlines, or stretch either element independently. The 16 px
and 32 px icons are rendered from the same source and must retain the opening.
The [interactive preview](branding-preview.html) shows the mark on light and dark
backgrounds, with downloadable files and actual-size samples.

## Design references

The September 2026 revision followed research through Tavily and the
`logo-generator` skill's exploration workflow. The geometry was drawn directly
as editable SVG; no stock symbol or generated raster was traced.

- [Jacob Cass, Smashing Magazine: Vital Tips For Effective Logo Design](https://www.smashingmagazine.com/2009/08/vital-tips-for-effective-logo-design)
  recommends a distinctive, simple silhouette, vector construction, and checking
  single-color, reversed, and small-size applications.
- [VistaPrint: Six key principles of logo design](https://www.vistaprint.com/hub/principles-of-logo-design)
  discusses proportion and checking readability at favicon sizes.
- [The Newton Agency: Why AI-Generated Logos Look Generic](https://www.thenewtonagencystudio.com/post/why-ai-generated-logos-look-generic)
  offers a designer's critique of interchangeable visual formulas. This is
  professional opinion, not an empirical test of originality or quality.

For this project, avoiding generic styling means using the interlocking form
and its negative space consistently, with restrained typography across assets.
Recognizability is a design aim; it has not been measured in a user study.

## Runtime packaging

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
