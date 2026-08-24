# Helion

A personal health tracker for the Amazfit Helio Strap, built as a companion to
[Gadgetbridge](https://gadgetbridge.org/).

Helion never talks to the wristband. Gadgetbridge owns the Bluetooth link and the
Zepp OS protocol; Helion drives it through its Intent API, reads the database it
exports, and keeps its own archive on the phone. Nothing leaves the device.

    Helio Strap ──BLE──▶ Gadgetbridge ──intents + file──▶ Helion ──▶ Strava (planned)

Everything is stored locally in the app's own database. There is no account, no
server, and no automatic publishing: an activity reaches Strava only when you
explicitly choose to publish it.

## Status

Early. The data layer is in place — ingestion from Gadgetbridge, a local archive,
and per-metric history. Sleep analysis, activity detection and Strava publishing
come next. Two things still need verifying against real hardware; see
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

## Working on the code

    ./scripts/helion.sh test              # unit tests
    ./scripts/helion.sh build             # assemble the debug APK
    ./scripts/helion.sh pull-export       # copy the device's export here for inspection
    ./scripts/helion.sh schema <file>     # dump the schema of an export database

An exported Gadgetbridge database contains personal health data. `Gadgetbridge` and
`Gadgetbridge*.db` are git-ignored; keep it that way.

Code, comments and commit messages are in English. Text shown in the app is in
French and lives in `app/src/main/res/values/strings.xml`.
