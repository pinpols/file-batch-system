# 租户作业配置改造：阶段 0 基线盘点

> 状态：首轮事实盘点完成，阶段 0 未关闭。本文只记录代码和契约证据，不代表未决产品/平台决议已经批准。
>
> **历史基线快照（2026-10-10）**：本文结论仅适用于下方记录的基线提交与检查范围，不代表当前代码、决议或验收状态。后续实施进度和当前决议以[租户作业配置改造计划](../plans/tenant-job-configuration-simplification-plan-2026-10-09.md)为准；请勿将本文中的未决项直接当作当前待办。

## 范围与基线

| 仓库 | 基线分支 | SHA |
|---|---|---|
| 后端 `file-batch-system` | `main` | `f22bd50fe946a0643ddaf270149fdb8f917ca59d` |
| 前端 `batch-console` | `main` | `fb3f603d7c969a9ce5ed095df42a71ec33265d9a` |

本次只读检查配置包 schema/解析与 apply 权限、Job Definition REST 写入入口、前端模板下载与导入向导；未修改运行代码、数据库、API 契约或前端页面，也未运行业务测试。当前正在进行的 #1203 与配对前端已有产品旅程分支均未纳入本次基线或修改范围。

## 已确认事实

### 工作簿规模与场景投影

配置包由后端 `ConfigPackageExcelSchema` 声明 12 个数据 Sheet，共 170 列。当前列数如下：

| Sheet | 列数 | 当前解析特征 |
|---|---:|---|
| `resource_queue` | 13 | Sheet 可省略；若出现，表头必须完整；并发、分区并发、QPS、公平权重是业务行必填字段 |
| `business_calendar` | 10 | Sheet 可省略；若出现，表头必须完整 |
| `batch_window` | 11 | Sheet 可省略；若出现，表头必须完整 |
| `job_definition` | 23 | Sheet 必须存在；除 `depends_on_job_code` 外，所有表头必须存在；向导元数据将作业编码、名称、类型、调度类型标为必填，其余字段有默认或条件语义 |
| `job_monitoring_policy` | 10 | Sheet 必须存在；只有 `job_code` 表头强制要求，其余表头可缺省；内容混有运行阈值和业务完成时限 |
| `file_channel_config` | 10 | Sheet 必须存在且完整表头 |
| `file_template_config` | 41 | Sheet 必须存在且 41 个表头全部强制；其中多字段仅适用于特定格式、方向或高级安全能力 |
| `pipeline_definition` | 9 | Sheet 必须存在且完整表头 |
| `pipeline_step_definition` | 12 | Sheet 必须存在且完整表头 |
| `workflow_definition` | 7 | Sheet 必须存在且完整表头 |
| `workflow_node` | 16 | Sheet 必须存在且完整表头 |
| `workflow_edge` | 8 | Sheet 必须存在且完整表头 |

这些是 schema 列数和解析器表头约束，不等同于“每个场景都要由用户填写的单元格数”。行级必填、条件必填、服务端默认、平台拥有字段及真正需要用户决定的字段，仍需按 Worker × 文件格式逐项盘点后才能给出精简率。

当前前端 `TenantPackageImportWizard` 的多选场景控件只调用 `sample-template`。后端按场景选择样例行，但 `ConfigPackageExcelWorkbookWriter` 仍写出完整工作簿结构；空白 `template` 也使用全量 sheet 规格。它不是“按需生成场景模板”。前端英文说明、控制器 JavaDoc 和部分确认文案仍写 11-Sheet，与当前 12-sheet schema 不一致。

### 权限与资源归属

