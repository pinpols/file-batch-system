# Plan — 多租户隔离 三件套(业务库 / Worker 运行时 / 自托管 SDK)

> 优先级 P0(业务库 RLS)+ P1(SDK)+ P2(Worker pool)· 估时见各 phase
>
> **本 plan 不取代 ADR-035**,而是把 ADR-035 放进更大的"多租隔离总图"里,跟其他两条隔离线一起规划。

## 1. 背景与必要性

当前架构对**多租户隔离**的承诺只到「**应用层行级 `tenant_id`**」:

| 维度 | 当前实现 | 隔离强度 |
|---|---|---|
| 业务库(`batch_business.biz.*`)| 1 库 1 schema 1 DB user,所有租户行级 `tenant_id` 共享 | 应用层防御(可被代码 bug 击穿) |
| Worker 进程(`batch-worker-*`)| 全租户共用同一组进程 / 线程池 / Kafka consumer group | 物理共用 |
| 凭据(SFTP / MinIO / DB)| 平台级,所有 worker 同一份 | 0 隔离 |
| 资源池(线程 / 连接)| 进程级共享,无 per-tenant 配额 | 0 隔离 |

**已经踩过的痛(或迟早会踩)**:
- 跨租户数据泄露(`MapperXmlTenantGuardArchTest` 已守住一次,动态 SQL 仍有风险)
- Noisy neighbor:大租户文件挤兑小租户(扩容缓解,不解决)
- 故障传染:A 租户怪文件触发 worker OOM → B/C 受影响
- 合规:多客户混部场景下数据隔离要 DB 层证据,不是「我们代码挺好的」

**三件事彼此关联但不是同一件事**:
- **业务库隔离**:解「DB 层防御」+「合规证据」+「租户能加自己的业务表」
- **Worker 运行时隔离**:解「故障传染」+「Noisy neighbor」+「资源公平」
- **SDK 自托管**:解「跨语言」+「自家依赖」+「完全自助」,顺带绕开前两个(租户连自己 DB,跑自己 worker)

**关键洞察**:**SDK 是 escape hatch**(不解决前两个,但提供"我不想跟别人共用"的选项);**业务库隔离 + Worker 隔离是底盘**(平台代运维的租户必须靠这俩)。

## 2. 三件套相互关系矩阵

| 解决 \ 方案 | 业务库 RLS | 业务库 schema 分 | 业务库 per-tenant DB | Worker per-tenant pool | SDK 自托管 |
|---|---|---|---|---|---|
| 跨租户数据泄露(代码 bug)| ✅ DB 兜底 | ✅ schema 隔离 | ✅ 物理隔离 | — | ✅ 平台不碰租户 DB |
| 噪声邻居(吞吐 / 优先级)| — | — | — | ✅ 独立 pool | ✅ 完全独立 |
| 故障传染(OOM / 卡)| — | — | ✅(连接池独立)| ✅ | ✅ |
| 凭据 blast radius | ✅ per-tenant DB role | ✅ | ✅ | — | ✅ |
| 租户加自己业务表 | ❌ | ✅(自己 schema)| ✅(自己 DB)| — | ✅(连自己 DB)|
| 合规:DB 层证据 | ✅ | ✅✅ | ✅✅✅ | — | ✅(数据不在平台)|
| 升级独立 | — | — | ⚠️(Flyway 跨库)| ✅ | ✅✅ |
| 实施成本 | 低 | 中 | 高 | 中 | 中 |
| 主架构改动 | 0 | 中 | 大 | 中 | 小 |

> **读这个矩阵的方式**:每行问一遍「这个问题我要不要解、用哪一列解最合算」。多列可叠加(RLS + per-tenant pool 同时上)。

## 3. 决策框架:不同租户画像 → 走哪条

| 租户画像 | 推荐组合 |
|---|---|
| 中小租户,平台预设业务模型够用 | **RLS** + 共用 worker(扩容)|
| 中等租户,要自己加业务表 | **RLS** + **per-tenant schema** + 共用 worker |
| 大租户,要 SLA / 不希望被噪声 | **RLS** + 共用 schema + **per-tenant worker pool** |
| 不信任平台 / 跨语言 / 自家闭环 | **SDK 自托管**(数据不进平台库)|
| 合规要求物理隔离 | **per-tenant DB** + per-tenant worker(或建议走 SDK)|

