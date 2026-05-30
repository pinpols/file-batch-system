# P0 / P1 / P2 演进路线图(2026-05-29)

> 基于 [代码行统计](../stats/loc-2026-05-29.md) + 跟 DolphinScheduler / Azkaban 对比识别的核心缺陷。
>
> **优先级判定**:P0 = 业务扩展瓶颈;P1 = 故障 blast radius;P2 = 业务团队自服务能力。

---

## P0 任务类型 SPI / Plugin 化

### 问题

- Pipeline 5-6 stage 写死(`IMPORT/EXPORT/PROCESS/DISPATCH`),新业务集成(SFTP / API / MQ)= 改 BE + 新 worker 模块,周期 ≥ 1 个月
- Worker 模块按类型物理拆分(`batch-worker-{import,export,process,dispatch}`),粒度过粗,扩展类型必然加 module
- 对照:DolphinScheduler 40+ 任务类型走 SPI 注册,加一种 = 实现 1 个 Task 类 + 1 行注册

### 方案:Task SPI 框架

#### 1. 定义 SPI 契约(`batch-common` 内)

```java
public interface BatchTaskExecutor {
  String taskType();                          // 唯一标识(如 "SFTP_PUSH")
  TaskCapability capability();                // I/O 资源声明(网络/磁盘/CPU)
  TaskResult execute(TaskContext ctx) throws TaskException;
  void cancel(String instanceId);             // 取消语义可选
}

public record TaskContext(
    String tenantId, String instanceId, String jobCode,
    Map<String,Object> parameters, OutputCollector output,
    MetricsRegistry metrics, BatchTimezoneProvider tz);
```

#### 2. 注册机制

- `META-INF/services/com.example.batch.spi.BatchTaskExecutor` 标准 ServiceLoader
- 启动期 `TaskExecutorRegistry` 扫描所有实现 → `Map<String, BatchTaskExecutor>`
- 重复 taskType 启动期 fail-fast

#### 3. Worker 改造

- 把现有 4 个 worker 模块(import/export/process/dispatch)的核心逻辑**抽成 SPI 实现**,各自仍独立部署但**共享统一 worker runner**(在 `batch-worker-core` 里)
- 新加任务类型:开新 jar(可不开新 module)→ classpath 注册 → `batch-worker-runner` 自动加载

#### 4. 路由策略

- `WorkerRegistry`(已有)按 `taskType` → `workerEndpoint` 路由
- Orchestrator 调度时按 task type capability + worker capability 匹配
- 一个 worker 进程可注册多种 taskType(取决于打包)

### 阶段

| Phase | 工期 | 内容 |
|---|---|---|
| Phase 1:抽 SPI 契约 + Registry | 1 周 | `BatchTaskExecutor` / `TaskContext` / `TaskExecutorRegistry` + 守护测试 |
| Phase 2:现有 4 个 worker 改造为 SPI 实现 | 3 周 | import/export/process/dispatch 每个 ~1 周,行为不变 |
| Phase 3:**通用执行能力 4 件套 builtin**(见下) | 2 周 | Shell / SQL / StoredProc / HTTP |
| Phase 4:新增 1 个示范业务类型(如 SFTP_PUSH) | 1 周 | 验证扩展性,文档化 |
| Phase 5:Worker 打包多任务支持 | 1 周 | 一个 worker 进程注册多个 taskType |

**总:8 周,核心 BE 1 人**。

### Phase 3:通用执行能力 4 件套 builtin

#### 背景

当前 `JobType.GENERAL`("通用任务")**只是枚举占位,无 worker handler**。grep 全仓 0 处 `Runtime.exec` / `ProcessBuilder` / `CallableStatement` / `jdbcTemplate.execute(任意 SQL)`。

对照:DolphinScheduler / Airflow / XXL-Job 都把 Shell / SQL 作为开箱能力。**本项目当前业务深度强但通用执行为 0,比 XXL-Job 还少**。SPI 框架落地后必须立刻补齐,否则"通用调度平台"定位站不住。

#### 4 种 builtin TaskExecutor

