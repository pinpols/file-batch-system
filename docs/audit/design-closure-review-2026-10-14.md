# 本次设计与跨模块正确性复核（2026-10-14）

范围：回应“workflow/day 之外还有无类似设计缺陷，以及此前审查为何遗漏”。只读检查本地 main b1b24def1、已获取的 origin/main 17c245b93 差异，以及 workflow-daily-integration 工作区相关文件。没有获取远端最新状态，审查未修改业务代码。主工作区原有两处修改保留。记录现归档至 `feature/workflow-daily-integration` 工作树；当前处置状态见 [TODO Master](../plans/todo-master.md)。下列行号对应审查时的 main 基线，整合代码行号可能不同。

证据口径：下面六项均有当前代码调用链证据；第 1 项的 SQL 误删机制完成真实 PostgreSQL 临时表复现，其余尚未执行端到端复现。未检查完整前端、所有 SDK、生产拓扑或真实外部交付环境。这是定向检查结果，不是全仓无遗漏证明。

**SEAM-1 / P1：PROCESS 清理跨分区误删，发布阶段可能仍返回成功。**

- 业务库 process_staging 按 staged_at RANGE 分区：[create_biz_tables.sql:63](../../scripts/db/business/create_biz_tables.sql)。
- 清理用父表裸 ctid 选旧行，再从整个父表按 ctid 删除：[ProcessStagingMapper.xml:10](../../batch-worker/process/src/main/resources/mapper/business/ProcessStagingMapper.xml)。不同分区存在相同 ctid，外层没有 tableoid 或时间谓词。
- 若新 staging 在 VALIDATE 与 COMMIT 之间被删除，发布返回 0 行仍 success：[SqlTransformComputePlugin.java:334](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java)。
- 修复方向：用能跨物理分区唯一定位的行身份完成有界删除，并补真实 PG 多分区回归；发布阶段按实际模式检查完整性，不把 SQL 无异常等同于处理完整。
- 整合工作区对应清理 SQL 未变。

实际复现命令（退出 0；只用 TEMP 表并 ROLLBACK；时间谓词改为固定日期保证可重现）：

```sh
docker exec -i batch-postgres-primary psql -X -U batch_user -d batch_business -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;
CREATE TEMP TABLE codex_review_staging (label text NOT NULL, staged_at timestamptz NOT NULL) PARTITION BY RANGE (staged_at);
CREATE TEMP TABLE codex_review_staging_old PARTITION OF codex_review_staging FOR VALUES FROM (MINVALUE) TO ('2026-10-01 00:00:00+00');
CREATE TEMP TABLE codex_review_staging_fresh PARTITION OF codex_review_staging FOR VALUES FROM ('2026-10-01 00:00:00+00') TO (MAXVALUE);
INSERT INTO codex_review_staging VALUES ('expired', '2026-09-01 00:00:00+00'), ('fresh', '2026-10-14 00:00:00+00');
SELECT label, tableoid::regclass AS partition_name, ctid FROM codex_review_staging ORDER BY staged_at;
DELETE FROM codex_review_staging WHERE ctid IN (SELECT ctid FROM codex_review_staging WHERE staged_at < '2026-10-01 00:00:00+00' ORDER BY staged_at LIMIT 1) RETURNING label;
SELECT count(*) AS remaining_rows FROM codex_review_staging;
ROLLBACK;
SQL
```

结果：expired 与 fresh 分别位于两个分区，ctid 都是 (0,1)；DELETE RETURNING 同时返回两行，输出 DELETE 2，remaining_rows=0。此结果证明 SQL 误删机制；并未运行完整 PROCESS 并发发布场景。

**SEAM-2 / P1：Export 重试先覆盖已经登记的文件，再发现摘要冲突。**

