# Mhirex — Implementation TODO

**Read `PLAN.md` first.** This file is the execution checklist. Every task is atomic and verifiable.

**Rules of engagement**
1. Work one Phase at a time, in order. Do not start Phase *N+1* until Phase *N* is verified and its boxes are ticked.
2. Never tick a box for partial work. If blocked, leave it unticked and record the blocker in the Phase's **Blockers** note.
3. After every phase: build, test, fix, and record implementation decisions in the phase's **Notes**.
4. Never break the build at a phase boundary.

**Build environment (required for every build in this repo)**
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home   # JDK 17; Java 25 breaks AGP 8.7.1
export GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"                                # this machine's IPv6 route is broken
./gradlew :app:assembleDebug
```

---

## Phase 0 — Rebrand to Mhirex + P0 defect fixes

* [x] **0.1 Inventory and freeze the rebrand surface**
  * [x] Record the 41 `package com.tharunbirla.librecuts` declarations (all `.kt`/`.java`) → `docs/rebrand-inventory.md` §1
  * [x] Record the 6 `com.tharunbirla.librecuts.R` imports → §1
  * [x] Record the 7 brand strings in `values/strings.xml` × 17 locales → §3
  * [x] Record the `values-zh-rCN` translated brand (自由剪辑) that must be replaced with the Latin "Mhirex" → §3
  * [x] Record `fastlane/metadata/**`, `README.md`, `.github/FUNDING.yml`, `res/raw/film.json` → §6, §7
  * [x] Confirm `LICENSE` MIT notice and decide the exact `NOTICE` wording → §8 (wording decided; `NOTICE` file not yet written)
  * [ ] Add `assets/licenses/` folder for bundled-asset licence files

* [x] **0.2 App label and brand strings**
  * [x] `app_name` → `Mhirex` in `values/strings.xml`
  * [x] `app_name` → `Mhirex` in all 16 other `values-*/strings.xml` (incl. overwriting the zh translation)
  * [x] `str_downloads_mhirex` → `Downloads/Mhirex` (all locales)
  * [x] `str_default_movies_mhirex` → `Default (Movies/Mhirex)`
  * [x] `str_default_music_mhirex` → `Default (Music/Mhirex)`
  * [x] `str_default_pictures_mhirex` → `Default (Pictures/Mhirex)`
  * [x] `str_mhirex_is_open_source_help` → reworded for Mhirex, keeps "open source" + GitHub link
  * [ ] `str_help_translate` → **DEFERRED, strings removed in 0.16:** the "Translate Mhirex" label, its Weblate subtitle, the LibreCuts wiki label and its wiki body text were all unreferenced after the About screen rewrite, and every one of them pointed at a LibreCuts destination. All four keys (`str_help_translate`, `str_contribute_translations_on_weblate`, `str_troubleshooting_amp_wiki_guide`, `str_if_you_encounter_any_export_is`) are deleted from `values/` and all locales, along with `Branding.WEBLATE` and `Branding.UPSTREAM_WIKI_TROUBLESHOOTING`. **Re-add the translate entry, the two strings and the platform link together, in one change, once a real Mhirex translation project exists** — re-adding one without the others is what produced the mislabelled link in the first place.
  * [x] `str_made_by_tharun_birla` → **kept verbatim in all 16 locales** (MIT requires the notice in every copy), plus a new additive `str_mhirex_based_on` line in the About card
  * [x] Rename the string *keys* containing `librecuts` to `mhirex` (done after values; `R.string` refs in Kotlin updated too)
  * [x] Sweep the whole `res/values*/` tree — no product-name or translated-brand leftovers; the required `LibreCuts` attribution remains in `str_mhirex_based_on`

* [x] **0.3 Package / namespace rename**
  * [x] `app/build.gradle`: `namespace 'com.mhirex.editor'` — `applicationId` deliberately unchanged (PLAN AD-2)
  * [x] `AndroidManifest.xml`: no hardcoded `com.tharunbirla` package attributes remain
  * [x] Move `app/src/main/java/com/tharunbirla/librecuts/**` → `app/src/main/java/com/mhirex/editor/**` (was briefly `com.mivio.editor`; see AD-2)
  * [x] Move `app/src/test/java/...` and `app/src/androidTest/java/...` to match
  * [x] Rewrite all `package` declarations
  * [x] Rewrite all `import com.tharunbirla.librecuts.*` statements
  * [x] Rewrite the 6 `R` imports
  * [x] `AndroidManifest.xml` `.LibreCutsApplication` → `.MhirexApplication` (class + file renamed)
  * [x] **18 fully-qualified custom-view references in 7 XML layouts also rewritten** — these resolve by reflection, so a stale FQCN compiles fine and crashes at inflate time
  * [x] Build and confirm zero `unresolved reference` errors
  * [x] Brand/path/link literals extracted to `Branding.kt` so a rebrand is a one-file change

* [x] **0.4 Delete dead code**
  * [x] Delete `app/src/main/java/.../ScratchTest.kt`
  * [x] Delete `app/src/main/java/.../ScratchTest.java`
  * [x] Delete repo-root `test_exo.kt`, `test_ext.kt`, `test_heavy.kt`
  * [x] Build
  * [ ] Exhaustive unreferenced-class sweep — **partially done.** `res/raw/film.json` was initially deleted on a false "unused" verdict and restored after the build failed; it is the loading-screen Lottie (`app:lottie_rawRes="@raw/film"` in `layout/loading_screen.xml:33`). A full asset audit by *reference form* (`@raw/`, `@drawable/`, `@string/`) is still owed.

* [ ] **0.5 P0-1 — fix no-op export on API 29+** — *verified on API 36; older APIs not available*
  * [x] Confirm the failure mode at `VideoEditingActivity.kt:4954-4963` (`FileOutputStream` into `getExternalStoragePublicDirectory`, with `WRITE_EXTERNAL_STORAGE` capped at API 28)
  * [x] Add `data/media/MediaPublisher.kt` — single implementation of "publish a finished file", used by all three previously-duplicated call sites
  * [x] Route the raw path through `MediaPublisher` (MediaStore + `IS_PENDING`)
  * [x] Preserve SAF tree-URI support, with fallback to MediaStore when the grant is revoked
  * [x] Use the `Mhirex` folder name for the default location
  * [x] Remove the direct-public-`File` branch entirely
  * [x] Add an instrumentation test asserting a readable `content://` URI → `MediaPublisherInstrumentedTest`
  * [x] **Verified on a real API 36 arm64 emulator:** publish returns a `content://` URI whose bytes read back byte-identical to the source
  * [ ] Verify on API 29, 33, 34 — only an `android-36.1` system image is installed locally

* [x] **0.6 P0-2 — audio-only export must not emit video bytes as `.mp3`** — *code complete; end-to-end MP3 playability not yet exercised*
  * [x] Confirm the raw-path audio-only branch copied source *video* bytes into a `.mp3` file
  * [x] Add `FFmpegRenderEngine.exportAudioOnly()` — real `libmp3lame` CBR 192 kbps transcode. `extractAudio()` was unusable here because `-acodec copy` would put AAC into an MP3 container, which is equally unplayable
  * [x] Raw path now transcodes rather than renaming
  * [x] Clear failure message if the build has no MP3 encoder
  * [ ] Test: audio-only export yields a genuinely playable MP3 (needs a UI-driven export run)

* [ ] **0.7 P0-3 — fix speed + reverse merge duration corruption** — *code complete, device verification outstanding*
  * [x] Inspect `VideoEditingViewModel.kt:913-915` — it keyed only off `speedOp.proxyUri != null`
  * [x] Identify the mismatch: the actual input is `finalProxyUri = reverseOp?.proxyUri ?: speedOp?.proxyUri`, so a **reverse** proxy wins when both are set, and a reverse proxy is *not* speed-scaled. Speed+reverse therefore reported `base / speed` for a clip that was really `base`, desyncing the concat demuxer
  * [x] Derive the duration from whichever proxy is genuinely input (`inputIsSpeedProxy`)
  * [ ] Consolidate into a single `effectiveDuration()` derivation — a targeted fix was made instead; the helper is deferred to Phase 1 where the duration model is reworked
  * [ ] Test: 3-clip merge + speed 0.5x + reverse → correct output duration, no overlap/clipping (needs a device)

* [ ] **0.8 P0-4 — fix hardcoded `.mp4` extension on cached content URIs** — *partially complete*
  * [x] `copyContentUriToTempFile` now derives the extension from the resolver's MIME type, with the display name as a fallback
  * [x] Whitelist the fallback suffix to `[a-z0-9]{1,8}` — the value reaches a cache filename and FFmpeg arguments, so a crafted `DISPLAY_NAME` must not inject a path separator
  * [x] Fix a second defect in the same function: `openInputStream(...)?.use {}` returned the path of a **zero-byte** file when the stream was null. Now throws, and a 0-byte result is rejected
  * [ ] Fix downstream type detection (`:828-834`) to read the **original** URI's MIME type, not the cached extension
  * [ ] Verify a still image no longer receives `-stream_loop -1`
  * [ ] Test: image/GIF/audio overlays imported via SAF produce correct timing and loop behaviour

* [ ] **0.9 P0-5 — fix `copyFontToCache` returning an alias instead of a path** — *code complete, device verification outstanding*
  * [x] Confirm `FFmpegRenderEngine.kt:101-124` returned `alias` (`"Roboto-Regular"`) despite its KDoc promising an absolute path
  * [x] Return `fontFile.absolutePath`. Registering the font directory does not make a bare relative alias valid inside a `drawtext=fontfile=` argument
  * [x] Verify `drawtext` receives an absolute `fontfile` (by inspection — `fontFilePath` flows unmodified from `onCreate` into the filter)
  * [ ] **Manual check: add text with the bundled Roboto font and confirm it renders in the actual export** (this bug is silent — the preview draws with Android's own font stack, never FFmpeg)
  * [ ] Test: export with text → the text is present in the output frames

* [x] **0.10 P0-6 — add `IS_PENDING` to MediaStore inserts** — *verified on API 36*
  * [x] `MediaPublisher` inserts with `IS_PENDING=1` for **all three** previously-duplicated publish call sites
  * [x] Stream the bytes, then `update` to `IS_PENDING=0`
  * [x] On failure *or* cancellation, delete the pending row / the partial SAF document
  * [x] Route the third site, `VideoEditingActivity.saveBitmapToGallery`, through `MediaPublisher` too — it had the same missing `IS_PENDING` and additionally reported success without checking `Bitmap.compress` returned true
  * [x] **Verified on a real API 36 emulator:** a successful publish reads back `IS_PENDING=0` and lands in `Movies/Mhirex`; a cancelled publish leaves no row and nothing pending; a missing source fails without creating a row; the image collection lands in `Pictures/Mhirex`
  * [ ] Test: SIGKILL the process mid-copy → no truncated file remains in the library (the cancel path is covered; a true process kill is not)

* [x] **0.10a P0-7 / SEC-33 — `VideoEditingActivity` hard crash + unnecessary export** — *found and fixed during device verification*
  * [x] Reproduce on the emulator: explicit intent without a `VIDEO_URI` extra → `UninitializedPropertyAccessException: lateinit property player has not been initialized` at `onCreate:714`
  * [x] Root cause: `setupExoPlayer()` built the ExoPlayer only inside `if (videoUri != null)`, but `onCreate` attaches a `Player.Listener` unconditionally
  * [x] Fix: construct the player unconditionally, attach media only when a URI exists
  * [x] Confirm the editor now reaches RESUMED with no crash (which also proves all 18 renamed custom-view FQCNs resolve at inflate time)
  * [x] Close the trigger path: `android:exported="true"` → `"false"`, with the reasoning recorded in a manifest comment
  * [x] Verify both callers (`MainActivity.kt:554`, `ProjectImportActivity.kt:543`) are in-app explicit intents and there is no `<intent-filter>`, so unexporting breaks nothing
  * [x] Rebuild and re-run the full suite green

* [x] **0.15a SEC-31 — remove `WRITE_EXTERNAL_STORAGE`**
  * [x] Confirm by grep that no code path writes to a public directory any more — including the last holdout, `saveBitmapToGallery`'s `API < Q` `FileOutputStream` branch, which 0.10 removed
  * [x] Confirm the residual `getExternalStoragePublicDirectory` calls only *navigate* for the read-only file browser / media picker
  * [x] Remove the permission; confirm absent from the merged-manifest report
  * [x] Record in `PLAN.md` §0.9 that this was verified by grep, not assumed

* [ ] **0.11 P1-7 — eliminate the export ANR**
  * [ ] Make `buildConsolidatedFFmpegCommand` a `suspend fun`
  * [ ] Move the call off `Dispatchers.Main` into `withContext(Dispatchers.IO)`
  * [ ] Audit all blocking calls in the builder: `MediaMetadataRetriever.setDataSource`, `BitmapFactory.decodeFile`, per-item retriever, `input.copyTo(it)`
  * [ ] Test: a 30-clip project exports without an ANR

* [ ] **0.12 P1-8 — cancellation must not be treated as failure** — *partially complete*
  * [x] Add `ExportService.ACTION_EXPORT_CANCELLED`; cancellation is no longer broadcast on `ACTION_EXPORT_FAILURE`
  * [x] Activity handles the new action without raising an error dialog
  * [x] `saveVideoToGallery` propagates a publish cancellation distinctly from a publish failure
  * [ ] Check `ReturnCode.isCancel` in `FFmpegRenderEngine` (`:298-318`) and construct `RenderResult.Cancelled`
  * [ ] **Never** enter the `h264_mediacodec` → `libx264` retry on a cancel
  * [ ] Verify `ProxyGenerationService` handles `Cancelled` correctly
  * [ ] Test: cancel an export → it stops and no new render starts

* [ ] **0.13 P1-9 — fix the FFmpeg session list leak**
  * [ ] Replace `mutableListOf` (`FFmpegRenderEngine.kt:36`) with `CopyOnWriteArrayList` or a `ConcurrentHashMap`
  * [ ] Remove the session in a `finally` on **every** path in `executeCommand`
  * [ ] Verify `hasActiveSessions()`/`getActiveSessionCount()` become accurate
  * [ ] Test: 200 sequential helper calls → session count returns to 0

* [ ] **0.14 P1-11 / P1-12 / P1-13 — temp cleanup, scoped cancel, shared engine** — *partially complete*
  * [x] Move temp-file cleanup from inside the `try` to `finally` (`ExportService.kt:110-112`) and log any delete that fails
  * [x] `VideoEditingActivity`'s direct-export path cleans its transcode temp file in `finally` too
  * [ ] Track *every* temp file created per export in a set and clean the whole set
  * [ ] Replace the process-global `FFmpegKit.cancel()` (`FFmpegRenderEngine.kt:179`) with session-id-scoped cancel
  * [ ] Make `FFmpegRenderEngine` a singleton so the Activity and `ExportService` address the same sessions (4 separate instances exist today)
  * [ ] Test: cancelling an export does not abort concurrent proxy generation
  * [ ] Test: no temp files remain in `cacheDir` after an induced failure

* [ ] **0.15 SEC-31 / deprecated API cleanup** — *SEC-31 closed as 0.15a; deprecated-API sweep mostly done; P1-14 deferred as a behaviour change*
  * [x] Remove `WRITE_EXTERNAL_STORAGE` from `AndroidManifest.xml` → done as **0.15a**
  * [x] Replace all 4 `getParcelableExtra` call sites with a new `utils/IntentCompat.kt` (`parcelableExtraCompat` / `uriExtraCompat`) that uses the API 33+ typed overload behind a version guard
  * [x] Fix the "Condition is always `true`" at `VideoEditingActivity.kt:8096` — `selectedColor` is a non-null `String`, so `&& selectedColor != null` was dead noise. Removed, behaviour unchanged
  * [x] Fix the "Condition is always `true`" at `VideoEditingViewModel.kt:1348` — `outputDuration` is provably non-null on the merge path, so the null check was dead. Simplified to an unconditional append, behaviour unchanged
  * [ ] **P1-14 — do NOT "fix" the third one blindly.** The always-true guard at `VideoEditingActivity.kt:8282` is not noise: the dead `else` branch (57 lines) is the **only** handler for mask keyframing on merged clips, and an earlier `?: return` makes it unreachable. Deleting it would cement a real bug; making it reachable is a behaviour change whose correctness is unverified. Recorded in `PLAN.md` §0.9 as P1-14; needs a device test of merge-clip masking first
  * [x] Add the explicit `kotlinx-coroutines` dependency to `app/build.gradle` via the 1.8.1 BOM (the version already resolving transitively, so no behaviour change) — with a comment explaining why "it happens to be there transitively" was load-bearing
  * [ ] Add `app_name` assertion test across all 17 locales (a single-locale runtime assertion exists in `ExampleInstrumentedTest`; the per-locale sweep does not)
  * [ ] Build + run tests

* [ ] **0.16 Branding assets and documentation**
  * [x] Add the additive `str_mhirex_based_on` attribution to the About card, keeping the MIT notice in every locale
  * [x] Create `NOTICE` crediting Tharun Birla (MIT) + Mhirex, cumulatively
  * [x] Create `ASSETS.md` recording origin + licence for **every** bundled asset (unknown filter/transition provenance is explicitly marked as a release blocker)
  * [ ] Document the `trans_preview_*.webp` and `filter_preview_*.jpg` provenance, or regenerate
  * [x] Identify `res/raw/film.json` — the loading-screen Lottie animation, in use
  * [x] Update `README.md` for Mhirex, keeping upstream attribution
    * [x] Drop the Weblate badge — it renders "LibreCuts" and counts the upstream project, not Mhirex. Re-add it when Mhirex has its own translation project (same treatment as the F-Droid badge in 0.16).
    * [x] Deduplicate the GitHub Releases badge; distribution row is now GitHub Releases · Obtainium · Discord
    * [x] Add a "Status: active development" block and a "What's next" roadmap so the README does not advertise unimplemented features as shipped (roadmap items come from `PLAN.md` §9 and are explicitly marked as not in current builds)
    * [x] Stop presenting the LibreCuts wiki as Mhirex documentation. Troubleshooting now points at the in-app error screen (which prefills a Mhirex issue), the issue tracker, and Discord; the `LC-###` codes and their upstream wiki reference are described as background, with `ErrorCode.kt` named as the authoritative list. **Do not rename the codes while the upstream wiki still documents them** — see 0.17 notes.
    * [x] Move "Keep Android Open" out of the top of the README into a bottom "Android Freedom" section, so the document positions the product first and states its position second
    * [x] Reword the origin story: keep the credits, drop the "developed just for him" phrasing
    * [x] Add a "Translations" section that states plainly that no Mhirex translation project exists yet and directs contributors to pull requests instead of the LibreCuts Weblate project
  * [x] Remove the app-side equivalents of the same two leaks: `Branding.WEBLATE` and `Branding.UPSTREAM_WIKI_TROUBLESHOOTING` deleted, plus the four unreferenced strings that fed them (`str_help_translate`, `str_contribute_translations_on_weblate`, `str_troubleshooting_amp_wiki_guide`, `str_if_you_encounter_any_export_is`) from `values/` and all locales. Neither constant had a single call site, so no UI changed. Replaced in-code, not just in prose: `ErrorDisplayActivity` already prefills a Mhirex issue with the error code and log, which is now the only in-app troubleshooting path.
  * [x] Update `fastlane/metadata/android/en-US/**` (title, short/full description)
  * [ ] Update `.github/FUNDING.yml`
  * [ ] Add the ffmpeg-kit **GPL-3.0** entry to the AboutLibraries screen (currently missing entirely)
  * [x] Design a Mhirex adaptive launcher icon + splash colour — `logo.png` is now the launcher/about/onboarding image; adaptive foregrounds point to it. A separate splash treatment remains for Phase 1.
  * [ ] Add a copyright header policy note for new files

* [ ] **0.17 Phase 0 verification**
  * [x] `./gradlew :app:assembleDebug` succeeds (3 ABI-split APKs)
  * [x] `./gradlew :app:testDebugUnitTest` green (the only JVM test is still the upstream `assertEquals(4, 2+2)`)
  * [x] `./gradlew :app:connectedDebugAndroidTest` green — **11/11 on a real API 36 arm64 emulator**
  * [x] App label reads "Mhirex" — asserted at runtime by instrumentation; `values-el` omits `app_name` and correctly falls back to the default
  * [x] `applicationId` and `namespace` are both `com.mhirex.editor` — **AD-2 reversed at user request**; guarded by a regression test so the two cannot drift apart by accident. Mhirex is a separate app from LibreCuts: no upgrade path, reinstall required, prior projects/settings unreachable, and F-Droid/Obtainium/Weblate listings need re-listing
  * [x] `VideoEditingActivity` inflates and reaches RESUMED on the emulator, proving all 18 renamed custom-view FQCNs resolve by reflection
  * [x] No-op save works on API 29+ — covered on API 36 by `MediaPublisherInstrumentedTest`
  * [ ] A legacy LibreCuts `.lcprj` still opens
  * [ ] Text and subtitles render in a real export — **P0-5's fix is compile-verified only; the silent-render check still needs a UI-driven export**
  * [ ] No temp files leak on induced failure
  * [ ] Verify on API 29 / 33 / 34 (only the `android-36.1` image is installed locally)
  * [ ] `ASSETS.md` complete
  * **`v1.0-beta8` was cut and published on 2026-09-25 with the items above still open.** The tag pins `92f7707`; CI signed and published `Mhirex-{arm64-v8a,armeabi-v7a,x86_64}.apk`. The unchecked items below therefore shipped as **disclosed gaps in the release notes**, not as silent omissions: legacy `.lcprj` loading, text/subtitle render parity through a real export, temp-file cleanup on failure, and API 29/33/34 coverage. If any of them turns out to be user-visible, the honest fix is a follow-up release, not a docs edit.
  * **Blockers:**
  1. **GitHub repository is live at [`Preet3627/Mhirex`](https://github.com/Preet3627/Mhirex).** `Branding.REPO_URL`, issue links, README download links and the in-app Star action now target the new repository. `Branding.WEBLATE` is **gone** rather than repointed — it had no call site, and the only URL it could hold was the upstream LibreCuts project. Translations are pull-request-only until Mhirex has its own platform.
  2. **`filter_preview_*.jpg` and `trans_preview_*.webp` have no documented provenance.** Shipping them without a licence record is a release blocker; this is an audit task, not a code task.
  3. **Only an `android-36.1` system image is installed locally.** API 29/33/34 remain unverified; covering them means downloading those system images.
  4. **Google Sign-In UI is implemented but has NEVER been exercised on a device.** Added after AD-2 as `com.mhirex.editor.auth.*` (Credential Manager, not the deprecated `play-services-auth` client). Verified: compiles, clean `assembleDebug`, `testDebugUnitTest` 4/4, `INTERNET` present in the APK, OAuth web client ID matches `google-services.json`. NOT verified: an actual sign-in, avatar download, or sign-out — no emulator run has covered this code, and no real Google account was used. Treat as unproven until someone signs in on a device.
  5. **`INTERNET` is a new permission and changes a stated privacy property.** Mhirex previously had no network permission at all, so "cannot phone home" was enforced by the manifest. It is now requested for the account chooser and the avatar download only; media processing remains on-device. The About copy must not be read as "makes no network requests", and the privacy claim is now a code-review guarantee rather than a manifest guarantee.
  **Notes:**
  - **New P0 defect found and fixed during device verification (absent from the original audit).** `VideoEditingActivity` crashed with `UninitializedPropertyAccessException: lateinit property player has not been initialized` at `onCreate:714`. `setupExoPlayer()` built the ExoPlayer only inside `if (videoUri != null)`, but `onCreate` then called `player.addListener(...)` unconditionally. Normal use always supplies a `VIDEO_URI` extra from `MainActivity`, which is why the audit missed it — but the activity is `exported="true"` with **no permission guard** and honours an explicit intent, so any app on the device could hard-crash Mhirex. Fixed by constructing the player unconditionally and attaching media only when a URI exists. **SEC-33 is now closed:** the activity is `exported="false"`; any future external entry point must add both an intent filter and a signature-level permission.
  - Three mistakes made and corrected during this phase, all recorded so they are not repeated:
    1. A bulk `sed` for the rebrand had a stray `"` in its replacement, corrupting 7 URL/path string literals. Fixed by extracting the literals into `Branding.kt` rather than re-patching the quotes, so the class of error cannot recur.
    2. `res/raw/film.json` was deleted on a false "unused" verdict. The original grep searched for `R.raw` / `film.json`, which cannot match the XML attribute form `app:lottie_rawRes="@raw/film"`. The build caught it immediately. **Rule adopted: audit assets by reference form (`@raw/`, `@drawable/`, `@string/`), not by symbol name.**
    3. The first `MediaPublisher` rewiring left two duplicate closing braces, truncating the `VideoEditingActivity` class body and producing 1322 cascading errors. **Rule adopted: after replacing a block that ends mid-function, re-read the following lines — do not trust the brace balance implied by the old text.**
  - `Branding.PREFS_NAME` is intentionally still `"librecuts_prefs"`. **The original justification is now void:** with `applicationId = com.mhirex.editor` there is no LibreCuts data to preserve and no upgrade path, so the name is kept only as a frozen identifier and MIT provenance. The `LC-1xx` error codes are kept for a still-valid reason: they are cited in the upstream troubleshooting wiki, so renaming them would break those references.
  - **Emulator setup for future phases:** a local AVD (its on-disk name predates the rebrand and is not an app identifier) exists — API 36, `google_apis_playstore`, arm64-v8a, 3 GB RAM / 4 cores. Build with JDK 17 and `GRADLE_OPTS=-Djava.net.preferIPv4Stack=true`, then `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`. Note that `connectedDebugAndroidTest` **uninstalls the app afterwards**, so reinstall the ABI-matched APK (`app-arm64-v8a-debug.apk`) before any manual `adb shell am start`.
  - **JDK 17 is mandatory for `assembleRelease`, not a preference.** On JDK 25 (the machine default, `/Library/Java/JavaVirtualMachines/jdk-25.jdk`) the release build compiles, dexes and packages all three ABI APKs, then dies on `:app:lintVitalAnalyzeRelease` with a useless error whose only clue is the Java version string: `> 25.0.2`, followed by `Failed to stop service ...LintClassLoaderBuildService`. That is a lint/AGP 8.7.1 incompatibility, **not** a lint finding — the same build under `/opt/homebrew/opt/openjdk@17` passes with zero findings. It cost ~11 minutes to surface. **Build releases with `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.** CI is unaffected (the workflow pins temurin 17). Also note that a failed lint run can leave the task looking `UP-TO-DATE` on the next invocation, so confirm a release build with `--rerun-tasks` rather than trusting a second plain run.

---

## Phase 1 — Core primitives & design system

* [ ] **1.1 `core/` package skeleton + architecture guard**
  * [ ] Create `com.mhirex.editor.core`
  * [ ] Write an architecture test: `core`/`data`/`engine` must not import `feature`/`ui`
  * [ ] Add `core/time/Rational.kt` (immutable, reduced, safe arithmetic)
  * [ ] Add `core/time/Timebase.kt` (frame ↔ time conversion, microsecond constants)
  * [ ] Add `core/time/TimeRuler.kt` (tick generation for the ruler)
  * [ ] Unit tests: rational reduction, frame↔time round-trip at 24/25/30/50/60 fps

* [ ] **1.2 Core maths**
  * [ ] `core/math/Easing.kt` — linear, easeIn/Out/InOut (cubic/quad), hold/step, elastic, bounce, back
  * [ ] `core/math/Geometry.kt` — normalized rect, transforms, anchor points
  * [ ] `core/math/KeyframeInterpolation.kt` — sample a keyframe track at time *t*
  * [ ] Unit tests: easing value tables; interpolation at boundaries, midpoints, outside range

* [ ] **1.3 Core result type**
  * [ ] `core/result/AppError.kt` — sealed hierarchy: Media / Decode / FFmpeg / Permission / Storage / State
  * [ ] `core/result/Result.kt` — small `Outcome<T>` type used across layers
  * [ ] Unit tests: error mapping table

* [ ] **1.4 `ui/` design system — Mhirex tokens**
  * [ ] Decide the Mhirex palette (keep OLED black; replace "Electric Pink" with the Mhirex accent)
  * [ ] Write `ui/theme/Color.kt`, `Type.kt`, `Shape.kt`, `Dimens.kt`
  * [ ] Port and re-theme `themes.xml` / `colors.xml`
  * [ ] Build standard components: `PrimaryButton`, `ToolChip`, `SliderRow`, `SectionHeader`, `BottomSheetScaffold`
  * [ ] **Enforce 48 dp minimum touch targets** on every interactive component
  * [ ] Support font scaling in all layouts
  * [ ] Respect `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (disable non-essential animation)
  * [ ] Screenshot the components

* [ ] **1.5 Phase 1 verification**
  * [ ] Build green; unit tests green
  * [ ] Architecture test passing
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 2 — Project v2: multi-track model, versioned storage, migration

* [ ] **2.1 Core model — media**
  * [ ] `core/model/Ids.kt` — `TrackId`, `ClipId`, `EffectId`, `ProjectId`, `LayerId`
  * [ ] `core/model/MediaRef.kt` — content URI + persisted permission + content hash + probe cache
  * [ ] `core/model/Clip.kt` — id, trackId, source, timelineStartUs, inUs, outUs, speed, reverse, transform, opacity, volume, effects, keyframes, mask, colorGrade, sourceDurationUs, proxy
  * [ ] `core/model/Transform.kt`, `MaskSpec.kt`, `ColorGrade.kt` (all coordinates **normalized 0.0–1.0**)
  * [ ] `core/model/CanvasSpec.kt` — width, height, fps (Rational), background
  * [ ] `core/model/Selection.kt` — anchor, focused, range, additive
  * [ ] `core/model/Marker.kt` — trackId, timeUs, kind (BEAT/CHAPTER), strength

* [ ] **2.2 Core model — tracks**
  * [ ] `core/model/Track.kt` — sealed: `MediaTrack`, `AudioTrack`, `OverlayTrack`
  * [ ] `AudioTrack.kind` ∈ MUSIC / VOICEOVER / SFX
  * [ ] Track `locked` / `muted` / `solo`
  * [ ] `core/model/AudioClip.kt`, `OverlayLayer.kt`
  * [ ] `core/model/Project.kt` — **with `schemaVersion`**, `id`, `name`, `canvas`, `tracks`, `selection`, `markers`, `meta`, `exportSpec`
  * [ ] `Project.durationUs` as a **derived** property, never stored
  * [ ] Unit tests: duration arithmetic; overlapping clips; track ordering

* [ ] **2.3 Effect model**
  * [ ] `core/model/Effect.kt` — sealed base with `id`, `enabled`, `params: Map<String, Float>`
  * [ ] Implement `TransformEffect`, `FilterEffect`, `ColorAdjustEffect`, `TransitionEffect`, `TextEffect`, `StickerEffect`, `AudioEffect`, `MaskEffect`
  * [ ] `core/model/Keyframe.kt` — `Keyframe(timeUs, value: Vec2, easing)`, `KeyframeTrack<T>`
  * [ ] Unit tests: param validation; keyframe sampling

* [ ] **2.4 Versioned serialization**
  * [ ] `data/project/ProjectCodec.kt` — hand-written polymorphic codec (adapted from `ProjectSerializer`, **with** `schemaVersion`)
  * [ ] `data/project/ProjectMigrator.kt` — migration chain v1 → v2 → …
  * [ ] **Do not** copy the `ProjectSerializer` fallback of substituting `MuteAudio` for unknown ops (B2)
  * [ ] Unknown/unsupported operation → a typed `UnsupportedOperation` marker the UI can report, never a silent substitution
  * [ ] `MediaRef` serialises as a string, not `Serializable`
  * [ ] Unit tests: round-trip equality; unknown-type handling produces a reportable marker

* [ ] **2.5 Atomic storage + recovery**
  * [ ] `data/project/ProjectStore.kt`
  * [ ] `save()`: write `cache/tmp-<uuid>.json` → `fsync` → atomic rename → `projects/<id>.lcprj`
  * [ ] `load()`: read → parse → `migrate(schemaVersion)` → `validate` → `Project`
  * [ ] `list()`: metadata index without full parse
  * [ ] `recover()`: on cold start sweep `*.tmp`; promote or discard; surface quarantined files
  * [ ] Unit tests (Robolectric): atomic write; truncated file → quarantined, app does not crash, user informed
  * [ ] Test: simulate a process kill mid-write → next launch recovers or discards cleanly

* [ ] **2.6 Legacy project migration**
  * [ ] `data/project/LegacyProjectMigrator.kt` — `VideoProject` + `List<EditOperation>` → `Project`
  * [ ] Map: `Trim` → clip in/out; `Merge` → media-track clips; `AddText` → `TextEffect` overlay
  * [ ] Map: `AddBackgroundAudio` → `AudioTrack` MUSIC; `AddImageOverlay` → `StickerEffect`; `Transition` → `TransitionEffect`
  * [ ] Map: `ColorFilter` → `FilterEffect`; `Adjust` → `ColorAdjustEffect`; `Crop` → `CanvasSpec`; `CanvasBackground` → canvas background
  * [ ] Preserve `beats` → `Marker(BEAT)`
  * [ ] Add a real `.lcprj` fixture from upstream to `src/test/resources`
  * [ ] Unit tests: 20-operation fixture migrates to a semantically identical project
  * [ ] **Acceptance:** round-trip export is duration- and stream-layout-comparable

* [ ] **2.7 Phase 2 verification**
  * [ ] Build green; unit tests green
  * [ ] Migrator fixture tests pass
  * [ ] Crash-recovery tests pass
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 3 — Editor state, structured undo/redo

* [ ] **3.1 `EditorState` and `EditorStore`**
  * [ ] `feature/editor/EditorState.kt` — project, selection, playheadUs, zoom, snapping
  * [ ] `feature/editor/EditorStore.kt` — single writer, `StateFlow<EditorState>`
  * [ ] `dispatch(op: EditOp)` is the **only** mutation path
  * [ ] Add an architecture test forbidding direct `Project` mutation outside `EditorStore`

* [ ] **3.2 `EditOp` implementations**
  * [ ] `core/model/EditOp.kt` — `apply(state)`, `invert(): EditOp`
  * [ ] Implement: `TrimClip`, `SplitClip`, `DeleteClip`, `MoveClip`, `ReorderClip`, `AddClip`, `SetSpeed`, `SetTransform`
  * [ ] Implement: `AddEffect`, `RemoveEffect`, `UpdateEffect`, `ReorderEffect`
  * [ ] Implement: `AddText`, `UpdateTextStyle`, `SetTextTiming`
  * [ ] Implement: `AddAudioTrack`, `AddSfx`, `TrimAudio`, `SetVolume`, `SetDucking`
  * [ ] Implement: `SetTransition`, `ApplyAutoTransitions`
  * [ ] Implement: `ApplyTemplate`, `ApplyMemePack`
  * [ ] Implement: `BeatSync`
  * [ ] Implement: `SetSelection`
  * [ ] Unit tests: **`invert(apply(s)) == s` for every op**

* [ ] **3.3 `HistoryManager`**
  * [ ] Bounded `ArrayDeque` (cap 100), no project snapshots
  * [ ] `undo()` / `redo()` with correct coalescing
  * [ ] `beginCoalesce`/`endCoalesce` so a 50-tick slider drag is **one** history entry
  * [ ] Persist history so undo survives process death
  * [ ] Unit tests: 36-op mixed sequence; coalescing; 10,000-op memory bound

* [ ] **3.4 Route legacy operations through the store**
  * [ ] Migrate every existing edit path in `VideoEditingActivity` to `EditorStore.dispatch`
  * [ ] Retire `EditCommand.kt`, `HistoryState`, and the snapshot undo in `VideoEditingViewModel`
  * [ ] Verify undo/redo for: trim, split, delete, move, effects, text, audio, transitions, templates, beat sync
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 4 — Media3 player and a real preview pipeline

> Removes B1, the single worst defect. **No FFmpeg in the preview path, ever.**

* [ ] **4.1 Media3 migration**
  * [ ] Add `androidx.media3:media3-exoplayer`, `media3-ui`, `media3-effect`, `media3-common`
  * [ ] Remove `com.google.android.exoplayer:exoplayer-core` and `exoplayer-ui`
  * [ ] Replace all 27 `com.google.android.exoplayer*` imports in `VideoEditingActivity.kt`
  * [ ] Replace `activity_video_editing.xml`'s `StyledPlayerView` / `AspectRatioFrameLayout`
  * [ ] Use `ContentPosition` for frame-accurate scrubbing
  * [ ] Build and verify playback of video, audio-only, and image sources

* [ ] **4.2 `RenderPlan` (pure, testable)**
  * [ ] `engine/model/RenderPlan.kt` — UI-agnostic description of the edit at the playhead
  * [ ] `engine/RenderPlanner.kt` — `Project` + playhead → `RenderPlan`
  * [ ] Must be a **pure function** — unit-testable with no device
  * [ ] Unit tests: plan for single clip, multi-clip, overlays, transitions, audio

* [ ] **4.3 GPU preview effects**
  * [ ] `engine/render/PreviewRenderer.kt`
  * [ ] Map `TransformEffect` → `ScaleAndRotateTransformation`
  * [ ] Map `ColorAdjustEffect` / `FilterEffect` → `RgbMatrix`
  * [ ] Map `OverlayLayer` → `OverlayEffect`
  * [ ] Map `StickerEffect` → `OverlayEffect` with the decoded sticker
  * [ ] Render **only the active clip range around the playhead**, not the whole timeline
  * [ ] Scrub from the existing scrub proxy

* [ ] **4.4 Delete the old preview path**
  * [ ] Remove `renderSegmentedPreview()` (`VideoEditingActivity.kt:7556-7606`) and `dismissPreview()`
  * [ ] Remove `FFmpegRenderEngine.renderTrimPreview` / `renderCropPreview` / `renderTextPreview`
  * [ ] Add a test/assertion that the preview path never calls `FFmpegKit`
  * [ ] **Verify no FFmpeg process is spawned during editing** (check `ps` while dragging a slider)

* [ ] **4.5 `PreviewDebouncer`**
  * [ ] Coalesce rapid edits by **dropping stale requests**, never by queueing work
  * [ ] Guarantee at most one in-flight preview
  * [ ] Unit tests: 50 rapid edits → at most N previews, latest state wins

* [ ] **4.6 Performance gate**
  * [ ] First preview frame < 150 ms
  * [ ] No leaked FFmpeg processes
  * [ ] No `ANR` during rapid scrubbing
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 5 — Timeline v2

* [ ] **5.1 `TimelineLayoutEngine` (pure)**
  * [ ] Map tracks/clips/time → lane geometry, pixel positions
  * [ ] Pure, no Android types — unit-testable
  * [ ] Unit tests: geometry at multiple zooms; overlapping clips; empty states

* [ ] **5.2 `TimelineView`**
  * [ ] Replace `TrackTrimView` (custom `View`, single continuous canvas)
  * [ ] Lanes sized to track content
  * [ ] Continuous pinch-zoom (replaces the 3 discrete `ZoomMode` levels)
  * [ ] Horizontal scroll synchronized with the ruler
  * [ ] Playhead
  * [ ] Per-clip trim handles with correct hit-testing
  * [ ] Drop indicator for drag-to-reorder
  * [ ] **Zero allocation in `onDraw`/`onTouchMove`** — pre-allocate `Paint`, `Path`, arrays
  * [ ] `drawVertices` for filmstrip tiles
  * [ ] Dirty-rect `invalidate()` only
  * [ ] `postOnAnimation` for gesture handling
  * [ ] `invalidate()` lifecycle correctness on detach

* [ ] **5.3 `GestureArbiter`**
  * [ ] Decide **once per gesture stream**: drag / scroll / pinch / trim / long-press
  * [ ] Prevents gesture conflicts (the main UX risk)
  * [ ] Unit tests: gesture disambiguation table

* [ ] **5.4 `SnapEngine` (pure)**
  * [ ] Candidates: clip edges, clip starts, playhead, project start/end, **beat markers**
  * [ ] **Pixel** threshold, not time threshold, so behaviour is zoom-independent
  * [ ] Priority ordering
  * [ ] Unit tests: snap selection at multiple zooms; tie-breaking; no-snap cases

* [ ] **5.5 Multi-select**
  * [ ] `Selection` in `EditorState` (therefore undoable)
  * [ ] Tap, long-press, range, additive
  * [ ] Visual selected state
  * [ ] Bulk actions: delete, move, transition, speed

* [ ] **5.6 Frame precision**
  * [ ] All model time is `Long` microseconds; display converts to frames via `Rational` fps
  * [ ] Show a frame readout when zoomed in
  * [ ] Split/trim land exactly on frame boundaries

* [ ] **5.7 `ThumbnailRepository`**
  * [ ] `data/media/ThumbnailRepository.kt` — disk-backed, keyed by (contentHash, timeMs, bucket)
  * [ ] Extract with `MediaMetadataRetriever` (not ffmpeg)
  * [ ] Bounded concurrency (2)
  * [ ] In-memory LRU over disk
  * [ ] **Progressive fill** — timeline is usable before thumbnails exist
  * [ ] Bounded response to `onTrimMemory`
  * [ ] Benchmark: 200 thumbnails < 8 s; memory < 24 MB

* [ ] **5.8 Accessibility (timeline)**
  * [ ] `contentDescription` on the timeline
  * [ ] `TimelineAccessibilityDelegate` exposing clips as virtual nodes
  * [ ] Accessibility actions: extend, trim, delete, split, move
  * [ ] Keyboard/D-pad navigation
  * [ ] Test: a clip can be selected, trimmed and deleted using **only** accessibility actions

* [ ] **5.9 Performance gate**
  * [ ] 200 clips @ 60 fps on a physical mid-range device (Macrobenchmark)
  * [ ] Frame time < 16 ms
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 6 — Media & audio foundation

* [ ] **6.1 Cached waveforms (fixes B13)**
  * [ ] `data/audio/WaveformExtractor.kt` — rewrite; **stream** in chunks, no whole-file `readBytes()` (P3-25)
  * [ ] `data/audio/WaveformCache.kt` — keyed by content hash, LRU-evicted
  * [ ] Store as a compact binary `ShortArray` peak file; memory-map for drawing
  * [ ] Remove the 4 hot ffmpeg call sites (`:3624`, `:6171`, `:6506`, `:6712`)
  * [ ] Wire `LC-301 OUT_OF_MEMORY` to a real catch
  * [ ] Unit tests: cache hit/miss/eviction; peak extraction vs a synthetic sine
  * [ ] Benchmark: 4 min track < 1.5 s cold; cached read < 20 ms

* [ ] **6.2 `WaveformView`**
  * [ ] Draw cached peaks in the timeline
  * [ ] Trim handles, fade handles
  * [ ] Live level meter during voice-over
  * [ ] Allocation-free drawing

* [ ] **6.3 `AudioMixer` (centralize)**
  * [ ] Move the scattered `adelay`/`volume`/`amix` construction into one place
  * [ ] Preserve the proven filter **order**: trim/PTS → fade → `adelay` → `volume` → `sidechaincompress` → `amix`
  * [ ] Add `loudnorm` and `alimiter` for headroom (P3-28)
  * [ ] Unit tests: filter order; multi-track mix graph golden strings

* [ ] **6.4 Fix global audio flags (P3-27)**
  * [ ] Scope `MuteAudio` to a clip instead of muting the whole export
  * [ ] Scope `removeOriginalAudio` to a track instead of all tracks
  * [ ] Legacy projects with the old global flag still load

* [ ] **6.5 Multi-track mixing UI**
  * [ ] AUDIO tool → Music / Voiceover / **SFX** tabs
  * [ ] Per-track volume fader
  * [ ] Per-track mute/solo
  * [ ] Fade in/out handles
  * [ ] 3+ tracks mix correctly (tested)

* [ ] **6.6 Ducking parameters (Phase 21)**
  * [ ] `core/model/AudioEffect.kt` — `DuckingParams(threshold, ratio, attack, release)`
  * [ ] Expose as sliders, replacing the `ducking: Boolean`
  * [ ] Auto-duck mode via `SpeechActivityDetector` (RMS-envelope VAD)
  * [ ] VAD runs on a background dispatcher, cached per file hash
  * [ ] Show the ducking envelope in the UI
  * [ ] Unit tests: parameter→filter mapping; VAD on a synthetic speech-envelope signal
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 7 — Text

* [ ] **7.1 `TextStyle` and `TextAnimation`**
  * [ ] `core/model/TextStyle.kt` — font, size, colour, **stroke**, **shadow**, **background box**, align, spacing, transform, opacity
  * [ ] `core/model/TextAnimation.kt` — fade / pop / slide / typewriter / shake / wave
  * [ ] `core/model/TextEffect.kt`
  * [ ] All style values normalized
  * [ ] Unit tests: style validation; animation parameter derivation

* [ ] **7.2 Shared layout model (WYSIWYG guarantee)**
  * [ ] `core/model/TextLayoutMetrics.kt` — the single source of truth for text size/position/rotation
  * [ ] Consumed by **both** the Android preview layout and the FFmpeg expression compiler
  * [ ] Test: preview layout and export agree within a documented tolerance

* [ ] **7.3 Hardened `FFmpegEscaper` (SEC-30)**
  * [ ] Escape the full set: `\ ' " : , ; [ ] % =`
  * [ ] **Exhaustive test table** covering every character, including combinations and multi-byte/emoji
  * [ ] Prefer per-cue `textfile=` temp files so user payloads never enter the command string
  * [ ] Validate `fontPath` against an allowlist of font directories
  * [ ] Test: a `.lcprj` with hostile text fields loads and exports safely

* [ ] **7.4 Text compiler**
  * [ ] Replace `buildDrawtextExpr` with an effect-driven compiler
  * [ ] Support fill, stroke (`borderw`), **shadow**, **background box** (`box`/`boxcolor`/`boxborderw`)
  * [ ] Rotation/scale via `rotate`/`scale` in the overlay chain
  * [ ] Animation via time-parameterised alpha/position expressions from `EasingFunction`
  * [ ] Golden tests for the emitted filter string

* [ ] **7.5 Text tool UI**
  * [ ] Templates: plain / caption / meme
  * [ ] Style sheet: font, size, colour, stroke, shadow, background
  * [ ] Position / scale / rotate handles on the preview
  * [ ] Animation picker
  * [ ] Duration handles on the timeline
  * [ ] **All controls round-trip preview→export identically**

* [ ] **7.6 Meme text catalog (Phase 6)**
  * [ ] `data/assets/MemeTextCatalog.kt` + `assets/catalogs/meme_text.json`
  * [ ] Generic `PresetCatalog<T>` loader with schema validation
  * [ ] Include the 8 required presets: `BRO 💀`, `NAHHH 😭`, `WHAT 💀`, `BRUH`, `SUS 🤨`, `W`, `L`, `I'M DONE 😭`
  * [ ] Add ~30 more original presets
  * [ ] Each preset = text + style + animation + optional SFX id
  * [ ] Unit tests: catalogue loads; no duplicate ids; every referenced SFX id resolves
  * [ ] **Acceptance:** all 8 named presets apply in one tap and stay fully editable
  * [ ] Verify emoji render consistently in preview and export; exclude inconsistent presets

* [ ] **7.7 Subtitle fixes**
  * [ ] Honour the stored subtitle colour and background (P3-24, currently hardcoded)
  * [ ] Keep SRT import; add export-as-SRT
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 8 — Effects, filters, effect stacks, meme packs

* [ ] **8.1 `EffectCompiler`**
  * [ ] `engine/ffmpeg/EffectCompiler.kt` — `List<Effect>` → ordered FFmpeg filter string
  * [ ] Order must be **predictable and golden-tested**
  * [ ] Retires the per-index `Adjust`/`ColorFilter` implicit ordering

* [ ] **8.2 Effect intensity**
  * [ ] Add `intensity` to colour effects; blend against the unfiltered stream
  * [ ] Intensity 0 is a true no-op (test)

* [ ] **8.3 Custom LUT import**
  * [ ] `data/assets/LutLoader.kt` — parse and validate `.cube` (1D and 3D)
  * [ ] Size and entry caps; parse timeout; reject malformed input with a clear message
  * [ ] Cache the parsed LUT
  * [ ] Unit tests: valid, malformed, oversized, 1D, 3D

* [ ] **8.4 Effects UI**
  * [ ] EFFECTS tool → categories: Colour / Adjust / Distort / Style
  * [ ] Per-effect intensity slider
  * [ ] Reorder, duplicate, enable/disable, delete per effect
  * [ ] A clear stack list view

* [ ] **8.5 Effect stack presets (Phase 14)**
  * [ ] `EffectStackPreset` + `EffectStackCatalog` (`assets/catalogs/effect_stacks.json`)
  * [ ] Include the 8 required stack members: video effect, transform, text, overlay, SFX, animation
  * [ ] "Save current stack as preset"
  * [ ] `UserPresetStore` for user-created stacks
  * [ ] Stacks remain fully editable after application
  * [ ] Unit tests: apply/reload round-trip; ordering preserved

* [ ] **8.6 Meme effect packs (Phase 7)**
  * [ ] `MemePack` + `MemePackCatalog` (`assets/catalogs/meme_packs.json`)
  * [ ] `MemePackApplier` — a pack is a **template**, applied by materialising normal entities **with fresh ids** so every component is independent
  * [ ] Implement the `☠️ DEAD PACK`: freeze + zoom + shake + skull + ` BRO 💀 ` + bass hit
  * [ ] Add ~8 more original packs
  * [ ] "Save current selection as pack"
  * [ ] Validation: forbid duplicate effect-type/param-slot conflicts
  * [ ] Unit tests: every pack applies to an empty project; independent ids; save/load round-trip
  * [ ] **Acceptance:** DEAD PACK applies in one tap, produces all six components, each independently editable and deletable, user-saved pack reloads
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 9 — SFX system

* [ ] **9.1 SFX synthesis (no sampled assets)**
  * [ ] `data/audio/sfx/` — procedural generators for: Impact, Whoosh, Pop, Bass, Meme, Transition, Notification, Camera, Cinematic, Glitch, Comedy
  * [ ] **Bundle the synthesiser code, not recordings** (PLAN §6.4) — unambiguous licensing
  * [ ] Generate assets at build/first-run into cache
  * [ ] Each SFX ≤ 3 s; all 10+ categories populated
  * [ ] Unit tests: each generator produces a non-silent, correctly-durations buffer

* [ ] **9.2 `SfxCatalog`**
  * [ ] `SfxDefinition(id, category, name, fileName, durationMs, licence)`
  * [ ] `assets/catalogs/sfx.json` with licence metadata
  * [ ] Validation: all files present, size cap, valid format
  * [ ] Browsable with a category filter and waveform preview

* [ ] **9.3 SFX in the model**
  * [ ] `AudioTrack.kind = SFX`; `AudioClip` on an SFX track
  * [ ] `SfxAttachment(parentEffectId, offsetUs, deleteWithParent)`

* [ ] **9.4 Auto-attach (Phase 8)**
  * [ ] `SfxAutoAttachRule` — when an effect of type X is applied, offer its SFX
  * [ ] One-tap "add SFX?" offer
  * [ ] **If an effect moves, its SFX moves with it**
  * [ ] **If an effect is deleted, offer to delete the associated SFX** (`deleteWithParent`)
  * [ ] SFX stay editable afterwards
  * [ ] Unit tests: rule table; move-with-parent; delete-offer semantics

* [ ] **9.5 Rendering**
  * [ ] SFX are ordinary audio inputs — `adelay` + `volume` + `amix`
  * [ ] Verify SFX appear correctly in the mix
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 10 — Beat detection & beat sync

* [ ] **10.1 `BeatTracker` (replace `AudioAnalyzer`)**
  * [ ] Stream decode to 22.05 kHz mono — **never `readBytes()`** (P3-25)
  * [ ] **Spectral-flux onset envelope** with half-wave rectification
  * [ ] FFT implemented in-module (no new dependency)
  * [ ] **Adaptive median-threshold normalisation** — removes the level-dependent magic-1000 threshold
  * [ ] **Tempo estimation** by autocorrelation of the onset envelope, 60–180 BPM
  * [ ] **Beat phase selection** by dynamic programming (Ellis-style) → coherent grid
  * [ ] **Downbeat/bar inference** with a confidence score
  * [ ] Streaming — memory O(window)
  * [ ] Cancellable, with progress

* [ ] **10.2 `BeatMap`**
  * [ ] `data/audio/BeatMap.kt` — `bpm`, `confidence`, `beatsUs`, `downbeatsUs`, `onsetUs`, `source`
  * [ ] `grid(mode: BeatMode, fromUs, toUs)` — `EVERY` / `EVERY_2` / `EVERY_4` / `MAJOR`
  * [ ] `nearestBeat(toUs, toleranceUs)`
  * [ ] `BeatMapCache` keyed by content hash
  * [ ] Unit tests: `grid` for every mode; `nearestBeat` tolerance

* [ ] **10.3 Beat fixtures and accuracy tests**
  * [ ] Create `src/test/resources/beat_120bpm.wav`, `beat_128bpm.wav`, `beat_140bpm.wav` (synthetic click tracks)
  * [ ] Create `silence.wav`, `white_noise.wav`
  * [ ] Create a real-music fixture + expected-grid fixture
  * [ ] **Assert the grid is within ±30 ms of ground truth for 120/128/140 BPM**
  * [ ] Assert low confidence for noise; empty (no crash) for silence

* [ ] **10.4 Beat markers UI (Phase 10)**
  * [ ] Analyse on import with progress
  * [ ] Draw markers on the timeline
  * [ ] Density selector: Every / Every 2 / Every 4 / **Major**
  * [ ] Tap to delete, drag to correct any marker
  * [ ] Clear all
  * [ ] Show the confidence score
  * [ ] Markers are `Project.markers` — undoable
  * [ ] Benchmark: ≤ 3 s for a 4-minute track; never blocks the UI

* [ ] **10.5 SnapEngine integration**
  * [ ] Beat markers are snap candidates (alongside clip edges and playhead)
  * [ ] Snapping respects zoom-independent pixel threshold
  * [ ] Unit tests: beat snapping at multiple zooms

* [ ] **10.6 SYNC TO BEAT (Phase 11)**
  * [ ] `data/audio/BeatSyncPlanner.kt` — **pure**: `(clips, BeatMap, settings) → List<ClipPlacement>`
  * [ ] `BeatSyncSettings` — beat frequency, **sync strength**, transition style, **motion strength**
  * [ ] Strength ∈ [0,1] interpolates each clip's start between its current position (0) and the nearest beat (1)
  * [ ] Operates on photos, videos, effects and transitions
  * [ ] `feature/beat/BeatSyncSheet.kt` — settings UI with Apply / Preview / Cancel
  * [ ] Output is **one undo step** and fully editable
  * [ ] Unit tests: every clip lands on a beat; order preserved; gaps ≥ 0; strength 0 is a no-op; reversible
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 11 — Transitions, AUTO, templates

* [ ] **11.1 Typed transitions**
  * [ ] `TransitionType` **enum** — replaces the 44-name stringly-typed set and the `smoothleft→coverleft` alias hack
  * [ ] `TransitionCatalog` (`assets/catalogs/transitions.json`) — data-driven so it can be updated without a code change
  * [ ] `@SerializedName` mapping so old string values still load
  * [ ] **Log a warning when a name is rejected** (P3-26)
  * [ ] Unit tests: every `TransitionType` maps to a valid `xfade` name or a custom chain

* [ ] **11.2 Transition groups (Phase 13)**
  * [ ] **Basic:** Cut, Fade, Dissolve
  * [ ] **Dynamic:** Zoom, Swipe, Push, Spin, Blur, Flash, Glitch
  * [ ] **Meme:** Shake, Impact, Flash, Zoom
  * [ ] Implement the non-`xfade` ones: `blur` (`boxblur`), `glitch` (`rgbashift`+`noise`), `spin` (`rotate`), `shake` (time-parameterised `crop`/`rotate` offsets)
  * [ ] Editable duration and alignment (centre/slow/fast)
  * [ ] Preview the actual transition in the editor

* [ ] **11.3 `AUTO` transitions**
  * [ ] `AutoTransitionPlanner` — pure; applies a transition across every cut in a selection (or the whole project)
  * [ ] Optional alternation between two transitions
  * [ ] Unit tests: correct for all selection shapes (empty, single, contiguous, disjoint)

* [ ] **11.4 `xfade` safety**
  * [ ] `xfade` requires overlapping inputs and has a duration bound relative to the shortest input
  * [ ] **Validate at plan time** and report a clear error rather than failing at export
  * [ ] Cap consecutive `xfade` gaps; fall back to `concat` beyond the cap; warn the user
  * [ ] Test: an unsatisfiable transition duration produces a clear message, not a failed export

* [ ] **11.5 Templates (Phase 18)**
  * [ ] `Template` + `TemplateCatalog` (`assets/catalogs/templates.json`)
  * [ ] A template is a parameterised timeline fragment (clip slots + effects + transitions)
  * [ ] Applying a template materialises **normal editable entities**
  * [ ] User-created templates
  * [ ] Unit tests: apply to an empty project; slot filling; round-trip
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 12 — Photo slideshow

* [ ] **12.1 `SlideshowPlanner` (pure)**
  * [ ] Input: photos + videos + optional music + style
  * [ ] Output: media-track clips, per-item durations, transitions, photo motion
  * [ ] `SlideshowStyle` — **Cinematic, Fast, Chill, Meme, Minimal, Beat**
  * [ ] Styles as data (`assets/catalogs/slideshow_styles.json`)
  * [ ] Unit tests: per style — item count, durations, transition placement, motion params, total duration, determinism

* [ ] **12.2 Arrangement**
  * [ ] Auto-arrange photos and videos into a sequence
  * [ ] Per-item duration (editable after generation)
  * [ ] Mix photos and videos
  * [ ] **Sync to music** when a `BeatMap` is available

* [ ] **12.3 Photo motion (Ken Burns)**
  * [ ] `PhotoMotion` — pan/zoom per item
  * [ ] Rendered via time-parameterised `scale`/`crop`/`overlay` expressions
  * [ ] Golden tests on the generated expression strings
  * [ ] Visual spot-checks on device

* [ ] **12.4 UI**
  * [ ] `CREATE SLIDESHOW` — one sheet: pick media, pick music, pick style, set duration, Generate
  * [ ] Result is **fully editable** on the normal timeline
  * [ ] **Acceptance:** all 6 styles generate a playable slideshow that stays editable
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 13 — Export, sharing, share stack

* [ ] **13.1 `ExportSpec` and presets**
  * [ ] `engine/model/ExportSpec.kt` — width, height, fps, bitrate, codec, container, audio rate
  * [ ] `ExportPreset` — **Original, Instagram Reel, YouTube Short, YouTube Video, Snapchat, Custom**
  * [ ] Presets as data (`assets/catalogs/export_presets.json`) so platform limits can change without a code change
  * [ ] Sensible auto-selection
  * [ ] `ExportSpec` **replaces the never-consumed `VideoProject.ExportConfig`** (P3-29)
  * [ ] Unit tests: preset → `ExportSpec` mapping for all 6

* [ ] **13.2 Export quality fixes from the audit**
  * [ ] `-movflags +faststart` (P3-18)
  * [ ] No-upscale guard `scale=-2:min(<h>,ih)` (P3-19)
  * [ ] One documented rate-control policy across both encoder branches (P3-17) — the software path currently has **no** rate control
  * [ ] Surface the hardware/software quality difference in the UI
  * [ ] Progress computed from the accumulated `StreamInfo.durationSec` so it **reaches 100%** (P3-21)

* [ ] **13.3 `ExportPipeline` (staged)**
  * [ ] Stage 0 Proxy → 1 Conform → 2 Composite per-track → 3 Composite transitions → 4 Overlay → 5 Audio → 6 Encode → 7 Publish
  * [ ] Per-stage progress
  * [ ] Intermediate caching (skip stages 1–3 when only 4/5 inputs changed)
  * [ ] **Originals are never modified** — verify with before/after checksums
  * [ ] Guaranteed temp cleanup in `finally`
  * [ ] `IS_PENDING` on MediaStore publish (P0-6)
  * [ ] Keep `LegacyFilterGraphCompiler` so existing `.lcprj` files still export

* [ ] **13.4 `ProgressBus`**
  * [ ] Replace the Activity-registered `BroadcastReceiver` with a `StateFlow`
  * [ ] Export survives Activity recreation and is observable in tests
  * [ ] Export survives process death (P1-10): persist the export job; re-attach rather than restart

* [ ] **13.5 Export UI**
  * [ ] Preset chooser
  * [ ] Live estimated size and estimated time
  * [ ] Advanced expands codec/bitrate/fps/audio
  * [ ] Progress with stage names
  * [ ] Cancel that actually cancels (P1-8)

* [ ] **13.6 Sharing (Phase 16)**
  * [ ] Add `FileProvider`
  * [ ] `ACTION_SEND` with `video/*` and a `content://` grant — **no video share intent exists today**
  * [ ] Targets: Instagram, Snapchat, YouTube, WhatsApp, Android share sheet
  * [ ] **Use only documented platform mechanisms.** Detect absence via `PackageManager`; fall back to the share sheet transparently
  * [ ] **Do not fake direct publishing APIs**
  * [ ] Unit tests: target resolution; fallback when the app is absent
  * [ ] Instrumentation: `FileProvider` grants resolve; the share intent carries a readable URI

* [ ] **13.7 Share stack (Phase 17)**
  * [ ] `ShareStackPlanner` (new) — targets → distinct `ExportSpec`s → **dedupe by spec hash**
  * [ ] **Avoid duplicate encoding**: 3 targets at the same spec encode **once**
  * [ ] `ShareReadinessRow` — `Instagram ✓`, `Snapchat ✓`, `YouTube ✓` with Preparing states
  * [ ] Unit tests: dedupe correctness (3 identical specs → 1 encode)
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 14 — Keyframes (complete the set)

* [ ] **14.1 Honour `interpolationType` (fixes B10)**
  * [ ] `core/math/Easing.kt` drives the compiler — the field is currently dead
  * [ ] Easing set: linear, ease-in/out/in-out, hold/step, elastic, bounce, back
  * [ ] Unit tests: each easing's value table

* [ ] **14.2 `KeyframeCompiler`**
  * [ ] Replace `buildFFmpegInterpolationExpr`
  * [ ] Emit nested `if(lt(t,…))` with easing applied per segment
  * [ ] **Cap and decimate** keyframes; bound nesting depth (P3-23)
  * [ ] Warn when decimating
  * [ ] Golden tests on emitted expressions; max-expression-size test

* [ ] **14.3 Channels**
  * [ ] Existing: position, opacity, speed, mask
  * [ ] Add: **scale**, **rotation**
  * [ ] Add/remove at playhead
  * [ ] Per-channel default value

* [ ] **14.4 `KeyframeCurveView`**
  * [ ] Draw the curve with keyframe handles
  * [ ] Easing picker
  * [ ] Channel selector
  * [ ] Allocation-free
  * [ ] Accessibility: keyframes as virtual nodes
  * [ ] **Acceptance:** all 6+ channels animate; easing visibly differs from linear; export matches preview
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 15 — Performance optimisation

* [ ] **15.1 Replace deprecated APIs**
  * [ ] Delete the `isDrawingCacheEnabled` / `buildDrawingCache` / `getDrawingCache` usage (`DraggableImageOverlayView.kt:559-604`) (B3)
  * [ ] Replace deprecated `android.graphics.Movie` GIF decoding (15 sites) with Media3/Coil + a frame budget (B4)
  * [ ] Fix deprecated `clipPath(Path, Region.Op)` (4 sites) — hardware-accelerated behaviour differs; verify mask accuracy
  * [ ] Add `coil` + `coil-video`

* [ ] **15.2 Memory**
  * [ ] No full-resolution bitmap retention
  * [ ] `Bitmap` pooling + `inBitmap` decode options
  * [ ] Respond to `onTrimMemory`
  * [ ] No Activity-held `Context` in long-lived objects
  * [ ] Target < 180 MB typical; no OOM at 4 GB

* [ ] **15.3 Main thread**
  * [ ] Audit for main-thread I/O across the whole app
  * [ ] `Dispatchers.IO` vs `Dispatchers.Default` split (I/O vs CPU)
  * [ ] No ANR on any flow

* [ ] **15.4 FFmpeg**
  * [ ] Cap concurrent FFmpeg processes at 2
  * [ ] Add `-preset`/`-crf` control for `libx264` (default `medium` is slow on mid-range)
  * [ ] `geq` (mask shapes) is notoriously slow — benchmark and consider a cheaper formulation
  * [ ] Cap filter-graph size

* [ ] **15.5 Macrobenchmark**
  * [ ] `androidx.benchmark:benchmark-macro`
  * [ ] Timeline frame timing @ 200 clips
  * [ ] Preview first-frame latency
  * [ ] Memory
  * [ ] Export throughput
  * [ ] **Measured on a physical mid-range device**, not an emulator

* [ ] **15.6 Verify §7 budgets**
  * [ ] Timeline: 60 fps @ 200 clips, < 16 ms
  * [ ] Thumbnails: 200 < 8 s, < 24 MB
  * [ ] Waveform: 4 min < 1.5 s cold, < 20 ms cached
  * [ ] Beat analysis: ≤ 3 s for 4 min
  * [ ] Preview: first frame < 150 ms
  * [ ] Export: ≥ 1× realtime on hardware encode
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 16 — Accessibility

* [ ] **16.1 Custom view semantics**
  * [ ] `contentDescription` on every interactive custom view (fixes B11)
  * [ ] `TimelineAccessibilityDelegate` — clips as virtual nodes with bounds
  * [ ] `ClipAccessibilityNodeProvider` — actions: extend, trim, delete, split, move
  * [ ] Overlay accessibility actions: move, resize, rotate, delete
  * [ ] Keyframes as virtual nodes
  * [ ] Reorder overlay views by z for TalkBack

* [ ] **16.2 Touch targets & scaling**
  * [ ] 48 dp minimum on every interactive element
  * [ ] Font scaling honoured in all layouts
  * [ ] Test at 200% font scale

* [ ] **16.3 Keyboard / switch access**
  * [ ] D-pad navigation of the timeline
  * [ ] Full editing without touch precision

* [ ] **16.4 Announcements**
  * [ ] `Announcer` + live regions for undo, export progress, errors
  * [ ] Respect `ANIMATOR_DURATION_SCALE == 0`

* [ ] **16.5 Verification**
  * [ ] Instrumentation test: a clip can be selected, trimmed and deleted using **only** accessibility actions
  * [ ] Automated accessibility scanner reports no critical issues on editor screens
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 17 — Error handling

* [ ] **17.1 `AppError`**
  * [ ] `core/result/AppError.kt` — Media / Decode / FFmpeg / Permission / Storage / State
  * [ ] Map every failure site to a typed error

* [ ] **17.2 Fix the lossy error mapping (P3-22)**
  * [ ] Gallery-save failures → `LC-202`, **not** `LC-101` (FFmpeg actually succeeded)
  * [ ] Wire the currently-unreachable codes: `LC-102`, `LC-201`, `LC-301`, `LC-500`
  * [ ] Wire `LC-301` to the real `OutOfMemoryError` catches (P3-25)
  * [ ] Unit tests: the full mapping table

* [ ] **17.3 Fail fast at plan time**
  * [ ] Validate at plan time what currently only fails at export: unsatisfiable `xfade` duration, missing font, unsupported codec, missing media, filter-graph size
  * [ ] Never discover a problem after a 20-minute render

* [ ] **17.4 `CleanupScope`**
  * [ ] Temp files cleaned in `finally` on every path (P1-11)
  * [ ] Per-export temp file set
  * [ ] Test: induced FFmpeg failure → clean error + **zero** leaked temp files

* [ ] **17.5 User-facing messages**
  * [ ] Say what to do next; keep technical detail in the shareable log
  * [ ] Do **not** dump the full FFmpeg command (with local paths and user text) into a dialog
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 18 — Testing

* [ ] **18.1 Test dependencies**
  * [ ] Add `kotlinx-coroutines-test`, `app.cash.turbine`, `io.mockk:mockk`, `org.robolectric:robolectric`, `com.google.truth:truth`
  * [ ] Add `androidx.benchmark:benchmark-macro`
  * [ ] Add JaCoCo
  * [ ] Add detekt

* [ ] **18.2 Unit tests (JVM)**
  * [ ] Delete/replace `ExampleUnitTest` (`assertEquals(4, 2+2)`)
  * [ ] `TimelineLayoutEngine`, `SnapEngine`
  * [ ] `EasingFunction`, `KeyframeCompiler`
  * [ ] `EffectCompiler`, `EffectStackCatalog`
  * [ ] `FFmpegEscaper` (exhaustive character table), `FFmpegCommandBuilder`
  * [ ] `BeatTracker` (synthetic + fixtures), `BeatMap.grid`
  * [ ] `WaveformCache`, `WaveformExtractor`
  * [ ] `LutLoader`
  * [ ] `SlideshowPlanner`, `BeatSyncPlanner`, `AutoTransitionPlanner`
  * [ ] `ShareStackPlanner`, `ExportPreset`
  * [ ] `ProjectCodec` round-trip, `ProjectMigrator` (upstream `.lcprj` fixtures)
  * [ ] `PresetCatalog` validation
  * [ ] `EditOp` invertibility
  * [ ] `AppError` mapping

* [ ] **18.3 Integration (Robolectric)**
  * [ ] `ProjectStore` atomic write + crash recovery
  * [ ] `ExportService` lifecycle
  * [ ] `MediaStore` interactions
  * [ ] `EditorStore` + `HistoryManager` sequences

* [ ] **18.4 UI / instrumentation**
  * [ ] Replace `ExampleInstrumentedTest` (asserts the package name — update for the namespace move)
  * [ ] Tool open/close flows
  * [ ] Timeline gestures
  * [ ] Export to gallery
  * [ ] Share intent
  * [ ] Permission flows
  * [ ] TalkBack-only editing

* [ ] **18.5 Rendering tests**
  * [ ] Golden strings for `drawtext` / `overlay` / `xfade` / `crop` / masks
  * [ ] Preview↔export visual parity: text position, size, colour, rotation, masks

* [ ] **18.6 Export tests**
  * [ ] 10 s project → playable MP4
  * [ ] Assert container, dimensions, fps, duration, audio presence
  * [ ] Induce a failure → clean error, no temp leakage

* [ ] **18.7 Project save/load**
  * [ ] Round-trip equality
  * [ ] Migration from **every** historical `schemaVersion`
  * [ ] Corrupt/truncated file → quarantined, user informed, no crash
  * [ ] Missing media file → precise per-clip error

* [ ] **18.8 Performance tests**
  * [ ] Macrobenchmark suite per §7

* [ ] **18.9 CI**
  * [ ] Extend `ci.yml`: `assembleDebug`, `lintDebug`, `testDebugUnitTest`, **`connectedDebugAndroidTest`** on an emulator, detekt
  * [ ] JaCoCo coverage floor 60% for `core`+`data`; fail on regression
  * [ ] Upload all reports as artifacts
  * [ ] Keep Java 17
  * [ ] Macrobenchmark on a scheduled job
  * [ ] `dependabot.yml` for Gradle + actions
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 19 — Licensing & assets

* [ ] **19.1 Project licence**
  * [ ] Keep MIT; add Mhirex attribution **cumulatively**
  * [ ] `NOTICE` credits Tharun Birla (MIT) + Mhirex
  * [ ] Verify no existing copyright header was altered
  * [ ] About screen shows both

* [ ] **19.2 Dependency licences**
  * [ ] Add the **ffmpeg-kit GPL-3.0** entry to AboutLibraries (currently missing)
  * [ ] Verify Media3, Material, Coil, Lottie, Gson, coroutines, kotlinx-collections-immutable attribution
  * [ ] Bump the ffmpeg pin `2.1.0` → `2.2.1`; vendor with a recorded SHA-256

* [ ] **19.3 GPL-3.0 compliance (the real gap)**
  * [ ] Publish a corresponding-source offer for the combined work
  * [ ] Ship the GPL-3.0 text in-app
  * [ ] **Evaluate the LGPL downgrade** — verify the required filter set works on an LGPL build (`ffmpeg-kit-min-gpl`); downgrade if it passes. **Highest-value licensing action.**
  * [ ] Record the outcome in PLAN §5.3

* [ ] **19.4 `ASSETS.md`**
  * [ ] Record origin + licence for **every** bundled asset
  * [ ] `trans_preview_*.webp` — establish provenance or regenerate
  * [ ] `filter_preview_*.jpg` — establish provenance or regenerate
  * [ ] `Roboto-Regular.ttf` — record Apache-2.0
  * [ ] `res/raw/film.json` — identify and document
  * [ ] Launcher icons, banner, fastlane screenshots — upstream author's work, retain attribution
  * [ ] Ship licence files in `assets/licenses/`
  * [ ] **Release blocker:** any asset with unestablished provenance is regenerated or removed

* [ ] **19.5 New assets**
  * [ ] SFX — synthesised in code (§9.1); no sampled assets
  * [ ] Music — **nothing bundled**; document the `MusicPackManifest` format only
  * [ ] Stickers — system emoji + user-supplied; **no third-party sticker art initially**
  * [ ] Fonts — system + Roboto + user-imported via SAF; **no commercial display fonts**
  * [ ] Filter LUTs — 8 existing + user `.cube`; no commercial packs
  * [ ] Icons — Material Symbols (Apache-2.0) or hand-authored vectors

* [ ] **19.6 Prohibitions check**
  * [ ] No copyrighted commercial music, SFX, fonts, stickers, or meme imagery
  * [ ] No proprietary UI/branding/trade dress copied
  * [ ] No faked platform publishing APIs
  * [ ] No AI code path shipped
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 20 — Optional AI seams (document only, do not build)

* [ ] **20.1 Document the seams**
  * [ ] `RemoveSilenceTool` → emits trims as `EditOp`s
  * [ ] `AutoCaptionTool` → emits `TextEffect`s; SRT parsing exists
  * [ ] `SmartClipSelectionTool` → emits clip removals
  * [ ] Confirm each needs no change to the core model (this is the point of AD-15)

* [ ] **20.2 Verify 1.x ships clean**
  * [ ] No ML runtime dependency
  * [ ] No network permission
  * [ ] No AI code path
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 21 — Final polish

* [ ] **21.1 Onboarding**
  * [ ] Rebrand the welcome dialog
  * [ ] Add a first-run beat-sync / meme-pack hint (the product differentiator)

* [ ] **21.2 Settings**
  * [ ] Default export preset
  * [ ] Storage usage + clear cache (waveform/thumbnail/proxy caches)
  * [ ] Keep existing: folders, language, fullscreen, encoder, haptics
  * [ ] Rebrand the "Translate LibreCuts" entry

* [ ] **21.3 Home / project browser**
  * [ ] Use the new `ProjectStore.list()`
  * [ ] Show project name, duration, thumbnail, modified time
  * [ ] Reopen, duplicate, delete, rename
  * [ ] Quarantined/corrupt projects surfaced clearly

* [ ] **21.4 Empty and error states**
  * [ ] Timeline empty, no media, missing media, unsupported format
  * [ ] Every state has a clear next action

* [ ] **21.5 Final visual polish**
  * [ ] Consistent spacing/rhythm across all tools
  * [ ] Motion that is fast and non-distracting
  * [ ] Consistent iconography
  * [ ] Screenshot every screen

* [ ] **21.6 Documentation**
  * [ ] `README.md` — Mhirex, features, build instructions (**including the JDK 17 requirement**)
  * [ ] `CONTRIBUTING.md`
  * [ ] `ARCHITECTURE.md` — the layer diagram and the "UI never calls FFmpeg" rule
  * [ ] `LICENSE_AUDIT.md` — dependency licences and obligations
  * [ ] CHANGELOG
  * [ ] **Notes:** *(record decisions here)*

---

## Phase 22 — Quality control gate

> Nothing ships until every box below is **executed and verified**, not assumed.

* [ ] **22.1 Build & test**
  * [ ] `clean assembleDebug` from scratch
  * [ ] `assembleRelease`
  * [ ] Full unit suite green
  * [ ] Instrumented tests green on an emulator
  * [ ] detekt + lint clean
  * [ ] Coverage floor met

* [ ] **22.2 Functional verification** *(PLAN §11)*
  * [ ] Create project
  * [ ] Reopen project
  * [ ] Reopen a **legacy LibreCuts `.lcprj`**
  * [ ] Recover from an interrupted save
  * [ ] Import video / photo / audio
  * [ ] Timeline: drag, trim, split, delete, reorder, zoom, multi-select, snap
  * [ ] Text: all controls, meme presets, animation, keyframes
  * [ ] Effects: stack, order, intensity, custom LUT
  * [ ] Meme packs: DEAD PACK applies and is fully editable
  * [ ] SFX: all categories, auto-attach, move/delete-with-parent
  * [ ] Music: multi-track, waveform, fades, ducking, voice-over
  * [ ] Beat detection: accuracy on fixtures
  * [ ] Beat sync: all 4 density modes, undoable
  * [ ] Slideshow: all 6 styles
  * [ ] Transitions: all 3 groups + AUTO
  * [ ] Export: all 6 presets
  * [ ] Sharing: with and without target apps installed
  * [ ] Share stack: dedupe verified
  * [ ] Undo/redo: all operations; survives process death

* [ ] **22.3 P0/P1 regression guards**
  * [ ] No-edit save on Android 10+
  * [ ] Text and subtitles render in the export
  * [ ] Speed + reverse merge timing
  * [ ] `content://` overlay typing
  * [ ] Interrupted export leaves no truncated file
  * [ ] Audio-only is a real MP3 or refused
  * [ ] Cancel does not start a new render
  * [ ] Cancel does not kill proxy generation
  * [ ] Progress reaches 100% with transitions
  * [ ] 480p is never upscaled to 2160p
  * [ ] Hostile `.lcprj` text fields export safely
  * [ ] No temp files leak after induced failure

* [ ] **22.4 Non-regression**
  * [ ] Originals never modified (checksum before/after)
  * [ ] No leaked FFmpeg processes during editing
  * [ ] No ANR on any flow
  * [ ] No leaked temp files after any operation

* [ ] **22.5 Hardware**
  * [ ] Tested on **physical mid-range Android hardware**
  * [ ] All §7 performance budgets met, measured

* [ ] **22.6 Legal**
  * [ ] `ASSETS.md` complete
  * [ ] Licence compliance signed off
  * [ ] GPL-3.0 corresponding-source offer published
  * [ ] LGPL downgrade evaluated and the outcome recorded
  * [ ] **Notes:** *(record decisions here)*

---

## Appendix A — Defect traceability

| Defect | Phase | Task |
|---|---|---|
| B1 preview FFmpeg + leaked processes | 4 | 4.4 |
| B2 silent `MuteAudio` substitution | 2 | 2.4 |
| B3 legacy drawing cache | 15 | 15.1 |
| B4 deprecated `android.graphics.Movie` | 15 | 15.1 |
| B5 EOL ExoPlayer | 4 | 4.1 |
| B6 zero real tests | 18 | 18.2+ |
| B7 no multi-select | 5 | 5.5 |
| B8 always-true branches | 0 | 0.15 |
| B9 deprecated Intent/clip APIs | 0 | 0.15 |
| B10 dead `interpolationType` | 14 | 14.1 |
| B11 accessibility gap | 16 | 16.1 |
| B12 dead code | 0 | 0.4 |
| B13 uncached waveforms | 6 | 6.1 |
| P0-1 no-op export API 29+ | 0 | 0.5 |
| P0-2 audio-only as mp3 | 0 | 0.6 |
| P0-3 speed+reverse duration | 0 | 0.7 |
| P0-4 hardcoded `.mp4` | 0 | 0.8 |
| P0-5 font alias not path | 0 | 0.9 |
| P0-6 no `IS_PENDING` | 0 | 0.10 |
| P1-7 export ANR | 0 | 0.11 |
| P1-8 cancel treated as failure | 0 | 0.12 |
| P1-9 session leak | 0 | 0.13 |
| P1-10 no process-death survival | 13 | 13.4 |
| P1-11 temp leak on failure | 0 | 0.14 |
| P1-12 global cancel | 0 | 0.14 |
| P1-13 separate engine instances | 0 | 0.14 |
| P3-17 no libx264 rate control | 13 | 13.2 |
| P3-18 no `+faststart` | 13 | 13.2 |
| P3-19 no no-upscale guard | 13 | 13.2 |
| P3-20 blind retry replace | 13 | 13.2 |
| P3-21 progress < 100% | 13 | 13.2 |
| P3-22 all failures LC-101 | 17 | 17.2 |
| P3-23 uncapped keyframe nesting | 14 | 14.2 |
| P3-24 subtitle colour ignored | 7 | 7.7 |
| P3-25 whole-file PCM read | 6 | 6.1 |
| P3-26 silent transition fallback | 11 | 11.1 |
| P3-27 global mute/removeOriginalAudio | 6 | 6.4 |
| P3-28 no loudnorm/alimiter | 6 | 6.3 |
| P3-29 unused `ExportConfig` | 13 | 13.1 |
| SEC-30 filtergraph injection | 7 | 7.3 |
| SEC-31 `WRITE_EXTERNAL_STORAGE` | 0 | 0.15 |
| SEC-32 global log callback | 15 | 15.1 |
| L1 GPL-3.0 in an MIT app | 19 | 19.3 |
| L2 ffmpeg-kit retired | 19 | 19.2/19.3 |
| L3 attribution must be kept | 0 | 0.16 |

## Appendix B — Deferred, deliberately

| Item | Why |
|---|---|
| Jetpack Compose | 60 existing layouts; Canvas-heavy custom views; not required by any feature (AD-5) |
| Gradle multi-module | Modularity enforced by packages + an architecture test instead (AD-1) |
| `applicationId` change | Orphans user data; deferred to Mhirex 2.0 with a data-import step (AD-2) |
| `media3-transformer` export | ffmpeg pipeline is good enough for 1.x; valuable if ffmpeg is retired |
| ProtoBuf project format | Deeply nested model; JSON is human-debuggable for an OSS project (AD-9) |
| AI tools | Not the product identity; mid-range devices cannot afford it (AD-15) |
| HEVC / HDR | Platform support is inconsistent; not required by any feature |
| Multi-device sync | Out of scope; the app is local-only and private by design |
| Additional locales | Inherit the existing 17; expand on community demand |
| Bundled music | Copyright (AD-14) |
| Third-party sticker art | Licensing; system emoji + user-supplied instead |
