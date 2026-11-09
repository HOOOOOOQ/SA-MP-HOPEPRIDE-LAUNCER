# HopePride Launcher — Forensic Report `crashv2.log`

**Evidence:** one logcat capture, 6 394 lines / 873 737 bytes, window
`11-09 18:44:20.147` → `18:44:40.634`, device `com.hopepride.samp` version from `main`
(release APK `apk-20261109-113216`, debug build, `v1.7.0 / 164`).
**Method:** every claim below is quoted from that file, re-verified this session; no
conclusion from the previous crash report was carried over unverified.
**Result in one line:** the crash is **new**, it is a **Java `NullPointerException`**, it is
**fixed in source** (`ffeacb5`, `28c23c0`, merged as `b85e4a4`), and it has **not been
verified on a device**.

---

## 1. Summary

At `11-09 18:44:34.122` the launcher process 14991 (single process, main thread) died with
`java.lang.NullPointerException` on a **null `ViewPagerWithoutSwipe pa`**, thrown from
`MainActivity.initializeLauncherUi()` at line 143 — the launcher's own first statement that
touches the pager. The field `pa` was declared and dereferenced in six places but **never
assigned from `findViewById()`**, so it was guaranteed to be null every time the data-gate
callback fired. This is a deterministic defect in the launcher code, not a driver, thermal,
or SurfaceFlinger problem.

There is **no native crash** in this capture: no `SIGSEGV`, no `SIGABRT`, no tombstone, no
`DEBUG   :` block, no `PC`/`LR`, no fault address, and the game process (`:game`) never
started. Therefore nothing in this log can be attributed to Texture DB, `SortEntries`, TXD,
DFF, RenderWare or custom-model streaming, and this report makes **no** claim about them.

## 2. Filtering and attribution

The capture was filtered for `com.hopepride.samp`, PID 14991, `AndroidRuntime`, `DEBUG`,
`libc`, `linker`, `ActivityTaskManager`, `ActivityManager`, `tombstoned`, `crash_dump`,
`FATAL EXCEPTION`, `SIGSEGV`, `SIGABRT`. Tag counts in the whole file:

| Tag | Lines |
|---|---|
| `AndroidRuntime` | 19 (17 of them the single crash block) |
| `ActivityTaskManager` | 17 |
| `ActivityManager` | 49 |
| `libc` | 9 — **all** unrelated (SELinux property denials on `sys.thermal.*`) |
| `linker` | 0 |
| `tombstoned` / `crash_dump` / `DEBUG   :` | 0 / 0 / 0 |
| `SIGSEGV` or `SIGABRT` | 0 |
| `TextureDatabase` / `Texdb` / `SortEntries` | 0 |
| Any line mentioning `hopepride` | 231 |

HopePride's own lines are the only ones attributable to us. The rest of the 231 lines belong
to the system (`system_server` 1533, `SurfaceFlinger` 860, `mtkpower`, `BufferQueueDebug`) or
to other apps that merely share the window: `com.termux` (used by the user, not by us),
`com.openai.chatgpt` (PID 17427 — it owned `VRI[MainActivity]` at 18:44:21–23, **not** our
process), `com.android.launcher3`, `com.google.android.googlequicksearchbox`.

## 3. The crash record (verbatim, lines 4334–4353)

