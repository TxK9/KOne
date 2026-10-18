package cc.txwd.kone.city

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CitiesTest {
    private val day = LocalDate.of(2026, 10, 17)

    @Test
    fun everySceneComesWithBothPaintings() {
        assertEquals(302, Cities.all.size)
        for (scene in Cities.all) {
            for (night in listOf(false, true)) {
                assertTrue(Cities::class.java.getResource("/scenes/images/${scene.id}-${if (night) "night" else "day"}.webp") != null, "${scene.id} night=$night")
            }
            assertTrue(scene.skySafe.left < scene.skySafe.right && scene.skySafe.top < scene.skySafe.bottom, scene.id)
            assertTrue(scene.crop.top + scene.crop.height <= 1.0 + 1e-9, scene.id)
        }
    }

    @Test
    fun aCountryShowsFourOfItsCitiesAndMovesOnEachDay() {
        val today = Cities.pick("JP", day)
        assertEquals(4, today.size)
        assertTrue(today.all { it.country == "JP" })
        assertEquals(4, today.map { it.cityId }.toSet().size)
        assertNotEquals(today, Cities.pick("JP", day.plusDays(1)))
    }

    @Test
    fun hongKongAndMacauComeFirstForTheirOwnNodes() {
        assertEquals("hong-kong", Cities.pick("HK", day).first().cityId)
        assertEquals("macau", Cities.pick("MO", day).first().cityId)
        assertTrue(Cities.pick("HK", day).all { it.country == "CN" })
    }

    @Test
    fun aCountryWithoutScenesKeepsTheInk() {
        assertTrue(Cities.pick("TW", day).isEmpty())
        assertTrue(Cities.pick(null, day).isEmpty())
    }

    @Test
    fun aFewScenesAreAllShown() {
        // Brazil has a single scene; Switzerland two.
        assertEquals(1, Cities.pick("BR", day).size)
        assertEquals(2, Cities.pick("CH", day).size)
    }
}
