package ai.kolai.station

import ai.kolai.core.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Endless rolling block queue. Ported 1:1 from backend/radioai/station.py class
 * StationEngine. Renders blocks strictly in order (continuity via a single
 * rolling prev-track), keeps blocks [current .. current+bufferAhead] ready, and
 * PRUNES old block files + registry entries so it runs forever without filling
 * disk or RAM. [reset] restarts from a fresh, reshuffled queue.
 *
 * PORTING NOTES (Python threading -> Kotlin coroutines):
 *  - The renderer/planner are injected as FUNCTION SEAMS (not the concrete
 *    final classes) so the engine is testable with fakes. Production passes
 *    rollingPlanner::nextSongs and blockRenderer::render.
 *  - Python's two locks (`_lock` + `_render_lock`) become two [Mutex]es: a
 *    [stateMutex] guarding the registry/frontier/generation/current, and a
 *    [renderMutex] serializing [ensureThrough] so only one render loop runs at a
 *    time. CRUCIALLY the state mutex is NOT held across the suspending
 *    [renderBlock] call (Python releases `_lock` during render and re-acquires
 *    only to store) -- this is what makes the generation check meaningful.
 *  - Python's `Event.wait(2.0)` + `_wake.set()` becomes a CONFLATED
 *    [Channel] wake signal: the run loop `withTimeoutOrNull(2000){ receive() }`,
 *    and [advance]/[reset] `trySend(Unit)`. CONFLATED collapses bursts of wakes
 *    into one pending token (matching `Event` semantics) and never suspends the
 *    sender.
 *  - Block files are `.m4a` (AAC), matching BlockRenderer's Android output, NOT
 *    Python's `.mp3`.
 *
 * DISK PERSISTENCE (Android addition, not in the Python):
 *  - Alongside every committed `block_N.m4a` the engine writes
 *    `block_N.meta.json` (the full [BlockMeta]), and after every commit /
 *    [advance] it writes `state.json` ({frontier, current, prevTitle,
 *    prevArtist}) -- both atomically (tmp + rename, see
 *    [StationPersistence.writeAtomic]).
 *  - On construction the engine RESTORES from blocksDir: every block_N.m4a
 *    with a parseable meta json goes back into the registry, so a process
 *    restart resumes from already-rendered blocks instead of cold-rendering
 *    from zero. prevLastTrack (decoded PCM continuity) is NOT persistable, so
 *    the first post-restart render gets prevTrack = null (no crossfade across
 *    a restart -- the renderer already tolerates this, exactly like block 0).
 *    The planner seed DOES survive (prevTitle/prevArtist in state.json).
 *  - Corrupt/missing persistence NEVER crashes: a bad state.json falls back to
 *    a fresh engine (stale files deleted best-effort); a bad meta json drops
 *    just that block.
 *
 * @param songsPerBlock songs to plan for a given block INDEX. Per-index so the
 *   very first block can be smaller (faster first tune-in) than steady-state.
 */
