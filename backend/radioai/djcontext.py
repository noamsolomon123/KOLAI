from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional
from radioai.clock import now_parts
from radioai.weather import WeatherService
from radioai.news import NewsService


@dataclass
class DJContext:
    time_str: str
    part_of_day: str
    weather: Optional[str]
    general_headline: Optional[str]
    topic_headlines: dict = field(default_factory=dict)

    @classmethod
    def build(cls, cfg, *, clock_dt=None, weather=None, news=None) -> "DJContext":
        dt = clock_dt or datetime.now()
        time_str, part = now_parts(dt)
        ws = weather if weather is not None else WeatherService()
        ns = news if news is not None else NewsService()

        weather_str = None
        try:
            if cfg.city:
                weather_str = ws.for_city(cfg.city)
        except Exception:
            weather_str = None

        general = None
        try:
            hs = ns.headlines(None, limit=1)
            general = hs[0] if hs else None
        except Exception:
            general = None

        topics = {}
        try:
            topics = ns.top_for_topics(cfg.topics)
        except Exception:
            topics = {}

        return cls(time_str=time_str, part_of_day=part, weather=weather_str,
                   general_headline=general, topic_headlines=topics)