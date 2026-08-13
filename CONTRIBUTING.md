# Contributing to consema-kt（Consema Kotlin 实现）

Consema 六仓拆分的 Kotlin 仓：本仓承载 Kotlin/JVM 实现（`kotlin/` 包）与
跨语言差分验证工具；规范权威（RFC / docs / 路线图 / conformance suites）在
[规范仓](https://github.com/consema/consema)。

**社区治理以规范仓主文档为准**：报 bug / 提 feature / RFC 流程 / 提交规范 /
评审规范 / 标签体系 / 发布纪律 / 行为准则，一律参见
[consema/CONTRIBUTING.md](https://github.com/consema/consema/blob/main/CONTRIBUTING.md)。
本文件只列本仓特有内容。

## 开发环境

- JDK（CI 用 Temurin 17）+ Kotlin K2JVMCompiler（CI 用 kotlinc 2.2.0）。
- Gradle wrapper 已入库（gradle 8.14；kotlin/gradlew、gradlew.bat、
  gradle/wrapper/）：kotlin-gates 经 gradlew 跑单测与 kover 60% 覆盖率门禁；
  conformance / differential 验证仍直驱 JVM K2JVMCompiler，与提交的验证脚本
  和 CI 完全一致。
- 运行时零依赖（runtime classpath 为空，依赖全部 test-scoped）。

## 构建与测试

验证直驱 JVM K2JVMCompiler（与 CI 一致）：

```text
# CI 方式（provision kotlinc 2.2.0 + Temurin 17，见 ci-kotlin.yml）
powershell -File scripts/kotlin-verify-byte-parity.ps1
powershell -File scripts/kotlin-verify-normalized-differential.ps1
powershell -File scripts/kotlin-verify-protocol-exchange.ps1
```

## 贡献点

- **Kotlin 实现**：`kotlin/` 包（PortableValue / 查询 / 投影 /
  materialization / 结构编辑 + 八格式家族）；完整文档见
  [kotlin/README.md](kotlin/README.md)。
- **差分 harness**：`scripts/` 跨语言差分验证（byte parity / normalized
  differential / protocol exchange）：`kotlin-verify-byte-parity.ps1`、
  `kotlin-verify-normalized-differential.ps1`、
  `kotlin-verify-protocol-exchange.ps1`。脚本构建 consema-rs 的 Rust emitter
  对拍本实现。
- **Conformance 数据同步**：conformance 数据来自规范仓 checkout（CI 多仓
  模式），权威在规范仓，改动必须回规范仓提交后再同步。

## CI 门禁

`.github/workflows/ci-kotlin.yml`：K2JVMCompiler 直驱编译 + 单测 + 零依赖
门禁、conformance runner 门禁（18 suites / 519 cases）与 Kotlin-Rust 差分
门禁（windows-latest 多仓 checkout）。push 到 main 或 PR 均触发；PR 另受
pr-labels.yml 的 kind 标签门禁约束（标签见规范仓 .github/LABELS.md）。

## 发布与安全

- 发布：本仓 [RELEASING.md](RELEASING.md)（Maven Central
  `dev.consema:consema-kotlin`，Central Portal deploy + PGP 签名；tag `v*`
  触发 release workflow，不要手动发布）。
- 安全：[SECURITY.md](SECURITY.md)；披露统一走规范仓 SECURITY.md 的渠道。
