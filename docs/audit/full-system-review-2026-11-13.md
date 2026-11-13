# 全面审查：安全、数据库、故障恢复、部署、测试及其他专项

日期：2026-11-13。基线：后端 `main` `bdb94501`，前端 `batch-console` `main` `9d49c304`。只读静态审查，未修改代码或配置，未编译、未运行测试、未连接任何环境。数字来自文本与正则扫描，是近似值；凡计数结论都经过抽查，抽查中发现的扫描误报已剔除。

## 范围与定位

本次覆盖此前审查未系统查过的十个方向：安全、数据库迁移与表设计、故障恢复、多语言 SDK、可观测性、部署与运维、测试体系、前端专项、依赖与供应链、文档与代码一致性。

Console 内部与前后端接口契约的结论在[同日审查](architecture-review-console-frontend-contract-2026-11-13.md)（ARCH-20261113-01..08），模块边界与表写入权限在 [11-03 审查](architecture-design-review-2026-11-03.md)，本文不重复。本文问题编号接续为 ARCH-20261113-09..14。

结论等级：**有当前证据**（本基线源码可定位）、**推断**（由源码与注释推出，未复现）、**建议**。静态证据不代表运行时已发生故障。

## 总体结论

没有发现可直接利用的高危漏洞，也没有设计方向上的问题。新增 6 项，集中在三类：接口权限比菜单宽、迁移与 Redis 依赖带来的运行风险、内部端点依赖默认关闭的网络隔离。可观测性、依赖治理、前端安全基线和文档一致性状态良好。

## 问题台账

### ARCH-20261113-09 / P2 / 有当前证据：部分查询接口的权限比菜单宽

- `/api/console/**` 的基线是"四种角色之一即可访问"（`ConsoleSecurityConfiguration`：ADMIN、TENANT_ADMIN、AUDITOR、TENANT_USER）。381 个接口中 302 个另有 `@PreAuthorize`；其余 79 个只有基线，其中 47 个在 `ConsoleQueryController`。
- 已确认一例：`menu.yml` 中"审计日志""操作审计"只对 ADMIN、TENANT_ADMIN、AUDITOR 开放，对应的 `GET /audits`、`GET /operation-audits` 没有角色检查。`OperationAuditQueryService` 只按租户过滤，因此 TENANT_USER 直接调接口能读到本租户的操作审计，包括管理员的操作记录。未找到拒绝 TENANT_USER 的测试。
- 其余 45 个只有基线的查询接口未逐个与菜单比对。
- 不受影响的：`ConsoleAiController` 的 13 个接口虽无 `@PreAuthorize`，但服务层 `ConsoleAiAuthorizationService.assertAllowed` 按配置白名单校验。

### ARCH-20261113-10 / P2 / 有当前证据：平台库的租户隔离只在应用层

- 数据库层 RLS 按设计只覆盖业务库（`biz` schema，见[多租户 RLS 手册](../runbook/multi-tenant-rls.md)）。平台库 `batch` schema 约 129 张表带 `tenant_id`，迁移中只有 35 张启用了 RLS；抽查的几张策略是按数据库角色限定访问范围，不是按租户过滤。
- 平台库的租户隔离依靠 SQL 里的 `tenant_id` 条件、`TenantIdResolver`，以及 10 个模块各自的 `MapperXmlTenantGuardArchTest`。这是有意的取舍，但等于少一层纵深防御：动态 SQL 或守卫漏检的写法漏掉租户条件时，数据库不会拦。
- 业务库的叶子分区问题 PART-01 仍未闭环，已登记在待办总表"数据库完整性、索引与分区"行，本文不重复。

### ARCH-20261113-11 / P2 / 有当前证据（表规模影响待验证）：迁移在既有表上建索引不用 CONCURRENTLY，也不设锁超时

- 在已存在的表上建索引的语句中，190 条是普通 `CREATE INDEX`，22 条用 `CONCURRENTLY`。V200 之后涉及热表的普通建索引包括 `job_partition`（V317、V318、V324、V366）、`job_task`（V317、V319、V320）、`job_instance`（V225、V300、V318、V334）、`workflow_run`、`trigger_request`、`file_record`、`pipeline_instance` 等。
- 普通 `CREATE INDEX` 会在建索引期间阻塞该表写入。383 个迁移中没有一个设置 `lock_timeout`，应用配置里也没有找到 Flyway 层面的锁超时。表大时，部署期间可能长时间阻塞调度主链路，或在锁等待中排队。
- 做得好的：59/133 个 CHECK 约束和 36/56 个外键以 `NOT VALID` 方式添加，避免了全表校验锁。
- 未核实：各表在目标环境的实际行数。

### ARCH-20261113-12 / P2 / 有当前证据（运行影响为推断）：Redis 是多条关键路径的硬依赖

