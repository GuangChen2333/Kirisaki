// SPDX-License-Identifier: GPL-3.0-only

package moe.nyaneko.kirisaki

/** An alternate entry means the installed target method is absent from the actual write stack. */
object EntryPolicy {
    fun isOtherEntry(targetClass: String, targetMethod: String, stack: Array<StackTraceElement>): Boolean =
        stack.none { it.className == targetClass && it.methodName == targetMethod }
}
