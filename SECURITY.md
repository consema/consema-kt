# Security and resource behavior

安全披露统一走 consema 组织（github.com/consema/consema）的 SECURITY 流程（披露渠道、响应 SLA 与支持窗口以规范仓为权威）；本仓库的安全边界与资源上限语义与规范仓一致，完整内容见下。

Consema 将资源上限作为执行策略，不把截断包装成成功：

- `ParseLimits` 限制 source、nesting、token/piece、node、diagnostic 和 number digits（`maxNumberDigits`，默认 100,000——单数字字面量系数+指数位数上限，wave-4 per-parser O(N²) BigInteger 构造放大守卫）；
- `DecodeLimits` 限制 PVCE bytes、depth、nodes、container、integer 和 blob；
- `ProtocolLimits` 同时限制 canonical JSON/PVCE 的 transport bytes、depth、nodes、container、integer 和 blob；
- `QueryLimits` 限制 step 与 result；
- `ProjectionLimits` 限制 value、report、provenance 和 depth；
- `SourceLimits` 限制 raw bytes、decoded UTF-8 bytes 与 scalar/location count；
- `SourcePatchLimits` 限制 result source、replacement count 与 patch bytes。
- `MaterializationLimits` 限制 input nodes/depth、output bytes、report entries 与 provenance entries。

超限分别返回携带冻结 registered code 的 typed 异常（各家族 `FormationException` 类、PVCE 编解码的 `PvceException`、查询的 `QueryFailureException`）或 failed projection。取消不会被报告为完成。

解析器和 decoder 无 unsafe 概念（JVM 内存安全；本实现不调用任何 native/unsafe API），严格检查 UTF‑8、长度溢出、非最短 varint、非规范整数/Decimal、容器计数和嵌套深度，行为由 conformance/vectors 与 differential case 集逐字节钉住。恶意/边界覆盖在单元套件中：`plist/BinaryHardeningTest`（16 个：trailer-limit、offset/object-reference 越界、marker 排除、扩展尺寸、cycle、非字符串字典键）、`xml/SecurityAndSpanTest`（7 个：entity deny-by-default、span 精确性）、`json/FormationClosureTest`（16 个：formation/恢复边界）、`hcl/NoEvaluationTest`（6 个：不求值契约）、`properties/ResourceTest`（1 个：resource-limit 矩阵）+ `document/LimitsTest`（4 个：冻结 limit 默认值）；资源上限违反返回 typed formation/query failure，不伪装成成功。如果发现未捕获异常（如 StackOverflowError/OOM）、无界分配或规范绕过，请附最小输入与触发的 capability contract 报告。

canonical protocol JSON 拒绝空白、替代 escape、重排/未知字段和非最短数字表示；PVCE 继续拒绝非规范 varint 与整数。默认协议任意精度整数 magnitude 上限为 1 KiB，避免十进制转换的 CPU 放大；调用方提高上限时必须同时评估输入可信度和工作预算。已 ship 记录的 envelope payload 进入对应 typed decoder，不能只靠匹配 `schema` 绕过字段与交叉约束；未 ship 记录只做 envelope/schema 级校验（分派表见 protocol/Payload.kt，含逐记录注记）。v1-v7 registry 全部冻结（v7 增量为 additive；语义模型 v6 向量断言的是 v6 发布时点的 v1-v5 冻结，历史快照）；JSON5 专属 diagnostic 从 semantic-model v4 起可外部化（92/132/166-code registry 均含对应代码）。

