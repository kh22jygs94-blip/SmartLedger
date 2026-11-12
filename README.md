# SmartLedger

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.10-blue.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Compose-2024.06.00-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![Room](https://img.shields.io/badge/Room-2.6.1-green.svg)](https://developer.android.com/jetpack/androidx/releases/room)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

SmartLedger（Agent 记账）是一款面向 Android 的全自动个人记账应用。应用通过三条独立采集链路自动捕获微信、支付宝的支付通知并解析入账，用户无需手动录入即可完成日常开销记录。

---

## Features

- **Automated Multi-Channel Capture** — Three independent data ingestion paths ensure maximum coverage:
  `NotificationListenerService` (primary, lowest latency) · `AccessibilityService` + ML Kit Chinese OCR (fallback for WeChat's custom-drawn UIs) · `ShareActivity` (manual entry via system Share Sheet)
- **Deduplication via Fingerprinting** — Each transaction is hashed by `sha1(amount + direction + merchant + timeBucket)`; identical entries within a ±3 minute window are treated as duplicates and discarded regardless of capture channel.
- **Confidence-Based Review Queue** — Records below a configurable confidence threshold are routed to a dedicated pending queue surfaced via a badge on the navigation icon, balancing automation accuracy with manual oversight.
- **24-Page Horizontal Calendar** — Lazy-paged month grid spanning Jan 2026 through Dec 2027, with per-cell income / expense annotations rendered directly on a single `Canvas` to eliminate per-cell composable overhead.
- **Single-Canvas Calendar Rendering** — 42-day grid drawn in one Canvas pass using native `TextPaint`; `dumpsys gfxinfo` benchmarks show Janky frames reduced from **5.5% → 1.9%**, and the 90th percentile frame time from **16 ms → 12 ms** under continuous swipe.
- **Room + Flow Reactive Architecture** — Five data streams (`transactions`, `categories`, `dayTotals`, `pendingCount`, `appSettings`) combined into a single `StateFlow<LedgerUiState>`, driving all screens without manual wiring.
- **Index-Optimized Persistence Layer** — Composite indices on `(status, occurredAt)` and `(amountCents, occurredAt)` ensure the primary query path hits the index directly without table scans.
- **Adaptive Layout** — `NavigationBar` on phones, `NavigationRail` on large screens (tablets), selected at runtime from `WindowMetrics`.
- **60 / 120 Hz Refresh Lock** — Per-window frame rate control via `Window.attributes.preferredRefreshRate`, scoped to the app only without affecting system display settings.
- **Built-In Diagnostics** — CPU usage, memory footprint, and frame time sampled at 1 Hz and plotted in-app; sampling can be stopped globally from a single toggle in settings.

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                     Entry Points                        │
│  ShareActivity        NotificationListenerService       │
│  (Intent.EXTRA_TEXT)  (System-bound, auto-triggered)    │
│                                    │                    │
│                    AccessibilityService                 │
│                    (ML Kit OCR fallback)                │
└──────────────────────┬─────────────────────────────────┘
                       │ TransactionEntity.Builder
                       v
┌──────────────────────────────────────────────────────────┐
│                 LedgerRepository                         │
│  - sha1 fingerprint dedup                                │
│  - confidence scoring                                    │
│  - Room write (suspend on I/O dispatcher)               │
└──────────────────────┬─────────────────────────────────┘
                       │ Flow<LedgerUiState>
                       v
┌──────────────────────────────────────────────────────────┐
│                 LedgerViewModel                          │
│  combine(5 streams) → homeFlow → state                  │
└──────────────────────┬─────────────────────────────────┘
                       │ StateFlow / State
                       v
┌──────────────────────────────────────────────────────────┐
│                  UI Layer (Jetpack Compose)             │
│  HomeScreen · StatsScreen · LogScreen                    │
│  DiagnosticsScreen · SettingsScreen · AddTransactionSheet│
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│                 Persistence (Room 2.6.1)                 │
│  tables: transactions · categories                       │
│  indices: (status, occurredAt) · (amountCents, occurredAt)│
│  migrations: 1 → 2 → 3 (see data/Migrations.kt)         │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│           Lifecycle & Reliability Services              │
│  BootReceiver  →  KeepAliveService (foreground, boot)    │
│  AccessibilityHealer (re-enables read screen after kill) │
│  ServiceHealth (runtime health checks)                  │
└──────────────────────────────────────────────────────────┘
```

### Capture Flow

All three capture channels run **concurrently and independently**. Upon receiving a payment notification, each channel:

1. Extracts candidate text from the notification body, OCR result, or share intent.
2. Runs `PaymentParser` to pull amount (Long, cents), direction (INCOME / EXPENSE / TRANSFER / REFUND), merchant, and confidence score.
3. Computes the dedup fingerprint and queries Room. If an identical record exists within ±3 minutes, the record is discarded immediately.
4. If confidence < threshold, the record is inserted with status `PENDING`; otherwise `COMPLETED`.
5. The five data streams reactively propagate the update to all subscribed Compose screens.

---

## Tech Stack

| Area | Choice |
|---|---|
| Language | Kotlin 2.0.10 |
| UI | Jetpack Compose BOM 2024.06.00 · Material 3 |
| State | Coroutines · Flow · `StateFlow<LedgerUiState>` |
| Persistence | Room 2.6.1 · KSP code gen · schema version 3 |
| OCR | ML Kit Text Recognition Chinese 16.0.1 (bundled model, offline) |
| Backend Services | NotificationListenerService · AccessibilityService |
| AGP / Gradle | 8.5.2 · KTS · Version catalog |
| SDK | minSdk 26 · compileSdk / targetSdk 34 · JDK 17 |

---

## Build

### Prerequisites

- JDK 17 or later
- Android Studio Hedgehog (2023.1.1) or later
- Android SDK Platform 34

### Clone

```bash
git clone https://github.com/kh22jygs94-blip/SmartLedger.git
cd SmartLedger
```

### Gradle

```bash
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug

# Output
# app/build/outputs/apk/debug/app-debug.apk
```

### Install on Device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release Build Note

`build.gradle.kts` sets `isMinifyEnabled = false` for release builds. R8 / ProGuard is intentionally disabled because the ML Kit OCR bundled model file paths may be rewritten during obfuscation, causing runtime load failures. Enable after ProGuard keep rules are verified.

---

## Permissions

| Permission | Purpose | Acquisition |
|---|---|---|
| `POST_NOTIFICATIONS` | Foreground-service notifications on Android 13+ | Runtime dialog |
| `RECEIVE_BOOT_COMPLETED` | Start keep-alive foreground service after boot | Auto-granted |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Reduce aggressive background kills | User-initiated settings flow |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Core: bind to notification event stream | User opens Settings → Notifications → allow |
| `BIND_ACCESSIBILITY_SERVICE` | Core: capture node tree + perform screen capture | User opens Settings → Accessibility → enable |
| `WRITE_SECURE_SETTINGS` | Auto-re-enable AccessibilityService after kill | **ADB one-time** (signature-level) |
| `DUMP` · `READ_LOGS` · `PACKAGE_USAGE_STATS` | Diagnostics panel reads system dropbox / usage events | **ADB one-time** (development-only) |

One-time adb grants (debugging only, production builds should remove):

```bash
adb shell pm grant com.example.smartledger android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.example.smartledger android.permission.DUMP
adb shell pm grant com.example.smartledger android.permission.READ_LOGS
adb shell pm grant com.example.smartledger android.permission.PACKAGE_USAGE_STATS
```

---

## Project Layout

```
SmartLedger/
├── app/src/main/
│   ├── java/com/example/smartledger/
│   │   ├── MainActivity.kt              # Application entry point
│   │   ├── SmartLedgerApp.kt            # Application subclass
│   │   ├── ShareActivity.kt             # Manual capture (Intent.EXTRA_TEXT)
│   │   ├── data/                        # Entity · DAO · Repository · Migration
│   │   ├── service/                     # NotificationListener · Accessibility · Boot
│   │   ├── parser/                      # PaymentParser · CategoryClassifier
│   │   ├── util/                        # Format · FrameRateControl · DiagnosticsMonitor
│   │   └── ui/                          # Compose screens + LedgerViewModel
│   ├── res/
│   └── AndroidManifest.xml
├── app/schemas/                          # Room schema JSON (committed for Migration history)
├── gradle/libs.versions.toml             # Version catalog
├── gradle/wrapper/
├── LICENSE
├── README.md
└── CONTRIBUTING.md
```

---

## Roadmap

- [ ] StatsScreen monthly trend charts (income vs. expense bars)
- [ ] Merchant-rule-based auto-categorization (regex / keyword matching)
- [ ] WebDAV / custom HTTP cloud sync
- [ ] CSV import (Alipay monthly statement export)
- [ ] Confidence-threshold per-channel tuning via settings

---

## Contributing

Pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for development environment setup, Room Migration workflow, and commit message conventions.

### Commit Message Convention

This project follows [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

| Prefix | Description |
|---|---|
| `feat:` | New feature |
| `fix:` | Bug fix |
| `perf:` | Performance optimization |
| `refactor:` | Code change that neither fixes a bug nor adds a feature |
| `docs:` | Documentation changes |
| `chore:` | Miscellaneous maintenance |

---

## License

MIT License — see [LICENSE](LICENSE).