| 操作 | 当前后端权限 | 当前前端表现 | 风险/事实边界 |
|---|---|---|---|
| 配置包下载、导出、指南 | `ADMIN`、`TENANT_ADMIN`、`AUDITOR` | 页面提供入口 | 只读不代表能提交配置 |
| 配置包 upload、preview、行内修正、预览工作簿 | `ADMIN`、`TENANT_ADMIN` | 页面提供入口 | 预览不写库 |
| 配置包 apply | 仅 `ADMIN` | 页面仍显示可点击的 Apply；不按角色隐藏 | 租户管理员会在提交时才遇到后端拒绝；产品权限流程不闭环 |
| Job Definition create/update/copy/clone/enable | `ADMIN`、`TENANT_ADMIN` | 表单可编辑 queue、worker group、shard strategy、priority 等字段 | 即使从 Excel 移除资源字段，也不能留下 REST 写入旁路 |
| `resource_queue` 独立 CRUD | 仅 `ADMIN` | 平台资源入口 | 与 Excel 的资源定义是不同授权路径 |

配置包 apply 服务在单事务内执行，但不能把所有对象都概括成相同的 MERGE 语义：多数实体按业务键 upsert，未出现的顶层行通常不删除；更新已存在的 `pipeline_definition` 时会先删除该 Pipeline 的全部 step 再按本次工作簿重建，更新 `business_calendar` 时会删除并重建该日历的 holidays。Workflow nodes/edges 是 upsert，不会自动删除旧行。因此当前存在“同一 apply 中不同对象集合的缺项语义不同”，必须通过对象级 MERGE/替换契约和 preview 明示，不能简单宣称“缺失即不变”或称当前实现为全量 RESTORE。当前导出包含平台资源队列，因此导出的业务配置包和可供租户编辑的配置包不是同一权限/所有权边界。

### 配置读取与快照初步证据

- `batch.job_instance` 存在 `params_snapshot`、`job_definition_version` 和 `rerun_policy_snapshot`；数据库注释明确 `params_snapshot` 创建时冻结。
- `DefaultLaunchService` 在实例创建时记录 `job_definition_version`、`params_snapshot`、`queue_code`、`worker_group`、`calendar_code` 等字段；但未发现实例持有 file template、pipeline step、channel、job monitoring policy 的不可变版本引用。
- Worker 的 `PlatformFileRuntimeMapper.selectLatestTemplateConfig` 按 tenant + templateCode + enabled 查询，并 `order by version desc limit 1`；`selectLatestPipelineDefinitionId` 按 tenant + jobCode 取最新版本。IMPORT/EXPORT 阶段随后加载这些当前配置。因此实例创建后、相应阶段运行前若模板或 Pipeline 发布新版本，执行内容有读取新配置的路径；目前的作业版本/参数快照不能等同于完整执行快照。
- Worker DISPATCH 的 `FileDispatchMapper.selectChannelConfig` 按 tenant + channelCode 读取当前通道行，没有版本条件；Orchestrator 的 `JobMonitoringMapper` 在扫描时直接 JOIN 当前 `job_monitoring_policy`。运行中实例的监控阈值/严重级别可能随当前策略变化，且策略 SQL 使用 `completion_deadline_updated_at` 限制某些完成截止候选，属于可观察的存量策略变更语义。
- Queue 行为也不是单一的“创建时冻结”：`DefaultResourceQueueManager` 可根据显式 queueCode 或当前启用队列及容量/公平权重选择队列，且本地缓存有 TTL；`DefaultResourceScheduler` 把解析结果写入计划。`DefaultLaunchService` 先写入作业定义上的 queueCode/workerGroup。需继续确认不同 launch/retry/DAG 路径最终持久化和执行所用的 resolved route，不能只凭 instance 初始字段宣称路由快照完整。
- 这些发现是当前代码的行为证据，不自动意味着“所有配置都必须冻结”。阶段 0 需要按配置类型明确一致性语义：实例级固定、阶段启动时解析、轮换即时生效或仅新实例生效，并规定版本保留、重试和显式重新绑定规则。尤其需覆盖“已排队未执行”“Pipeline 中途跨阶段”“重试/补偿”和“Secret 撤销”情形。
- `batch.secret_version` 有版本化元数据，且密钥载荷受保护；尚未证明 queued/running 实例对密钥轮换、撤销的确定行为，也没有把 Secret 明文放入工作簿的依据。
- 当前 `resource_queue` 是 tenant-scoped 的存量设计。租户不能自行配置容量是明确目标；究竟采用平台 provision 的租户逻辑队列、受限服务等级映射，还是经验证的共享物理池，仍需测算队列基数、claim 查询、公平性和隔离，不能仅凭字段归属直接重写路由。

