# 适配进度

本文件记录各系统 / 机型在两个模拟方案下的**实机验证**情况，是"现在到底适配到哪了"的唯一出处。
只登记在真机上实际测过的结果，没测过的一律写"未验证"，不要凭推测填写。

- 两个方案的区别见 Release 说明：`scoped` = 非全局（Hook 作用域内的目标 App），`global` = 全局（Hook 系统进程）。
- 全局方案按系统走不同的适配器，架构说明见 [vendor/README.md](xposed/src/main/java/com/vincenthzr/locationspoofer/xposed/hooks/vendor/README.md)，
  如何定位系统接口见 [ADAPTATION_GUIDE.md](xposed/ADAPTATION_GUIDE.md)。

- 全局方案实测时，App"系统适配 → Hook 运行状态"页会列出每个组件是否找到类、方法是否挂上，可作为登记依据。
- "按系统"表格第一列的系统名会被 App 用来查找"本机状态"（关键字见 `core-geo` 的 `RomFamily.progressKeywords`），改名时注意保留。

**状态说明**：✅ 实测通过 · ⚠️ 部分可用（见备注） · ❌ 实测不可用 · ❔ 未验证

---

## 全局方案（global）

### 按系统

| 系统 | 适配器 | 状态 | 已验证的系统版本 | 备注 |
|---|---|---|---|---|
| 小米 HyperOS / MIUI | `HyperOsVendor` | ✅ | HyperOS 4、HyperOS 3 | HyperOS 4 为完整实测；HyperOS 3（REDMI K90 Ultra / OS3.0.308.0.WHPCNXM）实测 global 定位注入可用，其余组件未逐一复验，见 [#74](https://github.com/HuangZhuoRui/LocationSpoofer/issues/74) |
| OPPO ColorOS / 一加 OxygenOS | `ColorOs16Vendor`（API 36）/ `ColorOsVendor` | ⚠️ | ColorOS 16 / Android 16 | PLQ110 的系统定位、GNSS/NMEA、Wi-Fi、电话及 BLE 已由独立 SDK Probe 验证，见下表及[本轮验收](docs/ITERATION_AUDIT.md)；PJX110 保留历史范围，OxygenOS 和目标应用兼容性未验证 |
| 三星 One UI | `OneUiVendor` | ❔ | — | 同上 |
| vivo OriginOS、荣耀 MagicOS、魅族 Flyme 等 | 无（落到 `AospVendor`） | ❔ | — | 尚无专用适配器 |
| 原生 AOSP / 类原生 | `AospVendor` | ❔ | — | 基线适配器，尚无实机验证记录 |

### 按组件（HyperOS 4）

| 组件 | 所在进程 | 状态 | 备注 |
|---|---|---|---|
| 定位（GPS / 网络 / 被动 provider） | system_server | ✅ | |
| Wi-Fi 扫描结果与已连接信息 | system_server | ✅ | |
| 网络状态脱敏（NetworkCapabilities） | system_server | ✅ | |
| AppOps 模拟定位检测规避 | system_server | ✅ | |
| 基站信息 | com.android.phone | ✅ | |
| 蓝牙扫描 | com.android.bluetooth | ❔ | Android 17 的扫描入口已迁到 `le_scan.ScanBinder.registerAndStartScan`，已适配并确认挂载成功（Hook 运行状态页）；虚拟信标的实际派发尚未实测 |

### 按组件（ColorOS 16，PJX110）

以下实机结果来自拆分前已安装的完整版本。移除原生传感器及修订配置兜底后的评审 APK 已构建，尚未做重启端到端复验。

| 组件 | 状态 | 验证范围 |
|---|---|---|
| Hook 状态报告 | ✅ | system_server、电话、蓝牙三份报告可读取，挂载无报错 |
| 配置同步 | ✅ | 拆分前版本五份配置一致；新增失败路径另有 6 项隔离测试 |
| 定位 | ⚠️ | 早期缓存查询通过；持续定位、运动世界校园崩溃复测未完成 |
| Wi-Fi | ⚠️ | 早期扫描与连接信息通过，最终版未复测 |
| 基站 | ✅ | 缓存查询、异步请求、监听；目标与对照应用隔离 |
| 蓝牙 | ✅ | 普通/PendingIntent、地址过滤、批量、主动刷新、发现/丢失、目标切换 |
| 原生传感器 | ❔ | 本评审分支不包含；独立分支评审与验证 |

### 按组件（ColorOS 16，PLQ110，3.0.0-remote.3）

2026-11：Android 16、LSPosed 2.2.1 / API 102、global ARM64 Debug 构建。仅注入三个系统宿主，使用独立公开 SDK Probe；已验证热重载，未执行重启或美团验收。以下结果不扩展到其他设备和应用。

| 组件 | 状态 | 验证范围 |
|---|---|---|
| 框架连接与挂载 | ✅ | 三宿主 9/9 组件挂载；跨两轮热重载保留 PID 与订阅，冷启动区分连接中和未激活 |
| 定位、GNSS 与 NMEA | ✅ | 持续 GPS/网络回调、时间相关漂移、共享高度、24 颗可见/12 颗使用、标识与校验和一致；注销、死亡、暂停及 AppOps 变化通过 |
| 原始 GNSS 测量 | ✅ | 注册前后均覆盖；原生对照有数据，模拟期间为 0，切出模拟范围恢复；跨热重载无混流 |
| 原始导航电文 | ⚠️ | 当前系统入口与拒绝权限路径覆盖；现场硬件对照无电文，未取得正向载荷验证 |
| Wi-Fi 与基站 | ✅ | 源缓存与可见子集分离，最多 12 条、1 个服务小区；同步/异步身份一致；中文 SSID、新旧字段、连接信息和观察时间一致；36 位 NR 为明确测试数据，目标点真实 NR 未取得 |
| 电话状态与活动重载 | ✅ | LTE 场景的 CellInfo、ServiceState、SignalStrength、DisplayInfo、网络类型及五类初始回调一致；100 次采样包含两轮重载，交接短暂不可用后恢复，无现场身份混流 |
| 日志资源控制 | ✅ | 查询类日志按事件限流；节流缓存最多 128 项，采用单调时钟和原子判定；安装生命周期使用 INFO，实际异常保留错误报告；长驻留结果见稳定性验收 |
| BLE | ✅ | 真实扫描对照、地址筛选、空列表/禁用数据、两轮重载、中途拒绝与重新授权、暂停恢复；缺数据不制造固定 iBeacon |
| 事件驱动与空闲 | ✅ | 无需求约 26 秒内，9 个模块宿主线程 CPU 与上下文切换增量为 0；不是长期耗电承诺 |
| 用户数据与配置 | ✅ | 安装包哈希匹配；业务行原样保留，仅更新用户授权的 WiGLE 参数；定位/蓝牙及网络状态恢复 |
| 第三方应用与其他传感器 | ❔ | 美团、运动应用及原生传感器不在本轮验收范围 |

### 实测设备

| 设备 | 系统版本 | 验证日期 | 备注 |
|---|---|---|---|
| 小米 17 Pro Max | HyperOS 4 | 2026-09 | 全局方案开发与验证机 |
| REDMI K90 Ultra (M332BF / warsaw) | HyperOS 3 / OS3.0.308.0.WHPCNXM / Android 16 | 2026-09 | KernelSU (ksud 4.2.0) + LSPosed v2.2.0（7854），global v3.0.0-beta-2 (arm64-v8a)；实测定位注入可用（`getLastLocation` provider=fused/network），其余组件未逐一复验，见 [#74](https://github.com/HuangZhuoRui/LocationSpoofer/issues/74) |
| PJX110 | ColorOS 16 / PJX110_16.0.1.301 / Android 16 | 2026-09 | LSPosed IT 2.1.1（7846），仅限上述部分验证 |
| PLQ110 | ColorOS 16 / Android 16 | 2026-11 | LSPosed 2.2.1 / API 102；3.0.0-remote.3（30006）；系统属性 OTA 原值 PLQ110_11.A.25_0250_202510210424；范围见本轮表 |

---

## 非全局方案（scoped）

| 设备 | 系统版本 | Root / LSPosed | 验证内容 | 状态 |
|---|---|---|---|---|
| 小米 Mi 10 Pro | Android 15 | SukiSU + LSPosed (API 101+) | 高德定位 SDK 回调（`com.bxkj.student` 跑步流程），见 PR #69 | ✅ |

> 非全局方案是项目的原有方案，历史上验证过的机型尚未整理进来，欢迎补充。

---

## 目标应用兼容性

| 应用 | 全局方案 | 非全局方案 | 备注 |
|---|---|---|---|
| 高德地图 | ✅ | ❔ | |
| 百度地图 | ✅ | ❔ | |
| 微信 | ✅ | ❔ | |
| TIM | ✅ | ❔ | |
| 淘宝 / 闲鱼 | ⚠️ | ❔ | 位置已正确模拟，但淘宝发送位置时仍提示"定位失败"，怀疑是服务端 IP 归属地校验，待确认 |

---

## 如何更新本文件

完成一次实机验证后，在对应表格里新增或修改一行，至少写清楚：

1. **设备与系统版本**：系统版本写真实的版本号，可以用下面的命令取得（小米为例）：
   ```bash
   adb shell getprop ro.mi.os.version.name
   ```
   ```bash
   adb shell getprop ro.build.version.release
   ```
2. **Root 方案与 LSPosed 版本**：确认框架支持 libxposed 远程配置；记录实际版本以便复现连接和通知问题。
3. **验证了哪些组件 / 应用**：没测的保持 ❔，不要因为"应该没问题"就标 ✅。
4. **验证日期**：精确到月即可。
5. **问题与现象**：标 ⚠️ 或 ❌ 时写明现象，最好附上关键日志或 issue 链接。

新增了厂商或系统版本适配器（`vendor/profiles/` 下）时，同时在"全局方案 → 按系统"里补一行。
