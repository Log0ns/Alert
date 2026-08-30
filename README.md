# DrowsyAlert

An Android app that uses your phone's front camera to watch for closed eyes
and sounds a loud full-screen alarm to keep you awake — useful as a
driving/study alertness aid. Detection runs **on-device**; no video or
images are ever recorded, stored, or uploaded. Monitoring keeps running in
the background — you can lock the phone or switch to another app (maps,
music, etc.) and it will still alert you.

## How it works
- Tapping **Start Monitoring** launches `DrowsinessService`, a foreground
  service that owns the front camera and runs continuously — independent
  of whether the app is on screen. A persistent notification ("DrowsyAlert
  is watching") shows it's active, with a Stop action.
- The service uses **CameraX** + **ML Kit Face Detection** (on-device) to
  get a per-eye "open probability" every frame. When the average drops
  below a threshold, it starts a timer.
- If eyes stay closed longer than your chosen threshold (adjustable
  3–45s, default 15s, via the slider on the main screen), it:
  - vibrates in a repeating pattern and plays your device's default alarm
    sound on a loop
  - fires a full-screen high-priority notification that pops
    `AlarmActivity` — a big red "WAKE UP!" screen — over the lock screen
    or whatever app you're currently in, similar to how an alarm clock or
    incoming call interrupts you
  - the alert clears as soon as you tap **I'm awake**, or automatically
    once open eyes are detected again
- The main screen is a status/control panel — a status dot + eye icon that
  reflect live state (idle / watching / eyes closed, counting / alarm),
  the threshold slider, and the Start/Stop button. It has no camera
  preview by design, since the camera is owned by the background service.

## Permissions
- **Camera** — required, for eye detection.
- **Notifications** — used for the "watching" status notification and the
  full-screen wake-up alert (Android 13+ requires this be granted
  explicitly; the app requests it alongside Camera).
- Vibration and wake-lock permissions are normal permissions, granted
  automatically at install.

## Opening the project
1. Install [Android Studio](https://developer.android.com/studio) (Koala or newer).
2. Choose **Open** and select this `DrowsyAlert` folder.
3. Let Android Studio sync Gradle (it will generate the Gradle wrapper for you
   automatically on first sync).
4. Connect a physical phone — CameraX + a real front camera work far more
   reliably than an emulator here — and hit **Run**.
5. Grant camera + notification permissions when prompted, adjust the alarm
   threshold, and tap **Start Monitoring**. You can now leave the app.

Minimum SDK: Android 8.0 (API 26). Requires a front-facing camera.

## Building an APK from your phone only (no computer needed)
This repo includes `.github/workflows/build.yml`, which builds a debug APK
in the cloud whenever you push to GitHub — so you never need Android Studio
or Termux. Steps, all doable from a phone browser or the GitHub app:

1. Create a free GitHub account if you don't have one, then create a new
   **public or private repository**.
2. On the repo page, **Add file → Upload files**, and upload the single
   `DrowsyAlert-project.zip` file (not its extracted contents — mobile
   browsers tend to flatten folder structure on multi-file uploads, but a
   single zip avoids that). Commit it.
3. Open the green **Code** button → **Codespaces** tab → **Create
   codespace on main**. This gives you a full VS Code environment with a
   terminal, running in your browser.
4. In the terminal, run:
   ```
   unzip DrowsyAlert-project.zip
   mv DrowsyAlert/* DrowsyAlert/.github .
   rmdir DrowsyAlert
   rm DrowsyAlert-project.zip
   git add .
   git commit -m "Add project files"
   git push
   ```
5. Go to the **Actions** tab — a build should start automatically. Once it
   finishes (green check), open the run, download the
   **DrowsyAlert-debug-apk** artifact, extract it, and install
   `app-debug.apk` on your phone (allow "install unknown apps" when
   prompted — normal for any APK not from the Play Store).

This produces a debug-signed APK: fine for your own device, not meant for
distributing to others or the Play Store.

## Notes and honest limitations
- Face/eye detection can be affected by glasses, low light, camera angle,
  or the phone shifting position — mount the phone so your face stays in
  frame.
- This is a driver-alertness *aid*, not a substitute for actually pulling
  over and resting if you're drowsy. Treat the alarm as a signal to stop
  driving, not just something to dismiss and continue.
- Some phone manufacturers (Samsung, Xiaomi, Huawei, etc.) aggressively
  kill background services to save battery. If monitoring stops
  unexpectedly after a while, look up "[your phone brand] disable battery
  optimization for an app" and exempt DrowsyAlert.
- The full-screen alarm relies on Android's full-screen-intent notification
  mechanism (the same one alarm-clock and calling apps use) — this reliably
  wakes the screen and shows over the lock screen and other apps on stock
  Android; behavior can vary slightly on heavily customized OEM skins.

## Where to tweak things
- `EyeAnalyzer.kt` — `closedThreshold` (0.35f) controls how "closed" an eye
  needs to look before it counts; lower it if it's triggering too easily,
  raise it if it's missing genuine closures.
- `DrowsinessService.kt` — `triggerAlarm()` / `stopAlarm()` control the
  alarm sound, vibration pattern, and notifications; `bindCamera()` and
  `handleEyeState()` control detection logic.
- `activity_main.xml` / `colors.xml` — the visual design (dark theme,
  status card, accent colors).