- ShedLock 默认使用 Redis（`BATCH_SHEDLOCK_PROVIDER:redis`），Orchestrator 53 个、Trigger 6 个定时任务依赖它；切到 JDBC 需要"全停、改配置、全起"。
- Console 幂等拦截器在 Redis 不可达时 fail-closed，返回 503（见 ARCH-20261113-01）。
- 推断：Redis 不可用期间，加锁的定时任务（outbox 投递、对账、超时治理等）无法获得锁而跳过，Console 带幂等键的写操作全部失败。未在真实环境验证，也没有找到 Redis 故障演练的记录。
- 未覆盖：Kafka 和 PostgreSQL 主库不可用时各模块的行为。

### ARCH-20261113-13 / P2 / 有当前证据：内部端点依赖默认关闭的网络隔离

- Helm 的 `networkPolicy.enabled` 全局默认 `false`；原子任务 Worker 的网络策略只限制出站（`policyTypes: Egress`）。
- 原子任务 Worker 暴露 `health,info,prometheus,loggers,atomicruntime`，没有接入 Spring Security。Helm 为它设置了独立的管理端口，但网络策略未开启时，集群内任何 Pod 都能访问，包括通过 `POST /actuator/loggers` 修改日志级别。对比：Console 把 `/actuator/loggers` 限定为 ADMIN；Orchestrator 内部接口有 `InternalAuthFilter` 和请求签名；Import Worker 的内部接口有共享密钥校验。
- **2026-11-14 部分实施**：原子任务 Worker 默认暴露改为 `health,info,prometheus,atomicruntime`，不再包含可写的 `loggers`，需要时用 `BATCH_WORKER_ATOMIC_ACTUATOR_EXPOSURE` 临时加回；`AtomicRuntimeLocalProfileTest` 守护默认与 local 两种配置。仓库没有生产 values 文件，`networkPolicy.enabled` 默认值仍为 `false`，是否默认开启需运维按目标集群的 CNI 与放行规则决定，仍开放。

### ARCH-20261113-14 / P3 / 有当前证据：测试依赖时序

- 后端测试中有 91 处 `Thread.sleep`（另有 115 个文件使用 Awaitility）。
- 最近两周 `main` 的 Full CI 失败多数是测试竞态，已逐个修复（#1338、#1343、#1344、#1348）。按此趋势仍会有新的偶发失败。
- 仅 2 处 `@Disabled`，没有隔离或标记为 flaky 的测试，说明问题都在正面修，没有被跳过。

## 核对后状态良好

- **可观测性**：Prometheus 告警规则引用的 85 个指标都能在代码或运维脚本中找到注册处；上一轮已删除未注册的指标名常量（#1360）。
- **依赖与供应链**：Spring Boot 4.1.2、Java 21；两个仓库都配置了 Dependabot、CodeQL，后端另有许可证审查流水线。未逐项核对现有漏洞告警。
- **前端安全与体验基线**：85 个路由全部按需加载，有打包体积检查脚本；不使用 `v-html`；令牌不存 `localStorage`；模板里写死的中文只剩 2 处；有 479 处 aria/role 标注和 1 个无障碍端到端测试。
- **多语言 SDK**：有持续维护的[能力对齐矩阵](../sdk/sdk-parity-matrix.md)和共享夹具契约，CI 有 SDK 契约任务。本次未重读各语言实现。
- **部署**：各模块配置了资源请求与上限、存活和就绪探针、PDB，JVM 内存按容器上限比例设置。Trigger 默认单副本，扩容前需调整连接预算，values 注释已说明。
- **文档与配置**：`.env.example` 的 306 个变量中只有 `BUSINESS_DB_SCHEMA` 无任何引用；其余经 Spring 环境变量绑定生效。`X-Operator-Id` 的文档错误已在 #1361 更正。
- **Console 鉴权基线**：`/api/console/**` 至少需要一个有效角色，Alertmanager 回调用常数时间比较的共享令牌，CSRF 使用 Cookie 令牌。

## 建议顺序（未授权实施）

1. ARCH-20261113-09：逐个比对 79 个只有基线的接口与菜单，补 `@PreAuthorize` 和拒绝测试。改动小，直接收紧权限。
2. ARCH-20261113-11：迁移门禁要求热表建索引用 `CONCURRENTLY`（或显式豁免），并统一设置 `lock_timeout`。
3. ARCH-20261113-13：生产 values 默认开启网络策略，或给原子任务 Worker 的管理端点加认证；至少不暴露可写的 `loggers`。
4. ARCH-20261113-12：整理 Redis 故障模式表，在测试环境做一次演练，再决定 ShedLock 默认是否改为 JDBC。
5. ARCH-20261113-10：决定是否给平台库热表补租户 RLS，或明确接受应用层隔离并记入架构文档。
6. ARCH-20261113-14：随功能改动逐步把 `Thread.sleep` 换成按条件等待。

## 未覆盖与未验证

- 全部结论来自静态扫描，未运行系统，未连接目标环境，未做渗透测试。
- 故障恢复只看了 Redis；Kafka、PostgreSQL 主库、对象存储不可用时的行为未审查。
- 只有基线权限的 79 个接口中，只逐个核对了审计相关的两个和 AI 的 13 个。
- 未统计目标环境的表行数，无法评估迁移锁的实际持续时间。
- 未重读各语言 SDK 实现，未逐项核对依赖漏洞告警。
