# fc-manifest-0.13.0.json 同步注记（vendored 副本）

本文件说明 consema-kt 仓 `docs/fc-manifest-0.13.0.json` 副本的来源与同步机制。载体对应波 3 对抗审计派工纪律第 4 条「vendored 文档副本生成机制」与总指挥裁决 R12（组 W3-04）。

## 副本机制（为何不入版本控制）

本仓 `docs/fc-manifest-0.13.0.json` 被 `.gitignore` 显式忽略（`/docs/fc-manifest-0.13.0.json`，见 .gitignore 注释）：

- 单一权威 = consema 规范仓（`consema/docs/fc-manifest-0.13.0.json`）。
- 本仓 CI（`.github/workflows/ci-kotlin.yml`、`.github/workflows/release.yml` 的 provision 步骤）在运行时从 consema checkout 复制该文件覆盖本副本（`Copy-Item … -Force`）；提交一份副本进本仓会分叉权威，故不入库。
- 本副本仅供本地开发 / 离线运行（conformance runner、capability parity 等读取 `digests.conformance_suite` 与 `capability_set` 记录）。

## 来源与同步

- source: consema@e6d0246（母仓 HEAD，2026-08-14 重同步时刻；内容 sha256 `21141047…`）
- synced: 2026-08-14（波 3 W3-04 修复，agent F3；W3-12/F7 重同步：母仓 f58dc1f 删注记字符串行号后副本逐字节重随）
- 同步范围（相对旧副本 dbd8e95f… 的 123 行差异全量对齐）：
  - `digests.dependency_lock_digest`（:66-67）：Cargo.lock sha256 死钉 `0adbb56b…`（2026-08-07 旧值）→ `4ada9e74…`（2026-08-14 实测），并补齐母仓同款重算命令与旧值失效注记。
  - gates 状态对齐：security（:456）、api_product（:650）由 partial → complete（其全部子条目本就 complete/closed）；quality（:339）、Q-7（:406）、C-2（:807）、C-3（:821）保持 partial（母仓同值）。
  - `evidence_note`、conformance_suite、全部证据行号/节锚与母仓逐字节一致。
- 同步后 sha256：`211410478b455ec92ee7e6ad1df8f17fa2b4258e9db6c633debb1deb0544c407`（与 consema@e6d0246 的 `docs/fc-manifest-0.13.0.json` 逐字节一致）。

## 同步 / 比对命令

```bash
# 母仓权威内容（LF 规范态）
git -C <consema-checkout> show HEAD:docs/fc-manifest-0.13.0.json | sha256sum
# 本仓副本
sha256sum docs/fc-manifest-0.13.0.json
# 两值一致即为同步；不一致时用母仓内容覆盖本副本：
git -C <consema-checkout> show HEAD:docs/fc-manifest-0.13.0.json > docs/fc-manifest-0.13.0.json
```

## 收口注记（裁决 R12 建议）

若后续实测本副本零功能引用（CI 全部由 provision 覆盖，本地 runner 亦改为直接读母仓 checkout），建议仅母仓持有本文件、本仓副本不再保留；本波不删除。
