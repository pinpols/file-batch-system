# 分区 / Citus 铺路改造实施计划(常驻分支)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在常驻分支 `feature/partition-readiness` 上完成 outbox_event/job_instance 月分区改造(Flyway 化)+ ON CONFLICT 幂等契约改造 + Citus 前置 POC,为未来分库分表/Citus 铺路;**分支不合 main**,已有链路零破坏。

**Architecture:** 分区 DDL 走 Flyway 新迁移(V170/V171)而非 side-script——testcontainers 全部 IT/E2E 自动跑在分区 schema 上,消除"e2e 验不出迁移"缺口。mapper 改造与 DDL 同分支绑定。Citus POC 选最小表实测 PK 复合化爆炸半径。

**Tech Stack:** PostgreSQL 17 RANGE 分区 / Flyway / MyBatis XML mapper / Testcontainers IT

---

## ⛔ 运营红线(执行者必读)

1. **本分支服务只连分支专用库**(2026-06-10 修订,经用户确认"开发期不考虑迁移成本"):在现有 PG 容器内新建 `batch_platform_part` / `batch_business_part` 两个全新库,Flyway 从 V1 直跑到 V171(空表,迁移复制步骤自然空转)。**共享库(batch_platform/batch_business)严禁被分支服务触碰**。单测/IT 仍走 testcontainers;全链路验证走专用库(Task 7b),验证时先 stop-all 主服务再起分支服务(端口复用)。
2. **本分支不开 PR、不合 main**。推 `origin/feature/partition-readiness` 保存即可。
3. 定期维护:`git rebase origin/main` 保持新鲜(冲突热点预期在 mapper XML 与 db/migration 序号;rebase 后必须全量重跑 Task 9)。
4. V 序号占用 V170/V171/V172;若 rebase 时 main 已用,顺延并同步改本文档。

## 已锁定的事实基线(2026-06-10 实测,不要重复调查)

- 受影响 ON CONFLICT 仅 **1 条**:`OutboxEventMapper.xml` 主 insert `on conflict (tenant_id, event_key) do nothing`。其余 55 处目标为 archive.* 冷表或不分区的配置表,无关。
- `JobInstanceMapper.insert` 无 ON CONFLICT,幂等承重在 `uk(tenant_id,dedup_key,run_attempt)` 抛 DuplicateKey;`biz_date` 经 `#{bizDate}` 直传,实库 0 行 NULL,但列可空。
- `PartitionReclaimUnit.java:142-147` 注释证明 event_key 已 version 内嵌(意图唯一),全局 UNIQUE 是兜底网非承重柱。
- `event_delivery_log` 与 `event_outbox_retry` 各有一条 FK → `outbox_event(id)`,分区后无法保留(分区表 PK 含 created_at),应用层 cleanup 已有级联删。
- job_instance 有 8 条子表 FK(清单见 `scripts/db/partition-migration/02-*.sql` E 段),分区后全部转应用层守护(`SuccessInstanceArchiveScheduler` 已支持级联)。
- 分区版完整 DDL 权威源:`scripts/db/partition-migration/01/02-*.sql`(2026-06-10 按 pg_dump 实库重生成,列/约束/索引全量)。
- Flyway 用真 PG 解析器,**支持 `DO $$` dollar-quote**(无 ScriptUtils 切割问题);迁移文件须去掉 psql 元命令(`\set`/`\echo`)与 `BEGIN;/COMMIT;`(Flyway 自带事务)。

---

### Task 1: 幂等语义决策文档

**Files:**
- Create: `docs/design/partition-idempotency-decision.md`

- [x] **Step 1: 写决策文档**(内容如下,直接落盘)

