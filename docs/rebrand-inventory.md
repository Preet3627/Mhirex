# Rebrand Inventory — LibreCuts → MhireX

> **Brand history:** the fork first moved the LibreCuts product surface to Mivio, then the requested product name was changed to MhireX. The technical namespace `com.mivio.editor`, `applicationId`, preference key, project extension, and upstream attribution remain unchanged for upgrade compatibility and legal provenance.

Frozen against upstream commit `a510390`. This is the working checklist for `TODO.md` Phase 0,
tasks 0.1–0.3. Every item here is verified against the source tree, not assumed.

---

## 1. Kotlin/Java package declarations — 41 files

All under `com.tharunbirla.librecuts`. Target: `com.mivio.editor`.

| Source set | Count | Files |
|---|---|---|
| `main` | 38 | `VideoEditingActivity.kt`, `MainActivity.kt`, `ErrorDisplayActivity.kt`, `ProjectImportActivity.kt`, `LibreCutsApplication.kt`, `FrameAdapter.kt`, `ScratchTest.kt` †, `viewmodels/VideoEditingViewModel.kt`, `viewmodels/VideoEditingViewModelExt.kt`, `commands/EditCommand.kt`, `models/EditOperation.kt`, `models/VideoProject.kt`, `services/FFmpegRenderEngine.kt`, `services/ExportService.kt`, `services/ProxyGenerationService.kt`, `utils/AudioAnalyzer.kt`, `utils/AudioWaveformExtractor.kt`, `utils/ErrorCode.kt`, `utils/FontManager.kt`, `utils/ProjectSerializer.kt`, `utils/SubtitleParser.kt`, `utils/ViewExtensions.kt`, `customviews/` × 17 |
| `test` | 1 | `ExampleUnitTest.kt` |
| `androidTest` | 2 | `ExampleInstrumentedTest.kt`, `KeyframeOpacityPreviewTest.kt` |

† `ScratchTest.kt` is dead code — deleted in task 0.4.

**Package tree:**

```
com.tharunbirla.librecuts
├── VideoEditingActivity, MainActivity, ErrorDisplayActivity,
│   ProjectImportActivity, LibreCutsApplication, FrameAdapter
├── commands/EditCommand
├── models/{EditOperation, VideoProject}
├── services/{FFmpegRenderEngine, ExportService, ProxyGenerationService}
├── utils/{AudioAnalyzer, AudioWaveformExtractor, ErrorCode, FontManager,
│          ProjectSerializer, SubtitleParser, ViewExtensions}
├── viewmodels/{VideoEditingViewModel, VideoEditingViewModelExt}
└── customviews/{BrushSizeDot, CustomFileExplorerBottomSheet, CustomVideoSeeker,
                CropOverlay, DraggableImageOverlay, DraggableTextOverlay,
                HSVColorPicker, HandwritingCanvas, ImageOverlay, MaskedFrameLayout,
                MediaPickerBottomSheet, TextOverlay, TimeRuler, TrackTrim,
                TransitionPreviewOverlay, VideoMaskOverlay}  (17)
```

---

## 2. XML fully-qualified custom-view references — 18 refs, 7 files

**These are easy to miss and break layout inflation silently at runtime, not at compile time.**
Android resolves these by reflection, so a stale FQCN compiles cleanly and then throws
`ClassNotFoundException` / `InflateException` on screen.

| File | Views referenced |
|---|---|
| `layout/activity_video_editing.xml` | `MaskedFrameLayout`, `TransitionPreviewOverlayView`, `TextOverlayView`, `DraggableTextOverlayView`, `ImageOverlayView`, `DraggableImageOverlayView`, `CropOverlayView`, `VideoMaskOverlayView`, `HandwritingCanvasView`, `TimeRulerView`, `CustomVideoSeeker` (11) |
| `layout/audio_editing_toolbar.xml` | `TrackTrimView` |
| `layout/chroma_key_bottom_sheet_dialog.xml` | `HSVColorPickerView` |
| `layout/item_sequence_segment.xml` | `TrackTrimView` |
| `layout/trim_bottom_sheet_dialog.xml` | `TrackTrimView` |
| `layout/layout_handwriting_panel.xml` | `BrushSizeDotView` |
| `layout/dialog_custom_color_picker.xml` | `HSVColorPickerView` |