- 故障窗口：STORE 和 REGISTER 已完成，COMPLETE/report 前故障；重派重新执行完整阶段链。ExportTaskExecutor.java:57、ExportStepExecutionAdapter.java:98、AbstractStageExecutor.java:76/251 支持该路径，均位于 batch-worker 下。
- [StoreStep.java:97](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/StoreStep.java) 重加密；:240 无条件 copy 到相同 final key。
- [BatchObjectCryptoService.java:109](../../batch-common/src/main/java/io/github/pinpols/batch/common/service/BatchObjectCryptoService.java) 每次随机 IV，通常产生不同密文和摘要，即使明文相同。
- [RegisterStep.java:115](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/RegisterStep.java) 此时才核对已有 file_record 摘要并返回冲突。数据库摘要与实际对象已不一致，重试继续重复该路径。
- 测试盲区：StoreStepTest.java:166 将加密 mock 为直接复制；RegisterStepTest 只验证登记冲突，没有联合验证对象保持不变。
- 当前修复：`StoreStep` 在加密/上传之前按 tenant + bucket + storage_path 查找登记记录；核对记录 SHA-256 与对象实际摘要后复用原对象、原摘要及登记的安全元数据；缺少摘要或对象已漂移则失败关闭，避免覆盖。`StoreStepTest` 覆盖复用/漂移拒绝。
- 验收证据：Export 集成测试使用 PostgreSQL 17.11 + MinIO，先用真实 AES-GCM 加密生成对象并在 `file_record` 落摘要/安全元数据，再通过真实 Spring `StoreStep` 重试；验证对象摘要、解密后的原始 bytes、数据库摘要和 JSONB 安全元数据不变。
- 剩余验收：该用例从“REGISTER 已成功”的数据库状态启动，未实际执行 STORE→REGISTER→COMPLETE 前故障、worker/Orchestrator 重派和全阶段重放；完成该跨层故障恢复演练后关闭该项。
- 整合工作区核心 STORE/REGISTER 路径仍未修复。未运行真实存储故障复现。

**SEAM-3 / P1：补跑“原配置/指定版本”只被记录，没有真正选择对应配置。**

- [RerunRequest.java:49](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/shared/command/RerunRequest.java) 暴露三种版本策略。
- [DefaultCompensationService.java:309](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java) 抽取原实例 effectiveParams，:717 透传策略。
- [DefaultLaunchValidationService.java:55](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultLaunchValidationService.java) 仍加载当前启用定义；DefaultLaunchService.java:458 使用当前路由/定义版本，:514 仅记录用户策略。
- 场景：原 v1 超时 600 秒、队列 A；改 v2 为 30 秒、队列 B；选择 v1 补跑仍可能执行 v2 策略，形成旧业务参数与新策略混用。
- 测试盲区：[DefaultConsoleJobRecoveryServiceTest.java:200](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/job/infrastructure/DefaultConsoleJobRecoveryServiceTest.java) 仅 mock 下游返回指令编号，不验证真正执行版本。
- 当前修复：新根 WorkflowRun 建立时按策略选择完整 JobDefinition，并将选中定义写入其不可变 execution snapshot。`USE_ORIGINAL_CONFIG` 通过源 JobInstance 关联到原 WorkflowRun 并核对定义版本；`USE_SPECIFIED_VERSION` 查询同租户下所有含有匹配 Job ID/code/version 的历史 WorkflowRun 快照，覆盖组合 Workflow（其 `single_job_definition_id` 为空）；无快照或版本不符时拒绝。`USE_LATEST_CONFIG` 使用当前已启用定义。小数来源 ID/指定版本拒绝，避免数值截断误命中。
- 验收证据：解析单测 7/7；WorkflowRunMapper XML 测试 1/1；PostgreSQL 17.11 集成测试 1/1，以组合 Workflow 快照验证版本/租户过滤，并通过真实 WorkflowRunMapper 调用 resolver 验证三种策略选择；`./mvnw -o -ntp -pl batch-orchestrator test` 1875/1875，0 failures/errors/skips。V279 空库迁移通过；迁移 safety lint 覆盖 40 个改动迁移、0 issues。
- 剩余验收：尚未执行“v1 运行→修改 v2→三种策略补跑→实际 CLAIM 配置”的跨模块链路，也未验证 Console 请求的有效参数与策略在最终 JobInstance/Task 快照中的一致性；SEAM-3 仍开放。现有 R17 的同实例重试冻结不能替代新补跑实例的配置版本选择验收。

