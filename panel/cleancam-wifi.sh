#!/usr/bin/env bash
# Runs the receiver to the phone over WiFi, for the CleanCam Android WiFi icon, which opens it in a terminal. Asks for
# the phone's WiFi IP and offers the last one, since the router can hand the phone a new one. The window stays open
# after the receiver stops, so its last message stays readable.
#   panel/cleancam-wifi.sh
set -uo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
SAVED=${XDG_CONFIG_HOME:-$HOME/.config}/cleancam-android/phone-ip

# A second receiver would write the same webcam: the panel's, or the iPhone CleanCam's, whose script has the same name.
if pgrep -f 'bash [^ ]*cleancam-receive\.sh' >/dev/null; then
  echo "A CleanCam receiver already runs: turn off Receiver in the CleanCam Android page, or close the other" \
    "receiver's window."
  read -rp "Press Enter to close."
  exit 1
fi
last=$(cat "$SAVED" 2>/dev/null)
read -rp "Phone's WiFi IP${last:+ [$last]}: " ip
ip=${ip:-$last}
if [[ -z $ip ]]; then
  read -rp "No IP given. Press Enter to close."
  exit 1
fi
mkdir -p "$(dirname "$SAVED")" && printf '%s\n' "$ip" >"$SAVED"
"$REPO/receiver/cleancam-receive.sh" "$ip"
echo
read -rp "Receiver stopped. Press Enter to close."
