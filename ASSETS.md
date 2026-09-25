# Asset provenance

This inventory covers binary and externally sourced assets shipped in, or used
for MhireX's public presentation. It is intentionally conservative: an asset is
not treated as freely licensed merely because it is present in the repository.

| Asset | Use | Provenance / licence status |
|---|---|---|
| `logo.png` | MhireX launcher, About card, onboarding, README, and derived store graphics | Supplied for the MhireX rebrand by the project owner. Confirm the owner's distribution terms before release. |
| `app/src/main/res/mipmap-nodpi/logo.png` | Android launcher and in-app logo | Exact copy of the supplied `logo.png`; no third-party artwork added. |
| `app/src/main/ic_launcher-playstore.png` | Play Store icon | Resized derivative of `logo.png`; no additional artwork. |
| `fastlane/metadata/android/en-US/images/icon.png` | Play Store icon | 512 px resized derivative of `logo.png`; no additional artwork. |
| `fastlane/metadata/android/en-US/images/featureGraphic.png` | Play feature graphic | Neutral background with a centered derivative of `logo.png`; created for this rebrand. |
| `src/images/ic_banner.png` | README/store banner asset | Resized derivative of `logo.png`; no additional artwork. |
| `app/src/main/assets/fonts/Roboto-Regular.ttf` | Bundled editor/export font | Upstream LibreCuts asset. Roboto is distributed under Apache License 2.0 by Google; retain the applicable font notices when redistributing. |
| `app/src/main/res/raw/film.json` | Loading-screen Lottie animation | Upstream LibreCuts asset. Its exact separate licence/provenance has not been independently verified; retain the upstream MIT notice and audit before release. |
| `app/src/main/res/drawable/filter_preview_*.jpg` | Filter picker previews | Origin and licence have not been established. **Release blocker:** regenerate or replace with project-owned artwork, or document the applicable licence and attribution. |
| `app/src/main/res/drawable/trans_preview_*.webp` | Transition picker previews | Origin and licence have not been established. **Release blocker:** regenerate or replace with project-owned artwork, or document the applicable licence and attribution. |
| `src/images/sc_*.png` and upstream badge images | README/store presentation | Upstream LibreCuts assets. Retain upstream attribution and verify their redistribution terms before release. |
| `app/src/main/res/drawable-nodpi/ig_idea.jpg` | About screen and startup welcome profile photo | Public Instagram profile image for `@_fivetriple.8_`, used at the profile owner's request; image rights remain with the profile owner. |
| `app/src/main/res/drawable-nodpi/ig_mehul.jpg` | About screen and startup welcome profile photo | Public Instagram profile image for `@il__mehul_patel__li`, used with the account owner's attribution; image rights remain with the profile owner. |
| `app/src/main/res/drawable/ic_instagram_24.xml` | Instagram link badge | Original vector recreation of the familiar Instagram camera outline; no copied raster artwork. |
| `app/src/androidTest/assets/mivio_test_fixture.mp4` | Instrumentation-only test media | Synthetic local fixture; test-only and not included in the production APK. |
| `com.antonkarpenko:ffmpeg-kit-full-gpl:2.1.0` | Native media processing dependency | GPL-3.0. It is a dependency rather than a repository asset; its source, licence, and corresponding-source obligations must accompany distributed binaries. |

No bundled music, third-party sticker pack, or unlicensed meme artwork is
intended for MhireX 1.x. Any future asset addition must be added to this table
before release.
