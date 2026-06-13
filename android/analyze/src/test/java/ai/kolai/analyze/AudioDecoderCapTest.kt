package ai.kolai.analyze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the DECODE-LENGTH CAP math (OOM safety net, 2026-06-13).
 *
 * [ai.kolai.analyze.AudioDecoder.decodeWithSampleRate] itself needs MediaCodec
 * (device/emulator only - the unit-test JVM has stub android.media classes), so
 * the cap LOGIC was factored into two pure helpers that ARE testable here:
 *  - [AudioDecoder.cappedSampleCeiling] - the interleaved-sample ceiling the
 *    decode loop enforces (break) and growTo is bounded by;
 *  - [AudioDecoder.preSizeSamples] - the exact (clamped) pre-size that avoids
 *    the growth-by-doubling overshoot.
 *
 * The decode loop is asserted to RESPECT these by construction (see
 * AudioDecoder.kt: the output-collect block clamps `shortCount` to
 * `sampleCeiling - collectedLen`, marks `capped`, and stops the loop; growTo
 * never exceeds the ceiling). A normal song decodes far below the cap so it is
 * untouched - these tests pin both the boundary and the normal-song no-op.
 */
class AudioDecoderCapTest {

    private val maxSeconds = AudioDecoder.MAX_DECODE_SECONDS // 600

    // ---- cappedSampleCeiling -------------------------------------------------

    @Test
    fun ceiling_is_maxSeconds_times_rate_times_channels_for_mono_44k() {
        // mono 44.1k, 600 s -> 26,460,000 interleaved samples (~100 MB Float).
        val ceil = AudioDecoder.cappedSampleCeiling(44_100, 1, maxSeconds)
        assertEquals(44_100 * 1 * 600, ceil)
    }

    @Test
    fun ceiling_scales_with_channels() {
        val mono = AudioDecoder.cappedSampleCeiling(44_100, 1, maxSeconds)
        val stereo = AudioDecoder.cappedSampleCeiling(44_100, 2, maxSeconds)
        assertEquals(2 * mono.toLong(), stereo.toLong())
    }

    @Test
    fun ceiling_unknown_rate_or_channels_means_no_cap() {
        // rate/channels not yet known at pre-size time -> Int.MAX_VALUE so a
        // missing input format never wrongly truncates (the real cap applies
        // once the output format reports a sane rate).
        assertEquals(Int.MAX_VALUE, AudioDecoder.cappedSampleCeiling(0, 1, maxSeconds))
        assertEquals(Int.MAX_VALUE, AudioDecoder.cappedSampleCeiling(44_100, 0, maxSeconds))
        assertEquals(Int.MAX_VALUE, AudioDecoder.cappedSampleCeiling(-1, -1, maxSeconds))
    }

    @Test
    fun ceiling_never_overflows_int() {
        // A pathological huge rate*channels*seconds must clamp to Int.MAX_VALUE
        // (no long->int overflow into a negative FloatArray size).
        val ceil = AudioDecoder.cappedSampleCeiling(192_000, 8, 1_000_000)
        assertEquals(Int.MAX_VALUE, ceil)
    }

    // ---- preSizeSamples ------------------------------------------------------

    @Test
    fun preSize_matches_duration_for_a_normal_song() {
        // A 3:30 (210 s) mono 44.1k single: pre-size == exact sample count, well
        // under the cap, so no doubling overshoot AND no truncation.
        val durUs = 210L * 1_000_000L
        val pre = AudioDecoder.preSizeSamples(durUs, 44_100, 1, maxSeconds)
        assertEquals(210 * 44_100, pre)
        assertTrue("normal song must be below the cap",
            pre < AudioDecoder.cappedSampleCeiling(44_100, 1, maxSeconds))
    }

    @Test
    fun preSize_clamps_an_overlong_mix_to_the_cap() {
        // The PROVEN OOM case: a 24-min (1440 s) mono 44.1k "song" would need
        // ~63.5M samples (~252 MB). Pre-size must clamp to the 600 s ceiling so
        // the single allocation can never reach the OOM size.
        val durUs = 1440L * 1_000_000L
        val pre = AudioDecoder.preSizeSamples(durUs, 44_100, 1, maxSeconds)
        val ceil = AudioDecoder.cappedSampleCeiling(44_100, 1, maxSeconds)
        assertEquals(ceil, pre)
        // ~100 MB, not ~252 MB.
        assertTrue("capped pre-size must be far below the OOM 252MB/63M-sample case",
            pre < 30_000_000)
    }

    @Test
    fun preSize_unknown_duration_returns_zero_for_growth_fallback() {
        // No KEY_DURATION -> 0 so the caller falls back to a modest default and
        // grows on demand (still bounded by the ceiling).
        assertEquals(0, AudioDecoder.preSizeSamples(0L, 44_100, 1, maxSeconds))
        assertEquals(0, AudioDecoder.preSizeSamples(-5L, 44_100, 1, maxSeconds))
    }

    @Test
    fun preSize_never_exceeds_ceiling() {
        // Across a range of durations the pre-size is always within [0, ceiling]
        // - the invariant the worst-case allocation relies on.
        val ceil = AudioDecoder.cappedSampleCeiling(48_000, 2, maxSeconds)
        for (minutes in 0..60) {
            val pre = AudioDecoder.preSizeSamples(minutes * 60L * 1_000_000L, 48_000, 2, maxSeconds)
            assertTrue("pre-size $pre out of [0, $ceil] at $minutes min", pre in 0..ceil)
        }
    }
}