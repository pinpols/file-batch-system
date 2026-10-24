# 依赖感知 fire(Dependency-Aware Fire)

> ADR-043 Phase B 实现。让声明了上游依赖的触发器在 fire 前先确认上游就绪,**不盲 fire 注定无输入 / 半输入的批**。默认对存量触发器零影响。

## 机制

`job_dependency` 声明下游 fire 前需就绪的上游作业；旧 `job_definition.depends_on_job_code` 保留为首个上游的兼容投影。scheduled fire 路径(`DefaultTriggerService.launchScheduled`)在 bizDate 解析后、persist 前插一道闸:

- 未声明上游(绝大多数存量触发器)→ 直接放行,**行为完全不变**。
- 声明一个或多个上游 → 通过 orchestrator 只读 API `GET /internal/readiness/job` 逐个检查同 bizDate 是否已有 **EFFECTIVE asset partition**(`UpstreamReadinessChecker` → `ReadinessService`)，全部满足才放行(AND):
  - 就绪 → 正常 fire。
  - 未就绪 → `launchScheduled` 抛 `UpstreamNotReadyException`，Quartz 创建一次性 retry trigger 执行 **readiness defer**，**不丢批**。

> **就绪口径=当前 EFFECTIVE 结果版本**:只认 `result_version.status=EFFECTIVE` 物化出的 asset partition。PENDING、DRY_RUN、失败产物、旧 SUPERSEDED 版本都不放行,避免下游按未生效或已被推翻的过期结果启动(结算级)。ready 响应会带 `businessKey/versionNo/jobInstanceId` 供日志和运维定位。
>
> 2026-07-01 起,orchestrator 读取物化 `asset_partition` 时还会校验它指向的是同 `businessKey` 的最新 EFFECTIVE 版本,写入物化行时也按 `version_no` 单调更新。这个守卫用于防止重跑产生更高版本后,旧 EFFECTIVE 物化行继续让下游误判 ready。

### 上游重跑与结果版本

依赖按“指定上游作业 + 对齐业务日”的**最新版本**判 ready，不固定绑定某个历史 `version_no`。上游重跑提交更高版本后，在该版本进入 EFFECTIVE 前，即使较旧版本仍标记 EFFECTIVE，也会暂时阻断尚未启动/仍在等待的下游；新版本 EFFECTIVE 后等待请求再恢复。该语义避免消费旧快照掩盖正在审核或失败的修订。

下游一旦已启动，不会因上游随后产生新版本而自动级联重跑。需要让下游消费修订结果时，运维应显式提交相应业务日的 replay，并按 replay 预览核对影响范围；这可以避免一次上游修订意外扩散成大规模自动重跑。现有机制没有把消费时的上游版本快照写入普通 `job_instance`，需要版本级 lineage 时应查对应结果/审计证据，不能把“latest at readiness time”误称为完整的消费版本审计。

> trigger **不直连** orchestrator 状态表(读写分离 + 模块边界),就绪判定一律经 orchestrator 暴露的只读 internal API(携 `X-Internal-Secret`)。orchestrator 仍是唯一状态主机。

## Orchestrator 全入口等待恢复

Trigger 的 Quartz readiness defer 是调度侧的快速门闸；作业实例创建前，`DefaultLaunchService` 还会使用 Orchestrator 自身的 EFFECTIVE asset partition 再检查一次。该统一检查覆盖文件组、Workflow 子作业、Catch-up、RERUN、API 和人工等进入 LaunchService 的请求。若有任一上游未就绪，Orchestrator 将完整 `LaunchRequest`、依赖规则快照和原始 `bizDate` 保存到 `job_dependency_waiting_launch`，把 `trigger_request` 标记为 `WAITING`，不创建实例；后台 reconciler 在全部上游同业务日 EFFECTIVE 后重放同一请求。重复唤醒仍由 `requestId` 和实例去重键收敛。

配置在等待期间发生变化不会改写已保存请求的依赖条件；新配置只影响之后首次进入等待的请求。等待超过 `BATCH_JOB_DEPENDENCY_WAIT_TIMEOUT_SECONDS`（默认 86400 秒）时，系统通过 CAS 发出一次 `BATCH_JOB_DEPENDENCY_WAIT_TIMEOUT` 告警；设为 0 可关闭告警。告警不会取消等待、自动跳过依赖或启动下游，全部上游同业务日结果生效后仍会自动恢复。该等待告警阈值与作业执行完成 SLA 分开配置。

Console 作业定义页的「依赖等待」只读列表可按租户查询等待中的下游、业务日、上游快照、最后阻塞原因、重检次数、等待时间和超时告警时间。读取结果不包含保存的完整 `LaunchRequest` 参数。等待仅会在所有上游同业务日结果生效后自动恢复；目前没有人工绕过入口，也不要通过 SQL 更新等待记录或 `trigger_request`。

## readiness defer(未就绪不丢批,ADR-043 §6.4)

