# Agent 记账 📒

> 一个跑在 Android 上的全自动记账应用。微信/支付宝账单自动识别、自动入账、自动分类，
> 你只管花钱，账我帮你记。

## ✨ 特性

- **三链路自动采集**：通知监听（最快、最准）+ 无障碍读屏 + ML Kit 中文 OCR 兜底
- **防错账机制**：金额 + 方向 + 商户 + 时间桶 生成去重指纹，置信度低的记录自动拦到待确认队列
- **待确认红点角标**：首页导航图标右上角实时显示未处理的记录数
- **月历 + 当天明细**：24 页滑月历（2026–2027），格子内直接标注当日收支，点哪看哪
- **秒级翻页**：42 格日格由单 Canvas + 原生 TextPaint 一次性绘制，`dumpsys gfxinfo` 量化 Janky 帧 5.5% → 1.9%
- **Room + Flow 响应式**：联合索引命中主查询，5 路数据流 combine 聚合驱动界面实时刷新
- **60/120Hz 可切换**：仅影响本应用窗口的锁帧率，系统其他 App 不受影响
- **手机 / 平板自适应**：NavigationBar 在手机、NavigationRail 在大屏，一套代码两种形态
- **内置诊断面板**：CPU / 内存 / 帧率实时曲线，一键关闭时全部采样停止

## 🏗️ 架构

```
┌─────────────────────────────────────────────────────────────┐
│                     UI Layer (Compose)                      │
│  HomeScreen · StatsScreen · LogScreen · Diagnostics · ...   │
└──────────────────────┬──────────────────────────────────────┘
                       │ StateFlow / State
┌──────────────────────▼──────────────────────────────────────┐
│                ViewModel (LedgerViewModel)                   │
│  combine(5 flows) → homeFlow → state                        │
└──────────────────────┬──────────────────────────────────────┘
                       │ Flow
┌──────────────────────▼──────────────────────────────────────┐
│  Repository ───── Room DAO (联合索引 status+time / amt+time)  │
│                                  ↑                           │
│        NotificationListenerService  │  ScreenReaderService   │
│        (parse → fingerprint → enqueue)  │  (OCR → fallback)  │
└─────────────────────────────────────────────────────────────┘
```

**采集链路说明**：
1. **NotificationListenerService**：系统把微信/支付宝的支付通知直接派发过来，文本解析后用指纹去重，最稳。
2. **AccessibilityService + ML Kit OCR**：微信自绘界面读不到节点文本时，截当前屏走本地 OCR（模型打包在 APK 里，全程离线）。
3. **ShareActivity 兜底**：支付宝账单页长按 → 分享 → Agent 记账，手动入口。

## 🛠 技术栈

| 领域 | 选型 |
|---|---|
| 语言 | Kotlin 1.9 |
| UI | Jetpack Compose (BOM 2024.06) · Material3 |
| 持久化 | Room (KSP) · Flow |
| 后台 | NotificationListenerService · AccessibilityService |
| OCR | ML Kit Text Recognition (Chinese, bundled) |
| 并发 | Coroutines · Flow |
| 构建 | Gradle (KTS) · version catalog |
| SDK | min 26 · target 34 · JDK 17 |

## 📦 构建

```bash
# 克隆（或直接在 GitHub Desktop 里 "File → Add Local Repository"）
git clone https://github.com/<your-username>/SmartLedger.git
cd SmartLedger

# 首次构建会自动下载 Gradle Wrapper 和依赖
.\gradlew.bat assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

**前置条件**：
- JDK 17+
- Android Studio Hedgehog / Iguana（或更高）
- Android SDK Platform 34

## 🔐 权限清单

| 权限 | 用途 | 是否需要用户/ADB 授予 |
|---|---|---|
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 | 运行时弹窗 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启常驻服务 | 自动 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 减少被系统后台清理 | 设置页手动 |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 通知监听核心能力 | 设置页手动开启 |
| `BIND_ACCESSIBILITY_SERVICE` | 无障碍读屏核心能力 | 设置页手动开启 |
| `WRITE_SECURE_SETTINGS` | 读屏被关后自动恢复 | **ADB 一次性** |
| `DUMP` / `READ_LOGS` / `PACKAGE_USAGE_STATS` | 诊断面板读系统日志 | **ADB 一次性** |

ADB 授予一次性权限（调试用，生产版通常不包含）：
```bash
adb shell pm grant com.example.smartledger android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.example.smartledger android.permission.DUMP
adb shell pm grant com.example.smartledger android.permission.READ_LOGS
adb shell pm grant com.example.smartledger android.permission.PACKAGE_USAGE_STATS
```

## 🧩 目录结构

```
SmartLedger/
├── app/src/main/
│   ├── java/com/example/smartledger/
│   │   ├── MainActivity.kt
│   │   ├── SmartLedgerApp.kt
│   │   ├── ShareActivity.kt
│   │   ├── data/          # Room Entity · DAO · Repository
│   │   ├── service/       # 通知监听 · 读屏 · 保活 · 开机自启
│   │   ├── parser/        # 通知文本解析 + OCR 后处理
│   │   ├── util/          # Format · FrameRateControl · DiagnosticsMonitor
│   │   └── ui/            # Compose 界面 + ViewModel
│   ├── res/
│   └── AndroidManifest.xml
├── app/schemas/           # Room schema 导出（纳入版本控制）
├── gradle/libs.versions.toml
└── gradle/wrapper/
```

## 🗺️ 路线图

- [ ] 统计页趋势图（月度支出/收入柱状）
- [ ] 规则引擎：按商户自动分类（正则 / 关键词）
- [ ] 云同步（WebDAV / 自建 HTTP）
- [ ] 导入 CSV（支付宝账单导出）

## 🤝 贡献

欢迎提 PR 和 Issue。开发环境配置见 `CONTRIBUTING.md`。

## 📄 许可

MIT License — 见 `LICENSE`。
