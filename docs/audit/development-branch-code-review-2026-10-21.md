# 开发分支代码、安全与性能审查（2026-10-21）

## 结论与范围

- 分支：`feature/workflow-daily-integration`；HEAD：`ee01e97515b04fd167ed17977535ec5cab8572f8`。审查包含该工作区既有暂存、未暂存与新增源码，HEAD 不能单独代表被审查版本。
- 本轮登记 5 项系统问题（2 项 P1、3 项 P2）及 1 项 P3 规范问题。本轮已修复 1–6；DEV-REVIEW-4 补了真实 PostgreSQL 查询计划与 5 万行合成负载证据，但仍未完成生产容量验收；不改变 R01–R36 验收状态。
- 重点追踪 Workflow 暂停恢复、启动重投、归档、通知与 SDK 参数链路。工作区约 1700 项改动，未逐文件完成全量人工审查，不能据此给整个分支签署通过。
- 使用 `code-review-and-gates`、`security-scan-governance`、`sql-query-performance`、`performance-validation`、`sdk-contract-governance` 及 `documentation-governance`。
- 本报告最初只登记问题；2026-10-21 已在同一开发分支按首红、最小修复和定向复验处理可本地验证的缺陷。执行证据见下方“整改与验证进度”和[执行日志](../plans/workflow-daily-integration-execution-log-2026-10-14.md)。当前待办见 [TODO Master](../plans/todo-master.md)。

## 系统问题

### DEV-REVIEW-1 / P1：恢复时遗漏暂停期间失败节点的后继

**位置**：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/WorkflowRunResumeProgressor.java:54–62`。

`TaskOutcomeDagProgressor` 会持久化暂停期间完成节点的 SUCCESS/FAILED，然后停止派发；恢复进度器却仅处理 SUCCESS，且固定以 `success=true` 解析边。节点 A 在暂停期间失败后恢复，A 的 FAILURE/ALWAYS 后继不能从 A 恢复派发。终态汇总只检查已物化节点，存在错误收口风险。

**证据**：用当前源文件重新编译的隔离 Java 探针输入 FAILED 节点，得到 `resolutions=0 dispatches=0`。这是模拟依赖的逻辑复现，未覆盖真实数据库事务。

**整改与验收**：根据已持久化的实际节点结果恢复 DAG，保持跳过传播与正常推进一致；真实 PostgreSQL 覆盖暂停→失败回报→恢复，证明 FAILURE/ALWAYS 后继各派发一次，并覆盖重复恢复和进程故障。

### DEV-REVIEW-2 / P1：START 恢复缺失原始启动参数

**位置**：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowRunLaunchService.java:280–290`，以及 `WorkflowRunResumeProgressor.java:58–73`。

创建 START 成功记录时未保存 `sourcePayloadProtected`。正常首次派发从 `request.params()` 生成 payload；恢复只读取节点保存的 payload，不读取原始请求。若根运行已切 RUNNING、首节点派发前被暂停，派发入口重读 PAUSED 后退出，恢复会用空输入重新解析 START 后继，丢失参数覆盖或改变条件选择。部分首节点派发后进程失败也有相同恢复输入缺口。

**证据**：当前源码确认 START 写入字段；Java 探针输入这种 START 记录，观察到 `START dispatch receives null sourcePayload`。未进行真实暂停竞态复现。

**整改与验收**：持久化受保护的 START 原始参数，或从不可变、已校验的请求载荷恢复；保留密钥保护。对带参数和条件边的 Workflow，在首次派发前暂停及故障恢复，逐项回读执行参数与分支选择，证明与连续执行一致。

### DEV-REVIEW-3 / P2：Go SDK 的 payload 保留旧 effectiveConfig 字段

**位置**：[sdk/go/client/lifecycle.go](../../sdk/go/client/lifecycle.go) 第 330–345 行。

`parameters := claim.EffectiveConfig` 后向该非空 map 执行 `json.Unmarshal`，Go 会保留 payload 未提供的旧键，并修改原 map。Python/TypeScript 的对应路径以新对象替换。因而相同 CLAIM 在不同语言产生不同参数；空对象 payload 也不能清除旧配置。

