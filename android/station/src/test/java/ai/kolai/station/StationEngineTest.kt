package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.TrackAnalysis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * TDD for Task 5.4 (StationEngine), ported 1:1 from
 * backend/radioai/station.py class StationEngine -- plus the Android-only disk
 * persistence layer (block_N.meta.json + state.json restore across engine
 * instances; see the "DISK PERSISTENCE" tests at the bottom).
 *
 * No real planner/renderer/audio: both seams are faked. The engine is
 * constructed with function-typed seams (nextSongs / renderBlock) so the
 * production code can pass rollingPlanner::nextSongs and blockRenderer::render
 * unchanged. Fakes are deterministic and RECORD the (index, prevTrack) they were
 * called with so continuity threading can be asserted. blocksDir is a real temp
 * dir; the fake render actually writes the block_<index>.m4a file so prune/delete
 * can be asserted on the filesystem.
 */
@OptIn(ExperimentalCoroutinesApi::class) // TestScope.currentTime (virtual clock)
class StationEngineTest {

    // ---- helpers ---------------------------------------------------------

    private fun song(name: String): Song = Song(title = name, artist = "artist-$name")

    private fun analysis(): TrackAnalysis = TrackAnalysis(
        path = "/fake.m4a",
        durationS = 10.0,
        bpm = 120.0,
        beatTimes = listOf(0.0, 0.5),
        keyCamelot = "8A",
        energy = 0.5,
        introEndS = 1.0,
        outroStartS = 9.0,
        vocalOnsetS = 1.0,
    )

    private fun loadedTrack(s: Song, path: String): LoadedTrack =
        LoadedTrack(song = s, analysis = analysis(), audio = FloatArray(0), path = path)

    /** Records each render call's (index, prevTrack); writes the .m4a file.
     *  The meta carries a real segment + talk entry so the persistence tests
     *  can assert a FULL BlockMeta round-trip through the meta json. */
    private class FakeRender(private val blocksDir: String) {
        val callIndices = mutableListOf<Int>()
        val callPrevTracks = mutableListOf<LoadedTrack?>()
        val callSeedSongs = mutableListOf<Song?>()
        var beforeStore: (suspend () -> Unit)? = null

        suspend fun render(songs: List<Song>, index: Int, prevTrack: LoadedTrack?): BlockResult {
            callIndices.add(index)
            callPrevTracks.add(prevTrack)
            callSeedSongs.add(prevTrack?.song)
            beforeStore?.invoke()
            val path = "$blocksDir/block_$index.m4a"
            File(path).writeText("block-$index")
            val last = songs.last()
            val meta = metaFor(index)
            val lastTrack = LoadedTrack(song = last, analysis = TrackAnalysis(
                path = path, durationS = 10.0, bpm = 120.0, beatTimes = listOf(0.0),
                keyCamelot = "8A", energy = 0.5, introEndS = 1.0, outroStartS = 9.0, vocalOnsetS = 1.0,
            ), audio = FloatArray(0), path = path)
            return BlockResult(audio = FloatArray(0), meta = meta, path = path, lastTrack = lastTrack)
        }

        companion object {
            /** Deterministic meta for [index] (same shape every FakeRender). */
            fun metaFor(index: Int): BlockMeta = BlockMeta(
                index = index,
                durationS = 10.0,
                segments = listOf(
                    Segment(index = 0, title = "t$index", artist = "a$index", startS = 0.0, endS = 10.0),
                ),
                talk = listOf(
                    TalkEntry(beat = "intro", text = "talk-$index", startS = 0.5, endS = 1.5),
                ),
            )
        }
    }

    /**
     * Deterministic planner seam. Each call returns n fresh songs named by a
     * global counter so blocks have distinct last-songs; records the seed it
     * was handed.
     */
    private class FakeNextSongs(private val perBlock: Int) {
        val seeds = mutableListOf<Song?>()
        private var counter = 0
        fun nextSongs(n: Int, seed: Song?): List<Song> {
            seeds.add(seed)
            return (0 until n).map { Song(title = "s${counter++}", artist = "a") }
        }
    }

