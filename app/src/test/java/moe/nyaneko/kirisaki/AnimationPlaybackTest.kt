// SPDX-License-Identifier: GPL-3.0-only

package moe.nyaneko.kirisaki

import org.junit.Assert.*
import org.junit.Test

class AnimationPlaybackTest {
    @Test fun interruptedPlaybackResumesWithoutConsumingCompletion() {
        val claimed = AnimationPlayback().claim("first", 0, true)!!
        val interrupted = claimed.checkpoint("first", 650, 650, false, true)
        assertFalse(interrupted.completed)
        assertEquals(650, interrupted.claim("resumed", 700, true)!!.elapsedMs)
    }
    @Test fun onlyACompletedFinalFramePreventsFurtherPlayback() {
        val claimed = AnimationPlayback().claim("first", 0, true)!!
        val awaitingFrame = claimed.checkpoint("first", 1500, 1500, false, false)
        assertFalse(awaitingFrame.completed)
        val done = awaitingFrame.checkpoint("first", 1500, 1501, true, true)
        assertTrue(done.completed)
        assertNull(done.claim("again", 6000, true))
    }
    @Test fun failedHookOrIncompleteTimelineCannotBecomeCompleted() {
        assertNull(AnimationPlayback().claim("first", 0, false))
        assertFalse(AnimationPlayback().claim("first", 0, true)!!
            .checkpoint("first", 300, 300, true, true).completed)
    }
    @Test fun onlyOneActivityOwnsPlaybackAndDeadOwnerEventuallyExpires() {
        val first = AnimationPlayback().claim("first", 0, true)!!
        assertNull(first.claim("second", 1000, true))
        val second = first.claim("second", 4001, true)!!
        assertEquals(second, second.checkpoint("first", 1500, 4002, true, true))
    }
    @Test fun resumedTimelineKeepsFastSlowFastTiming() {
        assertEquals(.16f, SeverTimeline.progress(100f), .0001f)
        assertEquals(.24f, SeverTimeline.progress(550f), .0001f)
        assertEquals(.32f, SeverTimeline.progress(1000f), .0001f)
        assertEquals(1f, SeverTimeline.progress(1500f), .0001f)
    }
}