**SEAM-4 / P1：主动取消被识别为普通失败，可能自动重试。**

- [DefaultTaskExecutionWrapper.java:74/270](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java) 生成 WORKER_EXECUTION_CANCELLED，HTTP 与 Controller 原样透传。
- [DefaultTaskOutcomeService.java:71/263](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultTaskOutcomeService.java) 只认 CANCELLED，:186 因而走失败重试。
- DefaultRetryGovernanceService.java:137 不把真实取消码列为非重试；JobTaskMapper.xml:343/361 的 claim/reset 不能阻止重新开始执行。
- 影响：有预算可重新派发并开始业务副作用，下一次续租才再次感知取消；无预算误入失败/死信。
- 测试盲区：Worker 单测断言自己的取消码；[DefaultTaskOutcomeServiceTest.java:374](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultTaskOutcomeServiceTest.java) 手造 CANCELLED，两个模块的测试可以各自通过。
- 当前修复：Orchestrator 取消判定码改为 Worker 实际返回的 `WORKER_EXECUTION_CANCELLED`；仍要求任务已带 `cancel_requested=true`，并沿现有取消终态路径绕过 retry governance。
- 验收证据：`DefaultTaskOutcomeServiceTest,DefaultRetryGovernanceServiceTest` 35/35，0 failures/errors/skips；断言任务 CANCELLED、无自动重试调用。Orchestrator 全量测试 1875/1875，0 failures/errors/skips。
- 剩余验收：未运行 Worker 实际取消、传输报告、持久化 task/partition/instance 收口及无新 outbox/Kafka 派发的跨模块闭环；SEAM-4 仍开放。

**SEAM-5 / P1：取消一个分区没有收口其 task 和父实例。**

- [InstanceManagementApplicationService.java:103](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/InstanceManagementApplicationService.java) 仅修改 partition 为 CANCELLED。
- [TaskOutcomeInstanceProgressor.java:81](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/TaskOutcomeInstanceProgressor.java) 仅以 success+failed 判断完成。
- 场景：两个分区，取消尚未运行的一份，另一份成功。完成数仍为 1/2，取消分区不再 report，父实例可能一直 RUNNING。
- 现有 terminal-child reconciler 先要求父实例终态，无法从此状态恢复。
- 修复方向：定义取消分区的父实例汇聚语义，并原子收口关联任务/资源；真实数据库测试 cancel+另一分区成功及并发顺序。整合工作区仍存在；未运行集成复现。

**SEAM-6 / P1（条件启用）：批量 CLAIM 后排队任务没有续租，过期后旧 Worker 仍可能执行。**

- [TaskDispatchExecutor.java:90](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/application/TaskDispatchExecutor.java) 先认领全部，:97 起同步逐个执行。
- [DefaultTaskExecutionWrapper.java:184](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java) 只有轮到执行才注册续租。
- 场景：A/B 批量认领，A 执行超过 B lease；B 回收并重派后，原 Worker 结束 A，仍用旧 claim 执行 B。REPORT fence 不能撤销已经发生的外部副作用。
- [WorkerBatchClaimProperties.java:21](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/config/WorkerBatchClaimProperties.java) 默认关闭，不能推断当前部署已暴露。
- 修复方向：使认领批次与执行容量、续租注册生命周期一致，执行前确认有效所有权；用可控时钟/阻塞 A/回收 B 验证旧 invocation 不执行。整合工作区相关流程仍在；未运行故障注入。

**覆盖与遗漏机制**

本轮深入：执行取消与恢复、批量 claim、Export 发布与重试、PROCESS 清理与发布、补跑配置版本。Import/Dispatch/Atomic 仅抽查，未完整覆盖；认证租户全链、全 SDK、前端、容量与部署未审完。

现存报告不能还原每次 AI 的内部推理，但可识别三个漏检机制：一是从已有代码/字段出发验证实现，缺少从业务义务反推模型；二是局部 mock 固化各模块自己的假设，缺少真实生产者到消费者的联合断言；三是把功能存在、文档完成、单项测试通过外推为整个系统设计成熟。设计审查应独立建立运行身份、版本/日期冻结、取消终态、外部副作用和恢复不变量，再用跨模块失败场景挑战它们。

