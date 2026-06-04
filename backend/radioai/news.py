import xml.etree.ElementTree as ET
from urllib.parse import quote
import requests

_BASE = "https://news.google.com/rss"
_TAIL = "hl=he&gl=IL&ceid=IL:he"


def google_news_url(query: str | None = None) -> str:
    if query:
        return f"{_BASE}/search?q={quote(query)}&{_TAIL}"
    return f"{_BASE}?{_TAIL}"


def parse_news_rss(xml_text: str) -> list[str]:
    root = ET.fromstring(xml_text)
    titles = []
    for item in root.iter("item"):
        t = item.find("title")
        if t is not None and t.text:
            titles.append(t.text.strip())
    return titles


class NewsService:
    """Headlines from Google News RSS (free, no API key, Hebrew). Inject
    `get_text(url)` for tests; defaults to a real HTTP GET."""

    def __init__(self, get_text=None):
        self._get = get_text or self._http_get

    def _http_get(self, url):
        r = requests.get(url, timeout=10)
        r.raise_for_status()
        return r.text

    def headlines(self, query: str | None = None, limit: int = 1) -> list[str]:
        xml = self._get(google_news_url(query))
        return parse_news_rss(xml)[:limit]

    def top_for_topics(self, topics) -> dict:
        out = {}
        for topic in topics:
            try:
                hs = self.headlines(topic, limit=1)
                if hs:
                    out[topic] = hs[0]
            except Exception:
                continue
        return out