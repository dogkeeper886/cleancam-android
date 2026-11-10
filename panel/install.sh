#!/usr/bin/env bash
# Installs CleanCam on this PC once: two icons in the desktop's app list, the user's one way in, and the CleanCam
# webcam at every boot through a systemd service. CleanCam Android opens the control panel for a phone on USB, and
# CleanCam Android WiFi runs the receiver to a phone on WiFi in a terminal. The names keep them apart from the iPhone
# CleanCam's icon. The receiver adds a missing webcam with sudo, which a click on an icon can't answer, so the icons
# alone start it only once the webcam loads at boot. Asks for the sudo password.
# Run it again after moving the repo; the icons point at this copy of it.
set -euo pipefail

PANEL=$(cd "$(dirname "$0")" && pwd)
APPS=${XDG_DATA_HOME:-$HOME/.local/share}/applications
ENTRY=$APPS/cleancam-panel.desktop
WIFI_ENTRY=$APPS/cleancam-wifi.desktop
VIDEO_NR=10  # the receiver's default webcam, /dev/video10

mkdir -p "$APPS"
cat >"$ENTRY" <<DESKTOP
[Desktop Entry]
Type=Application
Name=CleanCam Android
Comment=Control the phone webcam and its receiver
Exec=python3 "$PANEL/cleancam-panel.py"
Icon=$PANEL/cleancam.svg
Terminal=false
Categories=AudioVideo;Video;
DESKTOP
echo "Added CleanCam Android to the app list: $ENTRY"

# Terminal=true opens the desktop's own terminal, where the script asks for the phone's IP.
cat >"$WIFI_ENTRY" <<DESKTOP
[Desktop Entry]
Type=Application
Name=CleanCam Android WiFi
Comment=Receive the phone webcam over WiFi
Exec="$PANEL/cleancam-wifi.sh"
Icon=$PANEL/cleancam.svg
Terminal=true
Categories=AudioVideo;Video;
DESKTOP
echo "Added CleanCam Android WiFi to the app list: $WIFI_ENTRY"

# Adds the webcam beside any other at boot, as the receiver does, so the v4l2loopback package's own device, such as
# OBS's virtual camera, stays as it is. -x 1 sets exclusive_caps, which lets Chrome and other WebRTC apps list it.
CTL=$(command -v v4l2loopback-ctl) || {
  echo "v4l2loopback-ctl not found: install the v4l2loopback package's tools." >&2
  exit 1
}
sudo tee /etc/systemd/system/cleancam-webcam.service >/dev/null <<UNIT
[Unit]
Description=CleanCam webcam, /dev/video$VIDEO_NR
After=systemd-modules-load.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStartPre=/usr/sbin/modprobe v4l2loopback
ExecStart=/bin/sh -c 'test -e /dev/video$VIDEO_NR || $CTL add -n CleanCam -x 1 /dev/video$VIDEO_NR'

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable --now cleancam-webcam.service
echo "The CleanCam webcam, /dev/video$VIDEO_NR, now loads at every boot."
