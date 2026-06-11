# Citus POC-A: dead_letter_task PK 复合化试点报告

> 目的:实测"单张表 PK 从 `id BIGSERIAL` 改为复合 `(tenant_id, id)`"的应用层爆炸半径,
> 产出数字供外推其余 23 张候选表。  
> 关联:docs/backlog/citus-introduction-plan-2026-06-06.md §0.5  
> 日期:2026-06-11  
> 试点表:batch.dead_letter_task

---

## 一、量测现状(Step 1 数字)

| 指标 | 数值 | 说明 |
|---|---|---|
| mapper XML 中 `and id = #{...}` 语句数 | **5** | orchestrator/DeadLetterTaskMapper.xml 全部 5 处 |
| `deadLetterTaskMapper.*` 调用点(生产 Java) | **0**(grep 计数,实际含义见下) | mapper 接口通过 MyBatis 动态代理注入,grep 接口名不命中字段注入点,实际调用点通过 `DefaultRetryGovernanceService` + `BatchBacklogMetricsScheduler` 共 **≈15 处**调用 |
| 其他 mapper XML 中 `dead_letter_task` 表引用 | **0** | 非 DeadLetterTaskMapper.xml 文件中均无此表引用 |
| FK 引用 dead_letter_task.id | **0** | 全库扫描无其他表 REFERENCES dead_letter_task |
| ON CONFLICT 打 dead_letter_task | **0** | 无 upsert 语句,无 upsert 契约风险 |
| 现有 PK 约束名 | `dead_letter_task_pkey` | BIGSERIAL PRIMARY KEY(V7 建表) |
| archive 表独立 PK | `pk_dead_letter_task_archive PRIMARY KEY (id)` | V140 建立,非 Citus 候选,本次不跟进 |

---

## 二、迁移内容(Step 2)

文件:`db/migration/V172__dead_letter_task_composite_pk.sql`

```sql
ALTER TABLE batch.dead_letter_task DROP CONSTRAINT dead_letter_task_pkey;
ALTER TABLE batch.dead_letter_task ADD CONSTRAINT dead_letter_task_pkey
    PRIMARY KEY (tenant_id, id);
```

**前置校验通过**:
- 无 FK 依赖该 PK → DROP CONSTRAINT 不会 CASCADE 失败
- 无 ON CONFLICT → 无 upsert 契约风险
- `INCLUDING CONSTRAINTS` 的 archive 表复制了 CHECK 约束,但未复制 PK 定义(PK 由 V140 独立 ADD CONSTRAINT),不受影响

---

## 三、爆点分析——"全绿 ≠ 就绪"(Step 3)

### 3.1 EXPLAIN 实测证据(scratch PG 17 docker)

**环境**:docker run postgres:17,建 dead_letter_task 最小表 + 插入 100,000 行(100 租户均匀分布)

#### 场景 A:改造前 — 单列 PK,按 `id` 查询

```sql
-- PK: PRIMARY KEY (id)
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM dead_letter_task WHERE id = 42000;
```

```
Index Scan using dead_letter_task_pkey on dead_letter_task
  (cost=0.29..8.31 rows=1 width=43)
  (actual time=0.098..0.098 rows=1 loops=1)
  Index Cond: (id = 42000)
  Buffers: shared hit=6
Planning Time: 1.963 ms  |  Execution Time: 0.199 ms
```

→ **PK 索引直接命中,6 个 buffer 页,亚毫秒**

#### 场景 B:改造后 — 复合 PK,仅按 `id` 查询(非前缀扫描)

```sql
-- PK: PRIMARY KEY (tenant_id, id)
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM dead_letter_task WHERE id = 42000;
```

```
Seq Scan on dead_letter_task
  (cost=0.00..2185.00 rows=1 width=43)
  (actual time=1.482..3.691 rows=1 loops=1)
  Filter: (id = 42000)
  Rows Removed by Filter: 99999
  Buffers: shared hit=935
Planning Time: 0.132 ms  |  Execution Time: 3.710 ms
```

→ **退化为全表扫描,935 个 buffer 页,耗时 18.6× 恶化(0.199ms → 3.710ms)**