    private fun newTempDir(): String =
        Files.createTempDirectory("station-test").toFile().absolutePath

    private fun blockFile(dir: String, i: Int): File = File("$dir/block_$i.m4a")
    private fun metaFile(dir: String, i: Int): File = File("$dir/block_$i.meta.json")
    private fun stateFile(dir: String): File = File("$dir/state.json")

    private fun newEngine(
        planner: FakeNextSongs,
        render: FakeRender,
        dir: String,
    ): StationEngine = StationEngine(
        nextSongs = planner::nextSongs,
        renderBlock = render::render,
        songsPerBlock = { 3 },
        bufferAhead = 2,
        keepBehind = 2,
        blocksDir = dir,
    )

    // ---- ensureThrough: ordered render + continuity ----------------------

    @Test
    fun ensureThrough_rendersBlocksInOrder_threadsContinuity() = runTest {
        val dir = newTempDir()
        val planner = FakeNextSongs(3)
        val render = FakeRender(dir)
        val engine = newEngine(planner, render, dir)

        engine.ensureThrough(2)

        // rendered 0,1,2 strictly in order
        assertEquals(listOf(0, 1, 2), render.callIndices)
        assertEquals(3, engine.frontierForTest())

        // each block stored with the right path
        assertEquals("$dir/block_0.m4a", engine.getBlockMeta(0)?.let { "$dir/block_${it.index}.m4a" })
        for (i in 0..2) {
            assertTrue("block $i file exists", blockFile(dir, i).exists())
            assertEquals(i, engine.getBlockMeta(i)?.index)
        }

        // continuity: block 0's prevTrack is null; blocks 1,2 receive the
        // PREVIOUS block's lastTrack (its path is block_{i-1}.m4a).
        assertNull(render.callPrevTracks[0])
        assertEquals("$dir/block_0.m4a", render.callPrevTracks[1]?.path)
        assertEquals("$dir/block_1.m4a", render.callPrevTracks[2]?.path)

        // the seed handed to nextSongs equals the previous block's last song.
        // call 0 seed null; call i seed == lastTrack.song of block i-1.
        assertNull(planner.seeds[0])
        assertEquals(render.callPrevTracks[1]?.song, planner.seeds[1])
        assertEquals(render.callPrevTracks[2]?.song, planner.seeds[2])
    }

    // ---- per-index songsPerBlock ------------------------------------------

    @Test
    fun songsPerBlock_isCalledPerIndex_firstBlockCanBeSmaller() = runTest {
        val dir = newTempDir()
        val render = FakeRender(dir)
        val askedCounts = mutableListOf<Int>()
        var counter = 0
        val engine = StationEngine(
            nextSongs = { n, _ ->
                askedCounts.add(n)
                (0 until n).map { Song(title = "s${counter++}", artist = "a") }
            },
            renderBlock = render::render,
            songsPerBlock = { idx -> if (idx == 0) 1 else 2 },
            bufferAhead = 2,
            keepBehind = 2,
            blocksDir = dir,
        )

        engine.ensureThrough(2)

        // block 0 planned with ONE song (fast first tune-in), later blocks with 2
        assertEquals(listOf(1, 2, 2), askedCounts)
        assertEquals(listOf(0, 1, 2), render.callIndices)
    }

    // ---- stale-generation discard ---------------------------------------

    @Test
    fun staleGeneration_renderDuringReset_isDiscarded() = runTest {
        val dir = newTempDir()
        val planner = FakeNextSongs(3)
        val render = FakeRender(dir)
        lateinit var engine: StationEngine
        var resetOnce = false

        render.beforeStore = {
            if (!resetOnce) {
                resetOnce = true
                // simulate a reset() landing mid-render of block 0
                engine.reset()
            }
        }
        engine = newEngine(planner, render, dir)

        engine.ensureThrough(0)

        // the first render's result was for generation 0 but reset() bumped to
        // generation 1 mid-render -> the stale block must NOT be stored.
        // ensureThrough re-loops post-reset and renders block 0 again (gen 1),
        // which IS stored. So: two render calls, frontier ends at 1, block 0
        // present, and only block 0 (no leftover higher index).
        assertEquals(listOf(0, 0), render.callIndices)
        assertEquals(1, engine.frontierForTest())
        assertEquals(0, engine.getBlockMeta(0)?.index)
        assertNull(engine.getBlockMeta(1))
    }

