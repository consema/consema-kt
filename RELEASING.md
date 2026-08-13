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
   `version = "X.Y.Z"`（rootProject version），同时改仓根 `README.md` 的
   `Version:` 行（`check-version-consistency` 门禁强制一致）。
2. **CHANGELOG 策展**：记录本版本变更；跨语言变更同步到
   consema 仓库根 `CHANGELOG.md`（真实历史记录，勿指 docs/CHANGELOG.md 勘误页）。
3. **质量门禁全绿**：main 分支 CI `check (all gates green)` 全绿
   （清单见各仓 ci 配置）。
4. **打 tag 并推送**（发布动作的唯一触发点）：
   ```bash
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```
   发布 workflow 会先校验 tag↔版本一致（tag 去掉 `v` 前缀必须等于
   `kotlin/build.gradle.kts` 的 rootProject version，不一致即 exit 1
   中止），随后用 Temurin 17 + 已入库的 Gradle wrapper（gradle 8.14，
   commit c60d31a/b640af6；wrapper 的 distributionSha256Sum 钉住发行版
   下载，见 kotlin/gradle/wrapper/gradle-wrapper.properties）执行
   `gradlew publish`（发布 job 不启用 Gradle 缓存，且先跑 `gradlew test`
   作为发布路径测试门禁）。

## 2. 凭证配置（用户侧一次性动作）

### 2.1 Sonatype Central Portal（central.sonatype.com）

1. 注册/登录 central.sonatype.com，认领 namespace **`dev.consema`**
   （需要 GitHub 账号验证，参照门户指引）。
2. 在门户生成 **Deploy token**（用户名 = token 名，密码 = token 值）。
3. GitHub 仓库 Settings → Secrets and variables → Actions 新建：
   - **`OSSRH_USERNAME`** = token 名
   - **`OSSRH_PASSWORD`** = token 值
4. 首次发布在门户完成一次"验证发布"（发布一个占位/真实版本并等待
   portal 处理完成，确认 artifact 在 Maven Central 可见），后续版本由
   workflow 自动走 `central.sonatype.com/api/v1/publisher/deploy` 端点。

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
4. 跨语言同步：按 consema 仓 RELEASING.md 的检查单核对其他语言仓的发布
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