`json5.standard@1` 只实现 Standard JSON5 数据文法，不求值 JavaScript，不执行表达式、import、getter、method、computed key、regex 或模板字符串，也不访问文件与网络。IdentifierStart/Continue 使用宿主 JDK 表（`Character.isUnicodeIdentifierStart`，JDK 17 = Unicode 13.0），与 Rust 钉的 `unicode-id-start 1.4.0` 为近似关系（漂移风险注记见 json/Parser.kt 的 JSON5 标识符漂移注记注释块；向量无 case 触及分歧点）。有限数字进入任意精度 Integer/Decimal；只有 `±Infinity`/`±NaN` 进入四种固定 binary64 位模式，有限 binary64 和任意 NaN payload 不能通过 JSON5 文本伪造 exact round-trip。非法 escape/identifier/number/comment 进入 recovery 或 fatal failure，不暴露伪造 native 值。固定 JSON5 v2.2.3 gate（43 valid、39 invalid、一个完整真实夹具）的 corpus 载体是规范仓 conformance/corpora/json5-v2.2.3.json（母仓持有，rs 为 vendored 副本；本仓 provision 步骤把整个 conformance/ 树含 corpora/ 复制进 workspace）；执行该 gate 的不止 rs——consema-go 的 go/json/parse_test.go 同样接线该 corpus；本仓未接线该 corpus，JSON5 语义由 conformance 向量与单元测试覆盖。

`core.source-snapshot@1` 解码时重算 digest、BOM/encoding resolution 与 decoded status；`core.source-patch@1` 解码时检查 replacement order/count/bytes，应用时再次检查 base/original/target/encoding。极端 offset、stale content、非法 UTF 序列和超限输出都在返回新 snapshot 前失败；redaction 只影响 review/debug presentation，不删除应用所需的字节前置条件。

Materialization 在递归与输出分配前计算 input node/depth 和增长上限，并对 report/provenance 独立计数；任一上限、不可表示值、unsupported policy 或 target reparse 失败时，结果中没有 Document 或 partial output。caller string 总由 target Profile 转义，materializer 不执行表达式、不解析 import、不访问文件或网络。

结构事务首先验证 snapshot、role、target/anchor、所有权、冲突、表示能力与资源预算，再构造并重新解析完整 candidate；失败不会改变 base，也不会返回 ChangeSet、proof 或 SourcePatch。`UntouchedByteProof` 覆盖全部且仅覆盖 replacement 外的旧/新字节，并绑定 base/target digest；任何 region、digest 或 target 篡改均失败。dry-run 与 commit 必须产生相同 replacements 和 target digest，但 plan 本身不授权文件写入。

宿主语言无序列化禁令机制（该约束以 Rust 参考实现的 serde 派生为准：raw `NodeRef`、snapshot handle、cursor 与 `CancellationToken` 不在 Rust 的序列化面内）。需要 source/node identity 的 Diagnostic、Query、Provenance、ChangeSet、MaterializationResult 和 EditPlan 必须先绑定调用方稳定 locator；缺失绑定会失败，不会省略身份事实后伪造成功。

`toml.1.0@1` 只对完整合法文档形成 snapshot，非法输入返回 typed formation failure。`toml-lang/toml-test v2.2.0` 的 205 个 valid 和 474 个 invalid TOML 1.0 decoder cases 的脚本与记录在规范仓：脚本为 consema/scripts/run-toml-test.ps1（纯执行器，全文零写入命令），205/474 为 consema/docs/UPSTREAM-TOML-TEST.md 的自证数字（该文如实注记：完整输出无入库载体、上游语料不在仓内、无法复算）；本仓无 CI job 执行该门禁；上游版本变更必须单独审计。semantic edit 不会舍入 NaN payload、亚纳秒时间或非整分钟 offset 来伪造成功。

`xml.1.0-safe@1` 的 formation 只消费调用方提供的完整 document entity bytes，绝不打开外部 DTD、实体、URI、文件、网络连接、registry、classpath 或 catalog，也不提供用户 resolver 回调。DOCTYPE 仅允许 bounded internal subset；外部 subset、外部/参数实体、notation、conditional section 与 validation 声明一律恢复并发布稳定诊断。entity 膨胀按整个文档六维记账（declaration/reference 数量、expansion depth、expanded bytes/scalars、amplification ratio），任何一维突破即恢复；攻击无法把预算拆分到多个引用。内部 subset 注释按字符数据处理，其文本不会触发排除声明误报。UTF-16 输入必须携带 BOM；encoding 声明与实际编码冲突时恢复。恢复文档永不投影、物化或编辑；`xml.safe-canonical-document@1` materialization 对生成字节执行重解析闭包验证，失败返回无目标 Document。结构编辑不接收 raw markup，新内容一律 XML-escape；编辑不猜测或伪造 namespace 声明，unbound/reserved prefix、重复 expanded attribute、ancestor placement 与根删除均在 commit 前失败。XML 语法覆盖包含 37 种细粒度 kind，实体引用与属性部件均可被 lossless query 精确区分。

