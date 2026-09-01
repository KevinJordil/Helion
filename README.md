# Helion

A personal health tracker for the Amazfit Helio Strap, built as a companion to
[Gadgetbridge](https://gadgetbridge.org/).

Helion never talks to the wristband. Gadgetbridge owns the Bluetooth link and the
Zepp OS protocol; Helion drives it through its Intent API, reads the database it
exports, and keeps its own archive on the phone. By default, nothing leaves the
device.

    Helio Strap ──BLE──▶ Gadgetbridge ──intents + file──▶ Helion ──▶ Strava
                                                              │
                                                              ▶ Health Connect ──▶ Samsung Health

Everything is stored locally in the app's own database first. There is no account
and no automatic publishing by default: an activity reaches Strava only when you
explicitly send it through your own server — see
[Getting an activity onto Strava](#getting-an-activity-onto-strava) and
[Sending an activity to Strava through your own server](#sending-an-activity-to-strava-through-your-own-server).
The one exception is Health Connect: once you turn it on in Réglages, Helion keeps
that shared system store in sync on its own — see
[Exporting to Health Connect](#exporting-to-health-connect). Off (the default),
this app writes nothing anywhere outside its own archive.

## Status

Early. The data layer is in place — ingestion from Gadgetbridge, a local archive,
per-metric history, and TCX export. Sleep analysis and activity detection come
next. Two things still need verifying against real hardware; see
[Verifying the Gadgetbridge link](#verifying-the-gadgetbridge-link).

## Requirements

- Android 8.0 or newer.
- [Gadgetbridge](https://gadgetbridge.org/) installed and paired with the strap.
- For building: JDK 17 and the Android SDK (platform 36, build-tools 36).

## Setting up the phone

### 1. Pair Gadgetbridge with the strap

The strap is encrypted, and only Zepp's servers issue the pairing key — no app can
generate one. Pair with the official Zepp app first, then extract the key with
[huami-token](https://codeberg.org/argrento/huami-token):

    python3 huami_token.py --method amazfit --email you@example.com --password yourpassword

Enter it in Gadgetbridge prefixed with `0x`.

Two warnings. Do not unpair from Zepp before you have the key — unpairing erases it.
And a hard reset of the strap changes its Bluetooth address, which invalidates the
key and forces you to fetch a new one.

Once Gadgetbridge is paired, remove or disable Zepp. Two apps syncing the same strap
produce inconsistent sleep data.

### 2. Enable the Gadgetbridge features Helion needs

In Gadgetbridge:

- **Settings → Auto export** — enable it and choose an export location. Helion
  triggers exports on demand, so the interval does not limit how fresh your data is.
- **Settings → Developer options → Intent API** — enable it, along with the sync and
  export categories.

### 3. Enable USB debugging

On the phone: **About phone** → tap **Build number** seven times, then in
**Developer options** enable **USB debugging**. Plug the phone in and accept the
authorisation prompt.

## Building and installing

    ./scripts/helion.sh doctor     # check the toolchain and that a device is visible
    ./scripts/helion.sh test       # run the unit tests
    ./scripts/helion.sh install    # build and install on the connected device
    ./scripts/helion.sh logs       # follow the app's logs

Run `./scripts/helion.sh` with no arguments to list every command.

## Verifying the Gadgetbridge link

Helion drives Gadgetbridge with two intents whose exact names come from
documentation and have not yet been confirmed against a running install. If they are
wrong, Helion waits, times out, and reports a failure — it never silently pretends
to have synced. Confirm them once:

    ./scripts/helion.sh gb-check

This broadcasts both intents and shows the export file's timestamp before and after.
A sync should be visible in Gadgetbridge, and the export file's modification time
should change. If it does not, the export action name is wrong: try `TRIGGER_EXPORT`
instead of `TRIGGER_DATABASE_EXPORT` in `GadgetbridgeCommands.kt`. Both names live in
one place precisely so this is a one-file fix.

## First run

Once installed, in this order:

1. Open the settings screen and pick the Gadgetbridge export file. This exercises the
   file picker and the persistable permission.
2. Tap **Sync now** and check that values appear. This is the real test of the intent
   names.
3. Reboot the phone, reopen Helion, sync again. This is the only way to confirm the
   file permission survived — a failure here never shows up on the first run.

## Sport catalogue

Every sport [`SportType`](app/src/main/java/ch/kevinjordil/helion/store/Sport.kt) offers is
exactly Strava's own activity type vocabulary, spelled the way Strava writes it — no local
additions, so every stored sport has a real Strava equivalent on export. The picker groups
all fifty-six under eight categories
(cycling; running and walking; water; snow and ice; racket sports; indoor and fitness; team
sports; other) with a search field that matches against the French label shown on screen,
never the underlying English identifier.

An activity coming from a declared [slot](app/src/main/java/ch/kevinjordil/helion/store/Slot.kt)
always takes that slot's own sport — the owner named and configured it, so it is the one
reliable signal detection has. An activity detection finds with no slot behind it carries no
sport at all: heart rate alone cannot tell a motorcycle ride from a river descent from
badminton, and guessing one would be exactly the kind of assumption this app exists to avoid.
An activity with no sport is otherwise perfectly normal — it can be kept, reviewed and edited
like any other — but every export path (Downloads, share, the custom server, Health Connect)
refuses to send it until the owner sets one, rather than guessing or falling back to a generic
label.

## Getting an activity onto Strava

Helion has no direct Strava API integration — it never talks to Strava's servers on
its own. Getting an activity onto Strava is a single, explicit step from the
activity detail screen: configure a server URL and a shared token in Réglages,
then tap **Envoyer vers Strava** at the bottom of the screen. That server is what
actually talks to Strava's API — see
[Sending an activity to Strava through your own server](#sending-an-activity-to-strava-through-your-own-server)
below for the exact request it sends.

Two other ways of getting the same TCX file off the phone — saving it straight to
the phone's Downloads folder, and the plain Android share sheet — still exist in
the code (`TcxWriter`, `DownloadsExport`, `TcxShare`) and are covered by tests, but
are no longer offered as buttons on this screen.

(An earlier version of this app published directly through Strava's API. That
integration was removed once Strava's Standard API tier started requiring a paid
subscription the owner does not want; the code is preserved in git history, see
`docs/archive/strava-api-integration.md` on the machine that removed it.)

## Sending an activity to Strava through your own server

Configure a server URL and a shared token in Réglages, then tap **Envoyer vers
Strava** at the bottom of any activity's detail screen. Helion only sends — what
the receiving end does with the activity, and how it talks to Strava's own API, is
entirely up to your server.

The request is a plain `POST` to the configured URL, `Content-Type:
multipart/form-data`, with `Authorization: Bearer <token>`. The token is never
logged and never appears in any error message shown in the app.

Fields:

| Field              | Type          | Notes                                                                 |
|--------------------|---------------|------------------------------------------------------------------------|
| `file`             | file          | The activity's TCX. Filename matches the Downloads export, e.g. `badminton-2026-08-26-2010.tcx`. |
| `sport`            | text          | A stable, lower-case, hyphenated slug derived from the sport's own identifier (e.g. `badminton`, `rock-climbing`, `high-intensity-interval-training`) — never a translated label, so it never changes with the app's display language. Absent (the request is refused before it is ever sent) when the activity has no sport set. |
| `title`            | text          | The activity's title, or `Helion` if it has none.                    |
| `description`      | text          | The owner's own notes on the activity. May be empty. Never the detection explanation shown while reviewing a candidate -- that is diagnostic text for the app, not something sent anywhere. |
| `start`            | text          | ISO 8601 with a UTC offset, e.g. `2026-08-26T20:10:00+02:00`.         |
| `duration_seconds` | text (integer)| End minus start, in seconds.                                         |
| `calories`         | text (integer)| Present only when Helion has both a complete profile and heart-rate data to estimate from — omitted entirely otherwise, never sent as `0`. |
| `external_id`      | text          | Stable per activity (`helion-activity-<id>`) and identical across every send target. Use it to recognise a repeat send instead of creating a duplicate — Helion will happily send the same activity again (a retry, a manual re-send after fixing the server) and always uses this same value. |

A plain `http://` URL is refused until you explicitly tick the confirmation
checkbox next to it in Réglages — an activity carries heart-rate data, and sending
it in clear text is a choice, not a default.

Any 2xx response is a success — `200` and `202` both are, `200` meaning "already
received this activity, nothing was re-sent to Strava" and `202` meaning "freshly
accepted"; the activity detail screen tells the two apart. Whatever your server
answers — success or failure — its own response text is shown verbatim on the
activity detail screen, next to the real HTTP status, so its exact wording and
status code are what you see while debugging your own server; an unreachable host
still shows a plain transport error instead, since there is no response to show in
that case.

## Exporting to Health Connect

Samsung Health cannot read the Helio Strap at all, but it synchronises
bidirectionally with Android's Health Connect. Turn the export on in Réglages and
Helion keeps Health Connect in sync on its own from then on — no per-item button,
unlike the custom-server target above. Off (the default), nothing is written.

Turning it on asks, right there, for Health Connect's own runtime write
permissions — one per record type below. Refusing, or revoking any of them later
from Android's own permission screen, leaves the rest of the app untouched: writing
simply stops, and Réglages says so plainly next to the toggle. Health Connect
itself is a separate, optional system component; if it is not installed, or the
installed version is too old, Réglages says that too instead of writing anything.

What is written, as the matching Health Connect record type:

| Helion data                                   | Health Connect record                                    |
|------------------------------------------------|-----------------------------------------------------------|
| Sleep, with the device's own hypnogram stages   | `SleepSessionRecord`, with `Stage`s                        |
| Confirmed or published activities               | `ExerciseSessionRecord`, plus their own `HeartRateRecord`  |
| Heart rate, steps                               | `HeartRateRecord`, `StepsRecord` (one of each per UTC day) |
| HRV, SpO2, skin temperature, respiratory rate    | `HeartRateVariabilityRmssdRecord`, `OxygenSaturationRecord`, `SkinTemperatureRecord`, `RespiratoryRateRecord` |

Only [`ActivityStatus.CONFIRMED`](app/src/main/java/ch/kevinjordil/helion/store/Activity.kt)
or `PUBLISHED` activities are ever exported — a candidate the owner has not looked
at, or one explicitly dismissed, is never written, the same rule the rest of this
app enforces everywhere else. A night with no device-reported hypnogram (this
app's own minute-derived estimate instead) is never exported either — writing a
guess into a store another app treats as measured data would misrepresent it.

Every record carries a stable client record id derived from Helion's own identity
(an activity's id, a sleep session's own wake time, a reading's own timestamp) —
re-running the export updates the matching record instead of duplicating it, the
same discipline `external_id` already gives the custom-server target. Most of the
sport catalogue lands on a real or reasonably close Health Connect exercise type
(badminton maps exactly; cycling variants map to its generic biking type; swimming
maps to its pool type). Seven sports have no Health Connect equivalent at all
(kitesurfing, windsurfing, roller skiing, padel, pickleball, physical therapy, and
skateboarding) and fall back to its generic workout type instead
of a misleading near match — see
[`healthConnectExerciseType`](app/src/main/java/ch/kevinjordil/helion/healthconnect/HealthConnectSupport.kt)
for the full mapping and the reasoning behind each fallback. A confirmed activity
with no sport set is left out of the export pass entirely until the owner sets
one — see "Sport catalogue" above.

The export runs automatically after every ingest pass that stored something new,
and on demand via **Exporter maintenant** in Réglages — as a background job, never
awaited by ingestion itself, so a slow or unreachable Health Connect can never slow
a sync down. Réglages shows whether the export is on, whether the permission is
granted, when the last pass ran, and exactly what it wrote or why it failed.

## Working on the code

    ./scripts/helion.sh test              # unit tests
    ./scripts/helion.sh build             # assemble the debug APK
    ./scripts/helion.sh pull-export       # copy the device's export here for inspection
    ./scripts/helion.sh schema <file>     # dump the schema of an export database

An exported Gadgetbridge database contains personal health data. `Gadgetbridge` and
`Gadgetbridge*.db` are git-ignored; keep it that way.

Code, comments and commit messages are in English. Text shown in the app is in
French and lives in `app/src/main/res/values/strings.xml`.
