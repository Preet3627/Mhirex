# Mhirex

<div align="center">
  <!--
      Two variants of the same mark, swapped by the reader's GitHub colour scheme.

      The mark itself is white on transparent, which is correct for the app (it always sits on a dark
      surface) but would be invisible on GitHub's default white page. So the light variant carries a
      dark tile for the mark to read against, and the dark variant stays transparent because white
      already reads on GitHub's dark page. Measured contrast: 18.06:1 mark-on-tile, 18.85:1
      tile-on-white-page, 18.33:1 mark-on-dark-page, against a 3:1 floor for graphics.

      Both are 512x512 with the mark at 320px (62.5%, the launcher-icon safe zone) so the mark renders
      at the same size from the shared width="180" in either theme.

      Root logo.png is NOT used here. It is the older opaque black-square mark, and it is still the
      source for the launcher icon and the Play listing, so overwriting it would change the shipped
      icon. See ASSETS.md.
  -->
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="src/images/logo_readme_dark.png" />
    <source media="(prefers-color-scheme: light)" srcset="src/images/logo_readme_light.png" />
    <img src="src/images/logo_readme_light.png" alt="Mhirex logo" width="180"/>
  </picture>
  <br/>
  <br/>
  <br/>

  <a href="https://github.com/Preet3627/Mhirex">
    <img src="https://img.shields.io/badge/Star_Mhirex-ea4aaa?style=for-the-badge&logo=github&logoColor=white" height="35" alt="Star Mhirex on GitHub" />
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge" height="35" alt="License" />
  </a>
  <a href="https://github.com/Preet3627/Mhirex/graphs/contributors">
    <img src="https://img.shields.io/github/contributors/Preet3627/Mhirex?style=for-the-badge&label=Contributors&color=ff2a6d" height="35" alt="Contributors" />
  </a>
  <br/>
  <br/>
  <a href="https://github.com/Preet3627/Mhirex/releases/latest">
    <img src="src/images/badges/badge_github.png" alt="Get it on GitHub" height="96" />
  </a>
  <!-- F-Droid badge removed: Mhirex's applicationId is com.mhirex.editor, so the existing
       com.tharunbirla.librecuts F-Droid listing tracks LibreCuts, not Mhirex. Re-add the badge
       once Mhirex has its own F-Droid metadata (f-droid.toml + build metadata under this id). -->
  <!-- Obtainium badge removed: it is a third-party install redirect, and it deep-links through
       apps.obtainium.imranr.dev with our repo URL in the query string. That sends every tap
       through an unaffiliated host, which is the wrong default for a project asking people to
       sideload a build. Install from GitHub Releases (above) instead, which is the copy we sign. -->
  <!-- Discord badge removed: no Mhirex-owned Discord exists. discord.gg/gwr3nE7YW is LibreCuts'
       invite, so the badge was sending readers to the upstream project's community. Branding.DISCORD_INVITE
       still holds that URL and is likewise unused by the app; drop both together if Discord is
       coming back under a Mhirex invite. -->
  <!-- Weblate badge removed: hosted.weblate.org/engage/librecuts renders "LibreCuts" and counts
       the upstream translation project, not Mhirex. See "Translations" below for how to help
       until Mhirex has a translation project of its own. -->

  <a href="https://github.com/Preet3627/Mhirex/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=Preet3627/Mhirex" alt="Mhirex contributors" />
  </a>
  <br/>
  <sub>
    <!--
        contrib.rocks draws from the GitHub contributors API for this repository, which today is
        20 people who are all LibreCuts upstream contributors -- tharunbirla first, with 210
        contributions. That is the honest history of this repository, and MIT attribution wants those
        names visible, but it is not a list of Mhirex contributors and the README must not imply it
        is. Mhirex's own work on top of the fork point is a small minority of the 279 commits
        reachable from HEAD. Replace or drop this strip once that ratio changes.
    -->
    Contributed to Mhirex and its LibreCuts foundation &mdash; thank you.
  </sub>
</div>

<br/>

