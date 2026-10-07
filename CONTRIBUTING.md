# Contributing

## Prerequisites

- JDK 21 (Temurin recommended). Everything else is fetched by the Gradle wrapper.

## Build and test

```bash
./gradlew build            # compile, all tests (incl. headless UI tests), Checkstyle, SpotBugs
./gradlew :core:slowTest   # long-running load tests (tagged @Tag("slow")), e.g. 10,000 messages
./gradlew :app:run         # start the desktop app
./gradlew :cli:run --args="send -H localhost -p 2575 path/to/message.hl7"
```

To save screenshots of the UI during tests:

```bash
./gradlew :app:test -Dhl7sender.screenshotDir=/tmp/shots
```

## Project layout

| Path | What |
|---|---|
| `core/` | HL7 model, validation, MLLP transport, sender, test listener, settings. No UI. |
| `app/` | JavaFX desktop app |
| `cli/` | `hl7send` command-line tool |
| `app/src/packaging/` | Installer icons, Linux package scripts and the AppImage builder |
| `scripts/` | `package.sh` and `package.ps1`: build the installers for the system they run on |
| `docs/` | User, administrator and troubleshooting guides, and README screenshots |
| `config/` | Checkstyle and SpotBugs configuration |

## Conventions

- Java 21, 4-space indent, 120-column lines. Checkstyle enforces the basics and the build fails on
  any violation or compiler warning.
- **Never log message content.** HL7 messages contain PHI. Log metadata only: type, control ID,
  outcome, timings.
- New behaviour comes with tests. Network behaviour is tested against the built-in `TestListener`,
  not mocks.
- Explain significant design decisions in the pull request description.

## Commits and pull requests

- Small, focused commits with descriptive messages.
- Pull requests must pass CI on Windows, macOS and Linux.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org): `feat:`, `fix:`,
  `perf:`, `docs:`, `test:`, `build:`, `ci:`, `refactor:`, `chore:`, with an optional scope (`fix(queue): ...`).
  Release notes are generated from them, so write the subject for users. `feat!:` or a `BREAKING CHANGE:`
  footer marks an incompatible change.
- The project is [MIT-licensed](LICENSE); by contributing you agree your contribution is released under the same
  license.

## Packaging

### Building installers

Installers can only be built on the system they are for: build the Windows MSI on Windows, the DMG on macOS and
the DEB on Linux. To get all of them from one place, run the **Release** workflow (below) instead.

What you need on every system:

1. JDK 21 with `jlink` and `jpackage`, such as [Temurin 21](https://adoptium.net/temurin/releases/?version=21).
   Check with `java -version`. Gradle itself is fetched by the wrapper.
2. A clone of this repository.

Then run the script for your system from the repository root:

| System | Also install | Command | You get |
|---|---|---|---|
| Ubuntu / Debian | `sudo apt install fakeroot rpm` | `scripts/package.sh` | `.deb`, `.rpm`, portable `.tar.gz`; `.AppImage` if `appimagetool` is on the PATH |
| macOS | Xcode command line tools: `xcode-select --install` | `scripts/package.sh` | `.dmg`, `.pkg` |
| Windows | WiX Toolset 3: `choco install wixtoolset --version 3.14.1` | `powershell -ExecutionPolicy Bypass -File scripts\package.ps1` | `.msi`, portable `.zip` |

A `.deb` depends on the library versions of the Ubuntu it was built on, so build on the oldest release you
support (the release workflow uses 22.04); one built on 24.04 will not install on 22.04.

The installers are written to `app/build/jpackage/installer`. Each one contains the desktop app (**HL7 Sender**),
the `hl7send` command line tool and a trimmed Java runtime, so users do not need Java. Add `--image` (`-Image` on
Windows) to build only the ready-to-run application folder in `app/build/jpackage/image`, which you can zip and
copy anywhere.

Builds made this way are unsigned. On first launch Windows SmartScreen asks users to choose **More info > Run
anyway**, and macOS asks them to right-click the app and choose **Open**. To sign on macOS, set
`MAC_SIGNING_IDENTITY` (a Developer ID certificate in your keychain) before running the script; signed and
notarized builds for every system come from the release workflow once its secrets are set.

To build every installer at once on GitHub without publishing anything, open **Actions > Release > Run workflow**
and download the installers from the run's **Artifacts**.

### Gradle tasks

The scripts wrap these tasks:

```bash
./gradlew :app:jpackageImage                              # app folder with runtime → app/build/jpackage/image
./gradlew :app:jpackageInstaller                          # msi / dmg / deb for this OS → app/build/jpackage/installer
./gradlew :app:jpackageInstaller -PinstallerType=rpm      # or pkg, exe
app/src/packaging/linux/build-appimage.sh 1.0.0 out/      # AppImage (needs appimagetool; set APPIMAGETOOL)
```

The icons are generated; after changing `IconGen.java` run:

```bash
java -Djava.awt.headless=true app/src/packaging/IconGen.java app/src/packaging \
    app/src/main/resources/io/hl7sender/app/icon
```

To build and smoke-test every installer without publishing anything, run the release workflow by hand
(**Actions > Release > Run workflow**).

## Releasing

Versions follow [Semantic Versioning](https://semver.org). The version lives in `gradle.properties`.

1. Update `CHANGELOG.md`: move *Unreleased* items under the new version and date.
2. Set `version=X.Y.Z` in `gradle.properties` (no `-SNAPSHOT`) and commit: `chore(release): X.Y.Z`.
3. Tag and push: `git tag vX.Y.Z && git push origin vX.Y.Z`. The release workflow checks the tag matches the
   version, builds and tests the installers on all three systems, and publishes a GitHub Release with notes
   generated by git-cliff and `SHA256SUMS-*.txt`.
4. Set the next development version, for example `X.Y.(Z+1)-SNAPSHOT`, and commit.

Signing is used when these repository secrets are set; otherwise the installers are unsigned and the release
notes say so.

| Secret | Used for |
|---|---|
| `WINDOWS_CERTIFICATE_PFX` (base64), `WINDOWS_CERTIFICATE_PASSWORD` | Authenticode signing of the `.exe` launchers and the MSI |
| `MAC_CERTIFICATE_P12` (base64), `MAC_CERTIFICATE_PASSWORD`, `MAC_SIGNING_IDENTITY` | Developer ID signing of the app, DMG and PKG (identity such as `Example Corp (TEAMID)`) |
| `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD` | Notarization with `notarytool` and stapling |
