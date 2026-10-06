#!/usr/bin/env bash
# Builds an AppImage from the jpackage application folder (./gradlew :app:jpackageImage).
# Usage: app/src/packaging/linux/build-appimage.sh VERSION OUTPUT_DIR
# Needs appimagetool on the PATH, or APPIMAGETOOL pointing at it (it is run with --appimage-extract-and-run, so
# FUSE is not required).
set -euo pipefail

VERSION=$1
OUT=$2
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../../.." && pwd)
IMAGE="$ROOT/app/build/jpackage/image/hl7-sender"
TOOL=${APPIMAGETOOL:-appimagetool}
APPDIR=$(mktemp -d)/HL7_Sender.AppDir

[ -x "$IMAGE/bin/hl7-sender" ] || { echo "Build the app image first: ./gradlew :app:jpackageImage" >&2; exit 1; }
mkdir -p "$APPDIR/usr" "$OUT"
cp -a "$IMAGE" "$APPDIR/usr/hl7-sender"
cp "$HERE/../hl7-sender.png" "$APPDIR/hl7-sender.png"
cat > "$APPDIR/hl7-sender.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=HL7 Sender
Comment=Send HL7 v2 messages over MLLP/TCP and track acknowledgments
Exec=hl7-sender
Icon=hl7-sender
Categories=Development;
Terminal=false
DESKTOP
# AppRun: the desktop app by default; "AppRun hl7send ..." (or a symlink named hl7send) runs the CLI.
cat > "$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
HERE=$(dirname "$(readlink -f "$0")")
case "$(basename "$ARGV0" 2>/dev/null || basename "$0")" in
    hl7send*) exec "$HERE/usr/hl7-sender/bin/hl7send" "$@" ;;
esac
if [ "${1:-}" = hl7send ]; then
    shift
    exec "$HERE/usr/hl7-sender/bin/hl7send" "$@"
fi
exec "$HERE/usr/hl7-sender/bin/hl7-sender" "$@"
APPRUN
chmod +x "$APPDIR/AppRun"
ARCH=x86_64 "$TOOL" --appimage-extract-and-run --no-appstream "$APPDIR" "$OUT/HL7_Sender-$VERSION-x86_64.AppImage"
echo "Wrote $OUT/HL7_Sender-$VERSION-x86_64.AppImage"