> **Status: active development.** Mhirex is being rebuilt on top of its LibreCuts foundation. The
> core editing tools listed below work today; the multi-track architecture, instant GPU preview,
> beat-sync workflow, and creator-focused features are being built and shipped incrementally.
> Treat releases as beta software, and please [open an issue](https://github.com/Preet3627/Mhirex/issues)
> when something breaks. See [What's next](#-whats-next) for the roadmap.

**Mhirex** is a free, open-source video editor for Android that prioritizes simplicity, efficiency, and privacy. Built for seamless performance, it empowers creators to easily select, edit, and export watermark-free videos locally on their device.

**Project origin.** Mhirex started as a personal tool, built by **Preet Patel** for [@_fivetriple.8_](https://www.instagram.com/_fivetriple.8_/), who wanted to cut videos on his phone without installing anything from a store. It is now maintained as a public project, with [@il__mehul_patel__li](https://www.instagram.com/il__mehul_patel__li/) also contributing. Mhirex is based on [LibreCuts](https://github.com/tharunbirla/LibreCuts) by Tharun Birla and retains the upstream MIT license and full attribution — see [`NOTICE`](NOTICE) and [`ASSETS.md`](ASSETS.md) for third-party and release notices.

---

## 🚀 Features

Everything in this list ships today.

- **Trim** - Remove unwanted parts from the beginning or end of a video clip with a real-time timeline control.
- **Overlays** - Place text, stickers, images, GIFs, and video overlays on top of video clips to create engaging content. Includes support for continuous media looping.
- **Masking** - Apply various mask shapes to your overlays for creative effects.
- **Chroma Key** - Remove backgrounds from any overlay using the green screen effect.
- **Keyframes** - Animate overlays across the screen with keyframe support.
- **Subtitles (Captions)** - Import custom `.srt` subtitle files with a dedicated toolbar slider for resizing and fully interactive touch-based positioning directly on the video preview.
- **Layer Management** - Easily reorder overlay layers to control what renders on top.
- **Audio** - Manage soundtracks effortlessly by importing custom music or audio tracks, recording voice overs, applying audio ducking and fades, amplifying volume up to 200%, and muting original audio.
- **Audio Export** - Export your project's entire audio mix as a standalone MP3 file.
- **Snapshots** - Capture and save high-quality frame grabs (snapshots) directly from the video editor.
- **Crop** - Adjust the aspect ratio of a video with custom cropping support.
- **Merge** - Combine multiple video segments into a continuous sequence with drag-to-rearrange functionality.
- **Transition** - Apply transitions with animated visual previews in the toolbar.
- **Speed** - Change the speed of a video clip using a custom speed slider for granular control.
- **Adjust & Filters** - Modify video brightness, contrast, saturation, and apply color filters.
- **Canvas Background** - Add a blurred background or a solid color for a cohesive look when your video aspect ratio does not match the project frame.
- **Reverse** - Reverse video playback.
- **Timeline Organization** - Enhanced editing with snapping functionality, overlay duplication, freeze frame actions, and improved UI visual styling.
- **Project Save & Import** - Save non-destructive project state as a `.lcprj` file to save and reopen editable project files anytime.
- **Freehand Drawing** - Draw directly on top of video clips with custom brush color and stroke controls.
- **Custom Fonts** - Import `.ttf` or `.otf` font files to customize text overlay typography.
- **Fullscreen Preview** - Switch to true fullscreen preview mode with expanded timeline view and overlay controls.
- **Android 13+ Themed Icon** - Supports native monochrome adaptive icons for Android 13+ system themes.
- **Hardware Acceleration** - Super-fast and reliable video exports using device hardware-accelerated `h264_mediacodec` encoding (with seamless automatic fallback to software encoding for maximum device compatibility) and accurate FFmpeg progress calculation.

## 🛠️ What's next

Planned and in development — **not available in current builds**. The design and the staged rollout
are documented in [`PLAN.md`](PLAN.md); the work queue is tracked in [`TODO.md`](TODO.md).

- **Multi-track timeline** - A real project model (video, audio, and overlay tracks) replacing the single flat video track, with continuous zoom, multi-select, and frame-accurate snapping.
- **Instant preview** - GPU-composited preview that no longer spawns an FFmpeg process, so scrubbing and previewing stay interactive on mid-range hardware.
- **Beat detection and Sync to Beat** - On-device tempo and beat analysis, beat markers on the timeline, and a one-tap way to cut a montage to the music.
- **Sound effects** - A built-in, license-clean SFX library that can auto-attach to cuts and stays attached when you move or delete the clip it belongs to.
- **Effect stacks and meme packs** - Layered, intensity-controlled effects with editable presets (including custom LUTs) instead of single fixed filters.
- **Text upgrades** - Text animation presets, stroke, shadow, background, scale, and rotation, plus meme-text templates that stay fully editable.
- **Transitions and templates** - Typed transition groups, automatic (`AUTO`) transition selection, and a photo-slideshow planner with Ken Burns styles.
- **Platform export presets** - One-tap presets for Reels, Shorts, TikTok, and YouTube, with the share pipeline encoding once instead of per-target.
- **Structured undo** - Per-operation undo/redo for every edit, replacing today's whole-project snapshots.

## 📱 Screenshots

<div align="center">
  <table>
    <tr>
      <td align="center"><img src="src/images/sc_1.png" width="100%" alt="Home Screen"/></td>
      <td align="center"><img src="src/images/sc_2.png" width="100%" alt="Editor Screen"/></td>
      <td align="center"><img src="src/images/sc_3.png" width="100%" alt="Audio Import"/></td>
      <td align="center"><img src="src/images/sc_4.png" width="100%" alt="Timeline"/></td>
    </tr>
    <tr>
      <td align="center"><b>Home Screen</b></td>
      <td align="center"><b>Editor Screen</b></td>
      <td align="center"><b>Audio Import</b></td>
      <td align="center"><b>Timeline</b></td>
    </tr>
  </table>
</div>

## 💖 Support Mhirex

Mhirex is a personal project built with passion and provided for free. If it helped you create something amazing, please star the repository and follow the people behind the app.

<div align="center">
  <br/>
  <a href="https://github.com/Preet3627/Mhirex"><img src="https://img.shields.io/github/stars/Preet3627/Mhirex?style=for-the-badge&logo=github&logoColor=white" alt="Star Mhirex on GitHub" /></a>
  &nbsp;&nbsp;
  <a href="https://www.instagram.com/_fivetriple.8_/"><img src="https://img.shields.io/badge/Instagram-%40_fivetriple.8_-E4405F?style=for-the-badge&logo=instagram&logoColor=white" alt="App idea on Instagram" /></a>
  <br/>
  <br/>
</div>

## 🛠️ Getting Started

### Prerequisites

- Android Studio
- Android SDK

### Installation

1. **Clone the repository**:
   ```bash
   git clone https://github.com/Preet3627/Mhirex.git
   ```
2. **Open the project in Android Studio**:
   - Launch Android Studio and select "Open an existing Android Studio project."
   - Navigate to the cloned directory and select it.
3. **Build the project**:
   - Click on "Build" in the menu, then select "Make Project."
4. **Run the app**:
   - Connect an Android device or start an emulator.
   - Click on the "Run" button in Android Studio.

## 🔒 Permissions

Mhirex uses the following permissions to function properly:

- **READ_MEDIA_AUDIO/VIDEO/IMAGES**: For accessing media files on devices running Android 13 (API level 33) and above.
- **READ_EXTERNAL_STORAGE**: To read media on older Android versions.
- **POST_NOTIFICATIONS**: To show export and proxy-generation notifications.
- **RECORD_AUDIO**: To record voice-over audio.

Exports are published through Android MediaStore or a user-selected SAF folder; the app does not request legacy public-directory write access.

## 🌍 Translations

Mhirex ships with 18 languages today. Gujarati was added in Mhirex; the rest were inherited from the LibreCuts community when the project was forked, so some strings still carry upstream wording.

**Mhirex does not have its own translation project yet**, so there is no translation platform badge to link to — a LibreCuts one would count a different project and send contributors to the wrong place. Until Mhirex's own project exists:

- Translate or correct strings by editing the relevant `app/src/main/res/values-<locale>/strings.xml` and opening a pull request.
- Not sure which string needs work? Open an issue describing what reads wrong and in which language.
- The app deliberately has no translation link at all for now: an entry labelled "Translate Mhirex" could only point at the LibreCuts project and send you to the wrong place. Pull requests are the route until Mhirex's own project exists.

## 🔧 Troubleshooting & Support

If an export fails, a codec error appears, or the app crashes:

1. **Use the in-app error screen.** Mhirex shows a diagnostic log with copy and share buttons, plus a button that opens a pre-filled GitHub issue for the exact error code. Attach that log — it is what makes a bug fixable.
2. **Search [existing issues](https://github.com/Preet3627/Mhirex/issues).** It may already be known and under discussion.
3. **Otherwise open an issue** using the [bug report template](.github/ISSUE_TEMPLATE/bug_report.yml), with your device model, Android version, and the log from step 1.
4. **Ask on [Discord](https://discord.gg/gwr3nE7YW)** for real-time help and suggestions.

**About error codes.** Mhirex currently uses the upstream `LC-###` codes, so they still match the
[LibreCuts troubleshooting wiki](https://github.com/tharunbirla/LibreCuts/wiki/Error-Codes-&-Troubleshooting)
(`LC-101` FFmpeg failure, `LC-102` missing font, `LC-201` source file missing, `LC-202` save to
gallery failed, `LC-301` out of memory, `LC-500` unexpected crash). The authoritative list is
[`ErrorCode.kt`](app/src/main/java/com/mhirex/editor/utils/ErrorCode.kt). That wiki documents
LibreCuts rather than Mhirex, so treat it as background and not as Mhirex documentation; a
Mhirex-specific guide is still to be written.

## 🤝 Contributing

Contributions are welcome! If you have suggestions or improvements, feel free to create a pull request or open an issue.

1. Fork the repository.
2. Create a new branch for your feature or bug fix.
3. Commit your changes.
4. Push to the branch.
5. Submit a pull request.

## 🗽 Android Freedom

Mhirex is distributed as an open-source APK rather than through the Play Store, and keeping it that way is part of the project's point. Google's mandatory developer verification policy begins enforcing in **September 2026**, requiring all Android developers — including independent ones — to submit government ID and register centrally, which threatens sideloading freedom and the distribution of free and open-source software on Android.

If that matters to you, the campaign is documented at [keepandroidopen.org](https://keepandroidopen.org/).

## 📝 License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
