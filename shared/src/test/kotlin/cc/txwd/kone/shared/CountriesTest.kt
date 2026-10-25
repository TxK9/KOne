package cc.txwd.kone.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CountriesTest {
    @Test fun ordinaryWordsDoNotHideAnExplicitCountry() {
        assertEquals("JP", Countries.of("Servidor de Japan")?.code)
        assertEquals("FR", Countries.of("Serveur au France")?.code)
        assertEquals("SG", Countries.of("ca Singapore 2")?.code)
        listOf("contact us", "de", "au relay", "RUSSELL", "US / GB").forEach { assertNull(Countries.of(it), it) }
        assertEquals("US", Countries.of("US-West 01")?.code)
        assertEquals("GB", Countries.of("united kingdom 3")?.code)
    }
}
