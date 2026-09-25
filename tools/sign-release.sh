#!/usr/bin/env bash
#
# Sign and stage the release APKs for upload to a GitHub release.
#
# Why this script exists
# ----------------------
# The Gradle build has no signingConfig at all: `./gradlew assembleRelease` only ever emits
# `*-unsigned.apk`. Signing has therefore always been a manual, undocumented, out-of-band step, which
# is exactly the situation where a keystore password ends up pasted into a shell command and then into
# a shell history file. This script prompts for the password with echo disabled and passes it to
# apksigner through a 0600 temporary file that is removed on exit. The password never appears in
# argv, in a file inside the repository, or in this script.
#
# It also fills a second gap: there was no written record of the release signing procedure anywhere in
# the repo, so the certificate each release was signed with could not be recovered after the fact.
#
# Usage
# -----
#   ./gradlew assembleRelease
#   ./tools/sign-release.sh [version-tag] [key-alias]
#
# version-tag defaults to the versionName in app/build.gradle. key-alias is only needed if the
# keystore holds more than one entry; apksigner picks the sole entry otherwise.
#
# Output goes to dist/<version-tag>/, named to match the published convention Mhirex-<abi>.apk.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BUILD_TOOLS="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/build-tools"
APKSIGNER="$(ls -d "$BUILD_TOOLS"/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
ZIPALIGN="$(ls -d "$BUILD_TOOLS"/*/zipalign 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$APKSIGNER" ] || { echo "error: apksigner not found under $BUILD_TOOLS" >&2; exit 1; }
[ -n "$ZIPALIGN" ]  || { echo "error: zipalign not found under $BUILD_TOOLS" >&2; exit 1; }

KS="mhirex-release.jks"
[ -f "$KS" ] || { echo "error: $KS not found in $ROOT" >&2; exit 1; }

VERSION="${1:-$(sed -n 's/.*versionName[[:space:]]*"\(.*\)".*/\1/p' app/build.gradle | head -1)}"
ALIAS="${2:-}"
[ -n "$VERSION" ] || { echo "error: could not read versionName from app/build.gradle" >&2; exit 1; }

SRC="app/build/outputs/apk/release"
[ -d "$SRC" ] || { echo "error: $SRC not found -- run ./gradlew assembleRelease first" >&2; exit 1; }
shopt -s nullglob
UNSIGNED=("$SRC"/*-release-unsigned.apk)
[ ${#UNSIGNED[@]} -gt 0 ] || { echo "error: no *-release-unsigned.apk in $SRC" >&2; exit 1; }

PASSFILE=""
cleanup() { [ -n "$PASSFILE" ] && [ -f "$PASSFILE" ] && rm -f "$PASSFILE"; }
trap cleanup EXIT

echo "version   : v$VERSION"
echo "keystore  : $KS"
echo "signer    : ${APKSIGNER##*/build-tools/}"
echo "outputs   : ${#UNSIGNED[@]} APK(s)"
echo

printf 'keystore password (not echoed): '
read -r -s KS_PASS
echo
[ -n "$KS_PASS" ] || { echo "error: empty password" >&2; exit 1; }

# mktemp creates the file 0600. apksigner's file: spec reads it rather than taking the secret as an
# argument, so it stays out of this process's argv and out of ps output.
PASSFILE="$(mktemp -t mhirex-signpass)"
chmod 600 "$PASSFILE"
printf '%s' "$KS_PASS" > "$PASSFILE"
unset KS_PASS

ALIAS_ARGS=()
[ -n "$ALIAS" ] && ALIAS_ARGS=(--ks-key-alias "$ALIAS")

OUT="dist/v$VERSION"
rm -rf "$OUT"
mkdir -p "$OUT"

for apk in "${UNSIGNED[@]}"; do
  base="$(basename "$apk")"
  abi="${base#app-}"; abi="${abi%-release-unsigned.apk}"
  name="Mhirex-${abi}.apk"

  # zipalign must run BEFORE signing. Alignment is part of what the signature covers, so aligning
  # afterwards yields an APK that installs but fails verification.
  "$ZIPALIGN" -p -f 4 "$apk" "$OUT/$name"

  "$APKSIGNER" sign \
    --ks "$KS" \
    "${ALIAS_ARGS[@]}" \
    --ks-pass "file:$PASSFILE" \
    --key-pass "file:$PASSFILE" \
    --out "$OUT/$name" \
    "$OUT/$name"

  echo "  signed  $name"
done

cleanup; PASSFILE=""

echo
echo "=== verification ==="
status=0
for apk in "$OUT"/*.apk; do
  if "$APKSIGNER" verify "$apk" >/dev/null 2>&1; then
    printf '  VERIFIED  %s\n' "$(basename "$apk")"
  else
    printf '  FAILED    %s\n' "$(basename "$apk")"
    status=1
  fi
done

echo
echo "=== signing certificate (must match earlier releases) ==="
FIRST="$OUT/$(ls "$OUT" | head -1)"
"$APKSIGNER" verify --print-certs "$FIRST" 2>/dev/null \
  | sed -n 's/^Signer #1 certificate SHA-256 digest: /  SHA-256: /p'

echo
if [ $status -eq 0 ]; then
  echo "All APKs verified. Upload from $OUT once the tag is pushed."
else
  echo "One or more APKs FAILED verification. Do not upload." >&2
fi
exit $status
