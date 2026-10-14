# Why Not You — alarm MVP

Android alarm that wakes you with a short video instead of a tone.

**Get the APK:** Actions tab → latest "Build APK" run → Artifacts → `WhyNotYou-apk`. Unzip, copy to the phone, install (allow "unknown sources" for your browser/file manager).

**First run on the phone:** tap each permission button until all show ✓. On Samsung also set Battery → Unrestricted for the app.

**Test:** "Fire in 1 minute", lock the phone, wait.

**Swap the video:** replace `app/src/main/res/raw/wakeup.mp4` and update `PLACEHOLDER_LENGTH_MS` in `AlarmActivity.kt`.