**默认推荐 = RLS(必做)+ 共用 worker(扩容兜)+ SDK(给真不想共用的人)**。`per-tenant schema/DB/pool` 是按租户单独开通,不是默认形态。

## 4. 实施序(关键)

```
[现状] 应用层 tenant_id + RLS 已上线(transition/fail-open,未真正生效)
        ↓
[Phase A] 业务库 RLS — happy-path 已落地(#155/#158)  ← 剩 R1/R2/R3 收口才算真防御,约 1 周
        ↓
[Phase D] Per-tenant worker pool(几乎免费)← 核心代码已 ready,只需 Helm 模板,1.5-2 周
        ↓
[Phase B] SDK 自托管(ADR-035)            ← 解租户技术栈自主 + 数据驻留,8-10 周
        ↓
[Phase C] Per-tenant schema(否决,见 §6.1)
        ↓
[Phase E] Per-tenant DB(终态,大概率不做) ← 合规硬要求才做
```

**为什么这个序**:
- Phase A 是"防御",最便宜、不破现状、所有租户受益 —— happy-path 已先做完(#155/#158),现在卡在 R1/R2/R3 收口
- Phase B 是"escape hatch",独立于 A/C/D 完成时间,可并行
- C/D 是"按需开通",有了 A+B 之后基本只在大租户合同里出现,**不必默认做**
- E 是终态选项,几乎不该走(走了就该用 SDK)

**关键依赖**:
- B(SDK)不依赖 A(RLS)— 但 A 做完后,SDK 用户也可以选择「不连自己 DB,连平台 staging」时享 RLS 防护
- C(schema 分)需要先做 A(RLS 是 schema 内的兜底)
- D(worker pool)独立于 A/C — 但应该在 B 之后做(B 已经解了大部分大租户诉求)

## 5. 各 Phase 详细规划

### Phase A · 业务库 PostgreSQL RLS(P0)

> **状态更新(2026-05-31,按实际代码复核)— Phase A 的 happy-path 已落地并合并入 main,不再是待建工作:**
> - PR #155「Phase A 6 步完整落地」+ PR #158「worker 写入入口包 `RlsTenantContextHolder` + SET LOCAL」已 merge 到 main。
> - 已存在:`batch-common/.../rls/`(`RlsTenantContextHolder` / `RlsTenantSessionSupport` / `RlsPolicyHealthIndicator`)、`RlsTenantIsolationIntegrationTest` + `RlsPhaseAMigrationCoverageTest`、`scripts/db/business/rls-phase-a.sql`。
> - worker 写入路径已经过 `RlsTenantContextHolder` 做 SET LOCAL(`AbstractPipelineStepExecutionAdapter` / `SqlTransformComputePlugin`)。
> - **但当前是 transition / fail-open 模式,且账号 & 守护未收口 → RLS 现在不提供实际防护(见下方「出口红线」R1/R2/R3)。** 下面的「改造点 / Phase 拆解 / 出口」是历史规划,实际状态以红线为准。

**目标**:在不改业务模型 / 不分库分 schema 的前提下,**DB 层强制 `tenant_id` 过滤**,杜绝「应用 SQL bug → 跨租户数据泄露」。

**设计(下面是目标 strict 形态;实际已落地的是 transition 形态,见注释)**:

```sql
-- 启 RLS
ALTER TABLE biz.customer_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE biz.customer_account FORCE ROW LEVEL SECURITY;

-- 目标 strict 形态(尚未上线):
CREATE POLICY tenant_isolation ON biz.customer_account
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- 实际已上线 transition 形态(rls-phase-a.sql):policy 名 tenant_isolation_transition,TO PUBLIC,
--   USING/WITH CHECK 多一个 `current_setting('app.tenant_id', true) IS NULL OR = ''` 的逃逸分支
--   → app.tenant_id 未设/空时放行全部(fail-OPEN,向后兼容)。全 worker 上 SET LOCAL 后,
--      后续 PR 删逃逸分支转 strict(见 R2)。

-- 应用侧:每个事务起来前 SET LOCAL app.tenant_id = 'ta'
```

**改造点(实现状态已标注)**:
- ✅ `BatchPgSessionAutoConfiguration` / `HikariPgSessionSupport`(已有,管 statement_timeout / lock_timeout)→ 已扩展 `app.tenant_id` SET LOCAL
- ✅ worker 写之前 `tenant_id` 经 `RlsTenantContextHolder` 包装到 connection-scoped session var(#158;**需复核是否覆盖 import/export/dispatch 全部直写路径**,目前确认到 pipeline step adapter + sql plugin)
- ⚠️ 9 张 biz 表 + `batch.process_staging`(共 **10 张**)加 policy —— **不是 Flyway migration**,而是手工 psql 脚本 `scripts/db/business/rls-phase-a.sql`(`psql -d batch_business -f`);因 biz 表本身也非 Flyway 管(`create_biz_tables.sql` 由 ops/seed 脚本建)
- ✅ 测试:新建 `RlsTenantIsolationIntegrationTest`(命中/绕过反例)+ `RlsPhaseAMigrationCoverageTest`(脚本覆盖白名单守护)—— **不是**「扩 `MultiTenantIsolationIntegrationTest`」
- ⚠️ 平台运维聚合用 `BYPASSRLS` role —— 脚本创建的是 `batch_business_admin`(BYPASSRLS)+ `batch_business_writer`(应用,RLS 生效);**现役 `batch_user` 未收口(R1)**

**Phase 拆解(实际落地状态)**:
| 步骤 | 内容 | 状态 |
|---|---|---|
| A1 | DB role:脚本建 `batch_business_writer`(应用,RLS 生效)/ `batch_business_admin`(BYPASSRLS,平台聚合)| ⚠️ 部分 —— role 已建,但现役 `batch_user` 未迁移/未收口(R1) |
| A2 | `BatchPgSessionAutoConfiguration` / `RlsTenantContextHolder` 加 `app.tenant_id` SET LOCAL | ✅ 已落地(#158;待复核全写路径覆盖) |
| A3 | 给 10 张表加 ENABLE+FORCE+policy —— **手工 psql 脚本** `rls-phase-a.sql`(非 Flyway)| ✅ 已落地(transition 模式) |
| A4 | 单测:`RlsTenantIsolationIntegrationTest` 命中/绕过反例 | ✅ 已落地 |
| A5 | POLICY 漏配守护 —— 现为白名单(`EXPECTED_RLS_TABLES`)| ⚠️ 白名单会漏新表(R3) |
| A6 | `RlsPolicyHealthIndicator`(`pg_policies` healthcheck)| ✅ 已落地(但也是白名单,R3)|

**风险**:
- 部分动态 SQL 拼接没走 session 变量 → 漏 policy → 反而被拒(应用报错而非泄露)。**好事,变可见错误**
- 性能:RLS policy 加 `WHERE` 子句,索引必须含 `tenant_id` 作首字段(现有索引已有,审一遍)

**出口(happy-path 部分已达成)**:
- [x] 10 张表(9 biz + `batch.process_staging`)RLS 启用 + policy 部署(transition 模式)
- [x] `RlsTenantIsolationIntegrationTest` RLS 反例,全过
- [x] `pg_policies` healthcheck 集成 actuator(`RlsPolicyHealthIndicator`)
- [ ] runbook `docs/runbook/multi-tenant-rls.md` 写清 BYPASSRLS 何时用、policy 怎么 review

**出口红线(阻断项 —— 不满足则 RLS 形同虚设,必须在 Phase A 真正收尾前全绿)**:

> 上面的「出口」是功能完成度(已基本达成),这三条是**安全有效性**。任一未达成 → RLS 只是装了脚手架,不提供实际防护。当前三条**都未达成**。

- [ ] **R1 · 现役业务账号必须被收口为非 owner / 非 superuser / NOBYPASSRLS。**
  - 现状漏洞:`rls-phase-a.sql` 只对新建的 `batch_business_writer` 收口干净;真正在用的 `batch_user` 仅在 `NOT rolsuper` 时才 `ALTER ... NOBYPASSRLS`。**`ALTER ROLE NOBYPASSRLS` 治不了 superuser —— superuser 无视 RLS 是另一条独立通道,连 `FORCE` 都拦不住。** 若现役账号是 superuser 或 biz 表 owner,则 `pg_policies` 查得到 policy 但一行都不生效(静默失效)。
  - 达成标准:核实生产/预发的 business DataSource 账号**不是 superuser、不是 biz 表 owner、无 BYPASSRLS**;worker 迁到 `batch_business_writer`;`batch_user` 权限收敛或停用。需 DBA 配合(改 `BusinessDataSourceProperties.username` + 回收 `batch_user`),脚本做不到。
- [ ] **R2 · transition 模式必须翻成 strict —— 否则现在 fail-OPEN。**
  - 现状漏洞:policy `TO PUBLIC` 且 `current_setting('app.tenant_id', true) IS NULL OR = '' → 允许全部`。即**任何忘记 `SET LOCAL app.tenant_id` 的连接都放行全表**,而「应用 SQL bug / 漏带 tenant_id」正是 RLS 要兜底的场景 → 当前模式下毫无兜底,RLS 等于装了没开。
  - 进度:#158 已把 SET LOCAL 铺到 pipeline step 写入路径;达成标准 = 铺到**每一个** worker 写连接路径并验证后,删 `IS NULL/''` 逃逸分支转 strict(变量未设 → 0 行 / 拒写,fail-closed)。
- [ ] **R3 · 守护从「白名单」翻成「闭世界」,且 fail-fast。**
  - 现状漏洞:`RlsPolicyHealthIndicator.EXPECTED_RLS_TABLES` 是硬编码 Java 清单,`RlsPhaseAMigrationCoverageTest` 只校验「清单 ⊆ 脚本」。新增 `biz.foo` 若同时忘了加清单 + 忘了加脚本 → 该表 fail-open(跨租户可读),而 healthcheck 绿、test 绿、无报警。**biz 表会越来越多,白名单必漏且漏了静默泄露。**
  - 达成标准:守护从**活动 catalog** 出发 —— 查 `pg_tables WHERE schemaname='biz'`,断言**每一张实际存在的表**都 `ENABLE+FORCE+有 policy`;硬编码清单退化为「已知豁免名单」(同 CLAUDE.md 4 张系统表豁免写法)。漏配 → 启动 fail-fast / CI 红,而非静默放行。

**执行环节(R2/R3 判定逻辑在哪跑 —— 分层,主力是启动 fail-fast)**:

> 前提:biz 表与 `rls-phase-a.sql` 都不归 Flyway 管(脚本由 ops/seed 跑),「建表后记得再跑 RLS 脚本」这个**执行顺序**最易断 —— 必须用机制兜,不能靠人记。

| 层 | 环节 | 职责 |
|---|---|---|
| 主力 | **应用启动 fail-fast**(扫真实 business DS 的 `biz` schema,挂 `ArchiveSchemaDriftCheck` 同款 hook)| 闭世界校验,漏配 → 拒绝启动(非 health DOWN);只有连真实库才看得到脚本建的真实表 |
| 最早 | **CI Testcontainers IT** | 容器内按真实顺序跑 `create_biz_tables.sql` + `rls-phase-a.sql`,再闭世界 diff `pg_tables(biz)` vs `pg_policies` |
| 持续 | **actuator healthcheck**(`RlsPolicyHealthIndicator` 改闭世界)| 抓运行期被加表 / policy 被 DROP 的漂移 |
| 根因(可选)| **PG event trigger 自动入册**(`ddl_command_end` 在 `biz` 建表自动 ENABLE/FORCE + 套标准 policy)| 加表零 RLS 步骤,彻底消除「记得跑脚本」的顺序问题;代价是 superuser 魔法 + 模板一刀切,表真多了再上 |

> 闭世界判定写**一份**共享逻辑(扫 biz schema vs pg_policies),启动 fail-fast / CI IT / healthcheck 都复用它 —— single source of truth,别三处各写清单(那又退回白名单老问题)。

---

### Phase B · 租户自托管 Worker SDK(P1)

详见 **ADR-035**。本 plan 不重复 ADR 内容,只标注:

- **8-10 周**,分 6 个必做 Phase(P1-P6)
- 跟本 plan 其他 phase **并行可做**(无强依赖)
- Phase A 完成后,SDK 用户「不连自己 DB,用平台 staging」的形态也受 RLS 保护(SDK + 路径 1 组合,见 ADR-035 §6)

**额外补充给 Plan 整体**:SDK 是"escape hatch",做完 B 之后,后续 C/D 就不必默认做 — 不想跟人共用的租户直接走 SDK。

---

### Phase C · Per-tenant Schema(可选,按需)

**目标**:租户可以**自己加业务表**(`biz_ta.my_special_table`),平台不强制业务模型。

**设计**:

- 每开通一个走 schema 隔离的租户 → Flyway 创建 schema `biz_{tenantId}` + 给该租户 grant
- `BizTableSchemaRegistrar`(已留扩展点 — javadoc 写明「多租户 / 分库部署须扩展本类支持多 schema_name」)扫多个 schema 上报
- console 上传 Excel 校验时按 tenant 选 schema
- worker DataSource 通过 schema search_path session 变量动态切

**Phase 拆解**:
| 步骤 | 内容 | 估时 |
|---|---|---|
| C1 | Flyway 跨 schema migration 工具(`flyway -schemas=biz,biz_ta,biz_tb`)| 2 天 |
| C2 | `BizTableSchemaRegistrar` 扩多 schema 扫描 | 2 天 |
| C3 | console 「我的业务表」管理页(租户自己跑 DDL)| 1 周 |
| C4 | worker 写入按 tenant 路由 search_path | 3 天 |
| C5 | RLS policy 跨 schema 适配 | 2 天 |
| C6 | 端到端验证(ta 走 biz_ta,tb 走 biz_tb,互看不到) | 3 天 |

**总 3-4 周**。

**风险**:
- DDL 自助 = 租户可破坏自己 schema(可接受 — blast radius 在自己 schema 内)
- Flyway baseline 跨 schema 管理复杂(每租户独立 history 表)
- 监控指标基数(metrics by schema 维度)

**出口**:
- [ ] 开 1 个示范租户走 schema 隔离,跑通 IMPORT 全链路
- [ ] 文档:`docs/runbook/per-tenant-schema-onboarding.md`
- [ ] 跨租户隔离测试:RLS + schema 双保险

---

### Phase D · Per-tenant Worker Pool(几乎免费,P1)

**目标**:大租户独立 worker deployment,SLA / 故障 / 凭据物理隔离。

**关键洞察:核心代码已实现(2026-05-31 复核)**:

| 能力 | 现状 | 文件 |
|---|---|---|
| Worker 按 tenant 过滤任务 | ✅ `AbstractTaskConsumer.acceptTask()` rejecting cross-tenant | `batch-worker-core` |
| Kafka 订阅 mode(PATTERN / FIXED / TENANT_SCOPED)| ✅ 已实现 | `WorkerKafkaSubscribeProperties` |
| `tenantAllowlist` per-pool 订阅控制 | ✅ 已实现 | 同上 |
| Producer 端 SINGLE / TENANT / PRIORITY mode | ✅ 已实现 | orchestrator producer |
| 共用通配 `SHARED_TENANT_WILDCARD = "default-tenant"` | ✅ 默认 | `AbstractTaskConsumer` |

**默认配置 = 共用**(`tenant-id=default-tenant`),但**代码本身已支持 per-tenant 物理隔离**。本 Phase 只补部署侧:

**设计**:

- Helm chart 参数化 tenantId,模板 `worker-tenant.yaml`(渲染 `worker-{type}-{tenant}`)
- Kafka topic naming:`batch.task.dispatch.{type}.{tenantId}`(如 `batch.task.dispatch.import.bigcorp`)。
  这是 `BatchTopicResolver` TENANT 模式实际产出(`base + "." + tenantId`),也是 worker `TENANT_SCOPED`
  订阅正则 `^base(\.(tenantId))?$` 能命中的形态。**订正**:早前草稿写的
  `batch.task.dispatch.tenant.{tenantId}.{type}` 与代码不符,会导致投不进 / 订不到。详见
  `docs/runbook/per-tenant-worker-onboarding.md` §1。
- orchestrator producer mode 切 TENANT(`BATCH_MQ_ROUTING_MODE=TENANT`,**默认已是 TENANT**),
  根据 `(tenant_id, worker_type)` 派 topic
- per-tenant 池**必须用独立 consumer group**(`batch-worker-{type}-{tenant}`),否则与共享池同组
  会被 Kafka 跨池 rebalance,隔离失效(runbook §2 第 2 要素)
- per-tenant K8s ResourceQuota / ServiceAccount / Secret(凭据隔离)

**Phase 拆解(修订后)**:
| 步骤 | 内容 | 估时 |
|---|---|---|
| ~~D0~~ | ~~Worker tenant filter 业务代码~~ | ~~已实现,不做~~ |
| D1 | Helm chart per-tenant 参数化 | 1 周 |
| D2 | ~~orchestrator topic 路由~~ → 部署期 producer mode 切换文档 | 0.5 天(配置项,非代码)|
| D3 | Kafka topic 命名规约 + 自动创建脚本 | 1 天 |
| D4 | K8s ResourceQuota / NetworkPolicy MVP 模板 | 3 天 |
| D5 | per-tenant secret 注入(SFTP / MinIO 凭据,接 vault)| 2 天 |
| D6 | console「租户 worker 池状态」MVP 可视化(只读列表 + 健康度)| 3 天 |

**总 1.5-2 周**(全 DevOps / 部署 / 模板,**不动业务代码**)。

**快速验证路径**(2-3 天,纯手工):
- 选 1 个大租户,手工启动 worker 进程配 `tenant-id=t1`
- orch 端 producer mode 切 TENANT(配置改动)
- 验证 t1 任务只到该 worker / 其它租户任务不受影响

**风险**:
- 部署复杂度 ↑(每租户一套 deployment / topic / secret)
- 运维成本 ↑(N 倍租户 = N 倍升级 / 监控 alert),但 Helm template + GitOps 可自动化
- **建议**:大租户接入时默认走这条(代码 ready,代价低);**不**要等到 SDK 才考虑

**出口**(部署物料已交付;跑通 / 演练需 live 集群,留勾):
- [ ] 1 个示范大租户独立 worker pool 跑通(手工或自动化均可)— 步骤见 runbook §3,待集群执行
- [x] Helm chart `templates/worker-tenant.yaml` 模板(覆盖 import 等全 worker 类型)
- [x] Kafka topic 自动创建脚本 `scripts/data/init-tenant-topics.sh` + topic 命名规约文档(runbook §1)
- [x] 文档:`docs/runbook/per-tenant-worker-onboarding.md`
- [ ] 演练:示范租户 worker 挂 → 其他租户无感 — 脚本见 runbook §4,待集群执行

> 配套:per-tenant 凭据 / NetworkPolicy / ResourceQuota 模板 `templates/tenant-isolation.yaml`;
> values 开关 `tenantWorkerPools` / `tenantIsolation`(默认空,不影响现有渲染)。
> D6(console「租户 worker 池状态」只读页)是应用代码(FE+BE),不在本出口标准内,单列 feature 分支跟进。

---

### Phase E · Per-tenant DB(终态选项,大概率不做)

留作完整性,不写细节。**触发条件:监管合规明确要求物理隔离 + 该租户拒绝走 SDK 自托管**。

业界先例:Salesforce / Snowflake 大客户 dedicated cluster。我们这种系统不该走到这一步 — 走了就改用 SDK 把数据完全留在租户侧。

---

## 6. 替代方案否决(为什么不做这几条)

> 这一节是**显式记录已评估并否决的方案**,避免后续 review 反复重提同样的问题。

### 6.1 不做「per-tenant schema」(模型 B)

**方案**:1 个 business DB 内开 N 个 schema(`biz_ta` / `biz_tb` / ...),租户能自加业务表。

**否决理由**:

| 问题 | 说明 |
|---|---|
| **Flyway 复杂度爆炸** | 9 张表 × N 个 schema = N 倍 migration,每次发版都要跑 N 遍,失败回滚地狱 |
| **MyBatis XML 写不动** | `<select>` 里 `from biz.customer_account` 要变量化 schema 名,每个 mapper 都要改 |
| **连接池切 schema 损耗** | session 切 `search_path` 频繁,HikariCP connection reuse 受损 |
| **跨租户聚合 query 难写** | `SELECT ... FROM biz_ta.x UNION biz_tb.x UNION ...` 字符串拼接 N 段 |
| **运维 query 友好度差** | DBA 调试 query 要先想清"这是哪个租户的 schema" |
| **核心价值是伪需求** | 「租户自加业务表」诉求本质是「跑自家业务逻辑」,SDK 自托管比 schema 隔离更彻底地解 |

**替代**:走模型 A + RLS(默认)+ SDK 自托管(给真有自定义业务模型诉求的租户)。

### 6.2 不做「平台 worker × 租户自己 DB」混合模型(Fivetran 模式)

**方案**:平台代部署 worker,但连每个租户**自己的** business DB(需要租户 DBA 给平台 grant 一个连接账号)。

**否决理由**:

| 问题 | 严重性 |
|---|---|
| **每接一个租户 = 一次完整 DBA 谈判**(权限 / 网络 / 凭据 / SLA / 合规审计)| 接入周期周 → 月 |
| **凭据池管理复杂** | N 个租户 = N 套凭据 + N 个 vault entry + N 个轮转策略 + 凭据失效报警链路 |
| **网络拓扑爆炸** | VPN / 专线 / IP 白名单 / 反向 PrivateLink 全平台维护 |
| **schema 漂移** | 租户改表平台不知道,跑挂了才发现;每个租户的 schema 跟平台 ORM 兼容性独立维护 |
| **SQL 兼容性** | 租户用 PG / MySQL / Oracle 不同版本,平台 ORM 要全兼容,driver / 方言地狱 |
| **故障归因** | 慢了 / 错了到底是平台 worker 还是租户 DB → 扯皮,SLA 难定义 |
| **租户运维负担** | 租户 DBA 要为平台开权限 + 监控 + 防火墙 + 凭据轮转,租户接入成本不在我们手里 |
| **业界经验** | Fivetran 走这条 — 有专门的 Solutions Engineer 帮租户跑 30+ 步配置,本质是「重接入」产品形态 |

**替代**:

要"平台代运维 + 数据在租户"的混合好处 → 完全可以用 「**B(SDK)+ 平台提供标准 worker 镜像**」 实现:

- 平台发布 `batch-worker:1.0` 镜像(SDK 已编译进去,只配 `BatchTaskExecutor` 实现 + 端点 + DB 凭据)
- 租户在自家 K8s `helm install batch-worker --set platform=https://... --set api-key=xxx --set my-db=postgres://localhost`
- 凭据 / 网络 / 数据**全在租户内网**
- 平台还是「纯调度面 + 镜像供应者」,**不需要租户 DBA grant**

这样把「平台代运维 worker」变成「平台代发布 worker 镜像」,运维属责权在租户侧但操作成本低,**绕开混合模型的所有痛点**。

### 6.3 不做「per-tenant cluster」(模型 D)

**方案**:每个租户独立 PostgreSQL 实例(独立 RDS / 独立机器)。

**否决理由**:走到这步 = N 倍 RDS 月费 + N 倍备份 / 监控 / 巡检成本。这个成本**完全可以让租户自己承担** — 给租户 SDK,让租户在自家 cluster 跑 worker + 自家 DB,平台一分钱不花。

**例外**:合同里明文要求「物理独立 PG 实例 + 数据驻留指定区域」的大客户,这种走 D 也是临时态,长期还是要劝其走 SDK 自托管。

### 6.4 不做「平台 worker 只读 / 全读租户 DB」

**方案**:平台 worker 接租户 DB 做 audit / 报表 / 对账。

**否决理由**:

- 这违反 SDK 模式的核心承诺(「平台对租户数据 0 访问」)
- 替代:租户 worker 主动通过 `TaskResult.metadata` REPORT 任何需要的指标到 platform `job_instance.result_payload`,平台只是个 audit log,**租户控制汇报粒度**
- 业界对照:GitHub Actions / GitLab Runner / Temporal 都是 worker push 元数据,平台不 pull 业务数据

### 6.5 一图:模式空间 / 我们走哪、不走哪

```
                  数据在平台  ←─────────────────→  数据在租户
                  ┌─────────────────────────────────────┐
   平台 worker    │ A 共享 schema (默认)│ 混合(否决 6.2) │
                  ├─────────────────────────────────────┤
   租户 worker    │ (不存在,谁连?)    │ B SDK 自托管    │
                  └─────────────────────────────────────┘
                  
                  极端选项:
                  - B' = SDK + 平台发布 worker 镜像(混合需求的合理替代)
                  - C  = per-tenant schema(否决 6.1)
                  - D  = per-tenant cluster(否决 6.3,留 enterprise 合同例外)
```

**最终我们只支持 A 和 B(及 B' 变种),其它一律拒绝**。

---

## 7. 决策点(需 owner 拍板)

> 写 plan 是为了让 owner 拍板,不是默认全做。下列每个问题都需要明确答复:

| # | 问题 | 推荐 | 影响 |
|---|---|---|---|
| 1 | Phase A(RLS)收尾(R1/R2/R3)是否做? | ✅ P0 必做 | happy-path 已上线但 fail-open;不收尾 = RLS 形同未做,数据泄露风险仍在 |
| 2 | Phase B(SDK)是否做? | ✅ P1 推荐 | 不做 = 跨语言 / 自家依赖 / 数据驻留诉求没路径 |
| 3 | Phase C(per-tenant schema)是否默认开? | ❌ 否决(§6.1)| 价值伪需求,运维爆炸 |
| 4 | Phase D(per-tenant worker pool)是否做? | ✅ P1 几乎免费 | **核心代码已 ready**,只缺 Helm 模板,1.5-2 周搞定 |
| 5 | Phase A 后是否限制平台运维用 `BYPASSRLS`? | ✅ 限制 | 不限制 = RLS 等于没做 |
| 6 | SDK 接入文档放主 repo 还是独立 repo? | 独立 repo | 主 repo 文档膨胀,租户 onboarding 体验差 |
| 7 | 现有内建 worker(import/export/...)继续吗? | ✅ 继续 | 默认形态,租户体验最简单 |
| 8 | 业务库密码 / SFTP 凭据现在怎么管? | 加 vault | 现状是 env var / .env,合规弱 |

## 8. 优先级 / 资源建议

如果只能做 1 件:**Phase A 收尾(R1/R2/R3)** — happy-path 已上线(#155/#158),但当前 transition/fail-open + 账号未收口 + 白名单守护 = "DB 层有防御"承诺还没兑现。收尾约 1 周,收益 / 成本比最高。

如果做 2 件:**A + D(per-tenant worker pool)** — D 因为核心代码已 ready 几乎免费(1.5-2 周),立刻让大租户能要独立 pool。比 SDK 性价比更高(SDK 8-10 周)。

如果做 3 件:**A + D + B(SDK)** — B 解的是 D 不能解的「跨语言 / 自家依赖 / 数据驻留」诉求,长期看必做。

**6 个月**:完整跑完 A + D + B,1 个真实 SDK 接入租户 + 2 个大租户走 per-tenant pool。

> **修正记录 (2026-05-31)**:之前估 D 为 4-5 周 P2,实际复核代码后发现 worker tenant filter / Kafka 订阅 mode / producer routing 已实现,只缺部署模板,改为 1.5-2 周 P1。

## 9. 风险全景

| 风险 | 缓解 |
|---|---|
| **RLS 性能拖累**(每 query 多个 policy check)| 上线前压测,关键 query 加索引含 `tenant_id` 首字段 |
| **SDK 接入率低**(租户嫌麻烦,仍要求平台代部署)| 文档质量 + 模板 repo 易用性 + 案例样例 |
| **Per-tenant 方案做了但没人用** | 严格按合同触发,不默认开 |
| **`batch-common` 拆 SDK 影响内部模块** | ADR-035 已分析,薄壳不变内部 |
| **现有租户配置不兼容新隔离方案** | Phase A/B 设计上向后兼容(default-tenant + RLS bypass for legacy worker)|
| **运维 / on-call 知识断层** | 每 phase 配 runbook 是出口必备 |

## 10. 关联文档

- ADR-035 tenant self-hosted worker SDK(Phase B 主文档)
- ADR-029 dedicated SPI worker(本 plan 不变其定位)
- ADR-034 CAP 定位(本 plan 三件套不影响 CAP,主链不变)
- CLAUDE.md §架构硬约束(本 plan 全部 phase 都保留「orchestrator 唯一状态主机」「worker 必须 CLAIM」)
- `docs/architecture/scalability-assessment.md`(扩容能 / 不能解决的边界)
- `BizTableSchemaRegistrar.java` javadoc(已留 Phase C 扩展点注释)

## 11. 检查点

| 里程碑 | 标志 |
|---|---|
| M1 | Plan + ADR-035 评审通过,owner 拍板做哪几 phase |
| M2 | ~~Phase A 上线 — RLS 启用,健康检查绿~~ ✅ 已达成(#155/#158,transition 模式)→ **M2' = R1/R2/R3 收口,strict 模式生效** |
| M3 | Phase B SDK 样例 worker 端到端跑通,文档 published |
| M4 | 1 个真实 SDK 接入租户 production 跑通 |
| M5(可选)| 1 个 schema 隔离租户 + 1 个 per-tenant worker pool 试点 |
| M6 | 多租隔离 runbook + 架构图 + 运维演练完成 |
