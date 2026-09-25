package com.mhirex.editor.auth

import android.content.Context
import android.util.Log
import com.mhirex.editor.Branding
import java.io.File

/**
 * Persists the signed-in [UserProfile] in app-private SharedPreferences.
 *
 * This is deliberately a **local, device-only** record. Mhirex has no backend, so nothing is uploaded
 * and nothing can be restored on another device; signing in on a new phone starts from scratch. The
 * stored value is metadata plus a cache path, never a credential -- the ID token is intentionally NOT
 * persisted here. [GoogleSignInManager] re-acquires a fresh token per sign-in attempt rather than
 * caching one, so a revoked or expired token cannot leave the app holding an unusable session.
 *
 * Uses [Branding.PREFS_NAME] so profile data sits alongside the app's other settings rather than in a
 * second preferences file.
 */
object UserProfileStore {

    private const val TAG = "UserProfileStore"

    private const val KEY_SIGNED_IN = "profile_signed_in"
    private const val KEY_DISPLAY_NAME = "profile_display_name"
    private const val KEY_EMAIL = "profile_email"
    private const val KEY_PHOTO_URL = "profile_photo_url"
    private const val KEY_PHOTO_CACHED = "profile_photo_cached"
    private const val KEY_GIVEN_NAME = "profile_given_name"

    private fun prefs(context: Context) =
        context.getSharedPreferences(Branding.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Records a completed sign-in.
     *
     * [photoCached] is the local avatar file if one was downloaded. It is stored as a boolean rather
     * than the path itself: the avatar lives at a fixed location inside the app's own cache dir, so
     * the path is an implementation detail that would go stale if [AvatarCache.FILE_NAME] ever changed,
     * and re-deriving it from the cache dir cannot escape that directory.
     */
    fun save(context: Context, profile: UserProfile) {
        prefs(context).edit().apply {
            putBoolean(KEY_SIGNED_IN, true)
            putString(KEY_DISPLAY_NAME, profile.displayName)
            putString(KEY_GIVEN_NAME, profile.displayName)
            putString(KEY_EMAIL, profile.email)
            putString(KEY_PHOTO_URL, profile.photoUrl)
            putBoolean(KEY_PHOTO_CACHED, profile.cachedPhoto != null)
            apply()
        }
        Log.i(TAG, "Saved profile for ${profile.email ?: "unknown email"}")
    }

    /** Returns the stored profile, or `null` when signed out. */
    fun load(context: Context): UserProfile? {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_SIGNED_IN, false)) return null
        return UserProfile(
            displayName = prefs.getString(KEY_DISPLAY_NAME, null),
            email = prefs.getString(KEY_EMAIL, null),
            photoUrl = prefs.getString(KEY_PHOTO_URL, null),
            cachedPhoto = if (prefs.getBoolean(KEY_PHOTO_CACHED, false)) {
                File(context.cacheDir, "user_avatar.jpg").takeIf { it.exists() }
            } else {
                null
            }
        )
    }

    /** Whether a profile is currently stored. */
    fun isSignedIn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SIGNED_IN, false)

    /**
     * Clears the profile and deletes the cached avatar.
     *
     * The avatar must be deleted too: it is a copy of a personal photo in app storage, and leaving it
     * behind after sign-out would keep a user's likeness on disk with no UI path to remove it.
     */
    fun clear(context: Context) {
        prefs(context).edit().apply {
            remove(KEY_SIGNED_IN)
            remove(KEY_DISPLAY_NAME)
            remove(KEY_GIVEN_NAME)
            remove(KEY_EMAIL)
            remove(KEY_PHOTO_URL)
            remove(KEY_PHOTO_CACHED)
            apply()
        }
        AvatarCache.clear(context)
        Log.i(TAG, "Cleared stored profile and cached avatar")
    }
}
