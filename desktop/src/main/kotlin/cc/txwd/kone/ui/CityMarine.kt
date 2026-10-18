package cc.txwd.kone.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.withTransform
import cc.txwd.kone.city.BoatRoute
import cc.txwd.kone.city.CityScene
import cc.txwd.kone.city.Environment
import cc.txwd.kone.city.Polygon
import cc.txwd.kone.city.SceneMarine
import cc.txwd.kone.city.SkyState
import cc.txwd.kone.city.TAU
import cc.txwd.kone.city.smooth
import cc.txwd.kone.city.unit
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Codex's marine layer (marine-v13.js) over a city painting: wave marks on the water, boats on
 * their routes with wakes behind them, and birds crossing the sky by day. Sizes there are the
 * CSS pixels of a phone wallpaper about 300 wide; here each is [Cover.css], and [cssWidth] is
 * the wallpaper's width in them.
 */

private const val POWER = 2.6
private const val PACE = 2.35

private fun rgba(r: Int, g: Int, b: Int, alpha: Double) = Color(r, g, b, (unit(alpha) * 255).roundToInt())

private fun rgb(r: Double, g: Double, b: Double) = Color(r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255))

private val Cover.cssWidth get() = w / css

/** The marine layer's own reading of the air: the same wind, kept within its own bounds. */
private class Sea(environment: Environment) {
    val wind = environment.wind.coerceIn(.5, 1.6)
    val advection = environment.advection
    val gust = unit(environment.gust)
    val cloudCover = unit(environment.cloudCover)
    val waveGroup = unit(environment.waveGroup)
    val shimmer = unit(environment.shimmer)
    val activity = environment.activity.coerceIn(.025, 1.0)
}

fun DrawScope.drawMarine(scene: CityScene, sky: SkyState, environment: Environment, seconds: Double, x: Int, w: Int, h: Int) {
    val marine = SceneMarine.of(scene)
    val cover = Cover(x, w, h, scene)
    val sea = Sea(environment)
    val daylight = unit(sky.daylight)
    if (marine.style != null) {
        drawWaves(marine, sea, daylight, unit(sky.moonLit), cover)
        drawBoats(marine, sea, daylight, unit(sky.moonLit), seconds, cover)
    }
    drawBirds(marine, sea, daylight, seconds, cover)
}

/** The water, every outline turning the same way so they join, less the parts kept out. */
private fun DrawScope.inWater(marine: SceneMarine, cover: Cover, block: DrawScope.() -> Unit) {
    val water = Path()
    marine.water.forEach { polygon ->
        val points = polygon.points
        val area = points.indices.sumOf { i -> val next = points[(i + 1) % points.size]; points[i].x * next.y - next.x * points[i].y }
        water.addPath(Polygon(if (area < 0) points.reversed() else points).path(cover))
    }
    clipped(water, marine.exclusions.map { it.path(cover) }, block)
}

