package ai.kolai.station

/**
 * WeatherText - the pure mapping/formatting half of backend/radioai/weather.py.
 *
 * Only the WMO-code -> Hebrew table (`_CODE_HE`) and the one-line Hebrew
 * weather string (WeatherService.for_city's return value) are ported here;
 * the HTTP/JSON half (Open-Meteo geocoding + forecast) lives app-side
 * (LiveDjContext) so :station stays free of networking and JSON parsing.
 */
object WeatherText {

    /** Python: weather._CODE_HE. WMO weather codes -> short Hebrew description. */
    val weatherCodeToHebrew: Map<Int, String> = mapOf(
        0 to "שמיים בהירים", 1 to "בהיר ברובו", 2 to "מעונן חלקית", 3 to "מעונן",
        45 to "ערפל", 48 to "ערפל", 51 to "טפטוף קל", 53 to "טפטוף", 55 to "טפטוף חזק",
        61 to "גשם קל", 63 to "גשם", 65 to "גשם חזק", 71 to "שלג קל", 73 to "שלג",
        75 to "שלג כבד", 80 to "ממטרים", 81 to "ממטרים", 82 to "ממטרים עזים",
        95 to "סופת רעמים", 96 to "סופת רעמים", 99 to "סופת רעמים",
    )

    /** Python: weather_code_to_hebrew (unknown codes get the same fallback). */
    fun describe(code: Int): String = weatherCodeToHebrew[code] ?: "מזג אוויר משתנה"

    /**
     * Python: f"{round(w.temp_c)} מעלות, {weather_code_to_hebrew(w.code)}".
     * Math.rint is round-half-to-even, matching Python 3's round() exactly.
     */
    fun weatherLine(tempC: Double, code: Int): String =
        "${Math.rint(tempC).toInt()} מעלות, ${describe(code)}"
}