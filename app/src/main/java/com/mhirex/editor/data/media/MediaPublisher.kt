package com.mhirex.editor.data.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.mhirex.editor.Branding
import java.io.File
import java.io.IOException

/**
 * Publishes a rendered file to user-visible storage.
 *
 * This exists because the same "write the finished file somewhere the user can find it"
 * logic was duplicated in three places ([com.mhirex.editor.VideoEditingActivity.saveBitmapToGallery],
 * `VideoEditingActivity.saveVideoToGallery` and `ExportService.saveVideoToGallery`), and the copies
 * disagreed with each other. That divergence is what produced two release-blocking bugs:
 *
 *  * **P0-1** — the no-edits export path wrote with `FileOutputStream` straight into
 *    `Environment.getExternalStoragePublicDirectory(...)`. `WRITE_EXTERNAL_STORAGE` is capped at
 *    `maxSdkVersion="28"`, so on Android 10+ the app's most basic action — save a video you did not
 *    edit — failed with a raw filesystem exception.
 *  * **P0-6** — MediaStore rows were inserted and written in place with no `IS_PENDING` flag, so a
 *    process death mid-copy left a truncated, permanently indexed, unplayable file in the user's
 *    library forever.
 *
 * All three call sites now route through here so the storage rules live in one place.
 *
 * Contract:
 *  * Never writes directly to a public directory. Everything goes through MediaStore (API 29+) or
 *    a SAF tree URI the user explicitly granted.
 *  * MediaStore rows are inserted with `IS_PENDING=1` and only published with `IS_PENDING=0` after
 *    the final byte is written. A failure deletes the row.
 *  * On cancellation the partially written document is deleted, not abandoned.
 */
object MediaPublisher {

    private const val TAG = "MediaPublisher"
    private const val BUFFER_SIZE = 64 * 1024

    /** Which public collection a [pendingUri] should be inserted into. */
    enum class Collection {
        VIDEO, AUDIO, IMAGE;

        val contentUri: Uri
            get() = when (this) {
                VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

        val topLevelDirectory: String
            get() = when (this) {
                VIDEO -> Environment.DIRECTORY_MOVIES
                AUDIO -> Environment.DIRECTORY_MUSIC
                IMAGE -> Environment.DIRECTORY_PICTURES
            }
    }

    /** Outcome of a publish attempt. */
    sealed interface Outcome {
        /** Bytes are fully written and the entry is visible to other apps. */
        data class Published(val uri: Uri, val viaSaf: Boolean) : Outcome

        /** The copy was interrupted and the partial entry was removed. */
        data class Cancelled(val reason: String) : Outcome

        /** The copy failed and the partial entry was removed. */
        data class Failed(val reason: String, val cause: Throwable? = null) : Outcome
    }