class StationEngine(
    private val nextSongs: suspend (n: Int, seed: Song?) -> List<Song>,
    private val renderBlock: suspend (songs: List<Song>, index: Int, prevTrack: LoadedTrack?) -> BlockResult,
    private val songsPerBlock: (index: Int) -> Int = { 3 },
    private val bufferAhead: Int = 2,
    private val keepBehind: Int = 2,
    private val blocksDir: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    // RENDER WATCHDOG knobs (defaults are production values; unit tests rely on
    // runTest VIRTUAL time, so the defaults stay instant in tests): one render
    // attempt is bounded by [renderTimeoutMs]; a failed/hung attempt retries
    // after each backoff in [renderRetryDelaysMs]; after TOTAL failure the run
    // loop keeps retrying every [failureRetryDelayMs] forever (self-recovery
    // when the network returns).
    private val renderTimeoutMs: Long = 8 * 60_000L,
    private val renderRetryDelaysMs: List<Long> = listOf(5_000L, 30_000L, 120_000L),
    private val failureRetryDelayMs: Long = 180_000L,
    // THERMAL COURTESY: breather between back-to-back render-ahead renders once
    // the buffer is already comfortable (applied by the run loop only; urgent
    // getBlockPath callers never pay it).
    private val renderCooldownMs: Long = 15_000L,
) {
    // index -> (meta, path). Prunable. Guarded by stateMutex.
    private val blocks = HashMap<Int, Pair<BlockMeta, String>>()
    private var frontier = 0            // next index to render (monotonic; survives prune)
    private var generation = 0          // bumped on reset(); discards in-flight renders
    private var prevLastTrack: LoadedTrack? = null // continuity: last_track of frontier-1
    private var prevLastSong: Song? = null         // continuity: last song of frontier-1 (planner seed)
    private var current = 0

    private val stateMutex = Mutex()   // Python `_lock`
    private val renderMutex = Mutex()  // Python `_render_lock`

    // Python `_wake` Event -> conflated channel; trySend never suspends and
    // collapses multiple pending wakes into one token.
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)

    private var runJob: Job? = null

    init {
        File(blocksDir).mkdirs() // Python os.makedirs(exist_ok=True)
        restoreFromDisk()        // resume from any blocks already rendered on disk
    }

    /**
     * Restore the registry + state from blocksDir (constructor-time, so no
     * locking needed -- nothing else can observe the engine yet).
     *
     *  - No/corrupt state.json -> fresh engine; stale block files are deleted
     *    best-effort (we cannot trust them without state).
     *  - Otherwise every block_N.m4a with a PARSEABLE block_N.meta.json is
     *    restored; an unusable pair is deleted best-effort.
     *  - current = state.current (>= 0); frontier = the first index >= current
     *    that is NOT restored. This contiguity guard (rather than blindly
     *    trusting state.frontier) guarantees every index in [current, frontier)
     *    is actually servable, so getBlockPath can never hit a hole.
     *  - prevLastSong is rebuilt from the persisted title/artist (the planner
     *    seed only needs those); prevLastTrack stays null (see class doc).
     */
    private fun restoreFromDisk() {
        val stateFile = File(blocksDir, STATE_FILE)
        val stateText = try {
            if (stateFile.exists()) stateFile.readText() else null
        } catch (e: Exception) {
            null
        }
        val state = stateText?.let { StationPersistence.stateFromJson(it) }
        if (state == null) {
            // Missing or corrupt state -> fresh engine. Clear stale files so a
            // later render at index N never collides with an orphaned file.
            deleteBlockFiles()
            return
        }

        val names = try {
            File(blocksDir).list() ?: emptyArray()
        } catch (e: Exception) {
            emptyArray<String>()
        }
        for (f in names) {
            val m = BLOCK_FILE_RE.matchEntire(f) ?: continue
            val idx = m.groupValues[1].toIntOrNull() ?: continue
            val metaFile = File(blocksDir, "block_$idx.meta.json")
            val metaText = try {
                if (metaFile.exists()) metaFile.readText() else null
            } catch (e: Exception) {
                null
            }
            val meta = metaText?.let { StationPersistence.metaFromJson(it) }
            if (meta == null) {
                // unusable block (no/corrupt meta): drop the pair best-effort
                try { File(blocksDir, f).delete() } catch (ignored: Exception) {}
                try { metaFile.delete() } catch (ignored: Exception) {}
                continue
            }
            blocks[idx] = meta to "$blocksDir/block_$idx.m4a"
        }

        current = state.current.coerceAtLeast(0)
        // contiguity guard: frontier = first non-restored index >= current
        var f = current
        while (blocks.containsKey(f)) f += 1
        frontier = f
        prevLastSong = state.prevTitle?.let {
            Song(title = it, artist = state.prevArtist ?: "")
        }
        prevLastTrack = null // PCM continuity cannot survive a restart
    }

    /** Persist {frontier, current, prev song} -- MUST be called holding [stateMutex]. */
    private fun saveStateLocked() {
        try {
            StationPersistence.writeAtomic(
                File(blocksDir, STATE_FILE),
                StationPersistence.stateToJson(
                    StationPersistence.EngineState(
                        frontier = frontier,
                        current = current,
                        prevTitle = prevLastSong?.title,
                        prevArtist = prevLastSong?.artist,
                    ),
                ),
            )
        } catch (e: Exception) {
            // persistence is best-effort; never fail playback over it
        }
    }

    /**
     * Python `_ensure_through`. Render strictly in order until frontier > target.
     * Renders are serialized by [renderMutex] (no double-render: the frontier is
     * re-snapshotted under the lock each iteration), but the lock is held PER
     * BLOCK, not across the whole sweep. [Mutex] is fair (FIFO), so a waiter
     * with a small target -- the player feed asking for block 0 during the cold
     * start -- acquires right after the current block commits instead of
     * stalling behind the run loop's full buffer-ahead sweep. This is what lets
     * playback begin the moment block 0 exists (~1 song render) rather than
     * after bufferAhead+1 blocks.
     *
     * The frontier/generation are snapshotted under [stateMutex]; the render
     * runs WITHOUT the state mutex; results are committed under [stateMutex]
     * only if the generation still matches (else the render was made stale by a
     * [reset] and is discarded -- the loop then re-snapshots the post-reset
     * frontier). Every commit also persists the block's meta json + state.json.
     */
    suspend fun ensureThrough(target: Int, cooldownMs: Long = 0L) {
        while (true) {
            var cooldownEligible = false
            val done = renderMutex.withLock {
                val i: Int
                val gen: Int
                val seed: Song?
                val prevTrack: LoadedTrack?
                stateMutex.withLock {
                    i = frontier
                    gen = generation
                    seed = prevLastSong
                    prevTrack = prevLastTrack
                }
                if (i > target) return@withLock true

                val songs = nextSongs(songsPerBlock(i), seed)
                // RENDER WATCHDOG: per-attempt timeout + retry-with-backoff.
                // null = a reset() landed during a backoff (this snapshot is
                // stale): skip the render and re-snapshot on the next loop.
                val res = renderWithWatchdog(songs, i, prevTrack, gen)
                    ?: return@withLock false

                stateMutex.withLock {
                    if (gen != generation) {
                        // reset() happened mid-render -> discard this stale block
                        return@withLock
                    }
                    blocks[i] = res.meta to res.path
                    prevLastTrack = res.lastTrack
                    prevLastSong = songs.lastOrNull()
                    frontier = i + 1
                    // THERMAL COURTESY eligibility: more blocks remain to render
                    // AND the buffer already holds >= 1 fully-rendered block
                    // beyond the playing one, so a breather cannot stall
                    // playback (and an urgent getBlockPath caller acquires the
                    // render mutex itself during the pause -- it never waits).
                    cooldownEligible = frontier <= target && frontier > current + 1
                    try {
                        StationPersistence.writeAtomic(
                            File(blocksDir, "block_$i.meta.json"),
                            StationPersistence.metaToJson(res.meta),
                        )
                    } catch (e: Exception) {
                        // best-effort: a failed meta write only costs a restore
                    }
                    saveStateLocked()
                }
                false
            }
            // MEMORY HYGIENE (2026-06-14): the just-rendered block's mix timeline
            // (a multi-MB FloatArray) is now garbage; ART tends to grow the heap
            // FOOTPRINT to the cap rather than collect it, so across a long session
            // the next block's decode eventually finds no room (the emulator's
            // 512MB heap OOM'd this way). Nudge a collection at the block boundary.
            // Cheap: blocks are minutes apart, so the brief GC is inaudible, and it
            // keeps the steady-state footprint lower on every device.
            if (!done) System.gc()
            if (done) break
            if (cooldownMs > 0 && cooldownEligible) delay(cooldownMs)
        }
    }

    /**
     * RENDER WATCHDOG: run ONE block render with a generous per-attempt timeout
     * (a hung network call must never wedge the station) and retry-with-backoff
     * on failure. Returns null when a [reset] landed during a backoff (the
     * snapshot is stale; the caller re-snapshots); throws the LAST error only
     * after every attempt failed -- the run loop then keeps retrying forever
     * every [failureRetryDelayMs] while already-rendered blocks stay playable.
     */
    private suspend fun renderWithWatchdog(
        songs: List<Song>,
        index: Int,
        prevTrack: LoadedTrack?,
        gen: Int,
    ): BlockResult? {
        val attempts = renderRetryDelaysMs.size + 1
        var lastErr: Throwable? = null
        for (attempt in 1..attempts) {
            try {
                return withTimeout(renderTimeoutMs) { renderBlock(songs, index, prevTrack) }
            } catch (e: TimeoutCancellationException) {
                lastErr = e
                println("[station] render of block $index TIMED OUT after ${renderTimeoutMs}ms (attempt $attempt/$attempts)")
            } catch (e: CancellationException) {
                throw e // engine stopping: never swallow real cancellation
            } catch (e: Exception) {
                lastErr = e
                println("[station] render of block $index failed (attempt $attempt/$attempts): $e")
            }
            if (attempt < attempts) {
                delay(renderRetryDelaysMs[attempt - 1])
                // a reset() during the backoff makes this render stale: abort
                // quietly so the caller re-snapshots the fresh generation.
                stateMutex.withLock { if (gen != generation) return null }
            }
        }
        throw lastErr ?: IllegalStateException("render of block $index failed")
    }

    /** Python `get_block_meta`. */
    suspend fun getBlockMeta(n: Int): BlockMeta? =
        stateMutex.withLock { blocks[n]?.first }

    /** Python `get_block_path`: ensure the block exists, then return its path. */
    suspend fun getBlockPath(n: Int): String {
        ensureThrough(n)
        return stateMutex.withLock { blocks.getValue(n).second }
    }

    /**
     * Where playback should START feeding from: the lowest ready (restored or
     * already-rendered) block index at/after [current] if any, else the
     * frontier. On a warm relaunch this points at the restored blocks so the
     * service can enqueue them instantly instead of waiting on a cold render.
     */
    suspend fun firstPlayableIndex(): Int = stateMutex.withLock {
        blocks.keys.filter { it >= current }.minOrNull() ?: frontier
    }
    /** Python `advance`: monotonically bump current, wake the loop, prune. */
    suspend fun advance(n: Int) {
        stateMutex.withLock {
            if (n > current) {
                current = n
                saveStateLocked() // current changed -> persist the new resume point
            }
        }
        wakeChannel.trySend(Unit)
        prune()
    }

    /**
     * Python `reset`: restart from a fresh, reshuffled queue at block 0. Bumps
     * [generation] so any in-flight render is discarded, clears the registry +
     * continuity, and deletes old block files (including every meta json and
     * state.json, so a later process start does NOT restore pre-reset state).
     * Wakes the loop.
     */
    suspend fun reset() {
        stateMutex.withLock {
            generation += 1
            blocks.clear()
            frontier = 0
            current = 0
            prevLastTrack = null
            prevLastSong = null
        }
        deleteBlockFiles()
        wakeChannel.trySend(Unit)
    }

    /**
     * FAST MOOD SWITCH (2026-06-13): partially invalidate the rendered buffer
     * so the un-played blocks at/after [fromIndex] are re-rendered (with the
     * new mood) WITHOUT a full [reset]. Unlike [reset] this keeps [current] and
     * the already-PLAYING block intact - it only discards blocks the listener
     * has not reached yet, so a manual mood change is heard within roughly one
     * block instead of after the whole pre-rendered buffer drains.
     *
     *  - Bumps [generation] so any in-flight render of an invalidated index is
     *    discarded on commit (same staleness guard as [reset]).
     *  - Drops registry entries + block/meta files for indices >= [fromIndex].
     *  - Rewinds [frontier] to [fromIndex] (only if it was further ahead) so the
     *    run loop re-renders from there.
     *  - PCM crossfade continuity into [fromIndex] cannot be rebuilt cheaply, so
     *    [prevLastTrack] is cleared (the re-rendered block opens like a warm
     *    restart - the renderer already tolerates a null prevTrack). The planner
     *    seed [prevLastSong] is rebuilt from the retained block_(fromIndex-1)
     *    meta's last segment when available, so no-repeat history is preserved.
     *  - [fromIndex] <= [current] is clamped to current+1: the playing block is
     *    NEVER invalidated (that would interrupt playback). Wakes the loop.
     */
    suspend fun invalidateFrom(fromIndex: Int) {
        val toDelete: List<Int>
        stateMutex.withLock {
            val from = fromIndex.coerceAtLeast(current + 1)
            if (from >= frontier) {
                // nothing rendered ahead yet: only ensure future renders pick up
                // the new state (generation bump) so an in-flight render of
                // >= from is discarded; no files to drop.
                generation += 1
                wakeChannel.trySend(Unit)
                return
            }
            generation += 1
            toDelete = blocks.keys.filter { it >= from }.toList()
            for (k in toDelete) blocks.remove(k)
            // rebuild the planner seed from the last RETAINED block before
            // `from` (its last segment), so no-repeat history survives.
            val prevMeta = blocks[from - 1]?.first
            val lastSeg = prevMeta?.segments?.lastOrNull()
            prevLastSong = lastSeg?.let { Song(title = it.title, artist = it.artist) }
            prevLastTrack = null // PCM continuity cannot be rebuilt cheaply
            frontier = from
            saveStateLocked()
        }
        for (i in toDelete) {
            try {
                val f = File("$blocksDir/block_$i.m4a")
                if (f.exists()) f.delete()
            } catch (e: Exception) {
                // tolerate IO errors
            }
            try {
                val mf = File("$blocksDir/block_$i.meta.json")
                if (mf.exists()) mf.delete()
            } catch (e: Exception) {
                // tolerate IO errors
            }
        }
        wakeChannel.trySend(Unit)
    }

    /**
     * Python `_delete_block_files`: remove every block_*.m4a in blocksDir --
     * plus (Android persistence) every block_*.meta.json and state.json (and
     * their .tmp leftovers). Anything else in the dir is left alone.
     */
    private fun deleteBlockFiles() {
        val names = try {
            File(blocksDir).list() ?: return
        } catch (e: Exception) {
            return
        }
        for (f in names) {
            val isBlockMedia = f.startsWith("block_") && f.endsWith(".m4a")
            val isBlockMeta = f.startsWith("block_") &&
                (f.endsWith(".meta.json") || f.endsWith(".meta.json.tmp"))
            val isState = f == STATE_FILE || f == "$STATE_FILE.tmp"
            if (isBlockMedia || isBlockMeta || isState) {
                try {
                    File(blocksDir, f).delete()
                } catch (e: Exception) {
                    // tolerate IO errors (Python except OSError: pass)
                }
            }
        }
    }

    /** Python `_prune`: delete files (.m4a + .meta.json) + registry entries
     *  older than current-keepBehind. */
    suspend fun prune() {
        val stale: List<Int>
        val low: Int
        stateMutex.withLock {
            low = current - keepBehind
            stale = blocks.keys.filter { it < low }
        }
        for (i in stale) {
            try {
                val file = File("$blocksDir/block_$i.m4a")
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                // tolerate IO errors
            }
            try {
                val metaFile = File("$blocksDir/block_$i.meta.json")
                if (metaFile.exists()) metaFile.delete()
            } catch (e: Exception) {
                // tolerate IO errors
            }
            stateMutex.withLock { blocks.remove(i) }
        }
    }

    /** Python `start`: launch the background render loop (idempotent). */
    fun start() {
        if (runJob != null) return
        runJob = scope.launch { run() }
    }

    /** Python `stop`: stop the loop. Cancels the launched job + wakes it. */
    fun stop() {
        wakeChannel.trySend(Unit)
        runJob?.cancel()
        runJob = null
    }

    /**
     * Python `_run`: keep blocks ahead of current ready. Each pass renders
     * through current+bufferAhead, prunes, then waits for a wake or a 2s timeout
     * (whichever first). Render errors are caught + logged, never fatal.
     */
    private suspend fun run() {
        while (scope.isActive) {
            val target = stateMutex.withLock { current + bufferAhead }
            var failed = false
            try {
                ensureThrough(target, renderCooldownMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                println(
                    "[station] render error: $e -- retrying in ${failureRetryDelayMs}ms " +
                        "(station self-recovers when the network returns)",
                )
            }
            prune()
            // SELF-RECOVERY: after a TOTAL render failure (watchdog exhausted)
            // keep retrying forever on a slow cadence; a wake (advance/reset)
            // still triggers an immediate pass. Already-rendered blocks stay in
            // the registry and remain playable meanwhile.
            withTimeoutOrNull(if (failed) failureRetryDelayMs else 2000) { wakeChannel.receive() }
        }
    }

    // ----------------------------------------------------------------------
    // Test-only state accessors. These read the same fields under [stateMutex]
    // so they observe a consistent snapshot; they exist solely so the
    // deterministic unit tests can assert frontier/generation/current without
    // exercising the live run() loop. Not part of the production surface.
    // ----------------------------------------------------------------------
    internal suspend fun frontierForTest(): Int = stateMutex.withLock { frontier }
    internal suspend fun generationForTest(): Int = stateMutex.withLock { generation }
    internal suspend fun currentForTest(): Int = stateMutex.withLock { current }

    companion object {
        private const val STATE_FILE = "state.json"
        private val BLOCK_FILE_RE = Regex("""block_(\d+)\.m4a""")
    }
}