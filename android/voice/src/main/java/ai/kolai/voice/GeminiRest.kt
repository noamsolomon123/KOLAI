package ai.kolai.voice

import io.ktor.http.HttpStatusCode

/** Base URL for the Gemini generateContent REST endpoint. */
internal const val GEMINI_BASE_URL =
    "https://generativelanguage.googleapis.com/v1beta/models"

/**
 * Build the per-call generateContent endpoint for [model] authenticated with
 * the given API [key] (passed as a query param, matching the google-genai SDK).
 */
internal fun endpoint(model: String, key: String): String =
    "$GEMINI_BASE_URL/$model:generateContent?key=$key"

/**
 * A response that should trigger key rotation: rate-limiting (429) or any
 * server-side error (5xx). Mirrors the Python "rotate on Exception" loop, where
 * the google-genai SDK raised on exactly these conditions.
 */
internal fun HttpStatusCode.isTransientFailure(): Boolean =
    value == 429 || value in 500..599