`plist.xml@1`/`plist.binary@1` 的 formation 只消费调用方提供的完整文档字节，绝不打开外部 DTD、实体、URI、文件或网络连接，不 fetch Apple DTD 或任何 URI，也不读环境/locale 状态或调用应用代码。XML 表示只按声明的 UTF-8/UTF-16 source contract 读取，binary 表示只解析 object table 与 offset-table/trailer 事实，XML 文档永不暴露 binary object/offset/ref/trailer 事实，binary 文档永不暴露文本 token/trivia。date、data 与 integer 不通过字符串降维；object reference、offset 与 size 计算在分配前检查溢出与资源限制。XML/binary 双表示 round-trip 转换对目标表示无法表达的原始事实（UID、Float32 width、未配对 surrogate、分数秒/越界日期等）原子失败并发布 `plist.conversion.inexpressible@1`，不产生部分目标文档。恢复文档永不投影、物化或编辑；`plist.xml-canonical@1`/`plist.binary-canonical@1` materialization 对生成字节执行重解析闭包验证，失败返回无目标 Document。逐字节 mutation/truncation 对抗覆盖的证据在 consema-rs（plist_hardening.rs）；本仓的对抗/边界覆盖在单元套件（BinaryHardeningTest、SecurityAndSpanTest 等）与 conformance/差分门禁。

`hcl.native@1`/`hcl.tfvars@1` 的 parse/query/project/edit 全程不求值：无 variable/function/template 求值与展开，`hcl.expression@1` 只承载语法事实、永不执行，无 application schema 与 Terraform/cty 语义。formation 只消费调用方提供的完整文档字节，不访问文件、网络、registry 或环境。表达式/模板/heredoc depth、number digits、item/label/attribute counts 与 recovery regions 等全部尺寸算术在分配前 checked，limit 失败绝不伪装成空 body、截断表达式或缩短查询。恢复文档可查询、不可 project/materialize/commit；`hcl.canonical-document@1` materialization 生成字节必先重解析并逐节点比较闭包语义，失败返回无目标 Document、无 partial bytes、无 partial provenance。对抗门禁覆盖 expression depth、template/heredoc size、number digits、body nesting 与 item counts 的极限输入，验证无未捕获异常、无无界分配。

运行时仅语言平台：`kotlin/build.gradle.kts` 的全部依赖声明均为 test-scoped（testImplementation/testRuntimeOnly），runtime classpath 只含 Kotlin Gradle 插件注入的 kotlin-stdlib 2.2.0 及其传递的 org.jetbrains:annotations。依赖门禁由 `.github/workflows/audit.yml`（定时/路径触发轨）与 `.github/workflows/ci-kotlin.yml` 的 runtime-classpath-audit job（PR/推送轨，同一断言，属于聚合必查 `check (all gates green)` 的一部分——audit.yml:28-34 头注）双轨执行：经已入库的 Gradle wrapper（gradle 8.14，c60d31a）求值 `runtimeClasspath`，断言除语言运行时（kotlin-stdlib 及其传递的 org.jetbrains:annotations）外不解析任何第三方 group；同一策略的源码级 tripwire（禁止 implementation/api/compileOnly/runtimeOnly 声明）在 audit.yml 与 ci-kotlin.yml kotlin-gates 双重断言。Dependabot（gradle ecosystem）跟踪 build.gradle.kts 清单与 wrapper 更新；覆盖门禁为 kover 60% 行覆盖（koverVerify，kotlin-gates job）。下载供给（kotlinc 2.2.0 zip、kotlin-compiler.jar、junit-jupiter-api-5.10.2.jar）均钉 sha256；校验面按 W3-25 收窄口径（2026-08-14，与 ci-kotlin.yml 各 provision 步骤注释一致）：kotlinc zip 的 sha256 只在下载分支校验，缓存命中时整个下载分支（含 zip 校验）被跳过——缓存恢复的整棵树里只有 kotlin-compiler.jar 每次使用前重校验，同树消费的 kotlin-stdlib.jar / kotlin-test.jar / kotlin-test-junit5.jar 在缓存命中路径上零重验（仅由下载时 zip sha256 覆盖）；校验失败即失败。钉 sha256 的 junit-jupiter-api-5.10.2.jar 只服务直驱 runner 的反射注解发现（非执行面通道）；单元/发布门禁实际执行的 junit-jupiter:5.10.2（含 engine/params/platform 全家）、kotlin("jvm") 2.2.0 插件、kotlin-stdlib 2.2.0 与 junit-platform-launcher:1.10.2 由 Gradle 从 Plugin Portal/Maven Central 无校验和解析——本仓无 gradle/verification-metadata.xml（波 5 实测 find 零命中），Gradle 解析依赖面零校验和验证——「均钉 sha256」的声称只覆盖上述三件下载物，不覆盖 Gradle 解析面。

