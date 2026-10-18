package cc.txwd.kone.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.translate
import cc.txwd.kone.city.CityScene
import cc.txwd.kone.city.Environment
import cc.txwd.kone.city.SceneLife
import cc.txwd.kone.city.TAU
import cc.txwd.kone.city.WeatherProfile
import cc.txwd.kone.city.smooth
import cc.txwd.kone.city.unit
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Codex's city-life layer (city-life-v13.js) over a city painting: grass and canopies moving in
 * the wind, heat over hot ground, glass catching the sun, foam on the shore, accents glowing at
 * night, cars and walkers on their errands, drips from the eaves in the rain, fountains and
 * turning wheels. Sizes there are the CSS pixels of a phone wallpaper about 300 wide.
 */

private const val POWER = 1.8

private fun rgba(r: Int, g: Int, b: Int, alpha: Double) = Color(r, g, b, (unit(alpha) * 255).roundToInt())

private fun hex(text: String?, fallback: Color): Color =
    text?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: fallback

/** What the street feels of the weather: the real weather when there is some, the wind always. */
private class Street(weather: WeatherProfile?, environment: Environment) {
    val rain = weather?.rain ?: 0.0
    val fog = weather?.fog ?: 0.0
    val snow = weather?.snow ?: 0.0
    val wetness = weather?.wetness ?: (rain * .9)
    val wind = (weather?.wind ?: environment.wind).coerceIn(0.0, 3.0)
    val windPhase = environment.advection
    val windX = .65
    val gust = unit(environment.gust)
    val cloudCover = unit(weather?.cloudCover ?: environment.cloudCover)
    val temperature = weather?.temperature
    val fogVisibility = 1 - smooth((fog - .35) / .36)
    val directVisibility = fogVisibility * (1 - (weather?.cloudCover ?: 0.0) * .82) * (1 - rain * .72) * (1 - snow * .55)
}

fun DrawScope.drawLife(scene: CityScene, painting: PanelPainting, daylight: Double, environment: Environment, seconds: Double, x: Int, w: Int, h: Int) {
    val life = SceneLife.of(scene)
    if (!life.reviewed) return
    val cover = Cover(x, w, h, scene)
    val street = Street(environment.weather, environment)
    val day = unit(daylight)
    drawGround(life, painting, day, street, seconds, cover)
    drawTrees(life, painting, day, street, seconds, cover)
    drawGlass(life, day, street, seconds, cover)
    drawShore(life, day, street, seconds, cover)
    drawAccents(life, day, seconds, cover)
    drawTraffic(life, day, street, seconds, cover)
    drawWalkers(life, day, street, seconds, cover)
    drawDrips(life, day, street, seconds, cover)
    drawJets(life, day, street, seconds, cover)
    drawWheels(life, day, seconds, cover)
}

/** The painting itself, moved a little: how grass, canopies and hot air move without anything new drawn. */
private fun DrawScope.paint(painting: PanelPainting, cover: Cover, dx: Double, dy: Double) {
    drawImage(painting.image, topLeft = Offset((cover.x + dx).toFloat(), dy.toFloat()))
}