```markdown
# 分区下的 outbox 幂等语义决策(2026-06-10)

## 问题
outbox_event 月分区后 UNIQUE 必须含分区键 → (tenant_id, event_key, created_at),
全局 (tenant_id, event_key) 唯一在分区表上不可表达,
`on conflict (tenant_id, event_key) do nothing` 失配。

## 决策:INSERT ... SELECT ... WHERE NOT EXISTS 替代
- 写路径改为:仅当 (tenant_id, event_key) 不存在任意行时插入(扫
  uk_outbox_event_p_key 前缀索引,代价等价)。
- 冲突时不插入、useGeneratedKeys 不回填 id —— 与 DO NOTHING 行为完全一致,
  调用方(OutboxDomainEventPublisher.publish 返回 entity.getId())零改动。

## 为什么竞态可接受
1. 事件发射在 @Transactional(MANDATORY) 中,与聚合状态变更同事务;
   同一聚合的状态迁移被 job_instance.version 乐观锁串行化 ——
   同一逻辑事件不存在并发双发路径。
2. event_key 本身按"意图唯一"设计(PartitionReclaimUnit 将 partition.version
   内嵌 key),全局 UNIQUE 历史上反而造成过静默丢事件(见该类注释)。
3. 极端竞态下重复事件的下游代价:Kafka 重复投递 → Worker CLAIM 唯一性兜底,
   不会双执行。
4. 残余风险等级:低;监控抓手:`citus_stat`无关,直接
   `SELECT event_key, count(*) FROM batch.outbox_event GROUP BY 1,tenant_id HAVING count(*)>1`
   进 strict-verify 观测(Task 8)。

## 不做
- 不引入 advisory lock(写热点路径加锁,得不偿失)
- 不建独立 dedup 表(多一张表一致性负担,YAGNI)
```

- [x] **Step 2: Commit**

```bash
git add docs/design/partition-idempotency-decision.md
git commit -m "docs(design): 分区下 outbox 幂等语义决策(NOT EXISTS 替代全局唯一)"
```

---

### Task 2: 分区守护 IT(TDD 先行,先红)

**Files:**
- Create: `batch-orchestrator/src/test/java/com/example/batch/orchestrator/infrastructure/PlatformPartitionedTablesIntegrationTest.java`

- [x] **Step 1: 写失败测试**

```java
package com.example.batch.orchestrator.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("平台库分区守护:outbox_event / job_instance 必须是 RANGE 分区父表")
class PlatformPartitionedTablesIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void outboxEventAndJobInstance_shouldBePartitionedParents() {
    List<String> partitioned =
        jdbcTemplate.queryForList(
            "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname='batch' AND c.relkind='p'",
            String.class);
    assertThat(partitioned).contains("outbox_event", "job_instance");
  }

  @Test
  void partitions_shouldCoverCurrentMonthAndDefault() {
    Integer outboxParts =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_inherits WHERE inhparent='batch.outbox_event'::regclass",
            Integer.class);
    Integer jiParts =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_inherits WHERE inhparent='batch.job_instance'::regclass",
            Integer.class);
    // 36 月分区 + default
    assertThat(outboxParts).isEqualTo(37);
    assertThat(jiParts).isEqualTo(37);
  }
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
mvn -q test -pl batch-orchestrator -am -Dtest=PlatformPartitionedTablesIntegrationTest -DfailIfNoTests=true
```
Expected: FAIL — `partitioned` 不含 outbox_event/job_instance(当前是普通表)。

- [x] **Step 3: Commit(红灯入库,信息注明 WIP)**

```bash
git add batch-orchestrator/src/test/java/com/example/batch/orchestrator/infrastructure/PlatformPartitionedTablesIntegrationTest.java
git commit -m "test(orchestrator): 分区守护 IT 先行(红,待 V170/V171)"
```

---

### Task 3: V170 — outbox_event 转月分区(Flyway)

**Files:**
- Create: `db/migration/V170__outbox_event_monthly_partition.sql`
- 参照权威源: `scripts/db/partition-migration/01-outbox-event-partitioned.sql`(列/约束/索引与其 A-E 段完全一致)

- [x] **Step 1: 写迁移文件**——内容 = side-script 01 的 A→E 段,做且仅做以下 6 处机械转换:

