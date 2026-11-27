# Changelog

All notable changes to BTMap Mobile are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [0.1.0-beta.16] - 2026-11-27

### Added
- **5G NR on the live modem screen and the Live card** (root, Qualcomm): Mb/s, MCS, BLER, MIMO rank,
  RBs, retransmissions and the modulation mix for the 5G carrier, next to LTE; in 5G NSA both are
  shown with the total. Core decodes the NR5G MAC PDSCH status log (0xB887, format 3.13), worked
  out from a Galaxy S25 recording on Digi RO; the transport block sizes match the 3GPP TS 38.214
  calculation.

### Changed
- The MIMO figure is labelled *MIMO rank* everywhere (it was "Layers (rank)" on the live modem
  screen and "Rank"/"MIMO" on the card), and the Live card no longer squeezes its figures on narrow
  screens.

## [0.1.0-beta.15] - 2026-11-24

### Added
- **Export** in Settings → Data now writes the cell log as a NetMonster (.ntm)
  cell file, which BTSearch's log analyzer and NetMonster read. BTSearch rejected the CSV export
  because it reads every .csv as a Netmonitor file.
- **Modem card on the Live screen** (root, Qualcomm): Mb/s, MCS, BLER, MIMO rank, RBs and the
  modulation mix below the SIM cards, refreshed twice a second. It can be switched off in
  Settings → Live modem → *Show on the Live screen*.

### Fixed
- The live modem screen called the MIMO figure "2×2"; it now shows the MIMO rank (spatial
  layers): average, highest and the share of rank 1–4, so 4×4 MIMO shows up correctly.

## [0.1.0-beta.14] - 2026-11-22

### Fixed
- **Live modem screen showed no data** on the Galaxy S25: the modem answers seconds late and drops
  a request sent while an earlier one is pending, so the log mask was lost. Every command now waits
  for its reply, the mask is repeated until the modem confirms it and re-sent when data stops, and
  the screen shows the connection stage and what arrived.

## [0.1.0-beta.13] - 2026-11-22

### Added
- **Live modem screen** (root, Qualcomm): Settings → *Live modem* shows LTE downlink figures
  straight from the modem's own logs, refreshed twice a second: throughput (Mb/s), MCS (average
  and last), BLER, resource blocks, MIMO 2×2 share, retransmissions and the modulation mix
  (QPSK / 16QAM / 64QAM / 256QAM). The session runs only while the screen is open.
- Core decodes the modem's multi-SIM wrapper and the LTE PDSCH statistics log (0xB173, format 50),
  checked against a recording from a Galaxy S25 and the 3GPP TBS table.

### Fixed
- Modem log recording counted no packets on dual-SIM phones: logs inside the multi-SIM wrapper are
  now recognised.

## [0.1.0-beta.12] - 2026-11-22

### Changed
- **Modem log recording tries harder.** The first recording got no reply to the log mask, so it
  now tries both forms of the SET_MASK request, reads the mask back, switches on event reports
  as a check that data flows, and logs every command with the modem's reply. The second half of
  the 30 s records through Qualcomm's own `diag_mdlog` with a mask file, as a second way; its
  .qmdl files are shared too.

## [0.1.0-beta.11] - 2026-11-22

### Added
- **Record modem logs** (root, Qualcomm): Settings → Tools → *Record modem logs (30 s)* switches on
  the modem's LTE and 5G radio logs (PDSCH MCS/modulation/TBS, CQI/RI, measurements, RRC) through
  diag_socket_log, records them to a .qmdl file in Downloads with a summary, switches logging off
  and opens the share sheet.

## [0.1.0-beta.10] - 2026-11-22

### Added
- **DIAG through diag_socket_log** in modem diagnostics (root): BTMap listens on 127.0.0.1, starts
  Qualcomm's `diag_socket_log` pointed at it, asks the modem for its version and build ID and
  prints the replies, then stops the tool. Read-only.

## [0.1.0-beta.9] - 2026-11-22

### Changed
- **Modem diagnostics look at diag-router.** On newer Snapdragons the modem's DIAG channel is
  owned by the vendor daemon diag-router, so the DIAG ping (which could not get a reply) is
  replaced by a read-only look at diag-router: its client tools and their options, the sockets it
  serves, what it has open, and Samsung's modem log folders (*#9900# Silent Log).
- Leaner report: fewer unrelated device nodes and system properties.

## [0.1.0-beta.8] - 2026-11-22

### Added
- **DIAG ping in modem diagnostics** (root): newer Snapdragons have no /dev/diag; the modem's
  diagnostic channel is an MHI pipe. The check finds it, shows which process holds it and sends
  the harmless version request in both DIAG framings, printing any reply. Nothing in the modem is
  changed.

### Fixed
- Diagnostics run through root came out empty for large outputs (system properties, telephony
  registry): the output is now read while the command runs instead of after it.

## [0.1.0-beta.7] - 2026-11-22

### Added
- **Modem diagnostics**: Settings → Tools → *Check and send*. Checks every way to reach the modem
  (root, SELinux, the Qualcomm diagnostic interface /dev/diag and whether it opens, related device
  nodes and drivers, USB mode properties, vendor service apps, what the telephony registry
  reports), saves the report to Downloads and opens the share sheet. Settings → About → *Save
  diagnostics* includes the same check. No IMEI, SIM or phone numbers are included.

## [0.1.0-beta.6] - 2026-11-22

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

[0.1.0-beta.16]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.16
[0.1.0-beta.15]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.15
[0.1.0-beta.14]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.14
[0.1.0-beta.13]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.13
[0.1.0-beta.12]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.12
[0.1.0-beta.11]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.11
[0.1.0-beta.10]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.10
[0.1.0-beta.9]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.9
[0.1.0-beta.8]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.8
[0.1.0-beta.7]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.7
[0.1.0-beta.6]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.6
[0.1.0-beta.5]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.5
[0.1.0-beta.4]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.4
[0.1.0-beta.3]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.3
[0.1.0-beta.2]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.2
[0.1.0-beta.1]: https://github.com/btmap-pl/btmap-mobile/releases/tag/v0.1.0-beta.1