旧实现未就绪直接 skip——**日批场景**上游晚几分钟完成，下游当天就再也不会自动 fire。现由 `QuartzLaunchJob` 执行 defer：

- **窗口内重检(同 bizDate 不丢批)**：Quartz 为同一 JobDetail 创建 one-shot trigger，在 `now + recheckInterval` 再次执行。retry JobDataMap 固定原始 fire 时间、首次 defer 时间和原始 TriggerType，因此跨午夜不会漂移 bizDate，也不会因等待超过 misfire 阈值改变触发类型。
- **超窗 give-up**：等待达到 `readinessWindow` 后停止创建 retry trigger，记录 ERROR，并增加 `batch.trigger.quartz.readiness.timeout`。运维据此判断“上游严重延迟，本 bizDate 已放弃”，可走 batch-day replay 手动补。
- 原 Cron/FIXED_RATE trigger 保持不变；retry trigger 是独立的一次性触发，不修改正常调度表达式。
- 每次创建 retry trigger 增加 `batch.trigger.quartz.readiness.deferred`，对应 Prometheus 提前预警。

## fail-closed(结算优先)

就绪查询失败(orchestrator 不可达 / 超时 / 5xx)时,`UpstreamReadinessChecker` **不放行 fire**(返回未就绪)并记 `ERROR`。宁可不 fire 也不基于不确定状态盲 fire——结算链路要求。运维应对 ERROR 告警快速介入(多为 orchestrator 连通性 / secret 漂移)。

## freshness policy 告警

readiness defer 解决的是"依赖上游未就绪时不要盲 fire"。资产新鲜度策略解决的是"上游到了业务 SLA 还没产出时要显性告警"。

orchestrator 可通过 `batch.asset_freshness_policy` 配置 JOB asset 的 `expected_by_local_time / timezone / stale_after_seconds / lookback_days / severity`。定时扫描器只读当前 `EFFECTIVE asset_partition`:

- expectedBy 已过但宽限期未过,发 `ASSET_FRESHNESS_MISSING`。
- expectedBy + staleAfter 已过仍无 EFFECTIVE,发 `ASSET_FRESHNESS_STALE`。
- 已有 EFFECTIVE,不发告警。

这个告警不改变 trigger readiness 语义,也不把旧结果标为可消费。下游能否 fire 仍只由 `EFFECTIVE asset_partition` 决定。

## 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `job_dependency` | 空关系集 | 多上游关系；全部同 bizDate 的 EFFECTIVE asset partition 就绪才放行 scheduled fire |
| `job_definition.depends_on_job_code` | NULL | 首个上游的旧字段投影；用于兼容旧配置读取 |
| `batch.trigger.readiness-gate.enabled` | `true` | 全局 emergency switch;设 `false` 时一律放行(等价关闭依赖感知,应急用) |
| `batch.trigger.runtime.readiness-window-seconds` | `7200`(2h) | 等上游就绪的最长容忍窗口；超窗后停止 retry 并记录 ERROR |
| `batch.trigger.runtime.readiness-recheck-interval-seconds` | `30` | Quartz one-shot retry 的重检间隔 |
| `batch.asset-freshness.enabled` | `true` | asset freshness SLA 扫描开关 |
| `batch.asset-freshness.scan-interval-millis` | `60000` | freshness policy 扫描间隔 |
| `batch.asset-freshness.batch-limit` | `500` | 单轮最多扫描的策略数 |

## 边界 / 后续(v1 范围)

- 已支持多个上游 JOB + `SAME_DAY` / `PREVIOUS_BIZ_DAY` 对齐，所有上游均需满足对齐业务日最新版本的 EFFECTIVE_RESULT。配置下游作业业务日历后，`PREVIOUS_BIZ_DAY` 会跳过该日历及所属组的 `HOLIDAY`，并接受 `WORKDAY_OVERRIDE`；首次等待时把解析出的上游日期写入依赖快照，之后恢复不随日历变更漂移。未配置日历的旧作业继续按周一至周五推算。FILE_GROUP(文件组 TRIGGERED)作为作业依赖规则仍未实现；文件到达组已有独立触发与 Workflow sensor 路径。
- 超窗 give-up 目前是“停止 retry + ERROR 日志 + 人工 replay”，未自动落 `trigger_misfire_pending` 走 misfire 策略。
- Trigger Quartz defer 仍只覆盖正常 scheduled fire；其他 Trigger 路径由 Orchestrator 持久化等待器兜底。人工依赖 override 未开放；不要通过人工操作或 SQL 绕过依赖门闩。
- 不裁定上游业务对错(ADR-021 红线),只问"上游这个 job 这个批次日跑成功了没"。
- 不做通用 deadline/window-aware 优化调度器。后续最多补 late/at-risk/missed-window 标记和告警,不得把 trigger 扩成 Airflow/Temporal 类通用编排器。
