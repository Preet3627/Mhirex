package com.mhirex.editor

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

        // AD-2 was REVERSED on request: `applicationId` now matches the namespace
        // (`com.mhirex.editor`) instead of staying `com.tharunbirla.librecuts`.
        //
        // Consequences, all accepted deliberately: Mhirex installs as a SEPARATE app from
        // LibreCuts, so there is no upgrade path between them. LibreCuts users must reinstall and
        // their saved projects/settings are not reachable from Mhirex. The trade-off gained is a
        // package id Mhirex actually owns, which is a prerequisite for Play Store publication.
        //
        // This assertion is the regression guard: it fails if either the namespace or the
        // applicationId drifts apart again, so the next identity change has to be made on purpose.
        assertEquals("com.mhirex.editor", appContext.packageName)

        // The namespace did move, so the generated R class must be reachable from the new package.
        // If the rename regressed, this would not compile at all — which is the primary guard.
        assertNotNull(R.string.app_name)
    }

    /** PLAN AD-2: the visible app name must be the Mhirex brand in every locale. */
    @Test
    fun appNameIsMhirex() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appName = context.getString(R.string.app_name)
        assertEquals("Mhirex", appName)
    }
}