private fun DrawScope.drawWaves(marine: SceneMarine, sea: Sea, daylight: Double, illumination: Double, cover: Cover) {
    val style = marine.style ?: return
    val big = style.scale > .7
    val quiet = style.scale < .3
    val env = (.2 + illumination * .23) * (1 - daylight) + daylight
    val advected = sea.advection * PACE
    val windEnergy = .78 + sea.wind * .22 + sea.gust * .12
    val light = (.55 + (1 - sea.cloudCover) * .45) * (.8 + sea.shimmer * .2)
    val css = cover.css
    val power = minOf(POWER, 1.9)
    inWater(marine, cover) {
        marine.waves.forEachIndexed { region, marks ->
            val box = marine.water[region]
            val span = box.right - box.left
            for (wave in marks) {
                // Two carriers travel through a slower group; short ripples ride on the swell.
                val phase = advected * wave.speed * style.tempo + wave.phase
                val travel = ((wave.drift + advected * style.drift * marine.windX) % 1 + 1) % 1
                val nx = box.left + travel * span
                val groupPhase = advected * style.tempo * .18 + nx * 15 + wave.y * 29 + region * .73 + marine.seed * .001
                val group = (.28 + .72 * sin(groupPhase * .5).pow(2)) * (.76 + sea.waveGroup * .24)
                val cycle = (sin(phase + nx * 11) + 1) * .5
                val depth = unit((wave.y - box.top) / maxOf(.02, box.bottom - box.top))
                val x = cover.px(nx)
                val y = cover.py(wave.y) + ((sin(phase * .72 + nx * 8) * (.25 + style.scale * 1.3) * power * group + sin(phase * .31) * marine.windY * 1.6) * css).toFloat()
                val length = (wave.length * cover.w * (.65 + depth * .6) * style.stretch * (.85 + group * .28)).toFloat()
                val alpha = minOf(.5, (if (quiet) .09 else if (big) .17 else .125) * POWER * env * wave.weight * windEnergy * group)
                val swell = ((.15 + style.scale * 1.7) * power * sin(phase + nx * 19) * wave.bend * group * css).toFloat()
                val crest = Path().apply {
                    moveTo(x - length * .5f, y)
                    cubicTo(x - length * .18f, y + swell, x + length * .15f, y - swell * .55f, x + length * .5f, y + swell * .18f)
                }
                drawPath(
                    crest,
                    Brush.horizontalGradient(
                        0f to rgba(239, 242, 232, 0.0), .25f to rgba(239, 242, 232, alpha * .35), .48f to rgba(241, 243, 235, alpha * light),
                        .75f to rgba(239, 242, 232, alpha * .45), 1f to rgba(239, 242, 232, 0.0), startX = x - length * .5f, endX = x + length * .5f,
                    ),
                    style = Stroke(width = ((cover.cssWidth / 490).coerceIn(.6, 1.4) * (if (wave.crest) 1.15 else .75) * css).toFloat(), cap = StrokeCap.Round),
                )
                if (!quiet || wave.layer == 0) {
                    val trough = Path().apply {
                        moveTo(x - length * .4f, y + (1.2 * css).toFloat())
                        quadraticTo(x, y + swell * .45f + (1.4 * css).toFloat(), x + length * .36f, y + (1.1 * css).toFloat())
                    }
                    drawPath(trough, rgba(45, 76, 85, alpha * (if (quiet) .18 else .28)), style = Stroke(width = ((cover.cssWidth / 610).coerceIn(.4, 1.0) * css).toFloat(), cap = StrokeCap.Round))
                }
                if (wave.layer != 2) {
                    val ripplePhase = phase * 2.6 + wave.glint * TAU
                    val shimmer = sin(ripplePhase + sea.shimmer * 2.1).pow(2)
                    val offset = (sin(phase * .61 + wave.packet) * length * .19).toFloat()
                    val short = length * (.18f + style.ripple.toFloat() * .21f)
                    val ry = y + (cos(ripplePhase * .53) * (1 + depth * 1.3) * css).toFloat()
                    val ripple = Path().apply {
                        moveTo(x + offset - short * .5f, ry)
                        quadraticTo(x + offset, ry + (sin(ripplePhase) * .65 * css).toFloat(), x + offset + short * .5f, ry)
                    }
                    drawPath(ripple, rgba(239, 245, 235, alpha * style.ripple * (.25 + shimmer * .75) * light), style = Stroke(width = ((cover.cssWidth / 780).coerceIn(.4, .85) * css).toFloat(), cap = StrokeCap.Round))
                }
                if (wave.crest && cycle > .72) {
                    val burst = smooth(.72, .94, cycle) * group
                    val foam = Path().apply {
                        moveTo(x - length * .2f, y + swell * .18f)
                        cubicTo(x - length * .05f, y + swell * .45f - (.5 * css).toFloat(), x + length * .1f, y - swell * .22f, x + length * .24f, y + swell * .15f)
                    }
                    drawPath(foam, rgba(249, 249, 239, minOf(.42, burst * .27 * POWER * env * windEnergy)), style = Stroke(width = ((cover.cssWidth / 520).coerceIn(.7, 1.35) * css).toFloat(), cap = StrokeCap.Round))
                    // Only open water breaks into a few beads of foam.
                    if (big && burst > .65) {
                        for (j in 0..1) {
                            val rx = maxOf(.45 * css, length * .015.toDouble()).toFloat()
                            val ry = (.4 * css).toFloat()
                            val cx = x + length * (j * .14f - .07f)
                            val cy = y + ((1.2 + j * .35) * css).toFloat()
                            drawOval(rgba(245, 248, 235, burst * .16 * env), Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2))
                        }
                    }
                }
            }
        }
    }
}

