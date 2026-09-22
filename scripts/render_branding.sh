#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
command -v rsvg-convert >/dev/null || { echo "Install librsvg's rsvg-convert to render the branding assets." >&2; exit 1; }

rsvg-convert docs/assets/neosync-icon.svg -o docs/assets/neosync-icon.png
rsvg-convert docs/assets/neosync-installer.svg -o docs/assets/neosync-installer.png
cp docs/assets/neosync-installer.png src/main/resources/neosync_logo.png
