# 全系统能力缺口分析（2026-06-20）

> **方法论自省**:本分析起于一次对话——用户逐个发现「压缩 / `.chk` / 文件组 / 动态地区」等到达侧需求,而非平台主动给出完整领域地图。两个教训:① 我先前是被动答题,未主动框定「批量文件/任务交付」这一完整领域;② 更严重的是,讨论一直锁在**单个 import worker 的到达侧**,而一个**交付闭环**系统最致命的缺口往往在**单 worker 看不到的闭环级 / 其余 worker**。本文用统一深度,跨**全 10 模块 + 端到端闭环**做一次能力勘察。
>
> 系统定位(CLAUDE.md):**批量任务编排控制面 + 文件/任务交付闭环**,`import → process → export → dispatch`。本分析对标**结算级(settlement-grade)**批量平台的行业要求。
>
> 每条结论均**实勘代码**(file:line),区分 IMPLEMENTED / PARTIAL / MISSING。不在本文档内提修复方案——只画现状与缺口。

## 勘察范围与进度

| 域 | 模块 | 状态 |
|---|---|---|
| A. 入站 import 生命周期 | batch-worker-import + orchestrator 到达治理 | ✅ 已勘 |
| B. 出站 + 端到端闭环 | export / dispatch / process / 闭环 | ✅ 已勘 |
| C. 触发 | batch-trigger | ✅ 已勘 |
| D. 编排状态机 | batch-orchestrator | ✅ 已勘 |
| E. 原子任务 | batch-worker-atomic | ✅ 已勘 |
| F. 控制面/运维 | batch-console-api | ✅ 已勘 |
| G. 共享运行时 | batch-worker-core + batch-common | ✅ 已勘 |

---

## A. 入站 import 生命周期

