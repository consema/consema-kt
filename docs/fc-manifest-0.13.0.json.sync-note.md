# fc-manifest-0.13.0.json 同步注记（vendored 副本）

本文件说明 consema-kt 仓 `docs/fc-manifest-0.13.0.json` 副本的来源与同步机制。载体对应波 3 对抗审计派工纪律第 4 条「vendored 文档副本生成机制」与总指挥裁决 R12（组 W3-04）。

## 副本机制（为何不入版本控制）

本仓 `docs/fc-manifest-0.13.0.json` 被 `.gitignore` 显式忽略（`/docs/fc-manifest-0.13.0.json`，见 .gitignore 注释）：

- 单一权威 = consema 规范仓（`consema/docs/fc-manifest-0.13.0.json`）。
- 本仓 CI（`.github/workflows/ci-kotlin.yml`、`.github/workflows/release.yml` 的 provision 步骤）在运行时从 consema checkout 复制该文件覆盖本副本（`Copy-Item … -Force`）；提交一份副本进本仓会分叉权威，故不入库。
- 本副本仅供本地开发 / 离线运行（conformance runner、capability parity 等读取 `digests.conformance_suite` 与 `capability_set` 记录）。

## 来源与同步

- source: consema@ccc9943（母仓 HEAD，2026-08-15 波 4 R5 统一 provision 钉、F2 再锚；内容 sha256 `5cb4ab51…`）
- synced: 2026-08-14（波 3 W3-04 修复，agent F3；同步来源 consema@0146e6f1，本仓 commit 6ab5ef9；W3-12/F7 重同步：同步来源 consema@e6d0246，本仓 commit 6e87ce0——f58dc1f 是触发重同步的原因提交（删注记字符串行号），不是同步来源）；2026-08-15 波 4 R5：source 重锚 9aa6597（母仓 70e8884 R27/R40/R38 修订 manifest：锚点、C-2 freeze、审计计数 76→83 —— 源 sha256 变为 3fdf9a77；本仓 gitignore 副本为旧内容 21141047，已按下方命令重随）；2026-08-15 波 4 补派 F2：source 再锚 ccc9943（母仓 b8bf4cb R40 把 manifest 证据中两处裸行号改为字段锚「行号可能漂移，以字段名为锚」+ ccc9943 re-vendor —— 源 sha256 变为 5cb4ab51）；2026-08-15 G2 复核：实测本仓副本仍为 3fdf9a77（过期），已按下方命令重随并 hash 实证 5cb4ab51
- 同步范围（相对旧副本 dbd8e95f… 的 123 行差异全量对齐；条目一律以 gate id / JSON 路径为锚——manifest 内容每次重同步即漂移，行号引用必然失效，wave-3 锚点约定与波 4 复查）：
  - `digests.dependency_lock_digest`：Cargo.lock sha256 死钉 `0adbb56b…`（2026-08-07 旧值）→ `4ada9e74…`（2026-08-14 实测），并补齐母仓同款重算命令与旧值失效注记。
  - gates 状态对齐：`security`、`api_product` 由 partial → complete（其全部子条目本就 complete/closed）；`quality`、`Q-7`、`C-2`、`C-3` 保持 partial（母仓同值；2026-08-15 波 4 复核：现行清单中这些 id 位于 337/404/454/648/805/819 行附近，id 为锚，行号会漂移）。
  - `evidence_note`、conformance_suite、全部证据节锚与母仓逐字节一致。
- 同步后 sha256：`5cb4ab518cc3b3e1754a07f21953693b4b9ec113443d44c6ed8a6d4fa8faa6f8`（与 consema@ccc9943 的 `docs/fc-manifest-0.13.0.json` 逐字节一致）。

## 同步 / 比对命令

```bash
# 母仓权威内容（LF 规范态；统一 provision 钉 ccc9943）
git -C <consema-checkout> show ccc99430a6e3003bc1b0830d81cbad245323f0a4:docs/fc-manifest-0.13.0.json | sha256sum
# 本仓副本
sha256sum docs/fc-manifest-0.13.0.json
# 两值一致即为同步；不一致时用母仓内容覆盖本副本：
git -C <consema-checkout> show ccc99430a6e3003bc1b0830d81cbad245323f0a4:docs/fc-manifest-0.13.0.json > docs/fc-manifest-0.13.0.json
```

## 收口注记（裁决 R12 建议）

若后续实测本副本零功能引用（CI 全部由 provision 覆盖，本地 runner 亦改为直接读母仓 checkout），建议仅母仓持有本文件、本仓副本不再保留；本波不删除。
