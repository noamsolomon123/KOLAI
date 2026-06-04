from datetime import datetime
from radioai.config import Config
from radioai.djcontext import DJContext


def _cfg():
    return Config(gemini_api_keys=[], llm_model="m", tts_model="m", tts_voice="v",
                  cache_dir=".", spotify_client_id="", spotify_client_secret="",
                  spotify_redirect_uri="x", city="Tel Aviv",
                  topics=["AI", "physics"])


class _FakeWeather:
    def for_city(self, city):
        return "24 מעלות, שמיים בהירים"


class _FakeNews:
    def headlines(self, query=None, limit=1):
        return ["חדשות כלליות"]

    def top_for_topics(self, topics):
        return {t: f"כותרת על {t}" for t in topics}


def test_build_populates_all():
    ctx = DJContext.build(_cfg(), clock_dt=datetime(2026, 6, 4, 19, 0),
                          weather=_FakeWeather(), news=_FakeNews())
    assert ctx.time_str == "19:00"
    assert ctx.part_of_day == "ערב"
    assert ctx.weather == "24 מעלות, שמיים בהירים"
    assert ctx.general_headline == "חדשות כלליות"
    assert ctx.topic_headlines["AI"] == "כותרת על AI"


class _BoomWeather:
    def for_city(self, city):
        raise RuntimeError("no net")


def test_build_is_resilient_to_weather_failure():
    ctx = DJContext.build(_cfg(), clock_dt=datetime(2026, 6, 4, 19, 0),
                          weather=_BoomWeather(), news=_FakeNews())
    assert ctx.weather is None
    assert ctx.general_headline == "חדשות כלליות"