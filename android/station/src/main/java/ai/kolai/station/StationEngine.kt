package ai.kolai.station

import ai.kolai.core.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 */
class StationEngine(
    private val nextSongs: suspend (n: Int, seed: Song?) -> List<Song>,
    private val renderBlock: suspend (songs: List<Song>, index: Int, prevTrack: LoadedTrack?) -> BlockResult,
    private val songsPerBlock: Int = 3,
    private val bufferAhead: Int = 2,
    private val keepBehind: Int = 2,
    private val blocksDir: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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
    }

    /**
     * Python `_ensure_through`. Render strictly in order until frontier > target.
     * Serialized by [renderMutex] so concurrent callers don't double-render. The
     * frontier/generation are snapshotted under [stateMutex]; the render runs
     * WITHOUT the state mutex; results are committed under [stateMutex] only if
     * the generation still matches (else the render was made stale by a [reset]
     * and is discarded -- the loop then re-snapshots the post-reset frontier).
     */
    suspend fun ensureThrough(target: Int) {
        renderMutex.withLock {
            while (true) {
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
                if (i > target) break

                val songs = nextSongs(songsPerBlock, seed)
                val res = renderBlock(songs, i, prevTrack)

                stateMutex.withLock {
                    if (gen != generation) {
                        // reset() happened mid-render -> discard this stale block
                        return@withLock
                    }
                    blocks[i] = res.meta to res.path
                    prevLastTrack = res.lastTrack
                    prevLastSong = songs.lastOrNull()
                    frontier = i + 1
                }
            }
        }
    }

    /** Python `get_block_meta`. */
    suspend fun getBlockMeta(n: Int): BlockMeta? =
        stateMutex.withLock { blocks[n]?.first }

    /** Python `get_block_path`: ensure the block exists, then return its path. */
    suspend fun getBlockPath(n: Int): String {
        ensureThrough(n)
        return stateMutex.withLock { blocks.getValue(n).second }
    }

    /** Python `advance`: monotonically bump current, wake the loop, prune. */
    suspend fun advance(n: Int) {
        stateMutex.withLock {
            if (n > current) current = n
        }
        wakeChannel.trySend(Unit)
        prune()
    }

    /**
     * Python `reset`: restart from a fresh, reshuffled queue at block 0. Bumps
     * [generation] so any in-flight render is discarded, clears the registry +
     * continuity, and deletes old block files. Wakes the loop.
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

    /** Python `_delete_block_files`: remove every block_*.m4a in blocksDir. */
    private fun deleteBlockFiles() {
        val names = try {
            File(blocksDir).list() ?: return
        } catch (e: Exception) {
            return
        }
        for (f in names) {
            if (f.startsWith("block_") && f.endsWith(".m4a")) {
                try {
                    File(blocksDir, f).delete()
                } catch (e: Exception) {
                    // tolerate IO errors (Python except OSError: pass)
                }
            }
        }
    }

    /** Python `_prune`: delete files + registry entries older than current-keepBehind. */
    suspend fun prune() {
        val stale: List<Int>
        val low: Int
        stateMutex.withLock {
            low = current - keepBehind
            stale = blocks.keys.filter { it < low }
        }
        for (i in stale) {
            val path = "$blocksDir/block_$i.m4a"
            try {
                val file = File(path)
                if (file.exists()) file.delete()
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
            try {
                ensureThrough(target)
            } catch (e: Exception) {
                println("[station] render error: $e")
            }
            prune()
            withTimeoutOrNull(2000) { wakeChannel.receive() }
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
}