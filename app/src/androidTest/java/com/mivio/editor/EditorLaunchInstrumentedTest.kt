package com.mivio.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mivio.editor.utils.uriExtraCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Covers the editor's real in-app launch path.
 *
 * Two changes made it worth pinning down with an actual launch rather than a compile check:
 *
 *  * **P0-7** — `setupExoPlayer()` used to build the ExoPlayer only when a `VIDEO_URI` extra was
 *    present, while `onCreate` attached a `Player.Listener` unconditionally. The activity crashed
 *    in `onCreate` whenever the extra was missing. The activity is now unexported, but the
 *    no-URI launch remains a useful defensive regression guard.
 *  * **IntentCompat** — all four `getParcelableExtra` call sites were replaced with a version-
 *    guarded typed accessor. A typo in the extra *name*, or a cast that silently yields `null`,
 *    would look identical to "the picker gave us nothing" at the call site, so the round trip is
 *    asserted directly.
 *
 * Reaching RESUMED also transitively proves the whole editor layout inflated, which is the only
 * reliable check that the 18 custom-view FQCNs rewritten during the `com.tharunbirla.librecuts` →
 * `com.mivio.editor` rename still resolve. Those are resolved by reflection at inflate time, so a
 * stale FQCN compiles cleanly and crashes only here.
 */
@RunWith(AndroidJUnit4::class)
class EditorLaunchInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * A real, decodable clip, staged from the test APK's own assets.
     *
     * The editor builds an ExoPlayer and calls `prepare()` against this URI, so a byte blob would
     * fail for reasons unrelated to what is under test. The fixture ships as
     * `src/androidTest/assets/mivio_test_fixture.mp4` (1s, 64x64, H.264 + silent AAC) rather than
     * being generated on the device, because emulator system images carry no `ffmpeg` CLI — an
     * earlier version of this test tried to shell out and silently skipped itself.
     */
    private fun testClip(): File {
        val clip = File(context.cacheDir, "editor_launch_fixture.mp4")
        if (clip.exists() && clip.length() > 0) return clip

        // Must be the *instrumentation* context: `context` is the app under test, whose assets are
        // the app's. The fixture lives in the androidTest source set, so it is packaged into the
        // test APK instead.
        val testContext = InstrumentationRegistry.getInstrumentation().context
        testContext.assets.open("mivio_test_fixture.mp4").use { input ->
            clip.outputStream().use { output -> input.copyTo(output) }
        }
        assertTrue("fixture clip is empty", clip.length() > 0)
        return clip
    }

    /** Mirrors the extra name `MainActivity.kt:555` writes. */
    private fun editorIntent(videoUri: Uri?): Intent =
        Intent(context, VideoEditingActivity::class.java).apply {
            if (videoUri != null) putExtra("VIDEO_URI", videoUri)
        }

    /**
     * P0-7 regression guard: a `VIDEO_URI`-less launch must not crash the activity.
     *
     * Before the fix this threw `UninitializedPropertyAccessException: lateinit property player has
     * not been initialized` from `onCreate`, because `setupExoPlayer()` built the ExoPlayer only
     * when the extra was present while `onCreate` attached a `Player.Listener` unconditionally.
     *
     * Deliberately launched with **no** media. A variant that loads a real clip does reach RESUMED
     * — which is how the FQCN rename was verified — but then OOM-kills the AVD during
     * `ActivityScenario.close()`: the editor kicks off an FFmpeg preview render, and a 3 GB AVD on
     * `swiftshader_indirect` cannot sustain FFmpeg plus a software GPU. lmkd sends SIGKILL, the
     * instrumentation process dies, and the whole suite is reported as failed for a reason that has
     * nothing to do with the app. That check is therefore done by hand over `adb` and recorded in
     * `TODO.md`, not as a test. See the Phase 0 notes for the exact commands.
     */
    @Test
    fun editor_launchesWithoutVideoUri_doesNotCrash() {
        ActivityScenario.launch<VideoEditingActivity>(editorIntent(null)).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("activity finished early", !activity.isFinishing)
                assertNotNull("editor has no content view", activity.findViewById<android.view.View>(android.R.id.content))
                assertNotNull(
                    "timeline container missing — the editor layout did not inflate",
                    activity.findViewById<android.view.View>(R.id.timelineHorizontalScroll),
                )
                assertNotNull(
                    "player view missing — the editor layout did not inflate",
                    activity.findViewById<android.view.View>(R.id.playerView),
                )
            }
        }
    }

    /**
     * Direct test of the compat accessor, including the API 33+ branch. Asserting the accessor
     * itself is what makes the four call sites trustworthy: a wrong extra *name* is otherwise
     * indistinguishable from "the caller forgot to attach it".
     */
    @Test
    fun uriExtraCompat_readsUriOnEveryApiLevel() {
        val clip = testClip()
        val uri = Uri.fromFile(clip)
        val intent = Intent().putExtra("VIDEO_URI", uri)

        assertEquals(
            "uriExtraCompat returned the wrong value on API ${Build.VERSION.SDK_INT}",
            uri,
            intent.uriExtraCompat("VIDEO_URI"),
        )

        // And a missing extra must be null, not a crash and not a wrong key's value.
        assertNull(intent.uriExtraCompat("PROJECT_URI"))
    }
}
