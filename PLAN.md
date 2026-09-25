# Mhirex — Technical Implementation Plan

**Repository:** fork of [tharunbirla/LibreCuts](https://github.com/tharunbirla/LibreCuts)
**Audited at commit:** `a510390` (upstream last commit 2026-09-12)
**Target product:** Mhirex — a fast, mobile-first, open-source creator video editor for Android
**Document status:** authoritative. Updated whenever architecture changes during implementation.

---

## 0. Repository Audit (Phase 0 findings)

### 0.1 Baseline verification

| Check | Result |
|---|---|
| `local.properties` | **Missing** in repo — created, points at `~/Library/Android/sdk` |
| Android SDK | platform 34 ✅, build-tools 34.0.0 ✅ |
| System JDK | **Java 25 — incompatible** with AGP 8.7.1 / Gradle 8.9 |
| Working JDK | Homebrew `openjdk@17` (17.0.20.1) at `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` |
| JVM networking | **IPv6 route broken on this machine.** Java hangs on connect to `services.gradle.org` / `release-assets.githubusercontent.com`. `curl` unaffected. Fixed with `-Djava.net.preferIPv4Stack=true` |
| `./gradlew :app:compileDebugKotlin` | ✅ **BUILD SUCCESSFUL** in 3m 34s |

The IPv4 fix was written to **`~/.gradle/gradle.properties` (user-level, intentionally not committed)** because it is a property of this machine, not of the project. Every build in this session must use:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"
./gradlew <task>
```

`gradle/wrapper/gradle-wrapper.properties` was changed from `networkTimeout=10000` to `180000`. That change is a genuine improvement and is kept.

### 0.2 Project shape

| Property | Value |
|---|---|
| Modules | 1 (`:app`) — 20,945 lines of Kotlin/Java across 48 files |
| AGP / Gradle / Kotlin | 8.7.1 / 8.9 / 2.0.21 |
| SDK | `compileSdk 34`, `minSdk 26`, `targetSdk 34` |
| UI toolkit | **XML layouts + ViewBinding. No Jetpack Compose.** 60 layout files |
| JVM target | 17 |
| ABIs | `armeabi-v7a`, `arm64-v8a`, `x86_64` (no x86_64 host binary; Apple-Silicon emulators use `arm64-v8a`) |
| Release build | `minifyEnabled false`, `proguard-rules.pro` is 100% boilerplate comments |
| Test deps | JUnit4 + Espresso + `androidx.test.ext:junit` only. **No Robolectric, MockK, coroutines-test, Turbine, or Truth** |
| Coroutines | Used heavily but **not declared** — arrives transitively via `lifecycle-viewmodel-ktx` |
| CI | `ci.yml`: Java 17, `assembleDebug`, `lintDebug`, `testDebugUnitTest`, APK + lint artifacts, PR/issue commenting. **No instrumented tests, no static analysis, no coverage** |
| License | MIT (© 2024 Tharun Birla) |
| i18n | 17 locales, Weblate-managed |

### 0.3 Existing architecture

```
MainActivity (648)          home / project entry / settings
  └── VideoEditingActivity (9012 lines, 154 functions)   ← God object
        ├── VideoEditingViewModel (1757)   state + FFmpeg filter-graph builder
        ├── VideoEditingViewModelExt (436) command wrappers
        ├── FFmpegRenderEngine (744)       ffmpeg-kit wrapper, ~20 operations
        ├── ExportService (272)           foreground service, BroadcastReceiver progress
        ├── ProxyGenerationService (152)   speed/scrub/reverse proxy generation
        ├── AudioAnalyzer (116)            naive energy onset detection
        ├── AudioWaveformExtractor (87)    ffmpeg PCM → amplitude buckets
        ├── ProjectSerializer (144)        Gson, hand-rolled polymorphism
        ├── EditCommand (122)             7 command classes
        ├── EditOperation (489)            sealed class, 18 subtypes
        ├── VideoProject (159)            sourceUri + List<EditOperation>
        └── 17 custom views (customviews/)
```

**Core data model (the single most important fact about this codebase):**

```kotlin
data class VideoProject(
    val sourceUri: Uri,                 // ONE primary video
    val sourceName: String,
    val scrubProxyUri: Uri? = null,
    val operations: List<EditOperation> = emptyList(),   // flat, unordered-ish list
    ...
)
```

There is **no track model**. "Timeline" is a single linear sequence produced by one `EditOperation.Merge(items = List<MergeItem>)` appended to the primary video. Every other feature (text, audio, overlays, transitions, filters) is a `EditOperation` in one flat list. Time is expressed ad-hoc per operation as `startTimeMs`/`endTimeMs` nullable Longs.

**Undo/redo** uses whole-project snapshots:
```kotlin
data class HistoryState(val project: VideoProject, val commandDescription: String)
// capped at MAX_UNDO_STACK_SIZE = 30
```

**Rendering** is a single monolithic `filter_complex` assembled in `performRenderTrack()` (`VideoEditingActivity.kt:5892–6588`, **~700 lines in one function**), plus the parallel builder in `VideoEditingViewModel.kt`.

### 0.4 What already works — REUSE

These are genuinely good and must not be rewritten:

1. **FFmpeg filter-graph composition approach.** Building one `filter_complex` and encoding once is correct and much faster than multi-pass. Keep.
2. **`xfade` + `acrossfade` transition chaining** (`VideoEditingViewModel.kt:1035–1130`). Correctly validates against a 44-entry `validXfadeTransitions` set, chains per-gap with offset arithmetic, and pairs video crossfade with audio crossfade. This is high-quality work.
3. **Audio ducking is real** — `sidechaincompress=threshold=0.03:ratio=4:attack=5:release=500` (`:1254`, `:1480`), with `amix` and per-track `adelay`/`volume`. Keep and extend.
4. **Hardware encode with software fallback** — `h264_mediacodec` → `libx264`.
5. **Proxy generation** — `generateSpeedProxy`, `generateScrubProxy`, `reverseVideo`. Real, effective performance work for slow devices.
6. **Frame extraction throttling** — `Semaphore(2)` + `LruCache<Uri, List<Bitmap>>(15)`.
7. **Non-destructive project model** — media is referenced by URI; original files are never modified. Keep this property absolutely.
8. **Keyframe → FFmpeg expression compiler** — `buildFFmpegInterpolationExpr` emits nested `if(lt(t,...))` expressions from `KeyframePoint` lists. Sound design.
9. **Mask shape expressions** — `buildFFmpegMaskAlphaExpr` covers RECTANGLE/ELLIPSE/SPLIT/SHUTTER/HEART/STAR with rotation and feather.
10. **Export diagnostics** — `createDiagnosticReport` + `ErrorDisplayActivity` + user-shareable logs. Good failure UX.
11. **AboutLibraries** integration for third-party license attribution.
12. **Material 3 dark theme with a real token set** (OLED black `#000000`, "Electric Pink" `#FF2A6D`, surface/outline/active-tool tokens). Solid base for redesign.
13. **17 existing translations** — substantial asset to inherit.

### 0.5 What is partially implemented

| Feature | State | Gap |
|---|---|---|
| Timeline | Drag, trim handles, split, delete, reorder, snapping, 3 discrete zoom levels (`ZoomMode.FIT/MEDIUM/PRECISION`) | No continuous pinch-zoom, no multi-select, no ripple, no magnetic timeline to beats (beats *are* snap targets, `:7985`), no frame-precision scrub |
| Text | Size, color, border, align, position, opacity, 2 keyframe channels, 11 positions, custom font import | **No shadow, no background box, no text animation, no stroke-as-outline, no presets, no vertical text, no text-on-path** |
| Transitions | 20 exposed in UI, 44 supported by the filter builder | No `AUTO` mode, no per-transition parameter tuning, no preview of the actual transition |
| Stickers/Overlays | Image/GIF/video overlays with chroma-key, masks, keyframes, layer reorder | No built-in sticker library, no meme packs, no effect stacks |
| Audio | Multi-track `AddBackgroundAudio`, trim, volume, fade, ducking, voice-over, extract, mute, 200% boost | **No SFX system**, no waveform in the timeline, no beat modes |
| Waveforms | `AudioWaveformExtractor` produces amplitude buckets | **Never cached** — re-extracted via ffmpeg on all 4 call sites (`:3624`, `:6171`, `:6506`, `:6712`) |
| Beat detection | `AudioAnalyzer.detectBeats` | See §0.6 — not a beat tracker |
| Filters | 8 LUT presets with preview stills | No custom LUT import, no intensity slider |
| Adjust | 9 parameters (brightness/contrast/warmth/shadow/highlights/saturation/exposure/sharpen/vignette) | No presets, no per-clip intensity, no stack |
| Keyframes | Position/opacity/speed/mask channels | `interpolationType` field exists but **is never honoured** — linear only. No easing library |
| Undo/redo | Works | Snapshot-based, 30 cap, no coalescing, no grouping |
| Export | 720/1080/1440/2160 + audio-only + 3 quality tiers | **No platform presets** (Reel/Shorts/Snapchat), no HEVC, no HDR |
| Sharing | Save to gallery via MediaStore | **No video share intent anywhere in the app** (only error-log sharing) |
| Crop | 16:9 / 9:16 / 1:1 / Custom | No rotation, no straightening, no 2-point crop |
| Canvas | COLOR / IMAGE / BLUR | Fine as-is |
| Subtitles | SRT import, positioning, font, styling | No burn-in toggle, no auto-split, no export as SRT |
| Speed | Per-segment with proxy regeneration | No curve/ramp, no freeze-duration control |
| Snapshots | Frame capture to gallery | Fine |
| Onboarding | Welcome dialog | Needs rebrand + restructure |
| Settings | Export/audio/snapshot folders, language, fullscreen, encoder, haptics | No export defaults, no storage usage, no cache management |

### 0.6 What is broken — MUST FIX

**B1 — Preview architecture is fundamentally unusable (critical).**
`renderSegmentedPreview()` (`:7556–7606`) runs a **complete FFmpeg encode of the entire sequence** into a new cache file (`preview_segment_${System.currentTimeMillis()}.mp4`) on every edit, then swaps the player source. Worse:

```kotlin
previewJob?.cancel()                          // :7559
...
val result = withContext(Dispatchers.IO) {    // :7581
    ffmpegEngine.executeCommand(cmd)          // FFmpegKit.executeAsync — not Job-bound
}
```
`Dispatchers.IO` cancellation does **not** interrupt blocking native work, and `FFmpegKit.executeAsync` is not tied to the coroutine's `Job`. Rapid slider changes therefore **leak concurrent FFmpeg processes**. This is the single biggest reason the editor feels slow, and it blocks mid-range devices entirely.

**B2 — Silent project corruption on load.**
`ProjectSerializer.deserialize` (`:106–116`) replaces any unknown or unparseable operation with `EditOperation.MuteAudio("skipped_unknown_$type")`:
```kotlin
} catch (e: Exception) {
    EditOperation.MuteAudio("skipped_failed_$type")
}
```
Opening a project containing an effect this build does not recognise **silently mutes the user's audio** instead of reporting an error. `MuteAudio` is a destructive operation, so the sentinel choice is the worst possible one. There is also **no `schemaVersion` field** and no migration path.

**B3 — Legacy software drawing cache forced on.** `DraggableImageOverlayView.kt:559–604` uses `isDrawingCacheEnabled`, `buildDrawingCache`, `getDrawingCache` — the removed software rendering path — on the largest overlay view (883 lines).

**B4 — `android.graphics.Movie` for GIF** — deprecated across `ImageOverlayView.kt` and `DraggableImageOverlayView.kt` (15 sites), unmaintained API, no frame budget.

**B5 — ExoPlayer 2.19.1 is end-of-life.** `com.google.android.exoplayer` is deprecated, receives no updates, and is missing capabilities Mhirex needs (audio offload, precise `ContentPosition`, effect/timestamp support). Confined to 1 file + 1 layout, so migration is tractable.

**B6 — Zero real tests.** `ExampleUnitTest` is `assertEquals(4, 2 + 2)`; `ExampleInstrumentedTest` asserts the package name. No coverage of the serializer, filter builder, beat detection, or undo/redo.

**B7 — No multi-select anywhere.** Single-selection only.

**B8 — Dead branches.** "Condition is always `true`" at `VideoEditingActivity.kt:8086`, `:8269`, `VideoEditingViewModel.kt:1318`.

**B9 — Deprecated Intent/clip APIs.** `getParcelableExtra` (`MainActivity.kt:575`, `ProjectImportActivity.kt:199`) has no API 33+ path.

**B10 — `keyframe.interpolationType` is dead.** The field is declared and serialized but never read by the expression compiler.

**B11 — Accessibilitity gap.** Custom `Canvas` views (`TrackTrimView`, `TimeRulerView`, overlay views) expose no `contentDescription`, no virtual view hierarchy, and no accessibility actions. Drag-only interactions are unusable with TalkBack.

**B12 — Dead code in the main source set.** `ScratchTest.kt` + `ScratchTest.java` in `src/main`. Repo-root `test_exo.kt`, `test_ext.kt`, `test_heavy.kt` sit outside all Gradle source sets and are never compiled.

**B13 — Waveform extraction is uncached** and runs ffmpeg on every audio-toolbar interaction.

> A second, verified pass over the render/export pipeline produced a further defect register: **six P0 correctness/data-integrity bugs and ~25 P1/P3/security issues**, including a no-edit save that fails on every Android 10+ device (P0-1) and text/subtitles that silently fail to render in exports (P0-5). **§0.9 is the authoritative defect register** and is scheduled into Release Stages 0 and 0.5.

### 0.7 Licensing and dependency risk

**L1 — GPL-3.0 binary inside an MIT-licensed app.**
The app declares MIT but ships `com.antonkarpenko:ffmpeg-kit-full-gpl:2.1.0`. The `-gpl` bundle enables x264, x265, vid.stab, libass and others, which makes the **entire distributed binary subject to GPL-3.0**. GPL requires corresponding source availability for the combined work. LibreCuts does not publish a corresponding-source offer, nor does the About screen state that the binary is GPL. This is a pre-existing, real compliance gap that Mhirex must resolve rather than inherit silently.

**L2 — ffmpeg-kit is retired.**
`arthenica/ffmpeg-kit` was **archived 2026-07-02**. The successor "FFmpegKitNext" is source-only. The `antonkarpenko` fork is a community fork of a dead project: it receives no upstream FFmpeg security patches, and FFmpeg has a steady CVE stream. Dependency risk: **high**.

**L3 — Rebrand must not erase attribution.** MIT §"The above copyright notice ... shall be included in all copies". Renaming the app to Mhirex is fine; deleting Tharun Birla's copyright from LICENSE, the About screen, or the source headers is **not permitted**. Mhirex must add its own attribution as a *cumulative* notice.

### 0.8 Audit conclusions

| Question | Answer |
|---|---|
| What works | FFmpeg graph builder, xfade/acrossfade chaining, ducking, proxy generation, frame-cache throttling, non-destructive model, M3 theme, i18n |
| What is partial | Timeline, text, transitions, audio, effects, keyframes, export |
| What is broken | Preview pipeline, project loader, drawing cache, GIF decode, player, tests, accessibility, licensing posture |
| What to reuse | §0.4 — all of it |
| What to refactor | `VideoEditingActivity` (split), `VideoEditingViewModel` (split), preview pipeline, `ProjectSerializer` (versioned), `ViewModel` undo (structured) |
| What to replace | Preview rendering, beat detection, timeline widget, player (→ Media3), project format, undo mechanism, GIF decode |
| What is greenfield | Multi-track model, effect stacks, meme packs, SFX system, beat sync, slideshow, platform export presets, share stack, sticker catalog, meme-text presets |

### 0.9 Second-pass audit — verified critical defect register

A dedicated pass over the render/export pipeline produced a verified defect register. Six of these are **P0 correctness/data-integrity** and were not visible from the UI layer. All citations are `file:line` against commit `a510390`.

#### P0 — must be fixed before any release

| # | Defect | Evidence | Impact | Fix |
|---|---|---|---|---|
| **P0-1** | **No-op export is broken on API 29+.** The `hasOperations() == false` path writes via `FileOutputStream` into `Environment.getExternalStoragePublicDirectory(...)`, with no `MediaStore` insert and no SAF tree URI. `WRITE_EXTERNAL_STORAGE` is declared with `maxSdkVersion="28"`. | `VideoEditingActivity.kt:4954-4963`, `AndroidManifest.xml:16` | **The app's most basic action — "save a video with no edits" — fails on every Android 10+ device.** The user gets a raw filesystem exception surfaced as "Export failed" | Route the raw path through `MediaStore`/SAF exactly as `ExportService.saveVideoToGallery` does. Delete the direct-public-`File` branch |
| **P0-2** | Audio-only export on the raw path can write **video bytes into a `.mp3` container** | `VideoEditingActivity.kt:4932-4933`, `:4966-4992` | Unplayable file labelled MP3 | On the raw path, either transcode to audio or refuse audio-only without a transcode step |
| **P0-3** | **Speed + Reverse corrupts merge duration.** `VideoEditingViewModel.kt:913-915` consults only `speedOp.proxyUri`, while `VideoEditingActivity.kt:3938-3988` stores only the reverse proxy. The effective duration is derived from the wrong source. | as cited | `xfade` offsets and `-t` computed from wrong durations → **clipped or overlapping merge output**. Silent data corruption | Reconcile `SpeedMain`/`ReverseMain` into one effective-duration derivation *before* merge planning |
| **P0-4** | `copyContentUriToTempFile` **hardcodes the `.mp4` extension** for every source, and downstream type detection reads the *extension of the cached copy*, not the original MIME type. | `VideoEditingViewModel.kt:1646-1657`, `:828-834` | Images, GIFs and audio tracks copied from `content://` become `temp_videoNNNN.mp4`, are classified as **video**, and can receive `-stream_loop -1` on a still image. Overlay timing and loop behaviour become nondeterministic | Derive the extension from the source MIME type via `MimeTypeMap`; type-detect overlays from the **original** URI |
| **P0-5** | **`copyFontToCache` returns the cache `alias`, not a path** | `FFmpegRenderEngine.kt:101-124` (returns `alias`) | `drawtext` receives a non-absolute `fontfile` → **text overlays and subtitles silently fail to render in the export**, with no error | Return `fontFile.absolutePath` |
| **P0-6** | `MediaStore` insert has **no `IS_PENDING` flag** | `ExportService.kt:209-265` | If the process dies mid-copy, a **truncated, permanently indexed, unplayable file** is left in the user's Movies library forever | `IS_PENDING=1` → stream bytes → `IS_PENDING=0`; delete the row on failure |
| **P0-7** | **`VideoEditingActivity` hard-crashes when launched without a `VIDEO_URI` extra.** `setupExoPlayer()` builds the ExoPlayer only inside `if (videoUri != null)`, but `onCreate` then calls `player.addListener(…)` unconditionally — `UninitializedPropertyAccessException: lateinit property player has not been initialized`. *(Found during Phase 0 device verification, not by the static audit: the only in-app caller, `MainActivity.kt:555`, always supplies the extra, so no normal user path reaches it.)* | `VideoEditingActivity.kt:714` (throw site), `:5111-5113` (conditional init); reachable via the `exported="true"` declaration — see SEC-33 | Any app on the device can send an explicit intent and hard-crash Mhirex. No graceful error, no recovery | **Fixed in Phase 0:** construct the player unconditionally, attach media only when a URI exists. Building an ExoPlayer is cheap and requires no URI |

#### P1 — reliability and lifecycle

| # | Defect | Evidence | Fix |
|---|---|---|---|
| **P1-7** | **ANR: the FFmpeg command is built on `Dispatchers.Main` with blocking I/O** — `MediaMetadataRetriever.setDataSource` (`:701-708`), `BitmapFactory.decodeFile` (`:865-873`), per-item retriever (`:932-941`), full `input.copyTo(it)` (`:1646-1657`). Scales with clip count and can exceed the 5 s ANR budget. | `VideoEditingActivity.kt:4740-4757` | Make `buildConsolidatedFFmpegCommand` a `suspend fun`; run in `withContext(Dispatchers.IO)` |
| **P1-8** | **Cancellation is mis-classified as failure.** `RenderResult.Cancelled` is declared and handled by both services but **never constructed**. `ReturnCode.isCancel` is never checked. | `FFmpegRenderEngine.kt:90, 179, 298-318` | Check `ReturnCode.isCancel`; construct and return `RenderResult.Cancelled`; **never retry a cancel** — currently a cancelled render can feed the `h264_mediacodec`→`libx264` retry and **launch a new render the user just cancelled** |
| **P1-9** | `activeSessions` is a plain `mutableListOf` (not thread-safe) and `executeCommand` **adds without ever removing** | `FFmpegRenderEngine.kt:36, 137` | `CopyOnWriteArrayList`/`ConcurrentHashMap`; remove in `finally` on every path. Currently `hasActiveSessions()` is `true` forever and native handles leak across a session |
| **P1-10** | Both services return `START_NOT_STICKY`; no WorkManager, no state machine | `ExportService.kt:124`, `ProxyGenerationService.kt:99` | Process death during a 20-minute 4K export loses the render with no resume and no user explanation |
| **P1-11** | Temp-file cleanup sits **inside the `try` block**; the `catch` deletes nothing and `finally` only calls `stopSelf` | `ExportService.kt:110-112`, `:114-117`, `:118-121` | Move cleanup to `finally`; track all created temp files in a per-export set |
| **P1-12** | `cancelAllSessions()` calls the **process-global** `FFmpegKit.cancel()` | `FFmpegRenderEngine.kt:179` | Cancelling an export also kills concurrent proxy generation and any preview render. Target the specific session id |
| **P1-13** | `ExportService` owns a *different* `FFmpegRenderEngine` instance than the Activity, so the Activity's session list never contains the export session — cancellation works only by the global side effect above | — | Single shared engine (singleton) so sessions are addressable |
| **P1-14** | **Mask keyframing on merged clips is unreachable dead code.** `handleKeyframeActionClick` does `val op = project.operations.find { … } ?: return`, which makes `op` non-null, and then guards the whole body with `if (op != null) { … } else { … }`. The compiler flags the condition as always true, and the `else` branch — 57 lines handling `Mask Position` / `Mask Size` / `Mask Rotation` / `Mask Feather` via `selectedVideoIndex` — can never execute. | `VideoEditingActivity.kt:8279` (`?: return`), `:8282` (always-true guard), `:8380-8436` (dead `else`) | A user who selects a **merged sequence clip** and tries to keyframe its mask position/size/rotation/feather gets **nothing at all, with no error** — the early `?: return` fires before the mask branch is ever reached. Mask keyframing only works on standalone overlay/text clips | Make `op` genuinely nullable so the `else` branch becomes reachable — i.e. drop the `?: return` and let the mask path handle the "no overlay op selected" case. **Not done in Phase 0:** this is a behaviour change, not a cleanup, and the mask branch has never executed so its correctness is unverified. Needs a device test of merge-clip masking before landing. |

#### P2 — licensing (see §5.3 and §6)

Dependency is pinned to `2.1.0` while **`2.2.1` is the current release**, and it is a fork of an upstream archived 2026-07-02. See §5.3 for the LGPL-downgrade path, which is the actual fix.

#### P3 — output quality

| # | Defect | Evidence | Fix |
|---|---|---|---|
| **P3-17** | **`libx264` gets no rate control at all** — bitrate is passed only to `h264_mediacodec`, so the software path silently uses x264's CRF 23 default. Hardware and software exports are **not quality-equivalent** | `VideoEditingViewModel.kt:1300-1310`, `:1554-1564` | One documented policy for both (CRF+preset for x264, bitrate for MediaCodec); surface the difference in the UI |
| **P3-18** | No `-movflags +faststart` | absent from both builders | Exported MP4s have `moov` at the end and will not stream progressively — poor behaviour on social platforms and in-app players. Cheap, high-value fix |
| **P3-19** | **No no-upscale guard.** `scale=-2:<h>` with no `min()` against source height | `VideoEditingViewModel.kt:1173`, `:1511`, `:1567` | Exporting a 480p source at 2160p burns 30 Mbps on detail that does not exist. Use `scale=-2:min(<h>,ih)` |
| **P3-20** | Retry is a blind `command.replace("h264_mediacodec","libx264")` over the whole command — it can rewrite a token inside a **file path**, hardcodes `-b:v 8M` discarding the user's bitrate, and retries **filter-graph** failures (bad transition, missing font, malformed imported subtitle) identically, wasting a full render | `FFmpegRenderEngine.kt:154-162`, `:306-314` | Rebuild the command from parameters; gate retry on encoder-specific error codes only |
| **P3-21** | **Progress never reaches 100%** on transition-bearing merges: `getTotalSequenceDuration()` sums unadjusted `trimmedDurationMs` while `xfade` shortens the output | `VideoEditingActivity.kt:4791`, `:5288-5289` vs `VideoEditingViewModel.kt:1125` | Compute the total from the accumulated `StreamInfo.durationSec` |
| **P3-22** | **Every export failure is reported as `LC-101 FFMPEG_EXECUTION_FAILED`.** `LC-202 GALLERY_SAVE_FAILED` is declared and never used, so a storage failure is reported as "FFmpeg failed" when FFmpeg actually succeeded | `VideoEditingActivity.kt:216-220` vs `ErrorCode.kt:7` | Map gallery-save failures to `LC-202`; short user message, full diagnostic in the log (see §4.22) |
| **P3-23** | **Uncapped keyframe nesting.** `buildFFmpegInterpolationExpr` emits right-nested `if(lt(t,…))` with no cap on keyframe count or nesting depth; FFmpeg re-evaluates the whole ladder **per frame** | `VideoEditingViewModel.kt:281-326` | Cap + decimate keyframes; bound depth. See §4.15 |
| **P3-24** | **Subtitle colour and background fields are ignored** — hardcoded `white` / `0x00000080` despite `AddSubtitles` storing them | `VideoEditingViewModel.kt:610-611` | Honour the stored values or remove the UI affordances |
| **P3-25** | `AudioWaveformExtractor` and `AudioAnalyzer` call `pcmFile.readBytes()` — a single allocation proportional to audio length. A 60-minute WAV attempts a multi-hundred-MB allocation. `LC-301 OUT_OF_MEMORY` exists but is not wired to any catch. | `AudioWaveformExtractor.kt`, `AudioAnalyzer.kt` | Stream in fixed-size chunks; wire `LC-301` |
| **P3-26** | Unknown transition names **silently degrade to `fade`** with no log | `VideoEditingViewModel.kt:1115-1120` | Log a warning carrying the rejected name |
| **P3-27** | `MuteAudio` and `removeOriginalAudio` are **global**, not per-clip/per-track. One `MuteAudio` mutes the entire export; one track's `removeOriginalAudio` strips original audio from *all* clips. | `VideoEditingViewModel.kt:719-720` | Scope both to the clip/track. See §4.9 |
| **P3-28** | **No `loudnorm`, no `alimiter`** anywhere in the audio chain | `VideoEditingViewModel.kt:1240-1290` | No headroom protection against intersample clipping |
| **P3-29** | `ExportConfig` exists in the model and is **never consumed** by the command builder | `VideoProject.kt:152-159` | Either wire it or delete it. Superseded by `ExportSpec` (§4.17) |

#### P3 — security hardening

| # | Defect | Evidence | Fix |
|---|---|---|---|
| **SEC-30** | **FFmpeg filtergraph argument injection via imported project files.** Escaping covers only `\`, `'`, `:` (`:586-589`) and font paths (`:574-583`). **Not escaped:** `"`, `%` (`drawtext` expands `%{eif:…}` at runtime), `[`, `]`, `,`, `;`, `=`. A crafted `.lcprj` can close the single-quoted context and append extra `drawtext` options or extra filtergraph elements. `ProjectImportActivity` also triggers dependency repair and proxy generation, widening the surface to "any project the user is convinced to open". **This is not shell injection** — `FFmpegKit` receives the string directly (`:136`, `:256`) and no shell is spawned — but it is a genuine attack surface because project files are untrusted input. | as cited | Treat imported fields as hostile: escape the full set `\ ' " : , ; [ ] % =`, and preferably pass text payloads via a per-cue `textfile=` so user content never appears in the command string. Validate `fontPath` against an allowlist of font directories. See §4.4 |
| **SEC-31** | `WRITE_EXTERNAL_STORAGE` declared (capped at 28) | `AndroidManifest.xml:16` | Remove entirely once P0-1 is fixed — no code path should need it |
| **SEC-32** | Global FFmpeg log callback registered and never unregistered; buffers up to 2000 lines per session. Bounded per session, unbounded in aggregate given P1-9. | `FFmpegRenderEngine.kt:60-79`, `:66-69` | Bound the callback to active sessions; unregister on owner teardown |
| **SEC-33** | **`VideoEditingActivity` is `exported="true"` with no `permission` attribute**, while all of its real callers are in-app (`MainActivity.kt:555`). It holds a full project in memory and reads/writes `content://` URIs, so any app on the device can launch the editor against URIs it has no grant for. Exported is what makes P0-7 remotely triggerable. | `AndroidManifest.xml` — `.VideoEditingActivity` | Set `android:exported="false"`. Nothing outside the app starts it: it has no `<intent-filter>`, so no implicit launch can reach it either. If an external entry point is ever wanted, add an explicit intent-filter *and* a signature-level permission at the same time |

> Note on SEC-30: the realistic ceiling is filtergraph argument injection and `%{eif:…}` expression expansion, **not** arbitrary code execution. Severity is moderate but the fix is cheap and the input is genuinely untrusted.

---

## 1. Product Vision

**Mhirex is a fast, mobile-first, open-source video editor built for short-form creators who cut on their phone.**

The product loop is deliberately short and rhythmic:

```
IMPORT  →  CUT  →  MAKE IT FUN  →  ADD MUSIC/SFX  →  SYNC TO BEAT  →  EXPORT  →  SHARE
```

The differentiator is **not** "more features". It is **lower time-to-first-fun-export on a mid-range phone**. Two or three taps between "I have 30 clips" and "I have a beat-synced meme edit with music, SFX, text and transitions."

Design principles, in priority order:

1. **Speed is the feature.** Preview must be instant. Anything that blocks the main thread or spawns an encoder is a defect.
2. **Non-destructive, always.** Originals are never touched. Every operation is a parameter change, reversible, and serialisable.
3. **One hand, one screen.** Primary tools — CUT, TEXT, AUDIO, EFFECTS, TRANSITIONS, STICKERS, SPEED, FILTERS, CANVAS — are always one tap away. Nothing important hides in a menu.
4. **Presets over sliders.** Meme packs, templates, and beat-sync generate *editable* output. Generating a good edit must not mean hand-tuning 14 parameters.
5. **Fully local and private.** No network permission, no telemetry, no account.
6. **Legally clean.** No copyrighted music, no unlicensed meme imagery, no commercial fonts.
7. **AI is optional tooling, never the product's identity.** It may be added as isolated, individually-useful features. It must not shape the core architecture.

---

## 2. Current Architecture

Documented in full in §0.3. Summary of the load-bearing constraints:

- **Single source, flat operation list.** No tracks. Time is per-operation nullable Longs.
- **Undo = whole-project snapshot**, 30 deep.
- **Preview = full FFmpeg re-encode** (broken, B1).
- **Export = one monolithic `filter_complex`** assembled in two places that must be kept in sync (`performRenderTrack` and `VideoEditingViewModel`).
- **Progress via `BroadcastReceiver`** into an Activity — not process-death safe.
- **Project = unversioned Gson JSON** with a hand-maintained type table.
- **UI = one 9012-line Activity** owning rendering orchestration.

---

## 3. Target Architecture

### 3.1 Module structure

Single `:app` module is **retained deliberately**. Reasons: the ABI splits and the ~90 MB ffmpeg AAR already make builds heavy; splitting into Gradle modules adds configuration and build time for a project whose team is small, and Media3/ffmpeg interactions are inherently cross-cutting. Modularity is enforced by **package boundaries and one-way dependencies** instead, verified by an architecture test (§8).

```
com.mhirex.editor
├── core/            Cross-cutting primitives. No Android UI deps.
│   ├── model/       Immutable domain model (Project, Track, Clip, Effect, …)
│   ├── time/        Timebase, rational arithmetic, frame↔time conversion
│   ├── math/        Easing, curves, keyframe interpolation, geometry
│   └── result/      Result / AppError types
├── data/            Persistence, media access, analysis
│   ├── project/     ProjectRepository, ProjectSerializer v2, atomic writes, recovery
│   ├── media/       MediaStoreSource, ProbeResult, ThumbnailRepository
│   ├── audio/       WaveformExtractor (cached), BeatTracker, TempoEstimator, Ducker
│   └── assets/      Preset catalogs: memes, SFX, templates, effect stacks, transitions
├── engine/          Render + export. Knows nothing about UI.
│   ├── ffmpeg/      FFMpegCommandBuilder, filter-graph compiler, escaping
│   ├── render/      PreviewRenderer (GPU), ExportPipeline (staged), ProgressBus
│   └── model/       RenderPlan (UI-agnostic description of the edit)
├── feature/         One package per user-facing tool
│   ├── editor/      Composition root, toolbar host, tool registry
│   ├── timeline/    Timeline widget, gestures, snapping, selection
│   ├── text/        Text tool, meme-text catalog
│   ├── effects/     Effect stacks, filters
│   ├── transitions/ Transition catalog + AUTO
│   ├── audio/       Music, SFX, waveform track
│   ├── beat/        Beat markers, SYNC TO BEAT
│   ├── slideshow/   CREATE SLIDESHOW
│   ├── export/      Presets, progress, share stack
│   └── library/     Template/pack browser
└── ui/              Design system: tokens, components, a11y helpers
```

**Dependency rule (enforced by test):** `ui ← feature ← engine ← data ← core`. Nothing in `core`, `data`, or `engine` may import from `feature` or `ui`.

### 3.2 Core domain model — the central change

Replaces `VideoProject` + flat `EditOperation`. All types immutable; all mutations produce new instances.

```kotlin
@JvmInline value class TrackId(val value: String)
@JvmInline value class ClipId(val value: String)
@JvmInline value class EffectId(val value: String)

data class Project(
    val schemaVersion: Int,                  // REQUIRED — fixes B2
    val id: ProjectId,
    val name: String,
    val canvas: CanvasSpec,                  // width, height, fps: Rational, background
    val tracks: List<Track>,                 // ordered, index 0 = topmost
    val selection: Selection,
    val markers: List<Marker>,               // beat markers, chapter markers
    val meta: ProjectMeta,                   // created/modified, appVersion, warnings
) {
    val durationUs: Long                     // derived, never stored
}

sealed interface Track {
    val id: TrackId
    val name: String
    val locked: Boolean
    val muted: Boolean
}

data class MediaTrack(                       // video / image clips
    override val id: TrackId, ...,
    val clips: List<Clip>
) : Track

data class AudioTrack(
    override val id: TrackId, ...,
    val kind: AudioKind,                     // MUSIC | VOICEOVER | SFX
    val clips: List<AudioClip>
) : Track

data class OverlayTrack(                     // text, stickers, packs
    override val id: TrackId, ...,
    val layers: List<OverlayLayer>
) : Track

data class Clip(
    val id: ClipId,
    val trackId: TrackId,
    val source: MediaRef,                    // content Uri + persisted permission
    val timelineStartUs: Long,               // position ON THE TIMELINE
    val inUs: Long,                          // source in-point
    val outUs: Long,                         // source out-point
    val speed: Rational,
    val reverse: Boolean,
    val transform: Transform,                // x,y,scale,rotation in normalized space
    val opacity: Float,
    val volume: Float,
    val effects: List<Effect>,               // ordered effect stack
    val keyframes: List<Track<Keyframe>>,
    val mask: MaskSpec?,
    val colorGrade: ColorGrade?,
    val sourceDurationUs: Long,              // cached probe result
    val proxy: ProxyRef?,                    // speed/scrub proxy
)
```

**Effect stack** — the reusable primitive behind both EFFECTS and MEME PACKS:

```kotlin
sealed interface Effect {
    val id: EffectId
    val enabled: Boolean
    val params: Map<String, Float>           // uniform, serialisable
}

data class TransformEffect(scale, rotation, x, y, anchor, animateWithMotion) : Effect
data class FilterEffect(lutName: String, intensity: Float) : Effect
data class ColorAdjustEffect(brightness, contrast, saturation, temperature, …) : Effect
data class TransitionEffect(type: TransitionType, durationUs: Long, params) : Effect  // clip-local
data class TextEffect(style: TextStyle, animation: TextAnimation, keyframes) : Effect
data class StickerEffect(assetId: String, animation: OverlayAnimation) : Effect
data class AudioEffect(type: AudioEffectType, params) : Effect   // SFX lives here too
data class MaskEffect(shape, feather, invert) : Effect
```

**Everything is normalised (0.0–1.0) in the model** and converted to pixels/FFmpeg expressions only at compile time. This removes an entire class of px-vs-normalised bugs (the current `DraggableTextOverlayView` and `AddText` already disagree about coordinate spaces).

### 3.3 Timeline

- **Model:** `List<Clip>` per track with explicit `timelineStartUs`. Clips may overlap (picture-in-picture) — no implicit packing.
- **Widget:** replaced. `TrackTrimView` (custom `Canvas`) is retained as a proven base for frame strips, but the new `TimelineView` adds: continuous pinch-zoom, horizontal scroll, per-track lanes, ruler, playhead, trim handles, selection, and a snapping engine.
- **Snapping:** `SnapEngine` — candidates are clip edges, clip starts, playhead, project start/end, and beat markers. Priority + pixel-threshold (not time-threshold) so behaviour is zoom-independent.
- **Selection:** `Selection` is part of `Project` and therefore **undoable**. Supports single and multi-select, with range and additive modes.
- **Precision:** all internal time is **microseconds** (`Long`), with `Rational` fps. Frame precision is exact, never floating-point drift.

### 3.4 Audio

```
WaveformExtractor   ffmpeg/MediaExtractor → FloatArray peak buckets → on-disk cache (keyed by content hash)
BeatTracker         decode → onset envelope → tempo estimate → beat grid → BeatMap
AudioMixer          adelay / volume / fades / sidechaincompress  (extends existing, proven code)
SfxLibrary          data-driven catalog; SoundEffect entities; auto-attach rules
```

`BeatMap` is the key new type:

```kotlin
data class BeatMap(
    val trackId: TrackId,
    val bpm: Float,
    val confidence: Float,               // 0..1
    val beatsUs: List<Long>,             // the beat grid
    val downbeatsUs: List<Long>,         // bar-level (every 4)
    val onsetUs: List<Long>,             // raw onsets, for diagnostics
    val source: BeatSource,              // DETECTED | IMPORTED | MANUAL
    val analysisMs: Long
) {
    fun grid(mode: BeatMode, fromUs: Long, toUs: Long): List<Long>   // EVERY / EVERY_2 / EVERY_4 / MAJOR
    fun nearestBeat(toUs: Long, toleranceUs: Long): Long?
}
```

### 3.5 Rendering

Two independent paths, both consuming a `RenderPlan`:

**Preview (new — replaces B1).**
- **No FFmpeg for preview.** Use Media3 with a `Composition` and GPU `Effect` chain (`ScaleAndRotateTransformation`, `RgbMatrix`, `OverlayEffect`) for transforms, colour, and overlays.
- Preview renders only the **active clip range around the playhead**, not the whole timeline.
- Frame-accurate scrubbing decodes from a **scrub proxy** (existing `generateScrubProxy`).
- Hard budget: **first preview frame < 150 ms**, interaction 60 fps. No FFmpeg process may be spawned by the preview path.
- A `PreviewDebouncer` coalesces rapid edits; it never spawns work, it only drops stale requests.

**Export (evolution of existing — do not rewrite).**
Staged pipeline, each stage producing an intermediate file and reporting progress:

```
Stage 0  Proxy      (reused: speed / scrub / reverse proxies)
Stage 1  Conform    per-clip normalisation: scale, fps, sar, pix_fmt, speed, reverse
Stage 2  Composite  per-track: filters, masks, colour grades, transforms
Stage 3  Composite  xfade/acrossfade transitions between clips
Stage 4  Overlay    drawtext (text), overlay (stickers/GIF/video overlays), chromakey
Stage 5  Audio      adelay/volume/fades → sidechaincompress ducking → amix
Stage 6  Encode     h264_mediacodec → libx264 fallback; mux to MP4
Stage 7  Publish    MediaStore insert + optional share handoff
```

Staging exists for three concrete reasons: it keeps individual filter graphs small enough to debug, it enables **one-pass encoding for the whole project** while allowing per-stage progress, and it lets stages 1–3 be cached and skipped when only stage-4/5 inputs changed. The existing monolithic builder is retained as `LegacyFilterGraphCompiler` so **existing `.lcprj` files keep exporting** until migration completes.

### 3.6 Undo/redo

Replace snapshot undo with **structured command undo**:

```kotlin
interface EditOp { fun apply(s: EditorState): EditorState; fun invert(): EditOp }
```

- `EditorState` holds only the mutable editing state (project + selection + playhead), not rendered frames.
- `HistoryManager` holds `ArrayDeque<EditOp>` capped at 100, with **coalescing** (a continuous slider drag becomes one op via `beginCoalesce`/`endCoalesce`).
- Every mutating path goes through `EditorStore.dispatch(op)`. No direct state writes anywhere.
- Acceptance: 10,000-op stress test shows no unbounded memory growth (verified by §8 test).

### 3.7 Project storage

```
ProjectStore
  save(project)   → write to <cache>/tmp-uuid.json → fsync → atomic rename → <projects>/uuid.lcprj
  load(id)        → read → parse → migrate(schemaVersion) → validate → Project
  list()          → metadata index (no full parse)
  recover()       → on cold start, sweep *.tmp, promote or discard; surface quarantined files
```

- **Format:** `.lcprj` = JSON (Gson, hand-written polymorphic codec **with a `schemaVersion` and a real migration chain**). Not protobuf/ProtoBuf — the model is deeply nested and human-debuggable JSON has real value for an open-source project; the compression win does not justify the codegen and migration complexity now.
- **Media references** are persisted with `takePersistableUriPermission`; `MediaRef` also stores a content hash so a missing file is reported precisely rather than failing at export.
- **Atomic writes + recovery** are mandatory (fixes B2 and the interrupted-operation case in Phase 22).

### 3.8 Communication between components

- **Single writer:** `EditorStore` owns `Project`. Every component observes a `StateFlow<EditorState>`; none mutate directly.
- **UI never calls FFmpeg.** UI emits intents → `EditorStore` → `RenderPlanner` → `PreviewRenderer`/`ExportPipeline`. This is the single most important structural rule; it is what makes the B1 class of bug impossible to reintroduce.
- **RenderPlan** is a pure, testable value object produced from `Project`. Both preview and export consume it. Unit-testable with no device.
- **Progress** via a `ProgressBus` (StateFlow + `StateFlow<ExportProgress>`), not BroadcastReceiver, so export survives Activity recreation and is observable in tests.

---

## 4. Feature-by-Feature Implementation Plan

> Each feature states: existing implementation → required changes → files affected → new components → data-model changes → UI changes → rendering changes → performance → testing → dependencies → risks → acceptance criteria.
> Ordering and full task decomposition live in `TODO.md`.

### 4.1 Rebrand to Mhirex

- **Existing:** `app_name` = "LibreCuts" (16 locales; `values-zh-rCN` = 自由剪辑). 41 `package com.tharunbirla.librecuts` declarations, 6 `R` imports, 7 brand strings × 17 locales. `str_made_by_tharun_birla`, `str_github_sponsors`, `str_sponsor_project`. Fastlane metadata, README, `.github/FUNDING.yml`, AboutLibraries config.
- **Required changes:**
  1. `app_name` → `Mhirex` in **all 17 locales** (delete the zh translated brand).
  2. `str_downloads_librecuts` → `Downloads/Mhirex`; same for Movies/Music/Pictures defaults.
  3. New strings for About/credits that **keep** Tharun Birla's MIT attribution and add Mhirex's.
  4. Package rename `com.tharunbirla.librecuts` → `com.mhirex.editor` for **both** `namespace` and `applicationId`. (Originally scoped to `namespace` only — see AD-2.)
  5. `local.properties`-independent: `resources.properties` stays.
  6. Keep the persisted `.lcprj` extension for compatibility; future schema changes are handled by the versioned migrator rather than by introducing a Mivio-era extension.
- **Files:** all 41 Kotlin files, `app/build.gradle`, all 17 `strings.xml`, `AndroidManifest.xml`, `fastlane/**`, `README.md`, `.github/FUNDING.yml`, `LICENSE`, add `NOTICE`.
- **New components:** `NOTICE` file; `LicensesFragment` listing ffmpeg-kit as **GPL-3.0** (fixes L1 visibility).
- **Data model:** `EditRecipe` gains `schemaVersion`; legacy `.lcprj` mapped through `LegacyProjectMigrator`.
- **UI:** new adaptive launcher icon, splash colour, About screen with dual attribution.
- **Performance:** n/a.
- **Testing:** JVM test asserting all 17 locales resolve `app_name` to "Mhirex"; test that `.lcprj` still loads.
- **Dependencies:** none.
- **Risks:** ⚠️ **`applicationId` change orphans existing user data** and breaks upgrade from LibreCuts. **Revised decision (supersedes the original, see AD-2):** `applicationId` is now `com.mhirex.editor`, matching the `namespace`. Accepted consequences: (a) Mhirex installs as a **separate app** from LibreCuts — no upgrade path, so LibreCuts users must reinstall and their saved projects/settings are unreachable; (b) any prior LibreCuts install is *not* replaced, so both apps coexist; (c) the F-Droid/Obtainium/Play listings and Weblate project under `com.tharunbirla.librecuts` no longer track Mhirex and need new listings. Gained: a package id Mhirex actually owns, which is a **prerequisite for Play Store publication** — only the package owner can publish to it. If in-place LibreCuts upgrade is later judged more valuable than owning the id, a data-import migration must be built before switching back. This is recorded in §9.
- **Acceptance:** app label reads Mhirex in all locales; no obsolete LibreCuts product-name string remains in user-visible UI, while the required upstream attribution is preserved; MIT attribution present in LICENSE + NOTICE + About; app builds and a LibreCuts `.lcprj` opens.

### 4.2 Multi-track project model

- **Existing:** `VideoProject(sourceUri, operations: List<EditOperation>)`. No tracks.
- **Required changes:** introduce `Project`/`Track`/`Clip` (§3.2). Write `LegacyProjectMigrator` converting the old model → new model, so nothing is lost. `EditOperation` retained **frozen** for legacy export only.
- **Files:** new `core/model/**`; `models/VideoProject.kt`, `models/EditOperation.kt` become legacy; `VideoEditingViewModel.kt` rewritten against the new model.
- **New components:** `Project`, `MediaTrack`, `AudioTrack`, `OverlayTrack`, `Clip`, `AudioClip`, `OverlayLayer`, `Effect` hierarchy, `CanvasSpec`, `Transform`, `Selection`, `Timebase`.
- **Data model:** as §3.2. All time `Long` microseconds; `Rational` fps.
- **UI:** timeline renders N tracks; track headers with mute/lock/solo.
- **Rendering:** `RenderPlanner` walks tracks. Unchanged FFmpeg semantics for single-track legacy projects.
- **Performance:** `Project` diffing for minimal recompute; `Clip` identity is stable so Compose-free view updates can diff by id.
- **Testing:** exhaustive JVM unit tests on the migrator (fixture `.lcprj` from upstream); time-arithmetic property tests (trim/split/reorder never change total duration unless intended).
- **Dependencies:** `kotlinx-collections-immutable` (Apache-2.0) for structural sharing of clip lists.
- **Risks:** the flat→track migration is the highest-risk change in the project. **Mitigation:** migrator is pure, fixture-tested, and the legacy export path stays alive until migration is proven.
- **Acceptance:** a 20-operation LibreCuts project migrates to a visually and semantically identical new project; round-trip export is byte-comparable in duration/stream layout.

### 4.3 Timeline (Phase 5)

- **Existing:** `TrackTrimView` (440 lines) draws clips + thumbnails via `FrameAdapter`; `TimeRulerView`; `CustomVideoSeeker`; drag via `startDragSession`/`updateDragPosition`/`endDragSession`; trim dialog; split; delete; snapping via `getSnapTargetsMs()`; `ZoomMode` 3 discrete levels.
- **Required changes:** new `TimelineView` (custom `View`, hardware layer) with lanes, continuous pinch-zoom, h-scroll, playhead, per-clip trim handles, multi-select, drag-to-reorder, drop indicator. `SnapEngine` with pixel-threshold snapping and beat-marker candidates. Selection model in `EditorState`.
- **Files:** replace `customviews/TrackTrimView.kt`, `TimeRulerView.kt`, `CustomVideoSeeker.kt`; new `feature/timeline/**`; `FrameAdapter` retargeted.
- **New components:** `TimelineView`, `TimelineLayoutEngine`, `SnapEngine`, `GestureArbiter` (drag vs scroll vs pinch vs trim), `SelectionModel`.
- **Data model:** `Clip.timelineStartUs/inUs/outUs`; `Selection(anchorId, focusedId, range)`.
- **UI:** track lanes sized to content; playhead; zoom controls; "frames" readout when zoomed in.
- **Rendering:** none (timeline is pure Canvas).
- **Performance:** **hard requirement** — 60 fps with 200 clips. Achieved by: thumbnails from a disk-backed `ThumbnailRepository` (LRU in memory, files on disk), no allocation in `onDraw`/`onTouchMove` (pre-allocated `Paint`/`Path`/arrays), single-pass drawing with `drawVertices` for filmstrip tiles, `invalidate()` on dirty rects only, and gesture work on the render thread via `postOnAnimation`.
- **Testing:** JVM tests for `TimelineLayoutEngine` and `SnapEngine` (pure geometry); instrumentation test asserting 200-clip scroll/zoom stays under 16 ms/frame (measured, not asserted by luck).
- **Dependencies:** none new.
- **Risks:** gesture conflicts (pinch vs drag vs long-press) — mitigated by a single `GestureArbiter` that decides once per gesture stream.
- **Acceptance:** drag, trim, split, delete, reorder, pinch-zoom, multi-select, snapping (clip + beat), and undo/redo all work; 200 clips at 60 fps on a mid-range device.

### 4.4 Text (Phase 6)

- **Existing:** `AddText` with `fontSize`, `color`, `borderThickness`/`borderColor`, `textAlign`, `letterSpacing`, `lineSpacing`, `opacity`, `position` (11 presets) or `relativeX/Y`, `positionKeyframes`, `opacityKeyframes`, custom font import via `FontManager`, WYSIWYG `DraggableTextOverlayView`.
- **Required changes:** add **shadow**, **background box** (rounded, colour, padding, opacity), **stroke** (distinct from `border`), **rotation**, **scale**, **text animation** (fade/pop/slide/typewriter/shake/wave), **per-text vertical layout**, easing on all keyframe channels, and a **data-driven meme-text preset catalog**.
- **Files:** `core/model/TextStyle.kt` (new), `core/model/TextAnimation.kt` (new), `data/assets/MemeTextCatalog.kt` (new), `feature/text/**` (new), legacy `buildDrawtextExpr` retired in favour of the new compiler.
- **New components:** `TextStyle` (font, size, colour, stroke, shadow, background, align, spacing, transform, opacity), `TextAnimation`, `MemeTextPreset`, `MemeTextCatalog`, `EasingFunction`.
- **Data model:** `TextEffect(style, animation, keyframes)` as a clip/overlay effect.
- **UI:** text tool → template grid (plain / caption / meme) → style sheet (font, size, colour, stroke, shadow, background) → position/scale/rotate handles on the preview → animation picker → duration handles on the timeline.
- **Rendering:** `drawtext` for fill/stroke/shadow/background(box_alpha + boxborder); rotation/scale via `rotate`/`scale` before `drawtext` in the overlay chain; animation via time-parameterised `alpha`/position expressions compiled from `EasingFunction`. Critically, the **preview and export must share one layout model** so WYSIWYG holds — a `TextLayoutMetrics` value used by both.
- **Performance:** text layout cached per (text, style, width) and invalidated on change; keyframe expressions compiled once per render plan, not per frame.
- **Testing:** JVM — golden tests for `drawtext` string generation incl. escaping of `'`, `:`, `\`, `%`, newlines, emoji, RTL; animation expression compiler; `TextLayoutMetrics` vs measured Android layout tolerance test.
- **Dependencies:** none new (fonts via SAF + system).
- **Risks:** ⚠️ **FFmpeg text escaping is a confirmed injection surface (SEC-30), not merely a reliability concern.** The legacy builder escapes only `\`, `'`, `:` (`VideoEditingViewModel.kt:586-589`). It does **not** escape `"`, `%` (which `drawtext` expands as `%{eif:…}` at runtime), `[`, `]`, `,`, `;`, or `=`. Since text also arrives from **imported project files** — genuinely untrusted input, and `ProjectImportActivity` will then run proxy generation and dependency repair on them — a crafted project file can close the single-quoted context and append extra `drawtext` options or extra filtergraph elements. This is **not** shell injection (no shell is spawned; FFmpegKit receives the string directly), but it is a real attack surface. Mitigation: a single hardened `FFmpegEscaper` covering the full set `\ ' " : , ; [ ] % =`, an exhaustive test table, `fontPath` validated against an allowlist of font directories, and — preferred — per-cue `textfile=` temp files so user payloads never enter the command string at all.
- **Acceptance:** every text control round-trips preview→export identically; a text layer containing `'`, `:`, `\` and emoji exports successfully.

### 4.5 Meme text presets (Phase 6)

- **Existing:** nothing.
- **Required changes:** a data-driven catalog — the eight required presets (`BRO 💀`, `NAHHH 😭`, `WHAT 💀`, `BRUH`, `SUS 🤨`, `W`, `L`, `I'M DONE 😭`) plus ~30 more — each a pure data record: text, style, animation, optional SFX id, optional effect ids.
- **Files:** `data/assets/MemeTextCatalog.kt`; catalogue JSON in `app/src/main/assets/catalogs/meme_text.json` so it is editable without recompiling.
- **New components:** `MemeTextPreset`, `PresetCatalog<T>` (generic loader with schema validation), catalogue asset.
- **Data model:** none new — presets deserialise to `TextEffect` + `AudioEffect`.
- **UI:** "Meme" tab in the text tool; tap to apply; every field remains editable afterwards.
- **Rendering:** identical to text.
- **Performance:** catalogue parsed once and cached; ~40 presets is trivial.
- **Testing:** JVM — catalogue loads, all entries validate, no duplicate ids, every referenced SFX id resolves.
- **Dependencies:** none.
- **Risks:** emoji rendering differs between Android `StaticLayout` and FFmpeg `drawtext`. **Mitigation:** measured on-device, and presets that render inconsistently are excluded from the default catalog.
- **Acceptance:** all 8 named presets apply in one tap and are fully editable afterwards.

### 4.6 Effects and effect stacks (Phase 7 / 14)

- **Existing:** `Adjust` (9 int params, per clip index), `ColorFilter` (LUT name, per clip index), `MaskConfig` (6 shapes + keyframes), `CanvasBackground`. No ordering, no stacking, no intensity.
- **Required changes:** generalise to an ordered `List<Effect>` per clip. Add **intensity** to colour effects, **custom LUT import**, **effect stack presets**, **user-saved stacks**, and **reorder/duplicate/disable per effect**.
- **Files:** `core/model/Effect.kt` (new); `feature/effects/**` (new); `VideoEditingViewModel.getFFmpegFilterForAdjust` and the LUT builder migrate to `EffectCompiler`.
- **New components:** `Effect`, `EffectCompiler` (effect → FFmpeg filter string, ordered), `EffectStackPreset`, `EffectStackCatalog`, `UserPresetStore`.
- **Data model:** `Clip.effects: List<Effect>`; `Effect.params: Map<String,Float>`.
- **UI:** EFFECTS tool → category tabs (Colour / Adjust / Distort / Style) → intensity slider → "Save as stack".
- **Rendering:** `EffectCompiler` emits filters in list order, making order **predictable and testable** (the current per-index `Adjust`/`ColorFilter` ops have implicit order).
- **Performance:** precompiled per render plan; LUT applied as `lut3d` (`.cube`) with `intensity` via `blend`.
- **Testing:** JVM — `EffectCompiler` golden tests per effect type and for ordering; LUT file validation.
- **Dependencies:** none new (LUT = user-supplied `.cube`).
- **Risks:** effect ordering bugs are subtle. Mitigation: golden tests pin the emitted filter string.
- **Acceptance:** an effect stack applies in order, each element is individually editable/disable-able, and a user-saved stack reloads identically.

### 4.7 Meme effect packs (Phase 7)

- **Existing:** nothing.
- **Required changes:** packs that apply **an ordered set of effects + text + SFX + transform** in one action, matching the `☠️ DEAD PACK` example (freeze + zoom + shake + skull + " BRO 💀 " + bass hit). Every component must remain individually editable after application, and users can save their own packs.
- **Files:** `data/assets/MemePackCatalog.kt` (new), `assets/catalogs/meme_packs.json` (new), `feature/effects/packs/**` (new).
- **New components:** `MemePack`, `MemePackApplier` (pack → concrete effects/text/SFX, all with fresh ids so they are independent and editable), `MemePackCatalog`.
- **Data model:** a pack is a **template**, not persisted state. Applying it materialises normal `Effect`/`OverlayLayer`/`AudioClip` entities.
- **UI:** MEME PACKS grid; tap to apply to selected clip (or playhead); "Save current as pack".
- **Rendering:** no new renderer work — packs reuse existing effect compilation.
- **Performance:** materialisation is O(pack size); negligible.
- **Testing:** JVM — every pack applies cleanly to an empty project, produces independent ids, and round-trips through save/load.
- **Dependencies:** none (SFX assets see §4.10).
- **Risks:** packs that create conflicting effects (e.g. two rotations) — mitigation: pack schema forbids duplicate effect-type-with-same-param-slot and the applier validates.
- **Acceptance:** the DEAD PACK applies in one tap, produces freeze+zoom+shake+skull+text+bass-hit, all six components are independently editable and deletable, and a user-saved pack reloads.

### 4.8 SFX system (Phase 8)

- **Existing:** none. Audio is only `AddBackgroundAudio` (music/voiceover).
- **Required changes:** a first-class SFX layer: a catalog of **synthesised or bundled** effects across the 10 required categories (Impact, Whoosh, Pop, Bass, Meme, Transition, Notification, Camera, Cinematic, Glitch, Comedy), SFX clips on audio tracks, **auto-attach rules** (effect → SFX), move-with-parent, offer-delete-with-parent, and post-apply editability.
- **Files:** `core/model/SoundEffect.kt` (new), `data/assets/SfxCatalog.kt` (new), `assets/sfx/**` (new), `feature/audio/sfx/**` (new).
- **New components:** `SfxDefinition`, `SfxCatalog`, `SfxAttachment` (parent link + relative offset), `SfxAutoAttachRule`, `AudioClip` on `AudioTrack` with `kind = SFX`.
- **Data model:** `AudioTrack.kind ∈ {MUSIC, VOICEOVER, SFX}`; `SfxAttachment(parentEffectId, offsetUs, deleteWithParent)`.
- **UI:** AUDIO tool → Music / Voiceover / **SFX** tabs; SFX browser with category filter and waveform preview; "also remove SFX" toggle in the effect-delete confirmation.
- **Rendering:** SFX are ordinary audio inputs — `adelay` + `volume` + `amix`. No new FFmpeg filter needed.
- **Performance:** SFX are short (≤ 3 s); decode once, cache decoded PCM for preview.
- **Testing:** JVM — catalog validation (all files present, ≤ size cap, valid format), auto-attach rule table, `SfxAttachment` move/delete semantics.
- **Dependencies:** none for synthesis. See §4.15 for asset strategy.
- **Risks:** ⚠️ **Asset licensing.** Mitigation in §6 — everything either self-synthesised or permissively licensed, with licence metadata in the catalogue.
- **Acceptance:** all 10 categories browsable; an effect that auto-attaches SFX shows a one-tap "add SFX?" offer; SFX move with their parent, offer deletion with it, and stay editable.

### 4.9 Music system (Phase 9)

- **Existing:** `AddBackgroundAudio` with trim, volume, fade in/out, ducking, delay, multiple instances supported, `beats`, and voice-over recording. Good foundation.
- **Required changes:** waveform rendering **in the timeline**, per-clip gain automation, loop/repeat, **cached** waveform extraction (fixes B13), multi-track mixing UI, and a documented **open-licence music pack architecture** (no bundled commercial music).
- **Files:** `data/audio/WaveformExtractor.kt` (rewrite of `AudioWaveformExtractor.kt`), `data/audio/WaveformCache.kt` (new), `feature/audio/music/**` (new).
- **New components:** `WaveformCache` (content-hash keyed, LRU-evicted), `WaveformView`, `AudioMixer` (replacing scattered `adelay`/`amix` construction), `MusicPackManifest` (structure only — **no tracks shipped**).
- **Data model:** `AudioTrack` with `gainAutomation: List<AutomationPoint>`.
- **UI:** waveform strip per audio track; trim handles; fade handles; volume fader; ducking toggle with adjustable threshold.
- **Rendering:** existing `amix`/`adelay`/`volume`/`sidechaincompress` retained and centralised.
- **Performance:** waveform computed **once** per content hash, stored as a compact binary file (`ShortArray` peaks); timeline draws from memory-mapped file. Removes ffmpeg from all 4 hot call sites.
- **Testing:** JVM — cache hit/miss/eviction, peak extraction correctness against a synthetic sine, ducking parameter mapping.
- **Dependencies:** none new.
- **Risks:** cache growth — mitigated by LRU + a storage-usage screen and a "clear cache" action.
- **Acceptance:** importing music shows a waveform immediately on second import; 3+ audio tracks mix correctly; ducking audibly ducks music under voice-over; nothing copyrighted is bundled.

### 4.10 Beat detection (Phase 10)

- **Existing:** `AudioAnalyzer.detectBeats` — ffmpeg decodes to 8 kHz mono s16le, then 20 ms-window RMS energy, moving average ±50 windows, threshold `energy > avg*1.4 && energy > 1000`, local-max ±3 windows, 200 ms debounce. **Assessment: this is an onset detector, not a beat tracker.** It has no tempo estimate, no downbeat/bar phase, no silence handling, an input-level-dependent absolute threshold, and its 200 ms debounce **caps detection at 300 BPM**. Its output is consumed **only** as timeline snap targets (`VideoEditingActivity.kt:7985`) — it is never used for synchronisation.
- **Required changes:** replace with a real beat tracker, in `data/audio/BeatTracker.kt`:
  1. Decode to 22.05 kHz mono (better transient resolution than 8 kHz), streamed — never `readBytes()` the whole file.
  2. **Spectral-flux onset envelope** (FFT via `android.media`/`commons` math implemented in-module; no new dependency) with per-frame half-wave rectification.
  3. Normalise the envelope (adaptive median threshold) to remove level dependence — fixes the magic-1000 problem.
  4. **Tempo estimation** by autocorrelation of the onset envelope over 60–180 BPM.
  5. **Beat phase selection** by dynamic programming (Ellis-style) → a coherent beat grid.
  6. **Downbeat/bar inference** from the grid (every 4, with a confidence score).
  7. Map to `BeatMap` (§3.4). Cache by content hash. Streamed so memory is O(window).
- **Files:** replace `utils/AudioAnalyzer.kt`; new `data/audio/**`.
- **New components:** `BeatTracker`, `OnsetEnvelope`, `TempoEstimator`, `BeatGridFitter`, `BeatMapCache`.
- **Data model:** `Project.markers: List<Marker>` with `Marker(trackId, timeUs, kind=BEAT, strength)`.
- **UI:** AUDIO tool → BEATS → analyse with progress; markers drawn on the timeline; a density selector (Every / Every 2 / Every 4 / **Major**); tap-to-delete and drag-to-correct any marker; clear-all.
- **Rendering:** none. Beats are metadata.
- **Performance:** ≤ 3 s for a 4-minute track on a mid-range device, on a background dispatcher, cancellable, with progress. Memory bounded by streaming.
- **Testing:** JVM — synthetic click track at known BPM (assert grid within ±30 ms); white noise (assert low confidence); silence (assert empty, no crash); a real 120/128/140 BPM fixture checked into `src/test/resources` with an expected-grid fixture file. These are the tests that prove it works.
- **Dependencies:** none (FFT implemented in-module; avoids a dependency for one function).
- **Risks:** beat tracking is genuinely hard; mediocre results undermine SYNC TO BEAT. Mitigation: always expose manual markers, show the confidence score, and let the user drag. A wrong auto-grid that is user-correctable is acceptable; a silent wrong grid is not.
- **Acceptance:** on the 120/128/140 BPM fixtures the grid is within ±30 ms of ground truth; density modes and major-beat selection work; markers are correctable; analysis never blocks the UI.

### 4.11 Beat sync (Phase 11)

- **Existing:** nothing.
- **Required changes:** `SYNC TO BEAT` operating on photos, videos, effects and transitions, with settings for **beat frequency**, **sync strength**, **transition style**, and **motion strength**; output fully editable.
- **Files:** `feature/beat/**` (new), `data/audio/BeatSyncPlanner.kt` (new).
- **New components:** `BeatSyncSettings`, `BeatSyncPlanner` (pure: `(clips, BeatMap, settings) → List<ClipPlacement>`), `BeatSyncSheet`.
- **Data model:** operates on `Clip.timelineStartUs` and `Effect` params; no new persisted types.
- **UI:** a bottom sheet: strength slider, frequency (1/2/4/bar), transition style, motion strength, Apply / Preview / Cancel.
- **Rendering:** retiming clips shifts `timelineStartUs`; transitions move to the new cut positions.
- **Performance:** planning is O(clips × beats) and pure; sub-millisecond for realistic projects.
- **Testing:** JVM — `BeatSyncPlanner` is pure, so exhaustive tests: every clip lands on a beat, order preserved, gaps ≥ 0, strength=0 is a no-op, and it is **fully reversible by undo**.
- **Dependencies:** none.
- **Risks:** "sync strength" is ambiguous. **Decision:** strength ∈ [0,1] interpolates each clip's start between its current position (0) and the nearest beat (1) — deterministic, testable, and matches user intuition.
- **Acceptance:** applying SYNC TO BEAT moves every targeted clip onto a beat; the result is one undo step; every moved clip remains individually editable.

### 4.12 Photo slideshow (Phase 12)

- **Existing:** `generateVideoFromImage` exists (image → looping video with silent audio); image overlays; `Merge` accepts images via `MergeItem.isImage`. No slideshow generator.
- **Required changes:** `CREATE SLIDESHOW` — input photos + videos + optional music; auto-arrange, per-item duration, beat sync, transitions, Ken Burns photo motion, mixed photo/video, and 6 styles (**Cinematic, Fast, Chill, Meme, Minimal, Beat**).
- **Files:** `feature/slideshow/**` (new), `data/SlideshowPlanner.kt` (new), `assets/catalogs/slideshow_styles.json` (new).
- **New components:** `SlideshowStyle`, `SlideshowPlanner` (pure), `SlideshowSheet`, `PhotoMotion` (Ken Burns pan/zoom).
- **Data model:** produces normal `MediaTrack` clips + `Transition` effects; no new persisted types.
- **UI:** a single sheet — pick media, pick music, pick style, set per-photo duration, Generate.
- **Rendering:** images become clips (`-loop 1`), Ken Burns via time-parameterised `scale`/`crop`/`overlay` expressions.
- **Performance:** planning is pure and fast; rendering reuses the existing pipeline.
- **Testing:** JVM — `SlideshowPlanner` per style: item count, durations, transition placement, motion parameters, total duration within tolerance, determinism.
- **Dependencies:** none.
- **Risks:** Ken Burns via FFmpeg expressions is easy to get subtly wrong. Mitigation: golden tests on generated expression strings + visual spot-checks.
- **Acceptance:** all 6 styles generate a slideshow that plays, is beat-synced when music+BeatMap are supplied, and is fully editable afterwards.

### 4.13 Transitions and AUTO (Phase 13)

- **Existing:** 20 UI transitions, 44 filter-supported `xfade` types, `acrossfade` pairing, `Transition(index, type, durationMs)`. No AUTO.
- **Required changes:** group the catalogue into **Basic** (Cut, Fade, Dissolve), **Dynamic** (Zoom, Swipe, Push, Spin, Blur, Flash, Glitch) and **Meme** (Shake, Impact, Flash, Zoom). Add `AUTO` to apply a chosen transition across every cut in a selection (or the whole project), with alternation. Transition duration and alignment (centre/slow/fast) become editable.
- **Files:** `data/assets/TransitionCatalog.kt` (new), `assets/catalogs/transitions.json` (new), `feature/transitions/**` (new).
- **New components:** `TransitionCatalog`, `TransitionType` (enum, not a bare String — removes the 44-name stringly-typed set and the `smoothleft→coverleft` alias hack), `AutoTransitionPlanner`.
- **Data model:** `Transition` becomes a typed effect with a `TransitionType` enum; keep a `@SerializedName` mapping so old string values still load.
- **UI:** TRANSITIONS → Basic / Dynamic / Meme tabs; **AUTO** toggle + "apply to selection/all".
- **Rendering:** existing `xfade`/`acrossfade` chaining retained; `blur` via `boxblur`, `glitch` via `rgbashift`+`noise`, `spin` via `rotate`, `shake` via time-parameterised `crop`/`rotate` offsets.
- **Performance:** filter graph grows with transition count; cap enforced (see risks).
- **Testing:** JVM — every `TransitionType` maps to a valid `xfade` name or a custom filter chain; AUTO planner is pure and correct for all selection shapes.
- **Dependencies:** none.
- **Risks:** ⚠️ **`xfade` requires overlapping inputs; a very long chain of xfades is O(n) in filter complexity and slows export.** Mitigation: cap consecutive xfade gaps (fall back to `concat` beyond the cap) and warn the user. Also: `xfade` has a hard `duration` bound relative to the shortest input — validate at plan time and report a clear error rather than failing at export.
- **Acceptance:** all 3 groups browsable; AUTO applies across a selection correctly; an xfade duration that cannot be satisfied produces a clear message at plan time, not a failed export.

### 4.14 Filters (Phase 20)

- **Existing:** 8 LUT presets (`none, vintage, warm, cool, monochrome, contrast, vignette, negative, crossprocess`) with preview JPEGs; `ColorFilter(index, filterName)`.
- **Required changes:** intensity slider, custom `.cube` LUT import, and a before/after scrubber in the preview.
- **Files:** `data/assets/FilterCatalog.kt` (new), `feature/effects/filter/**` (new).
- **New components:** `LutLoader` (parse/validate `.cube`), `FilterPreset`.
- **Data model:** `FilterEffect(lutName, intensity)`.
- **UI:** FILTERS → presets with intensity; "import LUT".
- **Rendering:** `lut3d=file=…` with intensity via a `blend` against the unfiltered stream.
- **Performance:** LUT applied in the same pass; `.cube` parsed once and cached.
- **Testing:** JVM — `.cube` parser (size limits, malformed input, 1D vs 3D), golden filter strings.
- **Dependencies:** none.
- **Risks:** malicious/huge `.cube` files — mitigate with size/entry caps and a parse timeout.
- **Acceptance:** intensity 0 is a true no-op; a custom LUT applies and exports; a malformed LUT is rejected with a clear message.

### 4.15 Keyframes (Phase 19)

- **Existing:** position, opacity, speed, mask channels; `KeyframePoint(timeMs, valueX, valueY, interpolationType)`; `interpolationType` **never honoured**; UI in `keyframe_editing_toolbar.xml`; `showKeyframePropertyMenu`, `handleKeyframeSliderChange`.
- **Required changes:** honour `interpolationType` with a real easing set (linear, ease-in/out/in-out, hold, step, elastic, bounce, back), add **scale** and **rotation** channels, allow add/remove at playhead, and render a keyframe curve in the tool.
- **Files:** `core/math/Easing.kt` (new), `core/model/Keyframe.kt` (rewrite), `feature/effects/keyframe/**` (new), new compiler replacing `buildFFmpegInterpolationExpr`.
- **New components:** `EasingFunction`, `KeyframeTrack<T>`, `KeyframeCompiler` (→ FFmpeg expression), `KeyframeCurveView`.
- **Data model:** `Keyframe(timeUs, value: Vec2, easing)`, `KeyframeTrack(channel, keyframes, defaultValue)`.
- **UI:** channel selector, add/remove at playhead, curve editor, easing picker.
- **Rendering:** expression compiler emits nested `if(lt(t,…))` as today, now with easing applied per segment.
- **Performance:** compile once per render plan; the generated expression is size-sensitive, so keyframes are decimated to a documented density (default 1 keyframe per 2 frames of animation) with a warning when decimating.
- **Testing:** JVM — easing function value tables; expression compiler golden tests; a decimation test asserting max expression size.
- **Dependencies:** none.
- **Risks:** expression string explosion with many keyframes — mitigated by decimation and a hard size cap with a clear error.
- **Acceptance:** all 6+ channels animate; easing visibly differs from linear; export matches preview; an over-dense keyframe set warns instead of producing a broken export.

### 4.16 Audio ducking (Phase 21)

- **Existing:** `sidechaincompress=threshold=0.03:ratio=4:attack=5:release=500` — real and working.
- **Required changes:** expose threshold/ratio/attack/release as user parameters, add an auto-duck mode (duck whenever speech-like energy is detected), and show the ducking envelope in the UI.
- **Files:** `core/model/AudioEffect.kt` (Ducking), `feature/audio/**`, parameterised compiler.
- **New components:** `DuckingParams`, `SpeechActivityDetector` (simple RMS-envelope VAD), `DuckingEnvelopeView`.
- **Data model:** `AudioEffect(DUCKING, params)` replacing the current `ducking: Boolean` on `AddBackgroundAudio` (keep reading the old flag for legacy projects).
- **UI:** toggle + advanced sliders; "auto" checkbox.
- **Rendering:** existing filter retained, now parameterised.
- **Performance:** auto-duck requires a cheap VAD pass over the voice-over track — do it on a background dispatcher, cache per file hash.
- **Testing:** JVM — parameter mapping to filter string; VAD on a synthetic speech-envelope signal.
- **Dependencies:** none.
- **Risks:** low.
- **Acceptance:** ducking parameters take effect and are audible; legacy projects with `ducking=true` still load with default params.

### 4.17 Export presets and multi-platform (Phase 15)

- **Existing:** 720/1080/1440/2160, 3 quality tiers, audio-only. No platform presets.
- **Required changes:** presets for **Original, Instagram Reel, YouTube Short, YouTube Video, Snapchat, Custom**, each encoding width/height/fps/bitrate/codec/container/audio rate; sensible auto-selection; advanced settings remain available; originals never modified.
- **Files:** `feature/export/ExportPresets.kt` (new), `engine/model/ExportSpec.kt` (new), `engine/render/ExportPipeline.kt` (rewrite of `ExportService` internals).
- **New components:** `ExportPreset`, `ExportSpec`, `ExportPipeline` (staged, §3.5), `ProgressBus`.
- **Data model:** `Project.exportSpec`; `ExportSpec` **replaces the never-consumed `VideoProject.ExportConfig`** (P3-29).
- **UI:** preset chooser with a live "estimated size / estimated time" readout; Advanced expands codec/bitrate/fps/audio.
- **Rendering:** `h264_mediacodec` primary, `libx264` fallback (keep). HEVC optional and device-gated. Fixes carried from the audit: `-movflags +faststart` (P3-18); a no-upscale guard `scale=-2:min(<h>,ih)` (P3-19); one documented rate-control policy across both encoder branches rather than bitrate-for-hardware / CRF-23-for-software (P3-17); retry rebuilt **from parameters** and gated on encoder-specific return codes, never a blind string replace (P3-20).
- **Performance:** staged caching; hard rule that originals are read-only; `MediaStore` publish on completion with `IS_PENDING=1` → write → `IS_PENDING=0` so an interrupted export can never leave a truncated file in the user's library (P0-6); temp files always cleaned in `finally` (P1-11).
- **Data integrity:** the no-operations fast path must go through `MediaStore`/SAF — the current direct-public-`File` write fails on API 29+ (P0-1) — and must refuse audio-only without a real transcode (P0-2).
- **Testing:** JVM — preset → `ExportSpec` mapping; instrumentation — a real 10 s export produces a playable MP4 with the right dimensions/fps/duration.
- **Dependencies:** none new.
- **Risks:** platform limits change; mitigation — presets are **data-driven JSON**, so they can be updated without a code change.
- **Acceptance:** all 6 presets export correctly; Reel/Shorts produce 1080×1920 (or 1920×1080) at ≤30 fps within platform size guidance; advanced settings still work; source files are untouched.

### 4.18 Sharing and the share stack (Phase 16 / 17)

- **Existing:** **no video share intent exists** — only error-log sharing and file pickers. Save-to-gallery via MediaStore.
- **Required changes:** native Android sharing via `ACTION_SEND` with `video/*` and a `content://` URI grant. Deep-link intents for Instagram, Snapchat, YouTube, WhatsApp — **only where the platform publishes a documented intent contract**; otherwise fall back to the share sheet. A **share stack** that prepares the right export version per target, **re-encodes only when the spec actually differs** (dedupe by `ExportSpec` hash), and shows per-target readiness.
- **Files:** `feature/export/share/**` (new), `engine/render/ShareStackPlanner.kt` (new).
- **New components:** `ShareTarget` (Instagram, Snapchat, YouTube, WhatsApp, ShareSheet), `ShareStackPlanner` (targets → distinct `ExportSpec`s → dedupe), `ShareSheet`, `ShareReadinessRow`, `FileProvider` config.
- **Data model:** none persisted beyond `ExportSpec` per target.
- **UI:** a share screen with rows: `Instagram ✓`, `Snapchat ✓`, `YouTube ✓`; tapping shares; "Preparing…" states.
- **Rendering:** reuse `ExportPipeline`; dedupe identical specs so 3 targets at 1080×1920/30 encode **once**.
- **Performance:** dedupe is the whole point — no duplicate encoding.
- **Testing:** JVM — `ShareStackPlanner` dedupe correctness; instrumentation — `FileProvider` grants resolve, share intent carries a valid readable URI.
- **Dependencies:** **AndroidX `FileProvider`** (Apache-2.0) — required to hand out a content URI safely.
- **Risks:** ⚠️ **No faking direct publishing.** Instagram/Snapchat/YouTube do not publish general public direct-upload intent APIs usable by third-party editors. The implementation must therefore: attempt the documented share/deeplink, detect absence via `PackageManager`, and fall back to the share sheet transparently. This is a product-integrity requirement, not a technical limitation to engineer around.
- **Acceptance:** sharing works on a device with none of the target apps (share sheet) and with them installed (targeted intent where supported); the share stack encodes once when specs match and shows accurate per-target readiness.

### 4.19 Keyframes/undo of every operation (Phase 19)

- **Existing:** snapshot undo, 30 deep, no coalescing.
- **Required changes:** `HistoryManager` + `EditOp` (§3.6) covering **trim, split, delete, move, effects, text, audio, transitions, templates, beat sync**.
- **Files:** `core/model/EditOp.kt` (new), `feature/editor/HistoryManager.kt` (new), `EditorStore` (new), retirement of `EditCommand.kt`/`HistoryState`.
- **New components:** `EditorStore`, `EditOp` implementations per operation, `CoalescingScope`.
- **Data model:** `EditorState(project, selection, playheadUs, zoom, snapping)`.
- **UI:** undo/redo buttons reflecting `HistoryManager` state; a toast naming the undone action.
- **Rendering:** n/a.
- **Performance:** no snapshots → memory bounded by the op list, not by project size × 30.
- **Testing:** JVM — for every `EditOp`: `invert(apply(s)) == s`; a 36-op mixed sequence; coalescing produces 1 history entry for 50 slider ticks.
- **Dependencies:** none.
- **Risks:** forgetting to route a mutation through `EditorStore` silently breaks undo. **Mitigation:** an architecture test forbidding direct `Project` mutation outside `EditorStore`, plus a code review checklist.
- **Acceptance:** all 10 named operations are undoable; undo/redo survives process death (history persisted); a 10,000-op stress shows bounded memory.

### 4.20 Performance (Phase 20)

Covered per-feature above. Cross-cutting work:

| Area | Current | Target |
|---|---|---|
| Preview | Full-sequence FFmpeg encode per edit; leaked processes | Media3 Composition + GPU effects; preview window only; **no FFmpeg in preview**; <150 ms first frame |
| Thumbnails | LRU(15) in memory, ffmpeg per frame | Disk-backed `ThumbnailRepository`, extracted via MediaMetadataRetriever, async, shared across projects |
| Waveform | ffmpeg on every toolbar interaction (4 sites) | Cached by content hash, binary peak file, memory-mapped |
| Beat analysis | ~instant but naive, whole-file `readBytes()` | Streamed, ≤3 s for 4 min, cancellable, progress |
| Timeline | 3 discrete zoom levels | Continuous zoom, 200 clips @ 60 fps, no `onDraw` allocation |
| GIF decode | deprecated `android.graphics.Movie` | Media3/Coil frame source with a frame budget |
| Export | monolithic filter graph | Staged pipeline with per-stage progress and caching |
| Main thread | FFmpeg orchestration in the Activity | All heavy work off the main thread; UI never calls FFmpeg |

### 4.21 Accessibility (Phase 31)

- **Existing:** none of substance. Custom `Canvas` views expose no semantics.
- **Required changes:** `contentDescription` on all interactive custom views; **accessibility actions** on timeline clips (extend, trim, delete, split, move) and on overlays (move, resize, rotate, delete); **keyboard/D-pad and switch-access** support for the timeline; 48 dp minimum touch targets; text scaling honoured in layouts; `Snackbar`/live-region announcements for undo, export progress and errors; respect `Settings.Global.ANIMATOR_DURATION_SCALE = 0` by disabling non-essential animation.
- **Files:** `ui/a11y/**` (new), all custom views, all layouts.
- **New components:** `TimelineAccessibilityDelegate`, `ClipAccessibilityNodeProvider`, `Announcer`.
- **Testing:** instrumentation — TalkBack can select, trim and delete a clip using only accessibility actions.
- **Risks:** retrofitting custom-View accessibility is fiddly; budget real time for it.
- **Acceptance:** every editing action is reachable without touch precision; automated accessibility scanner reports no critical issues on the editor screens.

### 4.22 Error handling (Phase 32)

- **Existing:** `ErrorCode.kt` (9 lines), `createDiagnosticReport`, `ErrorDisplayActivity` with shareable logs, and a documented error-code wiki.
- **Required changes:** a typed `AppError` sealed hierarchy (Media/Decode/FFmpeg/Permission/Storage/State) surfaced consistently; **fail fast at plan time** rather than at export; guaranteed temp-file cleanup on every failure path; user-facing messages that say what to do, with the technical detail preserved in the shareable log.
- **Files:** `core/result/AppError.kt` (new), `ui/error/**` (new), retrofit `ErrorDisplayActivity`.
- **New components:** `AppError`, `ErrorPresenter`, `CleanupScope` (guarantees temp cleanup via `finally`).
- **Testing:** JVM — error mapping table; instrumentation — an induced FFmpeg failure produces a clean error and **no leaked temp files**.
- **Acceptance:** every failure path yields a typed error, a recoverable UI, and zero orphaned temp files.

### 4.23 Optional AI (Phase 21 of the brief)

**Decision: AI is explicitly out of scope for Mhirex 1.x and is architecturally isolated if added later.**

Rationale: AI is not the product's identity; a bundled model would add tens of MB, break the GPL/F-Droid story, and require network or on-device inference the mid-range target cannot afford. Adding it later is cheap *because* the architecture isolates editing intent from implementation: a tool that emits `EditOp`s is all an AI feature needs.

Reserved seams (built now, used never unless justified):
- `RemoveSilenceTool` → emits trims as `EditOp`s.
- `AutoCaptionTool` → emits `TextEffect`s; SRT parsing already exists.
- `SmartClipSelectionTool` → emits clip removals; needs no new model.

Acceptance for this phase: the seams are documented and the app ships with no model, no network permission, and no AI code path.

---

## 5. Dependency Plan

### 5.1 Existing dependencies

| Dependency | Version | License | Verdict |
|---|---|---|---|
| `com.google.android.exoplayer:exoplayer-core/ui` | 2.19.1 | Apache-2.0 | **Replace** → Media3 |
| `com.antonkarpenko:ffmpeg-kit-full-gpl` | 2.1.0 | **GPL-3.0** | **Keep short-term, evaluate downgrade** (§5.3) |
| `com.google.android.material:material` | 1.9.0 | Apache-2.0 | Keep |
| `com.airbnb.android:lottie` | 3.4.0 | Apache-2.0 | Keep (sticker/meme animation) |
| `com.mikepenz:aboutlibraries-core` / `aboutlibraries` | 11.2.3 | Apache-2.0 | Keep — required for L1/L3 attribution |
| `com.google.code.gson:gson` | 2.10.1 | Apache-2.0 | Keep |
| `androidx.core:core-ktx` | 1.13.1 | Apache-2.0 | Keep |
| `androidx.lifecycle:*` | 2.8.6 | Apache-2.0 | Keep |
| `androidx.appcompat` | 1.7.0 | Apache-2.0 | Keep |
| `androidx.constraintlayout` | 2.1.4 | Apache-2.0 | Keep |
| `androidx.recyclerview` | 1.3.2 | Apache-2.0 | Keep |
| **kotlinx-coroutines** | transitive only | Apache-2.0 | **Declare explicitly** |
| JUnit4 / Espresso / androidx.test.ext:junit | — | Apache-2.0 / EPL | Expand (§8) |

### 5.2 New dependencies (each justified)

| Dependency | License | Why | Avoidable? |
|---|---|---|---|
| `androidx.media3:media3-exoplayer` | Apache-2.0 | Replaces EOL ExoPlayer 2.x. Required for `Composition`-based preview, `ContentPosition` scrubbing, and modern codec handling | No |
| `androidx.media3:media3-ui` | Apache-2.0 | `PlayerView`, `StyledPlayerView` successor | No |
| `androidx.media3:media3-effect` | Apache-2.0 | GPU `Effect` chain for preview transforms/colour — the core of fixing B1 | No |
| `androidx.media3:media3-common` | Apache-2.0 | Media item/timeline primitives | No |
| `androidx.media3:media3-transformer` | Apache-2.0 | Future high-quality export path if ffmpeg is retired | **Yes** — defer; not needed for 1.x |
| `androidx.core:core-splashscreen` | Apache-2.0 | Correct Android 12+ splash for the Mhirex brand | Yes, but correct |
| `androidx.security:security-crypto` | Apache-2.0 | EncryptedSharedPreferences for settings | **Yes** — use plain prefs; settings are not sensitive. **Rejected.** |
| `io.coil-kt:coil` + `coil-video` | Apache-2.0 | Replaces deprecated `android.graphics.Movie` for GIF/sticker decoding (B4); adds thumbnail caching | No (B4 must be fixed) |
| `org.jetbrains.kotlinx:kotlinx-collections-immutable` | Apache-2.0 | Structural sharing for `List<Clip>` — avoids O(n) copies per edit | Yes, but materially improves timeline perf |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | Apache-2.0 | Safer JSON with schema evolution for the versioned project format (B2) | **Yes** — Gson + hand-written codec also works. **Decision: keep Gson** to avoid a second JSON stack; the real fix is `schemaVersion` + migrations, not the library. |
| `com.google.android.gms:play-services-location` | — | — | **Rejected.** Not needed. |
| Any AI/ML runtime (TFLite/ONNX) | various | — | **Rejected for 1.x** (§4.23) |
| Any music/SFX pack | commercial | — | **Rejected.** (§6) |

### 5.3 ffmpeg-kit strategy (L1 + L2)

Sequenced, lowest-risk-first:

1. **Short term (Mhirex 1.0):** keep `ffmpeg-kit-full-gpl` — replacing the render engine is not the point of this project, and the filter-graph compiler is our strongest asset. **But** add prominent, honest GPL-3.0 attribution in the About/Licenses screen and publish a corresponding-source offer for the combined work, as GPL-3.0 §6 requires. This closes the actual compliance gap. **Also bump the pin from `2.1.0` to `2.2.1`** (current as of 2026-07-13) and vendor the artifact with a recorded SHA-256 so the binary is auditable.
2. **Evaluate downgrade:** the app already prefers `h264_mediacodec` (hardware). Software fallback uses `libx264`. If `ffmpeg-kit-min-gpl` (LGPL: zlib + MediaCodec only) can serve the pipeline — it can, since `drawtext`/`xfade`/`overlay`/`amix`/`sidechaincompress` are all LGPL-buildable with freetype — the binary drops out of GPL entirely. **Task: verify the exact filter set against an LGPL build, then downgrade if it passes.** This is the single highest-value licensing action available.
3. **Long term:** track FFmpegKitNext (source-only) and be ready to build our own AAR via its scripts, giving us pinned FFmpeg versions with security patches.

### 5.4 Test dependencies (new, justified by §8)

`kotlinx-coroutines-test`, `app.cash.turbine` (Flow testing), `io.mockk:mockk`, `org.robolectric:robolectric`, `com.google.truth:truth`. All Apache-2.0. Each maps to a specific testing need; none is speculative.

---

## 6. Licensing and Assets

### 6.1 Project license

- **LibreCuts is MIT, © 2024 Tharun Birla.** MIT §: *"The above copyright notice and this permission notice shall be included in all copies or substantial portions."*
- **Mhirex may be MIT and may add its own copyright line, cumulatively.** Mhirex **must not** remove or obscure the original notice. Therefore the rebrand keeps: the `LICENSE` copyright line, a `NOTICE` file crediting Tharun Birla, the About screen's "Based on LibreCuts by Tharun Birla (MIT)", and unmodified source headers.
- This is a hard constraint on §4.1, not a preference.

### 6.2 Dependency licenses

- AboutLibraries already generates a third-party license screen — keep and extend it with the **ffmpeg-kit GPL-3.0** entry, which is currently missing.
- Media3, Material, Coil, Lottie, Gson, coroutines, kotlinx-collections-immutable: all Apache-2.0 — attribution only.
- GPL-3.0 obligations for the ffmpeg binary: see §5.3.

### 6.3 Existing bundled assets

| Asset | Assessment |
|---|---|
| `assets/fonts/Roboto-Regular.ttf` | Roboto is **Apache-2.0** — safe. Bundle notice in the Licenses screen. |
| `res/drawable/trans_preview_*.webp` (~60 files, 3 frames each) | **Origin undocumented in-repo.** These preview the upstream transition set. Action: establish provenance (likely generated or upstream-author-created) and record the licence in `ASSETS.md`; regenerate if provenance cannot be established. |
| `res/drawable/filter_preview_*.jpg` (9) | Same provenance question. |
| `res/raw/film.json` | Content to be confirmed; document once identified. |
| Mipmap launcher icons, banner | Upstream author's work → retain attribution. |
| `fastlane/metadata/**` screenshots | Upstream author's work → retain attribution. |

**Action:** create `ASSETS.md` recording origin + licence for **every** bundled asset. Any asset whose provenance cannot be established is regenerated or removed. This is a release blocker.

### 6.4 New asset requirements

| Category | Strategy | Constraint |
|---|---|---|
| **Music packs** | **No music is bundled.** Ship only a documented `MusicPackManifest` format (see §4.9) and let users point at their own licensed audio | No copyrighted commercial music. Any future bundled pack must be CC0 / CC-BY with attribution, with licence files shipped in-app |
| **SFX** | **Prefer synthesis.** Generate short effects (impact, whoosh, pop, bass, notification, camera, glitch) procedurally and bundle the **synthesiser code**, not recordings — the code is ours and the output is unambiguous | Synthesised or CC0 only. No sampled commercial SFX libraries |
| **Meme text presets** | Pure text/style data created by us | No third-party fonts. No copyrighted imagery |
| **Stickers/emoji packs** | **No bundled third-party sticker art initially.** Use the **system emoji font** (present on-device) and user-supplied images | No ripped GIFs, no trademarked characters, no unlicensed meme imagery |
| **Fonts** | System fonts + `Roboto-Regular.ttf` (Apache-2.0) + **user-imported** fonts via SAF | Do **not** bundle commercial display fonts. User-imported fonts are the user's responsibility; surface a one-line notice |
| **Filters** | 8 existing LUTs (provenance to be confirmed) + user `.cube` import | No commercial LUT packs bundled |
| **Icons** | Material Symbols (Apache-2.0) or hand-authored vector drawables | No proprietary icon sets |

**Principle: synthesise or omit. Never ship an asset whose licence we cannot state.**

### 6.5 Explicit prohibitions

1. No copyrighted commercial music, SFX, fonts, stickers, or meme imagery.
2. No copying proprietary UI, branding, or trade dress from any commercial editor.
3. No faking a platform's publishing API (see §4.18).
4. AI features ship disabled/unbuilt by default (§4.23).
5. GPL-3.0 obligations are disclosed and met (§5.3).

---

## 7. Performance Plan

Targets assume a **mid-range device** (Snapdragon 6-series / Dimensity 700-class, 4–6 GB RAM), Android 11+.

| Subsystem | Strategy | Budget |
|---|---|---|
| **Timeline rendering** | Custom `View`; pre-allocated `Paint`/`Path`/arrays; **zero allocation in `onDraw`/`onTouchMove`**; `drawVertices` filmstrips; dirty-rect `invalidate()`; `postOnAnimation` gesture handling | 60 fps @ 200 clips; frame time < 16 ms |
| **Thumbnail generation** | Disk-backed `ThumbnailRepository` keyed by (contentHash, timeMs, bucket); extracted with `MediaMetadataRetriever`; bounded concurrency (2); LRU in memory; progressive fill so the timeline is usable before thumbnails exist | 200 thumbs < 8 s; memory < 24 MB |
| **Audio waveform** | Cached by content hash as a binary peak file; computed once on a background dispatcher; memory-mapped read for drawing | 4 min track < 1.5 s; cached read < 20 ms |
| **Beat analysis** | Streamed decode; FFT in-module; adaptive normalisation; autocorrelation tempo; DP phase fit; cancelled on navigation; progress reported | ≤ 3 s for 4 min; memory O(window) |
| **Preview rendering** | **Media3 `Composition` + GPU effects; preview window only around the playhead; scrub from proxy; zero FFmpeg.** Debouncer coalesces edits by dropping stale requests | First frame < 150 ms; 30/60 fps playback |
| **Memory** | No full-resolution bitmap retention; bounded caches; `onTrimMemory` response; no Activity-held Context in long-lived objects; recycle replaced by `Bitmap` pooling + `inBitmap` decode options | < 180 MB typical; no OOM at 4 GB |
| **Export** | Staged pipeline; per-stage progress; hardware encode with software fallback; intermediate caching; guaranteed temp cleanup | ≥ 1× realtime on mid-range hardware encode |
| **Background processing** | `Dispatchers.IO` + `Dispatchers.Default` split (I/O vs CPU); coroutines throughout; foreground service only for export (user-visible); WorkManager-free by design (no deferrable background work) | Main thread never blocked; no ANR |
| **Mid-range specifics** | Avoid >2 concurrent FFmpeg processes; cap filter-graph size; decimate keyframes; hardware-encode first; downscale proxies aggressively; disable heavy animations under low-end | No crash, no ANR, usable at 4 GB |

**Profiling:** Macrobenchmark on a physical mid-range device is the acceptance gate for the performance phase. Frame-timing and memory assertions are measured, not estimated.

---

## 8. Testing Plan

Current state: **two placeholder tests, zero real coverage.** The test suite is built from the ground up.

| Layer | Tool | Scope |
|---|---|---|
| **Unit** | JUnit4 + Truth + coroutines-test | Pure logic: `TimelineLayoutEngine`, `SnapEngine`, `EasingFunction`, `KeyframeCompiler`, `EffectCompiler`, `FFmpegEscaper`, `FFmpegCommandBuilder`, `BeatTracker` (synthetic + fixtures), `WaveformCache`, `LutLoader`, `SlideshowPlanner`, `BeatSyncPlanner`, `AutoTransitionPlanner`, `ShareStackPlanner`, `ExportPreset` mapping, `ProjectMigrator` (upstream `.lcprj` fixtures), `PresetCatalog` validation, `EditOp` invertibility |
| **Integration** | Robolectric | `ProjectStore` atomic write + crash recovery; `ExportService` lifecycle; `MediaStore` interactions; `EditorStore` + `HistoryManager` sequences |
| **UI / instrumentation** | Espresso + androidx.test | Tool open/close flows; timeline gestures; export-to-gallery; share intent; permission flows; TalkBack-only editing (§4.21) |
| **Rendering** | Golden string tests + device spot-check | `drawtext`/`overlay`/`xfade`/`crop` expression generation; preview↔export visual parity for text position, size, colour, rotation, masks |
| **Export** | Instrumented on device | 10 s project → playable MP4; assert container, dimensions, fps, duration, audio presence; induce a failure and assert clean error + no temp leakage |
| **Project save/load** | JVM + Robolectric | Round-trip equality; `schemaVersion` migration from every historical version; **corrupt/truncated file → quarantined, user informed, app does not crash**; missing media file → precise per-clip error |
| **Undo/redo** | JVM | `invert(apply(s)) == s` for every `EditOp`; 36-op mixed sequence; coalescing; 10,000-op memory bound; history survives process death |
| **Performance** | Macrobenchmark | Timeline frame timing @ 200 clips; preview first-frame latency; memory; export throughput. **Measured on physical mid-range hardware** |
| **Static analysis** | detekt (Apache-2.0) + Android Lint | New in CI; blocks on new violations |
| **CI** | GitHub Actions | `assembleDebug`, `lintDebug`, `testDebugUnitTest`, **`connectedDebugAndroidTest` on emulator**, Macrobenchmark on a scheduled job, detekt. Upload reports as artifacts. Java 17. Gradle cache. |

**Coverage rule:** every new `core/`, `data/`, and `engine/` class requires unit tests. CI fails on regression of the coverage floor (set at 60% for `core`+`data`, enforced with JaCoCo).

**Golden assets:** `src/test/resources/` holds a click track at 120/128/140 BPM, a speech-envelope signal, a synthetic sine, a 10 s colour-bars video, and upstream `.lcprj` fixtures. These make rendering and analysis claims verifiable rather than aspirational.

---

## 9. Release Plan

Incremental, buildable at every step. Each stage ships working software.

| Stage | Content | Gate |
|---|---|---|
| **0 — Rebrand + P0 fixes** | App name → Mhirex in 17 locales; package/namespace → `com.mhirex.editor`; NOTICE/ASSETS.md; About screen with dual attribution; GPL-3.0 disclosure; delete dead code; declare coroutines explicitly; fix the "always true" branches; fix deprecated Intent/clip APIs. **Plus all six P0 defects (§0.9): P0-1 no-op export on API 29+, P0-2 audio-only-as-mp3, P0-3 speed+reverse duration corruption, P0-4 hardcoded `.mp4` temp extension, P0-5 font alias not path, P0-6 `IS_PENDING` on MediaStore.** Also P1-7 (ANR), P1-8 (cancel-as-failure), P1-9 (session leak), P1-11 (temp cleanup), P1-12 (global cancel), SEC-31 | Builds; all locales say Mhirex; legacy `.lcprj` still opens; **no-op save works on Android 10+**; text/subtitles render in export; no temp files leak on any failure path |
| **0.5 — Export quality** | P3-18 `+faststart`, P3-19 no-upscale guard, P3-17 unified rate control, P3-20 parameter-based retry, P3-21 correct progress total, P3-22 `LC-202` mapping, P3-26 warn on unknown transition | Exported MP4s stream progressively; 480p→2160p is refused or clamped; hardware and software exports documented and consistent |
| **1 — Foundations** | `core/` primitives (Timebase, Rational, Result, Easing); `ui/` design system (Mhirex tokens, components); architecture test enforcing layer dependencies | Unit tests green; lint green |
| **2 — Project v2** | `Project`/`Track`/`Clip`; `schemaVersion` + migrations; `ProjectStore` with atomic writes and recovery; `LegacyProjectMigrator` | Migrator fixture tests pass; crash-recovery tests pass |
| **3 — Editor state** | `EditorStore`; `EditOp`; `HistoryManager` with coalescing; all existing operations routed through it | Undo/redo covers all operations; `invert(apply(s))==s` |
| **4 — Player + preview** | Media3 migration; `Composition`-based preview with GPU effects; **remove all FFmpeg from the preview path**; deprecate `android.graphics.Movie` | First frame < 150 ms; no FFmpeg process spawned by preview; device test |
| **5 — Timeline v2** | `TimelineView`; continuous zoom; multi-select; `SnapEngine` with beat candidates; frame precision | 200 clips @ 60 fps; all gestures work; undoable |
| **6 — Media & audio** | Cached waveforms in the timeline; multi-track mixing UI; `ThumbnailRepository`; ducking parameters | No FFmpeg on waveform interaction; 3+ tracks mix correctly |
| **7 — Text** | `TextStyle` + `TextAnimation` + shadow/stroke/background/scale/rotation; shared `TextLayoutMetrics` for WYSIWYG; meme-text catalogue | Round-trip preview→export identical; escaping torture test passes |
| **8 — Effects & packs** | `Effect` stack; intensity; custom LUT; effect-stack presets; **meme packs** (incl. DEAD PACK); user-saved stacks | Packs apply and stay editable; ordering golden tests pass |
| **9 — SFX** | SFX catalogue (synthesised); auto-attach; move/delete-with-parent; SFX editing | All 10 categories; auto-attach offer works |
| **10 — Beat** | `BeatTracker` (spectral flux + tempo + DP phase); `BeatMap`; markers UI; density modes; manual correction; **SYNC TO BEAT** | ±30 ms on 120/128/140 BPM fixtures; all 4 density modes; undoable |
| **11 — Transitions & templates** | Typed `TransitionType`; Basic/Dynamic/Meme groups; **AUTO**; template system; `SlideTransitionType` reuse for template playback | All groups browsable; AUTO correct for all selections |
| **12 — Slideshow** | `SlideshowPlanner`; 6 styles; Ken Burns; photo+video mixing | All styles generate, play, and stay editable |
| **13 — Export & share** | Platform presets; staged `ExportPipeline`; `ShareStackPlanner` with dedupe; share intents; fallback | 6 presets export correctly; share stack encodes once; fallbacks work |
| **14 — Hardening** | Accessibility pass; error handling pass; performance tuning on physical mid-range hardware; Macrobenchmark | Accessibility scanner clean; no ANR; budgets met |
| **15 — Release** | `ASSETS.md` complete; licence compliance audited; store metadata rebranded; changelog; F-Droid/Obtainium metadata | Legal checklist (§6) signed off |

**Explicitly deferred:** AI tools (§4.23), `media3-transformer` export path, HEVC/HDR, multi-device sync, i18n expansion beyond the inherited 17 locales.

---

## 10. Architecture Decision Log

Recorded because these decisions are load-bearing and later phases must not silently reverse them.

| # | Decision | Rationale | Status |
|---|---|---|---|
| **AD-1** | Single `:app` module; package boundaries + architecture test | Modularity without Gradle build overhead given the heavy ffmpeg AAR | Accepted |
| **AD-2** | `namespace` **and** `applicationId` both → `com.mhirex.editor` | **REVERSED from the original "keep `applicationId = com.tharunbirla.librecuts`" decision, at the user's explicit request.** Trading upgrade continuity for a package id Mhirex owns, which Play Store publication requires. Accepted loss: no LibreCuts→Mhirex upgrade path (separate app, reinstall, orphaned projects/settings), plus stale F-Droid/Obtainium/Weblate listings that must be re-listed. Reverting later requires a data-import migration | Accepted (revisit with a migration) |
| **AD-3** | Retain MIT; add Mhirex attribution cumulatively; never remove Tharun Birla's notice | MIT requires the copyright notice in all copies | Binding |
| **AD-4** | Disclose GPL-3.0 for the ffmpeg binary and publish corresponding source; evaluate an LGPL downgrade | Pre-existing compliance gap; LGPL downgrade is the highest-value fix | Accepted |
| **AD-5** | Keep XML + ViewBinding; do **not** migrate to Compose | 60 existing layouts and heavily Canvas-based custom views. Compose would be a second rewrite on top of one rewrite. Not required by any feature | Accepted |
| **AD-6** | Timeline is a custom `View`, not Compose/RecyclerView | Filmstrip + trim-handle + multi-lane drawing is a single continuous canvas; a RecyclerView fights it | Accepted |
| **AD-7** | Preview via Media3 + GPU effects; FFmpeg for **export only** | Directly fixes B1, the biggest defect. Enforced by "UI never calls FFmpeg" | Accepted |
| **AD-8** | Replace snapshot undo with `EditOp` + `HistoryManager` | Snapshots are O(project × 30) memory and cannot be selective | Accepted |
| **AD-9** | JSON project format, Gson retained, with real `schemaVersion` + migrations | Fixes B2 without adopting a second JSON stack | Accepted |
| **AD-10** | All model time is `Long` microseconds; `Rational` fps | Frame precision without float drift | Accepted |
| **AD-11** | Model coordinates normalised 0.0–1.0; converted to pixels only at compile | Removes the px-vs-normalised class of bugs | Accepted |
| **AD-12** | Legacy filter-graph compiler retained alongside the new staged pipeline | Existing `.lcprj` files must keep exporting | Accepted |
| **AD-13** | SFX synthesised in code, not sampled assets | Unambiguous licensing | Accepted |
| **AD-14** | No bundled music | Copyright | Binding |
| **AD-15** | No AI code in 1.x; seams reserved | AI is not the product identity; mid-range devices cannot afford it | Accepted |
| **AD-16** | Gradle stays 8.9 / AGP 8.7.1; JDK pinned to 17 in CI | Verified working baseline; Java 25 breaks AGP 8.7.1 | Accepted |
| **AD-17** | Declared test deps: coroutines-test, Turbine, MockK, Robolectric, Truth, detekt | §8 needs them; each maps to a specific gap | Accepted |

---

## 11. Verification Checklist (Phase 22 gate)

Run before declaring any release candidate complete. Each item must be **executed**, not assumed.

- [ ] Clean build from scratch (`clean assembleDebug assembleRelease`)
- [ ] Full unit test suite green
- [ ] Instrumented tests green on an emulator
- [ ] Create project
- [ ] **No-edit save on Android 10+ succeeds** (P0-1 regression guard)
- [ ] **Text and subtitles actually render in the export** (P0-5 regression guard)
- [ ] **Speed + Reverse produces a correctly-timed merge** (P0-3 regression guard)
- [ ] **Image/GIF/audio overlays imported from `content://` are typed correctly** (P0-4 regression guard)
- [ ] **An interrupted export leaves no truncated file in the gallery** (P0-6 regression guard)
- [ ] **Audio-only export is either a real MP3 or refused** (P0-2 regression guard)
- [ ] **Cancelling an export does not start a new render** (P1-8 regression guard)
- [ ] **Cancelling an export does not kill proxy generation** (P1-12 regression guard)
- [ ] **Export progress reaches 100% on a transition-bearing project** (P3-21)
- [ ] **A 480p source is never upscaled to 2160p** (P3-19)
- [ ] **A `.lcprj` whose text fields contain `'`, `"`, `\`, `:`, `,`, `;`, `[`, `]`, `%`, `=` loads and exports safely** (SEC-30 regression guard)
- [ ] **No temp files leak after an induced export failure** (P1-11)
- [ ] Reopen project (and reopen a **legacy LibreCuts `.lcprj`**)
- [ ] Recover from an interrupted save (simulate truncated write)
- [ ] Import video / photo / audio
- [ ] Timeline: drag, trim, split, delete, reorder, zoom, multi-select, snap
- [ ] Text: all controls, meme presets, animation, keyframes
- [ ] Effects: stack, order, intensity, custom LUT
- [ ] Meme packs: DEAD PACK applies, fully editable
- [ ] SFX: all categories, auto-attach, move/delete-with-parent
- [ ] Music: multi-track, waveform, fades, ducking, voice-over
- [ ] Beat detection: accuracy on fixtures
- [ ] Beat sync: all 4 density modes, undoable
- [ ] Slideshow: all 6 styles
- [ ] Transitions: all 3 groups + AUTO
- [ ] Export: all 6 presets
- [ ] Sharing: target apps installed and not installed
- [ ] Share stack: dedupe verified (no duplicate encoding)
- [ ] Undo/redo: all operations; survives process death
- [ ] Accessibility: TalkBack-only editing works
- [ ] Performance: budgets in §7 met on physical mid-range hardware
- [ ] No leaked temp files after any operation
- [ ] Originals never modified (checksum before/after)
- [ ] `ASSETS.md` complete; licence compliance signed off