---

## 3. Brand strings — 8 keys × 17 locales

Source of truth: `res/values/strings.xml`. All 8 keys are present there.

| Key | Current value | Action |
|---|---|---|
| `app_name` | `LibreCuts` | → `MhireX` in **all** locales |
| `str_downloads_librecuts` | `Downloads/LibreCuts` | → `Downloads/MhireX`, key → `str_downloads_mhirex` |
| `str_default_movies_librecuts` | `Default (Movies/LibreCuts)` | → `…/MhireX`, key → `str_default_movies_mhirex` |
| `str_default_music_librecuts` | `Default (Music/LibreCuts)` | → `…/MhireX`, key → `str_default_music_mhirex` |
| `str_default_pictures_librecuts` | `Default (Pictures/LibreCuts)` | → `…/MhireX`, key → `str_default_pictures_mhirex` |
| `str_librecuts_is_open_source_help` | "LibreCuts is open source…" | rebrand, key → `str_mhirex_is_open_source_help` |
| `str_help_translate` | `Translate LibreCuts` | rebrand to `Translate MhireX`; key remains stable for now |
| `str_made_by_tharun_birla` | "Made by Tharun Birla" | **KEEP** — MIT attribution is mandatory. The additive `str_mhirex_based_on` line credits LibreCuts alongside it. |

### Locale completeness audit (17 dirs)

| Locale | `app_name` value | Notes |
|---|---|---|
| `values` (default) | `LibreCuts` | source of truth |
| `values-ar` | `LibreCuts` | all 8 present |
| `values-cs` | `LibreCuts` | all 8 present |
| `values-de` | `LibreCuts` | all 8 present |
| `values-el` | *absent* | only 4 unrelated strings; falls back to default — will correctly resolve to `MhireX` |
| `values-es` | `libreCuts` ⚠️ | lowercase-l typo upstream; also missing 3 keys, falls back |
| `values-et` | `LibreCuts` | all 8 present |
| `values-hi` | `LibreCuts` | all 8 present |
| `values-in` | `LibreCuts` | all 8 present |
| `values-it` | `LibreCuts` | all 8 present |
| `values-nl` | `LibreCuts` | all 8 present |
| `values-pt-rBR` | `LibreCuts` | missing `str_help_translate`, falls back |
| `values-ru` | `LibreCuts` | all 8 present |
| `values-sk` | `LibreCuts` | all 8 present |
| `values-ta` | `LibreCuts` | all 8 present |
| `values-tr` | `LibreCuts` | missing 3 keys, falls back |
| `values-zh-rCN` | `自由剪辑` ⚠️ | **translated brand** — must be replaced with the Latin `MhireX` |

Missing-key locales inherit from `values/`, so rebrand is correct there automatically. The two
`app_name` overrides (`es` lowercase, `zh-rCN` translated) are the only ones that would otherwise
escape the rebrand, and both are handled explicitly.

---

## 4. Build identity