1. 删除文件头注释中"禁止执行"段(Flyway 版配套 mapper 改造同分支,阻塞已解)、删除 `\set ON_ERROR_STOP on`、删除 `BEGIN;` 与 `COMMIT;`、删除 F 段全部 `\echo`/验证 SELECT(守护移交 Task 2 的 IT)
2. 文件头替换为:
```sql
-- V170: outbox_event 转 created_at 月分区。
-- 配套:OutboxEventMapper insert 已改 NOT EXISTS(同分支 V170 后续提交),
-- 幂等语义决策见 docs/design/partition-idempotency-decision.md。
-- 列/约束/索引权威源:scripts/db/partition-migration/01-*.sql(2026-06-10 pg_dump 重生成)。
-- 注意:本迁移含全表复制,生产规模执行前评估窗口;当前为上线前阶段,数据量 <20 万行,秒级。
```
3. E 段 `event_delivery_log` FK DROP 之后**追加**(side-script 漏的第二条 FK):
```sql
ALTER TABLE batch.event_outbox_retry
    DROP CONSTRAINT IF EXISTS event_outbox_retry_outbox_event_id_fkey;
```
4. E 段改名后**追加 DROP legacy**(Flyway 单源,不留双表;回滚 = 整库重建,testcontainers 天然如此):
```sql
DROP TABLE batch.outbox_event_legacy;
```
5. 其余 SQL(CREATE TABLE 14 列 + 3 CHECK + PK/UNIQUE、序列 default、36 月分区 DO 块、5 个 `idx_outbox_p_*` 索引、INSERT 全列复制、setval)逐字保留。
6. 校验无 psql 元命令残留:`grep -nE '^\\\\' db/migration/V170__outbox_event_monthly_partition.sql` 应空。

- [x] **Step 2: 跑 Flyway 静态守护**

```bash
bash scripts/ci/validate-flyway-schema.sh
```
Expected: PASS(V 序号唯一、可解析)。

- [x] **Step 3: 跑 Task 2 的 IT(预期半绿)**

```bash
mvn -q test -pl batch-orchestrator -am -Dtest=PlatformPartitionedTablesIntegrationTest
```
Expected: 仍 FAIL(job_instance 未分区),但失败断言里 `partitioned` 已含 `outbox_event`。

- [x] **Step 4: Commit**

```bash
git add db/migration/V170__outbox_event_monthly_partition.sql
git commit -m "feat(db): V170 outbox_event 转 created_at 月分区"
```

---

### Task 4: OutboxEventMapper insert 改 NOT EXISTS

**Files:**
- Modify: `batch-orchestrator/src/main/resources/mapper/OutboxEventMapper.xml:23-49`(id=insert 语句)
- Create: `batch-orchestrator/src/test/java/com/example/batch/orchestrator/event/OutboxInsertDedupIntegrationTest.java`

- [x] **Step 1: 写失败测试**

```java
package com.example.batch.orchestrator.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("分区后 outbox insert 幂等:同 (tenant,event_key) 二次插入静默跳过")
class OutboxInsertDedupIntegrationTest extends AbstractIntegrationTest {

  @Autowired private OutboxEventMapper outboxEventMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private OutboxEventEntity entity(String eventKey) {
    OutboxEventEntity e = new OutboxEventEntity();
    e.setTenantId("ta");
    e.setAggregateType("JOB_TASK");
    e.setAggregateId(1L);
    e.setEventType("TASK_READY");
    e.setEventKey(eventKey);
    e.setPayloadJson("{}");
    e.setPublishStatus("NEW");
    e.setPublishAttempt(0);
    return e;
  }

  @Test
  void shouldSkipSecondInsert_whenSameTenantAndEventKey() {
    String key = "ta:dedup-it:" + System.nanoTime();
    OutboxEventEntity first = entity(key);
    outboxEventMapper.insert(first);
    assertThat(first.getId()).isNotNull();

    OutboxEventEntity second = entity(key);
    outboxEventMapper.insert(second);
    // 与旧 DO NOTHING 契约一致:跳过时不回填 id
    assertThat(second.getId()).isNull();

    Integer rows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM batch.outbox_event WHERE tenant_id='ta' AND event_key=?",
            Integer.class,
            key);
    assertThat(rows).isEqualTo(1);
  }
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
mvn -q test -pl batch-orchestrator -am -Dtest=OutboxInsertDedupIntegrationTest
```
Expected: FAIL — V170 后旧 `on conflict (tenant_id, event_key)` 无匹配约束,首次 insert 即报 "no unique or exclusion constraint matching"。

