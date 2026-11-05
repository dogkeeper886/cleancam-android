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
13. Ask the user to add the udev rule from their own terminal, since it changes the system for everyone who logs in later: `printf '%s\n' '# Lets the logged-in user reach Google devices, such as a Pixel, over adb and fastboot without root.' 'SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", TAG+="uaccess"' | sudo tee /etc/udev/rules.d/51-android.rules && sudo udevadm control --reload-rules && sudo udevadm trigger --subsystem-match=usb`; `uaccess` grants the phone to the seat's user, where an older guide's `MODE="0666"` grants it to every user.
14. Ask the user to put the SDK and Gradle on `PATH` from their own terminal, ahead of the distribution's adb, since EPEL's android-tools adb 33 and the SDK's adb 37 stop each other's server: `printf '\n# Android SDK for cleancam-android; its adb comes before /usr/bin/adb.\nexport ANDROID_HOME="$HOME/Android/Sdk"\nexport PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$HOME/.local/opt/gradle-9.6.0/bin:$PATH"\n' >> ~/.bashrc`.
15. Stop a server the older adb started with `/usr/bin/adb kill-server`.
16. Confirm the tools in a new shell with `bash -ic 'command -v adb; adb version; sdkmanager --list_installed; gradle --version'`; `adb` resolves to `~/Android/Sdk/platform-tools/adb`.
17. Ask the user to plug the Pixel into a port on the PC itself, since the kernel can drop it on a hub port with `Cannot enable. Maybe the USB cable is bad?` in `dmesg`.
18. Confirm the kernel sees the phone with `lsusb | grep 18d1`.
19. Ask the user to turn on Developer options by tapping Settings → About phone → Build number seven times.
20. Ask the user to turn on USB debugging in Settings → System → Developer options.
21. Ask the user to tap Allow on the phone's "Allow USB debugging?" prompt, with "Always allow from this computer" checked.
22. Confirm `adb devices -l` lists the Pixel as `device`; `unauthorized` means the phone still shows step 21's prompt, and `no permissions` means the user has to replug the phone after step 13.
23. Record the Pixel's model and Android version with `adb shell getprop ro.product.model` and `adb shell getprop ro.build.version.sdk`, since the 4K, 60 fps, HEVC and lens cases depend on them.
