# Plan #3 — Soak Test(24h+ 长跑压测)

> r3 validation-infra · 优先级 P1 · 估时 1 天 + 一次半夜真跑

## 目标
扩展 `load-tests/` 加 soak / longevity 模式,持续 24h+ 跑生产 50% 流量(~200 TPS),
采 JFR + 周期 heap dump,验长期影响:连接池泄漏、静态缓存增长、跨日 bizDate 切换、
Hikari connection leak、Kafka consumer 累计 lag。

## 价值
- 现有 `run-worker-stress-tests.sh` 是 burst(几分钟),**不验长期**
- 短时压测验**吞吐 / 延迟**,soak 验**稳定性 / 可持续**
- 跨日翻批、JFR 内存趋势这类问题只有跑得够长才会显形

## 范围

**In scope**:
- `run-worker-soak-tests.sh` 主入口(已有骨架,补实现)
- JFR 启动参数 + 周期 heap dump 工具链
- 退出条件:任意 health 指标超阈值立即 stop 留现场
- 报告:`scripts/local/analyze-soak.sh` 解析 JFR 出 markdown 报告
- 跨日触发:通过 JVM 时钟偏移模拟日期前进 + 验 `batch_day_instance` 正常翻日

**Out of scope**:
- 不做集群伸缩(多副本压测 → 单独议题)
- 不做混沌注入(走 Plan #1 chaos IT)
- 不做生产真流量回放(走 Plan #4 forensic replay)

## 设计

### 流量模型
- RPS:200(生产 50%,可由 `SOAK_RPS` 覆盖)
- 持续:24h(`SOAK_HOURS=24`,可覆盖)
- 任务类型混合:IMPORT 30% / EXPORT 20% / DISPATCH 30% / WORKFLOW 20%(与生产分布一致)

### JFR 采集
启动参数:
```
-XX:StartFlightRecording=name=soak,duration=24h,filename=logs/soak/jvm-${pid}.jfr,settings=profile
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=logs/soak/
```

每 4h 主动 dump heap:
```
jcmd $pid GC.heap_dump logs/soak/heap-$(date +%Y%m%d-%H%M).hprof
```

### 退出条件(任一触发即 stop)
| 指标 | 阈值 |
|---|---|
| Heap usage 持续 >85% | 5 min |
| Hikari active connections == pool max | 1 min |
| Kafka consumer lag > 100k | 30 s |
| Error rate > 1% | 5 min |
| Disk usage > 90% | immediate |

### 报告项
- 内存:Heap / Eden / Old / Metaspace 趋势曲线(JFR `jdk.GCHeapSummary`)
- 线程:线程数 / 阻塞时长(JFR `jdk.ThreadCPULoad`)
- 连接池:Hikari active / idle / wait 时长
- GC:full GC 次数 / 累计停顿
- 业务:`job_instance` 完成数 / 平均延迟 P50/P95/P99
- 跨日:`batch_day_instance` 状态翻转记录

## 步骤拆解

### Step 1 — 流量驱动(3h)
- [ ] 抽取 `run-worker-stress-tests.sh` 的压力源逻辑成可复用模块
- [ ] 加 `--mode=soak` 长跑选项(rps + duration + 退出条件)

### Step 2 — JFR / heap 采集(2h)
- [ ] `start-soak.sh`:启动 console-api + 4 worker,JVM 参数注入 JFR
- [ ] `monitor-soak.sh`:后台 cron 跑 jcmd heap dump + 健康检查 + 退出条件评估

### Step 3 — 分析报告(2h)
- [ ] `analyze-soak.sh`:`jfr summary` + jq 解析 → markdown 报告
- [ ] 输出:`logs/soak/soak-report-YYYYMMDD.md`

### Step 4 — 跨日时间偏移(1h)
- [ ] 启动前 `faketime` 或 JVM `-Dbatch.testing.clock-offset=+12h`(需 BatchDateTimeSupport 支持注入 offset)
- [ ] 验 `batch_day_instance` 在测试时钟 00:00 翻批正确

> **TODO / 阻塞**:`BatchDateTimeSupport` 当前使用注入的 `Clock` bean(`clock.instant()`),**不读** `-Dbatch.testing.clock-offset`。
> `start-soak.sh` 已透传该 `-D` 参数,但要真正生效,需在 `batch-common` 的 Clock bean 配置上增加 offset 注入:
> 例如新增一个 `@Profile("soak") @Bean Clock offsetClock(...)` 用 `Clock.offset(Clock.systemUTC(), Duration.parse(...))` 包一层。
> 在该 bean 上线前,跨日时间偏移 走另一条路:用 `faketime`(macOS `libfaketime`)包 JVM 进程,或直接改宿主机时钟(不推荐)。
> 报告里的"跨日"维度会照常解析 `batch_day_instance`,只是触发依赖外部条件。

## 验收标准
- [ ] 一次 24h 真跑完成,报告无异常
- [ ] 报告里 5 项核心指标曲线平稳(heap 不持续涨 / 连接池不见底 / GC 停顿稳定)
- [ ] 至少 1 次跨日触发,`batch_day_instance` 状态机正确
- [ ] 退出条件触发时能 stop + 留 dump,不 hang

## 风险 / 依赖
- **依赖**:本地需 ~16GB RAM + 100GB 磁盘(24h JFR + 5 个 heap dump 约 20GB)
- **风险**:跑期间任何依赖容器挂(PG/Kafka/Redis)→ 报告无效需重跑
- **可控**:用 docker compose health check + 失败时 stop

## 检查点
| 里程碑 | 产出 |
|---|---|
| M1 | 流量驱动 1h 试跑通 |
| M2 | JFR + heap dump 自动化,可生成草报告 |
| M3 | 24h 真跑 + 完整报告 |
