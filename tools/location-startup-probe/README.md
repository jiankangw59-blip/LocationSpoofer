# 定位启动诊断

包名为 `com.codex.locationstartup`，启动入口为 `com.codex.locationstartup/.MainActivity`。如果要把本应用加入 Thanox 的 `GPSApp`，由你添加这一行，保留空格和加号。

```text
com.codex.locationstartup +
```

1. 首次打开时点击「授予定位权限」。授权页面属于准备阶段。
2. 结束应用进程，再从启动器打开「定位启动诊断」。已有权限时，应用在 `Application.attachBaseContext` 完成基础 Context 安装后启动记录器，以 attach 入口为冷启动会话起点。离开前台或点击「停止记录」会结束会话。
3. 记录结束后，点击「复制当前报告」。记录过程中复制会在剪贴板内容中标注未结束快照。

应用分别记录 `getLastKnownLocation` 缓存、普通 `LocationListener` 回调，以及 GPS 和 network 各一次 `getCurrentLocation`。1.4（versionCode 5）同时对 SDK 枚举中的 fused provider 记录这三种路径。SDK 未列出 fused 时会记作未覆盖。单次请求也可能返回缓存。启动与首次窗口焦点各有一段最多 40 个 50 毫秒时点的缓存采样，两个窗口共享重合的读取。记录器跳过已经错过的时点，并标出迟到的 SDK 返回。首个焦点未观察到时，页面窗口也未覆盖。

1.7.1（versionCode 12）的自动冷启动和热返回，会在取得定位服务后先尝试一次 GPS 缓存读取，阶段标为 `early_gps_before_receiver`。报告仍保存 SDK 调用起止时间、空返回和异常。设备枚举出 GPS 时只是移动原首轮请求，未枚举出 GPS 时最多增加一次诊断尝试。定位开关广播登记在这次读取之后，若开关已切换，可能只能从外部 mode 时间线夹逼。首个缓存返回前仍缺少坐标覆盖。手动与广播重录沿用原顺序。

1.4 同时记录前台 NMEA 监听的登记、移除和 SDK 返回的原始句子及时间。支持的句型会附带校验和、fix 状态与解析到的坐标，空结果、无有效 fix、校验失败和未经支持的句型各自保留。NMEA 记录也可能含坐标，和其他原始报告一样仅在本地处理。

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

1.6.3（versionCode 10）可以在重开记录命令后加 `--ez include_nmea false`。仅当次受控会话跳过 NMEA 订阅，JSONL 记录 `nmea_registration` 的 `disabled_by_test` 与 `sdk_attempted=false`。省略参数、显式 `true`、应用自动启动及界面按钮仍按原方式采集。该参数必须用广播的布尔类型传入。

1.7.0（versionCode 11）可以在前台的当次受控会话同时记录普通定位、NMEA 和逐星状态。保持应用可见且窗口有焦点，使用已授予的精确定位权限。

```sh
adb shell am broadcast -a com.codex.locationstartup.RESTART_RECORDING -p com.codex.locationstartup --ez include_gnss_status true
```

省略 `include_gnss_status`、传入布尔 `false`、应用自动启动或使用界面按钮时，逐星状态采集关闭。报告分别记录逐星回调的注册与注销结果、逐星帧和 started、stopped、first_fix 生命周期回调。first_fix 保留 SDK 的 `ttff_ms`。API 36 使用回调进入记录线程时的 elapsed 和 wall 时间，无法把逐星帧与某次定位确定为同一个 fix。停止记录会注销已尝试登记的回调。最多保存 60 个逐星帧，每帧最多 256 条，另最多保存 64 条生命周期回调。超过上限的完整帧不写入，丢弃数留在 `session_end.gnss_status_capture`。只有注销记录时，结论限于 SDK 调用结果。未收到帧、空帧或达到上限时，相应数据的覆盖有限。标准 `Location.extras.satellites` 仍保留 SDK 原始数值及类型。

第一条成功时返回策略快照文件名，第二条返回 `restart_requested`。该结果只确认已请求重录，实际登记与回调以新会话和系统定位服务记录为准。策略快照不调用 AppOps note 或修改权限、定位开关与 Thanox 设置。

### 高程模型参考

1.5（versionCode 6）新增显式的前台高程模型命令。先让启动录制结束，再从已有的本地测试输入填入 WGS84 纬度、经度与椭球高（米）。发送方仍需持有 `android.permission.DUMP`，且页面可见并持有焦点。

```sh
adb shell am broadcast --receiver-foreground -a com.codex.locationstartup.CAPTURE_ALTITUDE_REFERENCE -p com.codex.locationstartup --es lat "$LAT" --es lon "$LON" --es alt "$ALT"
```

广播成功返回预计的 `altitude-reference-*.json` 文件名。工作线程完成后，使用 `adb shell run-as com.codex.locationstartup cat files/location-startup/文件名` 读取本地结果。最多保留 20 份高程报告，重复命令在前一次运行中返回 `altitude_reference_busy`。

