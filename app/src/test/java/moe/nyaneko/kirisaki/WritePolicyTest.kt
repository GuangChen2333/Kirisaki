// SPDX-License-Identifier: GPL-3.0-only

package moe.nyaneko.kirisaki

import org.junit.Assert.*
import org.junit.Test

class WritePolicyTest {
    private val uri = "content://com.google.settings/partner"
    private val name = "network_location_opt_in"
    private val keys = setOf("name", "value")
    @Test fun onlyExactEnableWriteIsBlocked() {
        assertTrue(WritePolicy.blocks(uri, name, "1", keys))
        assertFalse(WritePolicy.blocks(uri, name, "0", keys))
        assertFalse(WritePolicy.blocks(uri, "other", "1", keys))
        assertFalse(WritePolicy.blocks("$uri/other", name, "1", keys))
        assertFalse(WritePolicy.blocks("$uri?x=1", name, "1", keys))
        assertFalse(WritePolicy.blocks(uri, name, "1", keys + "other"))
        assertFalse(WritePolicy.blocks(null, null, null, emptySet()))
    }
}
