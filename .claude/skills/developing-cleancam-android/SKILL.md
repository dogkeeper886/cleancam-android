---
description: >-
  An agent sets up a Linux PC to build CleanCam for Android from source by its
  own ideas. Android Studio bundles the build tools, but this repo builds the
  Pixel app from the command line, and a RHEL 9 family PC ships only a Java
  runtime, an adb older than the SDK's and udev rules that leave the Pixel to
  root. A guessed step leaves a build that stops as it compiles, two adb
  servers that stop each other, or a phone only root can reach. This skill
  asks the agent to install and check those tools by the steps below.
---

Set up a Linux PC to build CleanCam for Android from source and reach the Pixel:
1. Confirm the user has a Pixel, a USB-C cable that carries data and an x86_64 Linux PC; the build runs from the command line alone.
2. Install JDK 17 with its compiler, `sudo dnf install java-17-openjdk-devel` on the RHEL 9 family, adding `--disablerepo=<repo>` for a third-party repo that asks to import a GPG key, since AGP 9.5 needs JDK 17 and only the JDK's compiler builds the code.
3. Confirm the compiler with `javac -version`.
4. Download the command-line tools into a new folder from `mktemp -d`: `curl -fLO https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip`.
5. Check the download with `echo "4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  commandlinetools-linux-15859902_latest.zip" | sha256sum -c -`.
6. Unpack its `cmdline-tools` folder to `~/Android/Sdk/cmdline-tools/latest`, since sdkmanager finds the SDK root only from that path.
7. Ask the user to accept the Android SDK licenses, since the user agrees to Google's terms.
8. Accept them with `yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses`; sdkmanager still works, though Google deprecated it for the Android CLI's `android sdk`.
9. Install the SDK packages with `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --install "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"`, since AGP 9.5 compiles up to API 37 and defaults to build-tools 36.0.0.
10. Download Gradle 9.6.0, the lowest AGP 9.5 takes, into the step 4 folder: `curl -fLO https://services.gradle.org/distributions/gradle-9.6.0-bin.zip`.
11. Check the download with `echo "bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01  gradle-9.6.0-bin.zip" | sha256sum -c -`.
12. Unpack it to `~/.local/opt`, which makes `~/.local/opt/gradle-9.6.0`.
13. Ask the user to run `.claude/skills/developing-cleancam-android/one-time-setup.sh` from their own terminal, since it changes the system for later sessions: it adds a udev rule that grants Google's USB devices to the seat's user, puts the SDK and Gradle on `PATH` ahead of EPEL's adb 33, whose server stops the SDK's adb 37, and stops the older adb's server.
14. Confirm the tools in a new shell with `bash -ic 'command -v adb; adb version; sdkmanager --list_installed; gradle --version'`; `adb` resolves to `~/Android/Sdk/platform-tools/adb`.
15. Ask the user to plug the Pixel into a port on the PC itself, since the kernel can drop it on a hub port with `Cannot enable. Maybe the USB cable is bad?` in `dmesg`.
16. Confirm the kernel sees the phone with `lsusb | grep 18d1`.
17. Ask the user to turn on Developer options by tapping Settings → About phone → Build number seven times.
18. Ask the user to turn on USB debugging in Settings → System → Developer options.
19. Ask the user to tap Allow on the phone's "Allow USB debugging?" prompt, with "Always allow from this computer" checked.
20. Confirm `adb devices -l` lists the Pixel as `device`; `unauthorized` means the phone still shows step 19's prompt, and `no permissions` means the user has to replug the phone after step 13.
21. Record the phone's model and Android version with `adb shell getprop ro.product.model` and `adb shell getprop ro.build.version.sdk`, since the test cases' Phone decision names them.
22. Record the back camera's lenses, focal lengths and auto-exposure frame rates with `adb shell dumpsys media.camera | grep -A3 -E 'android\.(lens\.facing|lens\.info\.availableFocalLengths|control\.aeAvailableTargetFpsRanges|logicalMultiCamera\.physicalIds) \('`, since they decide which preset and lens cases apply: a Pixel 8 tops out at 30 fps with auto exposure and has no tele lens.
23. Record the phone's H.264 and HEVC encoders with `adb shell 'cat /vendor/etc/media_codecs*.xml' | grep -oE 'name="c2\.[^"]*(h264|avc|hevc)\.encoder"' | sort -u`, since the WiFi preset sends HEVC where the phone encodes it.
