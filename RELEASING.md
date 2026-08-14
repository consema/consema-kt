# Consema Kotlin 发布流程（Maven Central）

本文件是 consema-kt 仓库的发布操作手册（六仓统一纪律见 consema 仓库根
`RELEASING.md`）。发布是**半自动**的：版本 bump、CHANGELOG、tag 由人完成；
tag 推送后 `.github/workflows/release.yml` 执行 `gradle publish`，把
`dev.consema:consema-kotlin:<version>` 发布到 Sonatype Central Portal。

**Maven Central 是六语言中凭证最重的发布通道**：需要 Sonatype 账号 +
namespace 认领 + PGP 签名密钥。凭证均为用户侧一次性动作（下述 §2），
workflow 已写完整，但**凭证未配置前推送 tag 会明确失败**（portal 认证/
签名校验拒绝），这是有意的护栏。

## 1. 发布步骤（人执行的部分）

1. **版本 bump**：改 `kotlin/build.gradle.kts` 的
   `version = "X.Y.Z"`（rootProject version）。`check-version-consistency`
   门禁强制以下位置与 rootProject version 同步（全部是硬门禁，漏改即红；
   每个位置同时被存在性断言覆盖——整段删除同样红，wave-4 R36）：
   - 仓根 `README.md` 的 `Version:` 整行（精确匹配）；
   - `README.md` 快速开始区的 Maven 坐标 `dev.consema:consema-kotlin:X.Y.Z`
     （整词匹配；存在性断言：坐标文本不得整段删除）；
   - `.github/ISSUE_TEMPLATE/bug_report.yml` 环境信息节的
     「当前 X.Y.Z」版本注记（整词匹配；存在性断言：「当前 」注记不得
     整段删除）；
   - `kotlin/src/test/kotlin/toml/TestFixtures.kt` workspace-package 夹具的
     `version = "X.Y.Z"` 字面量（整串匹配；存在性断言：该字面量不得
     删除，wave-4 R36）；
   - （版本载体为 README `Version:` 整行，由 check-version-consistency
     门禁断言；本仓无 shields.io 版本徽章）。
2. **CHANGELOG 策展**：记录本版本变更；跨语言变更同步到
   consema 仓库根 `CHANGELOG.md`（真实历史记录，勿指 docs/CHANGELOG.md 勘误页）。
3. **质量门禁全绿**：main 分支 CI `check (all gates green)` 全绿
   （清单见各仓 ci 配置，含 runtimeClasspath 依赖审计 job）。
4. **打 tag 并推送**（发布动作的唯一触发点）：
   ```bash
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```
   发布 workflow 会先跑两道守卫，全部通过才进入发布路径：
   - **tag 必须指向 origin/main 上的 commit**（`git merge-base
     --is-ancestor`，防止从陈旧/分叉 commit 发布旧代码）；
   - **tag↔版本一致**：tag 去掉 `v` 前缀必须等于
     `kotlin/build.gradle.kts` 的 rootProject version，不一致即 exit 1
     中止。
   随后用 Temurin 17 + 已入库的 Gradle wrapper（gradle 8.14，
   commit c60d31a/b640af6；wrapper 的 distributionSha256Sum 钉住发行版
   下载，见 kotlin/gradle/wrapper/gradle-wrapper.properties；发布 job 另
   经 wrapper-validation-action 校验已入库的 gradle-wrapper.jar）执行
   `gradlew publish`。发布路径先跑 `gradlew test` 作为测试门禁：该 job
   按 ci-kotlin.yml 体例多仓 checkout 规范仓并 provision conformance 数据
   （CONSEMA_REPO 指向 workspace 根），无数据时 conformance/fixture 测试
   必然失败——不要删掉 provision 步骤。差分腿在发布 job 中不设 golden
   环境变量，按 §0.2 skip 纪律打印 documented [SKIP] 后通过；release.yml
   断言 JUnit XML 中的 [SKIP] 标记数 ≤ 3（wave-4 R47，与 kotlin-gates
   同界——实测三个 env-gated 差分腿：differentialByteParity /
   differentialNormalized / protocolExchange），新增静默 skip
   会在发布路径变红。发布 job 不启用 Gradle 缓存。