## 安全披露与支持周期

安全披露、响应时间与支持窗口（路线图 §19.4 的"安全披露联系方式和支持周期"；缺陷等级沿用 §18.4，P0/P1 在 1.0.0 前不允许未解决，P2 必须逐项公开评审）。

**披露渠道。** GitHub 私有漏洞报告当前未启用（2026-08-14 gh api 实测 false）；启用后为首选渠道，启用前首选维护者邮箱：franckcl1989 &lt;franckcl@icloud.com&gt;（本仓尚无发布 tag，GitHub 提交身份记录可核验）。报告请包含：受影响的版本与 Profile/contract、触发问题的 capability contract（如 `core.source-snapshot@1`）、最小复现输入、以及你观察到的行为。发现未捕获异常、无界分配或规范绕过时，也请携带上述信息报告（见上文 hardening 段）。披露遵循协调披露：收到报告后先确认再公开，不会在修复可用前公开细节；不承诺任何形式的赏金。供应链问题（依赖、SBOM、签名、CI）同样走此渠道。

**响应 SLA（按缺陷等级）。** P0（数据破坏、静默损失、RCE/外部访问、错误写文件、跨快照误编辑）：24 小时内确认，7 天内给出修复或缓解方案。P1（未捕获异常/崩溃/hang、错误完成状态、明显语义不一致、limit bypass）：72 小时内确认，14 天内修复。P2（有安全替代路径的功能缺陷、非核心性能回退、诊断位置错误）：随下一个发布窗口修复，发布判断逐项记录。P3（文档、易用性、非稳定 message、低风险边角）：尽力而为。任何等级都不得用降级测试或截断包装来"修复"；资源上限与完成状态语义是安全边界（见本文档开头部分），不能因披露而放松。

**支持窗口。** 1.0.0 发布前，安全修复只承诺两个窗口：当前版本与其前一 rc/稳定版本（当前版本见 README `Version:` 行，本段避免硬编码版本串；本仓尚无发布 tag）；更早版本不承诺修复，除非影响面证明必须回移。正式支持的目标是 CI 运行环境（windows-latest 与 ubuntu-latest 双 runner；Temurin 17 JDK；job 数以最近 CI run 为准）。工具链钉定按实测如实表述（wave-4 R19）：Kotlin/kotlinc 维度钉 2.2.0（build.gradle.kts `kotlin("jvm") version "2.2.0"`；直驱 K2JVMCompiler 路径钉 kotlinc 2.2.0 发行版 zip 并 sha256 校验，校验面同 W3-25 收窄口径——缓存命中路径只重校验 kotlin-compiler.jar，zip 本身不重验）；JVM 维度为 17.0.x 浮动补丁（CI 每次由 setup-java 解析最新 17.0.x，jvmToolchain(17) 同理），最低 17.0.0 未精确验证，不声称已实测。按五语言 CI 设计 §1.2 的构造性验证口径，CI 钉的版本就是支持面；工具链提升必须走清单变更记录。公共 API 与 CLI 命令的弃用期至少一个 minor；contract/Profile 退役必须走 RFC 进程，已冻结的 v1-v7 registry 永不删除 code，退役只改变新输入的接受行为并在发布记录中列明。
