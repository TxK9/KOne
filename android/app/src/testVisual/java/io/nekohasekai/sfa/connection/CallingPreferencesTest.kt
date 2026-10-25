package io.nekohasekai.sfa.connection

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CallingPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val raw get() = context.getSharedPreferences("kone_connection_choices", Context.MODE_PRIVATE)
    private val store get() = CallingPreferences(context)

    @Before fun clear() { raw.edit().clear().commit() }

    @Test fun newDefaultsFollowTheProfileWithoutWritingARecord() {
        assertEquals(CallingOptions(false, CallingDns.Original, 0), store.read(7))
        assertFalse(raw.contains("7"))
    }

    @Test fun existingExplicitParametersAreReadByteForByteWithoutMigration() {
        val saved = """{"enabled":true,"dns":"PreferIpv4","mtu":1280}"""
        raw.edit().putString("7", saved).commit()
        assertEquals(CallingOptions(true, CallingDns.PreferIpv4, 1280), store.read(7))
        assertEquals(saved, raw.getString("7", null))
    }

    @Test fun missingParametersFollowTheProfileAndDoNotRewriteExistingJson() {
        val saved = """{"enabled":true}"""
        raw.edit().putString("7", saved).commit()
        assertEquals(CallingOptions(true, CallingDns.Original, 0), store.read(7))
        assertEquals(saved, raw.getString("7", null))
    }

    @Test fun staleCompareAndSetPreservesTheNewerChoice() {
        val initial = CallingOptions()
        val newer = CallingOptions(true, CallingDns.PreferIpv6, 1400)
        store.write(7, newer)
        assertFalse(store.compareAndSet(7, initial, CallingOptions(true)))
        assertEquals(newer, store.read(7))
    }

    @Test fun successfulCompareAndSetOnlyChangesItsOwnProfile() {
        val other = CallingOptions(true, CallingDns.Ipv4Only, 1500)
        store.write(8, other)
        val next = CallingOptions(true, CallingDns.Original, 0)
        assertTrue(store.compareAndSet(7, CallingOptions(), next))
        assertEquals(next, store.read(7))
        assertEquals(other, store.read(8))
    }
}
