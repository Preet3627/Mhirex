package com.mhirex.editor

/**
 * Single source of truth for Mhirex's user-visible brand strings, output paths and
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
    const val APP_NAME = "Mhirex"

    /**
     * Folder created inside `Pictures/`, `Movies/` and `Music/` for default output.
     * User-visible in the system gallery app.
     */
    const val OUTPUT_FOLDER = "Mhirex"

    // ------------------------------------------------------------ output names

    const val VIDEO_FILE_PREFIX = "${APP_NAME}_"
    const val AUDIO_FILE_PREFIX = "${APP_NAME}_Audio_"
    const val SNAPSHOT_FILE_PREFIX = "${APP_NAME}_Frame_"

    // ------------------------------------------------------------ external links

    /**
     * Upstream project. These links intentionally continue to point at
     * LibreCuts — the MIT copyright notice and the sponsor/attribution links
     * must remain resolvable. See [REPO_URL] for the Mhirex fork.
     *
     * They are attribution, not support destinations: every link a *user* is
     * expected to follow for help must point at [REPO_ISSUES] or
     * [DISCORD_INVITE] instead. See the note on the removed wiki constant below.
     */
    const val UPSTREAM_REPO = "https://github.com/tharunbirla/LibreCuts"
    const val UPSTREAM_SPONSOR = "https://github.com/sponsors/tharunbirla"
    const val UPSTREAM_ISSUES = "$UPSTREAM_REPO/issues"
    const val UPSTREAM_RELEASES = "$UPSTREAM_REPO/releases/latest"

    // Deleted: UPSTREAM_WIKI_TROUBLESHOOTING = "$UPSTREAM_REPO/wiki/Error-Codes-&-Troubleshooting"
    // The LibreCuts wiki documents LibreCuts, not Mhirex, and the two can drift as Mhirex's
    // error handling changes. It was never wired to a UI entry (the About screen rewrite in
    // task 0.16 dropped it), so removing the constant is a pure deletion. Troubleshooting now
    // routes to REPO_NEW_ISSUE via ErrorDisplayActivity, which prefills the error code and log.
    // Do not re-add this without an equivalent Mhirex-owned page to point at.

    /** Mhirex's GitHub project. */
    const val REPO_URL = "https://github.com/Preet3627/Mhirex"
    const val REPO_ISSUES = REPO_URL + "/issues"
    const val REPO_RELEASES = REPO_URL + "/releases/latest"
    const val REPO_NEW_ISSUE = REPO_ISSUES + "/new"

    /** Instagram profiles credited in the About screen and startup welcome. */
    const val INSTAGRAM_IDEA_URL = "https://www.instagram.com/_fivetriple.8_/"
    const val INSTAGRAM_MEHUL_URL = "https://www.instagram.com/il__mehul_patel__li/"

    // No WEBLATE constant. Mhirex has no translation project of its own, and the inherited
    // project is hosted.weblate.org/engage/librecuts — an entry labelled "Translate Mhirex"
    // pointing there sends contributors into the upstream project, and the Weblate badge
    // renders "LibreCuts". Translations are therefore pull-request-only for now, which is what
    // the README says. Re-add a translation platform link together with the matching string,
    // in one change, when Mhirex's own project exists.

    const val DISCORD_INVITE = "https://discord.gg/gwr3nE7YW"

    // ------------------------------------------------------------------- storage

    /**
     * SharedPreferences file name.
     *
     * Still named after LibreCuts. The original reason — preserving existing LibreCuts users'
     * settings on upgrade — NO LONGER APPLIES: `applicationId` is now `com.mhirex.editor`, so
     * Mhirex is a separate app with its own sandbox and cannot read LibreCuts' preferences at all.
     * There is no data to preserve, and no upgrade path to break.
     *
     * It is kept anyway as a harmless frozen identifier, and because `librecuts_prefs` is part of
     * the upstream LibreCuts provenance the MIT attribution depends on. Changing it is a
     * deliberate decision, not a rebrand chore — see PLAN.md AD-2. If it is ever renamed, the
     * value is unobservable by users, so treat the rename as a no-op-cost refactor.
     */
    const val PREFS_NAME = "librecuts_prefs"
}
