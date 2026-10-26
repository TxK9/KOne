package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextTest {
    @Test fun beforeAnythingIsKnownItSaysConnected() {
        assertEquals("Connected", notificationText(null, null, "Connected"))
    }

    @Test fun healthComesFirstThenTraffic() {
        assertEquals("Good · 180 ms   ↑ 1 kB/s  ↓ 4 kB/s", notificationText("Good · 180 ms", "↑ 1 kB/s  ↓ 4 kB/s", "Connected"))
    }

    @Test fun eitherLineStandsAlone() {
        assertEquals("Unreachable", notificationText("Unreachable", null, "Connected"))
        assertEquals("↑ 0 B/s  ↓ 0 B/s", notificationText(null, "↑ 0 B/s  ↓ 0 B/s", "Connected"))
    }
}