本轮没有编译或运行 Maven 单元/集成套件，也没有宣称生产已经发生上述问题。仅 SQL 误删完成临时表实证，其余是有完整静态路径支撑、仍待定向运行复现的高置信缺陷。

## 前端补充审查（2026-10-14）

本节补充前文未完整覆盖的前端。基线为前端 `workflow-unification-plan/batch-console` 的 `662d254 + WIP`，配对后端为 `workflow-daily-integration/file-batch-system` 的 `e39270b6c + WIP`。检查实际工作树源码、调用方、Controller/Service/SQL 和现有测试；没有以远端分支已同步推断两端契约一致。

本轮确认 9 项独立问题。FE-1、FE-2、FE-4、FE-5、FE-7、FE-9 运行了当前源码的本地逻辑复现，网络、部分 UI/Vue 状态或服务响应使用替身；这些结果证明前端错误状态、请求或提示，不等同真实浏览器、真实后端、数据库落地或生产事故复现。FE-3、FE-6、FE-8 为静态闭合。没有重复计入原 SEAM-3 的补跑配置版本选择问题，也没有把已区分 CANCEL_REQUESTED 的界面误报为“取消请求即完成”。

以下 FE 路径相对于前端工作树，BE 路径相对于本报告所在后端工作树。核对 HEAD 表明这些缺陷的核心路径已有于主干；不将它们归因于本次 stash 冲突处理。本轮只补审查文档，没有修改业务代码或真实业务数据。

### FE-1 / P1：通知渠道迟到响应可引导管理员覆盖另一租户配置

- 触发：有多租户权限的管理员在 A 加载通知渠道，切到 B，B 响应先到、A 后到；两边都有同编码 `alerts`。旧响应覆盖 B 的列表，编辑旧行后提交使用 B 租户，配置却来自 A。
- FE：`src/views/system/components/NotificationChannelsTab.vue:244` 直接接收响应；:270 编辑只记渠道编码、不记行租户；:331 提交使用当前 `tenant.tenantId`；:411 切租户仅 reload。`src/layout/DefaultLayout.vue:35` 的组件 key 是路径，不会因租户变化重挂载。`useTenantReload`、`useListLoadState` 均不废弃迟到响应。
- BE：`batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/infrastructure/DefaultConsoleNotificationApplicationService.java:164` 按请求租户和编码查找；`batch-console-api/src/main/resources/mapper/NotificationChannelMapper.xml:42` 同样按二者更新配置。`ConsoleTenantGuard.java:44` 允许管理员操作 B，因此这是授权用户被 UI 引导误写，不能描述成无权限越权。
- 本地结果：`activeTenant=tb, displayedTenant=ta`，实际保存请求是 `["alerts","tb",{configJson:"{\"to\":\"a@example.test\"}",...}]`。没有向真实服务发送请求。
- 影响：B 的通知配置可能被 A 的收件人或渠道配置覆盖；不只是列表暂时显示错误。
- 修复与验收：请求核对租户/代次，编辑草稿冻结行租户和身份，切换时失效草稿；用 A/B 响应逆序和同名渠道验证 B 配置保持不变。
- 测试盲区：`e2e/notification-crud.spec.ts:14` 固定租户 CRUD；`e2e/multi-tenant-and-stream.spec.ts:29` 的 rapid switch 主要串行查询 auth/me，没有通知页面逆序响应后的写入断言。

### FE-2 / P1：自助重跑改变作业/日期后仍携带旧实例

