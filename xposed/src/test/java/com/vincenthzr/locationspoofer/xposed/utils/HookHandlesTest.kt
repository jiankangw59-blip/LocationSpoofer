package com.vincenthzr.locationspoofer.xposed.utils

import org.junit.Assert.*
import org.junit.Test

class HookHandlesTest {
    @Test
    fun failedInstallRollsBackEarlierHandlesAndPreservesError() {
        val unhooked = mutableListOf<Int>()
        val failure = IllegalStateException("install failed")
        val result = runCatching {
            installHookHandles(listOf(1, 2), {}) { item ->
                if (item == 2) throw failure
                AutoCloseable { unhooked += item }
            }
        }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(listOf(1), unhooked)
    }

    @Test
    fun closeContinuesAfterAnUnhookFailureAndIsIdempotent() {
        val attempts = mutableListOf<Int>()
        val failures = mutableListOf<Throwable>()
        val failure = IllegalStateException("unhook failed")
        val group = installHookHandles(listOf(1, 2), { failures += it }) { item ->
            AutoCloseable {
                attempts += item
                if (item == 2) throw failure
            }
        }
        group.close()
        group.close()
        assertEquals(listOf(2, 1), attempts)
        assertEquals(listOf(failure), failures)
    }
}
