#!/usr/bin/env bash
# Builds the HL7 Sender installers for the system this runs on (Linux or macOS; on Windows use package.ps1).
# Installers can only be built on their own system: run this once on each.
#
#   scripts/package.sh            installers for this system
#   scripts/package.sh --image    only the ready-to-run application folder (no installer)
#
# Output: app/build/jpackage/installer (installers) and app/build/jpackage/image (application folder).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
OUT="$ROOT/app/build/jpackage/installer"
VERSION=$(grep '^version=' gradle.properties | cut -d= -f2)

say() { printf '\n==> %s\n' "$*"; }
fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# JDK 21 is required: Gradle runs on it, and jlink/jpackage come from it.
have java || fail "Java not found. Install JDK 21 (for example Temurin 21) and try again."
java_major=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
[ "$java_major" = "21" ] || echo "Warning: 'java' is version ${java_major:-unknown}; JDK 21 is expected." >&2

case "$(uname -s)" in
    Linux) os=linux ;;
    Darwin) os=mac ;;
    *) fail "Unsupported system $(uname -s). On Windows run: powershell -File scripts\\package.ps1" ;;
esac

say "Building the application folder (version $VERSION)"
./gradlew :app:jpackageImage
if [ "${1:-}" = "--image" ]; then
    say "Done: app/build/jpackage/image"
    exit 0
fi
rm -rf "$OUT"
mkdir -p "$OUT"

# -x jpackageImage: reuse the application folder built above for every installer type.
installer() {
    say "Building the .$1 installer"
    ./gradlew :app:jpackageInstaller -x :app:jpackageImage -PinstallerType="$1"
}

if [ "$os" = linux ]; then
    if have fakeroot && have dpkg-deb; then
        installer deb
    else
        echo "Skipping .deb: install it with 'sudo apt install fakeroot'." >&2
    fi
    if have rpmbuild; then
        installer rpm
    else
        echo "Skipping .rpm: install it with 'sudo apt install rpm' (or 'sudo dnf install rpm-build')." >&2
    fi
    say "Building the portable .tar.gz"
    tar -C app/build/jpackage/image -czf "$OUT/hl7-sender-$VERSION-linux-x64.tar.gz" hl7-sender
    if have appimagetool || [ -n "${APPIMAGETOOL:-}" ]; then
        say "Building the AppImage"
        app/src/packaging/linux/build-appimage.sh "$VERSION" "$OUT"
    else
        echo "Skipping AppImage: put appimagetool on the PATH or set APPIMAGETOOL to build one." >&2
    fi
else
    [ -n "${MAC_SIGNING_IDENTITY:-}" ] || echo "Note: MAC_SIGNING_IDENTITY is not set, so the app is unsigned." >&2
    installer dmg
    installer pkg
fi

say "Done. Installers in app/build/jpackage/installer:"
ls -lh "$OUT"
