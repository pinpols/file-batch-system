# dialog-3

第1轮：补 7.2/7.3 执行与分片
用户：先补第1轮，接通 shard_strategy，统一 partition 状态口径，做最小可用动态分片。
我：会先收口 job_definition -> plan builder -> partition lifecycle -> waiting release 这条链，确保不是固定 1 分片，也不是状态图和代码两套口径。
验收：shard_strategy 真生效；CREATED/WAITING/READY/RUNNING 流转一致；编译通过。
状态：已完成（2026-03-22）

第2轮：补 5.3/6.1 DAG 运行推进
用户：先补第2轮，把 workflow 推进改成“按 node 聚合”，支持 fan-out / fan-in。
我：会把 workflowRun.currentNodeCode 从“单节点”修成“活跃节点集合”，任务回报按 workflowNodeCode 汇总，不再按整个 job_instance 一锅算。
验收：多分支 DAG 能正确推进；join 节点按前驱完成情况触发；编译通过。
状态：已完成（2026-03-22）

第3轮：补 8.x 资源调度与公平控制
用户：先补第3轮，把资源调度从“限额拦截”补成“公平调度”。
我：会补租户权重、队列配额、公平出队和更明确的优先级带宽，不只看 maxRunningJobsPerTenant/maxPartitionsPerTenant。
验收：同租户和跨租户竞争时有明确可解释的调度顺序；waiting release 不再只是谁先扫到谁先出。
状态：已完成（2026-03-22）

第4轮：补 9.3/9.4 文件到达与文件组等待
用户：先补第4轮，把文件到达管理和文件组等齐启动补完整。
我：会在现有 import scanner 的 .done + 稳定性检测 基础上，补 file group / 等齐规则 / 超时策略 / 到达窗口。
验收：一组文件可按组登记、按组等齐、按组触发或超时失败；控制台可查组内状态。
状态：已完成（2026-03-22）

第5轮：补 9.11 Skip 策略与坏记录治理
用户：先补第5轮，把坏记录、跳过阈值、错误文件留存补完整。
我：会补 bad-record 模型、行级错误输出、skip threshold、失败样本审计，而不是只返回 stage 失败。
验收：导入能区分“整文件失败”和“坏记录可跳过”；坏记录可追溯、可下载、可复盘。
状态：已完成（2026-03-22）

第6轮：补 10.x 文件资产治理闭环
用户：先补第6轮，把文件治理从基础版补成闭环版。
我：会在现有 file_record/pipeline/dispatch/audit 和治理调度基础上，补 archive/delete/redispatch 全链路、人工操作审计、对账修复、归档清理策略。
验收：文件能被受控归档、删除、重分发；审计完整；治理任务可定时收口。
状态：已完成（2026-03-22）

第7轮：补 6.2 DAG 可视化 + 9.10/10.x 控制台治理面
用户：先补第7轮，把 DAG 图和文件治理查询/操作面补到控制台。
我：会补 workflow definition/node/edge/query 接口，以及 DAG 拓扑、运行态、节点耗时、文件链路视图。
验收：控制台能看 DAG 图、节点状态、文件链路、dispatch 回执、治理操作结果。
状态：已完成（2026-03-22）

第8轮：补 4.1 AI 助手 / AI Gateway
用户：先补第8轮，把 AI 助手、权限分级、Prompt 出域网关补完整。
我：会补 console 侧 AI service/controller、能力开关、权限边界、脱敏、审计和统一网关，不只停留在依赖和配置。
验收：AI 功能不是空开关；有明确输入边界、权限边界、审计留痕。
状态：已完成（2026-03-22）

第9轮：补 11.x 运行质量与 SLA
用户：先补第9轮，把任务 SLA、文件到达 SLA 和数据质量规则补完整。
我：会先把任务级 deadline/expected_duration、文件到达 WAITING_ARRIVAL/latest_tolerable_time、人工继续等待/跳过批次/空跑入口，以及统一数据质量规则引擎一起收口，不再只靠 metadata 状态和硬编码校验。
验收：实例 SLA 可配置可告警；文件到达超时动作受控可审计；row_count/checksum/schema/null_check 和可配置校验规则真正生效。
状态：已完成（2026-03-22）

