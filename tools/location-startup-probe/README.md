# 定位启动诊断

包名为 `com.codex.locationstartup`，启动入口为 `com.codex.locationstartup/.MainActivity`。如果要把本应用加入 Thanox 的 `GPSApp`，由你添加这一行，保留空格和加号。

```text
com.codex.locationstartup +
```

1. 首次打开时点击「授予定位权限」。授权页面属于准备阶段。
2. 结束应用进程，再从启动器打开「定位启动诊断」。已有权限时，应用在 `Application.attachBaseContext` 完成基础 Context 安装后启动记录器，以 attach 入口为冷启动会话起点。离开前台或点击「停止记录」会结束会话。
3. 记录结束后，点击「复制当前报告」。记录过程中复制会在剪贴板内容中标注未结束快照。

应用分别记录 `getLastKnownLocation` 缓存、普通 `LocationListener` 回调，以及 GPS 和 network 各一次 `getCurrentLocation`。单次请求也可能返回缓存。启动与首次窗口焦点各有一段最多 40 个 50 毫秒时点的缓存采样，两个窗口共享重合的读取。记录器跳过已经错过的时点，并标出迟到的 SDK 返回。首个焦点未观察到时，页面窗口也未覆盖。

从桌面返回时，如果进程仍在且上一会话已结束，应用以 `Activity.onStart` 为暖启动起点。若进程仍在但页面需要重建，则以 `Activity.onCreate` 为暖启动起点。冷启动、暖启动和手动记录都最多持续 30 秒。手动结束后，当前页的焦点变化不会开启新会话。

进程启动到 attach 入口，以及 Context 安装、监听登记和首个 SDK 读数之前仍有观测盲区。日志分别记录实际接收时间、SDK 调用起止和序列化时间。监听回调的接收时间指回调进入应用线程的时刻。界面分别显示从会话起点和首次窗口焦点起算的观察计数。

数据写入应用私有目录 `files/location-startup/startup-*.jsonl`，每会话最多 4096 条事件，保留最近 20 份报告。定位保留 SDK 返回的坐标和 `isMock` 值，extras 仅列键名，每条最多 64 个键，每个键最多 256 个 UTF-16 单元。报告会标出省略和缩短的键数。事件限额、API 异常、权限准备、无样本和缺失的模式切换广播都会形成覆盖限制。

通过已连接的 ADB 读取报告文件名。

```powershell
adb shell run-as com.codex.locationstartup ls files/location-startup
```

1.2 版（versionCode 3）支持以下两条前台诊断命令。发送方需要 `android.permission.DUMP`，且应用页面可见并持有窗口焦点。第一条读取本应用的 FINE/COARSE 权限及当前 AppOps 原始和常规返回值，将 PID、UID、调用时间和异常类型保存为私有目录下的 `policy-*.json`，独立保留最近 20 份。第二条与「重新记录 30 秒」使用同一录制入口，新会话标记为 `control_restart`。

```sh
adb shell am broadcast -a com.codex.locationstartup.CAPTURE_POLICY -p com.codex.locationstartup
adb shell am broadcast -a com.codex.locationstartup.RESTART_RECORDING -p com.codex.locationstartup
```

第一条成功时返回策略快照文件名，第二条返回 `restart_requested`。该结果只确认已请求重录，实际登记与回调以新会话和系统定位服务记录为准。策略快照不调用 AppOps note 或修改权限、定位开关与 Thanox 设置。

未收到定位不代表无泄漏，mock 字段也不能单独判断定位来源。未出现 `session_end` 的报告属于未完成采样。报告包含原始坐标，请按需分享。

CI 构建使用 JDK 21 和 Android SDK 36。将 `PROBE_UNSIGNED` 设为 `1` 后执行 `python tools/location-startup-probe/build.py`，生成已对齐的 `location-startup-probe-unsigned.apk`。通过 `BUILD_TOOLS_VERSION` 选择已安装的 Build Tools。脚本输出 APK 路径、签名状态和 SHA-256。默认构建使用本地 debug keystore 签名，也可通过 `LOCATION_STARTUP_PROBE_KEYSTORE` 指定原 debug keystore 路径以保留更新身份。
