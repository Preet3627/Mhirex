package com.mivio.editor

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/doc/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        // `applicationId` is deliberately NOT `com.mivio.editor` for the 1.x line. Changing it would
        // orphan every user's saved projects and split the F-Droid/Obtainium listing and the Weblate
        // project. Only the source-level `namespace` moved. See PLAN.md AD-2.
        //
        // This assertion is therefore a regression guard: if someone "tidies up" the applicationId
        // to match the namespace, this test fails and forces the migration decision to be made
        // deliberately rather than by accident.
        assertEquals("com.tharunbirla.librecuts", appContext.packageName)

        // The namespace did move, so the generated R class must be reachable from the new package.
        // If the rename regressed, this would not compile at all — which is the primary guard.
        assertNotNull(R.string.app_name)
    }

    /** PLAN AD-2: the visible app name must be the MhireX brand in every locale. */
    @Test
    fun appNameIsMhireX() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appName = context.getString(R.string.app_name)
        assertEquals("MhireX", appName)
    }
}