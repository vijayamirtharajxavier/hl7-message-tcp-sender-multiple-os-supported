# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/). Release notes on GitHub are generated from the commit history with
[git-cliff](https://git-cliff.org) (see `cliff.toml`); this file is the curated summary.

## [Unreleased]

## [1.1.1] - 2026-10-07

### Fixed
- The Destination dialog fits on small screens: its Connection and delivery tab scrolls, so the OK and Cancel
  buttons are no longer pushed below the bottom of a 768 px or 800 px high screen.
- The 1.1.0 release was published without its Linux `.deb`, `.rpm`, portable `.tar.gz` and Linux checksums. The
  release workflow now checks that every installer is present and uploaded before it finishes.

## [1.1.0] - 2026-10-07

### Changed
- Enhanced-mode commit codes have their own outcomes and policy, following HL7 v2 chapter 2 (section 2.9.3 in
  v2.5.1), instead of sharing them with AE and AR:
  - **CR** (`COMMIT_REJECT`): the receiver does not accept the message type (MSH-9), version (MSH-12) or
    processing ID (MSH-11). Resending the same message cannot fix that, so it now goes to the dead-letter queue by
    default. It was retried.
  - **CE** (`COMMIT_ERROR`): the receiver could not commit the message for another reason, such as a sequence
    number error, which may clear. It is now retried by default. It went to the dead-letter queue.
  - The Destination dialog has separate **On CR** and **On CE** settings. Existing destinations get the new
    defaults; a setting changed earlier for AR or AE no longer applies to CR or CE.
  - `hl7send` exit codes are unchanged: CE still exits with 3 and CR with 4.

## [1.0.5] - 2026-10-07

### Fixed
- HL7 v2 to FHIR: a PV1-2 patient class with no Encounter.class equivalent (such as U, or an empty PV1-2) now
  becomes `http://terminology.hl7.org/CodeSystem/v3-NullFlavor#UNK` instead of a guessed `AMB`, and the FHIR
  preview and `hl7send fhir convert` show a note naming PV1-2. I, O, E and P still map to IMP, AMB, EMER and
  PRENC.

## [1.0.4] - 2026-10-07

### Added
- **Edit...** and **Delete...** on each destination card on the Dashboard, so a mistyped host or port can be fixed
  (or the destination removed) without going to the Queue tab. Hidden for users whose role cannot change
  settings.

### Changed
- The main window opens maximized, filling the screen at any resolution. The maximize button switches to a normal
  window of 1280 x 860, or 90% of the screen if that is smaller.
- Built with Gradle 9.8. The Gradle wrapper allows two minutes to download Gradle, so first builds on slow
  connections no longer time out.
- Updated libraries: Jackson 2.22.3, SLF4J 2.0.20, Angus Mail 2.0.5, JUnit 6.1.3 and AssertJ 3.27.7.

### Fixed
- On Linux, the main window is maximized again after a dialog closes (opening a dialog could leave it at its
  normal size).
- Dashboard cards no longer rebuild every few seconds when nothing changed, so clicks on their buttons are not
  lost.

## [1.0.3] - 2026-10-07

### Fixed
- The app icon now shows in the Linux dock and taskbar, and in the title bar of every window, instead of a
  generic gear. The Linux menu entry is named "HL7 Sender".
- **Help > Check for updates** opens at full size on Linux desktops; in 1.0.2 it could open only about 200 px
  wide.

## [1.0.2] - 2026-10-07

### Fixed
- **Help > Check for updates** no longer cuts off its content: the window grows to fit the result, release notes
  and buttons, can be resized, and shows the release notes as plain text instead of raw Markdown.

## [1.0.1] - 2026-10-07

### Changed
- New application icon: an HL7 message card with pipe-delimited fields and a green check for the accepted ACK.
  A simpler version is used at small sizes so it stays clear in taskbars and file lists.

## [1.0.0] - 2026-10-06

The first public release. Everything below is new. HL7 Sender is released under the MIT License.

### Sending and acknowledgments
- HL7 v2 over MLLP with connect and total ACK timeouts, configurable character sets, and optional generation of
  MSH-7 and MSH-10.
- Original and enhanced acknowledgment codes, ACK matching by MSA-2 = MSH-10, ERR segment decoding (v2.5+ and
  older layouts), and colour-coded outcomes.
- A no-ACK mode for receivers that never acknowledge.

### Durable queue
- SQLite store-and-forward queue with strict FIFO per destination, exponential backoff with jitter, a circuit
  breaker, configurable ACK policy, a dead-letter queue, crash recovery and a full audit trail.

### Bulk, templates and validation
- Import of files, folders, FHS/BHS batch files and MLLP captures; templates with per-message variables; folder
  watch per destination; per-destination rate limits.
- Lenient, Standard and Strict validation with HAPI and optional conformance profiles.

### Security and multiple destinations
- MLLP over TLS and mutual TLS, passwords in the OS keychain, optional queue encryption (AES-256), presets for
  Mirth Connect, Apache NiFi and EHRs, JSON destination profiles, and fan-out.

### Monitoring and desktop experience
- Dashboard, alerts (desktop, webhook, e-mail), live log viewer and diagnostics bundle.
- Syntax-highlighted editor with field names, History search and export, Test Listener with failure modes.
- Light and dark themes, keyboard shortcuts, screen-reader labels, English and Spanish, and a welcome wizard.

### Headless and automation
- `hl7send` with `send`, `validate`, `listen`, `queue`, `dlq`, `destination`, `serve`, `service`, `api` and
  `update` commands, `--json` output and CI-friendly exit codes.
- `hl7send serve` as a systemd, launchd or Windows (WinSW) service, and a token-protected local REST API.

### Packaging and release
- Installers with a bundled Java runtime: MSI (Windows), DMG and PKG (macOS), DEB, RPM, AppImage and a portable
  archive (Linux). Each installs both the app and `hl7send`.
- Release workflow that builds, optionally signs and notarizes, installs and smoke-tests every installer on
  clean runners, and publishes a GitHub Release with checksums and generated notes.
- Opt-in update check against GitHub Releases (**Help > Check for updates...**, `hl7send update`).
- User, administrator and troubleshooting guides.

### Testing and simulation
- Load testing: the **Load Test** tab and `hl7send load` (connections, rate, count or duration, latency
  percentiles, thresholds with exit code 10, JSON report).
- Responder rules for two-way simulation: per-type and per-field responses, custom responses, follow-up messages,
  and saving received messages (`hl7send listen --rules --save-dir`).
- Scheduled sends with cron expressions (**Tools > Scheduled sends**, `hl7send schedule`), and replay from History
  (`hl7send replay`, `POST /messages/{id}/replay`).

### Transforms, transports and FHIR
- JavaScript transform scripts per destination, sandboxed, with `hl7send script test`.
- HTTP(S), folder and FHIR R4 transports for destinations, and a plugin API for more (`examples/transport-plugin`,
  `hl7send destination transports`).
- HL7 v2 to FHIR R4 conversion: a live preview on the Sender tab and `hl7send fhir convert|send`.

### Teams
- A shared PostgreSQL queue database (**Tools > Queue database**, `hl7send database`), with one delivering
  process at a time and automatic take-over.
- Users and roles (viewer, operator, admin) with sign-in for the app and the CLI (`--user`, `hl7send user`,
  exit code 11), and user names in the audit trail.

[Unreleased]: https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported/compare/v1.1.1...main
[1.1.1]: https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported/compare/v1.0.5...v1.1.0
[1.0.5]: https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported/compare/v1.0.4...v1.0.5
[1.0.4]: https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported/compare/v1.0.3...v1.0.4
[1.0.3]: https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported/releases/tag/v1.0.0
