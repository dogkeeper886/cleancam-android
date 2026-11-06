#!/usr/bin/env bash
# Adds CleanCam to the desktop's app list, so a click on its icon opens the control panel in the browser.
# Run it again after moving the repo; the entry points at this copy of it.
set -euo pipefail

PANEL=$(cd "$(dirname "$0")" && pwd)
APPS=${XDG_DATA_HOME:-$HOME/.local/share}/applications
ENTRY=$APPS/cleancam-panel.desktop

mkdir -p "$APPS"
cat >"$ENTRY" <<EOF
[Desktop Entry]
Type=Application
Name=CleanCam
Comment=Control the phone webcam and its receiver
Exec=python3 "$PANEL/cleancam-panel.py"
Icon=$PANEL/cleancam.svg
Terminal=false
Categories=AudioVideo;Video;
EOF
echo "Added CleanCam to the app list: $ENTRY"
