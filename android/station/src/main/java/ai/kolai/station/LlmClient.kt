package ai.kolai.station

/**
 * LLM seam for the :station module.
 *
 * Mirrors the Python `_LLM` Protocol in `backend/radioai/setlist.py`
 * (`def complete(self, prompt: str) -> str`), but `suspend` because the Kotlin
 * LLM clients are coroutine-based.
 *
 * In production this is adapted from :voice's `GeminiTextClient.complete`
 * (same `suspend fun complete(prompt: String): String` signature). We do NOT
 * depend on :voice here -- callers wire a thin adapter -- so this module stays
 * unit-testable with a fake client.
 *
 * TEMPERATURE (2026-06-13, generation-diversity fix): complete() gained an
 * optional temperature that forwards to the model's sampling config. It is
 * DEFAULTED to null so every existing fake / planner caller compiles unchanged
 * and behaves exactly as before (null => the model's own default); only DjBrain
 * passes a per-beat value (creative / structured / precise).
 */
interface LlmClient {
    suspend fun complete(prompt: String, temperature: Double? = null): String
}
