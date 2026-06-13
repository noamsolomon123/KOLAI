package ai.kolai.station

/**
 * Pure helper for the FAST MOOD SWITCH invalidate-window math (see
 * KolaiMediaService.startMoodSwitchCollector). Lives here, next to
 * [StationEngine.invalidateFrom], because it is the domain rule that decides
 * WHICH blocks survive a manual mood change and from where the engine must
 * re-render -- and because the station module is the one with JVM unit tests
 * (the app module only has broken instrumented tests on this host).
 *
 * THE BUG THIS GUARDS (Bug 1, HIGH): the original switch stripped EVERY player
 * item with id > curBlock, leaving only the playing block N, then forced a COLD
 * render of N+1 via invalidateFrom(N+1). If block N finished before N+1''s render
 * returned, ExoPlayer drained to STATE_ENDED; the later-appended N+1 then took
 * neither resume branch (coldStart=false, and state was ENDED not IDLE), so the
 * station went silent until a manual transport action.
 *
 * THE FIX (primary): do NOT strip the immediately-next ALREADY-RENDERED block.
 * Keep block N AND N+1 (old mood, already on disk + in the playlist) so playback
 * continues seamlessly N -> N+1(old) -> N+2(new), and invalidate the engine from
 * N+2 instead. This trades one extra block of the old mood for ZERO drain/silence.
 * If N+1 is NOT yet rendered (not in the playlist), there is nothing to drain
 * into anyway, so we keep just N and the feed-loop ENDED safety-net (Bug 1b)
 * covers the rare drain.
 */
object MoodSwitchWindow {

    /**
     * Highest block id to KEEP in the player playlist on a manual mood switch.
     *
     * @param curBlock  the block id currently being PLAYED (mediaId as Int).
     * @param queuedBlockIds the block ids (mediaIds) currently present in the
     *   player playlist. Order/positions are irrelevant here -- only membership
     *   matters, because the playlist may have pruned leading items.
     * @return curBlock+1 IFF that block is already rendered and present in the
     *   playlist (so we can keep it and avoid a drain); otherwise curBlock.
     *
     * Items with id > the returned value are removed from the playlist, and the
     * engine is invalidated from (returned value + 1). Both are strictly greater
     * than curBlock, so the PLAYING block is never disturbed.
     */
    fun keepThrough(curBlock: Int, queuedBlockIds: Collection<Int>): Int =
        if (queuedBlockIds.contains(curBlock + 1)) curBlock + 1 else curBlock

    /**
     * The index the engine must invalidate FROM (re-render with the new mood):
     * one past [keepThrough]. Always > curBlock, so it is safe -- and
     * [StationEngine.invalidateFrom] additionally clamps to current+1.
     */
    fun invalidateFromIndex(curBlock: Int, queuedBlockIds: Collection<Int>): Int =
        keepThrough(curBlock, queuedBlockIds) + 1
}