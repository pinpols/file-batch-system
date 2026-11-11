# 文件等待与触发设计核查

> 日期：2026-11-09；基线：`main`、`e7999b364`。本次按用户提供的七项候选核查当前源码、配置、测试源码和文档；未修改业务代码，未运行测试、性能压测或真实环境验证。结论不外推到生产配置和数据。

> 后续整改进度：本文件保留最初审查和首红过程，相关代码随后已在 `codex/system-design-capacity-closure` 修改。具体渠道匹配、唯一权威清单、有界扫描及 WAIT 分支的最新定向结果和未验证边界，以[系统设计核查与容量验证计划](../plans/system-design-capacity-validation-plan-2026-11-09.md#当前进度与下一步)为准；尚未完成目标环境或容量验收。

相关权威入口：[Sensor WAIT](../architecture/adr/ADR-028-sensor-wait-node.md)、[批次清单](../architecture/adr/ADR-040-batch-manifest-driven-arrival-group.md)、[Workflow 与每日义务](../architecture/adr/ADR-047-workflow-scheduling-and-daily-obligations.md)、[事件到达手册](../runbook/event-driven-arrival.md)和[批次日手册](../runbook/batch-day-gate-howto.md)。

## 需要优先核实的正确性风险

1. **渠道语义不等于实际渠道身份。** [FileArrivalSensorPolicy](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/sensor/FileArrivalSensorPolicy.java) 将 `channelCode` 前缀映射为 `SFTP`、`API` 或 `UPLOAD`，无法映射时返回空值；[查询](../../batch-orchestrator/src/main/resources/mapper/SensorFileArrivalMapper.xml)仅在来源类型非空时过滤 `source_type`。因此同租户下满足其他名称、时间及可选业务日/组条件的记录可能被错误命中：未知前缀不按类型过滤，已识别前缀也不能区分同类型的不同渠道。默认 import scanner 登记 `SYSTEM` 来源，而 `sftp_*` 映射为 `SFTP`，在上述默认配置下也可能漏匹配。跨租户匹配并非此处结论。当前为源码确认、运行反例未验证；应先明确业务需要来源类型还是精确渠道，并核对登记链路是否保存可信渠道身份，再设计兼容策略。
2. **扫描列表可能使后续对象饥饿。** [ImportIngressScanner](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java) 每轮从空 marker 开始，最多取 `batch-size`（默认 200）个对象；已经登记的对象仅在取得后跳过，下一轮不保存分页位置。[本地对象存储](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/FilesystemObjectStore.java)按 key 排序返回前页：若前 200 项持续保留，排在后面的对象不会进入本轮或后续同样的扫描范围。[事件通知](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportEventArrivalService.java)也调用同一扫描，不直接登记通知对象。该失效路径由源码推导，尚未运行复现；应以保留前 200 项并新增第 201 项的隔离测试验证，再评估兼容清单、done 文件及稳定窗口的分页策略。

## 对原七项判断的核对

分页整改方案：用户已选择有界持久化；渠道整改选择新配置按具体渠道匹配、扫描登记补充渠道身份，并兼容历史无渠道记录。两项已随 #1339 合入 `main`（2026-11-10）；本节当时写的“实现和验证均尚未完成”是审查时状态。以下首红证据及候选方案分析保留原始发现过程。

### 托管上传占位记录补充复核

托管上传会先创建 `file_record` 占位行，再在对象存在并确认后写入 `arrivalConfirmedAt`。隔离 PostgreSQL 反例在修复前显示：占位行尚未确认时，FILE_ARRIVAL 返回 `MATCHED` 而非 `NOT_YET`。现已在 WAIT 查询复用文件组治理的托管上传确认条件，非托管来源保留原有查询口径。修复后运行 `./mvnw -pl batch-orchestrator -am -Dtest=FileArrivalPatternIntegrationTest,SensorFileArrivalMapperXmlTest,FileArrivalSensorPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`，20 项通过、0 跳过；其中真实 PostgreSQL 5 项，SQL 映射 2 项，策略单测 13 项。测试直接构造占位和确认数据，未验证真实上传 API 与浏览器。

| 原项 | 本轮结论 |
| --- | --- |
| P1 渠道前缀 | 风险成立且不止未知前缀；已识别前缀也只有来源类型粒度，默认 `SYSTEM` 还可能造成漏匹配。需区分渠道身份与来源类型。 |
| P2 过旧与未到 | 成立：`created_at`、名称、业务日、组及来源等条件不匹配均返回同一 `NOT_YET`。单独增加 reason 字段并不能判断原因，需要额外可验证数据。先到文件可能在整组 `TRIGGERED` 时已超过 `maxAge`；是否改变鲜度语义须先明确业务要求。 |
| P3 未登记及延迟 | Sensor 查的是 `file_record`，不直接查询物理存储；漏扫或登记失败时无法命中。但默认扫描间隔 30 秒之外还有默认 30 秒稳定窗口、节点探测和队列延迟，不能宣称最坏 30–40 秒。通知默认关闭，开启后仅提前扫描，不直接唤醒 WAIT。 |
| P4 每轮 50 | 上限和串行处理存在；扫描是 `fixedDelay`，处理时间、锁、失败和新增工作都会影响排队。1000/50 只能作忽略耗时与竞争的 20 批粗估，不能当容量实测或固定 200 秒上界；需实际负载和延迟指标后决定调参或分片。 |
| P5 主动满足及自动对账 | 原前提不成立：满足条件的 EVENT 请求 `LAUNCHED` 后由 [Workflow 启动服务](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowRunLaunchService.java)自动调用；[预期生成](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/batchday/ExternalEventExpectationService.java)也回查已启动请求。`SATISFIED` 表示触发义务，不代表运行终态或业务成功；不能只按 Workflow 终态自动校正。未满足的预期继续阻塞结算，符合批次日扫描条件且设置 due 的逾期项可发缺失输入告警，并非到期自动 FAILED 或保证立即送达。故障恢复与告警送达尚未验证。 |
| P6 入口重叠 | 两层互补：批次清单描述该批应有哪些成员；文件组预期记录业务日输入义务，能表达一份文件都未到的情况。统一入口或决策图是产品/文档建议，不能据此认定语义重复。 |
| P7 扫描间隔 | 全局扫描默认 10 秒，但每个 WAIT 已通过 `poll_interval_seconds` 配置探测间隔；不能说所有类型的实际探测周期一致。是否需要按类型单独的扫描器需负载证据。 |

## 对合理性说明的修正

- `NOT_YET` 按节点配置间隔重排；连续 `ERROR` 才按倍数退避，第三次失败。每节点 `REQUIRES_NEW` 加条件认领隔离事务，调度锁降低重复扫描，但串行慢节点仍会拖长整批，锁到期也不能单独证明绝不重复。
- SQL 的 `!` 等转义用于保持通配符字面语义；防 SQL 注入的主要机制是 MyBatis 参数绑定。`bindWorkflowBizDate` 是显式选项，不是所有旧配置强制启用。
- `requireCompleteGroup` 检查记录的 `arrivalState=TRIGGERED` 及业务日期等条件；组已到齐但处于人工确认等待时不会命中。输入到达或请求已启动并不证明后续业务成功。
- 用户给出的云厂商事件延迟对比未在本项目或可比较环境测量，不作为性能结论。

## 本次文档处理和验证边界

已按当前代码修正[事件到达手册](../runbook/event-driven-arrival.md)中全量扫描、固定时延及轮询必然补齐的表述，及[批次日手册](../runbook/batch-day-gate-howto.md)中由消费方必须手工满足预期的旧表述。本轮是静态评审；未运行相关测试、真实对象存储、数据库并发、压测或目标环境演练，亦未实施上述修复。

## 续查：导入扫描分页饥饿及修复方案

本节为上述静态评审后的独立续查；上文「未运行」描述原轮次，不代表本节新增的隔离复现结果。当前基线为 `codex/system-design-capacity-closure`，HEAD `e7999b364`，工作区存在其他并行改动。

### 首红证据

在 JUnit `@TempDir` 中使用真实 `FilesystemObjectStore` 创建按名称排序的 201 个文件，以 mock 文件记录仓储模拟前 200 个已登记、第 201 个未登记；配置批大小为 200、稳定窗口为 0，连续调用两次扫描。期望第 201 个文件被登记，实际没有调用其登记方法。测试未连接共享数据库或服务，验证范围为真实本地文件系统列举、扫描器和 mock 仓储之间的行为，不能替代 S3 或真实数据库验证。

- 复现测试：[ImportIngressScannerPaginationTest](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScannerPaginationTest.java)。
- 命令：`./mvnw -o -ntp -pl batch-worker/import -am -Dtest=ImportIngressScannerPaginationTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- 结果：**FAIL，1 failure、0 errors、0 skipped**，退出码 1；失败点为期望的第 201 个文件登记调用未发生。该测试当前仍为首红，生产修复尚未实施，不能作为通过证据。

### 跨页验证矩阵

下列除首行外均为**待验证**，不是已通过的测试。涉及数据库事务、多实例或持久状态的场景应使用隔离真实 PostgreSQL，不能用 mock 证明并发和恢复。

| 场景 | 应核对的行为 |
| --- | --- |
| 已登记前页，新文件在后页 | 定时轮询和事件触发后可最终登记；前 200 项的本地文件系统轮询反例已首红，事件路径未复现。 |
| 数据文件和 `.done` 分页 | 缺失标记不登记；标记跨页存在时可登记；覆盖追加全名和替换扩展名两种命名、MANIFEST 摘要不符及修复后重试。 |
| 数据文件和批次清单分页 | 清单先到、后到及同到均核对 group、`requiredFiles`、v2 映射；已登记成员仍可按既有语义回填。 |
| 稳定窗口跨页 | 前后页轮转后保留有效观察；对象替换或删除后重建时不沿用旧 size/etag 的稳定时间。 |
| 周期中对象变化 | 当前位置之前插入对象、删除对象、列举失败和重试；后续完整周期可重新发现，失败或未完成周期不误清有效状态。 |
| 并发及恢复 | 多实例交替调度、事件和定时并发、扫描中断/重启及旧执行者迟到提交；进度不倒退、不永久漏扫，核对文件记录和审计结果。 |
| 配置变更与边界页 | bucket、prefix 或影响扫描身份的配置变化不复用错误进度；空列表、末页及恰好整页均可结束并开始新周期。 |
| 已登记与重复投递 | 不同校验和及重试按现有文件版本与唯一性语义对账；不能仅以调用次数证明业务副作用正确。 |

### 可审阅方案与权衡

**方案一：每次扫描列举完整前缀。** 保持当前在同一轮快照中匹配数据、完成标记和批次清单的方式，分页读取直到末页后处理，改动范围较小，也不依赖跨轮本地游标。但它会改变 `batch-size` 当前对每轮候选数量的限制：对象数量增长会增加每轮存储列表请求、内存、文件登记/对账耗时和锁持有时间，现有两分钟锁上限及事件触发并发都需要重新评估。不能把此方案描述成有界处理，也没有当前容量数据证明可接受。

**方案二：有界分页和共享扫描状态。** 按部署命名空间及 bucket、prefix 等扫描身份维护可恢复的周期和分页进度；将待稳定对象的 size、etag 和首次观察时间，以及稳定批次清单与成员映射保存在独立索引中。每批列举和处理有明确预算，完整周期结束后再分批清理过期状态；数据登记或回填时通过索引匹配清单，支持清单晚到对账。完成标记使用 bucket 与精确 key 检查，不依赖与数据文件处于同页；需要校验清单版本时，可按完整 key 为前缀列举并确认返回 key、size 和 etag，而不能只检查存在性。

方案二避免每轮全量读取和全量内存，但周期可能跨多个调度间隔，首次发现延迟增加；持久状态的空间与待观察对象数、清单数及其成员数相关。除限制对象列表页，还应限制清单成员索引、回填和清理的每批工作量。需要独立 Flyway 迁移、Import Worker 数据库角色权限、升级和回滚安排，以及隔离数据库中的并发、崩溃恢复和幂等验证；不要求新建通用框架。

两个方案均需考虑以下现状：`batch.shedlock` 是框架管理的锁而非业务状态，且默认锁提供者为 Redis；现有等待分片游标是另一个调度器的专用状态，均不能直接复用。事件入口直接调用无锁 `scan()`，仅定时入口使用 ShedLock，不能假定所有扫描天然串行。对象存储读取和数据库状态更新没有共同事务；跨进程推进需要并发控制、失败后重扫与幂等处理，不能仅写入下一页 marker。稳定性依赖两次观察到的对象快照，不构成对象存储的强一致快照保证。

当前匹配逻辑按租户及成员文件名查找首个清单，多个清单同时匹配时遍历顺序没有稳定契约；数据先于清单时，现有静态配置回退也可能先登记，分页改造本身不能宣称消除这类既有时序问题。具体兼容语义和资源预算需要结合真实使用场景决定。

**决定状态：用户选择方案二，已随 #1339 合入 `main`（2026-11-10）。** 本续查当时未修改扫描器生产代码、首红测试仍失败；合入后 `ImportIngressScannerPaginationTest` 两项随 PR 门禁和主线 Full CI 通过。真实 S3、多实例、故障恢复和容量仍未验证，见[待办索引](../plans/todo-master.md#文件等待与入口扫描2026-11-09)。
