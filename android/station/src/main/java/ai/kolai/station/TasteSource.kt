package ai.kolai.station

import ai.kolai.core.taste.TasteProfile

/**
 * Seam over the taste data layer so [RollingPlanner] can pull (and force-refresh)
 * the listener's Spotify-derived [TasteProfile] without depending on the concrete
 * :core taste repository.
 *
 * Mirrors the Python `taste_service.get_profile(use_cache=...)` call used by
 * `backend/radioai/planner_rolling.py`. In production an adapter over :core's
 * taste repository implements this; tests supply a fake.
 *
 * @param useCache when true, a cached profile is acceptable (the cheap "first
 *   load" path); when false, the implementation must FORCE a fresh re-learn from
 *   Spotify (the periodic refresh path).
 */
interface TasteSource {
    suspend fun getProfile(useCache: Boolean): TasteProfile
}