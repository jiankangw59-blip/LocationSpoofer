package com.vincenthzr.locationspoofer

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit.SECONDS

internal fun SystemFilesInstrumentation.verifyNativeRegistrationCleanup(): Bundle = Bundle().apply {
    try {
        RegistrationFixture(targetContext.classLoader).use { fixture ->
            val binder = ObservedBinder()
            val failure = IllegalArgumentException("native registration rejected")
            check(fixture.register(binder, "original") { null } == null) { "Null result changed" }
            val original = checkNotNull(fixture.records[binder])
            check(binder.linkCount == 1) { "The original registration did not link once" }

            check(runCatching { fixture.register(binder, "throw") { throw failure } }
                .exceptionOrNull() === failure) { "Native exception identity changed" }
            check(fixture.records[binder] === original && binder.linkCount == 1) {
                "Native exception did not restore the original entry and link"
            }
            check(fixture.register(binder, "false") { false } == false) { "False result changed" }
            check(fixture.records[binder] === original && binder.linkCount == 1) {
                "False result did not restore the original entry and link"
            }

            val nativeStarted = CountDownLatch(1)
            val allowFailure = CountDownLatch(1)
            val rejectedCall = FutureTask {
                runCatching {
                    fixture.register(binder, "older attempt") {
                        nativeStarted.countDown()
                        check(allowFailure.await(5, SECONDS)) { "Timed out waiting for concurrent replacement" }
                        throw failure
                    }
                }.exceptionOrNull()
            }
            Thread(rejectedCall, "native-registration-regression").start()
            try {
                check(nativeStarted.await(5, SECONDS)) { "The first attempt did not reach its native call" }
                val returned = Any()
                check(fixture.register(binder, "concurrent replacement") { returned } === returned) {
                    "Native return object identity changed"
                }
                val replacement = checkNotNull(fixture.records[binder])
                check(replacement !== original) { "The successful registration did not replace the original" }
                allowFailure.countDown()
                check(rejectedCall.get(5, SECONDS) === failure) { "The first call did not propagate its exception" }
                check(fixture.records[binder] === replacement && binder.linkCount == 1) {
                    "The failed older attempt removed the successful replacement"
                }
            } finally {
                allowFailure.countDown()
                rejectedCall.get(5, SECONDS)
            }

            fixture.clear()
            check(binder.linkCount == 0) { "The successful registration retained a link after removal" }
            val cycles = 64
            repeat(cycles) { index ->
                val rejectedBinder = ObservedBinder()
                if (index % 2 == 0) {
                    check(runCatching { fixture.register(rejectedBinder, index) { throw failure } }
                        .exceptionOrNull() === failure)
                } else {
                    check(fixture.register(rejectedBinder, index) { false } == false)
                }
                check(rejectedBinder.linkCount == 0) { "Rejected registration $index retained a Binder link" }
            }
            check(fixture.records.isEmpty()) { "Rejected registrations accumulated in the map" }
            putInt("rejected_cleanup_cycles", cycles)
        }
        putString("result", "PASS")
    } catch (error: Throwable) {
        putString("result", "FAIL: ${error.stackTraceToString()}")
    }
}

private class RegistrationFixture(loader: ClassLoader) : AutoCloseable {
    private val prefix = "com.vincenthzr.locationspoofer.xposed.utils."
    private val attemptType = loader.loadClass(prefix + "NativeRegistrationAttempt")
    private val mapType = loader.loadClass(prefix + "BinderRegistrationMap")
    private val registryType = loader.loadClass(prefix + "BinderDeathRegistry")
    private val registry = loader.loadClass(prefix + "ModuleBinderDeaths").getField("INSTANCE").get(null)
    private val constructor = attemptType.getConstructor(Any::class.java)
    private val track = attemptType.getMethod("track", Any::class.java, Function1::class.java, Function2::class.java)
    private val proceed = attemptType.getMethod("proceed", Function0::class.java)
    private val put = mapType.getMethod("put", IBinder::class.java, Any::class.java)
    private val rollback = mapType.getMethod("rollback", IBinder::class.java, Any::class.java, Any::class.java)
    private val clearMap = mapType.getMethod("clear")
    private val lock = Any()
    private val map = mapType.getConstructor(registryType, Function1::class.java, Function0::class.java)
        .newInstance(registry, { _: Any -> }, { })

    @Suppress("UNCHECKED_CAST")
    val records = map as Map<IBinder, Any>

    fun register(binder: IBinder, value: Any, nativeCall: () -> Any?): Any? {
        val attempt = constructor.newInstance(lock)
        val insert: (Any) -> Any? = { record -> put.invoke(map, binder, record) }
        val restore: (Any, Any?) -> Unit = { rejected, previous ->
            rollback.invoke(map, binder, rejected, previous)
        }
        track.invoke(attempt, value, insert, restore)
        return try {
            proceed.invoke(attempt, nativeCall)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    fun clear() {
        clearMap.invoke(map)
    }

    override fun close() = clear()
}

private class ObservedBinder : Binder() {
    private val recipients = ConcurrentHashMap.newKeySet<IBinder.DeathRecipient>()
    val linkCount get() = recipients.size

    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
        super.linkToDeath(recipient, flags)
        recipients.add(recipient)
    }

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
        recipients.remove(recipient)
        return super.unlinkToDeath(recipient, flags)
    }
}
