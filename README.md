# Consema Kotlin（consema-kt）

![CI](https://img.shields.io/github/actions/workflow/status/consema/consema-kt/ci-kotlin.yml?branch=main)
![License](https://img.shields.io/github/license/consema/consema-kt)

Consema 语言中立契约（RFC 0002/0003/0004/0006 契约家族；权威仓 docs/rfcs/）的 **Kotlin/JVM 实现**仓库。本仓库是 Consema 六仓
拆分中的 Kotlin 仓：规范权威（RFC、docs、路线图、跨语言 conformance suites）在
[github.com/consema/consema](https://github.com/consema/consema)；本仓承载
Kotlin 实现与跨语言差分验证工具。

Version: 1.0.0-rc.1

（版本行：`kotlin/build.gradle.kts` rootProject version；CI
check-version-consistency job 断言与 README 整行一致。）

## 快速开始

Maven Central 坐标（发布后可用）：`dev.consema:consema-kotlin:1.0.0-rc.1`（Gradle：`implementation("dev.consema:consema-kotlin:1.0.0-rc.1")`）。

把下面内容保存为 `kotlin/quickstart.kt`（仓根相对路径），然后在 **`kotlin/` 目录下**（先 `cd kotlin`）与主源码一起编译后运行（一个 JSON 文档走完 parse → query → edit → render 四条链）：

```text
cd kotlin
kotlinc -J-Xmx2g -jvm-target 17 -d out src/main/kotlin quickstart.kt
java -cp "out;<kotlinc>\lib\kotlin-stdlib.jar" QuickstartKt
```

（上面命令块中的 `src/main/kotlin` 与 `quickstart.kt` 均为 kotlin/ 目录内相对路径，基准目录就是上文的 `cd kotlin` 之后；命令块不受门禁保护——examples job 只比对下方 ```kotlin 栅栏与入库文件的 Trim 后核心代码（两侧剥离 `//` 注释行与 package 行，wave-5 修正「剥离头部注释」声称：实现按注释行剥离，不依赖位置巧合）；命令块为人工同步，按需保持最新。）

注：kotlinc 2.2.0 启动器默认堆为 512 MiB，对 163 个主源文件的 K2 编译会在
IR 阶段耗尽堆——必须加 `-J-Xmx2g`（与 CI 直驱路径的内存档位一致，见
kotlin/gradle.properties 与 ci-kotlin.yml）。本地整仓编译约 2 分钟。

同一示例已入库为 [`kotlin/examples/Quickstart.kt`](kotlin/examples/Quickstart.kt)（带 `package consema.examples`，运行类 `consema.examples.QuickstartKt`），由 CI examples job 与 SdkChain.kt 一起编译并运行验证；CI examples job 还比对 README ```kotlin 栅栏与入库文件（两侧经 Trim 并剥离 `//` 注释行与 package 行后比较，粘贴版与入库版必须保持一致，由门禁强制）。

```kotlin
import consema.core.PvInteger
import consema.document.ProfileId
import consema.json.EditTransactionBuilder
import consema.json.JsonValue
import consema.json.RepresentationPolicy
import consema.json.SemanticAvailability
import consema.json.commit
import consema.parseDocument
import java.math.BigInteger

// 原生语义树成员查找（查询助手；完整操作符查询见 SdkChain 示例）。
fun member(value: JsonValue, name: String): JsonValue = when (val availability = value.objectMembers()) {
    is SemanticAvailability.Available -> {
        val members = availability.value ?: error("not an object")
        members.firstOrNull { m ->
            when (val n = m.name()) {
                is SemanticAvailability.Available -> n.value == name
                is SemanticAvailability.Unavailable -> false
            }
        }?.value() ?: error("member '$name' not found")
    }
    is SemanticAvailability.Unavailable -> error("semantics unavailable: ${availability.reason}")
}

fun main() {
    // 1. parse：json.strict 无损解析，render() 与源字节逐字节一致
    val document = parseDocument("""{"a":1,"b":{"c":2}}""".toByteArray(), ProfileId("json.strict", 1))
    val json = document.asJson() ?: error("not JSON")
    // 2. query：原生语义树读 `b.c`
    val c = member(member(json.root(), "b"), "c")
    // 3. edit：`b.c` 语义替换为 42（CanonicalForProfile），编辑外字节原样保留
    val transaction = EditTransactionBuilder.new(json)
        .semanticScalar(c.nodeRef(), PvInteger(BigInteger.valueOf(42)), RepresentationPolicy.CanonicalForProfile)
        .build()
    val edited = json.commit(transaction).document
    // 4. render：输出 {"a":1,"b":{"c":42}}
    println(String(edited.render(), Charsets.UTF_8))
}
```

完整链示例（parse → 操作符式原生语义查询 → best-exact 投影 → 结构编辑 → canonical 物化 → 跨格式转换到 TOML）：[`kotlin/examples/SdkChain.kt`](kotlin/examples/SdkChain.kt)，编译运行方式见示例头部注释。

## API 摘要

核心面一行式（完整签名见 [kotlin/README.md](kotlin/README.md)；`parse*` / `execute*Query` / `project` / `materialize` 按格式家族分布，`convert*` 是根级统一入口）：

| 操作 | facade 入口 |
| --- | --- |
| parse | `consema.parseDocument(bytes: ByteArray, profile: ProfileId): Document` |
| query | `consema.json.executeJsonQuery(executable: ExecutableQuery, document: Document, limits: QueryLimits = QueryLimits.default, cancellation: CancellationToken = CancellationToken()): List<JsonMatch>` |
| project | `Document.project(request: ProjectionRequest): ProjectionResult`（请求：`ProjectionRequest.builder(ProjectionTarget.BestExactCoreV1).build()`） |
| edit | `consema.json.EditTransactionBuilder.new(document)` + `Document.commit(transaction: EditTransaction): EditCommit`（`commit.document` 为编辑后文档） |
| materialize | `consema.json.materialize(value: PortableValue, request: MaterializationRequest): MaterializationResult<Document>` |
| convert | `consema.convertJson(source: json.Document, projectionRequest: json.ProjectionRequest, materializationRequest: MaterializationRequest): ConversionResult`（另有 convertIni / convertProperties / convertToml / convertYaml / convertXml / convertPlist / convertHcl） |
| registry | `consema.formatFamilies()` / `consema.profiles()` / `consema.queryDomains()` / `consema.operationRegistry(profile: ProfileId)`（8 家族 / 16 profiles / 21 查询域 / 16 操作注册表；组合示例：`profiles().map { operationRegistry(it.profile) }`） |

## 布局

- `kotlin/`：Kotlin/JVM 包（运行时仅 kotlin-stdlib 及 KGP 注入的传递
  org.jetbrains:annotations——build.gradle.kts 自身声明全部 test-scoped）。完整文档见
  [kotlin/README.md](kotlin/README.md)。
- `scripts/`：跨语言差分验证脚本（byte parity / normalized differential /
  protocol exchange）。脚本构建 consema-rs 的 Rust emitter 并对拍 Kotlin 实现；
  Rust 侧来自 consema-rs 仓 checkout（CI 多仓模式），conformance 数据来自规范仓 checkout。
- `.github/workflows/ci-kotlin.yml`：八个 job 的 Kotlin CI——kotlin-gates
  （gradlew 单测 + kover 60% 覆盖率 + 依赖面断言）、kotlin-conformance
  （18 suites / 519 cases，聚合 digest 字面量钉 + 直驱 K2JVMCompiler）、
  kotlin-differential（Kotlin-Rust 差分门禁，直驱 K2JVMCompiler；多仓
  checkout）、check-version-consistency、examples、kotlin-package、
  runtime-classpath-audit（2026-08-14 G103 增补）与聚合 check
  （windows-latest 5 job + ubuntu-latest 3 job）。

## 构建与测试

Gradle wrapper 已入库（gradle 8.14；kotlin/gradlew、gradlew.bat、
gradle/wrapper/，commit c60d31a/b640af6）：kotlin-gates 经 gradlew 跑单测
与 kover 60% 覆盖率门禁；conformance / differential 验证仍直驱 JVM
K2JVMCompiler，与提交的验证脚本和 CI 完全一致：

```text
# CI 方式（provision kotlinc 2.2.0 + Temurin 17，见 ci-kotlin.yml）
powershell -File scripts/kotlin-verify-byte-parity.ps1
powershell -File scripts/kotlin-verify-normalized-differential.ps1
powershell -File scripts/kotlin-verify-protocol-exchange.ps1
```

## FAQ

- **支持哪些配置格式？** 八个格式家族、16 个 profiles：JSON（`json.strict@1` / `jsonc.bounded@1` / `json5.standard@1`）、TOML（`toml.1.0@1`）、YAML（`yaml.1.2-core@1` / `yaml.1.1-compat@1`）、INI（`ini.portable@1` / `ini.windows@1` / `ini.python-configparser@1`）、Java Properties（`java-properties.reader@1` / `java-properties.latin1@1`）、XML（`xml.1.0-safe@1`）、Property List（`plist.xml@1` / `plist.binary@1`）、HCL（`hcl.native@1` / `hcl.tfvars@1`）。完整面枚举见 `consema.profiles()`。
- **与 Jackson / kotlinx.serialization 的关系？** 互补而非竞争：Jackson/kotlinx.serialization 做 JVM 对象与数据格式间的类型编解码，Consema 做格式内容处理（无损文档、查询、投影、原子编辑、跨格式转换）；Consema 明确不做业务 schema 校验（平台接入指南）。
- **性能如何？** 行为一致性由 18 suites / 519 cases conformance 门禁与跨语言差分门禁保证；解析/渲染基准基线见规范仓 `https://github.com/consema/consema/blob/main/docs/BENCHMARKS-0.13.0.md` 与 Go 仓 [consema-go/go/README.md](https://github.com/consema/consema-go/blob/main/go/README.md)。
- **依赖面？** 运行时仅 kotlin-stdlib（KGP 默认注入）及其传递的 `org.jetbrains:annotations`——build.gradle.kts 自身声明全部 test-scoped，依赖门禁断言 runtimeClasspath 不含任何第三方 group。
- **跨语言一致性如何保证？** 18 套语言无关 conformance suite 共 519/519 cases（聚合 digest `cfd6e296…`）由规范仓维护；实际钉/复算该 digest 的是 rs/go/py/kt 四仓 runner 与母仓 shared-conformance-digest 作业（ts 不 provision fc-manifest，其 digest 断言按 documented skip 永久跳过——ts 仓 ci-typescript.yml 如实注记，wave-5 修正「五仓共享」措辞）；CI 多仓 checkout 跑 conformance runner 与 Kotlin-Rust 差分门禁（byte parity / normalized differential / protocol-exchange）。
- **兼容承诺？** 语义化版本；`check-version-consistency` 门禁断言 README 版本行与 `build.gradle.kts` 一致；kover 60% 行覆盖门禁；兼容与支持政策见 RFC 0020。
- **如何贡献？** 见本仓 [CONTRIBUTING.md](CONTRIBUTING.md)（规范仓为权威版）；conformance 向量/夹具/oracle/差分数据权威在规范仓——向量变更是五仓同步事件，必须先回规范仓提交再同步五个语言仓。
- **"默认拒绝信息损失"是什么意思？** 投影/转换/编辑中的任何 loss（如 YAML 共享结构展开、Properties 重复键折叠、数值舍入）必须显式授权；未授权时操作原子失败（`ConversionResult.Failed`；fidelity 三档：Exact / Transformed / Lossy）。

## 六仓导航

| 仓库 | 角色 |
| --- | --- |
| [consema](https://github.com/consema/consema) | 规范 / RFC / 路线图 / 审计证据 / conformance 仲裁层（语言无关权威） |
| [consema-rs](https://github.com/consema/consema-rs) | Rust 参考实现 |
| [consema-go](https://github.com/consema/consema-go) | Go 实现 |
| [consema-ts](https://github.com/consema/consema-ts) | TypeScript 实现 |
| [consema-py](https://github.com/consema/consema-py) | Python 实现 |
| [consema-kt](https://github.com/consema/consema-kt)（本仓） | Kotlin 实现 |

## 文档导航

- 规范仓（RFC / docs / 路线图 / conformance 权威）：https://github.com/consema/consema
- [RFC 0001-0016](https://github.com/consema/consema/tree/main/docs/rfcs) + [RFC 0020 兼容与支持政策](https://github.com/consema/consema/blob/main/docs/rfcs/0020-compatibility-and-support-policy-v1.md)：语言无关规范的权威载体
- [1.0.0 产品路线图](https://github.com/consema/consema/blob/main/Consema%201.0.0%20产品路线图与双语言落地设计.md)
- [平台接入指南](https://github.com/consema/consema/blob/main/docs/platform-integration-guide.md)
- [CLI Cookbook（可复制配方）](https://github.com/consema/consema/blob/main/docs/cookbook.md)
- [多语言实现计划](https://github.com/consema/consema/blob/main/docs/multi-language-implementation-plan.md) / [五语言 CI 设计](https://github.com/consema/consema/blob/main/docs/five-language-ci-design.md)