## 2. 凭证配置（用户侧一次性动作）

### 2.1 Sonatype Central Portal（central.sonatype.com）

1. 注册/登录 central.sonatype.com，认领 namespace **`dev.consema`**
   （需要 GitHub 账号验证，参照门户指引）。
2. 在门户生成 **User Token**（用户名 = token 名，密码 = token 值）。
3. GitHub 仓库 Settings → Secrets and variables → Actions 新建：
   - **`OSSRH_USERNAME`** = token 名
   - **`OSSRH_PASSWORD`** = token 值
4. 首次发布在门户完成一次"验证发布"（发布一个占位/真实版本并等待
   portal 处理完成，确认 artifact 在 Maven Central 可见）。后续版本由
   workflow 自动走 Central Portal 的兼容 staging 端点
   （`https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/`，
   build.gradle.kts publishing 块；旧的 `s01.oss.sonatype.org` 与
   `central.sonatype.com/api/v1/publisher/deploy` 均不存在/已退役）。

### 2.2 PGP 签名密钥

Maven Central 要求所有 artifact（含 pom、module、sources/javadoc jar）
有 PGP 签名：

1. 生成密钥对（如 `gpg --full-generate-key`），把**公钥**发布到
   keys.openpgp.org 或 portal 认可的 keyserver。
2. GitHub 仓库 Secrets 新建：
   - **`SIGNING_KEY`** = ASCII-armored 私钥全文
     （`gpg --armor --export-secret-keys <id>` 的输出）
   - **`SIGNING_PASSWORD`** = 私钥口令
3. 本地验证（可选）：`gradle publishToMavenLocal` 或
   `gradle build`（无密钥时签名步骤自动跳过，见 build.gradle.kts
   `signing` 块注释）。

### 2.3 凭证专人（kotlinx 模式）

与 kotlinx 相同，Maven Central 凭证（portal token + PGP 私钥）建议由
**专人**保管与配置，不随仓库共享；若组织内多维护者需要发布权限，
由该专人代为触发 tag 或按需轮换 token。

## 3. 发布后核对

1. central.sonatype.com → My components 确认发布记录（或 portal 的
   publish history）成功，无 "signature verification failed" 等错误。
2. search.maven.org / mvnrepository.com 上 `dev.consema:consema-kotlin`
   新版本可见（portal 处理通常数分钟内完成）。
3. pom 元数据（name/description/licenses/scm/developers）在 portal 页面
   渲染正常。
4. **javadoc jar 内容核对**（wave-4 R20）：`kotlin/build/libs/`
   下的 `consema-kotlin-<v>-javadoc.jar` 当前是空壳（无 dokka 接线，
   build.gradle.kts `javadocJar` 注释与 RELEASING §4 记录；本地
   `gradlew publishToMavenLocal` 预演确认空壳可被 Maven 本地仓库接受，
   portal 端校验行为以首次发布实测为准）——发布前用
   `jar tf kotlin/build/libs/consema-kotlin-<v>-javadoc.jar` 确认内容，
   并在发布记录中注明该 jar 为空壳；若 portal 拒绝空壳 javadoc 是
   post-1.0.0 补 dokka 的触发条件。
5. 跨语言同步：按 consema 仓 RELEASING.md 的检查单核对其他语言仓的发布
   状态。

## 4. API reference 文档与依赖审计（决策：P2）

- **dokka 文档构建**：dokka 是 Gradle 插件；Gradle wrapper 已入库（gradle 8.14，commit c60d31a/b640af6）
  （设计 §7.3 的后续 L0-batch 项）。wrapper 条件已满足，但 API reference
  的 docs CI job 仍未引入（recorded gap，六仓审计 G138；P2）。
- **依赖审计**：完整依赖审计（如 OWASP dependency-check 或 Gradle
  dependency verification）需要求值 Gradle 配置。
  `.github/workflows/audit.yml` 已走 wrapper 求值 `runtimeClasspath` 并
  断言无第三方 group（kotlin-stdlib + annotations 除外）；Dependabot 已
  覆盖 `kotlin/build.gradle.kts` 的清单更新（见 .github/dependabot.yml）。