| TaskExecutor | LOC 估计 | 工期 | 实现核心 |
|---|---:|---|---|
| `ShellTaskExecutor` | ~300 行 | 3 天 | `ProcessBuilder` + cgroup/Docker 隔离 + timeout + stdout/err 收集到 outbox |
| `SqlTaskExecutor` | ~400 行 | 3 天 | `NamedParameterJdbcTemplate.execute` + 多 SQL 分号切 + 事务可选 + result-set 落文件 |
| `StoredProcTaskExecutor` | ~300 行 | 2 天 | `JdbcTemplate.call(...)` + IN/OUT 参数 binding + 多 OUT REFCURSOR(PG) |
| `HttpTaskExecutor` | ~250 行 | 2 天 | RestClient + 鉴权(Basic/Bearer)+ 响应断言 + retry |

#### 安全 / 隔离硬约束(必做)

每种 executor 上线前必须解决:

| 能力 | Shell | SQL | StoredProc | HTTP |
|---|---|---|---|---|
| **资源隔离** | cgroup / Docker / k8s pod sandbox | 独立连接池 + statement timeout | 同 SQL | 独立 RestClient + connection-pool 上限 |
| **超时** | `Process.destroyForcibly()` + hard timeout | `setQueryTimeout()` | `setQueryTimeout()` | `TimeLimiter` |
| **审计** | 命令 + stdout 全落 audit_log | 完整 SQL + affected rows | 入参 + OUT 参 | 完整 URL + method + status |
| **权限** | 限定 user / 禁 sudo / chroot | 走专用 DB role(非 `batch_user`)+ DDL 白名单 | 同 SQL | 出口域名白名单 |
| **回滚语义** | 不可回滚(脚本自己负责) | 默认 autocommit OFF + 显式 commit/rollback | 走 SAVEPOINT | 不可回滚 |
| **多租户** | env 传 tenantId + 工作目录隔离 | 强制 search_path / WHERE tenant_id | 同 SQL | URL/header tenantId 注入 |

#### 落地顺序(2 周内)

| 周 | 内容 |
|---|---|
| Week 1 day 1-3 | `ShellTaskExecutor`(覆盖 50% 通用需求) |
| Week 1 day 4-6 | `SqlTaskExecutor`(覆盖 +20%) |
| Week 2 day 1-2 | `StoredProcTaskExecutor` |
| Week 2 day 3-4 | `HttpTaskExecutor` |
| Week 2 day 5 | 整体安全审计 + ArchUnit 守护(禁绕过 sandbox / 禁直接 `Runtime.exec`) |

#### 跟对标项目差距收口

| 能力 | DS | Airflow | XXL-Job | Azkaban | 本项目(Phase 3 完成后) |
|---|---|---|---|---|---|
| Shell | ✅ | ✅ | ✅ | ✅ | ✅ |
| SQL/DDL | ✅ | ✅ | ❌ | ❌ | ✅ |
| StoredProc | ✅ | ✅ | ❌ | ❌ | ✅ |
| HTTP | ✅ | ✅ | ❌ | ❌ | ✅ |
| Spark/Flink | ✅ | ✅ | ❌ | ❌ | ❌(不做) |
| Python | ✅ | ✅(原生) | ❌ | ❌ | ❌(走 Shell + `python3 xxx.py`) |

Phase 3 完成后通用能力 **追上 DolphinScheduler / Airflow 80%**(差大数据 adapter,可不做)。

#### 不做的(给边界)

- ❌ 不做 Python 直跑(走 Shell + `python3 xxx.py` 即可)
- ❌ 不做 Spark / Flink adapter(确实有大数据场景再单独立项)
- ❌ 不做 DSL 编排(走 workflow DAG 就够,不重复造)

### 风险

- 现有 worker 互不知对方,SPI 化要梳理共用基础设施(MinIO / Kafka / outbox)→ 抽 `WorkerSupport` 基类
- 业务方对"是否要写 Java"敏感 → 配 PythonGateway / DSL 也是后续话题(暂不做)

### 收益

| 指标 | 现在 | 后 |
|---|---|---|
| 新任务类型上线周期 | 1 个月 | **1 周** |
| 新增任务类型需改的代码 | BE + worker + Pipeline 协议 | **1 个 SPI 实现类** |
| 业务方独立扩展能力 | 0(必须 PR 进核心仓) | 可放外部 jar |

---

## P1-A console-api 拆分

### 问题

- `console-api` 44k main / 21k test,9 个职责域全堆一起
- 变一处全要回归;部署粒度太粗 → blast radius 大
- 对照:DolphinScheduler 拆 5 个进程(API / Master / Worker / Alert / Tools)

### 方案:三阶段渐进

#### Stage 1:模块内子包归一(0 部署变化,1-2 周)

按 9 个有界上下文重组 `console-api` 内 controller / service:

```
batch-console-api/src/main/java/com/example/batch/console/
  domain/<bounded-context>/
    job/         workflow/    file/        ops/         governance/
    notification/audit/       rbac/        observability/
```

强制守护:ArchUnit 加规则,禁跨 context 直接 depend(只走应用层 service / event)。

#### Stage 2:抽出运行时独立的(2-3 个月)

| 拆出模块 | 拆理由 | 大致 LOC |
|---|---|---:|
| `console-push-api` | Web Push 跟主业务无依赖,流量独立 | ~3k |
| `console-notification-api` | Alert / 渠道,SaaS 高扇出 | ~6k |
| `console-observability-api` | Dashboard / SLA,只读高 QPS | ~5k |
| `console-ops-api` | Trigger 代理 / Outbox 运维 / 集群诊断 | ~5k |

留在 `console-api` core:job / workflow / file / governance / rbac ≈ 25k(已合理)。

**约束**:
- 拆出模块共享同一 DB schema(避免分布式事务)
- 共享 `batch-console-shared`(auth filter / tenant guard / RBAC / Idempotent)
- 网关层(Nginx / SCG)按路径转发:`/api/console/push/* → console-push-api`

#### Stage 3:进程级灰度 + Owner 制(持续)

- 每个拆出模块有独立 owner / SLO / 告警
- 改 push 模块不 trigger ops 的 IT,CI 独立

### 不要做的

- ❌ 不拆 DB(分布式事务复杂度爆炸,业务上没必要)
- ❌ 不拆 orchestrator(状态主机本来就该单点)
- ❌ 不做 gRPC(同栈 REST 足够)

### 阶段

| Phase | 工期 | 风险 |
|---|---|---|
| Stage 1 子包归一 + ArchUnit | 1-2 周 | 低 |
| Stage 2 拆 push | 3 周 | 低(独立) |
| Stage 2 拆 observability | 4 周 | 中(只读聚合 + Redis 缓存策略) |
| Stage 2 拆 notification | 4 周 | 中(渠道适配 + 模板) |
| Stage 2 拆 ops | 4 周 | 中(代理类多,downstream 复杂) |
| Stage 3 owner 制 + SLO | 持续 | — |

**总:~4 个月(逐月拆 1 个),核心 BE 1 人**。

---

## P1-B downstream 降级统一

### 问题

- 哪些 endpoint 可降级、哪些必 fail-fast,**没有清单**
- 每个 proxy 自己写 try/catch,代码重复 + 风格不一致 + 容易遗漏
- 缺 metrics 和 circuit breaker

### 方案:Resilience4j + 声明式降级

#### 1. 引入

```xml
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
```

#### 2. 注解式降级

```java
@CircuitBreaker(name = "trigger", fallbackMethod = "triggerListFallback")
@TimeLimiter(name = "trigger")
public List<Object> triggerList() {
  return newClient().get().uri("/api/triggers/management/list").retrieve()
      .body(new ParameterizedTypeReference<CommonResponse<List<Object>>>() {}).data();
}

private List<Object> triggerListFallback(Throwable ex) {
  meterRegistry.counter("downstream.fallback", "service", "trigger", "op", "list").increment();
  log.warn("trigger downstream degraded: {}", ex.getMessage());
  return List.of();
}
```

#### 3. 策略配置

```yaml
resilience4j:
  circuitbreaker:
    instances:
      trigger:
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        slidingWindowSize: 20
      orchestrator-write:
        failureRateThreshold: 100   # 写路径不允许任何错降级
```

#### 4. 降级策略清单(在 `docs/runbook/downstream-degradation.md` 维护)

| Service / Endpoint | 类型 | 失败策略 | Fallback | Owner |
|---|---|---|---|---|
| trigger:GET /list | 只读 | 降级 | empty list + WARN | ops |
| trigger:POST /pause | 写 | fail-fast | — | ops |
| orchestrator:GET /scheduler-status | 只读 | 降级 | `{status: UNKNOWN}` | ops |
| dashboard:GET /sla-report | 只读 | 降级 + 缓存 stale | last-known cache | ops |
| push:POST /send-vapid | 异步 | retry 3 + dead-letter | — | notif |

**守护**:ArchUnit 加规则 — 所有 `*ProxyService` public 方法必须有 `@CircuitBreaker` 注解。

#### 5. 可观测

```yaml
management.health.circuitbreakers.enabled: true
management.endpoints.web.exposure.include: health,prometheus,circuitbreakers
```

