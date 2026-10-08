# ওষুধের সময় — medicine reminder

An offline-first Android alarm app for a fixed daily prescription. It rings like a clock alarm,
shows exactly which medicines are due, and records whether each dose was taken.

Bengali interface, medicine names in English (as written on the prescription chart).

---

## The seeded schedule

The database ships pre-loaded with the chart this app was built from. Everything is editable
in-app — prescriptions change.

| Slot | Time | Medicines | Food rule |
|---|---|---|---|
| সকাল | **not set** | Empa 10 | খালি পেটে |
| | | Nidocard RH 2.6 · Vasteral MR · Betaloc 50 | খাবার পরে |
| দুপুর | 2:30 PM | Clopid AS | খাবার পরে |
| সন্ধ্যা | 7:00 PM | Atova 10 · Nidocard RH 2.6 | খাবার আগে |
| রাত | 10:00 PM | Betaloc 50 · Bizoran 5/40 · Vasteral MR · Siglimet 50/500 | খাবার পরে |

> The morning slot ships with **no time**, because the chart gave none. The app refuses to
> invent one and prompts for it on first launch. Until it is set, that slot is not armed.

## How an alarm behaves

A dose due at 3:00 with the shipped defaults:

```
3:00 ─ rings 2 min ─ 3:02 silent
3:05 ─ rings 2 min ─ 3:07 silent
3:10 … 3:15 … 3:20 … 3:25
3:30 ─ recorded as MISSED, stops
```

Confirming at any point (**খাওয়া হয়েছে**) records TAKEN, silences the alarm and cancels the
rest of the cycle. **বাদ দিন** records SKIPPED. Snooze re-rings after the configured gap.

All four durations are editable in **সেটিংস**, which shows a live preview of the resulting
cycle. Values are kept coherent automatically: the retry gap must exceed the ring length, and
the give-up window must be at least one full gap.

| Setting | Default |
|---|---|
| অ্যালার্ম কতক্ষণ বাজবে (ring) | 2 min |
| আবার বাজার বিরতি (retry gap) | 5 min |
| কতক্ষণ পর মিস ধরা হবে (give up) | 30 min |
| স্নুজের সময় (snooze) | 10 min |

## Why it rings on time

Medicine alarms fail when the OS defers them. Specific measures:

- **`AlarmManager.setAlarmClock`** — the only scheduling API Doze and battery optimisation will
  not defer. Verified via `dumpsys alarm`: `exactAllowReason=policy_permission`, and the app
  appears as *"Next wake from idle"*.
- **`USE_EXACT_ALARM`** — granted at install on API 33+; legitimate for an alarm app. On API
  31–32 the app requests `SCHEDULE_EXACT_ALARM` and surfaces a banner if denied.
- **Foreground service owns the sound**, not the Activity, so a blocked or dismissed
  full-screen intent still produces something audible. `USAGE_ALARM` audio plays through
  silent/DND.
- **Full-screen intent over the lock screen** — `setShowWhenLocked` + `setTurnScreenOn`.
  Verified: the alarm woke a sleeping device and took over the lock screen.
- **Re-armed on boot, reinstall, timezone change and clock change** (`BootReceiver`), on every
  app resume, and immediately when an alarm fires — so tomorrow's dose is set even if today's
  is ignored.
- **Escalation is armed when the ring starts**, not when it ends, so the cycle survives the
  process being killed mid-alarm.
- **Setup banners** on the আজ tab prompt for notification permission, exact-alarm permission,
  full-screen-intent permission, and battery-optimisation exemption — re-checked on every resume.

## Data

Room/SQLite, entirely offline.

| Table | Purpose |
|---|---|
| `dose_groups` | Slots: label, time, enabled, `createdAt` |
| `dose_items` | Medicines per slot, with food rule |
| `dose_logs` | One row per dose: TAKEN / SKIPPED / MISSED |
| `settings` | Escalation durations |

`dose_logs` has no foreign key on `groupId` and snapshots the slot label and medicine list, so
history survives editing or deleting the schedule. It also carries `uuid` and `synced` columns,
ready for the planned server sync — **no sync code exists yet**, as agreed.

`createdAt` on each slot prevents fabricated history: doses scheduled before a slot existed are
never written off as missed. Without it, a fresh install invented 21 missed doses for the week
before the app was installed.

## Build and run

```powershell
.\gradlew assembleDebug        # APK -> app/build/outputs/apk/debug/
.\gradlew installDebug         # install on a connected device/emulator
.\gradlew testDebugUnitTest    # escalation-logic tests
.\gradlew lintDebug
```

Emulator:

```powershell
emulator -avd Pixel_9_API37
adb shell pm grant com.medremind.app android.permission.POST_NOTIFICATIONS
adb shell appops set com.medremind.app USE_FULL_SCREEN_INTENT allow
```

Inspect the database on a debug build:

```powershell
adb shell "run-as com.medremind.app sqlite3 databases/medremind.db 'SELECT * FROM dose_logs;'"
```

### Testing the escalation quickly

Compress the timeline instead of waiting 30 minutes:

```powershell
adb shell "run-as com.medremind.app sqlite3 databases/medremind.db 'UPDATE settings SET ringSeconds=60, retryIntervalSeconds=120, giveUpSeconds=300;'"
```

## Toolchain

AGP 9.3.1 · Kotlin 2.4.10 (built into AGP) · Gradle 9.7.0 · Compose BOM 2026.06.01 ·
Room 2.8.4 + KSP 2.3.11 · compileSdk/targetSdk 37 · minSdk 26 · JDK 21.

> **AGP 9 has built-in Kotlin support.** Applying `org.jetbrains.kotlin.android` is now a build
> error. Only `com.android.application`, the Compose compiler plugin, and KSP are applied.

## Layout

```
app/src/main/java/com/medremind/app/
  MainActivity.kt          tab host (আজ / সময়সূচি / ইতিহাস / সেটিংস)
  MedRemindApp.kt          channels, startup reconcile + reschedule
  data/                    Room entities, DAO, repository, first-run seed
  alarm/
    AlarmScheduler.kt      daily alarms + separate retry channel
    AlarmReceiver.kt       dose due / retry / boot receivers
    AlarmService.kt        foreground service: sound, vibration, wake lock
    DoseActions.kt         single path for TAKEN / SKIPPED / snooze
  ui/                      Compose screens, view model, Bengali formatting
```

## Not done yet

- **Server sync** — deferred by request. Schema is ready (`uuid`, `synced`); no networking code.
- **Instrumented UI tests** — logic is unit-tested; UI was verified manually on an API 37 emulator.
- Renaming a medicine that appears in two slots must be done in both.

## Caveat

This is a backup to the paper chart, not a replacement. Phones get silenced, run out of battery,
and get "optimised" by aggressive OEM battery managers (Xiaomi, Oppo, Vivo, Samsung especially).
Whitelist the app in the phone's battery settings, and do not rely on it as the only safeguard
for a cardiac regimen.