**证据**：最小 Go 复现以旧配置 `{"legacyOnly":"stale","limit":99}` 解码 payload `{"limit":5}`，结果为 `parameters=map[legacyOnly:stale limit:5]; legacyRetained=true; claimConfigMutated=true`。仅复现标准库 map 行为及当前代码表达式，未运行完整 SDK/传输链路。

**整改与验收**：有 payload 时解码到新 map，仅在无 payload 时使用约定的兼容路径；增加旧配置独有键、空对象及非法对象用例，并核对多语言契约一致。

### DEV-REVIEW-4 / P2：归档每小批都重新汇总全部积压的引用异常

**位置**：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/archive/SuccessInstanceArchiveService.java:39–44`；`batch-orchestrator/src/main/resources/mapper/SuccessInstanceArchiveMapper.xml:121–136`；`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/archive/SuccessInstanceArchiveScheduler.java:61–70`。

每次 `archiveOnce()` 在有界候选选择前执行无批次上限的完整异常汇总；谓词包含多组关联 EXISTS。调度器一轮最多调用 50 次，重复处理尚未归档的积压候选。候选选择的 batchSize 不能限制前面的诊断汇总成本。调度入口已改为每个 scheduler tick 仅执行一次完整诊断，后续归档批次继续使用有界安全谓词；V334 为 `parent_instance_id IS NOT NULL` 增加分区索引，避免父引用核验对每个候选逐分区扫描。

**证据边界**：修复前使用 PostgreSQL 17.11 的 5 万行探针时，查询超过 2 分钟仍未结束并由探针主动取消；规划成本约 15,694,701.66，父引用子计划逐候选顺序扫描 2026-10 分区。V334 后同一类 5 万行/50 租户合成数据（其中 2.5 万条非空父引用）完成 3 次暖缓存 `EXPLAIN (ANALYZE, BUFFERS)`，测得 14.1–19.4 秒、共享命中 976,942 blocks、读取 0 blocks；计划命中新建的父引用分区索引，诊断结果为空。该运行期间另一工作树也在执行集成测试，耗时可能受共享主机竞争影响；前后种子分布与并发条件不完全相同，因此只能证明索引进入计划及本地合成样本可完成，不能作为严格前后容量基准或生产 SLO 结论。

**整改与验收**：已将完整诊断限制为每个调度 tick 一次，保留逐候选安全排除，并以 V334 为非空父引用建立分区索引。归档服务、Mapper XML、调度健康测试 12/12 通过，真实 PostgreSQL 探针确认诊断结果和索引路径；生产数据量、并发下的耗时/吞吐、锁等待和告警交付仍需容量目标及目标环境验证，故不标为容量验收完成。

### DEV-REVIEW-5 / P2：已创建根运行的启动重投仍受最新启用状态阻断

**位置**：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowRunLaunchService.java:90–96`。

启动先加载当前可接纳定义，再查请求对应的既有运行。`prepareRun` 独立事务已提交 CREATED/START、但尚未 markRunning 时发生进程故障，若定义随后停用，普通请求重投会先被拒绝，无法复用冻结快照。`TriggerLaunchConsumer.java:204–214` 对业务拒绝 ACK；`WorkflowRunMapper.xml:194–200` 的停滞扫描仅选择 RUNNING，留下 CREATED 运行及其活跃占位。持久 misfire 的 CATCH_UP 例外不覆盖普通启动。

**证据**：根运行创建提交后、`markRunning` 前注入中断并确认 `CREATED`/`ACCEPTED` 状态；停用定义后同请求按冻结快照恢复并重复投递不重复建子请求。复核发现 EVENT 恢复还会在结清既有事件预期时被“当前 Workflow 必须启用”检查阻断，现已将该路径纳入修复：仅匹配的 LAUNCHED EVENT 可结清同日同日历既有预期；创建新预期仍要求 Workflow 启用。

**整改与验收**：优先识别已接纳的请求绑定运行，按冻结快照恢复；若停用明确要求终止既有运行，应以生命周期事务收口而非留下 CREATED。覆盖根创建提交后故障、定义停用、消息重投，验证请求/根运行最终一致且无重复执行、无永久占位。

## 规范问题

### DEV-REVIEW-6 / P3：Push Mapper 使用过长位置参数列表

**位置**：`batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/mapper/JobPushDeliveryMapper.java:23–56`。