- `/actuator/circuitbreakers` 实时状态
- Prometheus 抓 `resilience4j_circuitbreaker_state` / `failure_rate`
- Grafana 大盘:每个 downstream 失败率 / 断路状态

#### 6. FE 配合(温和提示)

BE 降级时响应头加 `X-Degraded-Source: trigger`。FE interceptor 看到挂 banner:
> ⚠️ trigger 服务暂不可用,数据可能不完整

### 阶段

| Phase | 工期 |
|---|---|
| 引 Resilience4j + 抽 fallback 模板 | 1 周 |
| 改造所有 `*ProxyService`(约 6-8 个) | 2 周 |
| 策略清单文档 + ArchUnit 守护 | 1 周 |
| metrics + Grafana 大盘 | 1 周 |

**总:5 周,核心 BE 1 人**。可与 P1-A 并行。

---

## P2 可视化 DAG 编辑器

### 问题

- workflow 只 JSON 定义 + 后端校验,前端只读
- 业务/运营要 workflow 必须懂 schema
- 对照:DolphinScheduler / Airflow 拖拽编辑是行业标准

### 方案:Vue Flow(MIT,Vue 3 原生)

**选型理由**:
- Vue 3 + TS 一等公民(React Flow 官方移植,4k+ star)
- API 极简:`<VueFlow :nodes :edges>` 几行
- 自定义节点 = Vue 组件,直接复用现有 EP 组件
- 内置拖拽 / 缩放 / minimap / multi-select / undo/redo

**对比备选**:
- AntV X6(Alibaba):功能更全,DolphinScheduler 用的就是;上手 1 周,适合后期升级
- LogicFlow(滴滴):BPMN 流程图侧重,跟 workflow DAG 不完全对
- 不要 G6(底层引擎,要的是 editor 不是渲染)
- 不要 GoJS(商业 $3500/dev 起)

### 阶段

| 阶段 | 工期 | 内容 |
|---|---|---|
| Week 1 | 1 周 | Vue Flow PoC,JobNode + GatewayNode + 存 JSON(序列化为 workflow_definition.nodes/edges) |
| Week 2-3 | 2 周 | 接 BE workflow validation API,前端即时校验(循环 / 孤儿 / 必填) |
| Week 4 | 1 周 | 运行时态可视化(节点挂状态徽章 + 实时刷新),复用 workflow viewer |
| Week 5-6 | 2 周 | 模板库 / 复制粘贴 / 撤销重做 / 版本对比 |

**总:6 周,核心 FE 1 人**。

### 不要做的

- ❌ 别 fork DolphinScheduler 前端(他们栈是 Naive UI / 老 Vue,改造比重写贵)
- ❌ 别从 G6 起步(太底层,5 倍开发量)
- ❌ 别一开始就追求 DolphinScheduler 的全部能力(MVP 即可,迭代加)

---

## 总投入与排期

| 项 | 工期 | 人 | 与其他依赖 |
|---|---|---|---|
| P0 SPI 化 | 6 周 | 核心 BE 1 人 | 独立 |
| P1-A 拆分 | ~4 个月 | 核心 BE 1 人 | Stage 1 完成后可并行 P0 |
| P1-B 降级统一 | 5 周 | 核心 BE 1 人 | 独立,可与 P1-A 并行 |
| P2 DAG 编辑器 | 6 周 | 核心 FE 1 人 | 独立 |

### 推荐 4 个月窗口排期

```
月 1:           P1-B 降级统一 (5w)     ──► 全 service 加上断路+fallback
                P1-A Stage 1 (1-2w)    ──► 子包归一 + ArchUnit
                P2 DAG editor 启动 (4w / 月)

月 2-3:         P0 SPI 化 (6w)         ──► 同步拆 1 个 console 模块 (push)
                P2 DAG editor 完工 (2w)

月 4:           P1-A Stage 2 继续       ──► 拆 observability / notification

月 5+:          P1-A 拆 ops + Owner 制持续推进
```

总:**约 4 个月可拿到所有 P0+P1+P2 的核心收益**(2 人并行,1 BE + 1 FE)。

### 推迟到 4 个月后的事(给排期减负)

- P0 后续(任务 DSL / 跨语言 SDK)
- P1-A 后续(orchestrator 内部拆分)
- 可视化补数 / 重算 UI(P2 之后的事)
- K8s Operator / Helm chart 完整化
