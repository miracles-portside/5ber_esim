#!/usr/bin/env bash
# Builds the signed release APK and drops a copy in the phone's Downloads folder.
# Usage: ./build-release.sh
set -e
cd "$(dirname "$0")"

./gradlew assembleRelease

VERSION=$(sed -n 's/.*"versionName": "\(.*\)".*/\1/p' app/build/outputs/apk/release/output-metadata.json)
DEST="$HOME/storage/downloads/5ber-esim-${VERSION}-release.apk"
cp app/build/outputs/apk/release/app-release.apk "$DEST"

echo
echo "Release ${VERSION} copied to:"
echo "  $DEST  (=> /sdcard/Download/5ber-esim-${VERSION}-release.apk)"
