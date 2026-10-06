# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/). Release notes on GitHub are generated from the commit history with
[git-cliff](https://git-cliff.org) (see `cliff.toml`); this file is the curated summary.

## [Unreleased]

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

[Unreleased]: https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported/compare/v1.0.0...main
[1.0.0]: https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported/releases/tag/v1.0.0