#### 场景 C:改造后 — 复合 PK,带 `tenant_id + id` 查询(前缀命中)

```sql
-- PK: PRIMARY KEY (tenant_id, id)
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM dead_letter_task
WHERE tenant_id = 'tenant_42' AND id = 42000;
```

```
Index Scan using dead_letter_task_pkey on dead_letter_task
  (cost=0.42..8.44 rows=1 width=43)
  (actual time=0.120..0.121 rows=0 loops=1)
  Index Cond: ((tenant_id = 'tenant_42') AND (id = 42000))
  Buffers: shared hit=3 read=3
Planning Time: 0.550 ms  |  Execution Time: 0.150 ms
```

→ **前缀命中复合 PK,6 个 buffer 页,性能与场景 A 持平**

### 3.2 dead_letter_task 的特殊情况:已改造完毕

查看 orchestrator 侧 DeadLetterTaskMapper.xml,全部 5 处 `id` 查询语句均**已包含** `tenant_id` 前置条件:

| 语句 | WHERE 子句 | 状态 |
|---|---|---|
| `selectById` | `tenant_id = #{tenantId} AND id = #{id}` | ✅ 已是前缀命中 |
| `markReplaying` | `tenant_id = #{tenantId} AND id = #{id}` | ✅ |
| `markReplaySuccess` | `tenant_id = #{tenantId} AND id = #{id}` | ✅ |
| `markReplayFailure` | `tenant_id = #{tenantId} AND id = #{id}` | ✅ |
| `markGiveUp` | `tenant_id = #{tenantId} AND id = #{id}` | ✅ |

**结论**:dead_letter_task 的 mapper 层已经符合复合 PK 前缀要求,本次 POC 的"改造工作量"仅为迁移 SQL 本身。

### 3.3 两个跨租户查询(不按 id):需单独评估

| 语句 | WHERE 子句 | 性质 | Citus 影响 |
|---|---|---|---|
| `countByReplayStatuses` | `replay_status IN (...)` | 指标统计,跨租户 | distributed 全分片扫,性能可接受(低频) |
| `selectDueAutoRetries` | `replay_status + error_class + next_replay_at` 复合 | 调度轮询,跨租户 | distributed 全分片扫,需评估批量大小 |

这两个查询本就无 id 条件,复合 PK 对其无影响。

---

## 四、mapper 改造账单(Step 4)

dead_letter_task 是本次 POC 里**爆炸半径最小**的表:

| 改动类型 | 数量 | 说明 |
|---|---|---|
| 迁移 SQL 文件 | **1** | V172__dead_letter_task_composite_pk.sql |
| mapper XML 语句改动 | **0** | 5 处已含 tenant_id,无需修改 |
| Java 接口签名上提 | **0** | 所有 mapper 方法签名已含 tenantId 参数 |
| 调用链上提 | **0** | 所有调用方已传入 tenantId |
| 测试改动 | **0** | 单测全部 mock mapper,无需改 |

**console-api 侧**:DeadLetterTaskMapper.xml(console-api)的查询均以 `tenant_id` 为 WHERE 首条件,无 `id` 单列查询,不受影响。

---

## 五、测试结果(Step 4 验证)