| 关注点 | 现状 | 证据 / 备注 |
|---|---|---|
| 对象存储轮询到达 | ✅ | `ImportIngressScanner`(30s 轮询) |
| 完成标记 `.chk` / 稳定窗口 | ✅ | done-file 后缀可配 + naming + MANIFEST(本轮 #569/#570) |
| 文件组凑齐 / 动态成组 | ✅ | arrival group + 批次清单(ADR-040,本轮) |
| size/checksum/签名校验 | ✅ | sidecar manifest(#570) + RSA 验签(`ImportPreprocessPipeline`) |
| 解压/解密/转码 | ✅ | ZIP/GZIP/TAR/AES/charset(#569) |
| 字段/行/数据集校验 | ✅ | `DatasetRuleEvaluator`:row_count_check / checksum_check / schema |
| 坏行→拒绝文件回上游 | ✅ | `ImportErrorOutputStorage`(error.jsonl) + `file_error_record` |
| 复跑 by bizDate | ✅ | `BatchDayReplayService`(ADR-020) |
| 同文件重投去重 | ✅ | `(tenant,checksum,storage_path)` 唯一 + 稳定窗口 |
| 跨日依赖 | ✅ | `CrossDayDependencyReconciler`(ADR-018) |
| **头/尾控制记录校验**(trailer 声明记录数 vs 实际行数) | ❌ **MISSING** | `header_template`/`trailer_template` 列存在但**无校验逻辑**;结算级刚需 |
| **控制金额对账**(汇总金额列 vs 声明总额) | ❌ **MISSING** | `DatasetRuleEvaluator` 无金额汇总比对 |
| 事件驱动(push)到达 | ❌ MISSING | 仅 30s 轮询,无 S3 通知 / inotify |
| 入站 SFTP/API/MQ | 🟡 PARTIAL | `source_type` 含 SFTP/API 但实现仅对象存储 |
| 单文件 SLA / 缺失主动告警 | 🟡 PARTIAL | 组级超时有;按 `cutoff_time` 的单文件 SLA 升级不显式 |
| 坏整文件 quarantine 待审车道 | 🟡 PARTIAL | 坏行隔离;坏整文件直接 FAILED,无隔离待审态 |

## B. 出站 + 端到端闭环

| 关注点 | 现状 | 证据 / 备注 |
|---|---|---|
| 出站文件完整性(checksum/size/sidecar) | ✅ | `DispatchManifestSupport`(SHA-256 JSON manifest,NAS/OSS/SFTP/EMAIL) |
| 投递保证(回执/ACK/重投/幂等) | ✅ | `DispatchReceiptPollScheduler` + `RetryDispatchStep` + `dispatch_attempt` |
| 下游 sidecar `.chk` | ✅ | 多渠道写,原子 rename |
| **出站文件内嵌头/尾控制记录**(笔数/金额写进文件供下游对账) | 🟡 PARTIAL | recordCount/totalAmount **只落 DB metadata,未写进输出文件本身**;`header_template`/`trailer_template` 列未用于生成 |
| **端到端控制总额连续性**(import→process→export→dispatch 逐跳重核) | ❌ **MISSING** | 各阶段各记各的 count,无跨阶段对账闸;ADR-021 approved 但**未实现** |
| **投递后目的端回读校验**(落地后 readback size/checksum) | ❌ **MISSING** | 仅收 ACK,不回读目的端验证内容 |
| process 转换正确性(input==output 不静默丢) | 🟡 PARTIAL | `ProcessPublishedCountVerifier` 只查 `publishedCount>0`,**不比对上游入数** |
| 跨 worker 血缘(记录级:入站行→staging→出站行) | 🟡 PARTIAL | 仅文件/任务级 traceId+file_id;**无记录级血缘** |

## C. 触发（batch-trigger）

整体**时间调度生产级**:Quartz 6/7 字段秒级 cron(`CronExpressionAdapter`)、日历切日/节假日 SKIP/PREV/NEXT(`CalendarBizDateResolver`)、misfire 三策略 NONE/AUTO/MANUAL_APPROVAL(`trigger_misfire_pending` + `CatchUpThrottle` 防雪崩)、幂等 fire(内存去重 + `trigger_request(tenant,dedup_key)` + 下游 `job_instance` 三层)、ShedLock leader 选举、手动 fire/pause/resume/approve 齐。

| 关注点 | 现状 | 备注 |
|---|---|---|
| 秒级 cron / 日历 / 节假日 | ✅ | `CronExpressionAdapter` / `CalendarBizDateResolver` |
| misfire / catch-up / 防雪崩 | ✅ | 三策略 + `CatchUpThrottle` |
| 幂等 fire / leader 选举 | ✅ | 三层去重 + ShedLock |
| 手动 fire/pause/resume/approve | ✅ | `TriggerManagementController` |
| **依赖感知 fire**(上游没好不 fire) | ❌ **MISSING** | 纯时间触发,无 `depends_on`/上游就绪检查;结算级级联风险 |
| **迟到容忍 / T+N value date** | 🟡 PARTIAL | cutoff 是二元的,无每触发宽限窗 + 无 T+N 算术 |
| **事件/文件到达触发** | 🟡 PARTIAL | `TriggerType.EVENT` 占位但无消费者;无 file-arrival 触发(只 cron+manual) |
| fire 历史 / misfire 告警 | 🟡 PARTIAL | 只留 last fire,无 N 日审计;无 misfire 阈值自动升级;无 next-fire ops API |

**触发侧 Top 缺口**:① 依赖感知 fire(MISSING)② 迟到/value-date 容忍(PARTIAL)③ 事件/文件到达触发未落地(PARTIAL)④ fire 历史审计 + 告警薄(PARTIAL)。

## D. 编排状态机（batch-orchestrator）

**运营能力很完整**:DAG(GATEWAY/JOB/TASK/WAIT/FILE_STEP + 补偿 6 类 handler)、资源调度(配额/优先级/分区限流/租户公平/令牌桶限流)、retry+dead-letter(退避+jitter+自动/手动 replay)、卡死检测(租约回收/心跳超时/workflow stuck reconciler)、依赖(跨日 ADR-018 + sensor)、批次日生命周期(OPEN/CUTOFF/SETTLE/CLOSE + replay)、部分失败(PARTIAL_FAILED + 分区重试 + checkpoint)、手动 cancel/terminate/retryPartition。

| 关注点 | 现状 | 备注 |
|---|---|---|
| DAG / 补偿 / 资源调度 / retry-DLQ / 卡死检测 / 跨日依赖 / 批次日 / 部分失败 | ✅ | 见上,生产级 |
| **准入控制 / 过载 load-shedding** | 🟡 PARTIAL | 限流是**硬拒绝**,无软节流/排队/降级——结算洪峰下正常请求会被拒(已知控制面瓶颈) |
| **实例 pause/resume** | 🟡 PARTIAL | 无 PAUSED 态,cancel 是破坏性的;无法"周五停、周一续" |
| **审批作为 DAG 节点** | 🟡 PARTIAL | 审批是独立 approval_command,**不是** workflow 节点;无法管线中嵌入审批闸 |
| **SLA 升级阶梯** | 🟡 PARTIAL | 只发单条告警事件,无 1h→2h→4h 分级升级 |
| **批次日严格串行依赖** | 🟡 PARTIAL | 日 N+1 不等日 N 完成,设计文档自标"缺口";并行跑日有跨日污染风险 |
| workflow 卡死判定 | 🟡 PARTIAL | 只按 `updated_at` 超时,无 DAG 活性检查(上游全终态但本节点没派发) |

**编排侧 Top 缺口**:① 准入/过载降级(PARTIAL,洪峰风险)② pause/resume 缺失 ③ 审批未进 DAG ④ SLA 单级告警 ⑤ 批次日串行未强制。

## E. 原子任务（batch-worker-atomic）

5 类执行器齐(shell/sql/stored-proc/http/spark-submit),超时/输出截断/SSRF/敏感词校验扎实,CLAIM/REPORT 契约 + AtomicErrorCode 分类。

| 关注点 | 现状 | 备注 |
|---|---|---|
| 执行器覆盖 / 超时 / 输出截断 / 失败分类 / 参数校验 | ✅ | 5 类 + 防护 |
| **长任务 task 级心跳** | ❌ **MISSING** | 只有 worker 级心跳,长 shell/sql 超 worker 心跳窗会被误判 worker 死/孤儿——**结算长跑任务关键风险** |
| **dual-use 命令持久审计表** | ❌ MISSING | shell/sql 命令只进 SLF4J 日志,无可查询审计表(合规/取证缺口) |
| **文件操作执行器**(copy/sftp/s3) | ❌ MISSING | 只能 shell/http 绕,脆 |
| SQL/storedproc 取消 | 🟡 PARTIAL | 只 shell/spark 支持 cancel();长 SQL 只能等超时 kill |
| 参数模板替换(`${bizDate}`) | 🟡 PARTIAL | 参数是字面 JSON,无变量替换,耦合上移 |
| Spark cluster 模式 | 🟡 PARTIAL | 仅 client 模式,cluster/K8s/YARN 阻塞(TODO) |

**原子侧 Top 缺口**:① 长任务 task 级心跳(MISSING,关键)② 命令持久审计表(MISSING,合规)③ 文件操作执行器(MISSING)④ SQL 取消 / 参数模板(PARTIAL)。

## F. 控制面/运维（batch-console-api）

**功能很丰富**:观测面板(job/trigger/worker/sla/tenant 多视图)、通知(EMAIL/钉钉/企微/Webhook/SMS + 路由 + ack/silence)、RBAC 4 角色 + 144 处 `@PreAuthorize` + 审计切面、配置 CRUD(草稿/发布/灰度/回滚 + Excel 校验)、租户自助/onboarding、文件到达/投递/死信/错误记录可视。

| 关注点 | 现状 | 备注 |
|---|---|---|
| 观测 / 通知 / RBAC+审计 / 配置 / 租户自助 / 运维可视 | ✅ | 见上,丰富 |
| **双控(maker-checker)强制** | 🟡 PARTIAL | 审批是**单段** approve/reject,无第二复核人强制;高危操作(数据修正/补跑)缺双控——违结算双控原则 |
| **人工数据修正 API(带护栏)** | ❌ **MISSING** | console 无受控数据修正端点,只能走 orchestrator internal 或裸改库(危险,且改不可溯到操作人) |
| **告警升级阶梯** | ❌ MISSING | 只有路由,无 ack 超时→升级→呼叫 on-call 的阶梯 |
| 卡死/积压可视 | 🟡 PARTIAL | 有 SLA compliance,缺"卡死 >N 小时""队列深度告警"统一端点 |
| force-complete / 补跑再审批 | 🟡 PARTIAL | 有 cancel/terminate,无结算补跑的"强制置成功+再派发"闸 |
| forensic 取证 | 🟡 PARTIAL | v0.1 同步 + 小 bizDate 范围,6 个月回溯不支持 |

**控制面 Top 缺口**:① 双控强制(PARTIAL,结算合规)② 人工数据修正受控 API(MISSING)③ 告警升级阶梯(MISSING)④ 卡死/积压统一可视(PARTIAL)。

## G. 共享运行时（batch-worker-core + batch-common）

**基础设施很扎实**:CLAIM + 双轨租约续期(10s 批 + 2s 快重试 + 熔断,ADR-016)、心跳+负载上报、幂等(ON CONFLICT(tenant,task_id) 报告去重)、优雅排水(DRAINING 4 步 + awaitDrain + 指标)、信号量背压(满则 pause Kafka)、标准 REPORT 信封(success/failureClass/traceId/outputs/verifierFailures)、MDC 结构化日志、启动自检(Flyway + schema 漂移 fail-fast)。失败分类 7 类(`FailureClass`)是一等公民。

| 关注点 | 现状 | 备注 |
|---|---|---|
| CLAIM/租约续期 / 心跳 / 幂等 / 排水 / 背压 / REPORT 契约 / 观测 / 启动自检 | ✅ | 见上,扎实 |
| **ADR-038 checkpoint/resume 接入** | 🟡 PARTIAL | `pipeline_progress` 表 + store 就绪,**但未接入 LoadStep/GenerateStep**(Phase 2/3 未合);大文件崩后从头重跑 |
| worker 侧退避调度 | 🟡 PARTIAL | 退避在 orchestrator 侧;worker 只有固定 2s 快重试 |
| 分布式追踪 | 🟡 PARTIAL | 只 MDC/traceId,无 OTel/W3C traceparent,跨服务 span 不串(够日志,不够 APM) |
| 分块部分结果 | 🟡 PARTIAL | REPORT 是原子成功/失败,无 per-chunk 错误流 |

**共享运行时 Top 缺口**:① ADR-038 checkpoint/resume 未接入(PARTIAL,大文件崩溃从头跑)② 分布式追踪未集成(PARTIAL,APM 缺)。

---

## 全系统综合排序（A–G 汇总）

> 把所有域的缺口放在一起看,**最大、最连贯、价值最高的一簇恰恰是「单 worker 看不到」的——对账完整性闭环**。这正印证了开头的自省:之前锁在 import 到达侧,错过了贯穿 import→process→export→dispatch 的闭环命门。

### 🔴 第一优先簇:对账完整性闭环(结算命门,跨多 worker)

| # | 缺口 | 域 | 为什么是命门 |
|---|---|---|---|
| 1 | **入站头/尾控制记录校验**(trailer 笔数/金额 vs 实际) | A | 行业第一道对账闸,`trailer_template` 列已躺着没实现 |
| 2 | **端到端控制总额连续性**(逐跳重核 count/amount) | B | process 静默丢 10% 仍 SUCCESS,无跨阶段对账 |
| 3 | **投递后目的端回读校验** | B | 落地内容不验,传输损坏/半写不发现 |
| 4 | **出站文件内嵌控制记录** | B | 下游只能靠可丢的 sidecar,文件本身无对账依据 |
| 5 | **ADR-021 数据质量闸落地** | A/B/F | approved 未实现,坏数据无 BLOCKER 拦截 |

> 这五条是**一个东西的五个面**:一条「控制总额(笔数+金额)从入站声明 → 逐跳重核 → 出站内嵌 → 落地回读」的贯穿闸。建议合成一个 ADR 统一设计,而不是散点补。**这是对你这套结算系统价值最高的一块,且明确属"文件传输完整性"、不越 ADR-021「不裁定业务对错」的边界。**

### 🟠 第二优先:可靠性 / 长跑 / 洪峰

| # | 缺口 | 域 |
|---|---|---|
| 6 | 长任务 **task 级心跳**(长 shell/sql 误判 worker 死) | E |
| 7 | **ADR-038 checkpoint/resume 接入**(大文件崩从头跑) | G |
| 8 | **准入控制 / 过载 load-shedding**(洪峰硬拒正常请求) | D |

### 🟠 第三优先:结算治理 / 合规

| # | 缺口 | 域 |
|---|---|---|
| 9 | **双控 maker-checker 强制**(高危操作无第二复核) | F |
| 10 | **人工数据修正受控 API**(只能裸改库,不可溯) | F |
| 11 | **dual-use 命令持久审计表**(shell/sql 只进日志) | E |
| 12 | **告警升级阶梯**(无 ack 超时→升级→呼叫) | F |

### 🟡 第四优先:调度 / 到达 / 准实时

| # | 缺口 | 域 |
|---|---|---|
| 13 | **依赖感知 fire**(上游没好就不 fire) | C |
| 14 | **事件驱动到达**(S3 通知,准实时) + 单文件 SLA 主动告警 | A |
| 15 | **实例 pause/resume** + 批次日严格串行 | D |
| 16 | 事件/文件到达触发类型落地 | C |

## 结论

你的系统在**编排 / 依赖 / 复跑 / 幂等 / 资源调度 / 到达组装 / 投递机制 / 控制面观测**上是**生产级、偏强**。真正的系统级短板集中在**「对账完整性闭环」**——一个贯穿四个 worker、单 worker 视角看不到的命门;这也是为什么它要由"整体视角"才能发现,而不是逐个问 import worker 能问出来的。**下一步最高价值动作:为「控制总额贯穿闸(#1–#5)」立一个 ADR + 分阶段落地。**

---

## 行业痛点交叉验证（联网）

- **「instrument count 作为对账基线」**:行业把「收到笔数/记录数 vs 声明值」当作**最基础对账点**([BMC](https://www.bmc.com/it-solutions/finance-automation/transaction-processing.html))——命中 A 的 **trailer 记录数校验缺失**(最大洞)。
- **「reconciliation lag / 整批跑完才对账,中间 flying blind」**([Optimus](https://optimus.tech/blog/the-reconciliation-lag-why-your-batch-based-process-is-liability-in-the-era-of-real-time))——对应批架构准实时短板。
- **「completeness monitoring:是否收到预期 files/partitions,抓静默丢失」**([Databricks](https://www.databricks.com/blog/what-is-data-observability))——文件级 completeness 已覆盖(arrival group+批次清单);**记录级 completeness(trailer 数)仍缺**。
- **「late/missing data + freshness checks」**([OvalEdge](https://www.ovaledge.com/blog/real-time-data-lineage-tracking))——有 arrivalDelay 指标(事后),缺按 cutoff 的主动 SLA 升级。
- **「dependency 一处延迟→下游级联 SLA breach」**([JAMS](https://www.jamsscheduler.com/blog/batch-job-dependency-best-practices/))——跨日依赖是强项。
- **「rerun / idempotency / self-healing」**([ActiveBatch](https://www.advsyscon.com/blog/enterprise-job-scheduler/))——replay+dedup+retry 齐,强项。

---

## 系统级 Top 缺口（综合 A–G,排序）

> **待 5 个 agent 回填 C–G 后,在此给出全系统综合排序。** 截至目前(A+B)最高优先级:
>
> 1. 🔴 **头/尾控制记录校验**(入站 trailer 笔数/金额 vs 实际)——结算对账承重墙,行业第一道闸,`trailer_template` 列已躺在那未实现。
> 2. 🔴 **端到端控制总额连续性**——import→process→export→dispatch 无跨阶段对账,process 静默丢 10% 仍标 SUCCESS。
> 3. 🔴 **投递后目的端回读校验**——落地内容不验证,传输中损坏/半写不发现。
> 4. 🟠 **出站内嵌控制记录**——下游只能靠可丢的 sidecar,文件本身无对账依据。
> 5. 🟠 **ADR-021 数据质量闸未实现**——坏数据可达 EFFECTIVE 无 BLOCKER 拦截。
> 6. 🟠 **process input==output 校验缺失** / **事件驱动到达** / **单文件 SLA 主动告警**。

## 范围边界提醒（避免越界）

trailer 记录数 / 控制总额校验 = **文件传输完整性**(收到的=发出的),**不是** ADR-021 的「裁定业务对错」,在系统范围内、不越界。端到端 count 连续性同理。区分清楚:对账「数对不对、文件全不全」√ vs 「业务该不该这么算」✗。
