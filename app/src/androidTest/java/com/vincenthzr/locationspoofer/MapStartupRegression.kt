package com.vincenthzr.locationspoofer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.amap.api.maps.TextureMapView
import com.vincenthzr.locationspoofer.data.repository.SettingsRepository
import org.koin.core.context.GlobalContext
import kotlin.math.cos
import kotlin.math.hypot

/** Read the real SDK camera after an actual activity startup, without moving it in the test. */
internal fun SystemFilesInstrumentation.verifyMapStartup(): Bundle {
    val output = Bundle()
    var activity: Activity? = null
    try {
        val readyBy = SystemClock.elapsedRealtime() + 15_000
        while (GlobalContext.getOrNull() == null && SystemClock.elapsedRealtime() < readyBy) Thread.sleep(100)
        val settings = GlobalContext.get().get<SettingsRepository>()
        check(!settings.isSpoofingActive && settings.keepLastMapPosition) { "Preserve the user's simulation and map preference" }
        check(settings.getMapEngine() in setOf("AUTO", "AMAP")) { "This regression targets the installed AMap engine" }
        val saved = checkNotNull(settings.getLastMapPosition())
        val latitude = saved.first.toDouble(); val longitude = saved.second.toDouble()
        activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        fun map(view: View): TextureMapView? {
            if (view is TextureMapView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) map(view.getChildAt(index))?.let { return it }
            return null
        }
        var matchedAt = 0L; var samples = 0; var maxError = 0.0
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var error: Double? = null
            runOnMainSync {
                map(activity.window.decorView)?.map?.cameraPosition?.target?.let { target ->
                    error = hypot((target.latitude-latitude)*111_320,
                        (target.longitude-longitude)*111_320*cos(Math.toRadians(latitude)))
                }
            }
            val distance = error
            if (matchedAt != 0L) {
                check(distance != null && distance < 2.0) { "The SDK camera left the restored point after map style initialization" }
                samples++; maxError = maxOf(maxError, distance)
                if (SystemClock.elapsedRealtime()-matchedAt >= 4_000) break
            } else if (distance != null && distance < 2.0) matchedAt = SystemClock.elapsedRealtime()
            Thread.sleep(250)
        }
        check(matchedAt != 0L && samples >= 12) { "The SDK camera did not restore the saved position" }
        check(settings.getLastMapPosition() == saved) { "Startup changed the saved map point" }
        output.putInt("stable_camera_samples", samples)
        output.putInt("app_pid", android.os.Process.myPid())
        output.putDouble("max_settled_error_m", maxError)
        output.putBoolean("saved_point_preserved", true)
        output.putString("result", "PASS")
    } catch (error: Throwable) { output.putString("result", "FAIL: ${error.stackTraceToString()}") }
    finally { activity?.let { runOnMainSync { it.finish() } } }
    return output
}
