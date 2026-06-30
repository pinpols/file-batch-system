# Console API Protocol

This document is the human-readable contract for the console frontend.
When the API surface changes, update this file and [console-api.openapi.yaml](./console-api.openapi.yaml) together.

## Changelog

| 日期       | 变更摘要                                                                                                                                      |
|------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| 2026-06-30 | **BFS lineage file_record archive mirror**:`GET /api/console/lineage/result-versions/{id}` 的 path/响应外形不变，但文件证据在 `batch.file_record` 热表未命中时会回退查询 `archive.file_record_archive`。`coverage.sources.fileRecords` 可为 `ARCHIVE`，`knownGaps` 改为声明 hot/archive 均未命中；范围仅限 BFS 文件证据冷表，不处理对象存储生命周期、字段级 lineage 或记录级 lineage。 |
| 2026-06-30 | **BFS lineage archive 冷表补证**:`GET /api/console/lineage/result-versions/{id}` 的 path/响应外形不变，但 orchestrator 证据链在热表未命中时会回退查询 `archive.result_version_archive`、`archive.job_instance_archive`、`archive.pipeline_instance_archive`、`archive.file_dispatch_record_archive`。`coverage.scope` 可能从 `BFS_HOT_TABLES` 升为 `BFS_HOT_AND_ARCHIVE`，并新增 `coverage.sources` 标明 `resultVersion/jobInstance/pipelineInstances/fileRecords/dispatchRecords` 来自 `HOT/ARCHIVE/NONE`。 |
| 2026-06-30 | **P2 容量画像 / cost profile**:新增只读 `GET /api/console/capacity-profile?tenantId&from&to&groupBy&limit`，Console 转发 orchestrator `GET /internal/orchestrator/capacity-profile`。`groupBy` 支持 `TENANT/JOB/WORKER`，返回 `rows/totals/coverage`，指标来自 `job_instance/job_task/file_record/pipeline_instance/pipeline_progress` 热表聚合，覆盖耗时、任务数、成功/失败数、关联文件大小、processedRecords、records/s、MB/s。接口明确 `scope=BFS_HOT_TABLES`，`coverage.knownGaps` 标注历史任务无 checkpoint、DB CPU/IO/WAL 仍以 benchmark/数据库监控为准；`coverage.rejectedScopes` 明确不做云账单分摊、跨平台 FinOps、业务金额成本裁定。 |
| 2026-06-30 | **批次日重放影响预览(P1-1)**:新增只读 `POST /api/console/ops/batch-day-replay/sessions/preview`，请求体复用 `BatchDayReplaySubmitRequest`，由 Console 转发 orchestrator preview；返回 `scope/totalCount/entries/resultVersionImpacts/warnings`，用于提交前确认会重跑哪些 job、会创建新 result_version 还是 promote 既有版本。preview 不创建 `batch_day_replay_session/entry`、不触发审批。同步修正 `batch_day_replay_entry` 唯一键:从旧 `(session_id,tenant_id,job_code)` 改为 source/result 级 partial unique，避免同 session 同 jobCode 多来源 entry 被 `ON CONFLICT` 吞掉，保证 preview 与实际执行影响范围一致。 |
| 2026-06-30 | **资产分区 readiness drill-down Console BFF(P0-1 后续)**:新增只读 `GET /api/console/asset-partitions/readiness?tenantId&jobCode&bizDate`，转发 orchestrator `GET /internal/readiness/job`，返回统一 Console envelope，`data` 包含 `ready/reason` 以及命中的 EFFECTIVE `assetCode/bizDate/partitionKey/businessKey/freshnessStatus/versionNo/jobInstanceId/payloadStorage/payloadRef`。Console 只做租户边界校验与响应包装，依赖裁决和最新版本语义仍由 orchestrator readiness/asset_partition 读模型负责，避免前端自行拼 result_version。 |
| 2026-06-30 | **BFS lineage 最小证据链(P1-4)**:新增只读 `GET /api/console/lineage/result-versions/{id}?tenantId=...` 与 `GET /api/console/lineage/effective?tenantId=...&businessKey=...`，由 Console 转发 orchestrator lineage 内部端点。返回 `resultVersion/jobInstance/pipelineInstances/fileRecords/dispatchRecords/coverage`，其中 `coverage.scope=BFS_HOT_TABLES`，并通过 `knownGaps` 明确热表证据缺失。范围限定为 BFS 管辖热表 forensic 查询，不做字段级/记录级 lineage、外部 catalog 或 OpenLineage facets 扩展。 |
| 2026-06-30 | **实例级 stuck diagnosis(P0-4 第一刀)**:`GET /api/console/ops/cluster-diagnostic/instances/{id}?tenantId=...` 新增只读诊断入口，返回 `healthy/instance/summary/findings[]`。finding 采用 `severity + reasonCode + message + suggestedActions[] + evidence` 结构，首批覆盖活跃实例无 partition/task、终态实例仍有活跃子节点、workerGroup 无 ONLINE worker、活跃任务 worker/心跳异常、实例相关 outbox 事件未终结。目标是运维不用 SSH 查库即可判断卡住原因；接口不做修复动作，恢复仍走既有 retry/cancel/outbox republish 等受控 API。 |
| 2026-06-30 | **调度快照补队列积压字段(P0-1 第二刀)**:`GET /api/console/scheduler/snapshot` 的 `queues[]` 加 `maxRunningPartitions/effectiveMaxRunningPartitions`、分区状态计数(`created/waiting/ready/running/retrying/queued/activePartitions`)、`oldestWaitingSeconds`、`tenantWaitingSharePermille`、`partitionSaturationPermille`、`bottleneckReason`。后端统计 queueCode 时优先读 `job_partition.input_snapshot.queueCode`，缺失再回退 `job_instance.queue_code`，避免 workflow 子任务被归到 workflow 自身队列。新增字段均为响应加法，旧前端兼容；前端重生成类型后可直接展示队列深度、等待年龄和瓶颈原因。 |
| 2026-06-29 | **文件模板映射草案 + plugin ref 透传修复**:`POST /api/console/file-templates/mapping-draft` 新增向导式草案接口,输入 `direction/schemaName/tableName/tenantColumn/conflictColumns/fields[]`,返回可直接带入 `FileTemplateCreate/UpdateRequest` 的 `fieldMappingsJson/queryParamSchemaJson/defaultQuerySql/warnings`。IMPORT 生成 `query_param_schema.jdbcMappedImport`,EXPORT 生成 `query_param_schema.sqlTemplateExport + export_data_ref=sql_template_export`。同时补齐 `FileTemplateCreateRequest` 的 `loadTargetRef/exportDataRef`,并修复 create/update service + MyBatis upsert 对 `file_template_config.load_target_ref/export_data_ref` 的真实透传;此前 update DTO/OpenAPI 已暴露字段但落库丢失。 |
| 2026-06-28 | **依赖感知 scheduled fire 配置面贯通**:`JobDefinitionCreateRequest`/`JobDefinitionUpdateRequest`/`TenantConfigBatchInitRequest.JobDefinitionSpec`/`ConsoleJobDefinitionResponse` 新增 `dependsOnJobCode`;tenant-package Excel `job_definition` sheet 新增可选列 `depends_on_job_code`(老模板缺列仍兼容,新列会做 job 引用校验);trigger Quartz `JobDataMap` 透传该字段以支持重启恢复;`dependsOnJobCode` 校验长度对齐既有列宽 `batch.job_definition.depends_on_job_code VARCHAR(64)`(V177,见 #594),`@Size`/`@Pattern`/OpenAPI `maxLength` 统一为 64;OpenAPI 与 batch-console 生成类型同步更新。 |
| 2026-06-24 | **登录防暴力破解 + 可插拔验证码(总开关默认 off)**:在既有 IP 限流之外补两层——账号/IP 维度失败退避 + risk-based 验证码,**不锁账号**(规避 account-lockout DoS,达阈值只升级为"该次登录要求验证码")。① `ConsoleLoginRequest` 新增**加法字段** `captchaToken`(可空,非机密,达阈值后才需带;加密登录路径下从外层请求透传)。② 新增 2 个公开端点(`security: []`,登录前可达):`GET /api/console/captcha/config`(下发 `{provider, siteKey, loginProtectionEnabled}`,绝不下发 secret)、`GET /api/console/captcha/challenge`(仅 self-hosted provider 签发一次性滑块挑战 `{challengeId, gap}`,其它 provider 返 404)。③ 验证码做成 `CaptchaVerifier` SPI(`@ConditionalOnProperty` 选装),v1 实装 `none`(默认,等效旁路)+ `selfhosted`(自建滑块:单次有效 + 服务端自算时序 + 位置容差);tencent/aliyun 为冻结的"加一个实现类"扩展点。④ 达阈值未过验证码 → HTTP 401 + `code=CAPTCHA_REQUIRED`(FE 据此弹验证码组件)。配置:`batch.console.login-protection.{enabled=false,fail-threshold=5,fail-window-minutes=15,backoff-step-millis=200,backoff-cap-millis=2000}` + `batch.console.captcha.{provider=none,...}`。**默认全关,对现有部署零影响**。设计见 `docs/design/console-login-bruteforce-protection.md`。新增 `ResultCode.CAPTCHA_REQUIRED`;单测 `LoginProtectionServiceTest`/`SelfHostedSliderVerifierTest`。 |
| 2026-06-22 | **租户配置包 Excel 预览出错行内联编辑**:① preview 响应(`TenantConfigPackageExcelPreviewResponse`)新增 `errorRows`(加法字段)——按 (sheet, rowNo) 聚合的出错行整行单元格值 + 该行所有问题,供前端在预览页内联编辑该行(免去“下 Excel→改→重传”)。② 新增 `POST /api/console/config/tenant-package/excel/preview/{uploadToken}/patch`(`TenantConfigPackageExcelPatchRequest{sheetName,rowNo,values}`):把改动的单元格合并进上传会话对应行(`rowNo-2`=列表下标,仅合并该行已有列键、未知键忽略、value 走与解析期一致的 trim),重校验后返回新预览。**不落库**——仍走原 apply 闸门(`invalid>0` 拒绝),会话仍按 token 持有(30min TTL)。权限 `ROLE_ADMIN\|ROLE_TENANT_ADMIN`(与 preview 一致)。openapi 新增 `TenantConfigPackageErrorRowDto`/`TenantConfigPackageExcelPatchRequest` schema;新增 `ConsoleTenantConfigPackageExcelPatchIntegrationTest`。 |
| 2026-06-21 | **后端术语专业化二次清理(无 API 语义变更)**:继续统一后端文档、脚本、代码注释与 OpenAPI 描述中的非正式术语,仅调整说明文字;不改 path/schema/DTO/校验逻辑。Controller 变更仅为注释文字调整,接口行为不变。 |
| 2026-06-21 | **后端注释/文档/脚本文案专业化清理(无 API 语义变更)**:仅清理 Controller 注释、OpenAPI 描述、运行手册、脚本注释与代码注释中的口语化、对话式表达,不改 path/schema/DTO/校验逻辑。OpenAPI 文件仅同步描述用语,接口行为不变。 |
| 2026-06-21 | **alerts 列表前后端筛选契约对齐(前端 P5 服务端分页前置 batch-3)**:`GET /api/console/queries/alerts` 修正 OpenAPI 的「双向漂移」并补真实时间过滤——原 yaml 文档化了 `acknowledged`/`startDate`/`endDate`(后端 DTO 根本没有、静默忽略),却漏写了后端真实支持的 `severity`/`status`/`alertType`(前端因此被迫端上局部过滤,只对当前页生效)。本次:① **后端新增** `startDate`/`endDate`(ISO date 或 datetime,按 `last_seen_at` 范围过滤,命中既有 `(tenant_id,last_seen_at DESC)` 索引);② OpenAPI 改为真实入参集合 `severity/status/alertType/traceId/startDate/endDate/pageNo/pageSize`,**移除** `acknowledged`(后端从不读;前端改为直接传 `status` 的 OPEN/ACKED/SUPPRESSED/CLOSED 真值,不再做 OPEN/非OPEN 二档 lossy 映射)。链路:`AlertEventQueryRequest`→`AlertEventQuery`(record 加 `fromTime`/`toTime`)→`ConsoleOpsQueryService.alertEvents`→`AlertEventMapper.xml`(select+count 同步)。`severity`/`status`/`alertType`/`traceId` 后端早已支持(本次只补文档可见性 + 时间范围)。`AlertEventIntegrationTest` 增 last_seen_at 时间范围用例。**前端配套**(batch-console):`alertsQuery.ts`/`AlertList.vue` 去掉 `acknowledged` 映射与 `applyLocalFilter`,severity/alertType/status/traceId/时间范围全部传后端 + ProTable 服务端分页。 |
| 2026-06-21 | **补录 6 组保存接口的 requestBody schema(消 FE 手写 DTO 漂移)**:`POST/PUT` 的 queues / batch-windows / calendars / quota-policies / file-templates / file-channels 共 12 个端点,原 yaml requestBody 是匿名 `type: object`,前端因此只能手写 payload 类型、存漂移风险。本次按既有 Java DTO(`ResourceQueueCreate/UpdateRequest`、`BatchWindowCreate/UpdateRequest`、`CalendarSaveRequest`、`QuotaPolicySaveRequest`、`FileTemplateCreate/UpdateRequest`、`FileChannelCreate/UpdateRequest`)补齐 10 个 named schema 并把 requestBody 接到 `$ref`。**纯文档补录**:controller/DTO/校验逻辑一字未改,只是把一直存在的入参契约写进 OpenAPI,使前端可 `gen:api` 直接复用、消除手写漂移。沿用本仓既有惯例(同 `JobDefinitionCreateRequest`)不声明 `required`(运行期由 Bean Validation `@NotBlank`/`@ValidResourceCode` 强制)。`check-console-openapi-paths.py` 通过(356 routes)。FE 跟进 PR 做 `gen:api` + 替换 `governance.ts`/`system.ts` 手写类型。 |
| 2026-06-21 | **catch-up 待审列表补服务端筛选参数(前端 P5 服务端分页前置)**:`GET /api/console/queries/catch-up-approvals` 新增两个可选 query 参数:① `bizDate`(ISO yyyy-MM-dd,精确匹配 `trigger_request.biz_date`,`@Pattern` 校验,mapper `cast(#{bizDate} as date)`);② `keyword`(大小写不敏感 `ilike` 模糊匹配 `request_id`/`job_code`/`trace_id` 任一,≤128)。两者覆盖前端 `CatchUpApprovalsTab`/`MCatchUp` 唯二的跨页筛选维度(status 维度后端恒为 `ACCEPTED`,前端 enum 退化为 no-op,无需后端参数)。同时**补录 OpenAPI 既有漂移**:该端点原 yaml 漏写 `jobCode`/`requestId`/`cursor`(DTO 一直支持)且重复列了 PageNo/PageSize,本次一并补齐为真实入参集合。纯加法、向后兼容,不动响应 schema。动机:前端此前端上全量拉取 + 端上筛选(`fetchAllPageItems` 4000 截断隐患),迁服务端分页需后端先支持业务筛选参数,否则筛选会退化成「仅当前页生效」。详见 `docs/backlog/client-side-aggregation-pagination.md`。 |
| 2026-06-21 | **approvals 待审列表补服务端筛选参数 keyword + requesterId(前端 P5 服务端分页前置 batch-2)**:`GET /api/console/queries/approvals` 新增两个可选 query 参数:① `requesterId`(精确匹配 `approval_command.requester_id`,前端 `?requester=me` 入口);② `keyword`(大小写不敏感 `ilike` 模糊匹配 `approval_no`/`requester_id`/`target_type`/`target_id` 任一,≤128)。这两个是前端 `GeneralApprovalsTab` 仅有的、后端原本不支持的跨页筛选维度(status→approvalStatus / type→approvalType 后端早已支持)。同时**补录 OpenAPI 既有漂移**:该端点原 yaml 漏写 `approvalNo`/`approvalType`/`actionType`/`approvalStatus`(DTO/mapper 一直支持)且重复列了 PageNo/PageSize,本次补齐为真实入参集合。链路:`ApprovalCommandQueryRequest`→`ApprovalCommandQuery`→`ConsoleOpsQueryService.approvals`→`ApprovalCommandMapper.xml`(select+count 同步)。纯加法、向后兼容。新增 `ApprovalCommandQueryIntegrationTest` 的 requesterId / keyword 跨列用例。**注**:`/queries/audits` 经核后端 service+mapper+OpenAPI **已完整支持**前端全部筛选维度(operationType/result/operatorId/fileId/traceId/时间范围),无需后端改动,前端可直接迁;`/queries/alerts` 存在前后端契约错位(前端发 `acknowledged`/`startDate`/`endDate`,后端 DTO 实为 `status`/无时间参,且 traceId 后端精确、前端子串),需单独契约对齐,本批不动。详见 `docs/backlog/client-side-aggregation-pagination.md`。 |
| 2026-06-20 | **实例/工作流可逆暂停恢复(ADR-044 Phase B-2)**:新增 `POST /api/console/instances/{id}/{pause,resume}` 与 `POST /api/console/workflow-runs/{id}/{pause,resume}`(ROLE_ADMIN + `@AuditAction` + 类级 `@Idempotent`)。可逆暂停:job_instance 暂停停发新分区、workflow_run 暂停停推下游 DAG 节点,在途自然终结,resume 回 RUNNING;区别于破坏性 cancel/terminate。经既有 `ConsoleOrchestratorProxyService` 通用 action 转发到 orchestrator `/internal/{instances,workflow-runs}/{id}/{pause,resume}`(proxy 无改动,action 即路径段)。openapi 新增 4 path,复用 `CommonResponseString`。 |
| 2026-06-17 | **SDK 运行时可见性 2 件(纯后端,FE 门户页待独立 batch-console 前端仓接入)**:① **worker 协议兼容告警**:`WorkerFingerprintResponse`(`GET /api/console/workers/fingerprints`)新增只读 `compatibility` 对象 `{status: OK\|SDK_OUTDATED\|PROTOCOL_UNSUPPORTED\|UNKNOWN, reasonCode, reportedSdkVersion, platformSdkMajor}`,由后端 `WorkerCompatibilityEvaluator` 按 worker 上报的 `sdkVersion` 对照平台当前支持 SDK 主版本算出(`worker_registry` 只存 sdkVersion、无 per-worker 协议 schemaVersion,故纯 sdkVersion-major 判定;解析不出 → UNKNOWN,不瞎判)。向后兼容新增字段,不动 worker 注册/心跳写路径。② **SDK catalog 后端端点**:新增只读 `GET /api/console/sdk/catalog`,返回开发者门户数据源 `{protocolVersion(supportedMajors/current/rejectedFrom,源 SUPPORTED_MAJOR_VERSIONS), languages[](lang/artifact/latestVersion/installSnippet/conformanceStatus,版本取自各 SDK package.json/pyproject/Cargo.toml/pom——未发布标 repo-version), sharedConstants(镜像 sdk-shared-constants.yaml), docs[]}`。新增 `SdkPlatformConstants` 镜像 + `SdkPlatformConstantsParityTest` 对照 `docs/api/sdk-shared-constants.yaml` 防漂移;`ConsoleSdkCatalogServiceTest` 对照真实包元数据。openapi 新增 `WorkerCompatibility`/`SdkCatalog` schema。 |
| 2026-06-16 | **配置引导 3 项(让新人看得懂怎么填)**:① **字段说明示例填实**:`ConfigPackageExcelWorkbookWriter` 把最难字段(`query_param_schema`/`config_json`/import 与 export 两套 `field_mappings`/`default_query_sql`/`step_params`)的示例从空 `{}` 换成提取自 e2e fixture 的真实非空结构(import 用 `jdbcMappedImport` schema/table/columnMappings/conflictColumns/systemBindings、source/targetColumn/type/required/format;export 用 sourceColumn/header/format、命名参数 SELECT)。② **补设计承诺 sheet/列**:配置模板新增 `四类Worker示例` sheet(IMPORT/EXPORT/PROCESS/DISPATCH 各一份整行范例)+ `依赖说明` sheet(job→template/channel/queue 引用 + DB fallback 规则)+ 字段说明新增「填写示例」列(完整可抄片段);均只读不参与解析。③ **readiness 报怎么补**:`GET /api/console/tenants/{tenantId}/readiness` 的 `ReadinessItem` 加 `hint`(怎么填:在哪个 sheet 填哪个字段)+ `docRef`(指向 `docs/runbook/first-tenant-config-quickstart.md` / 字段说明),向后兼容新增字段。openapi 新增 `ReadinessItem`/`TenantReadinessResult` 文档参考型 schema 描述 `data` 形态;readiness 200 响应**仍保留 `CommonResponseObject` 包装**(`data` type=object 不收窄),以免改 wrapper 触发 oasdiff `response-property-type-changed` 破坏性变更,只在 `hint`/`docRef` 上做加法式扩展。 |
| 2026-06-16 | **租户/用户初始化 3 项优化**:① **首登强制改密 + 默认密码部署告警**:`console_user_account` 加 `must_change_password`(V174;内置账号 admin/auditor/config-admin 置 true)。登录响应新增 `mustChangePassword` 标记;新增 `POST /api/console/auth/change-password`(本人改密,清标志+踢会话);管理员 `reset-password` 置 must_change=true;改密守护过滤器拦截 must_change 账号的写操作(白名单除外)直到改密;`ConsoleDefaultPasswordGuard` 启动检测内置账号是否仍是出厂 `admin123`——prod fail-fast / 非 prod WARN。② **租户就绪自检**:新增只读 `GET /api/console/tenants/{tenantId}/readiness`,扫 template/channel/queue/job 配置闭环返回 `{ready, blocking[], warnings[]}`(enabled 模板关键字段空、渠道凭据缺、queue_code 悬空=blocking);ADR-026 dry-run 边界内(看配置完整性 √,不看业务结果 ✗)。③ **一键 provision**:`POST /api/console/tenants` 加可选 `initConfigFrom`/`initMode`(默认不复制=向后兼容),建租户→复制默认配置→就绪自检一次闭环,响应从 tenant 改为 `{tenant, configInit?, readiness}`(superset)。`ConsoleAuthTokenResponse` schema 加 `mustChangePassword`。 |
| 2026-06-15 | **通知端点权限收紧 + 通知投递接通(P1 审计修复)**:`/api/console/notifications/channels` 与 `/rules` 的 create/update/delete/test 端点权限从 `ROLE_ADMIN\|ROLE_TENANT_ADMIN\|ROLE_TENANT_USER` 收紧为 **`ROLE_ADMIN\|ROLE_TENANT_ADMIN`**(只读角色 `ROLE_TENANT_USER` 不应改通知配置);读端点(list/get)不变保留普通用户。无 path/schema 增删。配套修复:① `updateRule` 补 channel 存在性校验(原只 create 校验,update 可写入失效 channelCode 致规则永不命中);② 通用通知中心(`notification_channel`/`subscription_rule`)此前有 CRUD+前端但投递链只读旧 `webhook_subscription`、配的规则永不生效——新增 `SubscriptionRuleWebhookDispatcher` 让 `channel_type=WEBHOOK` 的规则经 `selectEnabledByEventType` join `notification_channel` 真正参与投递(复用 WebhookDispatcher 投递/HMAC/重试),非 WEBHOOK 类型显式 log 跳过不静默,与旧 webhook_subscription 两路并存。**注**:`notification_channel.config_json` 的 webhook key(url/secret)契约从未正式定义(旧 webhook 用 `callbackUrl`),实际 key 名需对前端。 |
| 2026-06-14 | **新增 biz 分片目录 shard-catalog(P2 tenant-routing 拓扑登记)**:3 端点 `GET/PUT /api/console/ops/shard-catalog` + `DELETE /api/console/ops/shard-catalog/{placementKey}`。平台 ROLE_ADMIN 登记「有哪些片 + 各片位置(host/port/db)+ 状态」,作为前端「分片列表」视图 + placement 指派 key 白名单的权威源(取代依赖 worker 配置 routing.shards)。写 `batch.business_shard_catalog`(V171,platform 库),**只存位置不存账密**(secretRef 仅凭据引用名,凭据走 secrets)。生效边界:本表是「登记/可见/校验」层,worker 实际连接池仍由其 routing.shards 配置 + secrets 在启动时构建,改本表不动运行中 worker 的池(需重启重建)。upsert/delete 带 `@AuditAction`。placement 指派校验改为优先查 catalog(enabled 片)→ 回退 routing.shards 配置。新增单测 `ConsoleBusinessShardCatalogController/ServiceTest`。 |
| 2026-06-14 | **新增 biz 租户分片 placement 管理(P2 tenant-routing)**:3 端点 `GET/PUT /api/console/ops/tenant-placements` + `DELETE /api/console/ops/tenant-placements/{tenantId}`。平台 ROLE_ADMIN 跨租维护「哪个租户在哪片」:list 全量映射、upsert 指派/迁片、delete 取消(回退 hash)。写 `batch.business_tenant_placement`(V170,platform 库),**只存 tenant→placement key,不涉账密**(凭据走 secrets/biz-shards)。`placementKey` 受 `^[a-z0-9-]+$` 约束防 typo;worker 侧 `DbTablePlacementResolver` 按 TTL 缓存读,迁片最迟一个 TTL 生效。复用 batch-common `BusinessTenantPlacementEntity`;mapper 全局视图无 tenant 过滤(本表即全局映射,非 `<if tenantId>` 守护)。runbook `docs/runbook/biz-tenant-routing.md`。新增单测 `ConsoleBusinessTenantPlacementControllerTest` / `ConsoleBusinessTenantPlacementServiceTest`。 |
| 2026-06-08 | **补齐本地文件系统后端上传闭环**：`POST /api/console/files/presign-upload` 从“存储直传签名”收敛为兼容命名的“应用托管上传会话”，返回 `fileId/uploadUrl/uploadMethod/contentField/storageBucket/storagePath`；新增 `PUT /api/console/files/{fileId}/content?tenantId=` multipart `file` 写入 `BatchObjectStore`，S3 与 filesystem 共用同一逻辑对象路径，不暴露真实磁盘路径；`POST /api/console/files/{fileId}/confirm-arrival` 在 orchestrator 内部先校验对象存在并回写实际 `file_size_bytes`，避免只确认 DB、不确认文件内容。 |
| 2026-06-07 | **⚠️ Breaking — dry-run L3 探测 backend-neutral 正名**(`POST /api/console/dry-run`):无 path / schema 增删,但 `params` 探测 key 与返回 finding code 改名。① 入参 key `minioBucket` → **`s3Bucket`**(OpenAPI `DryRunRequest.params` 描述同步);② finding code `EXEC_MINIO_BUCKET_INVALID` / `EXEC_MINIO_CLIENT_UNAVAILABLE` / `EXEC_MINIO_BUCKET_OK` / `EXEC_MINIO_BUCKET_MISSING` / `EXEC_MINIO_PROBE_FAILED` → **`EXEC_S3_BUCKET_INVALID` / `EXEC_S3_CLIENT_UNAVAILABLE` / `EXEC_S3_BUCKET_OK` / `EXEC_S3_BUCKET_MISSING` / `EXEC_S3_PROBE_FAILED`**;③ summary key `l3MinioProbed` → **`l3S3Probed`**。动机:对象存储客户端已迁移 AWS SDK v2(#401)、配置面已 `objectStorage.*`(#402),探测契约同步做 backend-neutral(MinIO / AWS S3 / OSS / COS 通用)。**调用方影响**:旧调用方传 `minioBucket` 将不再触发 bucket 探测(静默回退到默认 bucket),按 finding code 分支的 FE 需同步改 `EXEC_S3_*`。**服务端 MinIO 实现不变**(镜像 / compose 服务名保持),仅命名层正名。 |
| 2026-06-06 | **新增 `GET /api/console/files/fs-download?b&k&e&s`**(对象存储抽象 Phase 2 阶段二):FilesystemObjectStore 后端专用的令牌验证下载端点。S3 后端走存储原生 presign 直发,FS 后端无此能力 → 应用签 HMAC-SHA256 令牌(`<bucket>|<key>|<exp>`,`Base64URL` sig)+ 经此端点代下。**匿名访问 — HMAC 令牌即授权**(常时间 `MessageDigest.isEqual` 比较 + exp 检查),`ConsoleSecurityConfiguration` 加入匿名白名单。`@ConditionalOnProperty(name="batch.storage.backend", havingValue="filesystem")` —— S3 模式不装载,完全无回归。Controller 通过 `BatchObjectStore.get` 取流(加密装饰层透明生效),`StreamingResponseBody` 流式返回。失败:bad/expired token → 401;object not found → 404;key 含 `..` → 400(`FilesystemObjectStore` traversal 校验)。**范围边界**:仅 FS 模式生效,与 S3 presign 互补不冲突;现有 `presign-download`(S3 真签名)端点未变。新增单测 `FilesystemPresignDownloadControllerTest`(有效令牌/篡改/过期/traversal 四 case) |
| 2026-06-04 | **workflow-dag-designer Polish — 版本历史闭环升级(PR #370 降级 → V167 历史表真实接入)**:把 PR #370 的"降级返当前 1 条 / detail 仅支持 current"路径替换为真实多版本:V167 新建 `batch.workflow_definition_version`(`id` BIGSERIAL / `tenant_id` / `workflow_definition_id` / `workflow_code` / `version` / `workflow_name` / `workflow_type` / `enabled` / `nodes_json` JSONB / `edges_json` JSONB / `saved_by` / `saved_at` / `summary`,UNIQUE `(tenant_id, workflow_definition_id, version)` + idx `(workflow_definition_id, version DESC)`,archive 镜像 + ArchiveSchemaDriftCheck.ARCHIVED_TABLES 登记)。`DefaultConsoleWorkflowDefinitionApplicationService.fullUpdate` 在 `updateAndBumpVersion` 成功后同事务追加快照(`ObjectMapper.writeValueAsString` 序列化 entity list),`saved_by` 取 SecurityContext.username,`summary` 暂留 null(FE 未提交字段)。`listVersions` 走 `WorkflowDefinitionVersionMapper.listByDefinitionId`(version desc + 最新 current),历史表无数据时降级单条 current(兼容 PR #370);`getVersion` 当前 version 走主表,历史 version 走 `findByDefinitionIdAndVersion` + JSONB 反序列化,不存在 → NOT_FOUND。Controller `RBAC` 同步扩展到 `ROLE_ADMIN | ROLE_AUDITOR | ROLE_TENANT_ADMIN | ROLE_TENANT_USER`(read-only)。i18n 新增 `error.workflow.version.not_found` + `error.workflow.version_snapshot.{serialize,deserialize}_failed`(en + zh_CN 1:1)。FE PR #60 版本 diff 页从「当前 vs 空」降级恢复为真实多版本 diff。**范围边界**:不做物理回滚(设计 §2 明确)、不做版本 cleanup / TTL(后续 ArchivePolicy 走)、不暴露 summary 编辑入口(FE follow-up)、不动 FE。新增单测 `DefaultConsoleWorkflowDefinitionApplicationServiceVersionTest`(5 case:fullUpdate 后历史表 1 行 / 多次 fullUpdate N 行 / list 全量返回 current flag / list 降级单条 / detail JSONB 反序列化)|
| 2026-06-04 | **workflow-dag-designer Polish 版本下拉(BE 部分,batch-console PR #60 真实接入)**:新增 2 端点 — ① `GET /api/console/workflow-definitions/{id}/versions?tenantId=` 列出版本(返 `[{version, savedBy, savedAt, summary, current}]`);② `GET /api/console/workflow-definitions/{id}/versions/{version}?tenantId=` 取指定版本完整 definition(复用 `WorkflowDefinitionDetailResponse`)。RBAC:`ROLE_ADMIN | ROLE_TENANT_ADMIN`;租户作用域走 `ConsoleTenantGuard.resolveTenant`。**降级实现**:平台尚无 `workflow_definition_version` 历史归档表(`db/migration/` 全扫确认无),list 仅返回单条"当前版本"摘要,`savedAt = workflow_definition.updated_at`、`savedBy / summary = null`、`current = true`;detail 仅支持 `version == current`,其他版本号一律 NOT_FOUND + i18n `error.workflow_version.not_found`。FE 此前 fallback 「to=current vs from=空」,本 PR 提供真实端点后 FE 可直接消费;follow-up 引入历史归档表后接口语义不变,只是返回更长 list。**范围边界**:不做物理回滚(设计 §2 明确不做)、不做版本 diff 算法(在 FE 做)、不强行加 migration。i18n key `error.workflow_version.not_found` en + zh_CN 1:1。新增单测 `ConsoleWorkflowVersionsControllerTest`(4:成功列出当前版本 / 降级仍返 1 条 / 跨租户 404 / 单版本 detail OK + stale 404) |
| 2026-06-04 | **workflow-dag-designer MVP BE 回退 DAG 校验(`PUT /api/console/workflow-definitions/{id}/full`)**:无 path 增删、无 request/response schema 改。新增 `WorkflowDagValidator`(`batch-console-api/.../domain/workflow/validation/`),在 `DefaultConsoleWorkflowDefinitionApplicationService.fullUpdate` 锁校验后、持久化前同步跑;违反任意一条 → `BizException.of(VALIDATION_ERROR, "error.workflow.dag.<reason>", args...)` → 400。规则:节点数 ≤ 200、nodeCode 唯一、恰好 1 START、≥ 1 END、边端点必须已知、Kahn 无环、从 START BFS 可达、JOB.related_job_code 非空、FILE_STEP.related_pipeline_code 非空 + 必须命中同租户 `pipeline_definition`(新增单一 mapper method `PipelineDefinitionMapper.countByJobCode(tenantId, jobCode)`)、GATEWAY 出度 ≥ 2、GATEWAY.nodeParams 非空(承载 gateway_strategy)。i18n key `error.workflow.dag.*` 13 条(en + zh_CN 1:1)。FE client-side validators 仍按 lane 走,BE 是数据真相源防绕过 / 防恶意输入。**范围边界**:仅拓扑 + 引用完整性,不裁定业务对错(ADR-021)、不做 N+1 性能优化(MVP 节点 ≤ 200)、不做高级图分析。新增单测 `WorkflowDagValidatorTest`(18 case 覆盖每条规则 + 成功路径 + MAX_NODES 边界);`ConsoleWorkflowFullUpdateControllerTest` 加 2 case(DAG 含环 / FILE_STEP pipelineCode 不存在)|
| 2026-06-04 | **workflow-dag-designer BE Spike(docs/design/workflow-dag-designer.md)**:新增 4 端点 — ① `PUT /api/console/workflow-definitions/{id}/full` 画布全量替换(body `{definition, expectedVersion?, lockToken?}`,definition 复用 `WorkflowDefinitionSaveRequest`,同事务删旧 node/edge + 重写 + `version += 1`,workflowCode 不可改;失败:CONFLICT 锁不归属/锁未持有/expectedVersion 冲突、INVALID_ARGUMENT code 试改、NOT_FOUND);② `PUT /{id}/lock` 申请编辑锁(Redis SETNX,key `wf-design-lock:{tenantId}:{definitionId}`,5min TTL,value JSON `{lockedBy, expiresAt}`;别人持锁 → 409 含 lockedBy);③ `DELETE /{id}/lock` 释放锁(必须持锁人,204;锁过期幂等 no-op;非持锁人 403);④ `PUT /{id}/lock/renew` 续期 5min(持锁人;锁过期 → 409 让前端重 acquire)。新增 BE-only 内部 service `WorkflowDesignLockService`(独立于 ShedLock,后者是 scheduled job 互斥不复用)。同步新增下拉数据源(BE-only 缺失补)`GET /api/console/queries/job-definitions/codes?tenantId=` + `GET /api/console/queries/pipeline-definitions/codes?tenantId=`,只返回 enabled=true 的 (code, name) 二元组,按 code 升序,不分页(假定 < 数百)。RBAC:full / lock 三端点要求 `ROLE_ADMIN | ROLE_TENANT_ADMIN`;codes 沿用 queries controller 类级 `ROLE_*`。租户作用域全部走 `ConsoleTenantGuard.resolveTenant`。i18n key `error.workflow_design_lock.*`(held_by_other / required / not_owner / expired / serialize_failed)+ `error.workflow_full_update.*`(code_immutable / version_conflict)en + zh_CN。**范围边界**:仅服务端持久化 + 锁机制 + 下拉补齐;DAG 拓扑校验语义(画布 / FE)/ 版本对比 diff(后续 Polish)/ 协同编辑均不做。新增单测 `WorkflowDesignLockServiceTest`(6)+ `ConsoleWorkflowFullUpdateControllerTest`(3) |
| 2026-06-04 | **修复 BFF 双重 `CommonResponse` 包装(J1 Bug 2 / ADR-026 e2e integration-adr-features:18)**:`POST /api/console/ops/dry-run/plan` + `GET /api/console/result-versions/{effective,id}` + `POST /api/console/result-versions/{id}/{promote,reject}` + `POST /api/console/ops/batch-day-replay/sessions` + `POST /sessions/{id}/{approve,cancel}` + `GET /sessions/{id}` 共 **9 处**(ConsoleResultVersionController list / ConsoleBatchDayReplayController entries 早就单独 unwrap 过,本次扫齐遗漏的 7 处 + dry-run 1 处)controller 把 orchestrator 返回的整个 `CommonResponse<T>` envelope 当作 data 整体写入 `responseFactory.success(resp)`,导致 FE 收到 `{success:true, data:{success:true, data:{...真实负载...}}}` 嵌套,e2e 断言 `data.findings` 落到 `data.data.findings` 而误判 success=false。本次抽 `ConsoleResponseFactory.forwardOrchestrator(Map)` helper:解 envelope 的 `data` 透传;若 envelope `success=false` 则抛 `SYSTEM_ERROR + error.console.orchestrator_forward_failed`(防御层回退)。**无 path 增删、无 request 字段改**;响应外形从"双层 envelope"恢复为协议契约规定的"单层 envelope"。新增单测 `planShouldUnwrapOrchestratorEnvelopeAndNotDoubleNest` + `planShouldPropagateOrchestratorFailureEnvelope` 守护回归 |
| 2026-06-03 | **新增 `GET /api/console/queries/pipeline-progress?tenantId&workerCodes=`**(2026-06-03 落地 docs/design/pipeline-stage-progress-display.md):批量查指定 worker 当前 pipeline stage 行级进度,返回 `[{workerCode, rowsProcessed, totalRowsHint, heartbeatAt}]` 列表,**仅 IMPORT LOAD 流式 stage 在跑时有值**(EXPORT GENERATE 是 one-shot 不支持 mid-emit,留 TODO;PROCESS/DISPATCH 是原子 stage 行数无语义,设计明确不做)。配套:SDK wire `HeartbeatRequest` + 平台 `WorkerHeartbeatDto` 末尾追加 `Long rowsProcessed` + `Long totalRowsHint`(2 字段,NON_NULL 序列化,LOAD/GENERATE 之外 stage / 空闲态都是 null);Python SDK `_heartbeat.py` 同步对齐;新增 `PipelineStageProgressSink`(worker 进程级 AtomicReference,LoadStep 每 chunk flush 后 publish,stage 结束/异常 clear);新增 `PipelineStageProgressCache`(orchestrator in-mem ConcurrentHashMap,5min TTL,**不持久化**——进度只关心当前,过期由读侧 lazy 清理);新增内部端点 `GET /internal/pipeline-progress?tenantId&workerCodes=`(orchestrator → console-api,走 `DownstreamFallback.callOrFallback` 失败降级空 list)。FE 已先合 batch-console#55,本 PR 之前 FE 是 fallback 「—」展示,合后真实数据接入 |
| 2026-06-08 | **修正公开 `GET /api/console/queries/pipeline-progress` 契约为 `?pipelineInstanceId=`**：前端 FilePipelineObservability 按 pipeline instance 拉取 step 进度，返回 `{pipelineInstanceId, steps:[{stepId,pipelineInstanceId,stepCode,stageCode,rowsProcessed,totalRowsHint,lastHeartbeatAt}]}`。console-api 从 `batch.pipeline_step_run` 左连接 `batch.pipeline_progress`，用 pipeline instance 反查 tenant 后走 `ConsoleTenantGuard` 校验。旧 `tenantId&workerCodes` 入口只保留为兼容/内部降级路径，不再作为 OpenAPI 公开契约。 |
| 2026-06-08 | **新增 `GET /api/console/stream/pipeline-progress/events`**：发布低频命名 SSE 事件 `pipeline-progress-dirty`，payload 只含 `tenantId/pipelineInstanceId/jobInstanceId/reason/version/updatedAt`，不含 `rowsProcessed` 明细。前端收到 dirty 后防抖调用 `GET /api/console/queries/pipeline-progress?pipelineInstanceId=` 拉快照；后台发布器按 `pipelineInstanceId` 节流，默认 5s 扫描、10s 同 pipeline 节流。 |
| 2026-06-03 | **P1+P2 BE 安全加固**(docs/analysis/2026-06-03-deep-scan-be-security.md):无 path 增删,无 request 字段改;仅响应侧 `GET /api/console/api-keys` / `GET /api-keys/{id}` 不再回写 `keyHash`(此前实际无 FE 消费),新增字段 `salt` / `keyHashAlgo` 均标 `@JsonIgnore` 不外露。配套:① `ApiKeyHasher`(PBKDF2-HMAC-SHA256 600k iter + per-key 16B salt)替代裸 SHA-256,新签发一律 `pbkdf2`,老行登录命中后异步升级(V166 migration:加 `salt VARCHAR(64)` + `key_hash_algo VARCHAR(16) DEFAULT 'sha256'`,`key_hash` widen 256);② `ConsoleAuthenticationFilter` 撤掉"bypass-mode 时 JWT 失败降级到 admin"分支,客户端带 token 必按 token 严判;③ `ConsoleSecurityProperties.@PostConstruct` 启动期拒 CORS origins 含 `*` / `null` / 空白条目,ArchTest `CorsWildcardArchTest` 静态防回退;④ `RlsTenantSessionSupport` 改 `set_config(?,?,true)` PreparedStatement + `[A-Za-z0-9_\-]{1,64}` 形态白名单;⑤ `HttpTaskExecutor` 响应头落 `task_result.output` 前按固定黑名单(Set-Cookie / Authorization / Cookie / Proxy-Authorization)脱敏为 `[REDACTED]` |
| 2026-06-02 | **新增 `POST /api/console/ops/atomic-task-configs` + `GET ?taskType=`**(Round-1 TOP-8 / R3-5,BE scaffold,FE follow-up):承接 B.2 atomic 节点"可保存配置"诉求,原 console 只展示 `ConsoleAtomicTaskTypeSchemaService` 静态 schema 不存,本次落 V165 新表 `batch.atomic_task_config`(`id` BIGSERIAL / `tenant_id` NOT NULL / `task_type` / `name` / `parameters` JSONB / `created_by` / `created_at`/`updated_at`,UNIQUE `(tenant_id, task_type, name)`,archive 镜像 + ArchiveSchemaDriftCheck 登记)。POST 创建链:租户解析 → `ConsoleAtomicTaskTypeSchemaService` 校验 `taskType` 命中四类内置 + `parameters` key 落 schema + 必填非空 → `SensitiveDataValidator`(#242)拒 password/secret/apiKey 等关键字 → `ObjectMapper` 序列化为 JSONB `::jsonb` 入库,`@Transactional` 在 service。GET 按 `(tenant_id, task_type)` 列,`created_at` 倒序。Controller 返 `CommonResponse<T>`,租户作用域走 `ConsoleTenantGuard.resolveTenant`。新增 console 侧 `AtomicTaskConfigEntity` / `AtomicTaskConfigCreateParam` / `AtomicTaskConfigMapper`(+XML)/ `ConsoleAtomicTaskConfigService` / `ConsoleAtomicTaskConfigController`;i18n `error.atomic_task_config.*` 8 条 + zh_CN。FE 工作流编辑器接入留 follow-up |
| 2026-06-02 | **Round-3 #8(Round-2 §4 P0 #8):新增 `GET /api/console/ops/atomic-runtime-status`** + atomic worker 暴露 Actuator 端点 `/actuator/atomicruntime`。承接 #252-K1 隐式 prod 默认显式化:`HttpExecutorProdDefaults.applyProdDefaults` 加 `INFO` 启动日志(`ADR-029 prod hardening: enforce-allowlist auto-enabled (was=...)`,显式 / prod-default / 已开三态都打);atomic worker 新增 `AtomicRuntimeStatusService` + `AtomicRuntimeStatusEndpoint` 把 4 个 executor 当前 effective 安全门控(`shell.{enabled,commandWhitelistSize}` / `sql.{enabled,dialect}` / `http.{enabled,enforceAllowlist,enforceAllowlistSource,allowlistHostsSize}` / `storedProc.{enabled,allowedSchemasSize}`)做成只读快照;Console 加 `ConsoleAtomicWorkerClientProperties` + `AtomicWorkerInternalRestClient`(走 `DownstreamFallback` callOrFallback,5s/10s 超时)反向拉取。Operator 角色(TENANT_ADMIN+)+ 菜单 `/ops/atomic-runtime`。返回 `available=false` 时 FE 显示降级 banner |
| 2026-06-02 | **新增 `GET /api/console/workers/fingerprints` + `/summary`**(SDK Phase 5 / SDK-P5-3,console Lane D):租户视图查看 worker 运行指纹(V163 给 `batch.worker_registry` 加的 `build_id` / `sdk_version` 列,SDK Phase 5 register 已上报)。list 返 `ONLINE` + `DRAINING` worker(`heartbeat_at` 倒序,上限 200),含 `workerCode` / `buildId` / `processId` / `sdkVersion` / `status` / `heartbeatAt`;summary 按 `(buildId, sdkVersion)` 聚合 ONLINE 数,count desc,空值 SQL 层 COALESCE 为字面量 `(unknown)`。供运维灰度切 buildId 时排查与可视化用,取代 SQL 直查。只读、走 console-api 读写分离只读路径,worker_registry 由 orchestrator 写入;租户管理员看本租户、平台管理员可跨租户(`ConsoleTenantGuard.resolveTenant`)。新增 `WorkerFingerprintRow` / `WorkerFingerprintSummaryRow` / `WorkerFingerprintMapper`(+XML)/ `WorkerFingerprintResponse` / `WorkerFingerprintSummaryResponse` / `ConsoleWorkerFingerprintController`。FE 看板页留 follow-up(见 docs/analysis/2026-06-02-* TOP #5) |
| 2026-06-01 | **新增 `GET /api/console/atomic-task-types/schema` + `GET /api/console/tasks/{taskId}/heartbeat-details`**(FE 2-B / 2-C 读端点前置):前者返回平台内置原子四类(sql/shell/stored_proc/http)的参数 schema + 安全闸**静态目录**(console-api 不依赖 batch-worker-atomic,以静态镜像维护,字段与各 `*TaskExecutor.PARAM_*` + `*ExecutorProperties` 对齐),非租户维度、对所有租户一致,供工作流编辑器渲染内置节点表单。后者按租户读 `batch.job_task`(V161)最新心跳进度:`heartbeat_details` JSONB `::text` 读出后解析为 JSON 透传,无心跳时 details=null,未命中(或跨租户)404;租户作用域在 mapper WHERE 强制。两者均只读,走读写分离只读路径。新增 console 侧 `AtomicTaskTypeSchema` / `ConsoleAtomicTaskTypeSchemaService` / `ConsoleAtomicTaskTypeController`;`JobTaskHeartbeatEntity` / `JobTaskMapper`(+XML)/ `TaskHeartbeatDetailsResponse` / `ConsoleTaskHeartbeatService` / `ConsoleTaskController` |
| 2026-06-01 | **新增 `GET /api/console/custom-task-types` + `/count` + `/{taskTypeCode}`**(SDK Phase 3 M3.1 / API-P3-1):租户查看 SDK 声明的自定义 taskType(`custom_task_type_registry`,由 worker register 上报 descriptor 维护)。list/count 仅 ACTIVE,按 `last_declared_at` 倒序;detail 返回单条含 `descriptor` JSONB 全文(`::text` 读出原样透传),未命中 404。只读、走读写分离只读路径,租户管理员看本租户、平台管理员可跨租户(`ConsoleTenantGuard.resolveTenant`)。新增 console 侧 `CustomTaskTypeEntity` / `CustomTaskTypeMapper`(+XML)/ `ConsoleCustomTaskTypeController` |
| 2026-05-31 | **补 `GET /api/console/my-workers` + `GET /api/console/my-workers/count` 到 OpenAPI**(ADR-035 P4 BE):#172 落地 `ConsoleMyWorkerController` 时漏更 yaml,`check-console-openapi-paths.py` 在 main 报红 → full-ci-gate 失败。本次补 OpenAPI 定义恢复绿。两端点都按 `is_self_hosted=true` 过滤,租户管理员看自己 SDK 自托管 worker 列表 + 计数。FE 列表页 P4 后续 |
| 2026-05-30 | **任务级 log viewer 后端 `GET /api/console/queries/job-execution-logs`**(P0):在已有 `batch.job_execution_log` 表(V7)上加只读查询,锚定单个 `jobInstanceId`(必填),支持 `logLevel`(DEBUG/INFO/WARN/ERROR)/ `logType`(SYSTEM/BUSINESS/RETRY/ALARM/AUDIT)/ `jobPartitionId` / `keyword`(message 模糊)过滤 + 双轨分页(ADR-031 cursor,order by id desc)。补运维痛点:之前 console 无法在线看某实例执行日志,只能 SSH 翻文件。注意已有 `GET /queries/execution-logs` 是 `file_audit_log` 别名,与本端点不同表。新增 console 侧 `JobExecutionLogEntity` / `JobExecutionLogQuery` / `JobExecutionLogMapper`(+XML)/ `JobExecutionLogQueryRequest` / `ConsoleJobExecutionLogResponse`,查询走 `ConsoleJobQueryService.jobExecutionLogs`。新增 schema `CommonResponseJobExecutionLogList` |
| 2026-05-19 | **`GET /api/console/system/cron-preview` Cron 工具接口**:校验 + 计算下 N 次执行时刻,用 Quartz `CronExpression`(与 trigger 调度器同一份代码,时间与真实触发完全一致)。返回 `{expr, valid, error?, nextRuns[], timezone}`,nextRuns ISO-8601 UTC 升序,count 默认 3 上限 20,时区取 `batch.timezone.default-zone`。供前端 `CronExprInput` 防抖调用,替换之前 FE 自实现的不准解析。`batch-console-api` 新加 `org.quartz-scheduler:quartz` 直接依赖(不引 starter-quartz,免拉 Job/Trigger/Scheduler 整套 DI) |
| 2026-05-19 | **`DELETE /api/console/admin/test-data?prefix=...`(admin-only 批量级联清理测试数据)**:解决 e2e 跑完业务表 60-85% 是 `e2e-*` 残留的问题。按 FK 反向 DELETE 11 张表(job_partition / job_instance / workflow_node / workflow_edge / workflow_definition / job_definition / file_channel_config / file_template_config / console_user_account / archive_policy / tenant),`@Transactional` 全表事务,任何一段失败整体回滚。prefix 强制 `^[a-zA-Z][a-zA-Z0-9_-]{2,32}$` 正则 + SQL `LIKE 'prefix-%'`,空 prefix / SQL 通配符直接 400,只匹配 `prefix-xxx` 不会误删 `prefixer` 这种合法资源。`@AuditAction` 留痕到 console_operation_audit。FE `e2e/global-teardown.cjs` 自动调用 |
| 2026-05-19 | **通用用户操作审计 `GET /api/console/queries/operation-audits` + `@AuditAction` 切面落地**:新建 `batch.console_operation_audit` 表(V130,17 列含 aggregateType/aggregateId/action/operatorId/operatorRole/result/params(JSONB)/traceId/requestId/ipHash/uaHash/eventVersion),由 `AuditAspect` 在 `@AuditAction` 标注的方法成功返回后**同事务**写入(业务回滚 → audit 也回滚,强一致)。schema 字段顺序与将来 Kafka topic 对齐,迁移时只换 sink。试点端点:alert ack/silence/close、approval approve/reject/batchApprove/batchReject、instance cancel/terminate/partition retry/cancel、auth login/logout、apiKey create/revoke、alertRouting create/update/toggle、outbox cleanup/republish(共 16 处)。敏感操作(login、apiKey.create)显式 `recordParams=false` 避免密码/明文 key 落表。新增 `ConsoleOperationAuditResponse` + `CommonResponseConsoleOperationAuditList` schema |
| 2026-05-19 | **维护模式 / 降级开关落地**:新增 `batch.console.maintenance.{enabled,message,eta-at,read-only}` 配置 + `MaintenanceModeFilter`(放在 SecurityFilterChain 最前,白名单 `/actuator/**` / `/api/console/auth/check` / `/api/console/auth/logout` / `/api/console/system/maintenance`)+ `GET /api/console/system/maintenance` 状态探活(始终 200,permitAll)。enabled=true:除白名单外整站返 503 + JSON `{maintenance, readOnly, message, etaAt}` + `Retry-After` header;readOnly=true:仅写方法(POST/PUT/PATCH/DELETE)503,GET 通过并打 `X-Maintenance: read-only` header。前端轮询 30s 自动切换 banner / 降级页 / 写按钮禁用。SOP 见 `docs/runbook/maintenance-mode.md` |
| 2026-05-19 | **`GET /api/console/queries/instances` 新增 `slaBreached` 过滤**:供移动端 OpsSummary 「SLA 违约」入口跳转 MJobInstances 走服务端筛选,避免客户端只过滤当前页造成「分页与计数不一致」。判定逻辑与 `countSlaBreaches` 一致:`deadline_at < now AND instance_status IN (CREATED/WAITING/READY/RUNNING/PARTIAL_FAILED)`。`JobInstanceQuery` record 加 `Boolean slaBreached`,JobInstanceMapper.xml selectByQuery + countByQuery 同步加 SQL 片段 |
| 2026-05-19 | **Job Bundle 严格 all-or-nothing 落地**：`TenantConfigBatchInitRequest` 新增 `strict` 布尔(默认 false 保留 ConfigSync 部分成功语义);`DefaultConsoleTenantConfigInitApplicationService.initForTenant` 检测 strict=true 且 totalFailed>0 时抛 `StrictBundleAbortedException`,由外层 `@Transactional` 触发整体回滚;`DefaultConsoleJobBundleApplicationService.create/importBundle` 强制 strict=true。OpenAPI `/jobs/bundle/create` `/jobs/bundle/import` description 改为 "Strict all-or-nothing",与实现对齐。新增单测 `batchInit_strictModeRollsBackOnItemFailure` |
| 2026-05-19 | **补漏端点 `GET /api/console/meta/pipeline-stages` + `GET /api/console/meta/step-impls`**：后端 `ConsoleMetaController` 已实现、前端 `api/meta.ts` 已调用，但 OpenAPI 漏写导致 `check-console-openapi-paths.py` 报红。pipeline-stages 返回 `Map<pipelineType, List<stageCode>>`（与 `ConfigPackageExcelValidator.STAGES_BY_TYPE` 同源）；step-impls 入参可选 `module=IMPORT\|EXPORT\|PROCESS\|DISPATCH` 过滤 `step_registry` 已注册 impl_code。同次修复 `ImportFailureE2eIT.scenarioC` 因 `IMPORT_LOAD_CONFIG_INVALID` 列入 `NON_RETRYABLE_ERROR_CODES` 后短路死信导致 retry_count=0 的断言漂移 |
| 2026-05-18 | **Job Bundle 接口落地**：新增 `POST /api/console/jobs/bundle/create`、`GET /api/console/jobs/bundle/export`、`POST /api/console/jobs/bundle/import`。Bundle 复用 `ConfigSyncBundlePayload`，覆盖 jobDefinitions/workflowDefinitions/pipelineDefinitions/fileChannels/fileTemplates/resourceQueues/batchWindows/businessCalendars/quotaPolicies/alertRoutings；create 固定写当前租户，import 支持 targetTenantIds，写接口均走 `Idempotency-Key` 与后端事务。同次补齐既有 `GET /api/console/auth/public-key` OpenAPI 漏项，消除路径漂移。 |
| 2026-05-16 | **补 `GET /api/console/workflow-definitions/{id}/mermaid` 到 OpenAPI**:Mermaid viewer feature commit `8ea52c39` 加了端点但漏更 yaml,门禁脚本 `check-console-openapi-paths.py` 报红。Schema `WorkflowMermaidResponse{mermaid}` + 通用包装 `CommonResponseWorkflowMermaidResponse`。前端 `../batch-console/src/api/workflow.ts:140` 已调用,本次只补契约文件 |
| 2026-05-13 | **补 createUser 端点 `POST /api/console/users`**（ROLE_ADMIN，幂等）：V34 注释一直声称"通过该端点创建账号"但端点未实装；只需要 controller + service.create + yaml 定义即可（Mapper.insert 早已存在）。Request `{tenantId?, username, password, displayName?, authoritiesCsv?}`（username 规则同 CreateTenantRequest，password 最少 8 位），username 跨租户唯一时拒绝重名；authoritiesCsv 空时落 USER 默认。`createArrivalGroup` **不补**——`FileArrivalGroup` 不是表，是 `file_record.metadata_json` 聚合视图（按 fileGroupCode GROUP BY），没有"创建"语义，到达组由文件上报自然形成 |
| 2026-05-13 | **MCatchUp 审批按钮复活准备**：`ConsolePendingCatchUpResponse` 加 `approvalNo` + `approvalStatus`（来自 `batch.approval_command` LEFT JOIN，关联键 `target_id=request_id AND approval_type='CATCH_UP'`）。`PendingCatchUpMapper.selectByQuery` SQL 加 JOIN，未走统一审批的旧记录 approvalNo=null。前端 MCatchUp 拿到 approvalNo 后走统一审批 `POST /api/console/approvals/{approvalNo}/approve\|reject` |
| 2026-05-13 | **/queries/instances + /queries/workflow-runs 暴露 traceId 过滤**：OpenAPI 显式声明 `$ref TraceIdFilter`，配合既有 BE Mapper 过滤能力，前端不再走"拉全量 + 客户端 filter"回退；同步把 `WorkflowRunMapper.trace_id = #{traceId}` 改为 `like concat('%', ..., '%')`，与 `JobInstanceMapper` 模糊匹配语义对齐 |
| 2026-05-11 | **工作流编辑器画布高亮**：`POST /api/console/workflow-definitions/{id}/validate` 响应升级为 `DagValidationResult { valid, errors: string[], findings: DagValidationFinding[] }`。新增 `DagValidationFinding{code, level: ERROR\|WARNING, message, nodeCode?, edgeId?}`，前端拿到 finding 可按 nodeCode/edgeId 在画布上高亮对应单元（之前只返字符串数组）。`errors` 保留作向后兼容（next minor 删除），新代码请消费 findings。15 条规则按 code 枚举：MISSING_START / MULTIPLE_START / MISSING_END / MULTIPLE_END / EDGE_SOURCE_MISSING / EDGE_TARGET_MISSING / JOB_REF_MISSING / JOB_REF_NOT_FOUND / JOB_REF_DISABLED / EDGE_CONDITION_MISSING_EXPR / CYCLE_DETECTED / UNREACHABLE_FROM_START / CANNOT_REACH_END |
| 2026-05-10 | 新增 `GET /api/console/auth/check`：轻量鉴权探针，登录 → 204，未登录 → 401（走 SecurityConfig entryPoint）。专给 nginx `auth_request` / 反代鉴权用（如 `/docs/*` VitePress 静态站点接入控制台登录态时高频探测），不查 DB / 不组菜单，避免拖累现有 `GET /me`（要做 menu filter）。`@PreAuthorize("isAuthenticated()")` 显式声明，与 controller 其他端点一致 |
| 2026-05-10 | **API 对齐审计**：补录 `GET /api/console/ops/cluster-diagnostic/terminal-children`（集群诊断 — 终止节点子节点健康检查，`ROLE_ADMIN`，入参 `tenantId`，响应 `CommonResponseObject`），此端点已在控制器中实现但 OpenAPI 漏写 |
| 2026-05-07 | **ADR-017 Stage 6 result_version console 接入 + ADR-020 BATCH_DAY_REPLAY 审批接入**：（A）新增 5 个 `/api/console/result-versions/*` 端点 — `GET ?businessKey&limit`（按 businessKey 列举所有版本，含 PENDING / EFFECTIVE / SUPERSEDED / ARCHIVED / DRY_RUN）/ `GET /effective?businessKey`（当前 EFFECTIVE，同 businessKey 至多 1 条）/ `GET /{id}`（含完整 payload_json）/ `POST /{id}/promote`（PENDING → EFFECTIVE）/ `POST /{id}/reject`（PENDING → ARCHIVED）。orchestrator 端 `ResultVersionController` 提供 `/internal/orchestrator/result-versions/*` 同形 5 端点；写路径走 `ResultVersionPromoteService`，由 partial unique index `uk_result_version_effective` 保 EFFECTIVE 单版唯一。（B）**ADR-020 审批接入收尾**：`DefaultConsoleApprovalApplicationService.approve` switch 加 case `BATCH_DAY_REPLAY` — 接受 payload `{sessionId, tenantId}`，approve 后转发到 orchestrator `batch-day-replay/sessions/{id}/approve` 推进 PENDING_APPROVAL → RUNNING；approver 取自 operatorId（审批人），payload 字段缺失抛 `error.batch_day_replay.invalid_argument` |
| 2026-05-07 | **ADR-018/020/026 前端待办接口对齐**：（1）**ADR-020 批次日重放** 新增 5 个 `/api/console/ops/batch-day-replay/sessions...` 端点 — `POST sessions`（提交，scope ∈ ALL/ALL_FAILED/SUBSET_JOB_CODES/OUTPUTS_ONLY，autoApprove 控制 RUNNING 直起还是 PENDING_APPROVAL）/ `POST sessions/{id}/approve` / `POST sessions/{id}/cancel` / `GET sessions/{id}` / `GET sessions/{id}/entries`（按 status 过滤进度）；新增 schema `BatchDayReplaySubmitRequest`。orchestrator 端 `BatchDayReplayController` 由 `BatchDayReplayService` 后端落地，已含 OUTPUTS_ONLY 同步 promote。（2）**ADR-026 dry-run** 新增 `POST /api/console/ops/dry-run/plan` — L1 CONFIG_VALIDATE / L2 SCHEDULE_PLAN / L3 EXECUTION_PLAN 三档；新增 schema `DryRunPlanRequest`；L3 探测 SQL EXPLAIN / MinIO bucketExists / HTTP HEAD reachability，**不写 instance / 不调外部投递**（红线 priority-scope §5）；`ConsoleJobInstanceResponse` 加 `dryRun` + `failureClass` 字段。（3）**ADR-018 跨日 DAG** `ConsoleWorkflowNodeResponse` 加 `crossDayDependencies` JSON + `crossDayDependencyTimeoutSeconds` 让 UI 渲染依赖图。新增公共 schema `CommonResponseObjectArray` |
| 2026-05-07 | **ADR-022 v0.1** 新增 `POST /api/console/forensic/export` + `GET /api/console/forensic/export/{exportId}/download`：v0.1 同步生成 bundle（zip 含 manifest.json + job-instances.json + batch-day-operation-audits.json），SHA-256 attestation，落本地 fs。仅 ROLE_ADMIN。Schema `ForensicExportRequest` 含 `tenantId / bizDateFrom / bizDateTo / jobCodes? / exportFormat? / requestedBy?`。orchestrator 端 `POST /internal/forensic/export` 由 `ConsoleOrchestratorProxyService.requestForensicExport` 转发；service 同步打包 + sha256 + insert PROCESSING / markCompleted / markFailed 三态机。**v0.2 才做**：*_history 影子表 / OSS 对象锁 / 7 年保留 / 异步生成 / 配置 point-in-time 重建。**主链路无影响** —— 仅运维 ops pull，不在 trigger / claim / report 路径 |
| 2026-05-06 | 新增 `POST /api/console/batch-days/operate`：批量日治理 5 个动作 `FREEZE / RELEASE / SKIP / REOPEN / CLOSE` 统一入口（dispatcher 风格 body 路由，避免 5 条独立路径）。`BatchDayOperateRequest` schema：`tenantId / calendarCode / bizDate / action / operatorId / reason`，action 走 `^(FREEZE\|RELEASE\|SKIP\|REOPEN\|CLOSE)$` 校验。Console 控制器 `ROLE_ADMIN` + `@Idempotent`；orchestrator 端 `POST /internal/batch-days/operate` 由 `ConsoleOrchestratorProxyService.batchDayOperate(...)` 转发，状态机由 `BatchDayOperationService` 推进、同事务双写 `job_execution_log` + V105 `batch_day_operation_audit`；RELEASE 触发 `batch_day_waiting_launch` 下一日重放 |
| 2026-05-06 | `RerunRequest` 显式补跑策略入参（§5.5）：`resultPolicy`（`CREATE_NEW_VERSION` / `KEEP_BOTH` / `MANUAL_CONFIRM_EFFECTIVE`，默认 `CREATE_NEW_VERSION`）、`configVersionPolicy`（`USE_ORIGINAL_CONFIG` / `USE_LATEST_CONFIG` / `USE_SPECIFIED_VERSION`，默认 `USE_ORIGINAL_CONFIG`）、`configVersion`（仅 `USE_SPECIFIED_VERSION` 必填）。后端 `@Pattern / @Positive` + 跨字段校验；策略经 `CompensationPayload` → `CompensationSubmitCommand` → `LaunchRequest.params` 透传到 `job_instance.rerun_policy_snapshot`。同步补齐 OpenAPI `RerunRequest` schema（之前缺 `targetId / batchNo / relatedFileId / operatorId / approvalId / strategy` 字段）。`targetInstanceNo` 不再 required（与 `targetId` 二选一即可，BATCH 路径 dedupKey 可全空）|
| 2026-05-03 | `GET /api/console/meta/enums` 新增字典 key：`deadLetterErrorClass`（V90）/`quotaExceededStrategy`（V89）；同步 `CommonResponseMetaEnums` |
| 2026-05-03 | `POST /api/console/config/tenant-init` 与 `POST /api/console/config/tenant-copy` 的 requestBody 改为 `$ref` 引用：新增 `TenantConfigBatchInitRequest`（含 11 个嵌套 Spec：`JobDefinitionSpec` / `WorkflowDefinitionSpec`(`NodeSpec`/`EdgeSpec`) / `PipelineDefinitionSpec`(`StepSpec`) / `FileChannelSpec` / `FileTemplateSpec` / `ResourceQueueSpec` / `BatchWindowSpec` / `BusinessCalendarSpec` / `TenantQuotaPolicySpec` / `AlertRoutingSpec`）+ `TenantConfigCopyRequest` + `TenantConfigCopyType` enum。**修复**：原 yaml 中两处 requestBody 是 `type: object` 占位 / inline 手写，前端 codegen 拿不到完整结构；现改 `$ref` 后能完整生成 11 个 Spec 类型。`TenantConfigCopyType` 后端 `@JsonCreator` 兼容 `JOB`/`WORKFLOW`/`PIPELINE` 简写已在 schema description 注明 |
| 2026-04-30 | **i18n 三元组持久化(V77/V78)** + **ADR-009 节点 outputs(V72)**:`POST /internal/tasks/{taskId}/report` 请求体 `TaskExecutionReportDto` 追加 `errorKey: string` + `errorArgs: string`(JSON 数组,跨进程透传 BizException.of 的 i18n key + args);追加 `outputs: Map<String, Object>`(SUCCESS 时 worker 上报节点产出,如 fileId/recordCount/receiptCode 等,orchestrator 序列化写 `workflow_node_run.output` JSONB 列,供下游 workflow 节点 `$.nodes.<X>.output.<key>` DSL 引用)。`@JsonIgnoreProperties(ignoreUnknown=true)` 保护滚动升级期未识别字段。console 读路径(11 个 query service)过 `LocalizedErrorRenderer` 按当前 Locale 重渲染历史失败记录文案 |
| 2026-04-30 | **ADR-010 trigger 异步路径**:新增内部 Kafka topic `batch.trigger.launch.v1`(版本化,key=`tenantId:requestId`,value=`LaunchEnvelope` JSON,headers `X-Trace-Id`/`X-Tenant-Id`/`X-Envelope-Version`)。trigger → orchestrator 调度链走 outbox+Kafka 异步模式（2026-05-02 固化，`HttpOrchestratorTriggerAdapter` 同步 HTTP 桥已删除） |
| 2026-04-27 | P0-1.5 ExecutionMode 运行时打通（紧接 P0-1 模型层）。`TaskDispatchMessage` v1 schema 末尾追加 `highWaterMarkIn` 字段（FULL/CDC 为 null;旧 worker 反序列化忽略未知字段，前向兼容）；`POST /internal/tasks/{taskId}/report` 请求体 `TaskExecutionReportDto` 追加 `highWaterMarkOut` 字段（worker 业务逻辑通过 `ExecutionContext.attributes(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT)` 写入，框架自动透传）。orchestrator 在 `DefaultLaunchService.buildJobInstanceEntity` 给 INCREMENTAL job 从上次成功实例的 `high_water_mark_out` 读出作 IN；`DefaultTaskOutcomeService` 成功路径回写 OUT 到 `job_instance.high_water_mark_out`，失败/重试不推水位 |
| 2026-04-27 | P0-1 ExecutionMode 一等公民化（`docs/design/batch-classification-and-gaps.md` §4.1）。`ConsoleJobDefinitionResponse` 新增 `executionMode`（FULL / INCREMENTAL / CDC，required，默认 FULL）和 `watermarkField`（增量水位字段名，可空）；`POST /api/console/job-definitions` 与 `PUT /api/console/job-definitions/{id}` 请求体接受同名字段（请求 schema 仍 `type: object`，前端按字段名传即可）；`/api/console/meta/enums` 新增字典 `executionMode`（3 项）。后端 V73 migration 给 `job_definition` 加 `execution_mode/watermark_field`，给 `job_instance` 加 `high_water_mark_in/out`；运行时打通（worker 读写水位）后续 commit |
| 2026-04-26 | 补录 `ConsoleConfigCacheController` 6 条路径到 OpenAPI（commit a9b38d17 在 2026-04-25 引入但 yaml 漏写，CI path 一致性校验失败）：`POST /api/console/ops/cache/evict-{job-definition,all-job-definitions,workflow-definition,business-calendar,batch-window,quota-policies}`，全部为 `ROLE_ADMIN` Ops 救急入口（DB 直改后手动 evict orchestrator Redis cache，避免等 5min TTL 自然过期）；正常 console 写路径已自动 afterCommit evict |
| 2026-04-23 | 新增 `GET /api/console/queries/partitions`：按作业实例分页查询 `job_partition` 列表，支持 `partitionStatus` 过滤，按 `partition_no ASC` 排序；响应 `ConsoleJobPartitionResponse`（13 字段：`id/tenantId/jobInstanceId/partitionNo/partitionKey/partitionStatus/workerGroup/workerCode/retryCount/businessKey/leaseExpireAt/startedAt/finishedAt`）。前端 PartitionView 改为服务端分页，替换已废弃的本地聚合调用 |
| 2026-04-20 | 触发器运维 5 条路径由 `/api/console/triggers` 迁到 `/api/console/ops/triggers`（list/{jobCode}/register/unregister/pause/resume），语义归 Ops 救急入口；日常禁用 job 请走 `toggleEnabled`，`TriggerReconciler` 以 30s 周期把 Quartz 收敛到 DB（`ROLE_ADMIN` 权限不变，限流路径前缀同步调整） |
| 2026-04-20 | `/api/console/meta/enums` 的 `triggerType` 字典新增 `RERUN`（重跑触发）；与 V62 迁移中扩展的 `ck_trigger_request_type` / `ck_job_instance_trigger_type` CHECK 约束对齐；OpenAPI `MetaEnumItem` 由后端反射下发，schema 无需改 |
| 2026-04-20 | 删除 7 个单表 Excel 维护接口的 upload/preview/previewWorkbook/apply（job-definitions / workflows / business-calendars / quota-policies / batch-windows / file-channels / pipeline-definitions，共 28 条路由，均已由 tenant-package 合并导入取代）。保留各自 `/export` 和 `/template` 导出；同步删除 49 个仅被这些端点引用的 schema（含 Common/Preview/Apply wrapper 和 per-entity request body） |
| 2026-04-20 | 补录 `POST /api/console/auth/stream/ticket` 到 OpenAPI（2026-04-18 新增端点，Changelog 已记但 yaml 漏写，CI path 一致性校验失败）；新增 `ConsoleSseTicketResponse` schema（`{ticket: string}`）和 `CommonResponseConsoleSseTicketResponse` 包装器 |
| 2026-04-18 | `GET /api/console/auth/me` 响应体 `ConsoleAuthProfileResponse` 补齐 `menus` 字段（后端早已下发，OpenAPI 漏写导致前端 codegen 不可见，退化为硬编码）；新增 `ConsoleMenuGroup` / `ConsoleMenuItem` schema，字段：`key`/`title`/`icon`/`minRole`（VIEWER/OPERATOR/ADMIN）、`children`、`path`；前端应从 `menus` 渲染侧边栏，废弃本地硬编码 navigation |
| 2026-04-18 | 新增 `POST /api/console/auth/stream/ticket` SSE 一次性 ticket 鉴权端点；SSE 连接支持 `?ticket=` 参数（替代已移除的 `?token=`）；outbox-deliveries/outbox-retries stream 补充业务事件发布；`/meta/enums` 新增 8 个字典：triggerStatus/deliveryStatus/notificationChannelType/tenantStatus/logType/workflowDefinitionStatus/tenantConfigInitAction/triggerResourceType；OpenAPI 补 9 个 enum schema |
| 2026-04-17 | 抽取 `DictEnum` 接口（`code()` / `label()`），batch-common 下全部 60 个公共枚举统一实现；`ConsoleMetaQueryService.EnumReg` 精简为 `(key, enumClass)` 两字段，`GET /api/console/meta/enums` 新增 3 个字典 key：`fileDispatchStatus` / `fileReceiptStatus` / `pipelineRunStatus`（原为裸枚举，补齐 code/label 后对外暴露）；守护测试 EXCLUDED 白名单从 6 收窄到 3（保留 `ResultCode` / `WorkflowNodeCode` / `JobStatus`），新增 DictEnum 实现的强制断言 |
| 2026-04-17 | `GET /api/console/meta/enums` 新增 17 个字典 key：`priorityLevel` / `aiPromptDecision` / `checksumType` / `workflowJoinMode` / `fileDispatchRunStatus` / `compensationStatus` / `retryScheduleStatus` / `encryptType` / `compressType` / `errorSinkType` / `priorityBand` / `stepInstanceStatus` / `runMode` / `skipAction` / `workflowNodeRunStatus` / `deadLetterReplayStatus` / `skipThresholdMode`；同步补齐 `CommonResponseMetaEnums` schema 定义；新增 `ConsoleMetaEnumRegistrationTest` 守护测试，强制新增枚举二选一：注册或加入显式 EXCLUDED 白名单 |
| 2026-04-16 | 6 个 Excel 控制器（file-templates/file-channels/alert-routings/batch-windows/quota-policies/resource-queues）的 upload/apply 请求响应统一为共享类型 `ExcelUploadResponse`/`ExcelApplyResponse`/`ExcelApplyRequest`/`ExcelRowIssue`，旧 per-entity schema 改为 `$ref` 别名 |
| 2026-04-16 | `ExcelApplyResponse` 新增 `skippedRows` 字段；`ExcelPreviewResponse`（6 个 per-entity 变体）新增 `previewWorkbookUrl` 字段 |
| 2026-04-16 | 新增 `POST /api/console/config/alert-routings/excel/quick-import`：一键导入（upload + validate + apply 合并），无错误自动 apply（`applied=true`），有错误返回 preview + workbook URL（`applied=false`）；支持 `skipInvalid` 参数跳过无效行 |
| 2026-04-11 | list 查询 `enabled` 参数默认值改为 `true`：job-definitions / workflow-definitions / file-channels / file-templates 四类列表接口（含 `/queries/` 前缀版本），不传 `enabled` 时只返回已启用记录，需查禁用记录需显式传 `enabled=false` |
| 2026-04-11 | 软删除改 `PATCH`：`POST /{id}/toggle?enabled=` 及 `POST /batch-toggle` 统一改为 `PATCH /{id}`（body: `EnabledPatchRequest`）和 `PATCH /batch`（body: `BatchEnabledPatchRequest`），适用于 job-definitions / workflow-definitions / file-channels / file-templates |
| 2026-04-11 | `POST /api/console/files/delete` 改为 `DELETE /api/console/files/{fileId}`，请求体拆为 path/query 参数，统一物理删除走 `DELETE` 方法 |
| 2026-04-11 | 移除 `DELETE job-definitions/{id}`、`workflow-definitions/{id}`、`file-channels/{id}`、`file-templates/{id}`；软删除统一走 `PATCH /{id}`，`DELETE` 仅保留于物理删除场景 |
| 2026-04-11 | 删除 `DELETE /api/console/users/{id}`：账号不可物理删除，停用走 disable，彻底下线走租户 suspend |
| 2026-04-11 | 删除 `POST /api/console/users`（独立创建账号）：每个租户仅一个运营账号，由建租户接口统一创建，后续通过 `PUT /api/console/users/{id}` 调整权限 |
| 2026-04-12 | 新增 `GET /api/console/config/tenant-package/excel/export`：导出当前租户全量配置包为 8-Sheet xlsx（job_definition / file_channel / alert_routing / pipeline / pipeline_step / workflow_definition / workflow_node / workflow_edge），文件可直接回灌至合包导入接口 |
| 2026-04-12 | 10 个独立 Excel 导入 Controller 的 upload / preview / previewWorkbook / apply 端点标注 `deprecated`；export / template 端点不受影响；推荐改用 `/api/console/config/tenant-package/excel` 系列接口 |
| 2026-04-12 | `GET /api/console/meta/enums` 新增三个 key：`operationType`（文件审计操作类型，10 个值）、`operationResult`（SUCCESS / FAILED）、`fileStatus`（文件状态，11 个值）|
| 2026-04-12 | 新增 `GET /api/console/meta/biz-types?tenantId=`：按租户动态返回 `file_record` 中已有的 distinct `biz_type` 值，用于文件列表业务类型下拉筛选 |
| 2026-04-12 | 新增 `GET /api/console/config/tenant-package/excel/template`、`POST /upload`、`GET /preview/{token}`、`GET /preview/{token}/workbook`、`POST /apply/{token}`：8-Sheet 租户配置包 Excel 导入（job_definition / file_channel / alert_routing / pipeline / workflow，单事务，含跨 Sheet 依赖校验） |
| 2026-04-12 | `POST /api/console/tenants/batch`：`initConfigFrom` 不传时默认使用 `default` 模板租户（原逻辑：不传则跳过配置初始化） |
| 2026-04-12 | `POST /api/console/tenants/batch` 新增可选字段 `initConfigFrom`（源租户 ID）和 `initMode`（默认 `SKIP_EXISTING`）：非空时建完租户后自动复制源租户全部配置，响应体从 `List<ConsoleTenantResponse>` 改为 `{tenants, configInit}`（`configInit` 在未传 `initConfigFrom` 时为 null） |
| 2026-04-12 | 补齐 OpenAPI 缺失接口 `GET /api/console/config/file-templates/excel/preview/{uploadToken}/workbook`；修正 18 处 CRUD 创建/更新/复制响应体错误类型（`CommonResponseLong`/`CommonResponseString` → `CommonResponseObject`）；协议正文同步 PATCH 改造、移除已删除的 user create/delete 和 POST files/delete 接口描述 |
| 2026-04-11 | 新增 `POST /api/console/tenants/batch` 批量建租户端点，共享密码（≥12位）+ 用户名前缀（默认 `op-`），单事务 |
| 2026-04-11 | `POST /api/console/tenants` 新增必填字段 `username`/`password`，建租户时同步创建 `ROLE_TENANT_USER` 操作账号 |
| 2026-04-12 | `GET /api/console/meta/enums` 新增 6 个字典 key：`taskStatus` / `partitionStatus` / `workflowRunStatus` / `approvalType` / `outboxPublishStatus` / `aiPromptCategory`；OpenAPI 响应 schema 由 `CommonResponseObject` 改为精确的 `CommonResponseMetaEnums`，补全所有 34 个 key 的 schema 定义 |
| 2026-04-11 | 补齐 5 个新域（BatchWindow/BusinessCalendar/PipelineDefinition/TenantQuotaPolicy/ResourceQueue）Excel Upload/Preview/Apply 全套 schema（共 47 个），消除所有悬空 $ref；添加 Changelog 标识 |

## Common Headers

- `X-Request-Id`: optional, generated by server if absent
- `X-Trace-Id`: optional, generated by server if absent
- `X-Tenant-Id`: optional, can be used by gateway or upstream
- `X-Operator-Id`: optional in local mode, should be set by gateway in production
- `Idempotency-Key`: required for all write APIs
- `Authorization: Bearer <jwt>`: preferred authentication header for the console frontend
- Security headers are emitted by the backend on every console response:
  - `Content-Security-Policy`
  - `X-Frame-Options`
  - `X-Content-Type-Options`
  - `Referrer-Policy`
  - `Permissions-Policy`
  - `Strict-Transport-Security`

## Common Response Body

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {},
  "meta": {
    "requestId": "req-20260321T100000Z-ab12cd34",
    "traceId": "1a2b3c4d5e6f7890",
    "timestamp": "2026-03-21T10:00:00Z"
  }
}
```

## Standard Data Models

### CommonResponse<T>

- `code`: application-level status code
- `message`: human-readable status message
- `data`: typed payload, list, or documented primitive
- `meta`: request tracing metadata

### PageRequest

- `pageNo`: 1-based page number
- `pageSize`: number of items per page
- New paginated endpoints should use `PageRequest` instead of ad hoc limit fields when practical.

### PageResponse<T>

- `total`: total number of items
- `pageNo`: current page number
- `pageSize`: requested page size
- `items`: page items

### Common Header Mapping

- `X-Request-Id` and `X-Trace-Id` are propagated into response metadata.
- `X-Tenant-Id` is the tenant context input used by gateway or upstream.
- `X-Operator-Id` is the operator identity header for write actions and audit trails.
- `Idempotency-Key` is mandatory for write actions and must remain stable for retryable submissions.
- `Authorization: Bearer <jwt>` is the preferred console login/session credential.

## API Contract Rules

### Authentication And Authorization

- Console endpoints are protected by Spring Security.
- JWT bearer auth is the preferred login/session mechanism.
- `POST /api/console/auth/login` validates one of the seeded console accounts stored in the platform database and issues a JWT.
- Single-account login is single-session by default. A fresh login invalidates older JWTs for the same username and tenant.
<!-- Legacy `X-Console-Token` header auth was physically removed on 2026-04-30 (commit ff20c36f, S5-d). Only JWT + SSE ticket remain. -->
- `POST /api/console/auth/token` exchanges an authenticated console session for a JWT access token.
- `GET /api/console/auth/me` returns the current authenticated principal, including `menus` — the role-filtered sidebar tree produced by `ConsoleMenuRegistry`. Frontends should render navigation from this field rather than hard-coding menu items.
- `ROLE_ADMIN` can perform all write actions.
- `ROLE_AUDITOR` is read-only for operational views and queries.
- `ROLE_TENANT_ADMIN` can access config and worker operations, but not all write actions.
- `ROLE_TENANT_USER` can view job/file/workflow status, trigger jobs, and download exports, but cannot modify configurations or perform ops actions.
- AI endpoints require both role access and prompt authorization checks.

### Role Permission Matrix

| Operation | ADMIN | AUDITOR | TENANT_ADMIN | TENANT_USER |
|-----------|:-----:|:-------:|:------------:|:-----------:|
| Dashboard / Query / Meta / Realtime SSE | ✅ | ✅ | ✅ | ✅ |
| Job/File/Workflow status view | ✅ | ✅ | ✅ | ✅ |
| Scheduler snapshot view | ✅ | ✅ | ✅ | ✅ |
| Report Excel export | ✅ | ✅ | ✅ | ✅ |
| File pipeline observability | ✅ | ✅ | ✅ | ✅ |
| Job definition / workflow definition detail view | ✅ | ✅ | ✅ | ✅ |
| **Trigger job** | ✅ | ❌ | ❌ | ✅ |
| Config view (template / channel / Excel export) | ✅ | ✅ | ✅ | ❌ |
| Config modify (create / update / Excel import) | ✅ | ❌ | ✅ | ❌ |
| Config delete / reset | ✅ | ❌ | ❌ | ❌ |
| Ops actions (compensate / rerun / approve / dead-letter) | ✅ | ❌ | ❌ | ❌ |
| Notification channel / rule CRUD | ✅ | ❌ | ✅ | ✅ |
| Notification delivery log view | ✅ | ✅ | ✅ | ✅ |
| Config approval (submit / approve / reject) | ✅ | ❌ | ❌ | ❌ |
| Config approval detail view | ✅ | ✅ | ✅ | ❌ |
| Config sync (export / preview / import) | ✅ | ❌ | ❌ | ❌ |
| Config sync log view | ✅ | ❌ | ❌ | ❌ |
| Worker management (drain / restore) | ✅ | ❌ | ✅ | ❌ |
| Scheduler actions (pause-all / resume-all) | ✅ | ❌ | ❌ | ❌ |
| Alert management | ✅ | ❌ | ✅ | ❌ |
| **Tenant management (CRUD)** | ✅ | ❌ | ❌ | ❌ |
| **Tenant list / detail view** | ✅ | ❌ | ✅ | ❌ |
| **User account management (CRUD)** | ✅ | ❌ | ❌ | ❌ |

### Tenant Rules

- Login (`POST /api/console/auth/login`) does not require `tenantId`; the backend resolves tenant from the globally unique username.
- All other requests must carry an explicit `tenantId` either in the request body, query string, or upstream header mapping.
- Server-side tenant resolution is authoritative.
- Tenant mismatch must fail fast with `FORBIDDEN` or `STATE_CONFLICT` depending on the route semantics.
- Frontend must not treat tenant values as trusted client state.

### Idempotency Rules

- All write APIs require `Idempotency-Key`.
- The key must be stable for the same business action and unique across distinct actions.
- Duplicate write requests must be safe to retry.
- If idempotency handling is missing, the request should fail with `MISSING_IDEMPOTENCY_KEY`.

### Request And Response Rules

- Use `GET` for read-only endpoints.
- Use `POST` for commands, approvals, operations, and state transitions.
- Response bodies should use the common envelope:
  - `code`
  - `message`
  - `data`
  - `meta`
- `data` must be a typed DTO, list, or a documented primitive. Avoid anonymous `Map<String, Object>` in new endpoints.
- Approval lists, config release lists, secret version lists, change logs, and batch approval results use dedicated response DTOs instead of raw entities or generic maps.
- `meta` carries request tracing information and server timestamp.
- Treat all response strings as plain text. The frontend must not inject untrusted content through `innerHTML` or equivalent HTML sinks.
- New endpoints should prefer explicit DTOs over raw entities and should reuse `PageRequest` / `PageResponse` when pagination is required.
- Approval status values currently exposed by the backend are `PENDING`, `APPROVED`, `REJECTED`, and `EXECUTED`; the frontend should not assume `CLOSED` or `CANCELLED` are terminal approval states.

### Query And Pagination Rules

- Query endpoints should be filterable by `tenantId` and domain-specific identifiers.
- For list endpoints, prefer bounded result sets and explicit limit defaults.
- New list APIs should define pagination or at least `limit` semantics in both protocol and OpenAPI.
- Sorting and filtering should be explicit in request DTOs; do not rely on implicit database ordering.

### Error Semantics

- Validation failures use `VALIDATION_ERROR` or `INVALID_ARGUMENT`.
- Missing authentication maps to `UNAUTHORIZED`.
- Access violations map to `FORBIDDEN`.
- Missing resources map to `NOT_FOUND`.
- Concurrent or state mismatch cases map to `CONFLICT` or `STATE_CONFLICT`.
- Unimplemented or temporarily unavailable capabilities must not silently return success.

### Compatibility Rules

- Backward-compatible changes:
  - adding nullable fields
  - adding new endpoints
  - adding new enum values when documented
- Breaking changes:
  - removing fields
  - renaming fields
  - changing field semantics
  - changing idempotency or tenant behavior
- Breaking changes must update this document, `console-api.openapi.yaml`, and the frontend contract together.
- Prefer additive evolution over mutation of existing payloads.

### Text Safety Rules

- High-risk user-visible text fields are normalized before persistence or forwarding. These fields include reasons, titles, descriptions, prompts, and audit previews.
- Rich text is not a supported input format for the console API.
- Query and audit responses may contain escaped text. The frontend should render them as text nodes and not assume HTML markup.
- If a field needs to carry structured data, it must be encoded as JSON and validated as JSON, not as free-form HTML.

### Excel Config Maintenance Rules

- Excel maintenance is a controlled extension of the console, not a general-purpose file upload feature.
- All Excel-backed config flows must follow `upload -> preview -> apply -> export` through a dedicated adapter layer.
- Upload requests must use `multipart/form-data` with a single form field named `file`.
- `upload` returns an `uploadToken` and the token is the only handle used by `preview` and `apply`.
- `preview` must be a read-only check; it may validate and summarize rows, but it must not persist anything.
- `apply` must take the `uploadToken` in the path and the idempotency key header in the request headers.
- `export` remains a raw `.xlsx` download, not a JSON response.
- Each importable object also provides `GET /template` for downloading a blank template (for first-time import with no existing data).
- `file template config`, `file channel config`, `workflow definition / node / edge`, `job definition`, and `alert routing / notification policy` are the first-class editable domains.
- Excel templates must be user-facing edit forms, not raw database dumps.
- The main sheet should use frozen headers, required-field highlighting, enum dropdowns where practical, sample values, and automatic column sizing.
- A workbook should include a concise instruction sheet and, where useful, a dictionary sheet for enum values and a validation sheet for preview errors.
- Preview must report row numbers, column names, and validation reasons so the user can correct the sheet without guessing.
- `workflow definition / node / edge` and the safe subset of `job definition` are exportable and optionally importable only under strict validation.
- `config release`, `config change log`, `secret version`, `audit log`, `scheduler snapshot/history`, `worker registry`, and `outbox retry/delivery` are export-only records.
- Secret material, tokens, passwords, approval records, and other runtime facts must never be accepted as Excel import sources.
- Import payloads must be validated against a whitelist of editable columns before any write is applied.
- Excel processing logic must stay inside a dedicated adapter layer; controllers must only handle HTTP boundaries.

### Frontend Security Rules

- Use text binding for all server-provided strings.
- Do not render API strings via `innerHTML`, `v-html`, `dangerouslySetInnerHTML`, or similar APIs unless the content has been explicitly sanitized and approved.
- Do not trust role claims for security decisions on the client. They are only for routing and menu visibility.
- Treat `401` as login/session failure and `403` as authorization failure. Do not infer authorization by inspecting response body text.

## URL Design Conventions

### HTTP Method Assignment

| Method | Semantics | Typical usage |
|--------|-----------|---------------|
| `GET` | Read, never mutates state | Query, detail, list, export |
| `POST` | Create a resource **or** trigger a command/action | Create, trigger, approve, cancel, toggle |
| `PUT` | Idempotent full replacement of an existing resource | Update resource definition (replaces the whole record) |
| `DELETE` | Remove a resource | Hard delete |

`PATCH` is used exclusively for **enable/disable** operations:
- `PATCH /{id}` — single resource toggle, body: `EnabledPatchRequest { tenantId, enabled }`
- `PATCH /batch` — batch toggle (up to 200 ids), body: `BatchEnabledPatchRequest`

Currently applies to: `job-definitions`, `workflow-definitions`, `file-channels`, `file-templates`.  
All other partial updates are handled by a dedicated `PUT` endpoint or an action endpoint (see below).

### Resource URL Naming

1. **All resource paths use plural kebab-case.**  
   `GET /api/console/job-definitions`, `GET /api/console/queries/instances`, `GET /api/console/tenants/quota`

2. **Do not use singular for a resource collection**, even if the context is "my tenant" or "meta info".  
   Correct: `/api/console/queries/instances`, `/api/console/tenants/…`  
   Wrong: `/api/console/query/instance`, `/api/console/tenant/…`

3. **Exception — namespace prefixes are singular by convention.**  
   `/api/console/meta/enums` — "meta" here is a namespace qualifier, not a collection.  
   `/api/console/auth/login` — "auth" is a namespace, not a list of auth objects.

### Action (Command) Endpoints

For state-change operations that are not a simple CRUD (toggle, cancel, approve, drain, rerun, replay, etc.) use the **action suffix pattern**:

```
POST /api/console/{resources}/{id}/{action}
```

Examples:
- `POST /api/console/job-definitions/{id}/toggle`
- `POST /api/console/workers/{workerCode}/drain`
- `POST /api/console/approvals/{approvalNo}/approve`

Rationale: these commands are not idempotent replacements of a resource (which would be `PUT`). They represent an intent/event. GitHub API, Stripe API and similar industry APIs follow the same convention.

### Batch Action Endpoints

Batch operations on the same resource use the **`/batch-{action}`** path pattern (hyphenated, same level as the resource root):

```
POST /api/console/{resources}/batch-{action}
```

Examples:
- `POST /api/console/approvals/batch-approve`
- `POST /api/console/approvals/batch-reject`
- `POST /api/console/job-definitions/batch-toggle`

**Do not** use a path-separator style (`/batch/approve`). All batch endpoints must use the hyphenated form.

### Summary Table

| Pattern | Example | Correct |
|---------|---------|---------|
| Resource collection | `/api/console/job-definitions` | ✅ plural kebab-case |
| Resource detail | `/api/console/job-definitions/{id}` | ✅ |
| Create resource | `POST /api/console/job-definitions` | ✅ |
| Update resource | `PUT /api/console/job-definitions/{id}` | ✅ full replacement |
| Delete resource | `DELETE /api/console/job-definitions/{id}` | ✅ |
| Action on resource | `POST /api/console/job-definitions/{id}/toggle` | ✅ verb suffix |
| Batch action | `POST /api/console/approvals/batch-approve` | ✅ hyphenated |
| Query namespace | `/api/console/queries/instances` | ✅ plural |
| Meta namespace | `/api/console/meta/enums` | ✅ singular namespace exception |
| ❌ Singular collection | `/api/console/query/instance` | ❌ |
| ❌ Verb path-sep batch | `POST /api/console/approvals/batch/approve` | ❌ |

---

## Current Route Catalog

### Ops

- `POST /api/console/auth/login`
- `POST /api/console/auth/token`
- `GET /api/console/auth/me`
- `GET /api/console/ops/summary`
- `GET /api/console/ops/summary/events`

Default seeded accounts:

| Username | Password | Roles | Description |
|----------|----------|-------|-------------|
| `admin` | `admin123` | `ROLE_ADMIN`, `ROLE_AUDITOR`, `ROLE_TENANT_ADMIN` | Super admin |
| `auditor` | `auditor123` | `ROLE_AUDITOR` | Read-only auditor |
| `config-admin` | `config123` | `ROLE_TENANT_ADMIN` | Configuration manager |
| `tenant-user` | `tenant123` | `ROLE_TENANT_USER` | Tenant business user |

Username is globally unique. Login requires only `username` + `password`; tenant is resolved from the account record automatically.

Minimal login page:

- `GET /console-login.html`

`POST /api/console/auth/login` accepts:

- `username` required
- `password` required
- Username is globally unique; tenant is resolved from the account record automatically, no need to pass `tenantId`.
- Repository does not ship plaintext default passwords; only password hashes are stored in the platform database.

Session rule:

- The same seeded account keeps only one active JWT per tenant.
- A newer `POST /api/console/auth/login` or `POST /api/console/auth/token` replaces the previous JWT for that account.

`GET /api/console/ops/summary` is the first-screen operational snapshot. The server requires **`tenantId` as a query parameter** (not only `X-Tenant-Id`). The response is a typed summary payload inside `CommonResponse` and should be treated as the control plane entry for the console home page. It includes:

- pending approvals
- open alerts and critical alerts
- running and failed jobs
- SLA breach count
- worker online, draining, and offline/decommissioned distribution
- outbox retry backlog and delivery failures

`GET /api/console/ops/summary/events` is the first-screen realtime stream. It emits `ops-summary-updated` payloads with the full `ConsoleOpsSummaryResponse` snapshot after key write actions succeed. The frontend should use it to invalidate or replace the cached summary on the home page.

When the shared realtime channel receives a `summaryRefresh=true` envelope for `ops-summary`, the console consumer reloads the latest summary from the database and emits `ops-summary-updated` instead of forwarding the trigger event verbatim. In other words, the trigger signal is internal, and `ops-summary-updated` is the client-facing data update event.

Query parameters:

- `heartbeatMillis` is optional and controls the SSE keepalive interval.
- `initialSnapshot` is optional and defaults to `true`. When set to `false`, the stream only listens for later updates and does not emit an immediate snapshot after subscription.

Frontend integration rule:

1. Load `GET /api/console/ops/summary?tenantId=...` first and render the initial homepage state from that response.
2. Open `GET /api/console/ops/summary/events?tenantId=...` only after the first snapshot has been rendered.
3. On `ops-summary-updated`, replace the cached summary with the event payload directly when possible.
4. If the UI uses a query/cache layer, `setQueryData` or an equivalent cache replacement is preferred over an unconditional refetch, because the event already carries the full snapshot.
5. Use `heartbeat` only as a keepalive signal. Do not treat it as a data update.
6. If the SSE connection closes or errors out, reconnect automatically and fall back to a summary refetch if the client cannot guarantee event continuity.

Deployment note:

- The console SSE layer uses Redis Pub/Sub as the shared event source. Each `console-api` instance subscribes to the same channel, so every instance sees the same realtime events without sticky session.
- The local SSE hub is still in-process, but it is fed from Redis rather than from a single instance's write path.
- The realtime payload includes `originInstanceId` so the instance that already handled the local write path can ignore its own broadcast echo.
- `BATCH_CONSOLE_INSTANCE_ID` should be set per replica when possible so origin filtering remains stable across restarts.

### Job Definitions

- `GET /api/console/queries/job-definitions`
- `POST /api/console/job-definitions`
- `GET /api/console/job-definitions/{id}`
- `PUT /api/console/job-definitions/{id}`
- `PATCH /api/console/job-definitions/{id}` — enable/disable; body: `EnabledPatchRequest { tenantId, enabled }`
- `PATCH /api/console/job-definitions/batch` — batch enable/disable up to 200; body: `BatchEnabledPatchRequest`
- `POST /api/console/job-definitions/{id}/copy`
- `POST /api/console/job-definitions/{id}/clone` — clone with field overrides via `JobDefinitionCopyRequest` body (jobName, workerGroup, queueCode, scheduleExpr, retryPolicy, etc.)
- All write operations require `ROLE_ADMIN`. Read operations allow `ROLE_AUDITOR`, `ROLE_TENANT_ADMIN`, and `ROLE_TENANT_USER`.
- `copy` uses query params `tenantId` (required) and `newJobCode` (required); the cloned definition is created with `enabled=false`.
- `clone` accepts a JSON body with overridable fields; unset fields inherit from the source definition.

### Workflow Definitions

- `GET /api/console/queries/workflow-definitions`
- `POST /api/console/workflow-definitions`
- `GET /api/console/workflow-definitions/{id}`
- `PUT /api/console/workflow-definitions/{id}`
- `PATCH /api/console/workflow-definitions/{id}` — enable/disable; body: `EnabledPatchRequest { tenantId, enabled }`
- `POST /api/console/workflow-definitions/{id}/validate`
- `GET /api/console/workflow-definitions/events`
- Create and update are transactional: definition, nodes, and edges are persisted or replaced atomically.
- `validate` runs Kahn topological sort and checks for cycles, START/END node presence, and reachability. Returns a validation result payload, not a simple boolean.

### Compatibility Aliases

- `GET /api/console/queries/pipeline-definitions`
- `GET /api/console/queries/pipeline-definitions/{id}`
- `GET /api/console/file-pipeline-observability`
- `GET /api/console/file-pipeline-observability/{id}`
- These are compatibility aliases for older callers. They return the same file pipeline list/detail payloads as `/api/console/queries/file-pipelines` and `/api/console/queries/file-pipelines/{id}`.
- Delete cascades to nodes and edges.
- `GET /api/console/workflow-definitions/events` subscribes to the workflow-definition realtime stream. It emits change signals for create, update, toggle, and delete operations using event types such as `workflow-definition-created`, `workflow-definition-updated`, `workflow-definition-toggled`, and `workflow-definition-deleted`.

### Pipeline Definitions

- `GET /api/console/pipeline-definitions`
- `POST /api/console/pipeline-definitions`
- `GET /api/console/pipeline-definitions/{id}`
- `PUT /api/console/pipeline-definitions/{id}`
- `POST /api/console/pipeline-definitions/{id}/toggle`
- `GET /api/console/pipeline-definitions/events`
- Create and update are transactional: definition and step list are persisted or replaced atomically.
- Detail response (`PipelineDefinitionDetailResponse`) includes the ordered step list.
- `GET /api/console/pipeline-definitions/events` is the domain-level realtime entry for pipeline editing screens. It subscribes to the same event hub used by the other realtime console streams, but keeps the route close to the pipeline-definition UX.

### Workers

- `POST /api/console/workers/{workerCode}/drain`
- `POST /api/console/workers/{workerCode}/force-offline`
- `POST /api/console/workers/{workerCode}/takeover`
- `POST /api/console/workers/{workerCode}/warmup`
- `GET /api/console/workers/{workerCode}/claimed-tasks`
- `GET /api/console/workers/events`
- `GET /api/console/workers/events` subscribes to worker registry realtime changes. It emits `worker-updated` signals after drain, force-offline, or takeover actions succeed.
- Worker list query is served by `GET /api/console/queries/workers`.
- `takeover` is the explicit manual handoff path: it requeues in-flight tasks and marks the worker decommissioned immediately.
- `force-offline` keeps the stronger ops semantics and should be treated as an emergency offlining command.

### Alerts

- `POST /api/console/alerts/{alertId}/ack`
- `POST /api/console/alerts/{alertId}/silence`
- `POST /api/console/alerts/{alertId}/close`
- `GET /api/console/alerts/events`
- `GET /api/console/alerts/events` subscribes to alert governance realtime changes. It emits `alert-updated` signals after ack, silence, or close actions succeed.
- Alert list query is served by `GET /api/console/queries/alerts`.
- `ack` is the UI-facing confirm action. It maps to backend alert status `ACKED`.
- `silence` maps to backend alert status `SUPPRESSED`.
- `close` maps to backend alert status `CLOSED`.

### Job Instances and Workflow Runs

- `GET /api/console/stream/job-instances/events`
- `GET /api/console/workflow-runs/events`
- `POST /api/console/jobs/trigger`
- `POST /api/console/jobs/compensations`
- `POST /api/console/jobs/compensate`
- `POST /api/console/jobs/rerun`
- `POST /api/console/jobs/dead-letters/replay`
- `POST /api/console/jobs/tasks/replay`
- `POST /api/console/jobs/partitions/replay`
- `POST /api/console/jobs/catch-up/approve`
- `POST /api/console/jobs/batch-days/{bizDate}/catchup`
- `GET /api/console/stream/job-instances/events` exposes the shared realtime hub for job-instance run-state pages. `GET /api/console/workflow-runs/events` is the domain-specific shortcut for the workflow-run screen.
- `job-instances` emits `job-instance-updated` signals and `workflow-runs` emits `workflow-run-updated` signals after job and workflow-run write actions succeed.

### Outbox

- `GET /api/console/stream/outbox-retries/events`
- `GET /api/console/stream/outbox-deliveries/events`
- `GET /api/console/stream/outbox-retries/events` subscribes to outbox retry activity and emits `outbox-retry-updated` signals.
- `GET /api/console/stream/outbox-deliveries/events` subscribes to outbox delivery activity and emits `outbox-delivery-updated` signals.

### Instances

- `POST /api/console/instances/{id}/cancel`
- `POST /api/console/instances/{id}/terminate`
- `POST /api/console/instances/{id}/partitions/retry-failed`
- `POST /api/console/instances/partitions/{id}/cancel`
- `POST /api/console/instances/partitions/{id}/retry`
- `cancel` on an instance is allowed only for `CREATED`, `WAITING`, or `READY` states.
- `terminate` on an instance is allowed only for `RUNNING` state.
- `cancel` on a partition follows the same allowed states as instance cancel.
- `retry` on a partition is allowed only for `FAILED` state; `retryCount` is incremented.
- `retry-failed` on an instance retries all current `FAILED` partitions and returns `requested/retried/conflicts/partitionIds`; each partition still uses optimistic locking.
- All operations use optimistic locking via `version`.

### Triggers

- `GET /api/console/ops/triggers`
- `POST /api/console/ops/triggers/{jobCode}/register`
- `POST /api/console/ops/triggers/{jobCode}/unregister`
- `POST /api/console/ops/triggers/{jobCode}/pause`
- `POST /api/console/ops/triggers/{jobCode}/resume`
- `register` loads the job definition from DB and registers it into Quartz; safe to call again to update an existing trigger.
- `unregister` removes the Quartz job entry; does not affect the job definition record.
- Trigger list response includes `status`, `previousFireTime`, and `nextFireTime` per job.

### Meta

- `GET /api/console/meta/enums`
- `GET /api/console/meta/queues`
- `GET /api/console/meta/calendars`
- `GET /api/console/meta/windows`
- `GET /api/console/meta/worker-groups`
- `enums` returns all platform enum dictionaries. Each key maps to an ordered `[{code, label}]` list:

  | Key | 说明 |
  |---|---|
  | `triggerType` | 触发类型 |
  | `scheduleType` | 调度类型（CRON / FIXED_RATE / MANUAL）|
  | `triggerMode` | 触发模式（SCHEDULED / API / MANUAL / EVENT / MIXED）|
  | `catchUpPolicy` | 补跑策略 |
  | `jobType` | 作业类型 |
  | `shardStrategy` | 分片策略 |
  | `retryPolicy` | 重试策略 |
  | `taskStatus` | 任务状态 |
  | `partitionStatus` | 分区状态 |
  | `instanceStatus` | 作业实例状态 |
  | `workflowType` | 工作流类型 |
  | `workflowNodeType` | 工作流节点类型 |
  | `edgeType` | 工作流边类型 |
  | `workflowRunStatus` | 工作流运行状态 |
  | `pipelineType` | 流水线类型 |
  | `channelType` | 文件通道类型 |
  | `authType` | 通道认证类型 |
  | `receiptPolicy` | 回执策略 |
  | `fileTemplateType` | 文件模板类型 |
  | `fileTemplateFormat` | 文件格式 |
  | `endStrategy` | 批量窗口结束策略 |
  | `outOfWindowAction` | 窗口外动作 |
  | `holidayStrategy` | 节假日顺延规则 |
  | `dayType` | 日历日类型 |
  | `queueType` | 资源队列类型 |
  | `priorityPolicy` | 优先级策略 |
  | `severity` | 告警级别 |
  | `alertStatus` | 告警状态（OPEN / ACKED / SUPPRESSED / CLOSED）|
  | `approvalStatus` | 审批状态 |
  | `approvalType` | 审批类型（CATCH_UP / COMPENSATION / DLQ_REPLAY / DOWNLOAD）|
  | `configStatus` | 配置发布状态 |
  | `workerStatus` | Worker 注册状态 |
  | `outboxPublishStatus` | Outbox 投递状态 |
  | `aiPromptCategory` | AI Prompt 分类 |
- `queues`, `calendars`, and `windows` return simplified lists (`code` + `name`) for use as dropdown options; all require `tenantId` query param.
- `worker-groups` returns deduplicated group codes from active worker registrations.
- All meta endpoints allow `ROLE_ADMIN`, `ROLE_AUDITOR`, `ROLE_TENANT_ADMIN`, and `ROLE_TENANT_USER`.

### Queues

- `GET /api/console/queues`
- `POST /api/console/queues`
- `PUT /api/console/queues/{id}`
- `POST /api/console/queues/{id}/toggle`
- All write operations require `ROLE_ADMIN`.
- `queue_code` uniqueness is enforced on create.

### Batch Windows

- `GET /api/console/batch-windows`
- `POST /api/console/batch-windows`
- `PUT /api/console/batch-windows/{id}`
- `POST /api/console/batch-windows/{id}/toggle`
- All write operations require `ROLE_ADMIN`.
- `window_code` uniqueness is enforced on create.
- Window definition includes start/end time, cross-day policy, and out-of-window action.

### Calendars

- `GET /api/console/calendars`
- `POST /api/console/calendars`
- `PUT /api/console/calendars/{id}`
- `POST /api/console/calendars/{id}/toggle`
- `GET /api/console/calendars/{id}/holidays`
- `POST /api/console/calendars/{id}/holidays`
- `PUT /api/console/calendars/{id}/holidays/{holidayId}`
- `DELETE /api/console/calendars/{id}/holidays/{holidayId}`
- `calendar_code` uniqueness is enforced on create.
- Holiday create supports batch import (multiple entries in one request body).
- Holiday operations validate calendar tenant ownership before write.

### Scheduler

- `GET /api/console/scheduler/status`
- `POST /api/console/scheduler/pause-all`
- `POST /api/console/scheduler/resume-all`
- `GET /api/console/scheduler/snapshot`
- `GET /api/console/scheduler/snapshot/history`
- `status` returns one of `STARTED`, `PAUSED`, `STANDBY`, or `SHUTDOWN`.
- `pause-all` / `resume-all` apply globally to all Quartz triggers; use with caution in production.
- Scheduler snapshot responses keep the stable display slices `policies / queues / workers`; the frontend should treat those lists as the primary render contract.

### Quota Policies

- `GET /api/console/quota-policies`
- `POST /api/console/quota-policies`
- `PUT /api/console/quota-policies/{id}`
- `POST /api/console/quota-policies/{id}/toggle`
- All write operations require `ROLE_ADMIN`.
- `policy_code` uniqueness is enforced on create.
- Policy definition includes concurrent cap, QPS, fair-share configuration, burst limit, and sliding window hours.

### Workflow Runs

- `POST /api/console/workflow-runs/{id}/cancel`
- `POST /api/console/workflow-runs/{id}/terminate`
- `POST /api/console/workflow-runs/{id}/skip-node`
- `cancel` is allowed for `CREATED` or `RUNNING` states → transitions to `TERMINATED`.
- `terminate` is allowed for `RUNNING` state → transitions to `TERMINATED`.
- `skip-node` requires `nodeCode` query param and is allowed only for `FAILED` nodes → transitions to `SKIPPED`.

### Dashboard

- `GET /api/console/dashboard/job-stats`
- `GET /api/console/dashboard/trigger-stats`
- `GET /api/console/dashboard/worker-load`
- `GET /api/console/dashboard/alert-trend`
- `GET /api/console/dashboard/sla-compliance`
- `GET /api/console/dashboard/sla-report` — per-job SLA breakdown: avg/max duration, success/failure/breach counts
- `GET /api/console/dashboard/execution-progress` — execution progress by `jobCode` + `bizDate`, returns instance partition completion percentage
- `GET /api/console/dashboard/tenant-usage` — tenant resource usage: job/workflow/channel/template definition counts + recent instance/file counts (`days` defaults to `30`)
- All endpoints require `tenantId` query param. `days` defaults to `7` where applicable (except `tenant-usage` which defaults to `30`).
- `job-stats`: instance status distribution + daily execution trend.
- `trigger-stats`: trigger type distribution + daily trend.
- `worker-load`: worker status/group distribution + active partition breakdown.
- `alert-trend`: alert severity distribution + daily trend.
- `sla-compliance`: violation/on-time counts + average duration + daily trend.
- Allow `ROLE_ADMIN`, `ROLE_AUDITOR`, `ROLE_TENANT_ADMIN`, and `ROLE_TENANT_USER`.

### File Channels

- `GET /api/console/file-channels`
- `POST /api/console/file-channels`
- `GET /api/console/file-channels/{id}`
- `PUT /api/console/file-channels/{id}`
- `PATCH /api/console/file-channels/{id}` — enable/disable; body: `EnabledPatchRequest { tenantId, enabled }`
- Read requires `ROLE_ADMIN`, `ROLE_TENANT_ADMIN`, or `ROLE_AUDITOR`. Write requires `ROLE_TENANT_ADMIN` or above. Delete requires `ROLE_ADMIN`.

### File Templates

- `GET /api/console/file-templates`
- `POST /api/console/file-templates`
- `POST /api/console/file-templates/mapping-draft`
- `GET /api/console/file-templates/{id}`
- `PUT /api/console/file-templates/{id}`
- `PATCH /api/console/file-templates/{id}` — enable/disable; body: `EnabledPatchRequest { tenantId, enabled }`
- Same permission rules as File Channels.

### Jobs

- `POST /api/console/jobs/trigger` — supports `dryRun: true` in request body for sandbox validation (validates tenant, jobCode, bizDate, triggerType, enabled status without executing)
- `POST /api/console/jobs/batch-trigger` — batch trigger up to 50 jobs in one request; accepts `List<TriggerRequest>`, requires `Idempotency-Key`
- `POST /api/console/jobs/compensations`
- `POST /api/console/jobs/compensate`
- `POST /api/console/jobs/rerun`
- `POST /api/console/jobs/dead-letters/replay`
- `POST /api/console/jobs/tasks/replay`
- `POST /api/console/jobs/partitions/replay`
- `POST /api/console/jobs/catch-up/approve`
- `POST /api/console/jobs/batch-days/{bizDate}/catchup`

### Approvals

- `POST /api/console/approvals/{approvalNo}/approve`
- `POST /api/console/approvals/{approvalNo}/reject`
- `POST /api/console/approvals/batch-approve`
- `POST /api/console/approvals/batch-reject`
- Approval query views should use `ConsoleApprovalCommandResponse` and `ConsoleBatchApprovalResultResponse`, not raw entities.

### Config

- `GET /api/console/config/releases`
- `POST /api/console/config/releases`
- `GET /api/console/config/releases/{releaseId}`
- `POST /api/console/config/releases/{releaseId}/publish`
- `POST /api/console/config/releases/{releaseId}/gray`
- `POST /api/console/config/releases/{releaseId}/rollback`
- `GET /api/console/config/secrets`
- `GET /api/console/config/secrets/{secretVersionId}`
- `POST /api/console/config/secrets/rotate`
- `GET /api/console/config/dependencies` — query config item dependencies; params: `tenantId`, `configType` (QUEUE / CALENDAR / WINDOW / WORKER_GROUP), `configCode`
- `GET /api/console/config/releases/diff` — diff two release versions; params: `tenantId`, `releaseIdA`, `releaseIdB`
- `GET /api/console/config/change-logs`
- `GET /api/console/config/file-templates/excel/template`
- `GET /api/console/config/file-templates/excel/export`
- `POST /api/console/config/file-templates/excel/upload`
- `GET /api/console/config/file-templates/excel/preview/{uploadToken}`
- `GET /api/console/config/file-templates/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/file-templates/excel/apply/{uploadToken}`
- `GET /api/console/config/file-channels/excel/template`
- `GET /api/console/config/file-channels/excel/export`
- `POST /api/console/config/file-channels/excel/upload`
- `GET /api/console/config/file-channels/excel/preview/{uploadToken}`
- `GET /api/console/config/file-channels/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/file-channels/excel/apply/{uploadToken}`
- `GET /api/console/config/workflows/excel/template`
- `GET /api/console/config/workflows/excel/export`
- `POST /api/console/config/workflows/excel/upload`
- `GET /api/console/config/workflows/excel/preview/{uploadToken}`
- `GET /api/console/config/workflows/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/workflows/excel/apply/{uploadToken}`
- `GET /api/console/config/job-definitions/excel/template`
- `GET /api/console/config/job-definitions/excel/export`
- `POST /api/console/config/job-definitions/excel/upload`
- `GET /api/console/config/job-definitions/excel/preview/{uploadToken}`
- `GET /api/console/config/job-definitions/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/job-definitions/excel/apply/{uploadToken}`
- `GET /api/console/config/alert-routings/excel/template`
- `GET /api/console/config/alert-routings/excel/export`
- `POST /api/console/config/alert-routings/excel/upload`
- `GET /api/console/config/alert-routings/excel/preview/{uploadToken}`
- `GET /api/console/config/alert-routings/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/alert-routings/excel/apply/{uploadToken}`
- `GET /api/console/config/batch-windows/excel/template`
- `GET /api/console/config/batch-windows/excel/export`
- `POST /api/console/config/batch-windows/excel/upload`
- `GET /api/console/config/batch-windows/excel/preview/{uploadToken}`
- `GET /api/console/config/batch-windows/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/batch-windows/excel/apply/{uploadToken}`
- `GET /api/console/config/business-calendars/excel/template`
- `GET /api/console/config/business-calendars/excel/export`
- `POST /api/console/config/business-calendars/excel/upload`
- `GET /api/console/config/business-calendars/excel/preview/{uploadToken}`
- `GET /api/console/config/business-calendars/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/business-calendars/excel/apply/{uploadToken}`
- `GET /api/console/config/pipeline-definitions/excel/template`
- `GET /api/console/config/pipeline-definitions/excel/export`
- `POST /api/console/config/pipeline-definitions/excel/upload`
- `GET /api/console/config/pipeline-definitions/excel/preview/{uploadToken}`
- `GET /api/console/config/pipeline-definitions/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/pipeline-definitions/excel/apply/{uploadToken}`
- `GET /api/console/config/resource-queues/excel/template`
- `GET /api/console/config/resource-queues/excel/export`
- `POST /api/console/config/resource-queues/excel/upload`
- `GET /api/console/config/resource-queues/excel/preview/{uploadToken}`
- `GET /api/console/config/resource-queues/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/resource-queues/excel/apply/{uploadToken}`
- `GET /api/console/config/quota-policies/excel/template`
- `GET /api/console/config/quota-policies/excel/export`
- `POST /api/console/config/quota-policies/excel/upload`
- `GET /api/console/config/quota-policies/excel/preview/{uploadToken}`
- `GET /api/console/config/quota-policies/excel/preview/{uploadToken}/workbook`
- `POST /api/console/config/quota-policies/excel/apply/{uploadToken}`
- Config list views should use typed response DTOs for releases, secrets, and change logs.
- Excel maintenance currently covers 10 editable config domains: `file template`, `file channel`, `workflow`, `job definition`, `alert routing`, `batch window`, `business calendar`, `pipeline definition`, `resource queue`, and `tenant quota policy`.
- Each editable Excel domain follows the same HTTP shape: `template -> export -> upload -> preview -> preview workbook -> apply`.
- `GET /preview/{uploadToken}/workbook` downloads a corrected workbook family that includes populated `VALIDATION` rows and cell comments pointing at the failing cells.
- The preview workbook is intentionally re-importable: comments and extra sheets must not break a subsequent `upload`.
- Excel maintenance for `file template config` and `file channel config` follows the dedicated adapter flow with single-sheet maintenance semantics.
- Excel maintenance for `resource queue`, `tenant quota policy`, `batch window`, and `alert routing` follows the same single-sheet maintenance flow.
- Excel maintenance for `workflow definition / node / edge`, `business calendar / holiday`, and `pipeline definition / step definition` follows the same dedicated adapter flow but keeps multiple sheets aligned by shared keys.
- Excel maintenance for the safe subset of `job definition` follows the same dedicated adapter flow, but only allows white-listed mutable columns and update-only apply semantics.
- Export workbooks for editable Excel flows must stay as recoverable templates: data sheet first, then README / DICT / VALIDATION sheets, so users can edit and re-upload the same file family.
- Preview workbook generation is read-only: it reflects validation findings from the current upload session and does not write configuration data.
- `GET /api/console/reports/excel/config-releases`
- `GET /api/console/reports/excel/secrets`
- `GET /api/console/reports/excel/change-logs`
- `GET /api/console/reports/excel/audits`
- `GET /api/console/reports/excel/scheduler-snapshot`
- `GET /api/console/reports/excel/scheduler-history`
- `GET /api/console/reports/excel/workers`
- `GET /api/console/reports/excel/outbox-retries`
- `GET /api/console/reports/excel/outbox-deliveries`
- Report Excel exports are export-only snapshots or logs. They do not accept upload / preview / apply flows.

### Tenant Management

- `GET /api/console/tenants` — list tenants (keyword, status filter, paginated)
- `GET /api/console/tenants/{tenantId}` — tenant detail
- `POST /api/console/tenants` — create tenant
- `PUT /api/console/tenants/{tenantId}` — update tenant name / description
- `POST /api/console/tenants/{tenantId}/suspend` — suspend tenant
- `POST /api/console/tenants/{tenantId}/activate` — reactivate tenant
- Tenant is the platform-level isolation unit. Must exist in `batch.tenant` before any config can be pushed.
- `tenantId` format: `^[a-z0-9][a-z0-9\-]*[a-z0-9]$` (lowercase, alphanumeric, hyphens).
- `status` values: `ACTIVE`, `SUSPENDED`.
- `GET` list and detail require `ROLE_ADMIN` or `ROLE_TENANT_ADMIN`. All write operations require `ROLE_ADMIN`.
- Response fields: `id`, `tenantId`, `tenantName`, `status`, `description`, `createdBy`, `createdAt`, `updatedAt`.

### User Account Management

- `GET /api/console/users` — list accounts (tenantId filter, keyword search by username/displayName, paginated)
- `GET /api/console/users/{id}` — account detail
- `PUT /api/console/users/{id}` — update displayName and authoritiesCsv
- `POST /api/console/users/{id}/reset-password` — reset password (Argon2id hash stored, raw password never persisted)
- `POST /api/console/users/{id}/enable` — enable account
- `POST /api/console/users/{id}/disable` — disable account
- All operations require `ROLE_ADMIN`.
- `username` is globally unique (case-insensitive). Format: alphanumeric + `.` `_` `-`, min 2 chars.
- `authoritiesCsv` is a comma-separated list of role names (e.g. `ROLE_TENANT_ADMIN,ROLE_AUDITOR`); default is `ROLE_USER`.
- Password minimum 8 characters; only the Argon2id hash is stored, raw password is discarded after hashing.
- Response fields: `id`, `tenantId`, `username`, `displayName`, `authoritiesCsv`, `enabled`, `createdAt`, `updatedAt`. `passwordHash` is never exposed.
- Create request fields: `tenantId` (required), `username` (required), `displayName`, `password` (required, min 8), `authoritiesCsv`.
- The seeded default accounts (see Ops section) are `admin`, `auditor`, `config-admin`, `tenant-user` under `default-tenant`.

### Tenant Config Init

- `POST /api/console/config/tenant-init`
- Batch-initializes or updates configuration for multiple tenants in one request.
- `targetTenantIds` is resolved from `batch.tenant WHERE status = 'ACTIVE'` when omitted (broadcast to all active tenants).
- Supports two modes: `SKIP_EXISTING` (default, create missing only) and `UPSERT` (create or update).
- Supports `dryRun` mode: when `true`, performs read and validation only without executing writes.
- Covers all 10 config types: job definitions, workflow definitions, pipeline definitions, file channels, file templates, resource queues, batch windows, business calendars, quota policies, alert routings.
- Response includes `batchOperationId` for audit correlation, per-tenant results with per-item details (code, action, errorMessage) for each of the 10 config types.
- Requires `ROLE_ADMIN`.
- Requires `Idempotency-Key` header.

### Tenant Config Copy

- `POST /api/console/config/tenant-copy`
- Reads configuration from a source tenant and pushes it to one or more target tenants.
- Request body: `sourceTenantId`, `targetTenantIds` (max 50), optional `configTypes` (subset of `JOB_DEFINITION`, `WORKFLOW_DEFINITION`, `PIPELINE_DEFINITION`, `FILE_CHANNEL`, `FILE_TEMPLATE`, `RESOURCE_QUEUE`, `BATCH_WINDOW`, `BUSINESS_CALENDAR`, `QUOTA_POLICY`, `ALERT_ROUTING`; empty means all 10), `mode` (default `SKIP_EXISTING`), `dryRun`.
- Internally reads source tenant's configs and delegates to the tenant-init logic.
- Response format is identical to `tenant-init`.
- Requires `ROLE_ADMIN`.
- Requires `Idempotency-Key` header.

### Files

- `POST /api/console/files/archive`
- `DELETE /api/console/files/{fileId}` — delete a file record
- `POST /api/console/files/redispatch`
- `POST /api/console/files/presign-download`
- `POST /api/console/files/arrival-groups/action`
- `POST /api/console/files/presign-upload`
- `PUT /api/console/files/{fileId}/content`
- `POST /api/console/files/{fileId}/confirm-arrival`
- `GET /api/console/files/{fileId}/download`
- `GET /api/console/files/{fileId}/errors/export` — export file error records as CSV (param: `tenantId`, optional `errorStage`)
- File operation endpoints use `ConsoleFileOperationResponse`. `POST /api/console/files/presign-download` uses `ConsolePresignDownloadResponse`, where `approvalNo` and `downloadUrl` are mutually exclusive and one side may be `null` depending on whether the request goes through approval submission or direct presign execution.
- Download success responses are raw file bytes with `Content-Disposition: attachment`; validation or state errors still return the normal JSON error envelope via the global exception handler.

### AI

- `POST /api/console/ai/chat`

### System Parameters

- `GET /api/console/system-parameters` — list all parameters for tenant
- `GET /api/console/system-parameters/value` — get single parameter value by `key`
- `PUT /api/console/system-parameters` — upsert parameter (body: `key`, `value`, `description`)
- `DELETE /api/console/system-parameters` — delete parameter by `key`
- All endpoints require `ROLE_ADMIN`.
- Parameters are cached in Redis (`sys-param:{tenantId}:{key}`, TTL 30min); writes invalidate cache immediately.

### Webhooks

- `GET /api/console/webhooks` — list webhook subscriptions for tenant
- `GET /api/console/webhooks/{id}` — subscription detail
- `POST /api/console/webhooks` — create subscription (body: `name`, `callbackUrl`, `eventTypes[]`, `secret`, `enabled`)
- `PUT /api/console/webhooks/{id}` — update subscription
- `DELETE /api/console/webhooks/{id}` — delete subscription
- `GET /api/console/webhooks/delivery-logs` — query delivery log history (`subscriptionId` optional, `limit` default 20)
- Webhook delivery uses HMAC-SHA256 signature in `X-Webhook-Signature` header; payload is JSON with `eventType`, `tenantId`, `payload`, `timestamp`.
- Delivery retries up to 3 times with exponential backoff (2s, 4s, 8s).
- Permissions: read endpoints allow `ROLE_ADMIN`, `ROLE_TENANT_ADMIN`, `ROLE_TENANT_USER`; create/update/delete require `ROLE_ADMIN` or `ROLE_TENANT_ADMIN`.

### Resource Tags

- `GET /api/console/tags` — list tags for a specific resource (`resourceType`, `resourceCode` required)
- `GET /api/console/tags/search` — search resources by tag (`tagKey` required, `tagValue` optional)
- `GET /api/console/tags/keys` — list all distinct tag keys for tenant
- `POST /api/console/tags` — upsert tag (body: `resourceType`, `resourceCode`, `tagKey`, `tagValue`)
- `DELETE /api/console/tags` — delete single tag (`resourceType`, `resourceCode`, `tagKey` required)
- `DELETE /api/console/tags/all` — delete all tags for a resource
- Supported `resourceType` values: `JOB`, `WORKFLOW`, `FILE_CHANNEL`, `FILE_TEMPLATE`.
- Permissions: `ROLE_ADMIN`, `ROLE_TENANT_ADMIN`.

### API Keys

- `GET /api/console/api-keys` — list all API keys for tenant (key hash only, no raw key)
- `GET /api/console/api-keys/{id}` — API key detail
- `POST /api/console/api-keys` — create API key (body: `keyName`, `scopes`, `expiresAt`); returns raw key **once only**
- `DELETE /api/console/api-keys/{id}` — revoke API key (requires `ROLE_ADMIN`)
- Raw key is a `bk_`-prefixed Base64 token; only the SHA-256 hash and 8-char prefix are stored.
- List/detail/create: `ROLE_ADMIN`, `ROLE_TENANT_USER`. Revoke: `ROLE_ADMIN` only.

### Kafka Lag

- `GET /api/console/ops/kafka-lag` — query Kafka consumer group lag for batch-related topics

### Governance

- `GET /api/console/ops/governance` — list circuit breaker / rate limit parameters
- `POST /api/console/ops/governance` — update governance parameter
- `POST /api/console/ops/governance/reset` — reset to default

### Outbox Ops

- `GET /api/console/ops/outbox/stats` — outbox event statistics (pending / failed / delivered counts)
- `POST /api/console/ops/outbox/cleanup` — clean up delivered/expired outbox events
- `POST /api/console/ops/outbox/republish` — republish failed outbox events

### Archive Policies

- `GET /api/console/ops/archive-policies` — list archive/cleanup policies
- `PUT /api/console/ops/archive-policies` — upsert archive/cleanup policy

### Cluster Diagnostic

- `GET /api/console/ops/cluster-diagnostic` — full cluster health check
- `GET /api/console/ops/cluster-diagnostic/shedlock` — ShedLock lease status
- `GET /api/console/ops/cluster-diagnostic/workers` — worker consistency
- `GET /api/console/ops/cluster-diagnostic/outbox` — outbox health

### Tenant Self-Service

- `GET /api/console/tenants/quota` — tenant quota policies
- `GET /api/console/tenants/usage` — tenant usage metrics
- `POST /api/console/tenants/quota/request` — request quota change (stored as system parameter; returns request key)

Request body:

```json
{
  "field": "maxConcurrentJobs",
  "requestedValue": 200,
  "reason": "业务增长，需要提升并发额度"
}
```

Notes:

- `field` should match quota policy code returned by `GET /api/console/tenants/quota` (e.g. `items[].policyCode`)
- `requestedValue` must be a positive integer

### Self-Service Jobs

- `POST /api/console/self-service/jobs/rerun-request` — submit rerun via approval
- `POST /api/console/self-service/jobs/compensation-request` — submit compensation via approval

### Event Catalog

- `GET /api/console/event-catalog/event-types` — subscribable event types
- `GET /api/console/event-catalog/topics` — Kafka topic directory

### Queries

- `GET /api/console/queries/audits`
- `GET /api/console/queries/execution-logs`
- `GET /api/console/queries/alerts`
- `GET /api/console/queries/approvals`
- `GET /api/console/queries/files`
- `GET /api/console/queries/job-definitions`
- `GET /api/console/queries/outbox-retries`
- `GET /api/console/queries/outbox-deliveries`
- `GET /api/console/queries/file-pipelines`
- `GET /api/console/queries/file-pipeline-steps`
- `GET /api/console/queries/file-dispatches`
- `GET /api/console/queries/channel-receipts`
- `GET /api/console/queries/file-channels`
- `GET /api/console/queries/file-arrival-groups`
- `GET /api/console/queries/file-errors`
- `GET /api/console/queries/file-templates`
- `GET /api/console/queries/instances` — supports `sortBy=duration` and `minDurationSeconds` for slow task diagnosis
- `GET /api/console/queries/instances/{id}`
- `GET /api/console/queries/instances/batch-status` — batch query instance status by `instanceNos[]`
- `GET /api/console/queries/job-step-instances`
- `GET /api/console/queries/job-step-instances/{id}`
- `GET /api/console/queries/partitions` — 按作业实例分页查询 `job_partition`（分区粒度；`job-step-instances` 是步骤粒度）
- `GET /api/console/queries/workflow-definitions`
- `GET /api/console/queries/workflow-nodes`
- `GET /api/console/queries/workflow-edges`
- `GET /api/console/queries/workflow-runs`
- `GET /api/console/queries/workflow-runs/{id}`
- `GET /api/console/queries/workflow-node-runs`
- `GET /api/console/queries/workflow-node-runs/{id}`
- `GET /api/console/queries/workflow-topology`
- `GET /api/console/queries/ai-audits`
- `GET /api/console/queries/dead-letters`
- `GET /api/console/queries/retries`
- `GET /api/console/queries/catch-up-approvals`
- `GET /api/console/queries/batch-days`
- `GET /api/console/queries/batch-days/{bizDate}/window`
- `GET /api/console/queries/workers`
- `GET /api/console/queries/file-channels/{channelCode}`
- `GET /api/console/queries/file-templates/{templateCode}`
- `GET /api/console/queries/files/{id}`
- `GET /api/console/queries/file-pipelines/{id}`
- Query endpoints must return typed list DTOs or documented view objects. Avoid raw entity lists and anonymous maps in new query APIs.
- `execution-logs` is a UI alias for `audits` and uses the same response shape.
- `channel-receipts` is a receipt-focused alias of `file-dispatches`; it uses the same request fields and response DTO, but gives the frontend a stable semantic entrypoint for receipt tracking.
- `workflow-topology` returns `ConsoleWorkflowTopologyResponse` with `workflowDefinition`, `nodes`, `edges`, `workflowRuns`, and `nodeRuns`; the frontend should use those five fields directly instead of reconstructing a generic object map.
- All paginated query endpoints accept `tenantId`, `pageNo`, and `pageSize`. In addition, the following endpoints support server-side filtering:

| Endpoint | Filter Parameters | Match |
|----------|-------------------|-------|
| `/query/audits` | `operationType`, `operationResult` (exact); `operatorId`, `traceId` (partial); `fileId` (exact); `startTime`/`endTime` (range) | mixed |
| `/query/alerts` | `severity`, `status`, `alertType` (exact); `traceId` (exact); `startDate`/`endDate` (range on `last_seen_at`) | mixed |
| `/query/files` | `fileStatus`, `bizType` (exact); `fileName` (partial); `traceId`, `fileId` (exact); `fromTime`/`toTime` (range) | mixed |
| `/query/instances` | `jobCode` (partial); `instanceStatus`, `instanceNo`, `bizDate` (exact); `traceId` (partial); `startDate`/`endDate` (range); `sortBy` (`id`/`duration`); `minDurationSeconds` (threshold filter) | mixed |
| `/query/job-definitions` | `jobCode`, `jobName`, `workerGroup`, `queueCode` (partial); `jobType`, `scheduleType`, `enabled` (exact) | mixed |
| `/query/job-step-instances` | `jobInstanceId`, `jobPartitionId`, `stepCode`, `stepStatus` (exact) | exact |
| `/query/partitions` | `jobInstanceId`, `partitionStatus` (exact) | exact |
| `/query/workflow-definitions` | `workflowCode` (partial) | partial |
| `/query/workflow-nodes` | `workflowDefinitionId` (exact); `workflowCode`, `nodeCode` (exact); `nodeType`, `enabled` (exact) | exact |
| `/query/workflow-edges` | `workflowDefinitionId` (exact); `workflowCode`, `fromNodeCode`, `toNodeCode`, `edgeType`, `enabled` (exact) | exact |
| `/query/workflow-runs` | `workflowDefinitionId`, `relatedJobInstanceId`, `runStatus`, `currentNodeCode`, `traceId` (exact) | exact |
| `/query/workflow-node-runs` | `workflowRunId`, `nodeCode`, `nodeStatus` (exact) | exact |
| `/query/outbox-retries` | `retryStatus`, `eventKey` (exact) | exact |
| `/query/outbox-deliveries` | `deliveryStatus`, `eventType`, `eventKey` (exact) | exact |

### Streaming

- Streaming endpoints use `text/event-stream` and return raw SSE frames instead of the `CommonResponse` JSON envelope.
- Browser `EventSource` clients may authenticate with `Authorization: Bearer <jwt>` or `?token=<jwt>` when custom headers are unavailable.
- The realtime stream currently emits `ready`, `heartbeat`, and domain event names such as `pipeline-definition-created`, `pipeline-definition-updated`, and `pipeline-definition-toggled`.

### Notification Subscription Management

- `GET /api/console/notifications/channels` — list notification channels
- `GET /api/console/notifications/channels/{channelCode}` — get channel detail
- `POST /api/console/notifications/channels` — create notification channel (EMAIL / DINGTALK / WECOM / WEBHOOK / SMS)
- `PUT /api/console/notifications/channels/{channelCode}` — update channel
- `DELETE /api/console/notifications/channels/{channelCode}` — delete channel
- `POST /api/console/notifications/channels/{channelCode}/test` — send test notification
- `GET /api/console/notifications/rules` — list subscription rules
- `GET /api/console/notifications/rules/{ruleId}` — get rule detail
- `POST /api/console/notifications/rules` — create subscription rule (links channel + event types + filters)
- `PUT /api/console/notifications/rules/{ruleId}` — update rule
- `DELETE /api/console/notifications/rules/{ruleId}` — delete rule
- `GET /api/console/notifications/delivery-logs` — list delivery logs (param: `tenantId`, `limit`)
- Channel CRUD and rule CRUD require `ROLE_ADMIN`, `ROLE_TENANT_ADMIN`, or `ROLE_TENANT_USER`.
- Delivery log view additionally allows `ROLE_AUDITOR`.
- Channel delete requires `ROLE_ADMIN` or `ROLE_TENANT_USER`.
- Database tables: `notification_channel`, `subscription_rule`, `notification_delivery_log` (V49 migration).

### Config Approval Flow

- `POST /api/console/config/releases/{releaseId}/submit-approval` — submit release for approval (changes status to PENDING_APPROVAL)
- `GET /api/console/config/releases/{releaseId}/approval` — get approval detail for a release
- `POST /api/console/config/approvals/{approvalId}/approve` — approve (changes release to PUBLISHED)
- `POST /api/console/config/approvals/{approvalId}/reject` — reject (changes release back to DRAFT)
- Submit, approve, and reject require `ROLE_ADMIN`.
- Approval detail view allows `ROLE_ADMIN`, `ROLE_AUDITOR`, and `ROLE_TENANT_ADMIN`.
- State machine: `DRAFT → PENDING_APPROVAL → PUBLISHED` (approve) or `DRAFT → PENDING_APPROVAL → DRAFT` (reject).
- Database table: `config_approval` (V49 migration). Enum `ConfigLifecycleStatus.PENDING_APPROVAL` added.

### Cross-Environment Config Sync

- `POST /api/console/config/sync/export` — export config bundle from source tenant/environment
- `POST /api/console/config/sync/preview` — preview import impact without executing
- `POST /api/console/config/sync/import` — import config bundle into target tenants
- `GET /api/console/config/sync/logs` — list sync operation logs (param: `tenantId`, `limit`)
- All endpoints require `ROLE_ADMIN`.
- Export returns a `ConfigSyncBundlePayload` containing job definitions, workflow definitions, pipeline definitions, file channels, and file templates.
- Import delegates to `ConsoleTenantConfigInitApplicationService.batchInit()` and records sync log with RUNNING → SUCCESS / PARTIAL_FAILED / FAILED status.
- Database table: `config_sync_log` (V49 migration).

### Alert Routing / Notification Policy Status

- Alert routing Excel maintenance is available via the standard upload → preview → apply flow.
- Notification subscription management is now fully implemented (see above).

## Trigger API

- `POST /api/console/jobs/trigger`

Request headers:

```http
Idempotency-Key: tenant-a:job-daily-settlement:2026-03-21:req-001
X-Request-Id: req-001
X-Trace-Id: trace-001
X-Operator-Id: admin
```

Request body:

```json
{
  "tenantId": "tenant-a",
  "jobCode": "daily-settlement",
  "bizDate": "2026-03-21",
  "triggerType": "MANUAL",
  "payload": "{\"source\":\"console\"}",
  "dryRun": false
}
```

When `dryRun` is `true`, the server validates tenant, jobCode existence, enabled status, bizDate format, and triggerType without creating an actual trigger request. `Idempotency-Key` header is not required for dry-run calls.

## Compensate API

- `POST /api/console/jobs/compensate`

Request body:

```json
{
  "tenantId": "tenant-a",
  "jobCode": "daily-settlement",
  "bizDate": "2026-03-21",
  "targetInstanceNo": "INST-20260321-0001",
  "reason": "manual compensate"
}
```

## Rerun API

- `POST /api/console/jobs/rerun`

Request body:

```json
{
  "tenantId": "tenant-a",
  "jobCode": "daily-settlement",
  "bizDate": "2026-03-21",
  "targetInstanceNo": "INST-20260321-0001",
  "reason": "rerun failed partitions"
}
```

> **Governance / Tenant Self-Service / Archive & Cleanup Policies / Cluster Diagnostic / Self-Service Rerun · Compensation / Event Catalog**:these endpoints are already enumerated in the **Current Route Catalog** above (see the same-named subsections: Governance, Tenant Self-Service, Archive Policies, Cluster Diagnostic, Self-Service Jobs, Event Catalog). The `File Upload & Arrival Confirmation` endpoints (`presign-upload` / `{fileId}/content` / `{fileId}/confirm-arrival`) are listed under the **Files** subsection above (with `tenantId` / `channelCode` / `fileName` params and the `file` multipart form field).

### API Versioning

All console APIs support versioned paths via URL prefix:

- `/api/v1/console/**` → rewritten to `/api/console/**`
- `X-API-Version` response header indicates current version (`1`)
- `Accept-Version` request header is recognized for future negotiation

### Worker Warmup

- `POST /api/console/workers/{workerCode}/warmup` — trigger worker warmup (param: `tenantId`)

### Frontend Telemetry

- `POST /api/console/telemetry/events` — receive frontend telemetry events in batch
- Request body structure:

```json
{
  "app": "batch-console",
  "userId": "admin",
  "sessionId": "sess-abc123",
  "events": [
    {
      "type": "error",
      "name": "TypeError: Cannot read property 'id' of undefined",
      "ts": "2026-04-10T12:00:00.000Z",
      "page": "/jobs",
      "props": { "stack": "at JobList.vue:42", "componentName": "JobList" }
    }
  ]
}
```

- `type`: event category — `route` / `click` / `api` / `error`
- `name`: event name or description
- `ts`: ISO 8601 timestamp string
- `props`: arbitrary key-value object, backend serializes to JSON for logging
- Outer `app` / `userId` / `sessionId` provide session context without repeating per event
- Max 50 events per batch
- Backend logs each event via slf4j with MDC fields (`frontendApp`, `frontendUserId`, `frontendEventType`, `frontendPage`), then Promtail picks up into Loki
- `error` type events are logged at ERROR level; all others at INFO level
- Requires JWT authentication (any authenticated console user)
- Frontend should batch non-critical events (click, route) and report errors immediately
- See `docs/design/logging-architecture.md` for full logging pipeline design

## File Download API

- `GET /api/console/files/{fileId}/download`
- Query params: `tenantId` (required), `approvalId` (optional).
- Response is **raw file bytes** (`Content-Disposition: attachment`, MIME from metadata or `application/octet-stream`), **not** the `CommonResponse` JSON envelope.
- When the file’s template enforces download approval, `approvalId` must reference an **APPROVED** (or **EXECUTED**) approval; omit only when `batch.security.bypass-mode=true` or policy does not require approval.

## Error Code Baseline

- `INVALID_ARGUMENT`
- `VALIDATION_ERROR`
- `MISSING_IDEMPOTENCY_KEY`
- `NOT_FOUND`
- `CONFLICT`
- `STATE_CONFLICT`
- `UNAUTHORIZED`
- `FORBIDDEN`
- `BUSINESS_ERROR`
- `NOT_IMPLEMENTED`
- `SYSTEM_ERROR`