private class Pose(val x: Float, val y: Float, val ux: Float, val uy: Float, val roll: Double)

private fun pose(marine: SceneMarine, route: BoatRoute, progress: Double, time: Double, cover: Cover): Pose {
    val p = route.at(progress)
    val lengthCss = route.width * cover.cssWidth
    val power = minOf(POWER, 1.8)
    val roughness = .25 + (marine.style?.scale ?: .2) * .75
    val roll = sin(time * (if (marine.type == "bay") 1.2 else .83) * PACE + route.phase * TAU) * .042 * power * roughness
    val bob = sin(time * 1.13 * PACE + route.phase * 4) * minOf(1.4, lengthCss * .04) * power * roughness * cover.css
    val turn = roll * (if (route.kind == "sailboat") .8 else .4)
    return Pose(
        cover.px(p.x), (cover.py(p.y) + bob).toFloat(),
        (p.ux * cos(turn) - p.uy * sin(turn)).toFloat(), (p.uy * cos(turn) + p.ux * sin(turn)).toFloat(), roll,
    )
}

private fun frac(value: Double) = ((value % 1) + 1) % 1

private fun DrawScope.drawBoats(marine: SceneMarine, sea: Sea, daylight: Double, illumination: Double, time: Double, cover: Cover) {
    for (route in marine.routes) {
        val progress = frac(route.travel(time))
        val entry = smooth(0.0, .075, progress)
        val exit = 1 - smooth(.925, 1.0, progress)
        val leisure = route.kind in setOf("rowboat", "gondola", "sailboat", "yacht")
        val operating = if (leisure) sea.activity else maxOf(.5, sea.activity)
        val opacity = entry * exit * (if (!route.night) smooth(.12, .4, daylight) else 1.0) * operating
        if (opacity < .002) continue
        drawWake(marine, route, progress, opacity, daylight, time, cover)
        drawVessel(marine, route, progress, opacity, daylight, illumination, time, cover)
    }
}

