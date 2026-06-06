package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.TrackAnalysis
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * TDD for Task 5.4 (StationEngine), ported 1:1 from
 * backend/radioai/station.py class StationEngine.
 *
 * No real planner/renderer/audio: both seams are faked. The engine is
 * constructed with function-typed seams (nextSongs / renderBlock) so the
 * production code can pass rollingPlanner::nextSongs and blockRenderer::render
 * unchanged. Fakes are deterministic and RECORD the (index, prevTrack) they were
 * called with so continuity threading can be asserted. blocksDir is a real temp
 * dir; the fake render actually writes the block_<index>.m4a file so prune/delete
 * can be asserted on the filesystem.
 */
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

    /** Records each render call's (index, prevTrack); writes the .m4a file. */
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
            val meta = BlockMeta(index = index, durationS = 10.0, segments = emptyList(), talk = emptyList())
            val lastTrack = LoadedTrack(song = last, analysis = TrackAnalysis(
                path = path, durationS = 10.0, bpm = 120.0, beatTimes = listOf(0.0),
                keyCamelot = "8A", energy = 0.5, introEndS = 1.0, outroStartS = 9.0, vocalOnsetS = 1.0,
            ), audio = FloatArray(0), path = path)
            return BlockResult(audio = FloatArray(0), meta = meta, path = path, lastTrack = lastTrack)
        }
    }

    /**
     * Deterministic planner seam. Each call returns songsPerBlock fresh songs
     * named by a global counter so blocks have distinct last-songs; records the
     * seed it was handed.
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

    // ---- ensureThrough: ordered render + continuity ----------------------

    @Test
    fun ensureThrough_rendersBlocksInOrder_threadsContinuity() = runTest {
        val dir = newTempDir()
        val planner = FakeNextSongs(3)
        val render = FakeRender(dir)
        val engine = StationEngine(
            nextSongs = planner::nextSongs,
            renderBlock = render::render,
            songsPerBlock = 3,
            bufferAhead = 2,
            keepBehind = 2,
            blocksDir = dir,
        )

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
        engine = StationEngine(
            nextSongs = planner::nextSongs,
            renderBlock = render::render,
            songsPerBlock = 3,
            bufferAhead = 2,
            keepBehind = 2,
            blocksDir = dir,
        )

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
        val engine = StationEngine(
            nextSongs = planner::nextSongs,
            renderBlock = render::render,
            songsPerBlock = 3,
            bufferAhead = 2,
            keepBehind = 2,
            blocksDir = dir,
        )

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
        val engine = StationEngine(
            nextSongs = planner::nextSongs,
            renderBlock = render::render,
            songsPerBlock = 3,
            bufferAhead = 2,
            keepBehind = 2,
            blocksDir = dir,
        )

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
        val engine = StationEngine(
            nextSongs = planner::nextSongs,
            renderBlock = render::render,
            songsPerBlock = 3,
            bufferAhead = 2,
            keepBehind = 2,
            blocksDir = dir,
        )

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
        val engine = StationEngine(
            nextSongs = planner::nextSongs,
            renderBlock = render::render,
            songsPerBlock = 3,
            bufferAhead = 2,
            keepBehind = 2,
            blocksDir = dir,
        )
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
}