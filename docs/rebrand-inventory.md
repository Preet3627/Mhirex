# Rebrand Inventory — LibreCuts → Mhirex

> The fork moved the LibreCuts product surface to Mhirex. The technical namespace `com.mhirex.editor`, `applicationId`, preference key, project extension, and upstream attribution remain unchanged for upgrade compatibility and legal provenance.

Frozen against upstream commit `a510390`. This is the working checklist for `TODO.md` Phase 0,
tasks 0.1–0.3. Every item here is verified against the source tree, not assumed.

---

## 1. Kotlin/Java package declarations — 41 files

All under `com.tharunbirla.librecuts`. Target: `com.mhirex.editor`.

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

## 3. Brand strings — 8 keys × 18 locales (7 after the 0.16 string deletions)

Source of truth: `res/values/strings.xml`. All 8 keys were present there; `str_help_translate` was
since deleted, so 7 remain. The audit below is the pre-deletion state and is kept as the record of
what each locale inherited.

| Key | Current value | Action |
|---|---|---|
| `app_name` | `LibreCuts` | → `Mhirex` in **all** locales |
| `str_downloads_librecuts` | `Downloads/LibreCuts` | → `Downloads/Mhirex`, key → `str_downloads_mhirex` |
| `str_default_movies_librecuts` | `Default (Movies/LibreCuts)` | → `…/Mhirex`, key → `str_default_movies_mhirex` |
| `str_default_music_librecuts` | `Default (Music/LibreCuts)` | → `…/Mhirex`, key → `str_default_music_mhirex` |
| `str_default_pictures_librecuts` | `Default (Pictures/LibreCuts)` | → `…/Mhirex`, key → `str_default_pictures_mhirex` |
| `str_librecuts_is_open_source_help` | "LibreCuts is open source…" | rebrand, key → `str_mhirex_is_open_source_help` |
| `str_help_translate` | `Translate LibreCuts` | **Deleted** with `str_contribute_translations_on_weblate`, `str_troubleshooting_amp_wiki_guide` and `str_if_you_encounter_any_export_is` in task 0.16 — all four were unreferenced after the About rewrite and all four pointed at LibreCuts destinations. Re-add with a Mhirex translation project |
| `str_made_by_tharun_birla` | "Made by Tharun Birla" | **KEEP** — MIT attribution is mandatory. The additive `str_mhirex_based_on` line credits LibreCuts alongside it. |

### Locale completeness audit (18 dirs)

| Locale | `app_name` value | Notes |
|---|---|---|
| `values` (default) | `LibreCuts` | source of truth |
| `values-ar` | `LibreCuts` | all 8 present (7 now) |
| `values-cs` | `LibreCuts` | all 8 present (7 now) |
| `values-de` | `LibreCuts` | all 8 present (7 now) |
| `values-el` | *absent* | only 4 unrelated strings; falls back to default — will correctly resolve to `Mhirex` |
| `values-es` | `libreCuts` ⚠️ | lowercase-l typo upstream; also missing 3 keys, falls back |
| `values-et` | `LibreCuts` | all 8 present (7 now) |
| `values-gu` | `Mhirex` | added in Mhirex (Gujarati); written directly against the Mhirex key set, so it never carried the LibreCuts brand |
| `values-hi` | `LibreCuts` | all 8 present (7 now) |
| `values-in` | `LibreCuts` | all 8 present (7 now) |
| `values-it` | `LibreCuts` | all 8 present (7 now) |
| `values-nl` | `LibreCuts` | all 8 present (7 now) |
| `values-pt-rBR` | `LibreCuts` | was missing `str_help_translate`; the key is deleted anyway |
| `values-ru` | `LibreCuts` | all 8 present (7 now) |
| `values-sk` | `LibreCuts` | all 8 present (7 now) |
| `values-ta` | `LibreCuts` | all 8 present (7 now) |
| `values-tr` | `LibreCuts` | missing 3 keys, falls back |
| `values-zh-rCN` | `自由剪辑` ⚠️ | **translated brand** — must be replaced with the Latin `Mhirex` |

Missing-key locales inherit from `values/`, so rebrand is correct there automatically. The two
`app_name` overrides (`es` lowercase, `zh-rCN` translated) are the only ones that would otherwise
escape the rebrand, and both are handled explicitly.

---

## 4. Build identity