| File | Line | Current | Action |
|---|---|---|---|
| `app/build.gradle` | 13 | `namespace 'com.tharunbirla.librecuts'` | → `com.mivio.editor` |
| `app/build.gradle` | 25 | `applicationId "com.tharunbirla.librecuts"` | **UNCHANGED** — see PLAN AD-2 |
| `settings.gradle` | 22 | `rootProject.name = "LibreCuts"` | → `"MhireX"` |
| `AndroidManifest.xml` | 27 | `android:name=".LibreCutsApplication"` | class renamed to `MhireXApplication` |
| `AndroidManifest.xml` | 14–17 | `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | removed in task 0.15 (SEC-31) |

> **Why `applicationId` stays.** Changing it orphans saved projects, breaks F-Droid/Obtainium
> listings, and splits the Weblate project. `namespace` is source-level only and moves freely.
> Deferred to a documented MhireX 2.0 with a data-import step. See `PLAN.md` AD-2.

---

## 5. Runtime / persisted identifiers — deliberately preserved

These are **data keys, not brand surface**. Renaming them would silently reset user settings or
break existing project files, which is exactly the class of bug `PLAN.md` B2 exists to prevent.

| Identifier | Location | Decision |
|---|---|---|
| SharedPreferences file `librecuts_prefs` | `VideoEditingViewModel.kt:1308,1562`, `MainActivity.kt:391` | **Keep.** Renaming orphans all user settings. |
| Project file extension `.lcprj` | `ProjectSerializer`, `ProjectImportActivity` | **Keep.** Existing LibreCuts projects must remain loadable; the MhireX 1.x line continues to use the documented `.lcprj` format. |
| `MediaStore` output folder | `Branding.OUTPUT_FOLDER`, `MediaPublisher` | New writes go to `Movies/MhireX`, `Pictures/MhireX`, or `Music/MhireX`; old LibreCuts media remains where it was. |
| `ErrorCode` ids `LC-101` etc. | `utils/ErrorCode.kt` | **Keep.** They are cited in the upstream wiki; renaming breaks documented support references. |
| `LICENCE` copyright `© 2024 Tharun Birla` | `LICENSE` | **Keep — legally mandatory** (MIT). MhireX attribution is *cumulative*, via `NOTICE`. |

---

## 6. Documentation / metadata surfaces

| Surface | Matches | Action |
|---|---|---|
| `README.md` | 14 | Rebrand, keep upstream attribution + build link |
| `fastlane/metadata/android/en-US/title.txt` | `MhireX` | **Updated**; Play listing keeps the preserved applicationId until a deliberate migration |
| `fastlane/metadata/android/en-US/short_description.txt` | 1 | Rebrand |
| `fastlane/metadata/android/en-US/full_description.txt` | 2 | Rebrand |
| `fastlane/metadata/android/en-US/changelogs/3.txt` | 1 | Historical changelog — **leave as-is**, it describes a past LibreCuts release |
| `fastlane/metadata/android/en-US/images/` | — | Upstream author's screenshots; retain attribution |
| `.github/FUNDING.yml` | — | Preserve upstream sponsorship links |
| `src/images/` (README screenshots/badges) | — | Upstream author's assets; retain attribution; `logo.png` is the current MhireX mark |

---

## 7. Dead / unused assets found

| Asset | Status | Action |
|---|---|---|
| `app/src/main/res/raw/film.json` | **In use** — Lottie animation, referenced as `app:lottie_rawRes="@raw/film"` in `layout/loading_screen.xml:33`. Briefly deleted in task 0.4 after a grep for `R.raw` / `film.json` missed the XML `@raw/` attribute form; the build caught it and the file was restored. **Lesson recorded:** an asset used only from XML is invisible to a Kotlin-symbol grep. Audit resources by *reference form* (`@raw/`, `@drawable/`, `@string/`), not by symbol name. |
| `app/src/main/java/.../ScratchTest.kt` | Dead | Delete in task 0.4 |
| `app/src/main/java/.../ScratchTest.java` | Dead | Delete in task 0.4 |
| `test_exo.kt`, `test_ext.kt`, `test_heavy.kt` (repo root) | Outside every Gradle source set; never compiled | Delete in task 0.4 |
| `app/src/main/res/drawable/trans_preview_*.webp` | **Provenance undocumented** | `ASSETS.md` in task 0.16 — establish or regenerate (release blocker) |
| `app/src/main/res/drawable/filter_preview_*.jpg` | **Provenance undocumented** | Same |
| `app/src/main/assets/fonts/Roboto-Regular.ttf` | Roboto = Apache-2.0 | Keep, record licence |

---

## 8. Attribution policy (binding)

`LICENSE` is MIT, © 2024 Tharun Birla. MIT requires the copyright notice in all copies, so the
rebrand is **additive only**:

1. `LICENSE` — copyright line unchanged.
2. `NOTICE` — new file: credits Tharun Birla (MIT, original) **and** the MhireX contributors.
3. About screen — "MhireX … Based on LibreCuts by Tharun Birla (MIT)".
4. AboutLibraries — add the **GPL-3.0** ffmpeg-kit entry (currently missing entirely; `PLAN.md` L1).
5. Existing source headers — not retroactively altered.