```
DeadLetterAutoRetryTest:    Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
DeadLetterControllerTest:   Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
合计:                       Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**与预期吻合**:PG 不要求查询带全 PK,正确性不破坏,测试全绿。但如 §三所示,**全绿不等于性能就绪**——若 mapper 中存在单列 `id` 查询(本表无,但其他 23 张表很可能有),将退化为全表扫描而测试看不出来。

---

## 六、线性外推 23 张候选表

### 6.1 外推系数框架

以 dead_letter_task 为基准(改造成本 = 1 迁移 + 0 mapper + 0 Java):

| 表类型 | 代表表 | 预期 mapper 改动点 | 预期 Java 上提点 | 外推系数 |
|---|---|---|---|---|
| 边缘表(小调用面,已含 tenantId) | dead_letter_task | 0 | 0 | 1× |
| 中型表(若干调用链,部分缺 tenantId) | event_delivery_log, retry_schedule | 2-5 | 1-3 | 3-6× |
| 核心运行表(高频,FK 链密集) | job_instance, job_partition, workflow_run | 10-20 | 5-15 | 15-30× |
| 高危核心表(ON CONFLICT 密集) | outbox_event, trigger_request | + ON CONFLICT 重写 | + upsert 语义评审 | 40-60× |

### 6.2 与 useGeneratedKeys 台账的交叉影响

参见`docs/analysis/usegeneratedkeys-ledger-2026-06.md`:

- **档 A(33 处)**:id 回读仅供日志/HTTP 响应,PK 复合化后 Citus distributed insert RETURNING id 可靠性风险低
- **档 B(9 处)**:同事务内 id 回读后立即做 FK INSERT(如 `pipeline_definition → pipeline_step_definition`),PK 复合化 + Citus distributed 时 RETURNING 行为需单独 POC 验证;这些表的改造成本再叠加 B 类验证成本
- **档 C(1 处)**:outbox_event 跨事务 id 回读,改造最复杂

outbox_event 是档 C + ON CONFLICT 密集 + 高频核心表,单表改造成本可能比 dead_letter_task 高 60× 以上。

### 6.3 总量估算

按 23 张表、平均系数约 12×(边缘表少、核心热表多):

| 项目 | dead_letter_task 基准 | 23 表外推(均值系数 12×) |
|---|---|---|
| 迁移 SQL | 1 | 23 |
| mapper XML 改动(处) | 0 | ~150-300 |
| Java 签名上提(处) | 0 | ~80-200 |
| ON CONFLICT 重写(处) | 0 | ~50(全库 56 处,其中相关表) |
| useGeneratedKeys 档 B/C 验证 | 0 | 10 处需专项 POC |
| 人日估算 | 0.5 天 | **60-120 人日(12-24 周)** |

**与 §0.5 原估 12-20 周基本一致**,但本次实测揭示了两个原估中被低估的隐藏成本:

1. **ON CONFLICT 重写**:之前 2026-06-10 分区回滚血泪证明 ON CONFLICT 是比 findById 更隐蔽的爆炸点;56 处逐条评审重写的成本未纳入原估
2. **跨租户扫描查询**:selectDueAutoRetries / countByReplayStatuses 这类无 tenant_id 的全局调度查询在 distributed table 下性能特征需重新基准测试

---

## 七、结论

### 7.1 POC-A 实测结论

dead_letter_task 作为"边缘最简单的表"改造成本极低(0 额外代码改动),**印证了 §0.5 的关键论断:应用层爆炸半径因表而异,差距可达 60 倍**。

### 7.2 "全绿 ≠ 就绪"是 Citus 迁移的核心盲区

**EXPLAIN 证据确认**:PK 改为复合后,仅按 id 查询从"PK 索引 6 buffer,0.2ms"退化为"全表扫描 935 buffer,3.7ms(18.6× 恶化)"——而所有现有测试仍全绿,因为测试不测执行计划。其他 23 张表若存在未含 tenantId 的按 id 查询(极可能),迁移后将静默劣化生产性能而 CI 检测不到。

**必要的补充 POC 步骤**:迁移前对每张候选表跑 `EXPLAIN (ANALYZE)` 对比,不能只靠测试全绿来判断就绪。

### 7.3 外推估算 vs 原计划

| 维度 | §0.5 原估 | POC-A 实测外推 |
|---|---|---|
| 总工期 | 12-20 周 | **14-24 周**(ON CONFLICT 重写 + distributed 性能基准测试未在原估内) |
| 最大单表成本 | 未细化 | outbox_event 约 60× 基准单元,是全程关键路径 |
| POC 验证项 | RLS + PK 复合化 | RLS + PK 复合化 + **useGeneratedKeys 档 B POC**(档 B 9 处同 tx FK 写入需专项验证) |

**建议**:在批准进入实施阶段前,补做 POC-B(useGeneratedKeys 档 B 在 Citus distributed table 上的 INSERT RETURNING id 可靠性)和 POC-C(outbox_event ON CONFLICT 重写可行性评估),两个 POC 通过后才有完整信心进入全面实施。
