package ai.kolai.station

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for DjText.kt, ported from backend/radioai/djbrain.py
 * (words_for_seconds, _is_skip, _clean, _finish). The clean() pipeline is the
 * spoken-output safety net, so these assertions guard the regex behavior.
 */
class DjTextTest {

    @Test
    fun wordsForSeconds_matches_python() {
        // int(8.0 * 2.5) == 20
        assertEquals(20, wordsForSeconds(8.0))
        assertEquals(0, wordsForSeconds(0.0))
        // int truncates, not rounds: int(3.0 * 2.5) == int(7.5) == 7
        assertEquals(7, wordsForSeconds(3.0))
    }

    @Test
    fun isSkip_true_for_skip_token_variants() {
        assertTrue(isSkip("SKIP"))
        assertTrue(isSkip(" skip. "))
        assertTrue(isSkip("*SKIP*"))
        assertTrue(isSkip("\"skip\""))
        assertTrue(isSkip(null))
    }

    @Test
    fun isSkip_false_for_normal_text() {
        assertFalse(isSkip("שלום עולם"))
        assertFalse(isSkip("skipper"))
        assertFalse(isSkip(""))
    }

    @Test
    fun clean_removes_english_markdown_parens_quotes_codefences() {
        val out = clean("**שלום** (note: english) world! `x`")
        // no latin letters survive
        assertFalse("latin leaked: '$out'", out.any { it in 'A'..'Z' || it in 'a'..'z' })
        // no markdown asterisks
        assertFalse("markdown leaked: '$out'", out.contains("*"))
        // parenthetical dropped entirely
        assertFalse("paren leaked: '$out'", out.contains("(") || out.contains(")"))
        // Hebrew kept
        assertTrue("Hebrew lost: '$out'", out.contains("שלום"))
    }

    @Test
    fun clean_strips_code_fences() {
        val out = clean("```kotlin\nשלום עולם\n```")
        assertFalse(out.contains("`"))
        assertTrue(out.contains("שלום"))
    }

    @Test
    fun clean_keeps_first_substantive_line_from_option_list() {
        // Numbered-list form: the _DIRECTION_LINE "\d+[.)]" branch consumes the
        // "1." / "2." prefix entirely (digit + delimiter), so no marker leaks.
        val input = "1. שלום ראשון\n2. שלום שני"
        val out = clean(input)
        assertTrue("expected first option text, got '$out'", out.contains("שלום ראשון"))
        assertFalse("second option leaked: '$out'", out.contains("שני"))
        // the leading "1." marker is fully scrubbed
        assertFalse("list marker leaked: '$out'", out.contains("1"))
        assertTrue("expected line to start with Hebrew, got '$out'", out.startsWith("שלום"))
    }

    @Test
    fun clean_strips_leading_list_markers() {
        val out = clean("- שלום מהעולם")
        assertTrue(out.startsWith("שלום"))
    }

    @Test
    fun clean_tidies_dangling_punctuation_and_spaces() {
        // space before punctuation collapses; smart quotes/backticks gone
        val out = clean("שלום   עולם !")
        assertEquals("שלום עולם!", out)
    }

    @Test
    fun clean_null_returns_empty() {
        assertEquals("", clean(null))
    }

    @Test
    fun finish_returns_clean_text_when_within_budget() {
        val out = finish("שלום עולם נחמד", budget = 10)
        assertEquals("שלום עולם נחמד", out)
    }

    @Test
    fun finish_caps_to_budget_and_trims_to_last_sentence_punctuation() {
        // 5 words; budget 3 -> first 3 words, trimmed back to last [.!?...]
        val input = "אחת שתיים. שלוש ארבע חמש"
        val out = finish(input, budget = 3)
        // capped = "אחת שתיים. שלוש" -> last sentence end is after "שתיים." -> "אחת שתיים."
        assertEquals("אחת שתיים.", out)
    }

    @Test
    fun finish_keeps_capped_text_when_no_sentence_punctuation() {
        val input = "אחת שתיים שלוש ארבע חמש"
        val out = finish(input, budget = 2)
        assertEquals("אחת שתיים", out)
    }

    // ---- findings-djtext-v3: clean() keeps an intl NAME, not a skeleton -------

    @Test
    fun clean_keeps_prefixed_intl_title_instead_of_dangling_skeleton() {
        // seq-90 root shape: "תגביר ל-Language של רדיוהד". Pre-fix clean() stripped
        // "Language" leaving "תגביר ל- של רדיוהד" - a dangling connector skeleton.
        // Now the prefixed Latin NAME survives so the name is not dropped.
        val out = clean("תגביר ל-Language של רדיוהד, תהנה")
        assertTrue("intl title must survive: '$out'", out.contains("Language"))
        // and the result is NOT the empty connector skeleton.
        assertFalse("must not collapse to 'ל- של': '$out'", out.contains("ל- של"))
        assertTrue("Hebrew context kept", out.contains("רדיוהד"))
    }

    @Test
    fun clean_keeps_intl_title_adjacent_to_connector() {
        // "עכשיו עולה Creep של רדיוהד" - the Latin run sits in the name slot
        // (right after the connector context), so it is KEPT.
        val out = clean("עכשיו עולה Creep של רדיוהד")
        assertTrue("intl title must survive: '$out'", out.contains("Creep"))
        assertTrue(out.contains("רדיוהד"))
    }

    @Test
    fun clean_still_strips_stray_leaked_english_inside_hebrew() {
        // a leaked English word NOT in a name slot (no connector/prefix context)
        // is still scrubbed, exactly as before.
        val out = clean("שלום world עולם")
        assertFalse("stray english must be stripped: '$out'", out.any { it in 'A'..'Z' || it in 'a'..'z' })
        assertTrue(out.contains("שלום"))
        assertTrue(out.contains("עולם"))
    }

    @Test
    fun clean_still_strips_parenthetical_english_note_and_stray_word() {
        // the original guard test shape: a parenthetical English note plus a stray
        // "world" must leave NO latin (note removed upstream, world is stray).
        val out = clean("**שלום** (note: english) world! `x`")
        assertFalse("latin leaked: '$out'", out.any { it in 'A'..'Z' || it in 'a'..'z' })
        assertTrue("Hebrew lost: '$out'", out.contains("שלום"))
    }

    @Test
    fun clean_strips_latin_on_fully_english_line() {
        // a line with NO Hebrew and no connector slot -> all latin scrubbed.
        val out = clean("hello world")
        assertFalse("no latin should survive a pure-english line: '$out'", out.any { it in 'A'..'Z' || it in 'a'..'z' })
    }
}