第10轮：补 12.x 补偿、状态机与任务实例中心
用户：先补第10轮，把补偿平台、状态机约束和任务实例中心补完整。
我：会把现有 retry/dead letter/rerun/file redispatch 收口成统一补偿入口，补 job_step_instance 和业务实例字段，并把状态跃迁改成显式约束 + version/CAS，而不是分散更新。
验收：有统一 CompensationCommand/Service；任务/步骤/分片/文件/批次补偿共用入口；job_instance 和 step 实例中心字段完整；状态冲突可检测。
状态：已完成（2026-03-22）

第11轮：补 13.x 事务、消息与参数化
用户：先补第11轮，把 chunk 事务、消息一致性和参数化模型补完整。
我：会把 params_snapshot 和参数优先级审计接通，补任务模板/参数模型，梳理 trigger 到 orchestrator 的一致性边界，并把 outbox 从单表补到完整投递/重试记录。
验收：实例能落 params_snapshot；模板和默认参数可追溯；chunk 提交和恢复点清晰；event_outbox/event_outbox_retry/event_delivery_log 闭环成型；触发幂等键口径统一。
状态：已完成（2026-03-22）

第12轮：补 15.x 多租户与安全
用户：先补第12轮，把租户隔离、安全、配置发布和密钥轮换补完整。
我：会先把控制台从 header 透传改成真正的认证授权与租户注入，再补角色模型、文件下载/预签名/脱敏/审批、配置发布灰度回滚，以及 secret_ref/secret_version 轮换模型。
验收：角色和权限边界真实生效；控制台禁止任意指定租户；文件访问有预签名和审批审计；配置支持 DRAFT/PUBLISHED/GRAY/ROLLED_BACK；凭证支持版本和轮换窗口。
状态：已完成（2026-03-22）

第13轮：补 9.12 边查边写与流式处理硬约束
用户：先补第13轮，把 import/export 从“内存聚合版”改成流式 / 分页 / chunk 版，不能再整文件整结果集全量加载。
我：会优先收口 `ParseStep / ImportDataQualityService / CustomerAccountImportRepository / GenerateStep` 这条链，补 `streamingEnabled / pageSize / fetchSize / chunkSize` 默认参数，先让导入和导出符合设计文档的 OOM 防线。
验收：导入不再 `split("\n") + List<CustomerImportPayload>` 全量堆内存；导出不再一次性加载全量明细和整文件字符串；配置可查、行为可审计。
状态：已完成（2026-03-22）

第14轮：补 9.3 导入预处理与多格式解析
用户：先补第14轮，把导入链路从最小版补成文档里的标准版。
我：会补 `unzip / decrypt / verify-sign / charset-convert` 的真实预处理插件，并把解析从 `JSON / DELIMITED` 扩到至少 `EXCEL / XML / FIXED_WIDTH`，同时保留模板驱动和坏记录治理。
验收：导入支持多格式、多预处理步骤；模板和步骤配置真实生效；不再只有 base64 + 文本归一化。
状态：已完成（2026-03-22）

第15轮：补 9.4 导出快照、一致性与半文件保护
用户：先补第15轮，把导出链路的快照口径、临时对象、正式对象切换和登记顺序补完整。
我：会补 `snapshotMode / snapshotTs / sourcePartitions`、临时对象 `.part`、摘要校验、原子切换、`STORE -> REGISTER` 补偿规则，避免“对象存在但平台未登记”或“先登记后对象失败”的脏状态。
验收：导出记录能追溯数据快照；正式对象只在校验通过后可见；对象存储和 `file_record` 的补偿规则闭合。
状态：已完成（2026-03-22）

第16轮：补 9.5 / 16.6 分发渠道、回执轮询与健康治理
用户：先补第16轮，把分发从本地适配版补到真实渠道版。
我：会补真实 `SFTP / EMAIL / API_PUSH` 适配器、异步回执轮询、渠道健康状态、失败退避、熔断/恢复探测，不再把 `SFTP / EMAIL / OSS / NAS` 都落到本地文件适配器。
验收：至少三类真实渠道可运行；异步 ACK 可追踪；渠道健康、熔断、恢复事件可审计。
状态：已完成（2026-03-22）

