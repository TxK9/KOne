package io.nekohasekai.sfa.managed

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManagedClaimCancellationTest {
    private val claim get() = ManagedClaim("synthetic-device", "synthetic-credential")

    /** A real ManagedApi request held before its valid synthetic response. */
    private class PendingClaim : AutoCloseable {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val api = ManagedApi(requireNotNull(ManagedEndpoint.parse("http://127.0.0.1:${socket.localPort}")), 5_000)
        private val worker = thread(isDaemon = true, name = "synthetic-claim") {
            socket.accept().use { connection ->
                val reader = connection.getInputStream().bufferedReader()
                assertEquals("POST /api/client/claim HTTP/1.1", reader.readLine())
                var size = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) size = line.substringAfter(':').trim().toInt()
                }
                repeat(size) { reader.read() }
                requests.incrementAndGet()
                received.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                val body = """{"format":1,"deviceId":"synthetic-device","credential":"synthetic-credential"}""".toByteArray()
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
            }
        }
        override fun close() { release.countDown(); socket.close(); worker.join(1_000) }
    }

    @Test fun cancelledRequestCannotBindAfterTheBlockingApiReturns() = runBlocking {
        PendingClaim().use { pending ->
            val mutations = AtomicInteger()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                ManagedSync.claimTransaction(
                    request = { pending.api.claim("synthetic-invite", "Synthetic phone") },
                    removeProfile = { mutations.incrementAndGet() },
                    bind = { mutations.incrementAndGet() },
                    schedule = { mutations.incrementAndGet() },
                    synchronize = { mutations.incrementAndGet() },
                )
            }
            assertTrue(pending.received.await(3, TimeUnit.SECONDS))
            job.cancel()
            pending.release.countDown()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(1, pending.requests.get())
            assertEquals("Cancelled response must not remove, bind, schedule or sync", 0, mutations.get())
        }
    }

    @Test fun cancellationDuringRemovalCannotRebindOrSchedule() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mutations = AtomicInteger()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            ManagedSync.claimTransaction({ claim }, {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }, { mutations.incrementAndGet() }, { mutations.incrementAndGet() }, { mutations.incrementAndGet() })
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        job.cancel(); release.countDown(); job.join()
        assertEquals(0, mutations.get())
    }

    @Test fun failedClaimReleasesTheLockAndRetryCommitsExactlyOnceInOrder() = runBlocking {
        val events = mutableListOf<String>()
        try {
            ManagedSync.claimTransaction({ throw ManagedException(ManagedFailure.InviteInvalid) },
                { events += "remove" }, { events += "bind" }, { events += "schedule" }, { events += "sync" })
            fail("The claim failure must remain visible")
        } catch (failure: ManagedException) { assertEquals(ManagedFailure.InviteInvalid, failure.failure) }
        assertTrue(events.isEmpty())
        ManagedSync.claimTransaction({ events += "request"; claim }, { events += "remove" },
            { assertEquals("synthetic-device", it.deviceId); events += "bind" },
            { events += "schedule" }, { events += "sync" })
        assertEquals(listOf("request", "remove", "bind", "schedule", "sync"), events)
    }

    @Test fun aCancelledQueuedClaimNeverStartsItsRequest() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstCommits = AtomicInteger()
        val secondRequests = AtomicInteger()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            ManagedSync.claimTransaction({ entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); claim },
                {}, { firstCommits.incrementAndGet() }, {}, {})
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            ManagedSync.claimTransaction({ secondRequests.incrementAndGet(); claim }, {}, {}, {}, {})
        }
        second.cancel()
        release.countDown()
        first.join(); second.join()
        assertEquals(1, firstCommits.get())
        assertEquals(0, secondRequests.get())
    }
}