| File | Line | Current | Action |
|---|---|---|---|
| `app/build.gradle` | 13 | `namespace 'com.tharunbirla.librecuts'` | → `com.mhirex.editor` |
| `app/build.gradle` | 25 | `applicationId "com.tharunbirla.librecuts"` | → `com.mhirex.editor` — **AD-2 reversed**; Mhirex is now a separate app with no LibreCuts upgrade path |
| `settings.gradle` | 22 | `rootProject.name = "LibreCuts"` | → `"Mhirex"` |
| `AndroidManifest.xml` | 27 | `android:name=".LibreCutsApplication"` | class renamed to `MhirexApplication` |
| `AndroidManifest.xml` | 14–17 | `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | removed in task 0.15 (SEC-31) |

> **Why `applicationId` stays.** Changing it orphans saved projects, breaks F-Droid/Obtainium
> listings, and splits the Weblate project. `namespace` is source-level only and moves freely.
> Deferred to a documented Mhirex 2.0 with a data-import step. See `PLAN.md` AD-2.

---

## 5. Runtime / persisted identifiers — deliberately preserved

These are **data keys, not brand surface**. Renaming them would silently reset user settings or
break existing project files, which is exactly the class of bug `PLAN.md` B2 exists to prevent.

| Identifier | Location | Decision |
|---|---|---|
| SharedPreferences file `librecuts_prefs` | `VideoEditingViewModel.kt:1308,1562`, `MainActivity.kt:391` | **Keep.** The original "renaming orphans user settings" reason no longer applies — `applicationId` is now `com.mhirex.editor`, so Mhirex cannot read LibreCuts' preferences regardless. Kept as a frozen identifier and part of the MIT provenance. See `Branding.PREFS_NAME`. |
| Project file extension `.lcprj` | `ProjectSerializer`, `ProjectImportActivity` | **Keep.** Existing LibreCuts projects must remain loadable; the Mhirex 1.x line continues to use the documented `.lcprj` format. |
| `MediaStore` output folder | `Branding.OUTPUT_FOLDER`, `MediaPublisher` | New writes go to `Movies/Mhirex`, `Pictures/Mhirex`, or `Music/Mhirex`; old LibreCuts media remains where it was. |
| `ErrorCode` ids `LC-101` etc. | `utils/ErrorCode.kt` | **Keep.** They are cited in the upstream wiki; renaming breaks documented support references. |
| `LICENCE` copyright `© 2024 Tharun Birla` | `LICENSE` | **Keep — legally mandatory** (MIT). Mhirex attribution is *cumulative*, via `NOTICE`. |

---

## 6. Documentation / metadata surfaces

| Surface | Matches | Action |
|---|---|---|
| `README.md` | 14 | Rebrand, keep upstream attribution + build link. **Then:** Weblate badge removed (it renders "LibreCuts" and tracks the upstream project — re-add with the F-Droid badge once Mhirex has its own listings), duplicate GitHub badge removed, LibreCuts wiki demoted from "our troubleshooting guide" to background reference under a section that names `ErrorCode.kt` as authoritative, active-development status block + "What's next" roadmap added, "Keep Android Open" moved to a bottom "Android Freedom" section, origin story reworded, and a "Translations" section added that states no Mhirex translation project exists yet |
| `fastlane/metadata/android/en-US/title.txt` | `Mhirex` | **Updated**; Play listing keeps the preserved applicationId until a deliberate migration |
| `fastlane/metadata/android/en-US/short_description.txt` | 1 | Rebrand |
| `fastlane/metadata/android/en-US/full_description.txt` | 2 | Rebrand |
| `fastlane/metadata/android/en-US/changelogs/3.txt` | 1 | Historical changelog — **leave as-is**, it describes a past LibreCuts release |
| `fastlane/metadata/android/en-US/images/` | — | Upstream author's screenshots; retain attribution |
| `.github/FUNDING.yml` | — | Preserve upstream sponsorship links |
| `src/images/` (README screenshots/badges) | — | Upstream author's assets; retain attribution; `logo.png` is the current Mhirex mark |

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
2. `NOTICE` — new file: credits Tharun Birla (MIT, original) **and** the Mhirex contributors.
3. About screen — "Mhirex … Based on LibreCuts by Tharun Birla (MIT)".
4. AboutLibraries — add the **GPL-3.0** ffmpeg-kit entry (currently missing entirely; `PLAN.md` L1).
5. Existing source headers — not retroactively altered.
