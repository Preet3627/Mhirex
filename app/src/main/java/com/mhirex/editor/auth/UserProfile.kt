package com.mhirex.editor.auth

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The signed-in user's public profile.
 *
 * Deliberately holds only what Google returns in the ID token plus a locally cached avatar. There is
 * no server component in Mhirex and this is not synced anywhere, so the profile is a device-local
 * convenience, not an account the user can recover on a new device. See [UserProfileStore].
 */
data class UserProfile(
    val displayName: String?,
    val email: String?,
    val photoUrl: String?,
    /** Local file holding the cached avatar, if one has been downloaded. */
    val cachedPhoto: File? = null
) {
    /**
     * A name to render, degrading sensibly. Google may omit `name` entirely for accounts without a
     * profile name, and we do not fabricate a placeholder that looks like real data.
     */
    val displayNameOrEmail: String?
        get() = displayName?.takeIf { it.isNotBlank() } ?: email?.takeIf { it.isNotBlank() }

    /** Initials for the avatar placeholder, or `null` when nothing usable is known. */
    val initials: String?
        get() {
            val source = displayNameOrEmail ?: return null
            val parts = source.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return when {
                parts.isEmpty() -> null
                parts.size == 1 -> parts[0].take(2).uppercase()
                else -> "${parts.first().first()}${parts.last().first()}".uppercase()
            }
        }
}

/**
 * Downloads a Google profile image and stores it as a single fixed-name file in the app's cache dir.
 *
 * Google serves avatars from `lh3.googleusercontent.com` over HTTPS with a long-lived, content-addressed
 * path, so the bytes for a given URL are stable. The filename is therefore fixed rather than derived
 * from the URL: one user has one avatar, and deriving a name from the URL would leave an unbounded pile
 * of orphaned files behind on every avatar change.
 *
 * Written in `suspend` on [Dispatchers.IO] because both the download and the decode are blocking.
 */
internal object AvatarCache {

    private const val TAG = "AvatarCache"
    private const val FILE_NAME = "user_avatar.jpg"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024

    /**
     * Fetches [url] into the cache dir, replacing any previous avatar.
     *
     * Returns the file on success, or `null` if anything went wrong. A missing avatar is never fatal:
     * the UI falls back to initials, so every failure path here degrades quietly rather than
     * interrupting the sign-in flow. This is why it swallows exceptions instead of throwing -- a
     * profile picture failing to download must not make sign-in look broken.
     */
    suspend fun download(context: android.content.Context, url: String): File? =
        withContext(Dispatchers.IO) {
            var temp: File? = null
            try {
                val target = File(context.cacheDir, FILE_NAME)

                // Download to a temp file and rename on success, so a failed or interrupted transfer
                // never leaves a truncated image that would later decode as a corrupt bitmap.
                temp = File.createTempFile("avatar_", ".tmp", context.cacheDir)
                val bytes = fetch(url) ?: return@withContext null
                FileOutputStream(temp).use { it.write(bytes) }

                // Guard against a valid-but-undecodable file before overwriting the good avatar.
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temp.absolutePath, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                    Log.w(TAG, "Downloaded avatar did not decode as an image; discarding")
                    return@withContext null
                }

                if (!temp.renameTo(target)) {
                    // renameTo fails across some filesystems; fall back to copy + delete.
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                Log.i(TAG, "Cached avatar at ${target.absolutePath} (${target.length()} bytes)")
                target
            } catch (e: Exception) {
                Log.w(TAG, "Avatar download failed; profile will fall back to initials", e)
                null
            } finally {
                temp?.takeIf { it.exists() }?.delete()
            }
        }

    /** Returns the raw bytes, or `null` if the response was not a usable 2xx image. */
    private fun fetch(url: String): ByteArray? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            // Google's CDN requires no auth for avatars, but be explicit rather than relying on defaults.
            setRequestProperty("Accept", "image/*")
        }
        try {
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "Avatar request returned HTTP ${connection.responseCode}")
                return null
            }
            val declared = connection.contentLengthLong
            if (declared > MAX_DOWNLOAD_BYTES) {
                Log.w(TAG, "Refusing avatar of $declared bytes; cap is $MAX_DOWNLOAD_BYTES")
                return null
            }
            // Read with a cap: contentLengthLong is -1 for chunked responses, so the limit has to be
            // enforced while reading rather than only checked beforehand.
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > MAX_DOWNLOAD_BYTES) {
                        Log.w(TAG, "Avatar exceeded $MAX_DOWNLOAD_BYTES bytes mid-stream; aborting")
                        return null
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Loads the cached avatar at a bounded decode size. Returns `null` if absent or undecodable. */
    fun load(context: android.content.Context, maxDimension: Int = 256): Bitmap? {
        val file = File(context.cacheDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null

            // inSampleSize must be a power of two, so halve until we are at or below the target.
            var sample = 1
            while (longest / (sample * 2) >= maxDimension) sample *= 2

            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode cached avatar", e)
            // A corrupt cache entry must not persist across launches, so drop it.
            file.delete()
            null
        }
    }

    /** Removes the cached avatar, e.g. on sign-out. */
    fun clear(context: android.content.Context) {
        val file = File(context.cacheDir, FILE_NAME)
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete cached avatar at ${file.absolutePath}")
        }
    }
}
