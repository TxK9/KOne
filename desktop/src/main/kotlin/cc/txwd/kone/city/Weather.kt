package cc.txwd.kone.city

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The weather over a city as the paintings use it (Codex's weather-model-v11.js): how much cloud,
 * rain, snow and fog, 0 to 1; the wind, 1 for an ordinary breeze up to 2; how wet things are; °C.
 */
data class WeatherProfile(
    val cloudCover: Double,
    val rain: Double = 0.0,
    val snow: Double = 0.0,
    val fog: Double = 0.0,
    val wind: Double = 1.0,
    val wetness: Double = 0.0,
    val temperature: Double = 20.0,
) {
    fun mix(to: WeatherProfile, f: Double): WeatherProfile {
        val t = smooth(f)
        fun Double.to(b: Double) = this + (b - this) * t
        return WeatherProfile(
            cloudCover.to(to.cloudCover), rain.to(to.rain), snow.to(to.snow), fog.to(to.fog),
            wind.to(to.wind), wetness.to(to.wetness), temperature.to(to.temperature),
        )
    }

    companion object {
        /** Codex's clear day, for tests and for a city whose weather has not come yet. */
        val CLEAR = WeatherProfile(cloudCover = .16, fog = .025, wind = .78, temperature = 23.0)

        /**
         * The current weather in an Open-Meteo forecast response, as fromCurrent reads it: rain
         * and snow from their rates over the interval, fog from the weather code or visibility.
         * Null when anything it needs is missing or in other units.
         */
        fun fromOpenMeteo(json: JsonObject): WeatherProfile? {
            val current = json["current"] as? JsonObject ?: return null
            val units = json["current_units"] as? JsonObject ?: return null
            fun number(key: String) = (current[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
            fun unitOf(key: String) = (units[key] as? JsonPrimitive)?.contentOrNull
            val interval = number("interval")?.takeIf { it > 0 } ?: return null
            val code = number("weather_code")?.toInt() ?: return null
            val temperature = number("temperature_2m") ?: return null
            val cloud = number("cloud_cover") ?: return null
            val windSpeed = number("wind_speed_10m") ?: return null
            if (number("time") == null) return null
            if (unitOf("temperature_2m") != "°C" || unitOf("wind_speed_10m") != "m/s" || unitOf("cloud_cover") != "%") return null
            if (unitOf("rain") != "mm" || unitOf("showers") != "mm" || unitOf("snowfall") != "cm" || unitOf("time") != "unixtime" || unitOf("interval") != "seconds") return null
            if (unitOf("visibility") != null && unitOf("visibility") != "m") return null
            fun perHour(key: String) = max(0.0, number(key) ?: 0.0) * 3600 / interval
            val snowCode = code in listOf(71, 73, 75, 77, 85, 86)
            val rain = unit(sqrt((perHour("rain") + perHour("showers")) / 6))
            val snow = if (snowCode) unit(sqrt(perHour("snowfall") / 1.5)) else 0.0
            val visibility = number("visibility") ?: 10_000.0
            val fog = if (code == 45 || code == 48) .8 else ((6000 - visibility) / 6500).coerceIn(0.0, .6)
            return WeatherProfile(
                cloudCover = unit(cloud / 100), rain = rain, snow = snow, fog = fog,
                wind = (.62 + max(0.0, windSpeed) / 11).coerceIn(.5, 2.0),
                wetness = unit(rain + .25 * snow), temperature = temperature.coerceIn(-60.0, 60.0),
            )
        }
    }
}

/**
 * The real weather over the cities on the scroll, from Open-Meteo (free, no key, no account):
 * one request for all of them by the cities' own coordinates, never the person's location,
 * kept ten minutes. When it cannot be had, the cities keep fair weather.
 */
object CityWeather {
    private const val KEEP_MILLIS = 10 * 60_000L
    private const val VARIABLES = "temperature_2m,relative_humidity_2m,is_day,precipitation,rain,showers,snowfall,weather_code," +
        "cloud_cover,wind_speed_10m,wind_direction_10m,wind_gusts_10m,visibility"

    private class Kept(val profile: WeatherProfile, val at: Long)

    private val kept = HashMap<String, Kept>()
    private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build() }

    /** The weather of each of [scenes]' cities that is known, by city; asks Open-Meteo for the ones not asked lately. */
    fun current(scenes: List<CityScene>, now: Long = System.currentTimeMillis()): Map<String, WeatherProfile> {
        val cities = scenes.distinctBy { it.cityId }
        val stale = synchronized(kept) { cities.filter { city -> kept[city.cityId]?.let { now - it.at >= KEEP_MILLIS } ?: true } }
        if (stale.isNotEmpty()) {
            runCatching { fetch(stale) }.getOrNull()?.let { fetched ->
                synchronized(kept) { fetched.forEach { (city, profile) -> kept[city] = Kept(profile, now) } }
            }
        }
        return synchronized(kept) { cities.mapNotNull { city -> kept[city.cityId]?.let { city.cityId to it.profile } }.toMap() }
    }

    private fun fetch(cities: List<CityScene>): Map<String, WeatherProfile> {
        val query = listOf(
            "latitude" to cities.joinToString(",") { it.lat.toString() },
            "longitude" to cities.joinToString(",") { it.lon.toString() },
            "current" to VARIABLES, "wind_speed_unit" to "ms", "temperature_unit" to "celsius",
            "precipitation_unit" to "mm", "timeformat" to "unixtime", "timezone" to "UTC",
        ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        val request = HttpRequest.newBuilder(URI("https://api.open-meteo.com/v1/forecast?$query")).timeout(Duration.ofSeconds(12)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Open-Meteo answered ${response.statusCode()}" }
        return parse(Json.parseToJsonElement(response.body()), cities)
    }

    /** One forecast for one place, a list of them for several, in the order asked. */
    fun parse(json: JsonElement, cities: List<CityScene>): Map<String, WeatherProfile> {
        val answers = (json as? JsonArray)?.toList() ?: listOf(json)
        return cities.zip(answers).mapNotNull { (city, answer) -> (answer as? JsonObject)?.let(WeatherProfile::fromOpenMeteo)?.let { city.cityId to it } }.toMap()
    }
}

/**
 * The air over one scene: Codex's wind (environment-v13.js), turned by the real weather when
 * there is some. A change of weather eases in over five seconds, and the distance the wind has
 * carried things never jumps, so no cloud or ripple leaps when the weather changes.
 */
class CityAir(private val id: String) {
    private var profile: WeatherProfile? = null
    private var from: WeatherProfile? = null
    private var changedAt = 0.0
    private var gain = 1.0
    private var offset = 0.0

    /** The weather as it is at [seconds], easing to [weather] when that is new; once known, it stays until another comes. */
    fun at(seconds: Double, weather: WeatherProfile?): Environment {
        val base = Wind.at(id, seconds)
        if (weather != null && weather != profile) {
            from = current(seconds)
            profile = weather
            changedAt = seconds
        }
        val p = current(seconds)
        // The base wind's own distance, scaled by the weather's wind; continuous when the scale changes.
        val newGain = p?.wind?.coerceIn(.5, 2.0) ?: 1.0
        offset += (gain - newGain) * base.advection
        gain = newGain
        if (p == null) return base.copy(advection = base.advection + offset)
        val wind = base.wind * gain
        val cover = unit(p.cloudCover * .88 + base.cloudCover * .12)
        val fogVisibility = 1 - smooth((p.fog - .35) / .36)
        return Environment(
            wind = wind.coerceIn(.45, 1.65),
            advection = gain * base.advection + offset,
            gust = unit((wind - .4) / 1.8),
            cloudCover = cover,
            waveGroup = unit(base.waveGroup * .6 + (wind - .5) * .24 + p.rain * .18).coerceAtMost(2.0),
            shimmer = (base.shimmer * (1 - cover * .55)).coerceIn(0.0, 2.0),
            rain = p.rain, snow = p.snow, fog = p.fog,
            fogVisibility = fogVisibility,
            directVisibility = fogVisibility * (1 - p.cloudCover * .82) * (1 - p.rain * .72) * (1 - p.snow * .55),
            transmission = (1 - .88 * cover - .4 * p.rain - .25 * p.fog).coerceIn(.035, 1.0),
            wetness = p.wetness,
            temperature = p.temperature,
            activity = ((1 - p.rain * .94) * (1 - p.snow * .78) * (1 - p.fog * .7)).coerceIn(.025, 1.0),
            weather = p,
        )
    }

    private fun current(seconds: Double): WeatherProfile? {
        val to = profile ?: return null
        val start = from ?: return to
        return if (seconds - changedAt >= 5) to else start.mix(to, (seconds - changedAt) / 5)
    }
}