- 触发：先选 A / 10-12 的实例，再改为 B / 10-13，不重新选择实例，提交审批。
- FE：`src/composables/useInstanceNoSearch.ts:56` 仅清候选；`src/views/system/components/SelfServiceRerunTab.vue:140` 继续提交 `targetInstanceNo`。清空选项不会自动清空绑定值。
- BE：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java:552` 按实例号定位，未核对提交的 job/date；:325–328 以被定位实例的作业和日期构造实际重跑目标。
- 本地结果：真实 Vue 响应式执行后候选数为 0，但实际 submit 请求仍是 `{jobCode:"B",bizDate:"2026-10-13",targetInstanceNo:"old-A-2026-10-12"}`。未实际审批或启动作业。
- 影响：页面显示的新作业/日期与审批后执行的旧目标不同。此问题独立于 SEAM-3 的配置版本选择。
- 修复与验收：联动字段变化清目标值；提交/审批/执行端核对实例所属租户、作业和日期；以先选实例再改查询条件的真实表单操作验证。
- 测试盲区：`e2e/flows/03-job-fail-rerun-approve.spec.ts:28` 直接发送固定 API payload，没有操作联动表单，且 :40 接受部分失败状态。

### FE-3 / P1：配置同步的导出/导入类型不闭合

- FE：`src/views/system/components/configSyncTypeOptions.ts:7` 提供 10 类导出，`ConfigSyncTab.vue:260` 可将完整 bundle 复制到导入。
- BE：`batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigSyncApplicationService.java:72` 导出完整 bundle，但 :213–224 的 `toInitRequest` 只传 job/workflow/pipeline/channel/template，遗漏 `resourceQueues / batchWindows / businessCalendars / quotaPolicies / alertRoutings`。
- 场景：从 A 导出日历/告警路由等，导入缺失这些配置的 B；对应列表在映射时丢失，执行器不会处理，可能返回成功但目标仍缺配置。
- 证据：静态调用链；没有执行真实配置导入。Job Bundle 的另一个映射包含这些类别，不能将本问题泛化到所有导入入口。
- 修复与验收：统一完整映射并逐类核对；以空目标租户往返导出/导入，再逐类回读结果。
- 测试盲区：`e2e/config-management-ops.spec.ts:273` 同租户往返，:91 的导入 helper 侧重 HTTP 和 toast，无法证明目标新增了导出内容。

### FE-4 / P2：标签行操作读取未提交的筛选草稿，目标资源错位

- 触发：查询 job-A 的标签，将资源编码输入改为 job-B 而不点查询，再保存旧表格里的 owner 标签。
- FE：`src/views/system/components/TagResourceTab.vue:222` 从行只取 tagKey；:209 以当前 queryForm 作为 upsert 资源身份；:266 删除也从当前筛选值取身份。旧行没有因筛选草稿变化而失效。
- BE：`batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/service/ConsoleResourceTagService.java:52` 使用请求资源；`batch-console-api/src/main/resources/mapper/ConsoleResourceTagMapper.xml:63` 按请求组合键 upsert 并覆盖 value，:77 按组合键删除。
- 本地结果：显示的旧行来自 job-A，保存请求 `resourceCode=job-B, tagKey=owner, tagValue=edited-A`；另一次独立加载 A 后删除也请求 job-B。删除确认文案会显示 B，但保存没有确认，行身份错位本身仍成立。
- 修复与验收：使用行自带完整身份或成功加载时冻结的查询上下文；筛选草稿改变后清理/禁用旧行操作；断言实际请求仍对应被操作行。
- 测试盲区：`e2e/tag-resource-crud.spec.ts:23` 主要验证 tab、搜索按钮和抽屉取消，没有真实完成 upsert/delete 后回读。

### FE-5 / P2：Bundle 已回滚，创建/导入界面仍报成功

- FE：`src/views/job/JobDefinitionWizard.vue:737` 忽略 createBundle 返回值，:743 显示成功并跳转；`JobDefinitionList.vue:924` 多租户导入、`src/views/system/components/ConfigSyncTab.vue:348` 有同类问题。
- BE：`batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/infrastructure/DefaultConsoleJobBundleApplicationService.java:52` 设置 strict；`batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigInitApplicationService.java:59` 将已回滚的异常转成 `failureTenants/results[].success=false`；Controller 仍合法返回 HTTP 200 的外层成功 envelope。
- 本地结果：API 替身返回 `successTenants=0, failureTenants=1, results[0].success=false`，真实 submit 函数仍发出成功提示并跳到列表。
- 反证：真正 HTTP 4xx/5xx 会 reject；本问题特指 HTTP 200 中的业务失败结果。
- 修复与验收：API 用具体结果类型，逐租户/逐项检查失败，保留输入并提供失败原因和失败目标重试；断言事务回滚结果不会触发成功跳转。
- 测试盲区：`e2e/job-definition-crud.spec.ts:33` 有成功创建回读，缺少 nested failure 的创建/导入场景。

### FE-6 / P2：Job 编辑允许修改的字段未持久化却提示成功

- FE：`src/views/job/components/JobConfigBasicForm.vue:272` 提供执行器/参数 Schema；`src/views/job/JobDefinitionList.vue:1612` 提交 `bizType/dagEnabled/executionHandler/paramSchema/priority`，:1638 提示成功。
- BE：`batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/infrastructure/DefaultConsoleJobDefinitionApplicationService.java:219` 到 :298 的更新映射未读这些字段；`batch-console-api/src/main/resources/mapper/JobDefinitionMapper.xml:401` 未更新对应列，却递增版本。
- 场景：执行器 A 改 B 或 Schema A 改 B，保存成功后刷新仍是 A；后续执行使用旧配置。
- 证据：静态调用链，未实际更新数据库。`defaultParams` 有真实更新路径，不能说所有 Job 字段都无效。
- 修复与验收：明确可编辑字段并贯通 UI/DTO/Service/SQL；以独立预期逐字段保存后回读，确认运行时消费对应配置。

### FE-7 / P2：Workflow 节点跳过按钮与后端准入状态相反

- FE：`src/views/monitor/WorkflowRunDetail.vue:647` 将 FAILED 视作不能跳过；:655 对 PENDING/RUNNING/WAITING 返回 true，:342 控制按钮。
- BE：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunManagementApplicationService.java:146` 只允许 RUNNING/FAILED 的 Workflow Run，:156 只允许 FAILED 节点。
- 本地执行前端真实谓词：FAILED 按钮隐藏；PENDING/RUNNING/WAITING 按钮显示。后端条件经过静态核对及既有 FAILED 成功用例核对，没有运行真实 skip。
- 影响：可恢复的失败节点无入口，页面展示的其它状态点击后被拒；FE-9 又会吞掉这些 409 的全局提示。
- 修复与验收：按钮同时遵循运行和节点状态，完整矩阵对照后端；执行 FAILED skip 后核对节点和下游最终状态。
- 测试盲区：`e2e/flows-ui/13-workflow-dag-ui.spec.ts:36` 仅看详情页面异常，没有实际跳过操作。