```
11-09 18:44:34.121 14991 14991 D AndroidRuntime: Shutting down VM
--------- beginning of crash
11-09 18:44:34.122 14991 14991 E AndroidRuntime: FATAL EXCEPTION: main
11-09 18:44:34.122 14991 14991 E AndroidRuntime: Process: com.hopepride.samp, PID: 14991
11-09 18:44:34.122 14991 14991 E AndroidRuntime: java.lang.NullPointerException: Attempt to
  invoke virtual method 'androidx.viewpager.widget.PagerAdapter
  com.hopepride.samp.launcher.util.ViewPagerWithoutSwipe.getAdapter()'
  on a null object reference
11-09 18:44:34.122 14991 14991 E AndroidRuntime:  at MainActivity.initializeLauncherUi(MainActivity.java:143)
11-09 18:44:34.122 14991 14991 E AndroidRuntime:  at MainActivity.lambda$onCreate$0(MainActivity.java:82)
11-09 18:44:34.122 14991 14991 E AndroidRuntime:  at MainActivity$$ExternalSyntheticLambda0.onReady(D8$$SyntheticClass:2)
11-09 18:44:34.122 14991 14991 E AndroidRuntime:  at GameDataManager.lambda$checkAndPrepare$2(GameDataManager.java:136)
11-09 18:44:34.122 14991 14991 E AndroidRuntime:  at GameDataManager$$ExternalSyntheticLambda21.run(D8$$SyntheticClass:2)
11-09 18:44:34.122 14991 14991 E AndroidRuntime:  at GameDataManager.lambda$postToUi$0(GameDataManager.java:95)
11-09 18:44:34.122 14991 14991 E AndroidRuntime:  at GameDataManager$$ExternalSyntheticLambda4.run(D8$$SyntheticClass:4)
11-09 18:44:34.122 14991 14991 E AndroidRuntime:  at android.os.Handler.handleCallback(Handler.java:958)
... Looper.loopOnce / Looper.loop / ActivityThread.main / ZygoteInit.main
```

| Field | Value |
|---|---|
| Crash time | `11-09 18:44:34.122` |
| PID / TID | `14991 / 14991` — main thread, launcher process only |
| Exception | `java.lang.NullPointerException` |
| Signal | **none** — this is a Java exception, not a native fault |
| Fault address / PC / LR | **absent from this log** (no tombstone section) |
| Native library / backtrace | **none** — no native frames besides `java.lang.reflect.Method.invoke(Native Method)` |
| Java stack | `MainActivity.initializeLauncherUi:143` ← `lambda$onCreate$0:82` ← `GameDataManager.lambda$checkAndPrepare$2:136` ← `lambda$postToUi$0:95` |

The stack was cross-checked against the released source at `56cba56` (the commit the APK was
built from): line 143 is exactly `if (pa.getAdapter() == null) {`, line 82 is the
`ReadyCallback` lambda, `GameDataManager:136` is the `postToUi(activity, … callback.onReady())`
call and `:95` is `postToUi`'s `Handler.post` body. Line numbers match, so the log is from
that build — no symbol guessing was needed, and none was done.

## 4. Event ordering before the crash

```
18:44:30.152  ActivityTaskManager  Starting activity …/.launcher.intro.SplashActivity
18:44:30.174  ActivityTaskManager  START u0 … SplashActivity (from launcher, flg=0x10200000)
18:44:31.693  HopeDiagnostics      Could not write diagnostic log      <-- our store failing
18:44:32.163  ActivityTaskManager  Displayed SplashActivity +1s999ms
18:44:33.174  HopeDiagnostics      Could not write diagnostic log      <-- again, still silent
18:44:33.370  ActivityTaskManager  START u0 {cmp=…/.launcher.MainActivity} LAUNCH_MULTIPLE
18:44:33.977  ActivityTaskManager  Displayed MainActivity +509ms
18:44:34.068  View                 assignParent to null: DecorView[SplashActivity]
18:44:34.094  View                 assignParent to null: DecorView[MainActivity]   <-- window gone
18:44:34.099  InputManager-JNI     input channel of MainActivity disposed
18:44:34.103  WindowManager        focus changes from MainActivity EXITING to next window
18:44:34.122  AndroidRuntime       FATAL EXCEPTION (28 ms after the decor view was unparented)
18:44:34.464  libcrashlytics       initialised (Crashlytics collecting the tombstone-free crash)
18:44:36.707  ActivityTaskManager  Force finishing activity …/.launcher.MainActivity
```

Two facts follow, and only these two:

1. `initializeLauncherUi()` ran from a **`Handler` message posted by `GameDataManager`**, i.e.
   asynchronously to `onCreate()`, ~1.1 s after `MainActivity` was displayed.
2. In the 28 ms before that message ran, the Activity's decor view had already been unparented
   and its input channel disposed — the window hierarchy was being torn down.

