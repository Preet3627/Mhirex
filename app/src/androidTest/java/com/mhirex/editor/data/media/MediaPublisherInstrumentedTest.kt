package com.mhirex.editor.data.media

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented coverage for the two release-blocking storage defects.
 *
 * These cannot be verified by a JVM unit test: `MediaStore`, scoped storage and `IS_PENDING` are
 * all framework services that only exist on a real Android runtime. The compile that introduced
 * these bugs was green, and so is the compile that fixes them — only running the code tells you
 * whether a file actually lands in the user's library.
 */
@RunWith(AndroidJUnit4::class)
class MediaPublisherInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    /** Distinct name per run so repeated runs do not collide in the user's MediaStore. */
    private val runTag = "mhirex_test_${System.currentTimeMillis()}"

    @Before
    fun cleanSlate() {
        // Remove anything a previous aborted run left behind, so a leak is attributable to this run.
        resolver.delete(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("mhirex_pubtest_%"),
        )
    }

    private fun makeSourceFile(name: String, sizeBytes: Int = 64 * 1024): File {
        val file = File(context.cacheDir, name)
        // Deterministic non-zero content so a truncated copy is detectable by hash, not just size.
        val payload = ByteArray(sizeBytes) { (it % 251).toByte() }
        file.writeBytes(payload)
        return file
    }

    private fun publishedUriCount(): Int {
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("mhirex_pubtest_%"),
            null,
        )!!.use { cursor -> return cursor.count }
    }

    /**
     * P0-1: the no-edits export path used to write with `FileOutputStream` straight into
     * `Environment.getExternalStoragePublicDirectory(...)`, which is forbidden from API 29. This
     * asserts a modern publish yields a readable `content://` URI whose bytes survive intact.
     */
    @Test
    fun publish_toMediaStore_yieldsReadableContentUri_withIntactBytes() {
        val source = makeSourceFile("source_$runTag.bin")
        val expected = source.readBytes()

        val outcome = MediaPublisher.publish(
            context = context,
            source = source,
            mimeType = "video/mp4",
            displayName = "mhirex_pubtest_$runTag.mp4",
            collection = MediaPublisher.Collection.VIDEO,
            customTreeUri = null,
        )

        assertTrue(
            "expected Published but was $outcome",
            outcome is MediaPublisher.Outcome.Published,
        )
        val uri = (outcome as MediaPublisher.Outcome.Published).uri

        // A public-directory File would have had a "file://" or bare path; scoped storage mandates content://.
        assertEquals("content", uri.scheme)
        assertFalse("default publish must not silently go through SAF", outcome.viaSaf)

        // And the bytes must actually be readable back — this is what P0-1 broke.
        val readBack = resolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals(
            "published bytes differ from source: the copy was truncated or corrupted",
            expected.size,
            readBack.size,
        )
        assertTrue("published bytes differ from source", expected.contentEquals(readBack))
    }

    /**
     * P0-6: rows used to be inserted and written in place, so a process death mid-copy left a
     * truncated, permanently indexed, unplayable file in the user's library. The row must be
     * invisible while pending and must not survive a failure.
     */
    @Test
    fun publish_cancelled_leavesNoRowBehind() {
        val source = makeSourceFile("cancel_$runTag.bin")
        val before = publishedUriCount()

        // Cancel on the very first poll, i.e. before any byte is written.
        val outcome = MediaPublisher.publish(
            context = context,
            source = source,
            mimeType = "video/mp4",
            displayName = "mhirex_pubtest_cancel_$runTag.mp4",
            collection = MediaPublisher.Collection.VIDEO,
            customTreeUri = null,
            isCancelled = { true },
        )

        assertTrue(
            "expected Cancelled but was $outcome",
            outcome is MediaPublisher.Outcome.Cancelled,
        )

        // The critical assertion: a cancelled publish must not leave a visible row.
        val after = publishedUriCount()
        assertEquals("cancelled publish leaked a MediaStore row", before, after)

        // And nothing may be left pending, which would be invisible to the user but still occupy
        // storage until the system garbage-collects it.
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} != 0",
            arrayOf("mhirex_pubtest_%"),
            null,
        )!!.use { cursor ->
            assertEquals("a row was left in IS_PENDING state", 0, cursor.count)
        }
    }

    /**
     * A missing source must fail cleanly rather than publishing a zero-byte "successful" export —
     * the user would otherwise find an unplayable file in their gallery with no error shown.
     */
    @Test
    fun publish_missingSource_failsWithoutCreatingRow() {
        val before = publishedUriCount()
        val missing = File(context.cacheDir, "definitely_absent_$runTag.mp4")
        assertFalse(missing.exists())

        val outcome = MediaPublisher.publish(
            context = context,
            source = missing,
            mimeType = "video/mp4",
            displayName = "mhirex_pubtest_missing_$runTag.mp4",
            collection = MediaPublisher.Collection.VIDEO,
            customTreeUri = null,
        )

        assertTrue("expected Failed but was $outcome", outcome is MediaPublisher.Outcome.Failed)
        assertEquals("failed publish leaked a MediaStore row", before, publishedUriCount())
    }

    /**
     * The frame-snapshot path (`VideoEditingActivity.saveBitmapToGallery`) was the third and last
     * duplicated publisher. It was rewired to `Collection.IMAGE`, so assert that collection routes
     * to Pictures/Mhirex and is not silently landing in the video collection.
     */
    @Test
    fun publish_imageCollection_landsInPicturesMhirex() {
        val source = makeSourceFile("shot_$runTag.bin", sizeBytes = 4 * 1024)

        val outcome = MediaPublisher.publish(
            context = context,
            source = source,
            mimeType = "image/png",
            displayName = "mhirex_pubtest_$runTag.png",
            collection = MediaPublisher.Collection.IMAGE,
            customTreeUri = null,
        )

        assertTrue("expected Published but was $outcome", outcome is MediaPublisher.Outcome.Published)
        val uri = (outcome as MediaPublisher.Outcome.Published).uri

        // The row must be in the Images collection, not Movies.
        assertEquals(
            "image snapshot was written to the wrong MediaStore collection",
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI.takeIf {
                resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.MIME_TYPE),
                    null, null, null,
                )!!.use { c -> c.moveToFirst(); c.getString(0) == "image/png" }
            },
        )

        resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            val path = c.getString(0) ?: ""
            assertTrue("expected a Pictures/Mhirex path but got '$path'", path.contains("Pictures/Mhirex"))
        }

        resolver.delete(uri, null, null)
    }

    /** A successful publish must clear IS_PENDING, otherwise the file stays invisible to the gallery. */
    @Test
    fun publish_success_clearsIsPending() {
        val source = makeSourceFile("pending_$runTag.bin")

        val outcome = MediaPublisher.publish(
            context = context,
            source = source,
            mimeType = "video/mp4",
            displayName = "mhirex_pubtest_pending_$runTag.mp4",
            collection = MediaPublisher.Collection.VIDEO,
            customTreeUri = null,
        )
        assertTrue(outcome is MediaPublisher.Outcome.Published)
        val uri = (outcome as MediaPublisher.Outcome.Published).uri

        resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            val pending = c.getInt(0)
            assertNotNull(pending)
            assertEquals("row left IS_PENDING=1; the gallery will never show this file", 0, pending)
        }

        // Also confirm the default location is the rebranded folder, not the legacy one.
        resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            null, null, null,
        )!!.use { c ->
            assertTrue(c.moveToFirst())
            val path = c.getString(0) ?: ""
            assertTrue("expected Mhirex folder but got '$path'", path.contains("Mhirex"))
            assertFalse("legacy LibreCuts folder should no longer be written", path.contains("LibreCuts"))
        }
    }
}
