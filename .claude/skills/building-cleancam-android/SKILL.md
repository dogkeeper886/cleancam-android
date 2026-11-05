---
description: >-
  An agent builds and runs CleanCam for Android by its own ideas. The repo
  holds a Gradle project and a PC receiver, but not the steps between them:
  the build, the install over adb, the permissions, the forwarded ports and
  the checks that the PC gets the stream. A guessed step leaves an APK the
  phone never runs, a prompt nobody answers, or a receiver that talks to the
  older adb and drops the phone. This skill asks the agent to build, install,
  run and check the app by the steps below.
---

Build CleanCam for Android, run it on the Pixel, and check the PC receives it:
1. Set up the PC with the `developing-cleancam-android` skill first, since the build needs JDK 17, the Android SDK and the SDK's adb.
2. Put the SDK's platform-tools first on `PATH` in the shell that runs these steps with `export PATH="$HOME/Android/Sdk/platform-tools:$PATH"`, since a non-interactive shell skips `~/.bashrc` and finds the distribution's older adb.
3. Write `local.properties` with `printf 'sdk.dir=%s/Android/Sdk\n' "$HOME" > local.properties` when `ANDROID_HOME` is unset, since AGP finds the SDK through one of them; git ignores the file.
4. Build the APK from the repo's root with `./gradlew assembleDebug`, which writes `app/build/outputs/apk/debug/app-debug.apk`; the first run downloads AGP 9.5.
5. Install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk`; `-r` keeps the app's saved preset and options.
6. Grant the camera, microphone and local-network permissions with `for p in CAMERA RECORD_AUDIO ACCESS_LOCAL_NETWORK; do adb shell pm grant io.github.dogkeeper886.cleancam android.permission.$p; done`, since an agent can't tap the phone's prompts; a test of the prompts themselves skips this step.
7. Ask the user to unlock the phone when `adb shell dumpsys window | grep isKeyguardShowing` reports `true`, since the camera runs only while the app is on screen.
8. Launch the app with `adb shell am start -n io.github.dogkeeper886.cleancam/.MainActivity`.
9. Check for a crash with `adb logcat -d -b crash`.
10. Start `receiver/cleancam-receive.sh` from the repo's root in the background, since it runs until Ctrl-C; it adds the webcam with sudo and forwards ports 5000 to 5003 with `adb forward`.
11. Confirm the forwards with `adb forward --list`.
12. Confirm the webcam receives the stream with `ffprobe -hide_banner -loglevel error -f v4l2 -show_entries stream=width,height,pix_fmt -of compact /dev/video10`, which reports 1920x1080 `yuv420p`.
13. Confirm the CleanCam microphone and speaker with `wpctl status | grep -i cleancam`.
14. Read the debug port with `(sleep 5) | nc 127.0.0.1 5003`, keeping `nc`'s input open, since the app drops a client that closes its sending side; each `second` line names the frame rate, the bitrate, the keyframes and the viewers.
15. Change the preset or a camera option through the debug port, such as `(sleep 1; echo preset obs; sleep 5) | nc 127.0.0.1 5003`, which streams 60 fps at 20 Mbps on a Pixel 8.
16. Grab a webcam frame with `ffmpeg -hide_banner -loglevel error -y -f v4l2 -i /dev/video10 -frames:v 1 frame.png`, to compare with the phone's screen.
17. Screenshot the phone with `adb exec-out screencap -p > screen.png`; an upright phone streams its picture between black bars.
18. Ask the user to speak near the phone during `tools/mic-check.sh`, since a quiet room records near silence and the phone plays the recording back.
19. Stop the receiver with Ctrl-C, which removes the adb forwards and the CleanCam microphone and speaker.
