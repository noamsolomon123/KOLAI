from radioai.news import google_news_url, parse_news_rss, NewsService

_RSS = """<?xml version="1.0"?>
<rss version="2.0"><channel>
<title>feed</title>
<item><title>כותרת ראשונה</title><link>http://a</link></item>
<item><title>כותרת שנייה</title><link>http://b</link></item>
</channel></rss>"""


def test_google_news_url_general_is_hebrew():
    url = google_news_url()
    assert "hl=he" in url and "gl=IL" in url
    assert "/search" not in url


def test_google_news_url_topic_query():
    url = google_news_url("AI")
    assert "/search?q=AI" in url
    assert "hl=he" in url


def test_parse_news_rss_titles_in_order():
    titles = parse_news_rss(_RSS)
    assert titles == ["כותרת ראשונה", "כותרת שנייה"]


def test_headlines_limit():
    svc = NewsService(get_text=lambda url: _RSS)
    assert svc.headlines("AI", limit=1) == ["כותרת ראשונה"]


def test_top_for_topics_skips_failures():
    def flaky(url):
        if "physics" in url.lower():
            raise RuntimeError("boom")
        return _RSS
    svc = NewsService(get_text=flaky)
    out = svc.top_for_topics(["AI", "physics"])
    assert out["AI"] == "כותרת ראשונה"
    assert "physics" not in out