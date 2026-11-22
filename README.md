# BTMap Mobile

A cell monitoring app for Android, built for Polish mobile networks and integrated with
[BTMap](https://github.com/btmap-pl/btmapv2), the map of base stations in Poland.

BTMap Mobile reads what the modem actually reports: serving and neighbour cells, carrier
aggregation, signal quality and the base station you are connected to. It has its own core
(no third-party monitoring libraries), a modern light and dark interface, and optional
modem tools through [Shizuku](https://shizuku.rikka.app/).

> **Status: beta.** The app is tested on a POCO X6 Pro (MediaTek, HyperOS) and a Samsung
> Galaxy A22 5G (Android 13). Other phones should work for monitoring; modem tools such as
> band locking currently target MediaTek. See [Known limitations](#known-limitations).

## Features

### Live
- Every SIM at once: LTE, 5G NR (NSA and SA), UMTS and GSM.
- Serving cell with eNB/gNB, CID, PCI, TAC/LAC, (E/NR/U)ARFCN, band, frequency and bandwidth.
- **Carrier aggregation** read from the modem (PhysicalChannelConfig), not guessed from a
  database: each component carrier with its own band, bandwidth and signal (RSRP, RSRQ, SNR).
- Real SNR values; vendor placeholder values (for example MIUI's TA 1282 or −44 dBm) are
  filtered out instead of being shown as measurements.
- Station name and address for the serving cell and for neighbours, on 2G, 3G, 4G and 5G.
- Distance estimate from Timing Advance.
- Neighbour cell list (can be hidden).

### Map
- **Stations:** base stations around you, with the station you are connected to highlighted.
- **Coverage:** a crowdsourced coverage map built from users' measurements, drawn as
  coverage areas per operator and band. The same layer is available on the BTMap website.

### Log
- **Cells:** every cell the phone was connected to, stored on the device, with full details
  (identifiers, bands, signal, station, time).
- **History:** a timeline of changes, such as a new cell, a PCI or band change, aggregation
  starting or stopping, or losing service.

### Background monitoring
- A foreground service with a notification showing the current bands and station.
- Battery-aware: live refresh while the app is open, lighter polling with the screen on,
  and an idle mode with the screen off (no own GPS, sparse polling, no wake lock). There is
  no switch for it; the app adjusts on its own.

### Station database
- Choose a source: **BTSearch** (default) or **BTMap** (community data, without UKE records).
- Download per operator (Plus, Play, T-Mobile, Orange, NetWorks!) and refresh automatically
  every day, week or month, optionally on Wi-Fi only.
- Operators that are already up to date are not downloaded again.
- Lookups work offline once the database is downloaded.

### Tools (Shizuku)
With Shizuku running and BTMap authorised in it:
- **Band locking** for LTE and 5G NR, using bands the modem reports as supported. Saved,
  named band configurations. One-tap reset to the phone's default bands. The app checks that
  the modem really applied the lock and tells you if it did not.
- **Network mode:** 5G/4G/3G/2G combinations, including a real 5G SA mode on MediaTek, with
  automatic fallback if the phone loses service.
- Grants itself the `DUMP` permission, so aggregation details work without a computer.

### Settings
Grouped into categories: appearance (system, light or dark theme; Polish or English, by
default following the phone), Live screen, notification,
station database, tools, band locking, coverage sharing and about (including a diagnostics
export for bug reports).

## Requirements

- Android 12 (API 31) or newer.
- Location permission (Android requires it to read cell information).
- Optional: [Shizuku](https://shizuku.rikka.app/) for the modem tools.

## Installing the beta

1. Download `BTMap-<version>.apk` from the
   [Releases](https://github.com/btmap-pl/btmap-mobile/releases) page.
2. Open it on the phone and allow installing from that source.
3. Grant location and notification permissions when asked.

### Optional: carrier aggregation details without Shizuku

Per-carrier aggregation data comes from the telephony registry, which needs the `DUMP`
permission. With Shizuku the app grants it automatically. Without Shizuku, grant it once
over adb:

```bash
adb shell pm grant pl.btmap.mobile android.permission.DUMP
```

## Privacy

- Coverage sharing is **opt-in**. The app asks on first start and the choice can be changed
  in Settings at any time.
- A shared measurement contains only the operator (MCC/MNC), technology, band, RSRP and the
  position. The server keeps it aggregated into grid squares of about 110 × 110 m, and the
  map shows only those squares. No phone identifiers (IMEI, IMSI or phone number) are sent.
- The cell log stays on the device.

## Building

Requires JDK 17 and the Android SDK (compile SDK 35).

```bash
./gradlew :core:testDebugUnitTest :app:assembleDebug
```

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

### BTMap server address

The BTMap API address is set with `btmap.apiBaseUrl` in `gradle.properties`. The default
points at a local [btmapv2](https://github.com/btmap-pl/btmapv2) development server on
port 3001. With the phone on USB:

```bash
adb reverse tcp:3001 tcp:3001
```

### Release builds

Release signing reads these properties from `~/.gradle/gradle.properties`, so no keys are
ever stored in the repository:

```properties
BTMAP_STORE_FILE=/path/to/btmap-release.jks
BTMAP_STORE_PASSWORD=...
BTMAP_KEY_ALIAS=btmap
BTMAP_KEY_PASSWORD=...
```

```bash
./gradlew :app:assembleRelease
```

Without these properties the release build is left unsigned.

## Project structure

- `core/`: Android library with no UI.
  - `telephony/`: reading cells from every SIM, parsing CellInfo, SignalStrength and
    PhysicalChannelConfig, resolving the carrier, and filtering vendor placeholder values.
  - `radio/`: band and frequency math for EARFCN, NR-ARFCN, UARFCN and ARFCN; operators.
  - `stations/`: the local station database (SQLite), BTSearch and BTMap importers, and
    lookup by eNB/gNB, cell or physical cell.
  - `coverage/`: recording coverage measurements on the grid.
  - `api/`: BTMap web API client.
  - `BtmapCore`: the live state, power modes, cell log and change history.
- `app/`: the application (Jetpack Compose).
  - `ui/`: Live, Map, Log and Settings screens.
  - `service/`: foreground monitoring service and notification.
  - `tools/`: Shizuku bridge, band locking and network mode.
  - `work/`: database downloads and coverage uploads (WorkManager).

## Known limitations

- **BTMap server:** this beta points at a local development server. Until the BTMap API is
  publicly deployed, coverage uploads, the coverage map and the BTMap station source do not
  work for testers. The BTSearch database, Live, Log and the tools work normally.
- **Band locking** uses MediaTek modem commands. On Qualcomm, Exynos and other chips it may
  only limit network scans, or have no effect.
- Vendor ROMs (MIUI, HyperOS, One UI) may stop background work. If monitoring stops, allow
  BTMap to run without battery restrictions.
- Secondary carrier signal depends on what the vendor exposes; some phones report only the
  primary carrier.

## Reporting bugs

Open an [issue](https://github.com/btmap-pl/btmap-mobile/issues) with your phone model,
Android version and operator. Settings → About → **Save diagnostics** creates a file you can
attach.

## License

[MIT](LICENSE).

### Third-party assets

- The Shizuku logo (`app/src/main/res/drawable-nodpi/shizuku_logo.png`) comes from
  [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) (Apache-2.0) and is used only to
  identify the Shizuku integration.
- Station data: [BTSearch](https://btsearch.pl) and [BTMap](https://github.com/btmap-pl/btmapv2).
- Maps: © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors.