    /**
     * Copies [source] to a user-visible destination.
     *
     * @param customTreeUri a SAF tree URI the user granted via the folder picker, or `null` to use
     *   the default `MediaStore` location.
     * @param onProgress receives 0..100, throttled to roughly every 100 ms.
     * @param isCancelled polled during the copy so a user cancel takes effect promptly. Polling
     *   rather than passing a coroutine Job keeps this usable from both a `Service` and an Activity.
     */
    fun publish(
        context: Context,
        source: File,
        mimeType: String,
        displayName: String,
        collection: Collection,
        customTreeUri: Uri? = null,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Outcome {
        val resolver = context.contentResolver
        if (!source.exists() || !source.isFile || source.length() <= 0L) {
            return Outcome.Failed("Source file is missing or empty: ${source.absolutePath}")
        }
        if (isCancelled()) return Outcome.Cancelled("Export cancelled")

        val totalBytes = source.length().coerceAtLeast(1L)

        // Preferred path: a folder the user explicitly granted. No IS_PENDING concept here, so we
        // must delete the document ourselves on any failure or cancellation.
        if (customTreeUri != null) {
            val safOutcome = publishViaSaf(
                context = context,
                source = source,
                mimeType = mimeType,
                displayName = displayName,
                treeUri = customTreeUri,
                totalBytes = totalBytes,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
            if (safOutcome !is Outcome.Failed) return safOutcome
            // A broken or revoked tree URI should not fail the export; fall through to MediaStore.
            Log.w(TAG, "SAF publish failed (${safOutcome.reason}); falling back to MediaStore")
        }

        return publishViaMediaStore(
            context = context,
            source = source,
            mimeType = mimeType,
            displayName = displayName,
            collection = collection,
            totalBytes = totalBytes,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
    }

    private fun publishViaSaf(
        context: Context,
        source: File,
        mimeType: String,
        displayName: String,
        treeUri: Uri,
        totalBytes: Long,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ): Outcome {
        val resolver = context.contentResolver
        var documentUri: Uri? = null
        fun deletePartialDocument(uri: Uri) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                .onSuccess { deleted ->
                    if (!deleted) Log.w(TAG, "SAF provider refused to delete partial document: $uri")
                }
                .onFailure { Log.w(TAG, "Could not delete partial SAF document: ${it.message}") }
        }

        return try {
            if (isCancelled()) return Outcome.Cancelled("Export cancelled")
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val destination = DocumentsContract.createDocument(resolver, parentUri, mimeType, displayName)
                ?: return Outcome.Failed("createDocument returned null for the granted folder")
            documentUri = destination

            val copyResult = copyInto(
                resolver.openOutputStream(destination),
                source,
                totalBytes,
                onProgress,
                isCancelled,
            )
            val copyFailure = copyResult.exceptionOrNull()
            if (copyFailure != null) {
                deletePartialDocument(destination)
                return if (copyFailure is CopyCancelled) {
                    Outcome.Cancelled(copyFailure.message ?: "Export cancelled")
                } else {
                    Outcome.Failed(copyFailure.message ?: "Copy failed", copyFailure)
                }
            }

            // SAF has no IS_PENDING equivalent. Re-check after the final flush before declaring
            // the document complete; cancellation observed here removes the partial document.
            if (isCancelled()) {
                deletePartialDocument(destination)
                return Outcome.Cancelled("Export cancelled")
            }
            Outcome.Published(destination, viaSaf = true)
        } catch (e: Exception) {
            documentUri?.let(::deletePartialDocument)
            if (e is CopyCancelled) {
                Outcome.Cancelled(e.message ?: "Export cancelled")
            } else {
                Outcome.Failed(e.message ?: "SAF write failed", e)
            }
        }
    }

    private fun publishViaMediaStore(
        context: Context,
        source: File,
        mimeType: String,
        displayName: String,
        collection: Collection,
        totalBytes: Long,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ): Outcome {
        val resolver = context.contentResolver
        var pendingUri: Uri? = null
        var published = false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${collection.topLevelDirectory}/${Branding.OUTPUT_FOLDER}")
                // P0-6: keep the row invisible to other apps until the final byte has landed.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            pendingUri = resolver.insert(collection.contentUri, values)
                ?: return Outcome.Failed("MediaStore insert returned null")

            val copyResult = copyInto(
                resolver.openOutputStream(pendingUri),
                source,
                totalBytes,
                onProgress,
                isCancelled,
            )
            copyResult.getOrThrow()

            // This is the commit boundary.  A cancellation observed after the final flush wins
            // over publication; once the provider update succeeds, success is final and a later
            // cancellation does not delete a valid user-visible file.
            if (isCancelled()) throw CopyCancelled("Export cancelled")
            val publishValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            val updatedRows = resolver.update(pendingUri, publishValues, null, null)
            if (updatedRows <= 0) {
                throw IOException("MediaStore did not update the pending row")
            }

            // Verify the provider actually cleared IS_PENDING.  Some test/fallback providers
            // return success from update() without changing the row.
            val stillPending = resolver.query(
                pendingUri,
                arrayOf(MediaStore.MediaColumns.IS_PENDING),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) != 0 else true
            } ?: true
            if (stillPending) throw IOException("MediaStore row is still pending after publish")

            published = true
            // A progress callback is informational and must not turn an already committed file
            // into a reported failure.
            runCatching { onProgress(100) }
            Outcome.Published(pendingUri, viaSaf = false)
        } catch (e: CopyCancelled) {
            Outcome.Cancelled(e.message ?: "Export cancelled")
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "MediaStore write failed", e)
        } finally {
            // Any path that did not reach IS_PENDING=0 must not leave a broken row behind.
            if (!published) {
                pendingUri?.let {
                    runCatching { resolver.delete(it, null, null) }
                        .onFailure { Log.w(TAG, "Could not delete pending MediaStore row: ${it.message}") }
                }
            }
        }
    }

    private class CopyCancelled(message: String) : IOException(message)

    /** Thrown-free copy so callers can distinguish cancellation from a real I/O failure. */
    private fun copyInto(
        sink: java.io.OutputStream?,
        source: File,
        totalBytes: Long,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ): Result<Unit> = runCatching {
        if (sink == null) throw IOException("Could not open destination for writing")
        source.inputStream().use { input ->
            sink.use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var copied = 0L
                var lastEmit = 0L
                while (true) {
                    if (isCancelled()) throw CopyCancelled("Export cancelled")
                    val read = input.read(buffer)
                    if (isCancelled()) throw CopyCancelled("Export cancelled")
                    if (read < 0) break
                    if (isCancelled()) throw CopyCancelled("Export cancelled")
                    output.write(buffer, 0, read)
                    if (isCancelled()) throw CopyCancelled("Export cancelled")
                    copied += read
                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= 100) {
                        onProgress(((copied * 100) / totalBytes).toInt().coerceIn(0, 100))
                        lastEmit = now
                    }
                }
                if (isCancelled()) throw CopyCancelled("Export cancelled")
                output.flush()
                if (isCancelled()) throw CopyCancelled("Export cancelled")
            }
        }
    }
}
