package com.mivio.editor

/**
 * Single source of truth for MhireX's user-visible brand strings, output paths and
 * external links.
 *
 * These were previously scattered as inline string literals across
 * `VideoEditingActivity`, `ExportService`, `ProxyGenerationService` and
 * `MainActivity`. That made a global rebrand error-prone: a single careless
 * search-and-replace corrupted unrelated URL literals. Centralising them means
 * a rebrand is a one-file change and there is exactly one place to audit.
 *
 * @see docs/rebrand-inventory.md
 */
object Branding {

    // ---------------------------------------------------------------- identity

    /** Product name. Must stay in sync with `R.string.app_name`. */
    const val APP_NAME = "MhireX"

    /**
     * Folder created inside `Pictures/`, `Movies/` and `Music/` for default output.
     * User-visible in the system gallery app.
     */
    const val OUTPUT_FOLDER = "MhireX"

    // ------------------------------------------------------------ output names

    const val VIDEO_FILE_PREFIX = "${APP_NAME}_"
    const val AUDIO_FILE_PREFIX = "${APP_NAME}_Audio_"
    const val SNAPSHOT_FILE_PREFIX = "${APP_NAME}_Frame_"

    // ------------------------------------------------------------ external links

    /**
     * Upstream project. These links intentionally continue to point at
     * LibreCuts — the MIT copyright notice and the sponsor/attribution links
     * must remain resolvable. See [REPO_URL] for the MhireX fork.
     */
    const val UPSTREAM_REPO = "https://github.com/tharunbirla/LibreCuts"
    const val UPSTREAM_SPONSOR = "https://github.com/sponsors/tharunbirla"
    const val UPSTREAM_ISSUES = "$UPSTREAM_REPO/issues"
    const val UPSTREAM_RELEASES = "$UPSTREAM_REPO/releases/latest"
    const val UPSTREAM_WIKI_TROUBLESHOOTING = "$UPSTREAM_REPO/wiki/Error-Codes-&-Troubleshooting"

    /** MhireX's GitHub project. */
    const val REPO_URL = "https://github.com/Preet3627/Mhirex"
    const val REPO_ISSUES = REPO_URL + "/issues"
    const val REPO_RELEASES = REPO_URL + "/releases/latest"
    const val REPO_NEW_ISSUE = REPO_ISSUES + "/new"

    /** Instagram profiles credited in the About screen and startup welcome. */
    const val INSTAGRAM_IDEA_URL = "https://www.instagram.com/_fivetriple.8_/"
    const val INSTAGRAM_MEHUL_URL = "https://www.instagram.com/il__mehul_patel__li/"

    /** Upstream translation community, retained for existing attribution. */
    const val WEBLATE = "https://hosted.weblate.org/engage/librecuts/"

    const val DISCORD_INVITE = "https://discord.gg/gwr3nE7YW"

    // ------------------------------------------------------------------- storage

    /**
     * SharedPreferences file name.
     *
     * Deliberately **not** renamed to `mivio_prefs`. This is a persisted data key, not
     * brand surface: renaming it would silently discard every existing user's settings
     * (export folder, language, encoder, haptics, fullscreen) on upgrade, with no
     * migration path. The name is frozen and is not a rebrand defect.
     */
    const val PREFS_NAME = "librecuts_prefs"
}
