#!/usr/bin/env bash
#
# Check the signing certificate against the permanent record in docs/signing-fingerprints.md.
#
# Why this script exists
# ----------------------
# docs/signing-fingerprints.md records the SHA-1 and SHA-256 of the release and debug certificates so
# the values never have to be re-derived by hand. But a record that nothing checks is only a comment:
# if mhirex-release.jks were ever regenerated, the doc would quietly become wrong, every build signed
# with the new key would be rejected by Google's console and would refuse to install over an existing
# Mhirex, and the first person to find out would be a user.
#
# So the doc is the single source of truth and this script reads it. It is deliberately not given its
# own copy of the fingerprints: two places to update is the problem, not the solution. Editing the
# doc is the only way to change an expected value, and doing so is a visible, reviewable diff.
#
# Usage
# -----
#   ./tools/check-signing-fingerprint.sh                    # check mhirex-release.jks
#   ./tools/check-signing-fingerprint.sh --print            # print the values, touches nothing
#   ./tools/check-signing-fingerprint.sh --debug            # check ~/.android/debug.keystore
#   ./tools/check-signing-fingerprint.sh --apk dist/v1.0-beta9/Mhirex-arm64-v8a.apk
#
# Exit status is 0 when the certificate matches the record and 1 when it does not, so this can gate
# a release: tools/sign-release.sh calls it and refuses to stage a mismatched APK.
#
# The keystore password is prompted for with echo disabled and handed to keytool through an
# environment variable. It is never an argument, never in a file, and never in this script.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DOC="docs/signing-fingerprints.md"
[ -f "$DOC" ] || { echo "error: $DOC not found" >&2; exit 1; }

BUILD_TOOLS="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/build-tools"
APKSIGNER="$(ls -d "$BUILD_TOOLS"/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"

# Digests are compared in one canonical shape -- no colons, lowercase -- because the two producers
# disagree on both: apksigner prints lowercase without separators, keytool prints uppercase with
# them. Anything reporting them "differently" is a formatting artefact, not a different key.
normalise() { tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]'; }