What the log does **not** show is why the window was torn down. There is no
`ActivityTaskManager` record of a relaunch, no configuration change, no `ANR`, no user
`finish()`. The launcher itself launched `MainActivity` with `LAUNCH_MULTIPLE`, so a second
instance is possible; the `VRI[MainActivity]#1` destructor at `34.094` shows *an* earlier
root view of the same Activity class being destroyed. **Verdict: cause of the teardown is
insufficient evidence** — and it does not need to be known, because the code must be correct
either way (see §7).

Noise that is *not* the cause (checked, no causal link in this log):
`mtkpower`/`libPowerHal` thermal `SKIN 47.533 SEVERE` and mode switching, `MALI DEBUG
eglp_winsys_populate_image_templates`, `BufferQueueDebug`/`SurfaceFlinger` destructor spam
(timestamps `34.101`–`34.158`, i.e. *after* the fatal), `OpenGLRenderer: Unable to match the
desired swap behavior`. These are the ordinary tail of a window being destroyed plus a warm
device; nothing in the log ties any of them to the `NullPointerException`, and the exception
message names its own cause.

## 5. Proven root cause

**A view field that was never bound.** From the released source at `56cba56`:

```
53:    public ViewPagerWithoutSwipe pa;                                    (declaration only)
133:        if (pa != null) pa.removeOnPageChangeListener(pageChangeListener);
143:        if (pa.getAdapter() == null) {                                  (crash site)
159:            pa.setAdapter(new ViewPagerAdapter(getSupportFragmentManager()));
162-163:     pa.removeOnPageChangeListener / addOnPageChangeListener
166:        updateNavUI(pa.getCurrentItem());
267, 288:    if (pa == null || …) return;                                     (other methods DO guard)
```

`grep -nE "(^|[^.a-zA-Z_])pa *=" MainActivity.java` at `56cba56` returns **only** the two
`if (pa == null …)` comparisons. There is no `pa = findViewById(R.id.fragment_place);`
anywhere, although `activity_main.xml:11` defines `@+id/fragment_place` and every other view
field (`navHome`, `ivHome`, `tvHome`, …) is bound in `initNav()` — `pa` was simply left out.
So `initializeLauncherUi()` dereferenced a field that was null by construction. The 28 ms
window teardown made the crash *more likely* (nothing to show, callback still queued), but
the NPE would have thrown on the first healthy launch too.

This also explains why the crash appears **only now**: the launcher UI used to be built
synchronously inside `onCreate`; the "build after the data gate" rewrite moved it behind a
callback and the pager binding was lost in that move.

## 6. Comparison with the previous crash

| Marker | Expected | In `crashv2.log` | Classification |
|---|---|---|---|
| `SettingsFragment.java:107` `requireView()` | fixed crash site | **0 lines** — no `SettingsFragment` frame, no `IllegalStateException` | **moved elsewhere / not present** |
| `MainActivity.java:139` | old fixed crash entry | line 143 in the log; **same method**, different statement | **moved within the same method** |
| `TextureDatabaseRuntime::SortEntries(bool)` | separate investigation | **0 lines** | **insufficient evidence** (game never started) |
| `SIGSEGV 0x133f` | native marker | **0 lines**, no signal at all | **insufficient evidence** |
| `Texdb load-failure=1` | native telemetry | **0 lines** | **insufficient evidence** |
| `RLE guard calls=3` | native telemetry | **0 lines** | **insufficient evidence** |

**Classification: NEW crash, in the same method.** Previous → `IllegalStateException` from
`requireView()` inside `SettingsFragment.onCreateView()` while `setOffscreenPageLimit(3)`
populated all pages. Now → `NullPointerException` inside `MainActivity.initializeLauncherUi()`
because the pager was null. Different exception, different object, different cause; only the
file and the general area coincide. The previous fix is **not contradicted** by this log (its
frames are entirely absent, `getOffscreenPageLimit` is back at the default), but one capture
cannot prove it fixed — a successful Settings-page visit on a device still does.

`SortEntries` / TXD / DFF remain **unexamined by this log**. The `:game` process never
appears in the capture, so nothing here says the custom-model crash is better *or* worse.

## 7. Sources inspected and the evidence-based fix

