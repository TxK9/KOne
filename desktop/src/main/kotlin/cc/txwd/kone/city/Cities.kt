package cc.txwd.kone.city

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.jetbrains.skia.Image
import java.time.LocalDate

/** A part of the sky, as fractions of the whole wallpaper: left to right, top to bottom. */
data class Box(val left: Double, val right: Double, val top: Double, val bottom: Double)

/** Where the handscroll's 3:4 cut lies in the wallpaper (fractions of its height), and the wallpaper's width over height. */
data class Crop(val top: Double, val height: Double, val aspect: Double)

/**
 * One of Codex's city scenes (desktop/tools/city_scenes.py brings them in): a day and a night
 * painting of a city, where its camera looks, and the sky the sun and moon may show in. Scene
 * coordinates are fractions of the whole phone wallpaper; [crop] maps them into the cut.
 */
class CityScene(
    val id: String,
    val title: String,
    val city: String,
    val cityId: String,
    /** ISO code, as the nodes' countries: JP, US… */
    val country: String,
    val zone: String,
    val lat: Double,
    val lon: Double,
    val bearing: Double,
    val fov: Double,
    val horizonY: Double,
    val verticalScale: Double,
    val skySafe: Box,
    val skyExclusions: List<Box>,
    val crop: Crop,
    /** The band of sky clouds keep to, top and bottom, if the scene sets one. */
    val cloudBand: Pair<Double, Double>?,
    val waterType: String?,
    /** Codex's motion and city-life data for the scene, for the layers that move. */
    val motion: JsonObject,
    val life: JsonObject,
)

/** The city scenes that come with KOne, by country. */
object Cities {
    val all: List<CityScene> by lazy { load() }

    /** The countries with scenes, by ISO code. */
    val countries: Set<String> by lazy { all.map { it.country }.toSet() }

    /**
     * [count] scenes of [country] for the handscroll, from as many different cities as there
     * are, starting at a place that moves on each day so the scroll changes; none for a country
     * without scenes, which keeps the ink scroll. Hong Kong and Macau are among China's scenes
     * and come first for their own nodes.
     */
    fun pick(country: String?, date: LocalDate, count: Int = 4): List<CityScene> {
        val home = HOME_CITY[country]
        val scenes = all.filter { it.country == (if (home != null) "CN" else country) }
        if (scenes.isEmpty()) return emptyList()
        val start = (date.toEpochDay() % scenes.size).toInt()
        val ordered = (scenes.drop(start) + scenes.take(start)).sortedBy { it.cityId != home }
        val chosen = mutableListOf<CityScene>()
        val cities = mutableSetOf<String>()
        ordered.forEach { if (chosen.size < count && cities.add(it.cityId)) chosen += it }
        ordered.forEach { if (chosen.size < count && it !in chosen) chosen += it }
        return chosen
    }

    private val HOME_CITY = mapOf("HK" to "hong-kong", "MO" to "macau")

    /** The scene's day or night painting, as cut for the handscroll. */
    fun painting(scene: CityScene, night: Boolean): Image? =
        Cities::class.java.getResourceAsStream("/scenes/images/${scene.id}-${if (night) "night" else "day"}.webp")
            ?.use { Image.makeFromEncoded(it.readBytes()) }

    private fun load(): List<CityScene> {
        val text = Cities::class.java.getResourceAsStream("/scenes/catalog.json")?.use { it.readBytes().decodeToString() } ?: return emptyList()
        return (Json.parseToJsonElement(text) as JsonArray).mapNotNull { runCatching { scene(it as JsonObject) }.getOrNull() }
    }

    private fun scene(json: JsonObject): CityScene {
        val location = json["cityLocation"] as JsonObject
        val crop = json["crop"] as JsonObject
        return CityScene(
            id = json.text("id")!!,
            title = json.text("title").orEmpty(),
            city = json.text("cityLabel").orEmpty(),
            cityId = json.text("cityId").orEmpty(),
            country = json.text("country")!!,
            zone = json.text("cityZone") ?: location.text("zone")!!,
            lat = location.number("lat")!!,
            lon = location.number("lon")!!,
            bearing = json.number("cameraBearing") ?: 180.0,
            fov = json.number("horizontalFov") ?: 100.0,
            horizonY = json.number("horizonY") ?: 0.29,
            verticalScale = json.number("verticalScale") ?: 0.12,
            skySafe = (json["skySafe"] as? JsonObject).box(Box(0.10, 0.90, 0.13, 0.28)),
            skyExclusions = (json["skyExclusions"] as? JsonArray).orEmpty().map { (it as JsonObject).box(Box(0.0, 0.0, 0.0, 0.0)) },
            crop = Crop(crop.number("top")!!, crop.number("height")!!, crop.number("aspect")!!),
            cloudBand = (json["cloudSkyBand"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 2 }?.let { it[0] to it[1] },
            waterType = json.text("waterType"),
            motion = json["motion"] as? JsonObject ?: JsonObject(emptyMap()),
            life = json["life"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    private fun JsonObject?.box(default: Box): Box = if (this == null) default else Box(
        number("left") ?: default.left, number("right") ?: default.right, number("top") ?: default.top, number("bottom") ?: default.bottom,
    )

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
}
