# Contributing

Thank you for considering a contribution to SmartLedger. This document describes the development environment, build process, and commit conventions.

## Development Environment

| Tool | Version |
|---|---|
| JDK | 17 or later |
| Android Studio | Hedgehog (2023.1.1) or later |
| Android SDK Platform | 34 |
| OS | Windows / macOS / Linux |

No additional system dependencies are required. Gradle Sync on first open will download all required dependencies.

## Build & Run

```bash
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

Or run from Android Studio with **Shift + F10**.

## Install on a Physical Device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.smartledger/.MainActivity
```

Enable **USB debugging** in Android Developer Options and accept the host RSA key prompt on the device.

## Room Schema & Migrations

`app/build.gradle.kts` exports Room schema JSON to `app/schemas/` via KSP. Every change to an `@Entity` class must be accompanied by the following checklist:

1. Run `./gradlew :app:compileDebugKotlin` to regenerate schema JSON.
2. Commit the new `.json` file in `app/schemas/<database>/<version>.json`.
3. Add a migration class in `data/Migrations.kt` with `MIGRATION_<from>_<to>`.
4. Register the migration in `AppDatabase.kt` under `Room.databaseBuilder(...).addMigrations(...)`.
5. Update the `version` property on `@Database` to the new integer.

> **Important**: Incremental schema changes that drop existing columns or tables are breaking and require a destructive migration or a data reset for existing users.

## Code Style

SmartLedger follows the [official Kotlin style guide](https://kotlinlang.org/docs/coding-conventions.html) and the default formatting shipped with Android Studio.

Additional project conventions:

- **Compose screens** use the `Screen` suffix: `HomeScreen`, `StatsScreen`, `DiagnosticsScreen`.
- **State container** is `LedgerViewModel` — single `StateFlow<LedgerUiState>` drives all UI.
- **DAO functions returning Flow** are the primary query path; synchronous queries are allowed only for one-shot writes.
- **All monetary values** are represented as `Long` in cents. Floating-point is not used anywhere in the data layer.
- **Package layout**: `data/` (entities + DAO + repository + migrations) · `service/` (background capture & recovery) · `parser/` (text extraction + classification) · `util/` (formatting + system utilities) · `ui/` (Compose screens + ViewModel + theme).

## Static Analysis

No ktlint / detekt configuration is currently enforced. Before submitting a PR, please ensure:

```bash
./gradlew :app:assembleDebug
```

compiles cleanly with no warnings introduced by your change.

## Submitting a Pull Request

1. Fork this repository on GitHub.
2. Create a feature branch:

   ```bash
   git checkout -b feat/your-feature
   ```

   Use one of the Conventional Commits prefixes as the branch type (`feat`, `fix`, `perf`, `refactor`, `docs`).

3. Commit your changes. See the commit message conventions in `README.md` → Contributing.

4. Push the branch and open a Pull Request. The PR body should describe:
   - What changed (scope)
   - Why (motivation)
   - How you verified it ran on a device

## Issue Guidelines

When filing a bug report, include:

- Android version, device model, and screen size
- Exact steps to reproduce
- Expected behavior vs. actual behavior
- Logcat excerpt if relevant (`adb logcat -d -s SmartLedgerService PaymentParser DiagnosticsMonitor`)

---

By contributing, you agree that your contributions will be licensed under the project's [MIT License](LICENSE).
