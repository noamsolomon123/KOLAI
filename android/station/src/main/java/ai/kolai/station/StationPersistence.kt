package ai.kolai.station

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Disk persistence helpers for [StationEngine] (and [RollingPlanner]'s history
 * file writes). JSON uses kotlinx-serialization-json at RUNTIME only (manual
 * tree building / navigation -- no @Serializable, no serialization compiler
 * plugin; matches [SetlistParsing]'s approach and the module's build setup).
 *
 * Every `fromJson` is corruption-tolerant: ANY parse problem returns null so
 * callers can fall back to a fresh state instead of crashing on a half-written
 * or stale file.
 */
internal object StationPersistence {

    /** Engine state persisted as `state.json` in blocksDir. Generation is NOT
     *  persisted (it only exists to discard in-flight renders within one
     *  process). prevLastTrack (decoded PCM) is NOT persistable; only the
     *  planner-seed song title/artist survive a restart. */
    data class EngineState(
        val frontier: Int,
        val current: Int,
        val prevTitle: String?,
        val prevArtist: String?,
    )

    fun metaToJson(meta: BlockMeta): String = buildJsonObject {
        put("index", meta.index)
        put("durationS", meta.durationS)
        put(
            "segments",
            buildJsonArray {
                for (s in meta.segments) {
                    add(
                        buildJsonObject {
                            put("index", s.index)
                            put("title", s.title)
                            put("artist", s.artist)
                            put("startS", s.startS)
                            put("endS", s.endS)
                        },
                    )
                }
            },
        )
        put(
            "talk",
            buildJsonArray {
                for (t in meta.talk) {
                    add(
                        buildJsonObject {
                            put("beat", t.beat)
                            put("text", t.text)
                            put("startS", t.startS)
                            put("endS", t.endS)
                        },
                    )
                }
            },
        )
    }.toString()

    fun metaFromJson(text: String): BlockMeta? = try {
        val o = Json.parseToJsonElement(text).jsonObject
        BlockMeta(
            index = o.getValue("index").jsonPrimitive.int,
            durationS = o.getValue("durationS").jsonPrimitive.double,
            segments = o.getValue("segments").jsonArray.map { e ->
                val s = e.jsonObject
                Segment(
                    index = s.getValue("index").jsonPrimitive.int,
                    title = s.getValue("title").jsonPrimitive.content,
                    artist = s.getValue("artist").jsonPrimitive.content,
                    startS = s.getValue("startS").jsonPrimitive.double,
                    endS = s.getValue("endS").jsonPrimitive.double,
                )
            },
            talk = o.getValue("talk").jsonArray.map { e ->
                val t = e.jsonObject
                TalkEntry(
                    beat = t.getValue("beat").jsonPrimitive.content,
                    text = t.getValue("text").jsonPrimitive.content,
                    startS = t.getValue("startS").jsonPrimitive.double,
                    endS = t.getValue("endS").jsonPrimitive.double,
                )
            },
        )
    } catch (e: Exception) {
        null
    }

    fun stateToJson(state: EngineState): String = buildJsonObject {
        put("frontier", state.frontier)
        put("current", state.current)
        if (state.prevTitle != null) put("prevTitle", state.prevTitle)
        if (state.prevArtist != null) put("prevArtist", state.prevArtist)
    }.toString()

    fun stateFromJson(text: String): EngineState? = try {
        val o = Json.parseToJsonElement(text).jsonObject
        EngineState(
            frontier = o.getValue("frontier").jsonPrimitive.int,
            current = o.getValue("current").jsonPrimitive.int,
            prevTitle = o["prevTitle"]?.jsonPrimitive?.contentOrNull,
            prevArtist = o["prevArtist"]?.jsonPrimitive?.contentOrNull,
        )
    } catch (e: Exception) {
        null
    }

    /**
     * Write [text] to [target] via a sibling `.tmp` file + rename, so readers
     * never observe a half-written file. `Files.move(REPLACE_EXISTING)` is used
     * (plain [File.renameTo] does not replace on Windows); on any failure we
     * fall back to delete + rename best-effort.
     */
    fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        try {
            java.nio.file.Files.move(
                tmp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            try {
                target.delete()
            } catch (ignored: Exception) {
            }
            tmp.renameTo(target)
        }
    }
}