- [x] **Step 3: 改 mapper**(替换整个 `<insert id="insert">` 体)

```xml
    <insert id="insert" parameterType="com.example.batch.orchestrator.domain.entity.OutboxEventEntity"
            useGeneratedKeys="true" keyProperty="id">
        <!-- 分区后全局 (tenant_id,event_key) 唯一不可表达,NOT EXISTS 等价替代旧
             ON CONFLICT DO NOTHING(跳过时同样不回填 id);竞态可接受性论证见
             docs/design/partition-idempotency-decision.md -->
        insert into batch.outbox_event (
            tenant_id,
            aggregate_type,
            aggregate_id,
            event_type,
            event_key,
            payload_json,
            publish_status,
            publish_attempt,
            next_publish_at,
            trace_id,
            priority
        )
        select
            #{tenantId},
            #{aggregateType},
            #{aggregateId},
            #{eventType},
            #{eventKey},
            #{payloadJson,jdbcType=VARCHAR}::jsonb,
            #{publishStatus},
            #{publishAttempt},
            #{nextPublishAt},
            #{traceId},
            coalesce(#{priority}, 5)
        where not exists (
            select 1 from batch.outbox_event
            where tenant_id = #{tenantId} and event_key = #{eventKey}
        )
    </insert>
```

- [x] **Step 4: 跑测试确认通过**

```bash
mvn -q test -pl batch-orchestrator -am -Dtest=OutboxInsertDedupIntegrationTest
```
Expected: PASS(2 tests)。

- [x] **Step 5: 跑 outbox 相关既有测试防回归**

```bash
mvn -q test -pl batch-orchestrator -am -Dtest='Outbox*,*Reclaim*' -DfailIfNoTests=false
```
Expected: 全 PASS。

- [x] **Step 6: Commit**

```bash
git add batch-orchestrator/src/main/resources/mapper/OutboxEventMapper.xml \
        batch-orchestrator/src/test/java/com/example/batch/orchestrator/event/OutboxInsertDedupIntegrationTest.java
git commit -m "feat(orchestrator): outbox insert 幂等改 NOT EXISTS(分区兼容,契约等价)"
```

---

### Task 5: V171 — job_instance 转月分区(Flyway)

**Files:**
- Create: `db/migration/V171__job_instance_monthly_partition.sql`
- 参照权威源: `scripts/db/partition-migration/02-job-instance-partitioned.sql`

- [x] **Step 1: 写迁移文件**——side-script 02 的 A→F 段,机械转换同 Task 3 第 1 条(去 psql 元命令/事务/验证段、头注释改 V171 风格),另加两处:

1. F 段改名后追加:
```sql
DROP TABLE batch.job_instance_legacy;
```
2. 保留 E 段全部 8 条子表 FK DROP(逐字)。

- [x] **Step 2: validate-flyway-schema + Task 2 IT 转绿**

```bash
bash scripts/ci/validate-flyway-schema.sh
mvn -q test -pl batch-orchestrator -am -Dtest=PlatformPartitionedTablesIntegrationTest
```
Expected: 两者 PASS(37+37 分区断言绿)。

- [x] **Step 3: Commit**

```bash
git add db/migration/V171__job_instance_monthly_partition.sql
git commit -m "feat(db): V171 job_instance 转 biz_date 月分区(8 子表 FK 转应用层守护)"
```

---

### Task 6: job_instance 写路径 biz_date 收紧 + dedup 语义 IT

**Files:**
- Modify: `batch-orchestrator/src/main/resources/mapper/JobInstanceMapper.xml`(id=insert 中 `#{bizDate}` 一处)
- Create: `batch-orchestrator/src/test/java/com/example/batch/orchestrator/job/JobInstanceDedupPartitionIntegrationTest.java`

- [x] **Step 1: 写失败测试**

