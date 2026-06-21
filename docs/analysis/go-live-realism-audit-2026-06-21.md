# 上线真实性审计报告 — 2026-06-21

> 范围:按「真实批量场景装得下 / 出事能救 / 数字可信 / 固定窗口 / 故障恢复 / 多租隔离 / 接入上手 / 可观测 / 边界纪律 / go-live 签字」10 个维度做静态深审。
> 方法:阅读当前代码、Flyway、runbook、benchmark、sim 脚本和既有审计文档；本轮不跑测试、不重启服务。
> 结论口径:区分「代码能力已落地」「本地/sim 有验证」「只具备脚本或文档」「必须 staging 同构演练后才能签字」。
>
> **【2026-06-21 审计后更新】** 本报告成稿时 alert escalation notifier 还在未提交工作树。此后已合并 **PR #603(`f1ba2a04a`,告警升级最后一公里→平台内 webhook 通知)** 到 main,并跑通本地 live-smoke 实证(OPEN 告警 tier 抬升 → notifier 发 `alerts/alert-escalated` → 现有 webhook 分发器真发 HTTP,payload/CAS 幂等水位线/SSRF 拦截均验证)。故下文涉及「alert escalation 未提交/未闭环」的结论(§总结论 #4、§8、§10 No-Go、P0 #5)已被本次合并取代,正文处就近标注。其余告警 TODO(Prometheus alert 名待补等)仍 open。

## 总结论

项目已经不是只能跑 hello-world 的批处理 demo。bizDate、日历、misfire、readiness defer、批量日 replay、result_version、worker lease/claim、outbox、分片、checkpoint、sidecar manifest、控制记录、SLO/load-tests、sim harness 都有真实实现或验证资产。

但按结算级上线签字口径，当前仍不能直接宣称「生产完全就绪」。主要差距不是普通单测数量，而是:

1. staging 同构演练证据还缺:整队 worker 崩溃、PITR、PG failover、Kafka 短时不可用、DLQ 重放、真实 S3/SFTP/OSS 故障注入。
2. 对账闭环仍有默认告警-only/opt-in 的环节:跨阶段 count continuity 当前只告警不阻断；dispatch readback 已有代码路径，但按设计仍是 opt-in，且渠道能力缺失时会跳过并告警。
3. Day-2 控制台入口有断点:orchestrator 内部已支持 instance/workflow pause/resume，但 console 暴露的 instance/workflow 运维 API 当前只看到 cancel/terminate/partition retry/skip-node，pause/resume 未公开。
4. 告警规则/runbook 仍有 TODO:playbooks 多处写着 Prometheus alert 待补。~~当前工作树里有 alert escalation notify 变更，但未提交，不能算主线闭环。~~ **【2026-06-21 更新】alert escalation notify 已合并(PR #603)并 live-smoke 实证闭环;剩余仅 Prometheus alert 名待补。**
5. BYO 五语言 SDK 还不能按「全部生产级」签字:Java 最完整；Python/Go/TypeScript/Rust 有目录和 conformance/协议测试迹象，但本轮未看到同等生产 transport 验收证据。

## 1. 业务表达力:真实批量场景装得下吗

状态: **大体可承载，仍需少量上线口径收敛。**

已落地证据:

- readiness defer 已写入数据库并接入触发链路: `V180__trigger_runtime_state_readiness_deferred.sql` 增加 `WAITING_READINESS / WAITING_READINESS_TIMEOUT / SKIPPED_BY_CALENDAR`，`DefaultTriggerService` 在 launch 前检查上游，`HashedWheelTriggerScheduler` 维护 defer window/recheck，不再把当天批直接 skip 掉。
- 上游成功判定使用最新 attempt: `ReadinessService` / `JobInstanceMapper` 按 `tenantId + jobCode + bizDate` 取最新 attempt，避免 rerun 后旧 SUCCESS 误解锁下游。
- 重跑/补数有模型: `ADR-020`、`BatchDayReplayService`、`batch_day_replay_session` 支持按 bizDate/session/scope replay，并接 result_version。
- 文件侧不是玩具: import 已覆盖 XML/FIXED_WIDTH/EXCEL/DELIMITED、非 UTF-8 严格解码、坏行治理、行列定位、checkpoint、分区 COPY、APPEND/UPSERT/REPLACE 小矩阵；benchmark 文档记录了 1000w 级验证。
- 日批/月批/跨日依赖有设计和实现资产: batch day、calendar dependency、cross-day dependency、misfire pending、trigger catch-up/replay 形成了一套批量日语义。

主要风险:

- readiness defer 的「自动关联 catch-up request」曾被标成需修设计；当前证据显示 defer/recheck 已有，但上线前还要以 staging 业务日跑一遍「02:00 fire，上游 04:00 才成功，仍按原 bizDate 启动」。
- Excel multipart 配置包上传属于 console 配置链路，不属于 worker-import trigger 链路；文档已说明，但上线验收要把两条链路分开签。

## 2. Day-2 运维:出事运维能不能自己救

状态: **底座强，控制台/告警/演练闭环仍不完整。**

已落地证据:

- 运维脚本/治理入口存在:`scripts/ops/*`、outbox reset/cleanup/republish、dead-letter replay、timeout drain、retry partition/task、zombie pipeline 等。
- runbook 有可执行入口:`incident-response.md`、`playbooks/outbox-stuck-publishing.md`、`pg-primary-failover.md`、`kafka-rebalance-stuck.md`、`redis-shedlock-down.md`、`batch-day-not-settling.md`。
- 审计有统一表:`V130__create_console_operation_audit.sql` + `@AuditAction`，高风险 console 写操作多数有 action/tenant/trace 留痕。
- cancel/terminate/partition retry/skip-node 有 console API，且带 `@Idempotent` / `@AuditAction`。

缺口:

- instance/workflow pause/resume:orchestrator 内部 `/internal/instances/{id}/pause|resume` 和 `/internal/workflow-runs/{id}/pause|resume` 已有，但 console API 当前未转发这两个动作。运维仍可能要绕过 console 调内部接口。
- 多篇 playbook 的 Prometheus alert 名仍是 TODO，说明「怎么发现」尚未全自动。
- outbox cleanup/republish 是否全量走审批/操作人 reason 仍需复核；历史审计曾指出部分治理动作审计链不足。

## 3. 数字可信:跑成功的批敢不敢拿去出账

状态: **核心幂等强，对账关键约束已开始落地，但默认阻断语义不足。**

已落地证据:

- claim/report/outbox 的幂等与 CAS 是系统主设计:CLAIM、lease renew、terminal status 防复活、outbox 同事务、dedup key、result_version EFFECTIVE 唯一。
- 入站控制记录已实现:import 的 `TrailerControlRecord`、`ControlTotalEvaluator`、`DatasetRuleEvaluator` 支持 trailer record count/control total。
- 出站 trailer 已实现:export 的 `OutboundTrailerRecord` / `DelimitedExportFormat` 可把 record count/control total 写入文件。
- sidecar manifest 和 arrival require-verified 已有设计和实现:入站 `.chk` 注入 checksum/recordCount，dispatch 支持出站 manifest。
- 最新 attempt readiness 能挡住 rerun 旧结果误用。

主要风险:

- `CountContinuityOutboxService` 注释明确「inline-on-report, 仅告警」，不翻转状态。对结算链路，这应该变成可配置 BLOCKER，至少对特定 workflow/job 开启。
- ADR-041 仍是 Proposed；代码已有部分落地，但文档状态/上线门槛未同步成 Accepted/Implemented。
- dispatch readback 当前是 `readback_verify_enabled=true` 才启用；渠道不支持 readback 时会跳过并告警。结算出站链路建议把支持矩阵写清，并在关键渠道设为强制。

## 4. 容量确定性:固定窗口跑得完吗

状态: **本地容量画像较完整，生产同构窗口证据仍缺。**

已落地证据:

- `worker-throughput-benchmark-plan-2026-06-07.md` 记录 import/export P0/P1 已完成:import 1000w、partition replace/stage swap、bad-record、checkpoint；export 1000w、4 分片真并行、multipart、格式矩阵。
- load-tests 有 Gatling 场景和 SLO 参数，go-live runbook 写明 5-20 jobs/s 目标。
- 资源调度默认改为 `QUEUE_DEFER`，比直接拒绝更适合批量峰值流量。
- autoscaling/runbook 明确 static/dynamic sharding 的扩容前置。

主要风险:

- go-live readiness 明确说容量/SLO 需要在生产同构 staging 跑并签字；当前只是工具和本地/benchmark 证据。
- 单机控制面约 20 jobs/s 的瓶颈仍需以 launch lag、claim/report 锁等待、PG 连接、Kafka lag、outbox 积压一起定容量上限。
- 月底全量重算/10w storm/多租户混压属于 P2 容量承诺证据，不应混成 P0 已完成。

## 5. 故障恢复:出事不丢、不重

状态: **机制具备，灾难演练证据不足。**

已落地证据:

- worker crash/lease 回收/retry/checkpoint 有代码与 sim: `scripts/sim/25-import-stage2e-checkpoint-crash.sh` 记录 kill worker after chunk + 同 instance 续跑。
- `dr-drill-fleet-crash.sh`、`dr-drill-pitr.sh` 已作为 go-live 演练脚本存在。
- checkpoint 已接入 import LOAD 和 export GENERATE；process 有 crash-mid-flow cleanup 逻辑。
- outbox/kafka at-least-once + CLAIM 幂等是正确选择，避免强依赖 Kafka transactional producer。

主要风险:

- `go-live-readiness.md` 明确 DR/韧性演练是真缺口:全 worker 组崩溃、PITR、PG failover、Kafka 短不可用、DLQ replay 需要 staging 签字。
- `backup-and-pitr.md` 也明确 Kafka RF=1 时 broker 盘损不能硬保证在途事件 RPO，只能靠 outbox republish/lease 重派回退。生产必须 broker≥3、RF=3、min ISR 与 acks=all。
- Process RUNNING cancel / Atomic shell cancel 当前语义偏软取消，不能强杀正在执行的 shell/任务；对高风险长任务要写入运行约束。

## 6. 多租/权限:真隔离还是演示隔离

状态: **隔离设计和测试资产较强，仍需 staging 真数据复验。**

已落地证据:

- go-live readiness 列出 batch 列级、多租 mapper guard、biz RLS、RlsStrictMode、RlsTenantSession、BusinessMultiShardRouting 等测试资产。
- SDK topic/ACL、tenantId 自检、worker onboarding 文档都强调 per-tenant Kafka ACL 和 SDK 收到非本租户消息必须 fail-safe。
- RBAC 从二元角色演进到 ADMIN/AUDITOR/TENANT_ADMIN/TENANT_USER 等，console 菜单/权限模型有文档。
- prod bypass-mode=off 被 go-live runbook 列为硬门。

主要风险:

- 多节点/Citus/RLS 曾有历史风险，当前 go-live 门要求 staging 真数据跨租查询返 0 行，不应只拿单元测试签字。
- alert/playbook 中少数运维动作的 operator/reason/audit 是否齐全需要继续扫治理端点。
- 租户凭据注入、Kafka ACL、worker 自托管 API key scope 要作为 onboarding smoke 的必验项。

## 7. 接入上手:新租户/新作业/异构 worker 多快能上

状态: **Java 路径成熟，多语言路径还不能一概按 GA 讲。**

已落地证据:

- `docs/sdk/onboarding-journey.md` 是完整接入旅程:API key、Kafka ACL、第一个 handler、首单 task、进度、取消、buildId 灰度、drain。
- `docs/sdk/byo-sdk-guide.md` 明确了最小 HTTP/Kafka wire 协议、heartbeat directive、lease renew、cancel、FSM、tenant self-check、contract fixtures。
- `sdk/java`、`sdk/java-spring`、`sdk/java-testkit` 是主要参考实现；`sdk/python`、`sdk/go`、`sdk/typescript`、`sdk/rust` 也有目录与部分测试/协议文件。

主要风险:

- 本轮不能确认 Go/TypeScript/Rust 都达到 Java 同级生产行为；需要逐语言跑 conformance + 真 transport 接入，不只是 fixture 绿。
- Python 历史审计曾指出 P0/P1 缺口，当前目录已有不少补丁迹象，但仍要以当前源码和 CI 结果重新签。
- BYO SDK 真实客户接入最容易漂移的是 lease/heartbeat/cancel/backpressure，必须用统一 conformance gates 卡住。

## 8. 看得见 + 出事有人知道

状态: **trace/metrics 设计存在，告警路由闭环仍需补齐。**

已落地证据:

- ADR-013 分布式 tracing 已有，go-live 要求 trigger→orchestrator→worker→report trace 贯通。
- observability runbook 列出核心指标:outbox、Kafka lag、Hikari、Redis、MinIO、SLA、dispatch circuit、export rows、receipt count 等。
- alert_event + escalation 机制已有 orchestrator 侧;console-api notifier 和 V181 通知水位线 **已合并(PR #603)并 live-smoke 实证**:OPEN 告警升级后由 `AlertEscalationNotifier` 经现有 webhook 链路真实推送,CAS 水位线保证每次 tier 抬升只通知一次。

主要风险:

- `playbooks/README.md` 明确 alert 名待补清单由 ops 拉单维护，多篇 playbook 仍有 Prometheus alert TODO。
- ~~当前未提交的 alert escalation notifier 不能算已合主线。合并前只能说“工作树已有实现”，不能说“已上线闭环”。~~ **【2026-06-21 更新】已合主线(PR #603)且 live-smoke 验证升级→webhook 闭环;EMAIL/钉钉/企微 sender 仍未接通,v1 仅 WEBHOOK(+Web Push)。**
- traceId 机制前面刚修过前端搜索契约，go-live 前要做一个端到端 traceId 可检索 smoke。

## 9. 边界纪律:没膨胀成四不实

状态: **边界意识强，仍需防文档/模块膨胀。**

已落地证据:

- ADR-021/022/026/027 等多处明确“不裁定业务对错、不自研 K8s 调度、不做通用治理平台”。
- ADR-041 明确控制总额是“文件传输完整性”，不是业务对账裁判；这个边界判断是正确的。
- worker SDK starter 独立模块、core 保持 Spring-free，是正确的适配层设计。

主要风险:

- 文档体量很大，且多份审计/roadmap 之间有落地状态漂移。上线报告必须引用当前代码证据，不要引用旧报告当最终事实。
- ADR-041 当前 Proposed 但代码已有落地；状态不更新会让评审误判。
- 大盘/AI/治理类能力不能挤占 control total、DR 演练、告警、runbook 这些上线关键约束。

## 10. 上线就绪:go-live 真能签字

状态: **按当前证据不能全量签字；可进入 staging go-live 演练。**

go-live readiness 已给出正确总闸:

- Phase 0:full CI、安全扫描、strict verify。
- Phase 1:生产同构 staging 的 sim 连跑、load-tests、DR、PITR、PG/Kafka/DLQ、安全、observability。
- Phase 2:prod-sized Flyway dry-run、回滚、灰度、值班。

当前最小 No-Go 条件:

- 没有 staging 同构 DR/PITR/PG/Kafka/DLQ 演练记录。
- 对结算链路没有把 count/control total/readback 的阻断策略逐 job/template 标清。
- 告警规则仍有 TODO(Prometheus alert 名待补)。~~alert escalation 通知还在未提交工作树。~~ **【2026-06-21 更新】escalation 通知已合并(#603)+实证,此条不再阻断。**
- console 缺 instance/workflow pause/resume 入口。

## P0 / P1 / P2 后续清单

### P0 — 上线签字前必须闭环

1. 跑 `go-live-staging-execution.md` Phase 1-A~E，并把日志/SQL/trace/截图/签字落到一次执行报告。
2. 补 console instance/workflow pause/resume 转发 API + `@AuditAction` + 前端入口，避免运维绕内部接口。
3. 对 ADR-041 关键链路做上线策略表:哪些模板 `controlRecordCheck/controlTotalCheck/countContinuity/readback` 是 WARN，哪些是 BLOCKER。
4. 把 alert TODO 收敛:outbox stale/circuit open、Kafka lag、PG primary/replica/lock、Redis/ShedLock、batch day stuck、readiness timeout、DLQ 增长。
5. ✅ **【2026-06-21 已闭环 / PR #603】** 合并并验证 alert escalation notifier:`AlertEscalationNotifier`(console-api 轮询共享表 + ShedLock + CAS 通知水位线 V181)已合 main,并 live-smoke 实证 OPEN 告警升级 → `alerts/alert-escalated` 事件 → 现有 webhook 分发器真发 HTTP(payload/幂等/SSRF 均验)。**残留**:EMAIL/钉钉/企微 sender 未实现(v1 仅 WEBHOOK+Web Push),作 P1 跟进。
6. staging 跑端到端 traceId smoke:trigger → orchestrator → worker → report → console 可检索。

### P1 — 灰度扩大前完成

1. dispatch readback 渠道矩阵:LOCAL/NAS/SFTP/OSS/HTTP 哪些支持 size/checksum readback，关键出站渠道不允许“能力缺失静默跳过”。
2. BYO SDK conformance:Java/Python/Go/TypeScript/Rust 每种至少跑真 HTTP+Kafka transport、cancel、lease timeout、backpressure、tenant mismatch、report retry。
3. 真实外部依赖故障注入:S3/OSS/SFTP/HTTP timeout/5xx/权限失败/断连，验证 retry/DLQ/幂等。
4. process failure profile:worker kill、PG 短断、staging cleanup、幂等重跑、empty result/validation。
5. outbox/DLQ 高风险治理动作补 operatorId/reason/idempotency/audit/dry-run。

### P2 — 容量承诺和生产调参

1. 10w task storm 与多租户公平性容量画像，输出控制面瓶颈和扩展预案。
2. PG/Kafka/MinIO 生产拓扑演练:RF=3、min ISR、acks=all、备份新鲜度指标、PITR 自动化。
3. 月底全量重算/大规模 backfill 的窗口评估，确定 online batch 与补数互相隔离策略。
4. 定期文档归档:把过期审计报告标注 superseded，避免评审引用旧事实。

## 本轮审计没有做的事

- 没跑测试、没启动服务、没压测。
- 没修改业务代码。
- 没把当前工作树未提交的 alert/import 变更纳入“已合主线”结论，只作为观察到的本地变更背景。
