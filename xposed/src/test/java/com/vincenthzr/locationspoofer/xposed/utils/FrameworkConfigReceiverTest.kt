package com.vincenthzr.locationspoofer.xposed.utils

import android.content.SharedPreferences
import com.vincenthzr.locationspoofer.utils.FrameworkConfigChannel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FrameworkConfigReceiverTest {
    private class Preferences {
        @Volatile var snapshot: String? = null
        @Volatile var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        val reads = java.util.concurrent.atomic.AtomicInteger()
        // Registration is intercepted separately so the tests exercise the real listener path.
        val notifyingPrefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> { reads.incrementAndGet(); snapshot }
                "registerOnSharedPreferenceChangeListener" -> { listener = args!![0] as SharedPreferences.OnSharedPreferenceChangeListener; null }
                "unregisterOnSharedPreferenceChangeListener" -> { listener = null; null }
                else -> null
            }
        } as SharedPreferences
    }

    private fun snapshot(payload: String = "{\"active\":true}", file: String? = null): String = JSONObject().apply {
        put("version", FrameworkConfigChannel.VERSION)
        put("id", java.util.UUID.randomUUID().toString())
        put("published_at", 123L)
        if (file == null) put("payload", payload) else put("file", file)
    }.toString()

    @Test fun listenerDeliversStopAndCloseUnregistersIt() {
        val prefs = Preferences().apply { snapshot = snapshot() }
        val stopped = CountDownLatch(1)
        var active = false
        val receiver = FrameworkConfigReceiver({ prefs.notifyingPrefs }, { error("Unexpected file") }, {
            active = it.config.getBoolean("active")
            if (!active) stopped.countDown()
        }, { throw AssertionError(it) })
        try {
            receiver.refresh()
            assertTrue(active)
            prefs.snapshot = snapshot("{\"active\":false}")
            prefs.listener!!.onSharedPreferenceChanged(prefs.notifyingPrefs, FrameworkConfigChannel.SNAPSHOT_KEY)
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            assertFalse(active)
        } finally { receiver.close() }
        assertNull(prefs.listener)
    }

    @Test fun malformedUpdateKeepsValidConfigAndLaterUpdateRecovers() {
        val prefs = Preferences().apply { snapshot = snapshot() }
        val configs = mutableListOf<FrameworkConfigSnapshot>()
        val errors = mutableListOf<Exception>()
        FrameworkConfigReceiver({ prefs.notifyingPrefs }, { error("Unexpected file") }, configs::add, errors::add).use {
            it.refresh()
            it.refresh()
            assertEquals(1, configs.size)
            prefs.snapshot = snapshot("not json")
            it.refresh()
            assertEquals(1, configs.size)
            assertEquals(1, errors.size)
            prefs.snapshot = snapshot("{\"active\":false}")
            it.refresh()
            assertFalse(configs.last().config.getBoolean("active"))
        }
    }

    @Test fun failedFileReadRetriesTheSameSnapshot() {
        val file = "${FrameworkConfigChannel.FILE_PREFIX}test.json"
        val prefs = Preferences().apply { snapshot = snapshot(file = file) }
        var fail = true
        val configs = mutableListOf<FrameworkConfigSnapshot>()
        FrameworkConfigReceiver({ prefs.notifyingPrefs }, {
            assertEquals(file, it)
            if (fail) throw java.io.FileNotFoundException() else "{\"lat\":30}"
        }, configs::add, {}).use {
            it.refresh()
            assertTrue(configs.isEmpty())
            fail = false
            it.refresh()
            assertEquals(30, configs.single().config.getInt("lat"))
            assertEquals("libxposed:remote-file", configs.single().source)
        }
    }

    @Test fun earlyBootFailureRetriesAndClosedReceiverCannotPublish() {
        val prefs = Preferences().apply { snapshot = snapshot() }
        var attempts = 0
        val configs = mutableListOf<FrameworkConfigSnapshot>()
        val receiver = FrameworkConfigReceiver({
            if (++attempts == 1) throw IllegalStateException("Not ready")
            prefs.notifyingPrefs
        }, { error("Unexpected file") }, configs::add, {})
        receiver.refresh()
        receiver.refresh()
        assertEquals(1, configs.size)
        receiver.close()
        prefs.snapshot = snapshot("{\"active\":false}")
        receiver.refresh()
        assertEquals(1, configs.size)
    }

    @Test fun unsupportedProtocolAndPathTraversalAreRejectedBeforeFileAccess() {
        val future = JSONObject(snapshot()).put("version", 999).toString()
        assertThrows(IllegalArgumentException::class.java) { FrameworkConfigSnapshot.decode(future) { error("Unexpected file") } }
        val traversal = snapshot(file = "${FrameworkConfigChannel.FILE_PREFIX}../other.json")
        assertThrows(IllegalArgumentException::class.java) { FrameworkConfigSnapshot.decode(traversal) { error("Unexpected file") } }
    }

    @Test fun healthyReceiverDoesNotReadAgainWithoutAnEvent() {
        val prefs = Preferences().apply { snapshot = snapshot() }
        FrameworkConfigReceiver({ prefs.notifyingPrefs }, { error("Unexpected file") }, {}, {}).use {
            it.start()
            assertEquals(1, prefs.reads.get())
            it.onHostEvent()
            Thread.sleep(1_250)
            assertEquals("Healthy configuration must not retain the old one-second sampler", 1, prefs.reads.get())
        }
    }

    @Test fun malformedPublicationKeepsListenerForLaterValidEvent() {
        val prefs = Preferences().apply { snapshot = snapshot() }
        val failed = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        FrameworkConfigReceiver({ prefs.notifyingPrefs }, { error("Unexpected file") }, {
            if (!it.config.getBoolean("active")) stopped.countDown()
        }, { failed.countDown() }, longArrayOf()).use {
            it.start()
            val listener = prefs.listener!!
            prefs.snapshot = snapshot("not json")
            listener.onSharedPreferenceChanged(prefs.notifyingPrefs, FrameworkConfigChannel.SNAPSHOT_KEY)
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertSame(listener, prefs.listener)
            prefs.snapshot = snapshot("{\"active\":false}")
            prefs.listener!!.onSharedPreferenceChanged(prefs.notifyingPrefs, FrameworkConfigChannel.SNAPSHOT_KEY)
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun failedBootstrapHasFiniteRetriesAndLifecycleCanRestartReception() {
        val prefs = Preferences().apply { snapshot = snapshot() }
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val exhausted = CountDownLatch(4)
        val delivered = CountDownLatch(1)
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        FrameworkConfigReceiver({
            attempts.incrementAndGet()
            exhausted.countDown()
            if (!ready.get()) throw IllegalStateException("Service is not ready")
            prefs.notifyingPrefs
        }, { error("Unexpected file") }, { delivered.countDown() }, {}, longArrayOf(10, 20, 30)).use {
            it.start()
            assertTrue(exhausted.await(2, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertEquals(4, attempts.get())
            ready.set(true)
            it.onHostEvent()
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(5, attempts.get())
        }
    }

    @Test fun serviceRegistrationHintNeverWaitsForPendingFrameworkIpc() {
        val prefs = Preferences().apply { snapshot = snapshot() }
        val connecting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hinted = CountDownLatch(1)
        val receiver = FrameworkConfigReceiver({
            connecting.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            prefs.notifyingPrefs
        }, { error("Unexpected file") }, {}, {})
        val bootstrap = Thread { receiver.start() }
        val serviceThread = Thread { receiver.onHostEvent(); hinted.countDown() }
        try {
            bootstrap.start()
            assertTrue(connecting.await(2, TimeUnit.SECONDS))
            serviceThread.start()
            assertTrue("A service/lifecycle Hook must not block on configuration Binder IO",
                hinted.await(500, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            bootstrap.join(2_000)
            serviceThread.join(2_000)
            receiver.close()
        }
    }
}