转换使用 Android 34 及以上的公开 `AltitudeConverter`。1.6.2（versionCode 9）可加 `--es vertical_accuracy 1.5`，以米为单位指定有限非负浮点值。省略参数时仍用 `0`，显式传 `--es vertical_accuracy absent` 时不设垂直精度。无效参数在模型运行前拒绝。

JSON schema 2 的 `input` 包含实际送入模型的垂直精度及其存在标记。转换只修改该合成参考位置的副本，写入 `output`。报告保留 SDK、固件信息、起止时间、`h − H` 分离及模型返回的 MSL 精度。转换失败或 API 不支持时，分离和精度留空。该命令不会发起位置请求，结果仅用于验证平台模型。

未收到定位不代表无泄漏，mock 字段也不能单独判断定位来源。未出现 `session_end` 的报告属于未完成采样。报告包含原始坐标，请按需分享。

### Shell GPS 参考

1.3 版（versionCode 4）将 `ShellLocationReferenceMain` 一同打包。它在 ADB Shell 的 UID 2000 下使用系统登记的 `com.android.shell` 包上下文和已有权限，只读取 GPS。默认读取一次缓存，无监听或单次请求。普通探针可同时保持前台，仿真配置与系统定位设置无需变更。

```sh
adb push location-startup-probe.apk /data/local/tmp/location-startup-probe.apk
adb exec-out env CLASSPATH=/data/local/tmp/location-startup-probe.apk app_process /system/bin com.codex.locationstartup.ShellLocationReferenceMain > shell-gps-cache.jsonl
```

使用 Windows PowerShell 5 时，用支持字节重定向的终端或程序保存输出，避免把 UTF-8 JSONL 改写为 UTF-16。

需要独立主动采样时，在类名后加 `--request`，将输出写入另一个本地文件。默认主动窗口为 30 秒。1.6.3 可通过 `--request --duration-ms 180000` 显式延长至 180 秒，取值范围为 30000–180000。主动模式提交一条 GPS 持续请求和一次单次请求，并用同一真实身份的 Context 监听公开 GNSS 状态与 NMEA API。主动请求可能增加 GNSS 需求，应单独标注该轮的采样条件。GNSS 注册返回 true 后仍可能没有任何回调。

默认命令要求真实 UID 2000。作为独立系统 UID 对照时，可在参数末尾显式加 `--identity system`，例如 `--request --duration-ms 90000 --identity system`。调用方必须已为真实 UID 1000，入口核对当前 ROM 的 `android` 包和归属，报告标记为 `independent_system_uid_client`。该样本测试系统 UID 路径，主机需核对进程 PID 与 system_server 不同。UID 0 无法通过任一身份校验。

1.6.1（versionCode 8）从 `PackageManager.getPackageUid` 查询 `package_uid`，并写明 `package_uid_source`。Context 的 `ApplicationInfo.uid` 原值保存在 `context_application_uid`，用于诊断系统 Context 的缓存信息。

JSONL 保存原始 NMEA 句子及 SDK 时间，卫星逐项保存 svid、星座、C/N0、仰角、方位、载频、基带 C/N0 及星历等标志。实际收到的启动、停止、首 fix 时间也会单列。退出时独立取消单次请求、移除 GPS、GNSS 状态和 NMEA 监听。报告保留每次 API 尝试的返回和异常。持续定位上限等于请求时长向上取整的秒数。90 秒以内保留 180 个卫星状态帧和 4096 条 NMEA 的上限，更长会话按秒数扩大，180 秒最多各 360 帧和 8192 条。独立上限和遗漏计数写入终态。

Shell 和前台探针使用同一组原始定位字段。比较时核对同一次启动、GPS provider、样本源时间和精度。空结果和 API 异常保留在 JSONL 中，`reference_end.status` 为 `ok`、`no_fix` 或 `error`。主动 GPS fix、NMEA 和卫星状态分别有 `OBSERVED` 或 `UNOBSERVED`。缓存 GPS 结果单列。卫星数为 0 的状态帧也保留。到期后收到的定位或启动／停止事件带有 `after_deadline` 标记，不计入窗口覆盖。`ok` 只表示读取到了坐标，来源仍需通过独立证据确认。缺失终态表示采样未完整结束。参考文件含真实位置候选，只在本地处理，勿上传。

CI 构建使用 JDK 21 和 Android SDK 36。将 `PROBE_UNSIGNED` 设为 `1` 后执行 `python tools/location-startup-probe/build.py`，生成已对齐的 `location-startup-probe-unsigned.apk`。通过 `BUILD_TOOLS_VERSION` 选择已安装的 Build Tools。脚本输出 APK 路径、签名状态和 SHA-256。默认构建使用本地 debug keystore 签名，也可通过 `LOCATION_STARTUP_PROBE_KEYSTORE` 指定原 debug keystore 路径以保留更新身份。
