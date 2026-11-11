# 贡献指南

## 开发环境

- JDK 17+
- Android Studio Hedgehog 或更高版本（Gradle Sync 会自动下载依赖）
- Android SDK Platform 34
- Windows / macOS / Linux 均可

## 构建

```bash
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug

# 仅 app 模块
./gradlew :app:assembleDebug
```

首次构建 Gradle Wrapper 会自动下载依赖。

## 安装到设备

```bash
# 直接用 adb
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 或者 Android Studio 里点 Run（Shift + F10）
```

## Room Schema 迁移

`app/build.gradle.kts` 已配置 KSP 导出 schema 到 `app/schemas/`。

每次修改 Entity 后：
1. 跑一次 `./gradlew :app:compileDebugKotlin`
2. 把 `app/schemas/` 下新生成的 `.json` 提交上来
3. 在 `data/Migrations.kt` 里补齐 Migration 类（版本号必须与 schema 版本对齐）
4. 在 `AppDatabase.kt` 的 `migrations` 里注册

## 代码规范

- Kotlin 官方代码风格，Android Studio 默认格式化即可
- Compose 页面以 `Screen` 后缀命名（`HomeScreen`、`StatsScreen`）
- DAO 查询返回 Flow，Repository 层再做组合
- 金额单位统一用 Long（分），避免浮点误差
- 数据层包目录：`data/`（实体 + DAO + Repository）
- UI 层包目录：`ui/`（Compose 页面 + ViewModel）
- 采集服务包目录：`service/`（通知监听、读屏、保活）
- 工具类包目录：`util/`

## 静态检查

目前没有配置 ktlint / detekt。提 PR 后我会手动 review，
建议在本地先跑一遍 `./gradlew :app:assembleDebug` 确保能编译通过。

## 提 PR

1. Fork 本仓库
2. 新建特性分支：`git checkout -b feature/your-feature`
3. 提交改动：`git commit -m "feat: what your change does"`
4. 推送：`git push origin feature/your-feature`
5. 发起 Pull Request，描述清楚改了什么、为什么改、有没有测试

Commit message 约定（Conventional Commits）：
- `feat:` 新功能
- `fix:` 修 Bug
- `perf:` 性能优化
- `docs:` 文档
- `refactor:` 重构
- `chore:` 杂项
