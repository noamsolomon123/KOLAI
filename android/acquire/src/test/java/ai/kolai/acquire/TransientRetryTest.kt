package ai.kolai.acquire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * FETCH RESILIENCE unit tests: transient (IOException-caused) failures retry
 * with backoff; deterministic failures (verified no-candidate / PO-token wall /
 * HTTP error codes -- all FetchExceptions WITHOUT an IOException cause) never
 * retry. The sleeper is injected so the tests are instant.
 */
class TransientRetryTest {

    private val sleeps = mutableListOf<Long>()
    private val sleeper: (Long) -> Unit = { sleeps.add(it) }

    // ---- isTransient classification ---------------------------------------

    @Test
    fun isTransient_directIoException_true() {
        assertTrue(TransientRetry.isTransient(IOException("reset")))
        assertTrue(TransientRetry.isTransient(SocketTimeoutException("timeout")))
    }

    @Test
    fun isTransient_ioExceptionDeepInCauseChain_true() {
        val e = RuntimeException("outer", RuntimeException("mid", IOException("net")))
        assertTrue(TransientRetry.isTransient(e))
    }

    @Test
    fun isTransient_fetchExceptionWrappingIoException_true() {
        val e = NewPipeSource.FetchException("search failed", SocketTimeoutException("t"))
        assertTrue(TransientRetry.isTransient(e))
    }

    @Test
    fun isTransient_deterministicFailures_false() {
        // verified no-candidate / PO-token wall / HTTP code: no cause
        assertFalse(TransientRetry.isTransient(NewPipeSource.FetchException("no candidate: ...")))
        assertFalse(TransientRetry.isTransient(IllegalStateException("logic bug")))
        assertFalse(TransientRetry.isTransient(RuntimeException("outer", IllegalArgumentException("x"))))
    }

    @Test
    fun isTransient_causeCycle_terminates() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b) // cycle
        assertFalse(TransientRetry.isTransient(a))
    }

    // ---- run: retry behaviour ---------------------------------------------

    @Test
    fun run_successFirstTry_noSleep() {
        var calls = 0
        val out = TransientRetry.run("step", sleeper = sleeper) { calls++; "ok" }
        assertEquals("ok", out)
        assertEquals(1, calls)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun run_transientFailures_retriedWithBackoff_thenSucceeds() {
        var calls = 0
        val out = TransientRetry.run("step", sleeper = sleeper) {
            calls++
            if (calls < 3) throw NewPipeSource.FetchException("net", IOException("down"))
            "ok"
        }
        assertEquals("ok", out)
        assertEquals(3, calls) // initial + 2 retries
        assertEquals(listOf(1_000L, 3_000L), sleeps)
    }

    @Test
    fun run_transientFailuresExhaustRetries_lastErrorRethrown() {
        var calls = 0
        val boom = NewPipeSource.FetchException("net", IOException("down"))
        try {
            TransientRetry.run<String>("step", sleeper = sleeper) { calls++; throw boom }
            fail("expected FetchException")
        } catch (e: NewPipeSource.FetchException) {
            assertSame(boom, e)
        }
        assertEquals(3, calls) // initial + maxRetries(2)
        assertEquals(2, sleeps.size)
    }

    @Test
    fun run_deterministicFailure_neverRetried() {
        var calls = 0
        val noMatch = NewPipeSource.FetchException(
            "no candidate: 12 results but none matched the requested title",
        )
        try {
            TransientRetry.run<String>("step", sleeper = sleeper) { calls++; throw noMatch }
            fail("expected FetchException")
        } catch (e: NewPipeSource.FetchException) {
            assertSame(noMatch, e)
        }
        assertEquals(1, calls) // verified-no-match is deterministic: ONE attempt
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun run_customBackoff_clampsToLastEntry() {
        var calls = 0
        try {
            TransientRetry.run<String>(
                "step",
                maxRetries = 3,
                backoffMs = longArrayOf(10L, 20L),
                sleeper = sleeper,
            ) { calls++; throw IOException("down") }
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(4, calls)
        assertEquals(listOf(10L, 20L, 20L), sleeps) // 3rd retry reuses last backoff
    }
}