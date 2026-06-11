package ai.kolai.station

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WeatherText - ported from backend/radioai/weather.py (_CODE_HE /
 * weather_code_to_hebrew / the for_city output line).
 */
class WeatherTextTest {

    @Test
    fun known_codes_map_to_hebrew() {
        assertEquals("שמיים בהירים", WeatherText.describe(0))
        assertEquals("מעונן", WeatherText.describe(3))
        assertEquals("גשם", WeatherText.describe(63))
        assertEquals("סופת רעמים", WeatherText.describe(99))
    }

    @Test
    fun unknown_code_falls_back() {
        assertEquals("מזג אוויר משתנה", WeatherText.describe(42))
        assertEquals("מזג אוויר משתנה", WeatherText.describe(-1))
    }

    @Test
    fun weather_line_rounds_temperature() {
        assertEquals("24 מעלות, בהיר ברובו", WeatherText.weatherLine(23.6, 1))
        assertEquals("23 מעלות, בהיר ברובו", WeatherText.weatherLine(23.4, 1))
        assertEquals("0 מעלות, שלג", WeatherText.weatherLine(0.2, 73))
    }

    @Test
    fun weather_line_rounds_half_to_even_like_python() {
        // Python 3 round() is banker's rounding: round(22.5) == 22, round(23.5) == 24.
        assertEquals("22 מעלות, מעונן", WeatherText.weatherLine(22.5, 3))
        assertEquals("24 מעלות, מעונן", WeatherText.weatherLine(23.5, 3))
    }

    @Test
    fun weather_line_with_unknown_code() {
        assertEquals("18 מעלות, מזג אוויר משתנה", WeatherText.weatherLine(18.0, 1234))
    }
}