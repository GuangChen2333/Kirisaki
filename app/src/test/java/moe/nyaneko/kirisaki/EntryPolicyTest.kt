// SPDX-License-Identifier: GPL-3.0-only

package moe.nyaneko.kirisaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryPolicyTest {
    private fun frame(clazz: String, method: String) = StackTraceElement(clazz, method, "SourceFile", 1)
    @Test fun directAndHelperWritesStillBelongToInstalledTarget() {
        assertFalse(EntryPolicy.isOtherEntry("a.Target", "enforce", arrayOf(
            frame("android.content.ContentResolver", "update"), frame("a.Helper", "write"), frame("a.Target", "enforce"))))
    }
    @Test fun otherMethodOrClassIsAnAlternateEntry() {
        assertTrue(EntryPolicy.isOtherEntry("a.Target", "enforce", arrayOf(frame("a.Target", "different"))))
        assertTrue(EntryPolicy.isOtherEntry("a.Target", "enforce", arrayOf(frame("a.Other", "enforce"))))
    }
}
