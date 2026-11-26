package com.vincenthzr.locationspoofer

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/** Debug-only foreground surface for finite source tests on ROMs that freeze background clients. */
class LookupDiagnosticsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "正在验证无线数据源\n\n当前模拟配置保持原样。验证结束后此窗口自动关闭。"
            textSize = 20f
            setPadding(40, 80, 40, 40)
        })
    }
}
