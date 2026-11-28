package com.vincenthzr.locationspoofer

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle

/** Test APK only. The display fixture needs resources, not the real application's map SDK startup. */
class RadioResourcesInstrumentation : Instrumentation() {
    private var enabled = false
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        enabled = arguments?.getString("check") == "radio-refresh-fixtures"
        start()
    }

    override fun onStart() {
        val application = targetContext.applicationContext
        if (!enabled || application?.javaClass != Application::class.java) {
            finish(0, Bundle().apply { putString("result", "FAIL"); putString("failure", "Unexpected test application or check name") })
            return
        }
        val report = checkRadioRefreshFixtures(targetContext)
        report.putString("resource_app", application.javaClass.name)
        finish(if (report.getString("result") == "PASS") -1 else 0, report)
    }
}