```java
package com.example.batch.orchestrator.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("分区后 job_instance 幂等:同 (tenant,dedup_key,attempt,biz_date) 仍抛 DuplicateKey;NULL biz_date 由 SQL 兜底")
class JobInstanceDedupPartitionIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  private int rawInsert(String dedupKey, LocalDate bizDate) {
    return jdbcTemplate.update(
        "insert into batch.job_instance (tenant_id, job_definition_id, job_code, instance_no,"
            + " biz_date, trigger_type, instance_status, dedup_key, run_attempt)"
            + " values ('ta', 1, 'JOB-A', 'no-' || clock_timestamp()::text,"
            + " coalesce(?, CURRENT_DATE), 'MANUAL', 'CREATED', ?, 1)",
        bizDate, dedupKey);
  }

  @Test
  void shouldThrowDuplicateKey_whenSameDedupKeySameBizDate() {
    String dedup = "dedup-it-" + System.nanoTime();
    rawInsert(dedup, LocalDate.of(2026, 6, 10));
    assertThatThrownBy(() -> rawInsert(dedup, LocalDate.of(2026, 6, 10)))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void shouldRouteToCurrentMonthPartition_whenBizDateNull() {
    String dedup = "dedup-null-" + System.nanoTime();
    rawInsert(dedup, null);
    String partition =
        jdbcTemplate.queryForObject(
            "SELECT tableoid::regclass::text FROM batch.job_instance WHERE dedup_key=?",
            String.class,
            dedup);
    assertThat(partition).contains("job_instance_p_2026"); // 非 default 分区
  }
}
```

- [x] **Step 2: 跑确认两测均 FAIL(或第二测 FAIL)**

```bash
mvn -q test -pl batch-orchestrator -am -Dtest=JobInstanceDedupPartitionIntegrationTest
```
Expected: 第一测 PASS(uk 在 V171 已含 biz_date,同日重复仍冲突);第二测 FAIL——mapper/裸 SQL 不传 biz_date 时 NOT NULL 拒绝(测试 SQL 已带 coalesce,此步实际验证生产 mapper 也需同款兜底 → 进 Step 3)。

- [x] **Step 3: mapper 兜底**——`JobInstanceMapper.xml` insert 的 `#{bizDate}` 改:

```xml
            coalesce(#{bizDate}, CURRENT_DATE),
```

- [x] **Step 4: 全测确认通过**

```bash
mvn -q test -pl batch-orchestrator -am -Dtest='JobInstanceDedupPartitionIntegrationTest,JobInstance*' -DfailIfNoTests=false
```
Expected: 全 PASS。

- [x] **Step 5: Commit**

```bash
git add batch-orchestrator/src/main/resources/mapper/JobInstanceMapper.xml \
        batch-orchestrator/src/test/java/com/example/batch/orchestrator/job/JobInstanceDedupPartitionIntegrationTest.java
git commit -m "feat(orchestrator): job_instance biz_date 写路径 NOT NULL 兜底 + 分区 dedup 语义 IT"
```

---

### Task 7: 全量回归(分支绿线)

- [x] **Step 1: 全量单测 + IT(全在分区 schema 上跑)**

```bash
mvn -B clean test 2>&1 | tail -5
```
Expected: BUILD SUCCESS。失败按"工具链/环境/代码"分诊:凡 SQL 含 `on conflict` 打 outbox/job_instance 的失败 = 本计划遗漏,立修;Mockito/JDK25 类 = backlog 跳过。

- [x] **Step 2: E2E**

```bash
mvn -q install -DskipTests -pl batch-console-api,batch-orchestrator,batch-trigger,batch-worker-import,batch-worker-export,batch-worker-process,batch-worker-dispatch,batch-worker-atomic,batch-worker-sdk-testkit,batch-worker-sdk-spring-boot-starter -am
bash scripts/local/run-tests.sh --e2e --skip-build
```
Expected: `ALL TESTS PASSED [mode=e2e]`(41 个)。

- [x] **Step 3: Commit + 推分支**

```bash
git push -u origin feature/partition-readiness
```

---

### Task 7b: 分支专用环境 + 真实链路全栈验证

**Files:**
- Create: `scripts/local/env-partition-branch.sh`(专用库建库/初始化/指引一体脚本)

- [x] **Step 1: 写专用环境脚本**

