package cc.txwd.kone.app

import cc.txwd.kone.core.CoreConnection
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrafficTest {
    private val names = mapOf("node-1" to "Japan", "node-2" to "United States")
    private fun name(tag: String) = names[tag] ?: tag

    private fun connection(id: String, program: String?, via: String, down: Long, up: Long) = CoreConnection(id, program, "www.example.com:443", via, down, up)

    @Test
    fun countsWhatEachConnectionCarriedSinceItWasLastSeen() {
        var day = LocalDate.of(2026, 10, 18)
        val traffic = Traffic(File(Files.createTempDirectory("kone").toFile(), "traffic.properties")) { day }
        traffic.sample(listOf(connection("a", "chrome.exe", "node-1", 1_000, 100), connection("b", null, "direct", 50, 5)), ::name, now = 0)
        // The same totals again carry nothing new; a larger one counts only the difference.
        traffic.sample(listOf(connection("a", "chrome.exe", "node-1", 1_000, 100), connection("b", null, "direct", 50, 5)), ::name, now = 0)
        traffic.sample(listOf(connection("a", "chrome.exe", "node-1", 3_000, 300), connection("c", "Code.exe", "node-2", 400, 40)), ::name, now = 0)
        assertEquals(listOf("Japan" to Used(3_000, 300), "United States" to Used(400, 40), Traffic.DIRECT to Used(50, 5)), traffic.top(TrafficBy.Node, 1))
        assertEquals(listOf("chrome.exe" to Used(3_000, 300), "Code.exe" to Used(400, 40), Traffic.SYSTEM to Used(50, 5)), traffic.top(TrafficBy.App, 1))
        // "b" closed and was let go; an id seen afresh counts from zero.
        traffic.sample(listOf(connection("b", null, "direct", 10, 1)), ::name, now = 0)
        assertEquals(Used(60, 6), traffic.top(TrafficBy.Node, 1).first { it.first == Traffic.DIRECT }.second)
        day = day.plusDays(1)
        traffic.sample(listOf(connection("b", null, "direct", 20, 1)), ::name, now = 0)
        assertEquals(listOf(Traffic.DIRECT to Used(10, 0)), traffic.top(TrafficBy.Node, 1))
        assertEquals(Used(3_470, 346), traffic.top(TrafficBy.Node, 7).fold(Used(0, 0)) { sum, (_, used) -> Used(sum.down + used.down, sum.up + used.up) })
        val daily = traffic.daily(3)
        assertEquals(listOf(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 19)), daily.map { it.first })
        assertEquals(listOf(Used(0, 0), Used(3_460, 346), Used(10, 0)), daily.map { it.second })
    }

    @Test
    fun aRestartedCoreIsNotTakenForLessTraffic() {
        val traffic = Traffic(File(Files.createTempDirectory("kone").toFile(), "traffic.properties")) { LocalDate.of(2026, 10, 18) }
        traffic.sample(listOf(connection("a", "chrome.exe", "node-1", 5_000, 0)), ::name, now = 0)
        traffic.forget()
        traffic.sample(listOf(connection("a", "chrome.exe", "node-1", 200, 0)), ::name, now = 0)
        assertEquals(listOf("Japan" to Used(5_200, 0)), traffic.top(TrafficBy.Node, 1))
    }

    @Test
    fun keepsAMonthAndTheLargestOfEachDayInTheFile() {
        var day = LocalDate.of(2026, 9, 1)
        val file = File(Files.createTempDirectory("kone").toFile(), "traffic.properties")
        val traffic = Traffic(file) { day }
        traffic.sample(listOf(connection("old", "old.exe", "node-1", 9, 9)), ::name, now = 0)
        day = LocalDate.of(2026, 10, 18)
        val many = (1..Traffic.MOST + 5).map { connection("p$it", "p$it.exe", "node-2", it * 100L, 0) }
        traffic.sample(many, ::name, now = 120_000)
        assertTrue(file.exists())
        val read = Traffic(file) { day }
        val apps = read.top(TrafficBy.App, 30)
        assertEquals(Traffic.MOST + 1, apps.size)
        // The five smallest (100 to 500) were folded into Other; nothing was lost from the day.
        assertEquals(Used(1_500, 0), apps.first { it.first == Traffic.OTHER }.second)
        assertEquals((1..Traffic.MOST + 5).sumOf { it * 100L }, apps.sumOf { it.second.down })
        // September 1 is more than a month back and was let go.
        assertEquals(emptyList(), read.top(TrafficBy.App, 60).filter { it.first == "old.exe" })
        assertEquals(listOf("United States" to Used((1..Traffic.MOST + 5).sumOf { it * 100L }, 0)), read.top(TrafficBy.Node, 1))
    }

    @Test
    fun aDamagedFileIsReadForWhatItHas() {
        val file = File(Files.createTempDirectory("kone").toFile(), "traffic.properties")
        file.writeText("2026-10-18|node|Japan=10,2\n2026-10-18|node|=1,1\nnot-a-day|app|x.exe=1,1\n2026-10-18|elsewhere|y=1,1\n2026-10-18|app|z.exe=-4,1\n2026-10-18|app|Bar\\=Baz.exe=3,4\n")
        val traffic = Traffic(file) { LocalDate.of(2026, 10, 18) }
        assertEquals(listOf("Japan" to Used(10, 2)), traffic.top(TrafficBy.Node, 1))
        assertEquals(listOf("Bar=Baz.exe" to Used(3, 4)), traffic.top(TrafficBy.App, 1))
    }
}