private fun DrawScope.drawGround(life: SceneLife, painting: PanelPainting, day: Double, street: Street, time: Double, cover: Cover) {
    val css = cover.css
    life.grounds.forEachIndexed { i, g ->
        if (g.kind == "grass") {
            clipped(g.polygon.path(cover), emptyList()) {
                val phase = time * TAU / (g.period ?: 7.0).coerceIn(3.0, 30.0) + (g.phase ?: i.toDouble()) * TAU
                paint(painting, cover, sin(phase) * cover.w * .0018 * street.wind * g.gain, 0.0)
            }
            return@forEachIndexed
        }
        if (street.rain > .04 || street.snow > .02 || street.fog > .45 || street.wetness > .4 || street.wind < .85) return@forEachIndexed
        val p = g.polygon
        val phase = street.windPhase / (g.period ?: 11.0).coerceIn(4.0, 40.0) + (g.phase ?: 0.0)
        val color = if (day > .3) rgba(196, 163, 115, (.07 + .05 * street.gust) * g.gain) else rgba(157, 155, 142, (.07 + .05 * street.gust) * g.gain)
        clipped(p.path(cover), emptyList()) {
            for (k in 0 until 32) {
                val f = (((phase + k * .618) % 1) + 1) % 1
                val y = p.top + (p.bottom - p.top) * (.5 + .47 * sin(k * 2.37))
                val x = p.left + (p.right - p.left) * (if (g.flow.first >= 0) f else 1 - f)
                val from = Offset(cover.px(x), cover.py(y))
                drawLine(color, from, Offset(from.x + (g.flow.first * cover.w * .014).toFloat(), from.y + (g.flow.second * cover.height * .003).toFloat()), (.55 * css).toFloat())
            }
        }
    }
    if (day < .7 || street.rain > .02 || street.snow > .02 || street.fog > .35 || street.cloudCover > .45) return
    life.heats.forEachIndexed { i, heat ->
        val temperature = street.temperature ?: return@forEachIndexed
        if (temperature < heat.minTemperature) return@forEachIndexed
        clipped(heat.polygon.path(cover), emptyList()) {
            val step = (heat.polygon.bottom - heat.polygon.top) / 9
            for (k in 0 until 9) {
                val strip = Path().apply { addRect(Rect(cover.px(0.0), cover.py(heat.polygon.top + k * step), cover.px(1.0), cover.py(heat.polygon.top + (k + 1) * step))) }
                clipped(strip, emptyList()) { paint(painting, cover, sin(time * TAU / heat.period + k * .8 + i) * cover.w * .0018 * heat.gain, 0.0) }
            }
        }
    }
}

private fun DrawScope.drawTrees(life: SceneLife, painting: PanelPainting, day: Double, street: Street, time: Double, cover: Cover) {
    life.trees.forEachIndexed { i, t ->
        val cx = cover.px(t.center.x)
        val cy = cover.py(t.center.y)
        val rx = t.rx * cover.w
        val ry = t.ry * cover.height
        val gust = (.34 + street.gust * .66) * POWER
        val phase = t.phase?.let { it * TAU } ?: (i * 1.73)
        val rate = t.period?.let { TAU / it.coerceIn(2.0, 30.0) } ?: (if (t.kind == "palm") 1.4 else 1.08)
        val sway = sin(time * rate + phase) * (.5 + .5 * sin(time * .17 + phase)) + sin(time * 2.6 + phase) * .17
        val dx = min(rx * .17, cover.w * .0045) * sway * gust * street.windX * t.gain
        val dy = sin(time * 1.1 + i) * min(ry * .075, cover.height * .0005) * gust
        // Only the canopy moves: trunk, buildings and shore stay where they are painted.
        val crown = Path().apply { addOval(Rect((cx - rx).toFloat(), (cy - ry).toFloat(), (cx + rx).toFloat(), (cy + ry).toFloat())) }
        clipped(crown, emptyList()) { paint(painting, cover, dx, dy) }
        if (day > .15 && t.kind == "palm") {
            val leaf = rgba(66, 96, 80, .18 * day)
            val width = (maxOf(.45, cover.w / cover.css * .0011) * cover.css).toFloat()
            for (k in 0 until 5) {
                val a = k / 5.0 * TAU - .7
                val frond = Path().apply {
                    moveTo((cx + dx * .4).toFloat(), cy)
                    quadraticTo((cx + cos(a) * rx * .55 + dx).toFloat(), (cy + sin(a) * ry * .3).toFloat(), (cx + cos(a) * rx * .9 + dx).toFloat(), (cy + sin(a) * ry * .7 + kotlin.math.abs(sway) * ry * .1).toFloat())
                }
                drawPath(frond, leaf, style = Stroke(width))
            }
        }
    }
}

