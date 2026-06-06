package ai.kolai.station

import ai.kolai.core.Song
import ai.kolai.core.taste.TasteProfile
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Curates a flowing radio setlist grounded in the listener's taste, via an
 * [LlmClient] (production: an adapter over :voice's GeminiTextClient). Uses a
 * two-pass generate -> self-critique -> refine loop for higher-quality,
 * repeat-free setlists, with a robust single fallback to the first draft.
 *
 * Ported 1:1 from backend/radioai/setlist.py class SetlistPlanner. All prompt
 * text (taste/constraints/craft-rules/shape) is verbatim from the Python
 * because the curation quality depends on the exact wording.
 *
 * VARIETY (Android addition, not in the Python): the listener's seeded taste
 * does not change between launches, so a deterministic prompt made Gemini
 * return the same favourites every cold start (the station always opened with
 * the same track). To break that without abandoning the taste, each [plan]
 * call injects per-call variety: the taste top-tracks/top-artists shown to the
 * LLM are SHUFFLED (re-framed) and a random "Variety seed" nonce + directive
 * is added, asking for a fresh selection (especially a different opener) while
 * staying true to the taste. The [rng] is constructor-injected so production
 * gets real randomness ([kotlin.random.Random.Default]) and tests can pass a
 * seeded [kotlin.random.Random] for a deterministic, reproducible prompt.
 *
 * TODO post-MVP: persist no-repeat history across launches -- a cross-launch
 * played-songs log on disk fed into the exclude list would further guarantee
 * variety beyond the in-prompt nonce/shuffle. Not implemented for the MVP.
 */
class SetlistPlanner(
    private val client: LlmClient,
    private val rng: kotlin.random.Random = kotlin.random.Random.Default,
) {

    /** em dash used in "Title - Artist" lines (Python _EM). */
    private val em = "—"

    /**
     * Render the taste block with the top-tracks and top-artists SHUFFLED via
     * [rng]. The SET of tracks/artists shown is unchanged -- only their ORDER
     * differs run-to-run -- so the LLM stays grounded in the same taste while
     * the input framing varies each call.
     */
    private fun tasteBlock(taste: TasteProfile): String {
        val tracks = taste.topTracks.shuffled(rng).joinToString("\n") { t ->
            "- \"${t.title}\" $em ${t.artist}"
        }
        val artists = taste.topArtists.shuffled(rng).joinToString(", ")
        return "Top tracks:\n$tracks\n\nTop artists: $artists\n"
    }

    /**
     * Per-call variety directive grounded by a random [nonce]. Pushes the LLM
     * off its default favourites -- especially the opening song -- so repeated
     * cold starts produce genuinely different sets, while explicitly keeping
     * the selection true to the listener's taste.
     */
    private fun varietyBlock(nonce: Int): String =
        "\nVariety seed: $nonce. Produce a FRESH, different selection than your " +
            "default for this listener - especially vary the OPENING song; do " +
            "NOT always start with the same track across sets. Still stay true " +
            "to the listener's taste above.\n"

    /**
     * MVP: mood vibe injection is DEFERRED (the moods table from Python
     * radioai/moods.py is intentionally not ported). Always returns "" for now,
     * regardless of [mood]. The mood param is kept on plan/the prompt builders
     * so the public seam is stable when the moods table lands.
     *
     * TODO post-MVP: inject mood vibe from moods table -- the Python emitted
     * MOODS[mood]['song'] here as a "VIBE for this set: ..." block telling the
     * LLM to pick taste-grounded songs that fit the mood without abandoning the
     * listener's favorites.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun moodBlock(mood: String?): String = ""

    private fun constraintsBlock(exclude: List<String>?, seed: Song?): String {
        val seedLine = if (seed != null) {
            "\nCONTINUITY: the station just played \"${seed.title}\" $em " +
                "${seed.artist}. The FIRST pick must flow naturally out of it - " +
                "compatible key/BPM, sensible energy step, no jarring jump.\n"
        } else {
            ""
        }
        val excludeLine = if (!exclude.isNullOrEmpty()) {
            val joined = exclude.joinToString("; ")
            "\nHARD EXCLUDE - Do NOT include any of these recently played " +
                "songs, in any version: $joined.\n"
        } else {
            ""
        }
        return seedLine + excludeLine
    }

    private fun craftRules(n: Int): String =
        "Curate a flowing $n-song set like a world-class radio music " +
            "director. Apply ALL of these:\n" +
            "1. ENERGY ARC: shape a deliberate energy arc across the set - an " +
            "inviting open, a build, a peak, then a graceful comedown - rather " +
            "than a flat or random sequence.\n" +
            "2. HARMONIC + TEMPO FLOW: order songs so transitions are smooth - " +
            "adjacent tracks should sit in compatible musical keys " +
            "(Camelot-wheel neighbours: same number, +/-1, or relative " +
            "major/minor) and similar BPM/tempo so they beat-mix cleanly. " +
            "Avoid back-to-back tracks that clash harmonically or jump tempo " +
            "violently.\n" +
            "3. HEBREW <-> INTERNATIONAL BALANCE: deliberately balance Hebrew/" +
            "Israeli songs with international/English ones, reflecting the " +
            "listener's taste - alternate languages tastefully instead of " +
            "clumping them.\n" +
            "4. FAMILIAR + DISCOVERY (IMPORTANT): keep MOST picks familiar " +
            "and beloved, but DELIBERATELY sprinkle in discovery - about 1 in " +
            "4 songs should be a real song the listener probably has NOT heard " +
            "yet but is very likely to love based on their taste (related/" +
            "adjacent/emerging artists, frequent collaborators, same-scene or " +
            "same-era deep cuts). Expose them to fresh music here and there; " +
            "do NOT fill the set with unknowns.\n" +
            "5. MOOD + ERA VARIETY: vary mood and era across the set so it " +
            "feels curated, not monotonous.\n" +
            "6. NO DUPLICATES / REAL SONGS ONLY: never repeat a song or artist " +
            "back-to-back unnecessarily; NEVER include two versions of the " +
            "same song (no live, remix, sped-up, slowed, acoustic, cover or " +
            "alternate edits as separate picks); include ONLY real, " +
            "verifiably-existing released songs - do NOT invent songs or pair " +
            "a real title with the wrong artist.\n"

    private val shape: String =
        "Return ONLY a JSON array, no prose, no code fence, in exactly this " +
            "shape:\n[{\"title\": \"...\", \"artist\": \"...\"}, ...]"

    private fun prompt(
        taste: TasteProfile,
        n: Int,
        exclude: List<String>?,
        seed: Song?,
        mood: String?,
        nonce: Int,
    ): String =
        "You are a radio music director building a personal station for one " +
            "listener. Here is their recent taste.\n\n" +
            tasteBlock(taste) +
            varietyBlock(nonce) +
            constraintsBlock(exclude = exclude, seed = seed) +
            moodBlock(mood) + "\n" +
            craftRules(n) +
            shape

    private fun refinePrompt(
        taste: TasteProfile,
        n: Int,
        draftJson: String,
        exclude: List<String>?,
        seed: Song?,
        mood: String?,
        nonce: Int,
    ): String =
        "You are a meticulous radio music director doing QUALITY CONTROL on " +
            "a draft setlist before it goes on air. CRITIQUE then REFINE it.\n\n" +
            tasteBlock(taste) +
            varietyBlock(nonce) +
            constraintsBlock(exclude = exclude, seed = seed) +
            moodBlock(mood) + "\n" +
            "Here is the DRAFT setlist to review:\n" +
            "$draftJson\n\n" +
            "Silently critique the draft for: repeated songs or artists; two " +
            "versions of the same song (live/remix/sped-up/acoustic/cover/" +
            "edit); any fake, mislabelled or non-existent songs; weak energy " +
            "arc; harmonic or tempo clashes between neighbours; poor " +
            "Hebrew/international balance; and weak taste-fit. Then RETURN A " +
            "REPAIRED setlist that fixes every problem - swap out bad picks for " +
            "better real songs, reorder for a smoother energy + harmonic/tempo " +
            "flow, and keep exactly $n songs.\n" +
            craftRules(n) +
            shape

    /**
     * Generate a setlist. By default runs a second self-critique/refine LLM
     * pass; set [refine] = false to skip it. Always falls back to the first
     * draft if the refine pass fails or yields nothing usable. Mirrors Python
     * SetlistPlanner.plan.
     *
     * Per-call variety: a single random [rng] nonce is drawn for this call and
     * shared by the draft and refine prompts (so both passes agree on the same
     * variety framing), and [tasteBlock] re-shuffles the taste each time it is
     * rendered. With [kotlin.random.Random.Default] (production) this makes
     * repeated launches diverge; with a seeded [rng] (tests) it is reproducible.
     */
    suspend fun plan(
        taste: TasteProfile,
        n: Int = 6,
        exclude: List<String>? = null,
        seed: Song? = null,
        refine: Boolean = true,
        mood: String? = null,
    ): List<Song> {
        val nonce = rng.nextInt(0, Int.MAX_VALUE)
        val draftText = client.complete(
            prompt(taste, n, exclude = exclude, seed = seed, mood = mood, nonce = nonce),
        )
        val draft = parseSetlist(draftText, taste, n)
        if (!refine) return draft

        // Python: json.dumps([{title,artist}...], ensure_ascii=False). kotlinx
        // does not unicode-escape non-ASCII, so Hebrew stays literal.
        val draftJson = buildJsonArray {
            draft.forEach { s ->
                add(
                    buildJsonObject {
                        put("title", s.title)
                        put("artist", s.artist)
                    },
                )
            }
        }.toString()

        try {
            val refinedText = client.complete(
                refinePrompt(
                    taste, n, draftJson,
                    exclude = exclude, seed = seed, mood = mood, nonce = nonce,
                ),
            )
            val refined = parseSetlist(refinedText, taste, n)
            if (refined.isNotEmpty()) return refined
        } catch (e: Exception) {
            // fall through to draft
        }
        return draft
    }
}