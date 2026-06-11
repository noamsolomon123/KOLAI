package ai.kolai.station

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * parseNewsRss - ported from backend/radioai/news.py parse_news_rss, but as a
 * hand-rolled scanner (no XML parser dependency in :station). Covers multiple
 * items, CDATA, entity unescaping, and malformed input never throwing.
 */
class NewsRssTest {

    @Test
    fun parses_titles_of_multiple_items() {
        val xml = """
            <rss><channel>
              <title>Google News</title>
              <item><title>כותרת ראשונה</title><link>http://a</link></item>
              <item><title> כותרת שנייה </title></item>
              <item><title>Third headline</title></item>
            </channel></rss>
        """.trimIndent()
        assertEquals(
            listOf("כותרת ראשונה", "כותרת שנייה", "Third headline"),
            parseNewsRss(xml),
        )
    }

    @Test
    fun channel_title_outside_items_is_ignored() {
        val xml = "<rss><channel><title>FEED</title>" +
            "<item><title>only this</title></item></channel></rss>"
        assertEquals(listOf("only this"), parseNewsRss(xml))
    }

    @Test
    fun unwraps_cdata() {
        val xml = "<rss><channel><item>" +
            "<title><![CDATA[חדשות: בדיקה <b> & עוד]]></title>" +
            "</item></channel></rss>"
        assertEquals(listOf("חדשות: בדיקה <b> & עוד"), parseNewsRss(xml))
    }

    @Test
    fun unescapes_the_five_xml_entities() {
        val xml = "<rss><channel><item>" +
            "<title>A &amp; B &lt;c&gt; &quot;d&quot; &apos;e&apos;</title>" +
            "</item></channel></rss>"
        assertEquals(listOf("A & B <c> \"d\" 'e'"), parseNewsRss(xml))
    }

    @Test
    fun item_without_title_is_skipped() {
        val xml = "<rss><channel>" +
            "<item><link>http://a</link></item>" +
            "<item><title>yes</title></item>" +
            "</channel></rss>"
        assertEquals(listOf("yes"), parseNewsRss(xml))
    }

    @Test
    fun title_tag_with_attributes_is_handled() {
        val xml = "<rss><channel><item><title type=\"text\">attr title</title></item></channel></rss>"
        assertEquals(listOf("attr title"), parseNewsRss(xml))
    }

    @Test
    fun malformed_inputs_return_empty_or_partial_without_throwing() {
        assertEquals(emptyList<String>(), parseNewsRss(""))
        assertEquals(emptyList<String>(), parseNewsRss("not xml at all"))
        // unclosed item -> skipped
        assertEquals(emptyList<String>(), parseNewsRss("<rss><item><title>lost"))
        // first item broken, second intact -> partial result
        val xml = "<rss><item><title>broken</item>" +
            "<item><title>ok</title></item></rss>"
        val out = parseNewsRss(xml)
        assertTrue("must not throw and must keep the intact item", out.contains("ok"))
    }

    @Test
    fun item_prefix_does_not_match_longer_tag_names() {
        val xml = "<rss><itemset><title>nope</title></itemset>" +
            "<item><title>real</title></item></rss>"
        assertEquals(listOf("real"), parseNewsRss(xml))
    }
}