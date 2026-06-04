from radioai.weather import (
    WeatherNow, parse_weather, weather_code_to_hebrew, WeatherService,
)


def test_parse_weather():
    w = parse_weather({"current": {"temperature_2m": 24.3, "weather_code": 0}})
    assert isinstance(w, WeatherNow)
    assert abs(w.temp_c - 24.3) < 0.001
    assert w.code == 0


def test_weather_code_to_hebrew_known_and_unknown():
    assert "בהיר" in weather_code_to_hebrew(0)
    assert weather_code_to_hebrew(999) == "מזג אוויר משתנה"


def test_for_city_builds_summary():
    def fake_get(url, params=None):
        if "geocoding" in url:
            return {"results": [{"latitude": 32.07, "longitude": 34.78}]}
        return {"current": {"temperature_2m": 24.3, "weather_code": 0}}

    svc = WeatherService(get_json=fake_get)
    summary = svc.for_city("Tel Aviv")
    assert "24" in summary
    assert "מעלות" in summary
    assert "בהיר" in summary


def test_for_city_missing_raises():
    import pytest
    def fake_get(url, params=None):
        return {"results": []}
    with pytest.raises(ValueError):
        WeatherService(get_json=fake_get).for_city("Nowhere")