| File | Why |
|---|---|
| `app/src/main/java/com/sampmobile/xyvern/launcher/MainActivity.java` | crash site; missing binding; async callback |
| `app/src/main/java/com/sampmobile/xyvern/game/GameDataManager.java` | `postToUi` (95) keeps the Activity and posts later; `checkAndPrepare` (136) fires `onReady` |
| `app/src/main/java/com/sampmobile/xyvern/diagnostics/Diagnostics.java` | the two silent `Could not write diagnostic log` warnings |
| `app/src/main/res/layout/activity_main.xml` | `@+id/fragment_place` (line 11) — the id `pa` must bind |

Changes (commit `ffeacb5`, `28c23c0`; PR #14 → merge `b85e4a4`):

1. `pa = findViewById(R.id.fragment_place);` at the top of `initializeLauncherUi()`, with an
   immediate `return` if it is null. Because `changeTab()`, `animateIncomingPage()` and
   `onDestroy()` read the same field, binding it in one place removes the NPE for all of them
   instead of moving it to the first tab tap.
2. `isLauncherUiUsable()`: `!isFinishing() && !isDestroyed() && getWindow() != null &&
   getWindow().peekDecorView() != null`. The callback is asynchronous, so a late message for a
   window that has been torn down now returns early (and records
   `LAUNCHER_UI_SKIPPED_LIFECYCLE`) instead of touching views.
3. `initNav()` returns early with `LAUNCHER_NAV_INCOMPLETE` when a nav view is missing —
   `updateNavUI()` already tolerated null handles, so the pager keeps working.
4. `Diagnostics.record()` now logs the exception class and message (once per process) instead
   of a bare "Could not write diagnostic log", so the next capture will say *why* the store
   failed instead of hiding it.
5. New sourcecheck rule `check_view_fields_are_bound()`: fails when a view field is
   dereferenced but never bound from `findViewById()`. Regression-verified by removing the new
   binding line:
   `FAIL …/MainActivity.java: view field 'pa' is used at line 134 but only ever assigned from itself; bind it with findViewById()`.
   The fixed tree passes.

Deliberately **not** done: no ViewPager/page/animation/Settings feature removed; no exception
swallowed; `SortEntries`, Texture DB and Custom Models untouched; custom models **not**
disabled.

## 8. Verification — what is real and what is not

| Check | Result |
|---|---|
| `tools/sourcecheck/checks.py` | **PASS** (incl. the new rule) |
| New rule negative test (binding removed by hand) | **FAIL as intended**, exact message above |
| `tools/hosttest/run.sh` | **75 PASS** |
| CI `42612628556` (`ffeacb5`) | **success** |
| CI `42612869016` (`28c23c0`) | **success** |
| CI `42614047671` (merge `b85e4a4`, on `main`) | **success** |
| Release | `apk-20261109-120939` — `HopePride-SAMP-Android.apk`, 75 776 361 B |
| **Device run** | **NOT DONE.** No phone or emulator here; nothing below is claimed |

**Not verified / open:**
- The launcher rendering without a crash, and tab switching, on a real device.
- The Settings page (the previous fix) — still needs a real visit.
- Why the window was torn down 28 ms before the fatal (§4) — unexplained, and the fix does not
  depend on it.
- Why `Diagnostics` could not write — only that it now reports the reason.

**To verify on device (needed before this can be called fixed):**
1. Cold start → splash → launcher; tab through Home/Play/Settings three times; press Back and
   relaunch mid-splash. Expect no NPE and a visible `LAUNCHER_DATA_GATE_READY` /
   `LAUNCHER_ACTIVITY_START` pair in the diagnostics log.
2. Repeat with "Don't keep activities" enabled — this maximises exactly the
   callback-after-teardown case the gate covers; expect `LAUNCHER_UI_SKIPPED_LIFECYCLE`
   instead of a crash.
3. Reproduce the original path (leave during the splash while data is being verified) and
   confirm the process survives and rebuilds the UI on the next start.
4. If a crash still happens, capture a **full** logcat (`adb logcat -b all -v threadtime`) from
   the moment of launch; this report needs the section the current file lacks — a tombstone
   would be required before any native/Texture-DB claim can be made.