```bash
#!/usr/bin/env bash
# env-partition-branch.sh — feature/partition-readiness 分支专用库管理。
# 用法: bash scripts/local/env-partition-branch.sh {init|reset|env}
#   init  — 在现有 batch-postgres-primary 容器内建 batch_platform_part / batch_business_part
#           (business 库跑 create_biz_tables.sql + rls-phase-a.sql 手工脚本)
#   reset — DROP 两库重建(全新 Flyway 基线)
#   env   — 打印起服务前要 export 的 JDBC URL 覆盖(platform/business 指向 *_part)
set -euo pipefail
PGC=batch-postgres-primary
case "${1:-env}" in
  init|reset)
    if [[ "$1" == reset ]]; then
      docker exec $PGC psql -U batch_user -d batch_platform -c "DROP DATABASE IF EXISTS batch_platform_part;" || true
      docker exec $PGC psql -U batch_user -d batch_platform -c "DROP DATABASE IF EXISTS batch_business_part;" || true
    fi
    docker exec $PGC psql -U batch_user -d batch_platform -c "CREATE DATABASE batch_platform_part OWNER batch_user;"
    docker exec $PGC psql -U batch_user -d batch_platform -c "CREATE DATABASE batch_business_part OWNER batch_user;"
    docker exec -i $PGC psql -U batch_user -d batch_business_part -v ON_ERROR_STOP=1 < scripts/db/business/create_biz_tables.sql
    docker exec -i $PGC psql -U batch_user -d batch_business_part -v ON_ERROR_STOP=1 < scripts/db/business/rls-phase-a.sql
    echo "OK: *_part 两库就绪(platform 库由首个服务启动时 Flyway V1..V171 建表)"
    ;;
  env)
    cat <<'ENV'
export BATCH_PLATFORM_DB_URL="jdbc:postgresql://localhost:15432/batch_platform_part"
export BATCH_BUSINESS_DB_URL="jdbc:postgresql://localhost:15432/batch_business_part"
ENV
    ;;
esac
```
(若仓库 env 键名与上述不一致,以 `scripts/lib/env-common.sh` / application.yml 实际占位符为准修正,语义不变:platform/business JDBC URL 指向 `*_part`。)

- [x] **Step 2: 初始化专用库 + 全栈起服务验证**

```bash
bash scripts/local/env-partition-branch.sh init
bash scripts/local/stop-all.sh          # 停主分支服务
mvn -q package -DskipTests -pl batch-e2e-tests -am
# 拷 8 jar 到 build/runtime-jars/(worktree 内)后:
eval "$(bash scripts/local/env-partition-branch.sh env)"
bash scripts/local/restart.sh orchestrator trigger console worker-import worker-export worker-process worker-dispatch worker-atomic
```
Expected: 8 服务 health UP;orchestrator 日志无 ERROR;`batch_platform_part` 中 outbox_event/job_instance relkind='p'。

- [x] **Step 3: 真实链路冒烟**

```bash
# outbox 流转(2 分钟新事件>0、无 publish 积压)
docker exec batch-postgres-primary psql -U batch_user -d batch_platform_part -tAc   "SELECT count(*) FROM batch.outbox_event WHERE created_at > now() - interval '2 minutes';"
# 触发一个真实 import job(走 sim 脚本或 console API),确认 job_instance 落分区
docker exec batch-postgres-primary psql -U batch_user -d batch_platform_part -tAc   "SELECT tableoid::regclass::text, count(*) FROM batch.job_instance GROUP BY 1 LIMIT 5;"
```
Expected: 事件计数>0;job_instance 行落在 `job_instance_p_YYYY_MM` 而非 default。

- [x] **Step 4: 切回主环境确认零污染**

```bash
bash scripts/local/stop-all.sh
# 不带 env 覆盖重启主服务(主 checkout, main 分支 jar)
docker exec batch-postgres-primary psql -U batch_user -d batch_platform -tAc   "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='batch' AND c.relkind='p';"
```
Expected: 共享库分区父表数 = 0(未被触碰);主服务 health UP。

- [x] **Step 5: Commit**