### 当前导入默认与 MERGE 语义缺口

- 工作簿指南 `defaultBehaviorFor` 对多数选填列采用通用说明：“可留空；不覆盖现有可选配置”。这不是实际 apply 的普遍语义。作业更新会把 queue、worker group、schedule expr、retry、timeout、shard、execution mode 等由空单元格解析出的 null 直接写入 `updateJobDefinitionMaintenance`；文件模板和通道 upsert 也会将 excluded 的 null 覆盖到原字段。日历/窗口/队列 upsert 同样按输入值覆盖，不是字段级 patch。
- Pipeline apply 对已存在的 Pipeline 会先删除其全部 step，再按本次工作簿重建；同一包若漏掉原有 step，会产生实际删除效果。业务日历 apply 会先删除该日历全部 holiday 再按当前单元格重建。Workflow 节点和边则是 upsert，不自动清理缺项。各类对象的“行缺省”和“字段留空”目前语义不一致。
- 因此当前配置包更接近“按已提交对象做不一致的整行覆盖/局部集合替换”，而非方案目标中的字段级 MERGE。该差异有数据丢失风险，不能只通过精简模板隐藏字段解决。阶段 1 前必须为每个对象规定：省略 Sheet、缺省行、空单元格、显式清空、集合替换分别代表什么；预览需展示清除/替换项。若保留覆盖语义，指南必须如实标出，不得继续显示“不覆盖”。
- 作业头部规则另有结构负担：`job_definition` 行级核心必填仅 job_code/name/type/schedule_type，但解析器仍强制除 depends_on_job_code 外的全部 23 个表头存在。`job_monitoring_policy` 是必须 Sheet，虽仅 job_code 表头强制，但 apply 会为提交的策略行把空数值归零、空 severity 归为 WARN；它不是“省略字段即保留既有策略”的 PATCH。
- 文件模板解析器可从空值推导部分默认，例如 version=1、enabled=true、streaming=true、page/fetch=1000、chunk=500、checksum/compress/encrypt=NONE；但 Excel 指南中“建议值”并不都等价于解析器默认，且即便解析器有默认，重新导入时仍可能覆盖已有值。默认是否安全必须分别标记“新建默认”“更新时保留”“更新时清除/重置”，不能用单一“留空行为”描述。
- 通道 `config_json` 被定义为包含 endpoint/auth/credentials 的 JSON；行校验只验证 JSON 结构可解析，没有在配置包字段层强制凭据只能是 Secret 引用。配置包导出直接读取 `file_channel_config.config_json`，未发现导出前脱敏；样例使用 `passwordRef` 不能证明存量值或任意导入值都一定是引用。因此不得宣称配置包无敏感信息。后续必须限定为 Secret 引用并拒绝明文凭据，或由权限/导出策略明确排除敏感内容；还需核对独立通道 API 和 worker 消费路径保持一致。
- 当前针对配置包的测试主要覆盖 schema、校验、指南和监控策略 apply；未发现覆盖“对已存在作业/模板/通道上传空可选字段后的实际 DB 结果”、Pipeline steps/calendar holidays 缺项语义、包导出敏感字段脱敏的集成测试。上述是阶段 1 必须新增的契约回归用例，不可仅靠指南断言。

## 已确认的产品约束

根据已确认的用户画像和方向，本计划按以下约束推进：

1. 用户是有批量作业、账期和运维知识的专业操作人员，不按“小白模式”隐藏状态机、影响范围和数据副作用。
2. 系统应代办安全、可推断、重复的选择；场景能力不得因减少字段而被删减。常见路径简单，专家能力仍可发现、批量使用并可审计。
3. 租户不负责队列容量、配额、物理 Worker 路由、资源标签和并发/QPS；这些由平台负责。所有 REST、复制、初始化和 Excel 写入口都必须同时收口。
4. 用户可按所需场景生成单场景或多场景组合工作簿；场景无关的 Sheet/列不应默认强迫用户面对。完整业务快照仍可用于受控迁移和恢复，但不能与快速模板混为一谈。