private fun DrawScope.drawWake(marine: SceneMarine, route: BoatRoute, progress: Double, opacity: Double, daylight: Double, time: Double, cover: Cover) {
    val css = cover.css
    val motor = route.motor
    val lookBack = if (motor) 8.0 else if (route.kind == "gondola" || route.kind == "rowboat") 3.2 else 5.0
    val cycle = floor(route.travel(time))
    val speed = route.speed(time)
    val length = (route.width * cover.w).toFloat()
    val halfBeam = length * (if (motor) .15f else .085f)
    val boat = pose(marine, route, progress, time, cover)
    /** Where the boat was [age] seconds ago on this crossing, or null before it began. */
    fun past(age: Double): Pose? {
        if (age > time) return null
        val travel = route.travel(time - age)
        if (floor(travel) != cycle) return null
        return pose(marine, route, frac(travel), time - age, cover)
    }
    class Mark(val x: Float, val y: Float, val age: Float)
    val sides = listOf(mutableListOf<Mark>(), mutableListOf())
    val count = 23
    for (i in 0..count) {
        val age = i.toDouble() / count * lookBack
        val p = past(age) ?: break
        val spread = halfBeam + (age * length * (if (route.kind == "yacht") .105 else if (motor) .073 else .038)).toFloat()
        val wobble = (sin(time * 1.8 - age * 2.7 + route.phase * TAU) * (.25 + POWER * .22) * css).toFloat()
        for ((side, sign) in listOf(0 to -1f, 1 to 1f)) {
            sides[side] += Mark(p.x - p.ux * halfBeam - p.uy * (spread + wobble) * sign, p.y - p.uy * halfBeam + p.ux * (spread + wobble) * sign, i.toFloat() / count)
        }
    }
    inWater(marine, cover) {
        val strength = (if (route.kind == "yacht") .53 else if (motor) .39 else .23) * (.75 + speed * .25)
        val width = ((cover.cssWidth / 550).coerceIn(.65, 1.4) * (if (motor) 1.2 else .8) * css).toFloat()
        for (marks in sides) for (i in 1 until marks.size) {
            val a = marks[i - 1]
            val b = marks[i]
            drawLine(rgba(241, 244, 234, opacity * strength * (.3 + daylight * .7) * (1.0 - b.age).pow(1.6)), Offset(a.x, a.y), Offset(b.x, b.y), width, StrokeCap.Round)
        }
        if (motor) {
            // Broken churn along the way it came, turns and all.
            for (i in 1 until 16) {
                val p = past(i / 16.0 * lookBack * .8) ?: continue
                val f = 1 - i / 16.0
                val side = (sin(time * 3.1 - i * 1.7) * halfBeam * (.35 + i * .025)).toFloat()
                val rx = maxOf(.4 * css, halfBeam * (.5 + i * .03)).toFloat()
                val ry = maxOf(.35 * css, halfBeam * .25.toDouble()).toFloat()
                val cx = p.x - p.uy * side
                val cy = p.y + p.ux * side
                rotateRad(atan2(p.uy, p.ux), pivot = Offset(cx, cy)) {
                    drawOval(rgba(246, 247, 236, opacity * .25 * f * (.35 + daylight * .65)), Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2))
                }
            }
            for (i in 1..5) {
                val age = lookBack * (.18 + i * .13)
                val p = past(age) ?: continue
                val fade = (1 - age / lookBack).pow(2)
                val spread = halfBeam * (1.5f + (age * .15).toFloat())
                val ripple = Path().apply {
                    moveTo(p.x - p.uy * spread - p.ux * length * .09f, p.y + p.ux * spread - p.uy * length * .09f)
                    quadraticTo(p.x - p.ux * length * .13f, p.y - p.uy * length * .13f, p.x + p.uy * spread - p.ux * length * .09f, p.y - p.ux * spread - p.uy * length * .09f)
                }
                drawPath(ripple, rgba(229, 240, 230, opacity * .16 * fade * (.35 + daylight * .65)), style = Stroke(width = (.55 * css).toFloat(), cap = StrokeCap.Round))
            }
        }
        // A gentle bow wave.
        val bowX = boat.x + boat.ux * length * .48f
        val bowY = boat.y + boat.uy * length * .48f
        val bow = Path().apply {
            moveTo(bowX - boat.uy * halfBeam * 1.6f, bowY + boat.ux * halfBeam * 1.6f)
            quadraticTo(bowX + boat.ux * length * .12f, bowY + boat.uy * length * .12f, bowX + boat.uy * halfBeam * 1.6f, bowY - boat.ux * halfBeam * 1.6f)
        }
        drawPath(bow, rgba(245, 247, 238, opacity * (if (motor) .4 else .2) * (.4 + daylight * .6)), style = Stroke(width = ((cover.cssWidth / 600).coerceIn(.5, 1.1) * css).toFloat(), cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawVessel(marine: SceneMarine, route: BoatRoute, progress: Double, opacity: Double, daylight: Double, illumination: Double, time: Double, cover: Cover) {
    val css = cover.css
    val p = pose(marine, route, progress, time, cover)
    val roll = p.roll
    val length = (route.width * cover.w).toFloat()
    val beam = when (route.kind) {
        "ferry", "canalboat", "yacht" -> length * .165f
        "gondola" -> length * .085f
        else -> length * .115f
    }
    fun at(along: Float, across: Float, lift: Float = 0f) = Offset(p.x + p.ux * along - p.uy * across, p.y + p.uy * along + p.ux * across - lift)
    fun shape(a: Offset, b: Offset, c: Offset, d: Offset? = null, e: Offset? = null, f: Offset? = null) = Path().apply {
        moveTo(a.x, a.y)
        for (o in listOfNotNull(b, c, d, e, f)) lineTo(o.x, o.y)
        close()
    }
    val brightness = (.21 + illumination * .12) * (1 - daylight) + daylight
    val shown = opacity * (.72 + brightness * .28)
    /** Every colour of the boat, as faint as the boat is. */
    fun c(color: Color, alpha: Double = 1.0) = color.copy(alpha = (color.alpha * unit(alpha) * shown).toFloat().coerceIn(0f, 1f))
    fun stroke(width: Double) = Stroke(width = width.toFloat(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val ink = 68 + daylight * 40
    val hull = shape(at(length * .5f, 0f), at(length * .18f, beam), at(-length * .43f, beam * .8f), at(-length * .5f, 0f), at(-length * .41f, -beam * .8f), at(length * .2f, -beam))
    val hullColor = when (route.kind) {
        "yacht" -> rgb(157 + daylight * 86, 161 + daylight * 82, 154 + daylight * 83)
        "gondola" -> rgb(35 + daylight * 18, 42 + daylight * 17, 42 + daylight * 15)
        else -> rgb(ink, ink + 5, ink + 3)
    }
    drawPath(hull, c(hullColor))
    drawPath(hull, c(rgba(224, 225, 211, .38 + daylight * .26)), style = stroke(maxOf(.36 * css, length * .028.toDouble())))
    when (route.kind) {
        "sailboat" -> {
            val mast = at(-length * .055f, 0f, length * .055f)
            val top = Offset(mast.x + (roll * length).toFloat(), mast.y - length * .81f)
            val windSide = if (marine.windX < 0) -1f else 1f
            val sheet = length * .43f * windSide
            val cream = 215 + daylight * 26
            val seam = rgba(126, 134, 128, .25 + daylight * .14)
            val main = shape(Offset(top.x + windSide * length * .025f, top.y + length * .07f), Offset(mast.x + sheet, mast.y - length * .06f), Offset(mast.x + windSide * length * .025f, mast.y - length * .035f))
            drawPath(main, c(rgb(cream, cream + 1, cream - 9)))
            drawPath(main, c(seam), style = stroke(maxOf(.32 * css, length * .018.toDouble())))
            val jib = shape(Offset(top.x - windSide * length * .025f, top.y + length * .19f), Offset(mast.x - sheet * .64f, mast.y - length * .08f), Offset(mast.x - windSide * length * .026f, mast.y - length * .045f))
            drawPath(jib, c(rgb(cream - 12, cream - 8, cream - 17)))
            drawPath(jib, c(seam), style = stroke(maxOf(.32 * css, length * .018.toDouble())))
            drawLine(c(rgba(83, 94, 90, .66 + daylight * .23)), mast, top, maxOf(.4 * css, length * .025.toDouble()).toFloat(), StrokeCap.Round)
            drawLine(c(rgba(92, 100, 93, .28 + daylight * .12)), Offset(top.x, top.y + length * .2f), at(length * .42f, 0f), maxOf(.3 * css, length * .013.toDouble()).toFloat(), StrokeCap.Round)
        }
        "gondola" -> {
            // One gondolier rows standing at the stern; only the power stroke rings the water.
            val dark = c(rgba(40, 47, 46, .8 + daylight * .15))
            val prow = Path().apply { val a = at(length * .46f, 0f); val b = at(length * .57f, 0f, length * .04f); val e = at(length * .54f, 0f, length * .13f); moveTo(a.x, a.y); quadraticTo(b.x, b.y, e.x, e.y) }
            val stern = Path().apply { val a = at(-length * .45f, 0f); val b = at(-length * .55f, 0f, length * .04f); val e = at(-length * .51f, 0f, length * .09f); moveTo(a.x, a.y); quadraticTo(b.x, b.y, e.x, e.y) }
            drawPath(prow, dark, style = stroke(maxOf(.6 * css, length * .055.toDouble())))
            drawPath(stern, dark, style = stroke(maxOf(.6 * css, length * .055.toDouble())))
            val strokeTime = (time * .34 * PACE + route.phase * 2) % 1
            val drive = strokeTime < .62
            val swing = (if (drive) -.2 + strokeTime / .62 * .38 else .18 - (strokeTime - .62) / .38 * .38).toFloat()
            val hand = at(-length * .27f, length * .025f, length * .24f)
            val blade = at(-length * .24f + swing * length, length * .61f, if (drive) 0f else (length * .09 * sin((strokeTime - .62) / .38 * PI)).toFloat())
            val shoulder = at(-length * .29f + (sin(strokeTime * TAU) * length * .018).toFloat(), 0f, length * .34f)
            val hip = at(-length * .32f, 0f, length * .15f)
            val head = at(-length * .29f, 0f, length * .42f)
            val body = c(rgba(41, 49, 48, .8 + daylight * .2))
            val limb = maxOf(.55 * css, length * .047.toDouble()).toFloat()
            drawPath(Path().apply { moveTo(hip.x, hip.y); lineTo(shoulder.x, shoulder.y); lineTo(hand.x, hand.y) }, body, style = stroke(limb.toDouble()))
            drawLine(body, hip, at(-length * .35f, length * .04f), limb, StrokeCap.Round)
            drawLine(body, hip, at(-length * .29f, -length * .04f), limb, StrokeCap.Round)
            drawCircle(c(rgba(42, 49, 47, .85 + daylight * .15)), maxOf(.5 * css, length * .041.toDouble()).toFloat(), head)
            val oar = c(rgba(102, 90, 69, .7 + daylight * .27))
            drawLine(oar, hand, blade, maxOf(.42 * css, length * .025.toDouble()).toFloat(), StrokeCap.Round)
            drawLine(oar, blade, at(-length * .24f + swing * length + length * .045f, length * .66f, if (drive) 0f else length * .09f), maxOf(.7 * css, length * .047.toDouble()).toFloat(), StrokeCap.Round)
            if (drive) {
                inWater(marine, cover) {
                    val ringAge = ((strokeTime / .62 + .1) % 1).toFloat()
                    val ring = at(-length * .24f + swing * length, length * .66f)
                    val rx = length * (.025f + ringAge * .065f)
                    val ry = length * (.012f + ringAge * .025f)
                    drawOval(rgba(243, 245, 234, opacity * .38 * (1 - ringAge) * (.4 + daylight * .6)), Offset(ring.x - rx, ring.y - ry), Size(rx * 2, ry * 2), style = Stroke((.6 * css).toFloat()))
                }
            }
        }
        "rowboat" -> {
            val strokeTime = (time * .42 * PACE + route.phase * 2) % 1
            val sweep = (sin(strokeTime * TAU) * length * .22).toFloat()
            val seat = at(-length * .06f, 0f, length * .08f)
            val oar = c(rgba(116, 96, 69, .7 + daylight * .25))
            for (side in listOf(-1f, 1f)) {
                val hand = at(-length * .08f, beam * side * .5f, length * .15f)
                val blade = at(length * .03f + sweep, side * length * .5f)
                drawLine(oar, hand, blade, maxOf(.45 * css, length * .032.toDouble()).toFloat(), StrokeCap.Round)
                if (strokeTime < .5) {
                    inWater(marine, cover) {
                        val rx = length * .05f
                        val ry = length * .022f
                        drawOval(rgba(240, 243, 232, opacity * .22 * sin(strokeTime * PI * 2)), Offset(blade.x - rx, blade.y - ry), Size(rx * 2, ry * 2), style = Stroke((.55 * css).toFloat()))
                    }
                }
            }
            drawOval(c(rgba(88, 106, 103, .8 + daylight * .18)), Offset(seat.x - length * .065f, seat.y - length * .065f - length * .09f), Size(length * .13f, length * .18f))
            drawCircle(c(rgba(123, 110, 90, .8 + daylight * .18)), maxOf(.5 * css, length * .046.toDouble()).toFloat(), at(-length * .07f, 0f, length * .23f))
        }
        "yacht" -> {
            val cabinHeight = length * .13f
            val cabin = shape(at(length * .24f, -beam * .67f, cabinHeight * .7f), at(length * .13f, beam * .65f, cabinHeight), at(-length * .28f, beam * .65f, cabinHeight), at(-length * .35f, -beam * .65f, cabinHeight))
            drawPath(cabin, c(rgb(158 + daylight * 85, 162 + daylight * 81, 159 + daylight * 77)))
            drawPath(cabin, c(rgba(207, 222, 219, .45 + daylight * .35)), style = stroke(maxOf(.5 * css, length * .035.toDouble())))
            val glass = shape(at(length * .2f, -beam * .58f, cabinHeight * .75f), at(length * .095f, beam * .58f, cabinHeight * .92f), at(-length * .07f, beam * .58f, cabinHeight * .92f), at(-length * .02f, -beam * .58f, cabinHeight * .75f))
            drawPath(glass, c(rgba(69, 110, 122, .5 + daylight * .16)))
            val rail = Path().apply {
                val a = at(length * .32f, -beam * .55f, length * .03f); val b = at(length * .43f, 0f, length * .03f); val e = at(length * .28f, beam * .62f, length * .03f)
                moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(e.x, e.y)
            }
            drawPath(rail, c(rgba(231, 236, 221, .6 + daylight * .3)), style = stroke(maxOf(.4 * css, length * .025.toDouble())))
            if (daylight < .45) drawCircle(c(rgba(245, 215, 155, .8 * (1 - daylight / .45))), maxOf(.45 * css, length * .025.toDouble()).toFloat(), at(-length * .14f, beam * .6f, length * .09f))
        }
        else -> {
            val cabinHeight = length * (if (route.kind == "canalboat") .105f else .16f)
            val cabin = shape(at(length * .22f, -beam * .6f, cabinHeight), at(length * .22f, beam * .6f, cabinHeight), at(-length * .28f, beam * .6f, cabinHeight), at(-length * .28f, -beam * .6f, cabinHeight))
            drawPath(cabin, c(rgb(150 + daylight * 70, 154 + daylight * 68, 145 + daylight * 65)))
            drawPath(cabin, c(rgba(95, 108, 105, .4 + daylight * .22)), style = stroke(maxOf(.35 * css, length * .025.toDouble())))
            if (route.kind == "canalboat") {
                // A low glazed sightseeing boat.
                val roof = shape(at(length * .19f, -beam * .58f, cabinHeight * 1.02f), at(length * .19f, beam * .56f, cabinHeight * 1.02f), at(-length * .26f, beam * .56f, cabinHeight * 1.02f), at(-length * .26f, -beam * .58f, cabinHeight * 1.02f))
                drawPath(roof, c(rgba(89, 128, 132, .36 + daylight * .23)))
                for (j in 0 until 4) {
                    val along = -length * .24f + j * length * .135f
                    drawLine(c(rgba(224, 231, 218, .42 + daylight * .25)), at(along, -beam * .6f, cabinHeight * 1.05f), at(along, beam * .6f, cabinHeight * 1.05f), maxOf(.35 * css, length * .018.toDouble()).toFloat(), StrokeCap.Round)
                }
            }
            drawLine(c(rgba(66, 89, 94, .28 + daylight * .22)), at(-length * .25f, beam * .65f, cabinHeight * .62f), at(length * .2f, beam * .65f, cabinHeight * .62f), maxOf(.5 * css, length * .041.toDouble()).toFloat(), StrokeCap.Round)
            if (daylight < .55) {
                val lamp = rgba(242, 208, 144, (1 - smooth(.08, .55, daylight)) * .84)
                for (j in 0 until 3) {
                    val o = at(-length * .18f + j * length * .13f, beam * .68f, cabinHeight * .6f)
                    val rx = maxOf(.4 * css, length * .024.toDouble()).toFloat()
                    val ry = maxOf(.32 * css, length * .02.toDouble()).toFloat()
                    drawOval(c(lamp), Offset(o.x - rx, o.y - ry), Size(rx * 2, ry * 2))
                }
            }
        }
    }
}

private fun DrawScope.drawBirds(marine: SceneMarine, sea: Sea, daylight: Double, time: Double, cover: Cover) {
    val shown = smooth(.17, .4, daylight) * sea.activity
    if (marine.birds.isEmpty() || shown < .003) return
    val f = marine.flight
    if (f.right <= f.left || f.bottom <= f.top) return
    val sky = Path().apply { addRect(androidx.compose.ui.geometry.Rect(cover.px(f.left), cover.py(f.top), cover.px(f.right), cover.py(f.bottom))) }
    clipped(sky, marine.skyExclusions.map { it.path(cover) }) {
        for (bird in marine.birds) {
            val driftTime = time + .38 * sin(time * .14 + bird.phase * TAU)
            val progress = frac(driftTime * PACE / bird.period + bird.phase)
            val localX = if (bird.direction > 0) -.08 + progress * 1.16 else 1.08 - progress * 1.16
            val sizeCss = (cover.cssWidth * bird.size).coerceIn(2.0, 5.6)
            val marginX = sizeCss * 1.5 / cover.cssWidth
            val marginY = sizeCss * 1.5 / (cover.height / cover.css)
            val band = f.bottom - f.top
            if (band < marginY * 2) continue
            val lane = f.top + (.2 + bird.lane * .6) * band
            val arc = (sin(progress * PI) * .08 + sin(time * .24 + bird.phase * TAU) * .07) * band
            val localY = (lane + arc).coerceIn(f.top + marginY, f.bottom - marginY)
            if (localX - marginX < f.left || localX + marginX > f.right) continue
            if (marine.skyExclusions.any { cc.txwd.kone.city.Point(localX, localY) in it || SceneMarine.touches(it, localX - marginX, localY - marginY, localX + marginX, localY + marginY) }) continue
            val fade = smooth(f.left + marginX, f.left + marginX + .025, localX) * (1 - smooth(f.right - marginX - .025, f.right - marginX, localX))
            if (fade < .003) continue
            // A short burst of beats, then a longer glide; banking foreshortens one wing.
            val beatCycle = frac(time / bird.beatPeriod + bird.flap)
            val envelope = smooth(0.0, .045, beatCycle) * (1 - smooth(bird.beatDuty - .055, bird.beatDuty, beatCycle))
            val flap = sin(time * bird.beatRate + bird.flap * 7) * envelope * (.85 + sea.gust * .15)
            val bank = sin(time * .31 + bird.phase * TAU) * bird.bank + sin(time * .1 + bird.flap) * .035
            val glide = .06 * sin(time * .8 + bird.flap)
            val size = (sizeCss * cover.css).toFloat()
            val tipY = ((-.13 + flap * .5 + glide * (1 - envelope)) * size).toFloat()
            val near = (1 + bank * .8).toFloat()
            val far = (1 - bank * .8).toFloat()
            val alpha = shown * fade * (.38 + bird.size * 14)
            val b = bank.toFloat()
            withTransform({
                translate(cover.px(localX), cover.py(localY))
                rotateRad((bank + bird.direction * .045).toFloat(), pivot = Offset.Zero)
            }) {
                val wings = Path().apply {
                    moveTo(-size * near, tipY + b * size * .14f)
                    cubicTo(-size * .6f * near, tipY - size * .16f, -size * .28f, -size * .22f, 0f, 0f)
                    cubicTo(size * .27f, -size * .17f, size * .63f * far, tipY - size * .12f, size * far, tipY + size * .035f - b * size * .14f)
                }
                drawPath(wings, rgba(83, 101, 107, alpha), style = Stroke(width = maxOf(.55f * cover.css.toFloat(), size * .16f), cap = StrokeCap.Round, join = StrokeJoin.Round))
                rotateRad((-bird.direction * .2).toFloat(), pivot = Offset(0f, size * .025f)) {
                    drawOval(rgba(80, 98, 104, alpha), Offset(-size * .18f, size * .025f - size * .085f), Size(size * .36f, size * .17f))
                }
            }
        }
    }
}
