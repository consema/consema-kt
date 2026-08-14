# Changelog

Consema 遵循 Semantic Versioning。本仓变更记录以规范仓 CHANGELOG 为权威；完整历史与跨语言时间线见 github.com/consema/consema 的 CHANGELOG.md。

## 1.0.0-rc.1（2026-08-12）

六仓拆分落地：本仓自规范仓（github.com/consema/consema）拆分独立（2026-08-12），承载 Kotlin/JVM 实现（K2JVMCompiler 2.2.0 直驱验证 + Temurin 17，运行时仅 kotlin-stdlib 及 KGP 注入的传递 org.jetbrains:annotations，version 1.0.0-rc.1）。

- L0-L4 落地（2026-08-12，随六仓拆分自规范仓继承）：core / graph / protocol / document + 8 格式家族 + root facade + conformance runner（实现树由本仓根提交 95cf5d6 携带，其 commit message 为规范仓 fuzz 证据积累、与 L0-L4 事件无关——跨仓历史继承，完整时间线见规范仓 CHANGELOG）；
- L5 差分 harness（2026-08-12 · 233ee66）：byte-parity / normalized differential / protocol-exchange 跨语言差分 + 五语言 CI workflow；
- 首跑缺陷修复（2026-08-12 · f198335）：kotlin jar 供给 + TestShim.kt 入库；
- conformance 519/519（18 套 / 聚合 digest cfd6e296 共钉）+ capability parity；
- CI（ci-kotlin.yml）：gradlew 单测（wrapper 驱动）+ 依赖面门禁、conformance runner 门禁、Kotlin-Rust 差分门禁；
- 完整历史与跨语言时间线见规范仓 CHANGELOG。