第17轮：补 8.2 / 8.3 高级公平调度与负载画像
用户：先补第17轮，把资源调度从“最小公平版”补到文档里的租户公平与节点负载模型。
我：会补 `fair_share_group / burst_limit / quota_reset_policy / tenant_scheduler_snapshot / current_load`，并把节点级负载真正参与 worker 选择，而不只靠 `worker_group + heartbeat`。
验收：租户共享组、公平快照、突发额度、节点负载都可解释；控制台可查询调度快照。
状态：已完成（2026-03-22）

第18轮：补 12.4 / 12.5 高级补偿中心收口
用户：先补第18轮，把补偿平台从“最小统一入口”补到真正可运营版。
我：会补 `STEP` 级无 partition 补偿、人工命令审批留痕、命令冲突检测、命中条数/冲突条数反馈，去掉 `NOT_IMPLEMENTED` 型残缺分支。
验收：补偿类型不再有明显空洞；人工操作有审批号、审计和冲突反馈；状态机仍由 Orchestrator 收口。
状态：已完成（2026-03-22）

第19轮：补 15.2 文件内容安全与下载审批
用户：先补第19轮，把文件侧的安全能力补完整。
我：会补 `preview_masking_enabled / error_line_masking_enabled / log_masking_enabled / content_encryption_enabled / encryption_key_ref / download_requires_approval`，让预览、错误样本、下载和对象内容真正受控。
验收：高敏文件可脱敏预览、审批下载、按密钥版本加密；日志和坏记录输出不会裸露敏感明文。
状态：已完成（2026-03-22）

第20轮：补 16.x 结构化日志、告警事件与运行手册
用户：先补第20轮，把运行治理从“有指标”补到“可观测闭环”。
我：会补统一结构化日志字段、告警事件模型、告警收敛、Prometheus/Grafana 基线、巡检 SOP 和故障 runbook，不再只有若干 scheduler 日志。
验收：核心模块日志口径统一；告警事件可落库/查询；仓库里有可执行 runbook/SOP 文档。
状态：已完成（2026-03-22）

第21轮：补 18.2 Worker 排空、下线与滚动升级
用户：先补第21轮，把 Worker 优雅下线做到文档要求。
我：会补 drain API、剩余认领任务查询、`drain_timeout_seconds / drain_check_interval_seconds`、强制下线和滚动升级手册，不只停留在状态枚举和 `shutdown()`。
验收：控制台可发起 drain、观察排空、超时接管和强制下线；滚动升级流程有代码和文档支撑。
状态：已完成（2026-03-22）

第22轮：补 20.11 Flyway 版本治理与默认运行参数表
用户：先补第22轮，把实施交付里的数据库和默认参数基线收口。
我：会先修复重复的 Flyway 版本号，再补运行参数表/文档，明确 `chunkSize / fetchSize / pageSize / timeout / retry / breaker threshold / file size limit` 的默认值和来源。
验收：Flyway 版本序列唯一可执行；默认运行参数有统一口径，可用于本地、测试和生产基线。
状态：已完成（2026-03-22）

第23轮：补 16.5 / 18.4 集成测试、压测与容量基线
用户：先补第23轮，把验证从“能编译”提升到“能证明”。
我：会补 `SpringBootTest / Testcontainers / EmbeddedKafka` 集成测试、关键失败链路回放、容量压测脚本与基线文档，解决当前只有一个单元测试的问题。
验收：主链路、补偿、租约回收、文件治理至少有集成测试；仓库里有容量基线和压测脚本/说明。
状态：已完成（2026-03-28）

第24轮：补 18 / 19 / 合规交付物
用户：先补第24轮，把生产交付和合规产物补齐。
我：会补部署清单、灰度/回滚顺序、恢复手册、自动化运维脚本，以及 `THIRD-PARTY-LICENSES.md / NOTICE / SBOM` 这类正式交付件。
验收：仓库具备生产部署说明、恢复说明、自动巡检/自愈脚本和基础合规产物；不再只有 `docker-compose.yml` 和本地脚本。
状态：已完成（2026-03-28）