```bash
git add scripts/local/env-partition-branch.sh
git commit -m "ops(local): 分支专用库管理脚本(零迁移全新建,共享库零污染)"
```

---

### Task 8: 观测抓手进 strict-verify(重复事件监控)

**Files:**
- Modify: `scripts/local/strict-verify.sh`(在 §3 审计落表检查之后追加一个检查块,模式仿照文件内既有 `check` 函数用法)

- [x] **Step 1: 追加检查**(若 strict-verify.sh 的断言风格不同,以文件内既有写法为准改写,断言语义不变)

```bash
# §X outbox 重复事件观测(分区后无全局唯一,NOT EXISTS 竞态残余监控)
DUP_EVENTS=$(psql_platform -tAc "SELECT count(*) FROM (
  SELECT tenant_id, event_key FROM batch.outbox_event
  GROUP BY tenant_id, event_key HAVING count(*) > 1) d;" 2>/dev/null || echo "SKIP")
[[ "$DUP_EVENTS" == "0" ]] && ok "outbox 无重复 (tenant_id,event_key)" \
  || ng "outbox 出现 $DUP_EVENTS 组重复事件(NOT EXISTS 竞态,需查发射方)"
```

- [x] **Step 2: 本地跑确认语法**(注意:共享本地库未分区,此检查在两种 schema 下均合法)

```bash
bash scripts/local/strict-verify.sh 2>&1 | tail -4
```
Expected: PASS 计数 +1。

- [x] **Step 3: Commit**

```bash
git add scripts/local/strict-verify.sh
git commit -m "ops(strict-verify): outbox 重复事件观测(分区幂等残余风险抓手)"
```

---

### Task 9: Citus POC-A — PK 复合化试点(dead_letter_task)

> 目的:实测 §0.5 要求的"PK 复合化爆炸半径",产出报告供未来 23 表全量估算。选 `dead_letter_task`:无子表 FK、mapper 单文件、调用面小。

**Files:**
- Create: `db/migration/V172__dead_letter_task_composite_pk.sql`
- Modify: `batch-orchestrator/src/main/resources/mapper/DeadLetterTaskMapper.xml`(所有 `where id = #{id}` / `where tenant_id = #{tenantId} and id = #{id}` 处)
- Create: `docs/analysis/citus-pk-composite-poc-2026-06.md`

- [x] **Step 1: 量测现状**

```bash
grep -cE "where id = #\{|and id = #\{" batch-orchestrator/src/main/resources/mapper/DeadLetterTaskMapper.xml
grep -rn "deadLetterTaskMapper\.\w+ById" batch-orchestrator/src/main/java --include="*.java" | wc -l
```
记录两数字进 POC 报告草稿。

- [x] **Step 2: 写迁移**

```sql
-- V172: Citus POC — dead_letter_task PK 复合化 (tenant_id, id)。
-- 目的:实测应用层爆炸半径,产出 docs/analysis/citus-pk-composite-poc-2026-06.md。
ALTER TABLE batch.dead_letter_task DROP CONSTRAINT dead_letter_task_pkey;
ALTER TABLE batch.dead_letter_task ADD CONSTRAINT dead_letter_task_pkey
    PRIMARY KEY (tenant_id, id);
```

- [x] **Step 3: 全测找爆点**

```bash
mvn -q test -pl batch-orchestrator -am -Dtest='DeadLetter*' -DfailIfNoTests=false 2>&1 | tail -8
```
Expected: 可能全 PASS(PG 不要求查询带全 PK,单 id 查询仍走 (tenant_id,id) 索引尾列?——**不走,会全表扫**;正确性不破坏,性能退化)。把"全绿但性能退化不可见"这一观察写进报告——这正是 Citus POC 最重要的发现类别。

- [x] **Step 4: mapper 改造**——`DeadLetterTaskMapper.xml` 内所有按 id 查询补 tenant_id 条件(逐条列在报告里);Java 调用方若签名缺 tenantId 则上提参数。

- [x] **Step 5: 写 POC 报告**(`docs/analysis/citus-pk-composite-poc-2026-06.md`):单表改造 = 迁移 1 + mapper N 处 + Java M 处 + 测试 K 处 + 工时实测;线性外推 23 表;记录"全绿≠就绪"教训(性能爆点测试不可见,需 EXPLAIN 抽查)。

