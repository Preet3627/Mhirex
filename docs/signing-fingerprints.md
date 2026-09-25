# Signing certificate fingerprints

The permanent record of the certificates Mhirex is signed with, and the values that must be
registered with Google so that "Sign in with Google" works.

**These fingerprints are not secrets.** Every one of them is embedded in the public APK, so they are
recorded here in full and in the clear on purpose. The keystore *password* is never written to this
repository, to a file, or to a command line -- `tools/sign-release.sh` and
`tools/check-signing-fingerprint.sh` both prompt for it with echo disabled.

If you only want the values to paste into a console, run:

```
./tools/check-signing-fingerprint.sh --print
```

## Why this file exists

Google matches an app by **package name plus signing-certificate SHA-1**. It does not match on app
version, so shipping `1.0-beta10` changes nothing. Two consequences make this file worth having:

1. **A release signed with a new key cannot sign in, and cannot upgrade.** An unregistered SHA-1 is
   rejected with `UNREGISTERED_ON_API_CONSOLE` before any chooser appears, and a different key makes
   Android refuse to install over an existing Mhirex at all. Both failures are silent or cryptic.
2. **`app/google-services.json` cannot be used to check this.** It is gitignored
   (`.gitignore:49`, `**/google-services*.json`), it is hand-authored rather than exported from
   Firebase, it has no `certificate_hash` key at all, and it lists only a Web OAuth client
   (`client_type: 3`) with no Android client (`client_type: 1`). Nothing in it reflects what the
   Google Cloud console actually holds.

So the console is the source of truth, this file is the thing you copy from, and
`tools/check-signing-fingerprint.sh` is what tells you whether the key on disk still matches.

## Release certificate

Applies to every published release from `v1.0-beta8` onward. Verified on `v1.0-beta9`: same key, so
those releases install over each other without an uninstall.

| | |
| --- | --- |
| Subject | `CN=Preet, OU=MhireX, O=Aartiq, L=Navsari, ST=Gujarat, C=In` |
| **SHA-1** | `43:CD:DA:44:34:CF:34:77:91:6B:DE:4B:C6:7F:2A:A8:14:7F:5C:C5` |
| **SHA-256** | `09:31:97:DF:31:26:E7:E0:4E:12:0F:F2:27:24:18:8F:5A:87:95:7F:42:E5:C1:20:30:22:A5:E1:85:46:BB:6F` |
| Keystore | `mhirex-release.jks` (gitignored, never committed) |
| Keystore file SHA-256 | `5d147054474a0fc8fe96342e3d5d7fd1881758cba11a84aea9d171d65cbabe63` |

### Where each fingerprint has to be registered

In Google Cloud console -> APIs & Services -> Credentials, for project `mhirex-5888` and package
`com.mhirex.editor`:

| target | what it is for | needs |
| --- | --- | --- |
| **Android** OAuth client | the bottom-sheet stage, `GetGoogleIdOption` | the **SHA-1** |
| **Web** OAuth client `444731751675-28prvlfdjuhshvn0shmebbpb02e03not.apps.googleusercontent.com` | the button flow, `GetSignInWithGoogleOption`, via `serverClientId` | the **SHA-1** |

The SHA-256 is not something Google asks for during OAuth client setup. It is recorded here because
it is the value to compare when proving two builds were signed by the same key, and because Play
App Signing and other tooling ask for it.

> **Registration status: unconfirmed.** As of 2026-09-25 nobody had opened the console to verify
> these are present, and sign-in is reported failing in the published release. Do not record it as
> done until the console has been checked. See blocker 4 in `TODO.md`.

## Debug certificate

Not needed for any published build. Register it only if you want locally built debug APKs to sign in
on a machine other than the one that made them.

| | |
| --- | --- |
| Subject | `C=US, O=Android, CN=Android Debug` |
| **SHA-1** | `28:20:7C:E3:FA:29:DC:41:47:F1:C5:AA:18:7D:B8:09:F3:B4:B7:6E` |
| **SHA-256** | `7B:88:63:8C:12:9D:35:F8:45:E0:B0:A8:3C:F2:BC:B4:6B:08:B6:E2:74:B2:9D:74:CB:9D:ED:9C:DB:66:F6:0C` |
| Keystore | `~/.android/debug.keystore` |

The debug keystore is per-machine. A different machine has a different `debug.keystore` and therefore
a different SHA-1, so this row only ever describes the machine that generated it. That is the usual
reason a colleague's debug build cannot sign in.

## Regenerating the release key

Do not. A new key means:

- every existing install cannot be upgraded, and users must uninstall and lose their settings;
- the new SHA-1 must be added to both OAuth clients before sign-in works again;
- the values in this file, and the Play listing if there ever is one, become wrong.

If the keystore is lost, the honest options are to keep the package name and accept the reinstall, or
to ship under a new `applicationId`. Neither is a chore.

## Checking for drift

```
./tools/check-signing-fingerprint.sh                 # verify mhirex-release.jks against this file
./tools/check-signing-fingerprint.sh --print         # just print what to paste, no keystore needed
./tools/check-signing-fingerprint.sh --apk dist/v1.0-beta9/Mhirex-arm64-v8a.apk
```

The script exits non-zero when the key on disk no longer matches the SHA-256 recorded here, and
`tools/sign-release.sh` calls it so a mismatched release cannot be staged by accident.
