// SPDX-License-Identifier: GPL-3.0-only

package moe.nyaneko.kirisaki

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StateProviderTest {
    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    @Test fun ownAppCanReadBootSession() {
        val record = requireNotNull(resolver.call(STATE_URI, "read", null, null))
        assertFalse(record.getString("boot").isNullOrBlank())
    }
    @Test fun ownAppCannotForgeHookSuccess() {
        try {
            resolver.call(STATE_URI, "report", null, Bundle().apply { putBoolean("precise", true) })
            fail("Own UID must not report module success")
        } catch (_: IllegalStateException) { }
    }
    @Test fun unvalidatedStateNeverStartsSuccessAnimation() {
        val record = requireNotNull(resolver.call(STATE_URI, "read", null, null))
        if (!record.getBoolean("precise")) {
            assertFalse(requireNotNull(resolver.call(STATE_URI, "beginAnimation", null, Bundle().apply { putString("owner", "instrumented-test") })).getBoolean("play"))
        }
    }
}