# Pull a `| **Field** | \`value\` |` row out of one ## section of the doc, so the release and debug
# records, which use identical field names, cannot be read out of each other.
field_from_section() {
  local section="$1" field="$2"
  awk -v want="$section" -v field="$field" '
    /^## /                { inside = (index($0, "## " want) == 1) }
    inside && index($0, field) {
      if (match($0, /`[^`]+`/)) { print substr($0, RSTART + 1, RLENGTH - 2); exit }
    }
  ' "$DOC"
}

require_field() {
  local v; v="$(field_from_section "$1" "$2")"
  if [ -z "$v" ]; then
    echo "error: could not read '$2' from the '$1' section of $DOC" >&2
    echo "       the record and this script have drifted apart; fix the doc" >&2
    exit 1
  fi
  printf '%s' "$v"
}

pretty() {  # 43CDDA4434CF... -> 43:CD:DA:44:34:...
  # Written with a trailing-colour strip rather than a lookahead: macOS ships BSD sed, which rejects
  # (?=...), so the obvious spelling of this is an error on the machine most likely to run it.
  echo "$1" | sed -E 's/(..)/\1:/g' | sed -E 's/:$//' | tr '[:lower:]' '[:upper:]'
}

cmd_print() {
  echo "Permanent signing certificate record -- $DOC"
  echo
  local s
  for s in "Release certificate" "Debug certificate"; do
    echo "  $s"
    printf '    subject  : %s\n' "$(require_field "$s" 'Subject' | tr -d '`' )"
    printf '    SHA-1    : %s\n' "$(pretty "$(require_field "$s" 'SHA-1' | normalise)")"
    printf '    SHA-256  : %s\n' "$(pretty "$(require_field "$s" 'SHA-256' | normalise)")"
    printf '    keystore : %s\n' "$(require_field "$s" 'Keystore |')"
    echo
  done
  echo "  Register the release SHA-1 in the Google Cloud console on BOTH the Android OAuth client"
  echo "  and the Web OAuth client for project mhirex-5888 / package com.mhirex.editor."
  echo "  Google matches package + certificate SHA-1; it does not match on app version."
  echo
  echo "  These are not secrets -- they are embedded in every public APK. The keystore password is"
  echo "  never written to this repository, to a file, or to a command line."
}

# Confirm the cert on an already-signed APK. No password, and this is the check that matters most:
# it reports the identity Google will see in the wild.
check_apk() {
  local apk="$1"
  [ -f "$apk" ] || { echo "error: $apk not found" >&2; exit 1; }
  [ -n "$APKSIGNER" ] || { echo "error: apksigner not found under $BUILD_TOOLS" >&2; exit 1; }

  local got_sha1 got_sha256
  got_sha1="$("$APKSIGNER" verify --print-certs "$apk" 2>/dev/null \
    | sed -n 's/^Signer #1 certificate SHA-1 digest: //p' | head -1)"
  got_sha256="$("$APKSIGNER" verify --print-certs "$apk" 2>/dev/null \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"
  [ -n "$got_sha256" ] || { echo "error: could not read a signature from $apk" >&2; exit 1; }

  local want_sha1 want_sha256 status=0
  want_sha1="$(require_field "Release certificate" 'SHA-1' | normalise)"
  want_sha256="$(require_field "Release certificate" 'SHA-256' | normalise)"

  echo "  APK     : $apk"
  if ! compare "SHA-256" "$(printf '%s' "$got_sha256" | normalise)" "$want_sha256"; then status=1; fi
  if ! compare "SHA-1"   "$(printf '%s' "$got_sha1"   | normalise)" "$want_sha1";   then status=1; fi
  return $status
}

# check_keystore reads the certificate out of the keystore, which is the pre-flight version of the
# same question: is the key on disk the one we registered?
check_keystore() {
  local ks="$1" section="$2" label="$3"
  [ -f "$ks" ] || { echo "error: $ks not found" >&2; exit 1; }
  command -v keytool >/dev/null || { echo "error: keytool not on PATH" >&2; exit 1; }

  local storepass="${KS_PASS:-}"
  if [ -z "$storepass" ]; then
    if [ "$ks" = "$HOME/.android/debug.keystore" ]; then
      storepass="android"   # the AOSP default, public, and not this project's secret
    else
      printf 'keystore password for %s (not echoed): ' "$ks"
      read -r -s storepass
      echo
      [ -n "$storepass" ] || { echo "error: empty password" >&2; exit 1; }
    fi
  fi

  # -storepass:env keeps the secret out of argv, so it never shows up in ps or in a shell history.
  local dump
  dump="$(KS_PASS="$storepass" keytool -list -v \
    -keystore "$ks" -storepass:env KS_PASS -alias "${4:-}" 2>/dev/null || true)"
  unset storepass
  [ -n "$dump" ] || { echo "error: keytool could not read $ks (wrong password, or no such alias?)" >&2; exit 1; }

  local got_sha1 got_sha256
  got_sha1="$(printf '%s' "$dump"   | sed -n 's/^[[:space:]]*SHA1:[[:space:]]*//p'   | head -1 | normalise)"
  got_sha256="$(printf '%s' "$dump" | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -1 | normalise)"
  [ -n "$got_sha256" ] || { echo "error: keytool reported no SHA-256 for $ks" >&2; exit 1; }

  local want_sha1 want_sha256 status=0
  want_sha1="$(require_field "$section" 'SHA-1' | normalise)"
  want_sha256="$(require_field "$section" 'SHA-256' | normalise)"

  echo "  $label"
  if ! compare "SHA-256" "$got_sha256" "$want_sha256"; then status=1; fi
  if ! compare "SHA-1"   "$got_sha1"   "$want_sha1";   then status=1; fi

  # A whole-keystore hash as well as the certificate's. A different pair almost always means a
  # different key, but a same-certificate-different-file is worth knowing about too, since it can
  # mean the keystore was rebuilt around the same certificate.
  local want_file
  want_file="$(field_from_section "$section" 'Keystore file SHA-256' | normalise)"
  if [ -n "$want_file" ] && [ -f "$ks" ]; then
    if ! compare "file SHA-256" "$(shasum -a 256 "$ks" | awk '{print $1}' | normalise)" "$want_file"; then
      status=1
    fi
  fi
  return $status
}

compare() {  # compare <label> <got> <want>; returns 1 (the caller's failure) on mismatch
  local label="$1" got="$2" want="$3"
  if [ "$got" = "$want" ]; then
    printf '    %-13s OK    %s\n' "$label" "$(pretty "$got")"
    return 0
  fi
  printf '    %-13s MISMATCH\n' "$label"
  printf '      recorded: %s\n' "$(pretty "$want")"
  printf '      actual  : %s\n' "$(pretty "$got")"
  printf '      The key on disk is not the key in %s.\n' "$DOC"
  printf '      A different certificate cannot sign in (Google matches package + SHA-1) and cannot\n'
  printf '      install over an existing Mhirex. Do not ship this. Either restore the original\n'
  printf '      keystore, or accept a new identity and update the doc in the same review.\n'
  return 1
}

case "${1:---keystore-release}" in
  --print)                 cmd_print ;;
  --apk)                   [ -n "${2:-}" ] || { echo "error: --apk needs a path" >&2; exit 1; }
                           check_apk "$2" ;;
  --debug)                 check_keystore "$HOME/.android/debug.keystore" "Debug certificate" "debug keystore" androiddebugkey ;;
  --keystore-release|--keystore-release-*) check_keystore "mhirex-release.jks" "Release certificate" "release keystore" ;;
  --keystore)              [ -n "${2:-}" ] || { echo "error: --keystore needs a path" >&2; exit 1; }
                           check_keystore "$2" "Release certificate" "release keystore" ;;
  -h|--help)               sed -n 's/^# \{0,1\}//p' "$0" | sed -n '1,30p' ;;
  *) echo "error: unknown option '$1' (try --help)" >&2; exit 1 ;;
esac