`claim/finish/insertDecision/insertDevice` 原分别具有 5/7/7/8 个参数，违反 `docs/coding-conventions.md` 的 Mapper 多参数命名对象约定。未证明已有参数错绑，不计作运行缺陷。现已改为语义参数 record，并经 PostgreSQL 集成验证 SQL 绑定。

## 整改与验证进度（2026-10-21）

| ID | 处理结果 | 验证证据与剩余边界 |
|---|---|---|
| DEV-REVIEW-1 | ✅ 已修复 | 恢复进度按持久化的 SUCCESS/FAILED/SKIPPED 结果选择边，保持 skip cascade。真实 PostgreSQL 覆盖暂停期间失败回报、派发中故障回滚、FAILURE/ALWAYS 各派发一次、SUCCESS 分支跳过和重复恢复；恢复/归档组合定向验证 15/15。 |
| DEV-REVIEW-2 | ✅ 已修复 | START 保存加密根参数快照；真实 PostgreSQL 验证首次派发前暂停后按原参数选择同一条件分支，且数据库未保存明文。START 恢复用例通过。 |
| DEV-REVIEW-3 | ✅ 已修复 | Go SDK 有 payload 时解码到新 map；旧键替换、空对象清空、非对象拒绝且原 map 不变均覆盖；`go test ./client` 通过。未运行完整多语言 SDK 契约矩阵。 |
| DEV-REVIEW-4 | 🟡 重复查询与父引用扫描根因已修，容量验收未闭合 | Scheduler 每 tick 执行一次完整引用诊断，后续每批只做有界候选归档；V334 为非空 `parent_instance_id` 添加分区索引。归档服务/Mapper/调度测试 12/12 通过；PostgreSQL 17.11 的 5 万行合成探针 3 次暖缓存执行为 14.1–19.4 秒，计划使用新索引。样本并发受共享主机影响，未证明生产吞吐、SLO 或高并发容量。 |
| DEV-REVIEW-5 | ✅ 已修复 | 已有根运行按不可变 execution snapshot 恢复；新启动继续校验当前启用状态。真实 PostgreSQL 端到端覆盖 EVENT 请求在 CREATED 故障窗口后、定义停用时按原快照重投并结清已物化预期；另以 API 路径验证重复投递及新请求拒绝，服务与数据库测试验证匹配限制和新预期配置仍要求启用定义。 |
| DEV-REVIEW-6 | ✅ 已修复 | `claim/finish/insertDecision/insertDevice` 改用语义参数 record；Console Push 单测及 PostgreSQL 集成 28/28 通过，覆盖实际 SQL 参数绑定和既有投递行为。 |

**本轮验证边界**：未运行全量 Maven/SIM、Spotless/格式门禁、全语言 SDK 契约、CI、生产容量测试或完整安全审计。原 Gitleaks 的 9 项候选均确认来自测试配置/测试请求；改为运行期生成的测试值后，变更文件快照扫描 1746 个文件、0 命中、退出 0。安全 E2E 因该测试启动后持续补开历史 BatchDay 而主动中断，不能记为通过；R01–R36 和整分支验收状态不变。未提交、推送或创建 PR。

## 安全扫描及覆盖边界

对相对 HEAD 的变更文件及未跟踪文件创建临时快照，使用当前 `.gitleaks.toml` 扫描实际工作区内容，避免仅扫描 Git 历史而漏掉大量未提交代码。

- 原始扫描 1727 个文件、约 21.93 MB、约 4.94 秒；Gitleaks 退出码 1，命中 9 项 `generic-api-key`。逐项回查后均为测试配置、请求头或测试子进程参数，不是生产凭据，没有触发凭据轮换。
- 测试值已改为测试运行时构造；未扩展 Gitleaks 白名单。随后对包含工作区变更和未跟踪文件的 1746 个文件快照重新扫描，退出码 0、0 findings。
- 安全 E2E 测试源码已编译，但定向运行因测试日历从 2026-01 开始持续生成历史 BatchDay、测试环境出现持续回填而由操作者中断；该结果不视为 E2E 通过，也没有清理或修改其他工作树/数据库。
- 读取了安全脚本及 PR/Full Gate 扫描路由；配置覆盖不等于本轮已执行。未执行依赖/CVE、镜像、DAST、全仓 SAST 或外部攻击验证；当前环境没有 Semgrep。
- 未发现已证实的新安全漏洞不代表安全验收完成；仍未执行依赖/CVE、镜像、DAST、全仓 SAST 或外部攻击验证。性能探针只验证本地 PostgreSQL 执行路径和合成样本；生产容量、P95/P99、锁等待、故障恢复负载仍无证据。

