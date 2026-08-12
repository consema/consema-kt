# Consema Kotlin（consema-kt）

Consema 语言中立契约（RFC 0016）的 **Kotlin/JVM 实现**仓库。本仓库是 Consema 六仓
拆分中的 Kotlin 仓：规范权威（RFC、docs、路线图、跨语言 conformance suites）在
[github.com/consema/consema](https://github.com/consema/consema)；本仓承载
Kotlin 实现与跨语言差分验证工具。

Version: 1.0.0-rc.1（`kotlin/build.gradle.kts` rootProject version；CI
check-version-consistency job 断言与 README 一致）。

## 布局

- `kotlin/`：Kotlin/JVM 包（运行时零依赖——build.gradle.kts 的 runtime
  classpath 为空，全部依赖 test-scoped）。完整文档见
  [kotlin/README.md](kotlin/README.md)。
- `scripts/`：跨语言差分验证脚本（byte parity / normalized differential /
  protocol exchange）。脚本构建 consema-rs 的 Rust emitter 并对拍 Kotlin 实现；
  Rust 侧来自 consema-rs 仓 checkout（CI 多仓模式），conformance 数据来自规范仓 checkout。
- `.github/workflows/ci-kotlin.yml`：Kotlin 门禁（K2JVMCompiler 直驱 + 单测 +
  零依赖）、conformance runner 门禁（18 suites / 508 cases）与 Kotlin-Rust 差分
  门禁（windows-latest 多仓 checkout）。

## 构建与测试

本仓库无 Gradle wrapper（设计 §7.3 的后续 L0-batch 项），验证直驱 JVM
K2JVMCompiler，与提交的验证脚本和 CI 完全一致：

```text
# CI 方式（provision kotlinc 2.2.0 + Temurin 17，见 ci-kotlin.yml）
powershell -File scripts/kotlin-verify-byte-parity.ps1
powershell -File scripts/kotlin-verify-normalized-differential.ps1
powershell -File scripts/kotlin-verify-protocol-exchange.ps1
```

## 链接

- 规范仓（RFC / docs / 路线图）：https://github.com/consema/consema
- Rust 参考实现：https://github.com/consema/consema-rs
