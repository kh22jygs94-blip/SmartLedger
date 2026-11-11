# 贡献指南

## 开发环境

- JDK 17+
- Android Studio Hedgehog 或更高版本
- Android SDK Platform 34
- Windows / macOS / Linux 均可

## 构建

```bash
./gradlew assembleDebug     # 全部
./gradlew :app:assembleDebug
```

首次构建 Gradle Wrapper 会自动下载依赖。

## Room Schema

`app/build.gradle.kts` 里已经配置了 KSP 导出 schema 到 `app/schemas/`。
每次修改 Entity 后跑一次 `./gradlew :app:compileDebugKotlin`，
把新生成的 `.json` 提交上来，方便后续写 Migration。

## 代码规范

- Kotlin 官方代码风格，Android Studio 默认格式化即可
- Compose 页面以 `Screen` 后缀命名（`HomeScreen`、`StatsScreen`）
- DAO 查询返回 Flow，Repository 层再做组合
- 金额单位统一用 Long（分），避免浮点误差

## 提 PR

1. Fork 本仓库
2. 新建特性分支：`git checkout -b feature/your-feature`
3. 提交改动：`git commit -m "..."`
4. 推送：`git push origin feature/your-feature`
5. 发起 Pull Request
