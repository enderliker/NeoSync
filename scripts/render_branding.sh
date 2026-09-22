#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
command -v rsvg-convert >/dev/null || { echo "Install librsvg's rsvg-convert to render the branding assets." >&2; exit 1; }
command -v magick >/dev/null || { echo "Install ImageMagick to render the startup strip." >&2; exit 1; }

rsvg-convert docs/assets/neosync-icon.svg -o docs/assets/neosync-icon.png
rsvg-convert -w 16 -h 16 docs/assets/neosync-icon.svg -o docs/assets/neosync-icon-16.png
rsvg-convert -w 32 -h 32 docs/assets/neosync-icon.svg -o docs/assets/neosync-icon-32.png
rsvg-convert docs/assets/neosync-installer.svg -o docs/assets/neosync-installer.png
cp docs/assets/neosync-installer.png src/main/resources/neosync_logo.png
# FML 4 expects 28 vertically stacked frames and clips the right sixth of each.
# Keep a static mark inside that area instead of imitating its mascot animation.
rsvg-convert -w 96 -h 96 docs/assets/neosync-icon.svg | magick - -background none -gravity west -extent 128x128 -duplicate 27 -append docs/assets/neosync-startup.png
