package ai.kolai.station

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [MoodSwitchWindow], the pure invalidate-window math behind the
 * FAST MOOD SWITCH Bug 1 (HIGH) fix. Verifies that the immediately-next
 * already-rendered block is KEPT (so playback never drains into STATE_ENDED) and
 * that the engine is always invalidated strictly above the playing block.
 */
class MoodSwitchWindowTest {

    @Test
    fun keeps_next_block_when_already_queued() {
        // Playing N=5, with N+1=6 already rendered/queued (plus a further N+2).
        val ids = listOf(4, 5, 6, 7)
        assertEquals(6, MoodSwitchWindow.keepThrough(5, ids))
        // -> invalidate from N+2 = 7, so 5 and 6 keep playing (old mood), 7+ re-render.
        assertEquals(7, MoodSwitchWindow.invalidateFromIndex(5, ids))
    }

    @Test
    fun keeps_only_current_when_next_not_yet_rendered() {
        // Playing N=5 but N+1 not yet in the playlist (cold render still pending).
        val ids = listOf(4, 5)
        assertEquals(5, MoodSwitchWindow.keepThrough(5, ids))
        // -> invalidate from N+1 = 6 (the original behaviour; safety-net covers drain).
        assertEquals(6, MoodSwitchWindow.invalidateFromIndex(5, ids))
    }

    @Test
    fun single_item_playlist_keeps_current_only() {
        val ids = listOf(0)
        assertEquals(0, MoodSwitchWindow.keepThrough(0, ids))
        assertEquals(1, MoodSwitchWindow.invalidateFromIndex(0, ids))
    }

    @Test
    fun invalidate_index_is_always_above_current() {
        // Whatever is queued, we never invalidate at/below the playing block.
        for (cur in 0..10) {
            for (ids in listOf(listOf(cur), listOf(cur, cur + 1), listOf(cur - 1, cur, cur + 1, cur + 2))) {
                assert(MoodSwitchWindow.invalidateFromIndex(cur, ids) > cur)
                assert(MoodSwitchWindow.keepThrough(cur, ids) >= cur)
            }
        }
    }

    @Test
    fun pruned_leading_items_do_not_confuse_membership() {
        // Leading items pruned: positions != ids, but membership still decides.
        val ids = listOf(12, 13) // playing 12, 13 already rendered
        assertEquals(13, MoodSwitchWindow.keepThrough(12, ids))
        assertEquals(14, MoodSwitchWindow.invalidateFromIndex(12, ids))
    }
}