    // ---- advance + prune -------------------------------------------------

    @Test
    fun advance_onlyMovesForward_andPrunesOldBlocks() = runTest {
        val dir = newTempDir()
        val planner = FakeNextSongs(3)
        val render = FakeRender(dir)
        val engine = newEngine(planner, render, dir)

        // render blocks 0..5 so there is history to prune
        engine.ensureThrough(5)
        for (i in 0..5) assertTrue(blockFile(dir, i).exists())

        // advance to 5 -> prune everything < current - keepBehind = 5 - 2 = 3
        engine.advance(5)

        // pruned: 0,1,2 gone (files + map). kept: 3,4,5
        for (i in 0..2) {
            assertFalse("block $i file pruned", blockFile(dir, i).exists())
            assertNull("block $i meta pruned", engine.getBlockMeta(i))
        }
        for (i in 3..5) {
            assertTrue("block $i file kept", blockFile(dir, i).exists())
            assertEquals(i, engine.getBlockMeta(i)?.index)
        }

        // advance backwards must NOT move current (and must not re-prune kept)
        engine.advance(2)
        assertEquals(5, engine.currentForTest())
        for (i in 3..5) assertTrue(blockFile(dir, i).exists())
    }
    // ---- reset -----------------------------------------------------------

    @Test
    fun reset_bumpsGeneration_clearsState_deletesAllBlockFiles() = runTest {
        val dir = newTempDir()
        val planner = FakeNextSongs(3)
        val render = FakeRender(dir)
        val engine = newEngine(planner, render, dir)

        engine.ensureThrough(2)
        engine.advance(2)
        val genBefore = engine.generationForTest()
        for (i in 0..2) assertTrue(blockFile(dir, i).exists())

        engine.reset()

        assertEquals(genBefore + 1, engine.generationForTest())
        assertEquals(0, engine.frontierForTest())
        assertEquals(0, engine.currentForTest())
        for (i in 0..2) {
            assertFalse("block $i file deleted by reset", blockFile(dir, i).exists())
            assertNull(engine.getBlockMeta(i))
        }
        // after reset, continuity is fresh: next render gets null prevTrack/seed
        engine.ensureThrough(0)
        assertNull(render.callPrevTracks.last())
        assertNull(planner.seeds.last())
    }

    // ---- getBlockPath triggers ensureThrough -----------------------------

    @Test
    fun getBlockPath_triggersEnsureThrough_returnsStoredPath() = runTest {
        val dir = newTempDir()
        val planner = FakeNextSongs(3)
        val render = FakeRender(dir)
        val engine = newEngine(planner, render, dir)

        // nothing rendered yet; getBlockPath(2) must render 0..2 then return path
        val path = engine.getBlockPath(2)
        assertEquals("$dir/block_2.m4a", path)
        assertEquals(listOf(0, 1, 2), render.callIndices)
        assertTrue(blockFile(dir, 2).exists())
    }

    // ---- deleteBlockFiles only touches block_*.m4a -----------------------

    @Test
    fun deleteBlockFiles_onlyRemovesBlockM4a_leavesOtherFiles() = runTest {
        val dir = newTempDir()
        val planner = FakeNextSongs(3)
        val render = FakeRender(dir)
        val engine = newEngine(planner, render, dir)
        // a non-block file and a block_*.mp3 (wrong ext) must survive reset
        File("$dir/keep.txt").writeText("x")
        File("$dir/block_9.mp3").writeText("x")

        engine.ensureThrough(1)
        engine.reset()

        assertTrue(File("$dir/keep.txt").exists())
        assertTrue(File("$dir/block_9.mp3").exists())
        assertFalse(blockFile(dir, 0).exists())
        assertFalse(blockFile(dir, 1).exists())
    }