### FE-8 / P2：自助补偿选项缺必要目标，审批之后无法执行

- FE：`src/views/system/components/SelfServiceCompensationTab.vue:111` 提供 STEP/PARTITION/FILE/DLQ，但 :145 请求没有 targetId/relatedFileId。
- BE：`batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/web/ConsoleSelfServiceJobController.java:75` 的 DTO 也无这些标识；自助服务持久化该 payload。`DefaultConsoleApprovalApplicationService.java:84` 先批准再执行补偿。
- 执行要求：`batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java:356` STEP 必须 step ID，:386 PARTITION 必须 targetId，:402 FILE 必须文件 ID，:473 DLQ 必须 targetId。实例号不能替代这些标识，后续透传也未补齐。
- 影响：可提交审批，但所述类型执行时因缺目标失败；同 payload 重试仍不能完成。
- 证据：完整静态链路，未创建真实审批。
- 修复与验收：为每类补偿提供正确资源选择器并贯通 API；尚不支持的类型在提交前明确拒绝；逐类型完成申请、批准、执行、最终效果核对。
- 测试盲区：`e2e/flows/05-compensation.spec.ts:34` 接受 400/500/503 等失败状态，:49 只断言状态码 <600；没有完成审批执行。这些断言不能作为业务成功证据。

### FE-9 / P2：自动幂等键被误当成重放证据，真实 409 冲突被静默