## 执行记录

临时探针与脱敏扫描 JSON 保存在本机临时目录，未加入仓库。以下变量表示对应临时目录与本机依赖 classpath；不应原样作为独立命令执行。

| 实际执行命令形式 | 结果与解释 |
|---|---|
| `gitleaks dir "$SNAPSHOT" --config .gitleaks.toml --redact --no-banner --report-format json --report-path "$REPORT"` | 退出 1，9 项候选；不是安全通过 |
| `javac -cp "$CP" -processorpath "$LOMBOK" -d "$CLASSES" "$CURRENT_PROGRESSOR" "$PROBE"` | 退出 0，编译当前恢复进度器及隔离探针 |
| `java -javaagent:"$MOCKITO" -cp "$CLASSES:$CP" io.github.pinpols.batch.orchestrator.application.service.task.ReviewResumeProbe` | 首次因 final 类 mock 配置退出 1；仅调整临时 fixture 后退出 0，复现 DEV-REVIEW-1/2；不代表业务通过 |
| `go run "$SCRATCH/main.go"` | 退出 0，复现旧键残留及原 map 修改 |
| `./mvnw -o -ntp -l logs/verification/workflow/dev-review-archive-diagnostic-load-indexed-20261021.log -pl batch-orchestrator -am '-Dtest=ArchiveReferenceDiagnosticLoadProbeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | PostgreSQL 17.11，5 万行/50 租户，暖缓存 3 次为 14.1–19.4 秒，0 shared read、976,942 shared hit blocks，计划使用 `parent_instance_id` 分区索引，0 invalid refs；1/1 与 `BUILD SUCCESS`。探针文件仅为本地一次性诊断，已移除。 |
| `./mvnw -o -ntp -l logs/verification/workflow/dev-review-archive-focused-20261021.log -pl batch-orchestrator -am '-Dtest=SuccessInstanceArchiveServiceTest,SuccessInstanceArchiveMapperXmlTest,ArchiveSchedulerHealthReportingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 12/12，0 failures/errors/skips，`BUILD SUCCESS`。 |

未执行完整编译、全量单测/集成测试、真实服务验收或当前 CI/Sonar gate。安全 E2E 定向运行被历史 BatchDay 回填耗时主动中断，不计为通过。独立 WorkflowArchiveService 已有根运行归档路径，因此不采纳“所有关联作业永久无法归档”的初始怀疑。

## 后续顺序

本轮六项代码问题及规范问题均有针对性修复与验证记录；继续保持 R01–R36 原状态。后续只需为 DEV-REVIEW-4 补目标环境的容量标准和负载/锁等待验收，并按发布流程补齐依赖、镜像、DAST、CI/Sonar 等未运行的门禁；不得将本地探针结果扩大成生产或整分支通过结论。

## 文档检查结果

- `git diff --check -- docs/audit/development-branch-code-review-2026-10-21.md docs/audit/README.md docs/plans/todo-master.md docs/changelog.md`：退出 0。
- `python3 scripts/ci/check-docs-structure.py`：退出 0，DOCS_STRUCTURE 通过。
- `python3 scripts/ci/check-doc-timestamp-policy.py`：退出 0，DOC_TIMESTAMP_POLICY 通过。
- 2026-10-21 本轮增补后复验：`git diff --check`、`check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py`、`check-changelog-sync.py`、`check-terminology-doc-sync.py`、`check-test-config-sources.py` 均通过。首次 DOCS_STRUCTURE 发现整合计划中指向不存在日志锚点，已改为指向现有执行日志文件，复验通过。未运行 Spotless（按用户此前安排暂缓）。
- 主分支在本轮末次只读检查时出现审计索引、文档 changelog 和新增 nested-callsite 审计文档改动；来源未确认，本轮未写入或丢弃这些主分支改动。