    // ======================================================================
    // DISK PERSISTENCE (Android addition): meta jsons + state.json + restore
    // ======================================================================

    @Test
    fun persistence_writesMetaJsonAndStateJson_alongsideEveryBlock() = runTest {
        val dir = newTempDir()
        val engine = newEngine(FakeNextSongs(3), FakeRender(dir), dir)

        engine.ensureThrough(2)

        for (i in 0..2) assertTrue("meta json $i written", metaFile(dir, i).exists())
        assertTrue("state.json written", stateFile(dir).exists())
    }

    @Test
    fun persistence_secondEngineRestoresBlocks_withoutReRendering() = runTest {
        val dir = newTempDir()
        val engine1 = newEngine(FakeNextSongs(3), FakeRender(dir), dir)
        engine1.ensureThrough(2)

        // a SECOND engine over the same blocksDir restores the registry
        val planner2 = FakeNextSongs(3)
        val render2 = FakeRender(dir)
        val engine2 = newEngine(planner2, render2, dir)

        assertEquals(3, engine2.frontierForTest())
        assertEquals(0, engine2.currentForTest())

        // serving a restored block does NOT render anything
        val path = engine2.getBlockPath(2)
        assertEquals("$dir/block_2.m4a", path)
        assertTrue(render2.callIndices.isEmpty())

        // the meta survived the json round-trip FULLY (segments + talk)
        assertEquals(FakeRender.metaFor(2), engine2.getBlockMeta(2))
        assertEquals(FakeRender.metaFor(0), engine2.getBlockMeta(0))
    }

    @Test
    fun persistence_restoredEngineContinuesAtFrontier_withPersistedSeed_nullPrevTrack() = runTest {
        val dir = newTempDir()
        val engine1 = newEngine(FakeNextSongs(3), FakeRender(dir), dir)
        engine1.ensureThrough(2) // songs s0..s8; last song of block 2 is s8

        val planner2 = FakeNextSongs(3)
        val render2 = FakeRender(dir)
        val engine2 = newEngine(planner2, render2, dir)

        engine2.ensureThrough(3) // only block 3 is missing

        assertEquals(listOf(3), render2.callIndices)
        // PCM continuity cannot survive a restart -> null prevTrack (renderer
        // tolerates it, like block 0) ...
        assertNull(render2.callPrevTracks[0])
        // ... but the PLANNER seed does survive via state.json title/artist.
        assertEquals(Song(title = "s8", artist = "a"), planner2.seeds[0])
    }

    @Test
    fun firstPlayableIndex_freshEngine_returnsFrontierZero() = runTest {
        val dir = newTempDir()
        val engine = newEngine(FakeNextSongs(3), FakeRender(dir), dir)
        assertEquals(0, engine.firstPlayableIndex())
    }

    @Test
    fun firstPlayableIndex_afterRestore_returnsLowestRestoredAtOrAfterCurrent() = runTest {
        val dir = newTempDir()
        val engine1 = newEngine(FakeNextSongs(3), FakeRender(dir), dir)
        engine1.ensureThrough(5)
        engine1.advance(4) // current=4; prune < 2 -> blocks 2..5 remain on disk

        val engine2 = newEngine(FakeNextSongs(3), FakeRender(dir), dir)

        assertEquals(4, engine2.currentForTest())
        assertEquals(6, engine2.frontierForTest())
        // restored blocks 2..5; lowest at/after current(4) -> 4
        assertEquals(4, engine2.firstPlayableIndex())
        // the kept-behind blocks below current are restored too (prev-block nav)
        assertEquals(2, engine2.getBlockMeta(2)?.index)
    }

