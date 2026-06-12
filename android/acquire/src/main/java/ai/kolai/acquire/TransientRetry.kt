package ai.kolai.acquire

import java.io.IOException

/**
 * Retry policy for TRANSIENT network failures in the acquire pipeline.
 *
 * TRANSIENT = the failure's cause chain contains an [IOException] (socket
 * timeouts, DNS, connection resets, ...) -- the kind of failure that a retry
 * on a flaky drive-through-a-tunnel connection can actually fix.
 *
 * DETERMINISTIC failures must NOT retry, and don't: a verified no-candidate
 * (search returned results but none matched the requested title), the
 * PO-token / bot-detection wall (zero streams), and HTTP error codes are all
 * raised as [NewPipeSource.FetchException]s WITHOUT an IOException cause, so
 * [isTransient] is false and they surface immediately.
 *
 * Pure JVM logic (no Android deps) so it is unit-testable; the sleeper is
 * injectable for instant tests.
 */
internal object TransientRetry {

    const val DEFAULT_MAX_RETRIES = 2
    val DEFAULT_BACKOFF_MS = longArrayOf(1_000L, 3_000L)

    /** True when [e]'s cause chain contains an [IOException] (network-ish). */
    fun isTransient(e: Throwable): Boolean {
        var cur: Throwable? = e
        var hops = 0
        while (cur != null && hops < 10) { // hop cap: cause cycles exist
            if (cur is IOException) return true
            if (cur is InterruptedException) return false
            cur = cur.cause
            hops++
        }
        return false
    }

    /**
     * Run [block]; on a TRANSIENT failure sleep and retry up to [maxRetries]
     * times, then rethrow the last failure. Non-transient failures rethrow
     * immediately (attempt 1) -- deterministic semantics are preserved.
     */
    fun <T> run(
        what: String,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        backoffMs: LongArray = DEFAULT_BACKOFF_MS,
        sleeper: (Long) -> Unit = { Thread.sleep(it) },
        log: (String) -> Unit = {},
        block: () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Throwable) {
                if (attempt >= maxRetries || !isTransient(e)) throw e
                val wait = if (backoffMs.isEmpty()) 1_000L else backoffMs[minOf(attempt, backoffMs.size - 1)]
                log(
                    "transient failure in $what " +
                        "(attempt ${attempt + 1}/${maxRetries + 1}): $e -- retrying in ${wait}ms",
                )
                sleeper(wait)
                attempt++
            }
        }
    }
}