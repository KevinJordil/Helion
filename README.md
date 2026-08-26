# Helion

A personal health tracker for the Amazfit Helio Strap, built as a companion to
[Gadgetbridge](https://gadgetbridge.org/).

Helion never talks to the wristband. Gadgetbridge owns the Bluetooth link and the
Zepp OS protocol; Helion drives it through its Intent API, reads the database it
exports, and keeps its own archive on the phone. Nothing leaves the device.

    Helio Strap ──BLE──▶ Gadgetbridge ──intents + file──▶ Helion ──▶ Strava

Everything is stored locally in the app's own database. There is no account, no
server, and no automatic publishing: an activity reaches Strava only when you
explicitly choose to save a file and import it yourself — see
[Getting an activity onto Strava](#getting-an-activity-onto-strava). You can also
send an activity straight to a server of your own; see
[Sending an activity to your own server](#sending-an-activity-to-your-own-server).

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

## Getting an activity onto Strava

Helion has no direct Strava API integration — it never talks to Strava's servers on
its own. Getting an activity onto Strava is a manual, explicit step from the
activity detail screen:

- **Enregistrer le fichier** writes a TCX file straight into the phone's Downloads
  folder, named from the sport and start time (no share sheet).
- **Ouvrir Strava** opens Strava's web upload page in the browser — from there:
  Fichier → choose the saved file. TCX only distinguishes running, cycling, and
  "other", so the sport needs correcting inside Strava after import.

A plain share action is also available, for sending the same file to a computer
and importing it through Strava's web uploader instead.

(An earlier version of this app published directly through Strava's API. That
integration was removed once Strava's Standard API tier started requiring a paid
subscription the owner does not want; the code is preserved in git history, see
`docs/archive/strava-api-integration.md` on the machine that removed it.)

## Sending an activity to your own server

A second, independent export target: configure a server URL and a shared token in
Réglages, then tap **Envoyer à mon serveur** on any activity's detail screen. Helion
only sends — what the receiving end does with the activity is entirely up to you.

The request is a plain `POST` to the configured URL, `Content-Type:
multipart/form-data`, with `Authorization: Bearer <token>`. The token is never
logged and never appears in any error message shown in the app.

Fields:

| Field              | Type          | Notes                                                                 |
|--------------------|---------------|------------------------------------------------------------------------|
| `file`             | file          | The activity's TCX. Filename matches the Downloads export, e.g. `badminton-2026-08-26-2010.tcx`. |
| `sport`            | text          | A stable, lower-case slug (`badminton`, `running`, `cycling`, `walking`, `swimming`, `other`) — never a translated label, so it never changes with the app's display language. |
| `title`            | text          | The activity's title, or `Helion` if it has none.                    |
| `description`      | text          | The owner's own notes on the activity. May be empty. Never the detection explanation shown while reviewing a candidate -- that is diagnostic text for the app, not something sent anywhere. |
| `start`            | text          | ISO 8601 with a UTC offset, e.g. `2026-08-26T20:10:00+02:00`.         |
| `duration_seconds` | text (integer)| End minus start, in seconds.                                         |
| `calories`         | text (integer)| Present only when Helion has both a complete profile and heart-rate data to estimate from — omitted entirely otherwise, never sent as `0`. |
| `external_id`      | text          | Stable per activity (`helion-activity-<id>`) and identical across every send target. Use it to recognise a repeat send instead of creating a duplicate — Helion will happily send the same activity again (a retry, a manual re-send after fixing the server) and always uses this same value. |

A plain `http://` URL is refused until you explicitly tick the confirmation
checkbox next to it in Réglages — an activity carries heart-rate data, and sending
it in clear text is a choice, not a default.

Any non-2xx response, an unreachable host, and a rejected token each show their own
message on the activity detail screen — the real HTTP status and response body for
the first, the transport error for the second, never a generic "failed".

## Working on the code

    ./scripts/helion.sh test              # unit tests
    ./scripts/helion.sh build             # assemble the debug APK
    ./scripts/helion.sh pull-export       # copy the device's export here for inspection
    ./scripts/helion.sh schema <file>     # dump the schema of an export database

An exported Gadgetbridge database contains personal health data. `Gadgetbridge` and
`Gadgetbridge*.db` are git-ignored; keep it that way.

Code, comments and commit messages are in English. Text shown in the app is in
French and lives in `app/src/main/res/values/strings.xml`.
