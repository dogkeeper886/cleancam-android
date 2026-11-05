#!/usr/bin/env bash
# Finishes this PC's setup for cleancam-android with the two steps that change the system for later sessions, which
# the user runs once from their own terminal: a udev rule that lets the logged-in user reach the Pixel over adb, and the
# Android SDK on PATH ahead of the distribution's older adb. A second run changes nothing.
#   .claude/skills/developing-cleancam-android/one-time-setup.sh
# This is step 13 of the SKILL.md beside it.
set -euo pipefail

UDEV_RULES=/etc/udev/rules.d/51-android.rules
UDEV_RULE='SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", TAG+="uaccess"'
BASHRC_MARKER='# Android SDK for cleancam-android; its adb comes before /usr/bin/adb.'

# uaccess grants Google's USB devices, such as a Pixel, to the user at the seat, where MODE="0666" grants them to every
# user.
if ! grep -qxF "$UDEV_RULE" "$UDEV_RULES" 2>/dev/null; then
  printf '%s\n' '# Lets the logged-in user reach Google devices, such as a Pixel, over adb and fastboot without root.' \
    "$UDEV_RULE" | sudo tee "$UDEV_RULES" >/dev/null
  sudo udevadm control --reload-rules
  sudo udevadm trigger --subsystem-match=usb
  echo "Added $UDEV_RULES. Replug the Pixel if it is plugged in."
fi

# The SDK's adb comes before /usr/bin/adb, since two adb versions stop each other's server.
if ! grep -qxF "$BASHRC_MARKER" ~/.bashrc 2>/dev/null; then
  # shellcheck disable=SC2016  # $HOME and $PATH expand as ~/.bashrc runs.
  printf '\n%s\nexport ANDROID_HOME="$HOME/Android/Sdk"\nexport PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$HOME/.local/opt/gradle-9.6.0/bin:$PATH"\n' \
    "$BASHRC_MARKER" >>~/.bashrc
  echo "Added the Android SDK to PATH in ~/.bashrc. Open a new terminal, or run: source ~/.bashrc"
fi

# A server the older adb started would stop the SDK's adb.
if [[ -x /usr/bin/adb ]]; then /usr/bin/adb kill-server 2>/dev/null || true; fi
