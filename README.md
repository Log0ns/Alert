# DrowsyAlert

An Android app that uses your phone's front camera to watch for closed eyes and
sounds a loud alarm + vibration to keep you awake — useful as a driving/study
alertness aid. Everything runs **on-device**; no video or images are ever
recorded, stored, or uploaded.

## How it works
- **CameraX** streams the front camera preview and hands frames to an analyzer.
- **ML Kit Face Detection** (Google's on-device model) returns a per-eye
  "open probability" for each frame. When the average of both eyes drops
  below a threshold, the app treats the eyes as closed and starts a timer.
- If eyes stay closed longer than your chosen threshold (default 15s,
  adjustable 3–45s via the slider), the app:
  - flashes a full-screen red alert with "WAKE UP!"
  - plays your device's default alarm sound on a loop
  - vibrates in a repeating pattern
  - all of which stop automatically as soon as open eyes are detected again.
- A wake lock keeps the screen on while monitoring is active, since the point
  is to keep watching your face continuously.

## Building an APK from your phone only (no computer needed)
This repo includes `.github/workflows/build.yml`, which builds a debug APK
in the cloud whenever you push to GitHub — so you never need Android Studio
or Termux. Steps, all doable from a phone browser or the GitHub app:

1. Unzip this project on your phone (any file manager with "extract" works).
2. Create a free GitHub account if you don't have one, then create a new
   **public or private repository** (e.g. `drowsy-alert`).
3. Upload the files: on the repo page, tap **Add file → Upload files**, then
   select everything from the unzipped `DrowsyAlert` folder (do this in a
   couple batches if your browser limits how many files you can pick at
   once — folder structure is preserved as long as you select nested files
   together, e.g. select the whole `app` folder's contents, then `.github`'s
   contents separately). Commit the upload.
4. Go to the **Actions** tab of your repo. A "Build debug APK" run should
   start automatically (or tap **Run workflow** if it doesn't).
5. Wait a few minutes for it to finish (green checkmark), then open the run
   and download the **DrowsyAlert-debug-apk** artifact — that's a zip
   containing `app-debug.apk`.
6. On your phone, open that APK file to install it. You'll need to allow
   "install unknown apps" for your browser/files app when prompted — this is
   normal for any APK not from the Play Store.

This produces a debug-signed APK, which is fine for installing on your own
device but isn't meant for distributing to others or publishing to the Play
Store.

## Opening the project (if you do have a computer later)
1. Install [Android Studio](https://developer.android.com/studio) (Koala or newer).
2. Choose **Open** and select this `DrowsyAlert` folder.
3. Let Android Studio sync Gradle (it will generate the Gradle wrapper for you
   automatically on first sync).
4. Connect a phone (or start an emulator with a webcam-backed front camera —
   physical devices work much better for this) and hit **Run**.
5. Grant the camera permission when prompted, adjust the alarm threshold, and
   tap **Start Monitoring**.

Minimum SDK: Android 8.0 (API 26). Requires a front-facing camera.

## Notes and honest limitations
- Face/eye detection can be affected by glasses, low light, camera angle, or
  the phone shifting position — mount the phone so your face stays in frame.
- This is a driver-alertness *aid*, not a substitute for actually pulling
  over and resting if you're drowsy. Treat the alarm as a signal to stop
  driving, not just a snooze-and-continue tool.
- The app currently runs only while in the foreground with the screen on
  (by design, since it needs the camera and needs to alert you visually too).
  It does not run as a background/headless service.

## Where to tweak things
- `EyeAnalyzer.kt` — `closedThreshold` (0.35f) controls how "closed" an eye
  needs to look before it counts; lower it if it's triggering too easily,
  raise it if it's missing genuine closures.
- `MainActivity.kt` — `triggerAlarm()` / `stopAlarm()` control the alarm
  sound, vibration pattern, and full-screen overlay.
- `activity_main.xml` — the SeekBar's `max` (45) bounds the slider's range in
  seconds.