private fun DrawScope.drawGlass(life: SceneLife, day: Double, street: Street, time: Double, cover: Cover) {
    if (day < .15 || street.directVisibility <= 0) return
    life.facades.forEachIndexed { i, facade ->
        val p = facade.polygon
        val progress = (time / (facade.period ?: (31.0 + i * 7)).coerceIn(12.0, 180.0) + (facade.phase ?: (i * .31))) % 1
        val bright = sin(progress * PI).pow(4)
        val x = cover.px(p.left + (p.right - p.left) * progress)
        val span = ((p.right - p.left) * cover.w * .6).toFloat()
        val alpha = day * street.directVisibility * bright * .28 * facade.gain * sqrt(POWER)
        clipped(p.path(cover), emptyList()) {
            drawRect(
                Brush.horizontalGradient(0f to rgba(255, 250, 222, 0.0), .5f to rgba(255, 250, 222, alpha), 1f to rgba(255, 250, 222, 0.0), startX = x - span, endX = x + span),
                topLeft = Offset(cover.px(p.left), cover.py(p.top)), size = Size(cover.px(p.right) - cover.px(p.left), cover.py(p.bottom) - cover.py(p.top)),
            )
        }
    }
}

private fun DrawScope.drawShore(life: SceneLife, day: Double, street: Street, time: Double, cover: Cover) {
    val css = cover.css
    life.shores.forEachIndexed { index, shore ->
        clipped(shore.water.path(cover), emptyList()) {
            val bands = 5
            for (band in 0 until bands) {
                val phase = (time / (shore.period ?: (8 + index * 1.3)).coerceIn(4.0, 30.0) + band.toDouble() / bands + shore.phase) % 1
                val approach = (1 - cos(phase * TAU)) / 2
                val alpha = sin(phase * PI).pow(2) * (.18 + street.gust * .12) * maxOf(.3, day) * shore.gain
                val foam = Path()
                shore.points.forEachIndexed { i, point ->
                    val offset = shore.reach * (1 - approach)
                    val px = cover.px(point.x + shore.flow.first * offset)
                    val py = cover.py(point.y + shore.flow.second * offset)
                    if (i == 0) foam.moveTo(px, py) else foam.lineTo(px, py)
                }
                drawPath(foam, rgba(246, 251, 247, alpha), style = Stroke((maxOf(.55, cover.height / css * .00065) * (1 + street.gust * .6) * css).toFloat()))
            }
            // Small caustics in the shallows.
            val shallow = shore.shallow
            if (shallow != null && day > .25 && street.directVisibility > .001) {
                clipped(shallow.path(cover), emptyList()) {
                    for (k in 0 until 22) {
                        val f = (((k * .61803398875 + .07 * sin(time * .09 + k)) % 1) + 1) % 1
                        val x = cover.px(shallow.left + (shallow.right - shallow.left) * f)
                        val y = cover.py(shallow.top + (shallow.bottom - shallow.top) * (.5 + .43 * sin(k * 2.8 + time * .17)))
                        val a = .16 * day * street.directVisibility * (.5 + .5 * sin(time * 1.2 + k))
                        val rx = (cover.w * (.006 + .004 * sin(time * .5 + k))).toFloat()
                        val ry = (cover.height * .0008).toFloat()
                        drawArc(rgba(244, 255, 235, a), 0f, 270f, false, Offset(x - rx, y - ry), Size(rx * 2, ry * 2), style = Stroke((.7 * css).toFloat()))
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawAccents(life: SceneLife, day: Double, time: Double, cover: Cover) {
    val night = 1 - day
    if (night < .05) return
    life.accents.forEachIndexed { i, a ->
        val p = a.polygon
        val phase = time / (a.period ?: (37.0 + i * 5)).coerceIn(18.0, 180.0) + (a.phase ?: (i * .29))
        val pulse = .65 + .35 * sin(phase * TAU)
        val alpha = night * (.036 + .085 * pulse) * a.power * sqrt(POWER)
        val (r, g, b) = a.color
        val from = Offset(cover.px(p.left), cover.py(p.top))
        val to = Offset(cover.px(p.right), cover.py(p.bottom))
        clipped(p.path(cover), emptyList()) {
            drawRect(
                Brush.linearGradient(0f to rgba(r, g, b, alpha * .3), .55f to rgba(r, g, b, alpha), 1f to rgba(r, g, b, alpha * .4), start = from, end = to),
                topLeft = from, size = Size(to.x - from.x, to.y - from.y),
            )
        }
    }
}

private fun DrawScope.drawTraffic(life: SceneLife, day: Double, street: Street, time: Double, cover: Cover) {
    if (life.traffic.isEmpty()) return
    val css = cover.css
    val roads = Path().apply { life.roads.forEach { addPath(it.path(cover)) } }
    clipped(roads, emptyList()) {
        for (route in life.traffic) {
            val p = life.position(route, time) ?: continue
            val angle = atan2(p.dy * cover.height, p.dx * cover.w).toFloat()
            val length = (route.size * cover.w).toFloat()
            val fade = p.fade.toFloat()
            translate(cover.px(p.x), cover.py(p.y)) {
                rotateRad(angle, pivot = Offset.Zero) {
                    val body = if (day > .5) hex(route.color, Color(0xFF657984)) else Color(0xFF334651)
                    drawRect(body.copy(alpha = fade), Offset(-length * .5f, -length * .17f), Size(length, length * .34f))
                    if (day < .6) {
                        val lamp = Color(255, 237, 191).copy(alpha = .95f * fade)
                        val lw = maxOf(.7 * css, length * .17.toDouble()).toFloat()
                        val lh = maxOf(.5 * css, length * .15.toDouble()).toFloat()
                        drawRect(lamp, Offset(length * .35f, -length * .23f), Size(lw, lh))
                        drawRect(lamp, Offset(length * .35f, length * .08f), Size(lw, lh))
                        drawRect(Color(222, 103, 79).copy(alpha = .8f * fade), Offset(-length * .55f, -length * .17f), Size(maxOf(.6 * css, length * .13.toDouble()).toFloat(), length * .34f))
                    }
                    if (street.wetness > .08) {
                        // Its lights on the wet road.
                        val alpha = (fade * street.wetness * (1 - day * .7) * .36).toFloat().coerceIn(0f, 1f)
                        drawRect(Color(0xFFE5C88E).copy(alpha = alpha), Offset(-length * .5f, length * .5f), Size(length * 2.8f, maxOf(.6 * css, length * .22.toDouble()).toFloat()))
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawWalkers(life: SceneLife, day: Double, street: Street, time: Double, cover: Cover) {
    if (life.walkers.isEmpty()) return
    val css = cover.css
    val plazas = Path().apply { life.plazas.forEach { addPath(it.path(cover)) } }
    clipped(plazas, emptyList()) {
        life.walkers.forEachIndexed { i, route ->
            val p = life.position(route, time) ?: return@forEachIndexed
            val x = cover.px(p.x)
            val y = cover.py(p.y)
            val size = (route.height * cover.height).toFloat()
            val stride = (sin(time * 5 + i) * size * .13).toFloat()
            val color = hex(route.color, Color(0xFF687B7E)).copy(alpha = (p.fade * (if (day > .2) .67 else .48)).toFloat())
            val width = maxOf(.5 * css, size * .18.toDouble()).toFloat()
            drawCircle(color, size * .14f, Offset(x, y - size * .83f))
            drawLine(color, Offset(x, y - size * .65f), Offset(x, y - size * .25f), width, StrokeCap.Round)
            drawLine(color, Offset(x, y - size * .25f), Offset(x + stride, y), width, StrokeCap.Round)
            drawLine(color, Offset(x, y - size * .25f), Offset(x - stride, y), width, StrokeCap.Round)
            if (street.rain > .16) {
                val umbrella = (if (i % 2 == 1) Color(0xFF8A9690) else Color(0xFF798E9B)).copy(alpha = (p.fade * .7).toFloat())
                val r = size * .56f
                drawArc(umbrella, 180f, 180f, true, Offset(x - r, y - size * .73f - r), Size(r * 2, r * 2))
            }
        }
    }
}

private fun DrawScope.drawDrips(life: SceneLife, day: Double, street: Street, time: Double, cover: Cover) {
    val amount = maxOf(street.rain, street.wetness * .35)
    if (amount < .035 || (street.temperature != null && street.temperature <= 0)) return
    val css = cover.css
    val color = if (day > .5) rgba(125, 153, 166, amount * .38) else rgba(183, 195, 191, amount * .38)
    life.eaves.forEachIndexed { index, e ->
        for (k in 0 until 5) {
            val f = (k + .5) / 5
            val x = cover.px(e.a.x + (e.b.x - e.a.x) * f)
            val y = cover.py(e.a.y + (e.b.y - e.a.y) * f)
            val phase = (time * (.7 + amount * .8) + k * .37 + index * .19) % 1
            if (phase > .25 + amount * .55) continue
            val drop = phase.pow(1.7) * cover.height * e.fall
            drawLine(
                color, Offset((x + street.windX * drop * .15).toFloat(), (y + drop).toFloat()),
                Offset((x + street.windX * (drop + css) * .15).toFloat(), (y + drop + 1.2 * css).toFloat()), (.6 * css).toFloat(),
            )
        }
    }
}

private fun DrawScope.drawJets(life: SceneLife, day: Double, street: Street, time: Double, cover: Cover) {
    if (street.temperature != null && street.temperature < 0) return
    val css = cover.css
    life.jets.forEachIndexed { index, j ->
        val height = j.base.y - j.top.y
        val drift = street.windX * street.wind * j.spread * .22
        val clip = j.clip ?: cc.txwd.kone.city.Polygon(listOf(
            cc.txwd.kone.city.Point(j.base.x - j.spread * 1.5, j.base.y), cc.txwd.kone.city.Point(j.top.x - j.spread * 1.5, j.top.y),
            cc.txwd.kone.city.Point(j.top.x + j.spread * 1.5, j.top.y), cc.txwd.kone.city.Point(j.base.x + j.spread * 1.5, j.base.y),
        ))
        val color = if (day > .3) rgba(232, 248, 249, (.16 + .16 * (1 - day)) * j.power) else rgba(189, 224, 241, (.16 + .16 * (1 - day)) * j.power)
        clipped(clip.path(cover), emptyList()) {
            for (k in 0 until 12) {
                val phase = (time / j.period + k / 12.0 + index * .17) % 1
                val up = (1 - (2 * phase - 1).pow(2)) * height
                val x = j.base.x + (j.top.x - j.base.x) * up / height + (k / 11.0 - .5) * j.spread * .8 + drift * phase * phase
                val from = Offset(cover.px(x), cover.py(j.base.y - up))
                drawLine(color, from, Offset(from.x + (cover.w * .0003).toFloat(), from.y + (cover.height * .0025).toFloat()), (.6 * css).toFloat())
            }
        }
    }
}

private fun DrawScope.drawWheels(life: SceneLife, day: Double, time: Double, cover: Cover) {
    val css = cover.css
    for (wheel in life.wheels) {
        val (r, g, b) = wheel.color ?: Triple(238, 211, 170)
        val color = rgba(r, g, b, if (day > .3) .25 else .68)
        val rx = (maxOf(.6, cover.w / css * .0016) * css).toFloat()
        val ry = (maxOf(.8, cover.height / css * .001) * css).toFloat()
        for (k in 0 until 12) {
            val a = TAU * (time / wheel.period + wheel.phase + k / 12.0)
            val x = cover.px(wheel.center.x + cos(a) * wheel.rx)
            val y = cover.py(wheel.center.y + sin(a) * wheel.ry)
            drawOval(color, Offset(x - rx, y - ry), Size(rx * 2, ry * 2))
        }
    }
}
