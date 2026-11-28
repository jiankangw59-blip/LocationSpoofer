package com.vincenthzr.locationspoofer

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import com.vincenthzr.locationspoofer.data.model.RadioCaptureReport
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.screen.radioCaptureLabels
import com.vincenthzr.locationspoofer.utils.CaptureStatus
import com.vincenthzr.locationspoofer.utils.RefreshOutcome
import java.util.Locale

/** Run the first-party screen's formatter with synthetic rounds; read APK resources only. */
internal fun checkRadioRefreshFixtures(ctx: Context): Bundle {
    val out = Bundle()
    var n = 0
    fun verify(label: String, ok: Boolean) { check(ok) { label }; n++ }
    fun labels(refresh: RefreshOutcome, data: CaptureStatus) = radioCaptureLabels(RadioCaptureReport(refresh, data))
    try {
        val waiting = radioCaptureLabels(null)
        verify("no round does not mean a completed empty scan", waiting.refresh == null && waiting.data == R.string.obs_waiting)
        val cached = labels(RefreshOutcome.NOT_REQUESTED, CaptureStatus.CACHED)
        val late = labels(RefreshOutcome.TIMED_OUT, CaptureStatus.CACHED)
        val denied = labels(RefreshOutcome.PERMISSION_DENIED, CaptureStatus.CACHED)
        val failed = labels(RefreshOutcome.FAILED, CaptureStatus.CACHED)
        verify("cache-only read", cached.refresh == R.string.refresh_not_requested && cached.data == R.string.obs_cached)
        verify("timeout with fallback", late.refresh == R.string.refresh_timed_out && late.data == cached.data)
        verify("denial with fallback", denied.refresh == R.string.refresh_permission_denied && denied.data == cached.data)
        verify("error with fallback", failed.refresh == R.string.refresh_failed && failed.data == cached.data)
        val cooled = labels(RefreshOutcome.LOCAL_COOLDOWN, CaptureStatus.NOT_UPDATED)
        val notConfirmed = labels(RefreshOutcome.NOT_CONFIRMED, CaptureStatus.NOT_UPDATED)
        verify("local cooldown distinguished from unconfirmed update", cooled.refresh == R.string.refresh_local_cooldown &&
            notConfirmed.refresh == R.string.refresh_not_confirmed && cooled.data == notConfirmed.data)
        val empty = labels(RefreshOutcome.COMPLETED, CaptureStatus.EMPTY)
        val fresh = labels(RefreshOutcome.COMPLETED, CaptureStatus.OBSERVED)
        val unverified = labels(RefreshOutcome.COMPLETED, CaptureStatus.NOT_UPDATED)
        verify("completed with no records", empty.refresh == R.string.refresh_completed && empty.data == R.string.obs_empty)
        verify("new records require separate evidence", fresh.refresh == empty.refresh && fresh.data == R.string.obs_received)
        verify("notification alone is not a fresh sample", unverified.refresh == empty.refresh && unverified.data == R.string.obs_not_updated)
        val afterDenied = labels(RefreshOutcome.COMPLETED, CaptureStatus.PERMISSION_DENIED)
        verify("later read denial does not rewrite update event", afterDenied.refresh == empty.refresh && afterDenied.data == R.string.obs_denied)
        val lines = listOf("zh-CN", "en-US", "ar").map { tag ->
            val cfg = Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val res = ctx.createConfigurationContext(cfg).resources
            val action = res.getString(requireNotNull(late.refresh))
            val data = res.getString(late.data)
            val both = res.getString(R.string.refresh_and_data, action, data)
            verify(tag + " shows both independent parts", action != data && both.contains(action) && both.contains(data))
            both
        }
        verify("localized strings packaged", lines.distinct().size == lines.size)
        out.putString("result", "PASS")
    } catch (e: Exception) {
        out.putString("result", "FAIL")
        out.putString("failure", e.message?.take(128) ?: e.javaClass.simpleName)
    }
    out.putInt("assertions", n)
    return out
}