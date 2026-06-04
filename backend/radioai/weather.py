from dataclasses import dataclass
import requests

_GEO_URL = "https://geocoding-api.open-meteo.com/v1/search"
_FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

# WMO weather codes -> short Hebrew description.
_CODE_HE = {
    0: "שמיים בהירים", 1: "בהיר ברובו", 2: "מעונן חלקית", 3: "מעונן",
    45: "ערפל", 48: "ערפל", 51: "טפטוף קל", 53: "טפטוף", 55: "טפטוף חזק",
    61: "גשם קל", 63: "גשם", 65: "גשם חזק", 71: "שלג קל", 73: "שלג",
    75: "שלג כבד", 80: "ממטרים", 81: "ממטרים", 82: "ממטרים עזים",
    95: "סופת רעמים", 96: "סופת רעמים", 99: "סופת רעמים",
}


@dataclass
class WeatherNow:
    temp_c: float
    code: int


def weather_code_to_hebrew(code: int) -> str:
    return _CODE_HE.get(code, "מזג אוויר משתנה")


def parse_weather(forecast_json: dict) -> WeatherNow:
    cur = forecast_json.get("current", {})
    return WeatherNow(temp_c=float(cur.get("temperature_2m", 0.0)),
                      code=int(cur.get("weather_code", 0)))


class WeatherService:
    """Current weather for a city via Open-Meteo (free, no API key). Inject
    `get_json(url, params)` for tests; defaults to a real HTTP GET."""

    def __init__(self, get_json=None):
        self._get = get_json or self._http_get

    def _http_get(self, url, params=None):
        r = requests.get(url, params=params, timeout=10)
        r.raise_for_status()
        return r.json()

    def for_city(self, city: str) -> str:
        geo = self._get(_GEO_URL, {"name": city, "count": 1})
        results = geo.get("results") or []
        if not results:
            raise ValueError(f"City not found: {city}")
        lat = results[0]["latitude"]
        lon = results[0]["longitude"]
        fc = self._get(_FORECAST_URL, {
            "latitude": lat, "longitude": lon,
            "current": "temperature_2m,weather_code",
        })
        w = parse_weather(fc)
        return f"{round(w.temp_c)} מעלות, {weather_code_to_hebrew(w.code)}"