## 阶段 0 尚未关闭的强制事项

以下事项需要业务/平台负责人明确确认；在决议落档前，不实施相关权限或执行行为变更：

| 决议 | 当前证据/缺口 | 建议供确认的默认方向 |
|---|---|---|
| 业务 MERGE 与 RESTORE 授权、审批和删除范围 | 当前 apply 仅 ADMIN，业务 upsert 与平台资源混在一事务流程；缺少显式导入意图 | 租户配置负责人可对本租户做 MERGE；RESTORE 单独权限、强预览、审计并按现有审批策略二次审批；平台资源恢复仅 ADMIN |
| 包类型及旧格式识别 | 当前 API 无明确 package kind；新解析要求监控 Sheet，旧 11-sheet 包会被拒绝；尚无已批准兼容窗口 | 请求明确携带 MERGE/RESTORE 意图；旧 11-sheet 仅在可无歧义识别且通过迁移预览时兼容，否则给可操作拒绝原因；禁止猜测 |
| 稳定业务键和旧包 ID | 当前 schema 不暴露 DB 主键，但其他复制/初始化路径需一起核查 | 租户键用 tenant-scoped 稳定业务码；目标环境主键由服务端解析/生成；跨环境引用必须预览映射 |
| 实例配置引用、Secret 轮换/撤销 | 只确认了部分作业参数快照 | queued/running 实例绑定不可变作业/模板/路由/SLA 版本；Secret 只保存引用和必要版本，撤销影响范围和紧急阻断规则需明定 |
| 队列/路由规模、公平性与租户隔离 | 当前队列 tenant-scoped，容量和 Worker 字段由写入口传入；没有目标租户数及压力证据 | 保持租户逻辑隔离；优先由平台为租户 provision 路由，物理共享须以多租户并发、容量和隔离实测批准；不能机械地每租户每场景创建队列 |
| SLA 时间语义 | schema 有 deadline、day offset、依赖完成窗；其适用条件分散在指南/validator/runtime | SLA 可选、只告警不改变执行状态；业务日/作业时区、上游 EFFECTIVE、DST 与 retry 锚点逐场景定义，平台运行阈值不进租户包 |
| 旧包停止接收与灰度/回滚 | 无负责人、时间表、灰度租户和回滚阈值记录 | 旧格式先读兼容后收写；分租户灰度，作业实例不得被隐式重路由；发布前完成差异盘点、恢复与对账演练 |

## 下一步

继续阶段 0，而不是开始删除列或改路由：

1. 生成逐场景字段矩阵：每列标注业务/平台所有者、适用 Worker/文件格式、必填/条件必填、默认事实源、敏感级别、租户读写权限、当前校验与运行时消费方；把新建默认与更新留空语义分开。
2. 已追踪到模板、Pipeline、通道和监控策略存在运行时读取当前配置的路径；继续补齐 claim/partition/重试/补偿路径，明确哪些路径会再次解析配置、配置变更在哪个时间点生效，并形成可复现测试缺口。
3. 追踪 Trigger/SLA 时间锚点及告警 claim/outbox 失败窗口；为业务日、依赖晚到、跨日和 retry 准备样例。
4. 完成队列规模和公平性分析所需的目标租户数/场景基数假设，并设计真实 PG 的 claim/隔离压测；未有真实负载数据时清楚标明估算。
5. 将上表决议由负责人确认后记录决策人和日期，再进入 Excel/API 契约和权限切片。

## 本轮验证边界

本轮为源码、配置、API 权限和前端调用点的静态事实盘点；未运行单测、IT、前后端 E2E、真实 PG/Kafka/Worker 验证，也未宣称改造已完成。报告与权威计划分离维护，阶段 0 的结论不能被当作运行行为验收。
