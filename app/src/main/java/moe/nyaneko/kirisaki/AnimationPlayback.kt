// SPDX-License-Identifier: GPL-3.0-only

package moe.nyaneko.kirisaki

/** Persist elapsed time rather than eased progress so a resumed animation keeps the same timing. */
object SeverTimeline {
    const val DURATION_MS = 1500
    fun progress(elapsedMs: Float): Float = when {
        elapsedMs <= 100f -> elapsedMs.coerceAtLeast(0f) / 100f * .16f
        elapsedMs <= 1000f -> .16f + (elapsedMs - 100f) / 900f * .16f
        else -> .32f + ((elapsedMs - 1000f) / 500f).coerceAtMost(1f) * .68f
    }
}

data class AnimationPlayback(
    val elapsedMs: Int = 0,
    val completed: Boolean = false,
    val owner: String = "",
    val leaseUntil: Long = 0,
) {
    fun claim(requester: String, now: Long, hookInstalled: Boolean): AnimationPlayback? {
        if (!hookInstalled || completed || requester.isBlank()) return null
        if (owner.isNotEmpty() && owner != requester && leaseUntil > now) return null
        return copy(owner = requester, leaseUntil = now + 4000)
    }

    fun checkpoint(requester: String, elapsed: Int, now: Long, finish: Boolean, release: Boolean): AnimationPlayback {
        if (completed || owner != requester || owner.isEmpty()) return this
        val position = maxOf(elapsedMs, elapsed.coerceIn(0, SeverTimeline.DURATION_MS))
        val done = finish && position == SeverTimeline.DURATION_MS
        return copy(elapsedMs = position, completed = done,
            owner = if (done || release) "" else requester,
            leaseUntil = if (done || release) 0 else now + 4000)
    }
}