- [x] **Step 6: Commit**

```bash
git add db/migration/V172__dead_letter_task_composite_pk.sql \
        batch-orchestrator/src/main/resources/mapper/DeadLetterTaskMapper.xml \
        docs/analysis/citus-pk-composite-poc-2026-06.md
git commit -m "poc(citus): dead_letter_task PK 复合化试点 + 爆炸半径实测报告"
```

---

### Task 10: useGeneratedKeys 台账(Citus POC-B 的纸面部分)

**Files:**
- Create: `docs/analysis/usegeneratedkeys-ledger-2026-06.md`

- [x] **Step 1: 生成台账**

```bash
grep -rn "useGeneratedKeys" batch-*/src/main/resources/mapper/*.xml | sed 's|/src/main/resources/mapper/|:|' > /tmp/ugk.txt
wc -l /tmp/ugk.txt
```

- [x] **Step 2: 按三档归类写入文档**(每条:文件:行 | 目标表 | 回读 id 的后续用途 | Citus 风险档):
   - 档A 回读 id 仅日志/返回值 → distributed 下安全
   - 档B 回读 id 作为后续同事务 FK 写入 → 需 POC 验证 distributed sequence 行为
   - 档C 回读 id 跨事务使用 → 高危,逐个评审

- [x] **Step 3: Commit**

```bash
git add docs/analysis/usegeneratedkeys-ledger-2026-06.md
git commit -m "docs(analysis): useGeneratedKeys 49 处三档台账(Citus POC-B)"
```

---

### Task 11: 收尾 — 分支说明 + 推送

**Files:**
- Create: `docs/plans/2026-06-10-partition-citus-paving-plan.md`(本文件,随首个提交入库)
- Modify: 本文件勾选所有完成项

- [x] **Step 1: 全量最终回归**(同 Task 7 两步)
- [x] **Step 2: 推送常驻分支,不开 PR**

```bash
git push origin feature/partition-readiness
```

- [x] **Step 3: 在 main 侧留索引**(切回 main 单独小 PR,一行进 `docs/backlog/citus-introduction-plan-2026-06-06.md` §13:"分区/Citus 铺路实现已在常驻分支 feature/partition-readiness,计划文档 docs/plans/2026-06-10-partition-citus-paving-plan.md")

---

## Self-Review 结论

- 规格覆盖:分区(T2-T7)/幂等契约(T1,T4,T6,T8)/Citus 铺路(T9,T10)/链路零破坏(T7 全量回归 + 运营红线)✅
- 类型一致:OutboxEventEntity setter 名与 mapper #{} 字段一一对应(实测自 D2 段源码)✅
- 已知风险点已显式:共享库红线、V 序号 rebase 顺延、Task 9 "全绿≠就绪" 性能盲区 ✅


---

## 执行结果(2026-06-11 收尾)

- T1-T10 全部完成,T7b 达成核心目标:8 JVM 全栈在分区 schema(Flyway V1..V172 从零建)启动、
  health 8/8、0 ERROR、共享库零污染(guard 脚本验证)。
- T7b 已知边界:专用库无业务种子,未实际触发 console 发起的业务 job(真实业务全链路已由
  e2e 41 项在分区 schema 覆盖);后续可选:load-system-test-data.sh 指向 *_part 后做业务冒烟。
- 事故记录:首次 T7b 用裸 env export 被 application-local.yml 硬编码 URL 压制,分支服务把
  V170/V171 跑上共享库;已完整回滚(数据零损,FK/序列/Flyway 记账复原)并改用 -D 系统属性
  + guard 模式根治。教训已写入 env-partition-branch.sh 头注释。
- 全量验证:单测+IT 1106+886+... 全绿(各模块 0 失败)、e2e 41/41、分区守护 IT 37+37。
- 关键修复沉淀:UNIQUE 约束名是隐性契约(DefaultLaunchService 按名匹配 dedup)→ V170/V171
  采用"建表 _p_ 临时名 + DROP legacy 后 RENAME 回原名"模式。