- FE：`src/api/interceptors.ts:420` 首次写请求也自动生成 key 和 signature；:555–570 只要有 signature 就把任何 HTTP 409 标记 `idempotentReplay/silenced` 并只记 info，不要求服务端声明幂等重放。
- 实际用户链路：审批列表 `src/views/approvals/components/GeneralApprovalsTab.vue:505` 提交后在 :512 统一 catch 并当成取消；当另一人已经拒绝该审批，BE `batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/DefaultConsoleApprovalApplicationService.java:77` 返回 STATE_CONFLICT/409。此时用户第一次点击批准也没有冲突提示、不会刷新列表。
- 本地执行真实请求/错误拦截器：首次自动 key 的 STATE_CONFLICT 得到 `silenced=true, toastCount=0, logLevel=info`；控制组显式 key 得到 `silenced=false, toastCount=1, logLevel=error`。API 响应和通知/日志接口为替身，DEV 环境值置 false，未调用后端。
- 影响边界：局部主动弹错的页面仍可能显示错误，不能说全站所有 409 都完全不可见；全局错误分类本身错误，依赖全局提示的审批/skip 等操作受影响。
- 修复与验收：仅凭服务端明确的幂等重放码分类；分别验证首次业务冲突、并发版本冲突、真正重复请求，保留可操作的错误反馈。
- 测试盲区：现有 interceptor 集成测试主要覆盖成功、400/403、日志脱敏，没有覆盖首次写请求的真实 STATE_CONFLICT/409。

### 本轮验证记录与覆盖边界

前端工作目录：`<Codex 数据目录>/worktrees/workflow-unification-plan/batch-console`。

| 实际命令 | 结果 | 证明范围 |
| --- | --- | --- |
| `npm run typecheck` | 退出 0 | 当前已安装工具链下 TypeScript/Vue 类型检查通过 |
| `BE_OPENAPI_PATH=../file-batch-system/docs/api/console-api.openapi.yaml bash scripts/check-api-drift.sh` | 退出 1，DRIFT DETECTED | 配对整合后端与前端生成类型尚不一致，首段差异含 batch-day catch-up preview/control/historical-gap 接口 |
| `node /tmp/frontend-tenant-design-probe.cjs` | 退出 0，复现 FE-1/FE-4 | 执行 SFC script setup，UI/Vue/API 替身，捕获错误修改目标 |
| `node /tmp/frontend-recovery-contract-review-2026-10-14.cjs` | 退出 0，复现 FE-2/FE-7 的前端行为 | 真实 Vue composable、原 submit/predicate；API 替身，后端 skip 条件另做静态核对 |
| `node /tmp/frontend-bundle-result-repro.js` | 退出 0，复现 FE-5 | 原 submit 函数，模拟合法业务失败结果，观察成功提示/跳转 |
| `node /tmp/frontend-design-interceptor-repro.cjs` | 退出 0，复现 FE-9 | 真实拦截器逻辑，模拟 409，比较首次自动 key 与显式 key |

类型检查输出留在 `/tmp/frontend-design-typecheck-20261014.log`，接口漂移输出留在 `/tmp/frontend-design-api-drift-20261014.log`。临时复现脚本不是已提交的回归测试；首个 interceptor harness 因 CommonJS 不支持 import.meta 未启动，随后将 DEV 环境值替换为 false 才成功执行，未修改生产源码。

API 漂移属于当前整合交付缺口，未混入上述 9 个原有缺陷。更新 Git 基线、类型检查通过均不足以证明配对前端已完成整合。没有运行全量 Vitest/Playwright、真实登录后的浏览器路径、审批/启动作业或生产环境验证。视觉、移动端、全角色权限、全部页面和所有 SDK 不在本轮覆盖范围，不能报告“前后端已全量查完”。

漏检机制已有可检查的证据：部分 E2E 允许失败状态仍通过；部分用例只打开页面或抽屉；同租户往返掩盖跨租户遗漏；字段可发送和 HTTP 200 被当成保存/业务完成。后续验收应断言“用户看到的目标 = 请求目标 = 实际修改/执行目标”，并回读持久化结果、检查失败后的可恢复状态。