    @Test
    fun reset_wipesPersistence_nextEngineStartsFresh() = runTest {
        val dir = newTempDir()
        val engine1 = newEngine(FakeNextSongs(3), FakeRender(dir), dir)
        engine1.ensureThrough(1)
        assertTrue(stateFile(dir).exists())

        engine1.reset()

        assertFalse("state.json wiped by reset", stateFile(dir).exists())
        for (i in 0..1) {
            assertFalse("block $i m4a wiped", blockFile(dir, i).exists())
            assertFalse("block $i meta wiped", metaFile(dir, i).exists())
        }

        val render2 = FakeRender(dir)
        val engine2 = newEngine(FakeNextSongs(3), render2, dir)
        assertEquals(0, engine2.frontierForTest())
        assertEquals(0, engine2.firstPlayableIndex())
        assertNull(engine2.getBlockMeta(0))
    }

    @Test
    fun prune_alsoDeletesMetaJsons() = runTest {
        val dir = newTempDir()
        val engine = newEngine(FakeNextSongs(3), FakeRender(dir), dir)
        engine.ensureThrough(5)
        for (i in 0..5) assertTrue(metaFile(dir, i).exists())

        engine.advance(5) // prune < 3

        for (i in 0..2) assertFalse("meta $i pruned", metaFile(dir, i).exists())
        for (i in 3..5) assertTrue("meta $i kept", metaFile(dir, i).exists())
    }

    @Test
    fun corruptStateJson_fallsBackToFreshEngine_withoutCrashing() = runTest {
        val dir = newTempDir()
        val engine1 = newEngine(FakeNextSongs(3), FakeRender(dir), dir)
        engine1.ensureThrough(1)
        stateFile(dir).writeText("{this is not json!!")

        val engine2 = newEngine(FakeNextSongs(3), FakeRender(dir), dir)

        assertEquals(0, engine2.frontierForTest())
        assertEquals(0, engine2.currentForTest())
        assertEquals(0, engine2.firstPlayableIndex())
        assertNull(engine2.getBlockMeta(0))
        // stale files were cleared best-effort so re-renders never collide
        assertFalse(blockFile(dir, 0).exists())
        assertFalse(metaFile(dir, 0).exists())
    }

    @Test
    fun corruptMetaJson_dropsOnlyThatBlock_onRestore() = runTest {
        val dir = newTempDir()
        val engine1 = newEngine(FakeNextSongs(3), FakeRender(dir), dir)
        engine1.ensureThrough(2)
        metaFile(dir, 1).writeText("garbage")

        val engine2 = newEngine(FakeNextSongs(3), FakeRender(dir), dir)

        // block 0 restored; block 1 unusable -> the contiguity guard stops the
        // frontier at the hole so block 1 will simply be re-rendered.
        assertEquals(0, engine2.getBlockMeta(0)?.index)
        assertNull(engine2.getBlockMeta(1))
        assertEquals(1, engine2.frontierForTest())
        assertEquals(0, engine2.firstPlayableIndex())
        // the unusable pair was deleted best-effort
        assertFalse(blockFile(dir, 1).exists())
        assertFalse(metaFile(dir, 1).exists())
    }

    // ======================================================================
    // RENDER WATCHDOG + SELF-RECOVERY + THERMAL COURTESY
    // All delays/timeouts run on runTest VIRTUAL time -> tests are instant,
    // and currentTime PROVES the backoff/cooldown was actually applied.
    // ======================================================================

    @Test
    fun watchdog_retriesFailingRender_withBackoff_thenCommits() = runTest {
        val dir = newTempDir()
        val inner = FakeRender(dir)
        var failuresLeft = 2
        var calls = 0
        val engine = StationEngine(
            nextSongs = FakeNextSongs(3)::nextSongs,
            renderBlock = { songs, index, prev ->
                calls++
                if (failuresLeft > 0) {
                    failuresLeft--
                    throw RuntimeException("network died")
                }
                inner.render(songs, index, prev)
            },
            songsPerBlock = { 3 },
            bufferAhead = 2,
            keepBehind = 2,
            blocksDir = dir,
        )

        engine.ensureThrough(0)

        // initial attempt + 2 retries, then the block commits normally
        assertEquals(3, calls)
        assertEquals(1, engine.frontierForTest())
        assertEquals(0, engine.getBlockMeta(0)?.index)
        assertTrue(blockFile(dir, 0).exists())
        // backoff actually applied between attempts: 5s then 30s (virtual time)
        assertEquals(35_000L, currentTime)
    }

