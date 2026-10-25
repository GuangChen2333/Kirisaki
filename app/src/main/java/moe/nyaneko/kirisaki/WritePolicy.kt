// SPDX-License-Identifier: GPL-3.0-only

package moe.nyaneko.kirisaki

/** Exact provider and inserted key/value only; value 0 always passes through. */
object WritePolicy {
    fun blocks(uri: String?, name: String?, value: String?, keys: Set<String>): Boolean =
        uri == "content://com.google.settings/partner" &&
            name == "network_location_opt_in" && value == "1" && keys == setOf("name", "value")
}
