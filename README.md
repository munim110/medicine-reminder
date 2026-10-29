# ওষুধের সময় — medicine reminder

An offline-first Android alarm app for a fixed daily prescription. It rings like a clock alarm,
shows exactly which medicines are due, and records whether each dose was taken.

Bengali interface, medicine names in English (as written on the prescription chart).

---

## The starting schedule

A new install has the four customary slots and **no medicines**, so a phone that only follows
family never rings. Medicines come from a prescription scan or are typed in.

| Slot | Time |
|---|---|
| সকাল | **not set** |
| দুপুর | 2:30 PM |
| সন্ধ্যা | 7:00 PM |
| রাত | 10:00 PM |

> The morning slot ships with **no time**. The app refuses to invent one: once it holds a
> medicine, the আজ tab asks for the time. Until it is set, that slot is not armed.
>
> Versions before 1.1 shipped one family's prescription pre-loaded. A phone signed in with
> Google (a guardian's) that still carries exactly that list gets a card on আজ offering to clear it.

A slot only rings on days it has something due, and never for a dose already answered — taking
it early from the আজ tab moves that slot's alarm to the next day.

## How an alarm behaves

A dose due at 3:00 with the shipped defaults:

```
3:00 ─ rings 2 min ─ 3:02 silent
3:05 ─ rings 2 min ─ 3:07 silent
3:10 … 3:15 … 3:20 … 3:25
3:30 ─ recorded as MISSED, stops
```

Confirming at any point (**খাওয়া হয়েছে**) records TAKEN, silences the alarm and cancels the
rest of the cycle. **বাদ দিন** records SKIPPED. Snooze re-rings after the configured gap; a
snooze that lands past the give-up time still rings once, and the dose is recorded MISSED only if
that ring goes unanswered too.

Doses the phone never got to ring for (it was off, say) are back-filled as MISSED when the app
next runs, judged by the current schedule — but only from when that slot's schedule last changed,
so adding a medicine or setting a time never invents a week of missed doses.

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

Room/SQLite on the device; the schedule and history live here.

| Table | Purpose |
|---|---|
| `dose_groups` | Slots: label, time, enabled, `createdAt` |
| `medicines` | One row per medicine: name, generic name, form, instructions, stock (null = not tracked) |
| `dose_items` | A medicine in a slot: quantity (½, 1, 1½…), food rule, frequency (daily / weekly on a weekday / monthly on a date) |
| `dose_logs` | One row per dose: TAKEN / SKIPPED / MISSED |
| `settings` | Escalation durations |

A slot rings only on days when at least one of its items is due, so a weekly-only slot stays
silent on the other six days. Stock goes down by the dose quantity the first time a dose is
marked taken; at five days or fewer left the আজ tab shows a shopping-list banner.

Version 1 databases migrate in place (`MIGRATION_1_2`): medicine names repeated across slots
become one `medicines` row, so renaming happens once.

`dose_logs` has no foreign key on `groupId` and snapshots the slot label and the medicines due
that day, so history survives editing or deleting the schedule. `uuid` and `synced` drive the
family sync.

`createdAt` on each slot prevents fabricated history: doses scheduled before a slot existed are
never written off as missed. Without it, a fresh install invented 21 missed doses for the week
before the app was installed.

## Build and run

```powershell
.\gradlew assembleDebug        # APK -> app/build/outputs/apk/debug/
.\gradlew installDebug         # install on a connected device/emulator
.\gradlew testDebugUnitTest    # escalation, schedule and drug-matching tests
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

## Cloud

Alarms never touch the network. Cloud features are additive and fail quietly when offline.

```
app ── anonymous sign-in ──▶ Firebase Auth (project medremind-7e401) ──▶ ID token
 │
 ├── POST /scan  photo + ID token ──▶ Cloudflare Worker (server/scan-worker)
 │                                      ├─ verifies the token against Google's public keys
 │                                      ├─ daily limits in D1: per user, and for everyone
 │                                      └─ AI_PROVIDER: DeepSeek or a Bedrock model (+ optional fallback)
 │
 └── family sharing ──▶ Firestore (patients/…, pairCodes/…, users/…/linked)
```

| Piece | Where | Notes |
|---|---|---|
| Firebase Auth | Anonymous (patient) + Google (guardians) | Enabled via `firebase.json`, `npx -y firebase-tools@latest deploy --only auth` |
| Firestore | `asia-south1`, delete protection on | [firestore.rules](firestore.rules); tested in [server/firestore-rules](server/firestore-rules) |
| Scan Worker | `https://medremind-scan.munim987654321.workers.dev/scan` | Never logs or stores images or results |
| `app/google-services.json` | not committed | `npx -y firebase-tools@latest apps:sdkconfig ANDROID 1:288323619548:android:d194f6579c680d120fc219` |

### Prescription scan

সময়সূচি → **প্রেসক্রিপশনের ছবি থেকে যোগ করুন** (camera or gallery). The first use explains that the
photo goes to an AI service and is not kept. The result opens a review screen where every
medicine must be ticked as checked against the paper before import. Names the AI read are matched
on the device against 21,000 Bangladeshi brands (`assets/drugs.tsv`), so "Janner 50/500" offers
"Janmet 50/500" with its generic name. Course durations are kept in the instructions verbatim;
the app never ends a course by itself.

### Switching the AI provider

The Worker talks to the AI through [providers.ts](server/scan-worker/src/providers.ts); the
prompt and the JSON parsing are shared, so every provider returns the same result to the app.
Switching is configuration only, in [wrangler.jsonc](server/scan-worker/wrangler.jsonc):

| | DeepSeek (fallback) | Bedrock (Kimi K2.5, current) |
|---|---|---|
| `AI_PROVIDER` | `"deepseek"` | `"bedrock"` |
| `DAILY_SCANS_TOTAL` | `"300"` (≈ $0.30/day) | `"250"` (≈ $0.005/scan, ≈ $9/week at the cap) |
| Secrets | `DEEPSEEK_API_KEY` | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` |
| Model var | `DEEPSEEK_MODEL` | `BEDROCK_MODEL`, `BEDROCK_REGION` |

Setting `AI_FALLBACK` to the other provider retries there when the first one errors (not when
the photo is simply unreadable). A provider whose secrets are missing is skipped.

Bedrock is called through the Converse API, so `BEDROCK_MODEL` can be any Bedrock vision model,
by model ID or inference-profile ID (for example `global.anthropic.claude-haiku-5-5`, which
would need `"140"` scans/day to stay under $10/week). The IAM user `bedrock-user` already has
the `bedrock:InvokeModel` permission this needs. The deployed Worker runs this setup now:

```powershell
cd server\scan-worker
# Paste each value at the prompt. Piping it in from PowerShell adds a line break that AWS rejects.
npx wrangler secret put AWS_ACCESS_KEY_ID
npx wrangler secret put AWS_SECRET_ACCESS_KEY
# wrangler.jsonc: AI_PROVIDER "bedrock", AI_FALLBACK "deepseek", DAILY_SCANS_TOTAL "250"
npx wrangler deploy
```

Models compared on 2026-10-08 with the real prompt, on a typed sheet and a real handwritten
prescription (Bengali doses, struck-through options):

| Model | Handwritten result | Note |
|---|---|---|
| `moonshotai.kimi-k2.5` | 6 of 7 names, doses right, only the ticked D-Rise | Best; ≈ 5 s |
| `deepseek-flash` (DeepSeek) | Listed struck-through options, invented one medicine | Cheapest |
| `us.meta.llama4-maverick-17b-instruct-v1:0` | Most names, but doses mixed up and one invented | US regions only |
| Nova Lite / Nova Pro, Mistral Large 3, Qwen3-VL | Real but wrong drug names | Avoid |
| Gemma 3, Ministral 3, Nemotron Nano, Llama 4 Scout | Garbled or missing medicines | Avoid |
| `in.moonshotai.kimi-k3` | Thinks for over a minute, no answer | Too slow |

Claude Haiku 5.5 and the OpenAI models were not enabled for this AWS account. Gemma 4 is
served only through the Mantle endpoint, which needs `bedrock-mantle:CreateInference`.

Worker:

```powershell
cd server\scan-worker
npm install
npx tsc --noEmit                                  # type-check
npm test                                          # parser and provider-chain tests
npx wrangler deploy
npx wrangler secret put DEEPSEEK_API_KEY          # paste the key; never commit it
npx wrangler d1 execute medremind-scan --remote --file schema.sql   # first time only
```

### Family (guardian) sharing

The patient's phone stays anonymous; a family member signs in with Google on their own phone.

1. Patient: পরিবার → **অভিভাবক যোগ করুন** shows an 8-character code, valid 15 minutes, with a
   button to send it.
2. Guardian: পরিবার → **Google দিয়ে সাইন ইন** → **কোড দিয়ে যুক্ত হোন**.
3. The guardian sees today's doses, 7- and 30-day adherence, the schedule, stock and the last
   31 days of history. Every 30 minutes their phone checks for missed doses and for a patient
   phone that has been silent for over 26 hours, and notifies them.
4. Guardians can set a slot's time, add stock, add or delete a medicine. These are requests in
   `edits/`; the patient's phone applies them on its next sync and shows a notification.

```
pairCodes/{code}                 patient, name, expiresAt (≤ 1 h)
patients/{uid}                   name, slots, medicines, health, lastSeen  (written only by the patient)
patients/{uid}/logs/{group_date} one per dose
patients/{uid}/guardians/{gUid}  created by the guardian with a live code
patients/{uid}/edits/{id}        guardian requests, deleted once applied
users/{gUid}/linked/{pUid}       the guardian's own list
```

Sync is WorkManager only (no push), so everything stays on free tiers: on any local change, on
resume, and hourly. Removing the last guardian (or stopping sharing) deletes the patient's cloud
data.

Rules tests (Firestore emulator, needs Java):

```powershell
cd server\firestore-rules
npm install
npm test
npx -y firebase-tools@latest deploy --only firestore:rules   # from the repo root
```

## Toolchain

AGP 9.3.1 · Kotlin 2.4.10 (built into AGP) · Gradle 9.7.0 · Compose BOM 2026.06.01 ·
Room 2.8.4 + KSP 2.3.11 · compileSdk/targetSdk 37 · minSdk 26 · JDK 21.

> **AGP 9 has built-in Kotlin support.** Applying `org.jetbrains.kotlin.android` is now a build
> error. Only `com.android.application`, the Compose compiler plugin, and KSP are applied.

## Layout

```
app/src/main/java/com/medremind/app/
  MainActivity.kt          tab host (আজ / সময়সূচি / ইতিহাস / পরিবার / সেটিংস), camera + gallery
  MedRemindApp.kt          channels, startup reconcile + reschedule, sync triggers
  data/                    Room entities, DAO, repository, migration, first-run seed
    Import.kt              scan → schedule: slot matching, ½-dose wording
    DrugDirectory.kt       on-device fuzzy match against assets/drugs.tsv
  alarm/
    AlarmScheduler.kt      daily alarms + separate retry channel
    AlarmReceiver.kt       dose due / retry / boot receivers
    AlarmService.kt        foreground service: sound, vibration, wake lock
    DoseActions.kt         single path for TAKEN / SKIPPED / snooze
  cloud/
    CloudSession.kt        anonymous sign-in, Google sign-in (account linking), ID tokens
    ScanClient.kt          photo → scan Worker → parsed medicines
    FamilyModel.kt         Firestore paths, pairing codes, remote documents
    PatientSync.kt         patient side: upload logs + snapshot, apply guardian edits
    Guardian.kt            guardian side: link, read, request edits, missed-dose watch
  ui/                      Compose screens, view models, scan review, family, Bengali formatting

server/scan-worker/        Cloudflare Worker: auth check, quotas, AI providers
server/firestore-rules/    emulator tests for firestore.rules
tools/drug-list/           builds assets/drugs.tsv from the dataset below
```

## Not done yet

- **App Check** — before release, so only the genuine app can call the Worker and Firestore.
- **Release signing** — add the release key's SHA-1 to Firebase, or Google sign-in fails in
  release builds.
- **Instrumented UI tests** — logic is unit-tested and the Firestore rules are emulator-tested; UI
  was verified manually on an API 37 emulator. Google sign-in needs a device with an account.

## Credits

Drug names: *Medicinal Products in Bangladesh*, Mendeley Data,
<https://data.mendeley.com/datasets/zhtvkny53n>, licensed CC BY 4.0.

## Caveat

This is a backup to the paper chart, not a replacement. Phones get silenced, run out of battery,
and get "optimised" by aggressive OEM battery managers (Xiaomi, Oppo, Vivo, Samsung especially).
Whitelist the app in the phone's battery settings, and do not rely on it as the only safeguard
for a cardiac regimen.