    @Test
    fun watchdog_timesOutHungRender_thenRetrySucceeds() = runTest {
        val dir = newTempDir()
        val inner = FakeRender(dir)
        var calls = 0
        val engine = StationEngine(
            nextSongs = FakeNextSongs(3)::nextSongs,
            renderBlock = { songs, index, prev ->
                calls++
                if (calls == 1) delay(10 * 60 * 60_000L) // hung render (network died mid-fetch)
                inner.render(songs, index, prev)
            },
            songsPerBlock = { 3 },
            bufferAhead = 2,
            keepBehind = 2,
            blocksDir = dir,
        )

        engine.ensureThrough(0)

        // the hung attempt was killed by the per-attempt timeout, then retried
        assertEquals(2, calls)
        assertEquals(1, engine.frontierForTest())
        assertEquals(0, engine.getBlockMeta(0)?.index)
        // 8min render timeout + 5s first backoff (virtual)
        assertEquals(8 * 60_000L + 5_000L, currentTime)
    }

    @Test
    fun watchdog_totalFailure_throws_keepsRenderedBlocks_thenRecovers() = runTest {
        val dir = newTempDir()
        val inner = FakeRender(dir)
        var networkDown = false
        var block1Attempts = 0
        val engine = StationEngine(
            nextSongs = FakeNextSongs(3)::nextSongs,
            renderBlock = { songs, index, prev ->
                if (index == 1 && networkDown) {
                    block1Attempts++
                    throw RuntimeException("network died")
                }
                inner.render(songs, index, prev)
            },
            songsPerBlock = { 3 },
            bufferAhead = 2,
            keepBehind = 2,
            blocksDir = dir,
        )

        engine.ensureThrough(0) // block 0 renders fine
        networkDown = true
        try {
            engine.ensureThrough(1)
            fail("expected total render failure to surface")
        } catch (e: RuntimeException) {
            assertEquals("network died", e.message)
        }

        // every attempt burned (initial + 3 retries); block 0 STAYS playable
        assertEquals(4, block1Attempts)
        assertEquals(1, engine.frontierForTest())
        assertEquals("$dir/block_0.m4a", engine.getBlockPath(0))
        assertEquals(0, engine.getBlockMeta(0)?.index)

        // network returns -> the SAME engine recovers on the next pass
        networkDown = false
        engine.ensureThrough(1)
        assertEquals(2, engine.frontierForTest())
        assertEquals(1, engine.getBlockMeta(1)?.index)
        assertTrue(blockFile(dir, 1).exists())
    }

    @Test
    fun renderCooldown_pausesBetweenBackToBackRenders_whenBufferComfortable() = runTest {
        val dir = newTempDir()
        val render = FakeRender(dir)
        val engine = newEngine(FakeNextSongs(3), render, dir)

        engine.ensureThrough(3, cooldownMs = 15_000L)

        assertEquals(listOf(0, 1, 2, 3), render.callIndices)
        // cooldown applies ONLY between back-to-back renders once the buffer is
        // comfortable: after block 0 (frontier=1 == current+1) no pause; after
        // blocks 1 and 2 a 15s pause each; after block 3 the target is reached
        // (no trailing pause). Total = 30s of virtual time.
        assertEquals(30_000L, currentTime)
    }

    @Test
    fun renderCooldown_zeroByDefault_addsNoDelay() = runTest {
        val dir = newTempDir()
        val render = FakeRender(dir)
        val engine = newEngine(FakeNextSongs(3), render, dir)

        engine.ensureThrough(3)

        assertEquals(listOf(0, 1, 2, 3), render.callIndices)
        assertEquals(0L, currentTime)
    }
}