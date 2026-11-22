# Changelog

All notable changes to BTMap Mobile are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed
- **Wrong bands on Samsung** with modem access (root or Shizuku): the modem reports channel 0
  for carriers it does not fill in, which was read as band 1 (2100). Channel 0 now counts as
  unknown and the band comes from the measured cell.
- **Holes in recorded coverage** along a drive: a short gap (up to ~800 m and 90 s) on the same
  band, after a moment of poor GPS or a stale reading, is filled with readings interpolated along
  the way.

### Added
- **Routes with signal** on the Map tab: the *Route* pill shows the latest trip as a line coloured
  by the data SIM's signal (green strong → red weak, grey without service), with its length and
  start time; ‹ › go to earlier trips. Routes are recorded on the phone only; a pause over
  10 minutes starts a new trip.

## [0.1.0-beta.5] - 2026-11-22

### Added
- **Root support** as an alternative to Shizuku: Settings → Tools → Root → *Use root*. On a
  rooted phone (Magisk, KernelSU, APatch) BTMap grants itself modem access (DUMP) and can change
  the network mode without Shizuku. Locking individual bands still needs Shizuku.

## [0.1.0-beta.4] - 2026-11-22

### Changed
- The Live screen starts straight with the SIM cards: no title bar and no GPS coordinates line.

## [0.1.0-beta.3] - 2026-11-22

### Added
- **Import your own NetMonster database (.ntm)** in Settings → Database → Own database. It works
  in any country, and its stations take priority over the downloaded database. Rows without
  coordinates are skipped.
- **Bands worldwide.** Full 3GPP band tables for LTE (B1–B88), 5G NR (FR1 and FR2), UMTS and
  GSM 850, so bands are right abroad and in roaming, not only in Poland. Bands outside the
  UKE labels get their common name, e.g. "700 B12" or "600 n71".
- **Uplink frequency** in the cell details (hidden for TDD, where it equals the downlink).

### Fixed
- 5G NSA carriers on MediaTek phones were all shown as n1 (the modem reports band 1 for
  every NR carrier). The band now comes from the NR-ARFCN; the modem's band is used only
  when it matches the frequency. 2600 TDD is shown as n41.

## [0.1.0-beta.2] - 2026-11-22

### Added
- **English language.** The whole interface, the notification and the cell history are
  available in English. Settings → Appearance → Language: Auto (follows the phone; English
  on any phone not set to Polish), Polski or English. The switch applies immediately.

## [0.1.0-beta.1] - 2026-11-22

The first public beta.

### Added
- **Core:** own cell monitoring core for every SIM, covering LTE, 5G NR (NSA and SA), UMTS
  and GSM. It reports band, frequency and bandwidth from (E/NR/U)ARFCN, eNB/gNB:CID, and
  distance from Timing Advance.
- **Carrier aggregation from the modem:** read from PhysicalChannelConfig, with per-carrier
  band, bandwidth, RSRP, RSRQ and SNR. You can switch between the carriers on the Live screen.
- **Station names** for the serving cell and neighbours on 2G, 3G, 4G and 5G.
- **Live screen** with light and dark themes, animations and an optional neighbour list.
- **Map tab:**
  - a station map that highlights the station you are connected to;
  - a crowdsourced coverage map per operator and band.
- **Coverage sharing:** opt-in, asked on first start. Uploads over Wi-Fi or mobile data.
- **Log:**
  - a persistent list of connected cells, with a details screen for each;
  - a history of changes (cell, PCI, band, aggregation, loss of service).
- **Background monitoring:** a foreground service whose notification shows the bands and
  the station. Power modes switch automatically between live, background and idle.
- **Settings** grouped into categories: appearance, Live, notification, database, tools,
  band locking, coverage and about.
- **Station database:**
  - BTSearch or BTMap (without UKE) as the source;
  - per-operator download through the BTSearch API v2 that respects the API's rate limits;
  - scheduled refresh, with a Wi-Fi-only option;
  - operators that are already up to date are skipped.
- **Shizuku integration:** unlocks the modem tools and grants `DUMP` automatically.
- **Band locking (MediaTek):**
  - LTE and NR band masks, using the bands the modem reports as supported;
  - saved named configurations;
  - a reset to the default bands;
  - a check that the modem really applied the lock.
- **Network mode:** 5G/4G/3G/2G combinations, including a real 5G SA mode on MediaTek that
  falls back automatically if the phone loses service.
- **Diagnostics export** and an on-device crash report (Settings → About).

### Fixed
- Vendor placeholder values are filtered out instead of being shown as real measurements:
  - MIUI: TA 1282, −44 dBm, 1.4 MHz bandwidth, NCI 268435455;
  - an unknown CI.
- Crash on Android 13 (Samsung Galaxy A22 5G) when the SIM list changes.

### Known limitations
- The BTMap API points at a development server. Coverage upload, the coverage map and the
  BTMap station source are not available to testers until the API is publicly deployed.
- Band locking targets MediaTek modems.

[0.1.0-beta.5]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.5
[0.1.0-beta.4]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.4
[0.1.0-beta.3]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.3
[0.1.0-beta.2]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.2
[0.1.0-beta.1]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.1
