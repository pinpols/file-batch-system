# EXPORT 分片 keyset 区间优化(方案A)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让导出分片在命中激活条件时,每片在 DB 侧只读自己 1/N 的游标区间(索引区间扫),把 hashtext 的 N× 全表扫描放大降到 ~1×;不命中则逐字节退回现有 hashtext。

**Architecture:** 在两个 ExportDataPlugin(`SqlTemplateExportDataPlugin` / `GenericJdbcMappedExportDataPlugin`)的 `loadDetailPage` 内,首次调用(`exportSnapshot` 无缓存边界)时算游标列 `min/max` 等宽区间 `[loN,hiN)` 缓存进 `exportSnapshot`(跨页复用的同一可变 map);分页谓词由 hashtext 换成 `cur>=:__loN AND cur<:__hiN`(末片含上界)。边界每分区自算,不经 orchestrator(对称 import range-slice 的每分区 `statObject`)。任何异常/空 min-max/非数值游标列 → 退回 hashtext,绝不失败。

**Tech Stack:** Java 21、Spring JDBC(NamedParameterJdbcTemplate / JdbcTemplate)、PostgreSQL、JUnit5 + AssertJ + Mockito、Testcontainers(`AbstractIntegrationTest`)。

**设计依据:** `docs/backlog/export-partition-keyset-range-2026-06-06.md`

---

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `batch-worker-export/.../plugin/ExportKeysetRange.java` | 不可变值对象:`loN`/`hiN`/`active`(BigDecimal),含等宽切分静态工厂 | Create |
| `batch-worker-export/.../plugin/ExportKeysetRangePlanner.java` | 纯逻辑:判激活 + 算 `[loN,hiN)` + snapMap 缓存读写;不碰 SQL 执行 | Create |
| `.../plugin/SqlTemplateExportDataPlugin.java` | `buildPagedSql` 增 range 变体;`loadDetailPage` 接 planner | Modify |
| `.../plugin/GenericJdbcMappedExportDataPlugin.java` | `buildDetailQuery` 增 range 变体;`loadDetailPage` 接 planner | Modify |
| `batch-common/.../plugin/PipelineRuntimeKeys`(若 export snapshot key 常量在此)或 plugin 内私有常量 | snapMap 缓存键 `__keysetRange` | Modify/inline |
| `.../plugin/ExportKeysetRangePlannerTest.java` | planner 单测 | Create |
| `.../plugin/SqlTemplateExportKeysetRangeTest.java` | sql_template buildPagedSql range 变体单测 | Create |
| `.../plugin/GenericJdbcMappedExportKeysetRangeTest.java` | jdbc_mapped buildDetailQuery range 变体单测 | Create |
| `.../plugin/ExportKeysetRangeIT.java` | 端到端:4 片无重叠+全覆盖 / 退回 hashtext / 倾斜 / 边界只算一次 | Create |

> 激活开关 `partition_keyset_range` 走现有 `templateConfig` map(`context.templateConfig().get("partition_keyset_range")`),无需新增表列或 DTO 字段。

---

## Task 0: 隔离 worktree + 分支

**并行 session 在 churn 主工作区,必须隔离。**

- [ ] **Step 1: 用 superpowers:using-git-worktrees 建 worktree(基于最新 main)**

REQUIRED SUB-SKILL: `superpowers:using-git-worktrees`。基线 `origin/main`(含 #390,`788df5f2f`)。分支名 `feature/export-keyset-range`。

- [ ] **Step 2: worktree 内确认依赖在位**

Run: `grep -c 'partitionNo\|partitionCount' batch-common/src/main/java/com/example/batch/common/plugin/ExportDataContext.java`
Expected: `2`

---

## Task 1: ExportKeysetRange 值对象 + 等宽切分

**Files:**
- Create: `batch-worker-export/src/main/java/com/example/batch/worker/exports/plugin/ExportKeysetRange.java`
- Test: `batch-worker-export/src/test/java/com/example/batch/worker/exports/plugin/ExportKeysetRangePlannerTest.java`(本 Task 仅测 ExportKeysetRange.equalWidth)

- [ ] **Step 1: 写失败测试**

```java
package com.example.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ExportKeysetRangePlannerTest {

  @Test
  void equalWidth_4partitions_disjoint_and_cover() {
    // lo=0 hi=100 count=4 → [0,25) [25,50) [50,75) [75,100]
    ExportKeysetRange p1 = ExportKeysetRange.equalWidth(new BigDecimal("0"), new BigDecimal("100"), 4, 1);
    ExportKeysetRange p4 = ExportKeysetRange.equalWidth(new BigDecimal("0"), new BigDecimal("100"), 4, 4);
    assertThat(p1.active()).isTrue();
    assertThat(p1.loN()).isEqualByComparingTo("0");
    assertThat(p1.hiN()).isEqualByComparingTo("25");
    assertThat(p1.includeUpper()).isFalse();
    assertThat(p4.loN()).isEqualByComparingTo("75");
    assertThat(p4.hiN()).isEqualByComparingTo("100");
    assertThat(p4.includeUpper()).isTrue(); // 末片含上界,防丢 max 行
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl batch-worker-export test -Dtest=ExportKeysetRangePlannerTest#equalWidth_4partitions_disjoint_and_cover`
Expected: 编译失败 `cannot find symbol ExportKeysetRange`

- [ ] **Step 3: 实现 ExportKeysetRange**

```java
package com.example.batch.worker.exports.plugin;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 单个分片的游标值区间 [loN, hiN)(末片 includeUpper=true 时含上界)。等宽切分,数值游标列专用。
 * active=false 表示不启用 keyset-range(调用方退回 hashtext)。
 */
public record ExportKeysetRange(boolean active, BigDecimal loN, BigDecimal hiN, boolean includeUpper) {

  static final ExportKeysetRange INACTIVE = new ExportKeysetRange(false, null, null, false);

  /** lo/hi 为游标列 min/max;partitionNo 1-based。lo>=hi(单值或空)→ INACTIVE(交调用方退 hashtext)。 */
  static ExportKeysetRange equalWidth(BigDecimal lo, BigDecimal hi, int partitionCount, int partitionNo) {
    if (lo == null || hi == null || lo.compareTo(hi) >= 0 || partitionCount <= 1) {
      return INACTIVE;
    }
    BigDecimal span = hi.subtract(lo);
    BigDecimal width = span.multiply(BigDecimal.valueOf(partitionNo - 1))
        .divide(BigDecimal.valueOf(partitionCount), MathContext.DECIMAL64);
    BigDecimal loN = lo.add(width);
    boolean last = partitionNo == partitionCount;
    BigDecimal hiN = last
        ? hi
        : lo.add(span.multiply(BigDecimal.valueOf(partitionNo)).divide(BigDecimal.valueOf(partitionCount), MathContext.DECIMAL64));
    return new ExportKeysetRange(true, loN, hiN, last);
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl batch-worker-export test -Dtest=ExportKeysetRangePlannerTest#equalWidth_4partitions_disjoint_and_cover`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add batch-worker-export/src/main/java/com/example/batch/worker/exports/plugin/ExportKeysetRange.java \
        batch-worker-export/src/test/java/com/example/batch/worker/exports/plugin/ExportKeysetRangePlannerTest.java
git commit -m "feat(export-keyset): ExportKeysetRange 等宽区间值对象"
```

---

## Task 2: ExportKeysetRangePlanner — 激活判定 + 边界缓存

**Files:**
- Create: `batch-worker-export/.../plugin/ExportKeysetRangePlanner.java`
- Test: 追加到 `ExportKeysetRangePlannerTest.java`

职责:给定 `ExportDataContext` + 一个「算 min/max 的回调」,判激活并返回本片 `ExportKeysetRange`;结果按片缓存进 `context.exportSnapshot()`(跨页同一 map),只算一次。任何异常 → INACTIVE。

- [ ] **Step 1: 写失败测试(激活 + 缓存只算一次 + 非数值退化 + 异常退化)**

```java
  @org.junit.jupiter.api.Nested
  class PlannerTest {
    private java.util.Map<String, Object> snap;
    private com.example.batch.common.plugin.ExportDataContext ctx(int no, int count, boolean optIn) {
      snap = new java.util.LinkedHashMap<>();
      java.util.Map<String,Object> tc = new java.util.LinkedHashMap<>();
      if (optIn) tc.put("partition_keyset_range", true);
      return new com.example.batch.common.plugin.ExportDataContext(
          "ta","J","B","TPL", tc, snap, no, count);
    }

    @Test
    void active_computesRange_andCachesOnce() {
      ExportKeysetRangePlanner planner = new ExportKeysetRangePlanner();
      int[] calls = {0};
      java.util.function.Supplier<BigDecimal[]> minMax = () -> { calls[0]++; return new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("100")}; };
      var c = ctx(1, 4, true);
      ExportKeysetRange r1 = planner.resolve(c, minMax);
      ExportKeysetRange r2 = planner.resolve(c, minMax); // 第二页
      assertThat(r1.active()).isTrue();
      assertThat(r1.hiN()).isEqualByComparingTo("25");
      assertThat(calls[0]).isEqualTo(1); // 边界只算一次
      assertThat(r2.hiN()).isEqualByComparingTo("25");
    }

    @Test
    void inactive_whenNotOptIn() {
      var r = new ExportKeysetRangePlanner().resolve(ctx(1,4,false), () -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.TEN});
      assertThat(r.active()).isFalse();
    }

    @Test
    void inactive_whenSinglePartition() {
      var r = new ExportKeysetRangePlanner().resolve(ctx(1,1,true), () -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.TEN});
      assertThat(r.active()).isFalse();
    }

    @Test
    void inactive_whenMinMaxThrows() { // fallback:绝不失败
      var r = new ExportKeysetRangePlanner().resolve(ctx(1,4,true), () -> { throw new RuntimeException("nullable col"); });
      assertThat(r.active()).isFalse();
    }

    @Test
    void inactive_whenMinMaxNull() { // 空表/全 null
      var r = new ExportKeysetRangePlanner().resolve(ctx(1,4,true), () -> new BigDecimal[]{null, null});
      assertThat(r.active()).isFalse();
    }
  }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl batch-worker-export test -Dtest=ExportKeysetRangePlannerTest`
Expected: 编译失败 `cannot find symbol ExportKeysetRangePlanner`

- [ ] **Step 3: 实现 planner**

```java
package com.example.batch.worker.exports.plugin;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.plugin.ExportDataContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/** keyset-range 激活判定 + 每分区边界(缓存进 exportSnapshot,只算一次)。任何异常 → INACTIVE(退回 hashtext)。 */
@Slf4j
public class ExportKeysetRangePlanner {

  /** exportSnapshot 缓存键:已解析的本片区间(含 INACTIVE 也缓存,避免反复重算)。 */
  static final String SNAP_KEY = "__export_keyset_range";

  /**
   * @param minMaxSupplier 算游标列 [min,max] 的回调(BigDecimal[2];非数值/空 → 元素 null 或抛异常)。
   *     仅在激活且首次解析时调用一次。
   */
  ExportKeysetRange resolve(ExportDataContext context, Supplier<BigDecimal[]> minMaxSupplier) {
    Map<String, Object> snap = context.exportSnapshot();
    if (snap != null && snap.get(SNAP_KEY) instanceof ExportKeysetRange cached) {
      return cached;
    }
    ExportKeysetRange resolved = compute(context, minMaxSupplier);
    if (snap != null) {
      snap.put(SNAP_KEY, resolved);
    }
    return resolved;
  }

  private ExportKeysetRange compute(ExportDataContext context, Supplier<BigDecimal[]> minMaxSupplier) {
    if (context.partitionCount() <= 1 || !optedIn(context)) {
      return ExportKeysetRange.INACTIVE;
    }
    try {
      BigDecimal[] mm = minMaxSupplier.get();
      if (mm == null || mm.length != 2 || mm[0] == null || mm[1] == null) {
        return ExportKeysetRange.INACTIVE;
      }
      return ExportKeysetRange.equalWidth(mm[0], mm[1], context.partitionCount(), context.partitionNo());
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(ExportKeysetRangePlanner.class, "catch:keysetRangeMinMax", ex);
      return ExportKeysetRange.INACTIVE; // fallback hashtext
    }
  }

  private boolean optedIn(ExportDataContext context) {
    Map<String, Object> tc = context.templateConfig();
    Object v = tc == null ? null : tc.get("partition_keyset_range");
    return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl batch-worker-export test -Dtest=ExportKeysetRangePlannerTest`
Expected: PASS(6 个)

- [ ] **Step 5: Commit**

```bash
git add batch-worker-export/src/main/java/com/example/batch/worker/exports/plugin/ExportKeysetRangePlanner.java \
        batch-worker-export/src/test/java/com/example/batch/worker/exports/plugin/ExportKeysetRangePlannerTest.java
git commit -m "feat(export-keyset): 激活判定 + 每分区边界缓存(只算一次,异常退 hashtext)"
```

---

## Task 3: 接入 SqlTemplateExportDataPlugin

**Files:**
- Modify: `.../plugin/SqlTemplateExportDataPlugin.java`
- Test: `.../plugin/SqlTemplateExportKeysetRangeTest.java`

`buildPagedSql` 增 range 变体:激活时谓词用 `base.cur >= :__loN AND base.cur < :__hiN`(末片 `<= :__hiN`),并仍叠加 `base.cur > :__cursor`;未激活保持 hashtext 原样。`loadDetailPage` 在算 baseSql 后,用 planner 解析区间(min/max 回调 = `SELECT min(cur),max(cur) FROM (baseSql) base` 经同一 txTemplate/RLS 执行,转 BigDecimal)。

- [ ] **Step 1: 写失败测试(纯 SQL 字符串断言,不连库)**

```java
package com.example.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SqlTemplateExportKeysetRangeTest {

  @Test
  void rangePredicate_replacesHashtext_whenActive() {
    ExportKeysetRange r = new ExportKeysetRange(true, new BigDecimal("0"), new BigDecimal("25"), false);
    String sql = SqlTemplateExportDataPlugin.buildPagedSql(
        "SELECT id, v FROM biz.t WHERE tenant_id = :tenantId", "id", false, r);
    assertThat(sql).contains("base.\"id\" >= :__loN").contains("base.\"id\" < :__hiN");
    assertThat(sql).doesNotContain("hashtext");
  }

  @Test
  void lastPartition_includesUpperBound() {
    ExportKeysetRange r = new ExportKeysetRange(true, new BigDecimal("75"), new BigDecimal("100"), true);
    String sql = SqlTemplateExportDataPlugin.buildPagedSql("SELECT id FROM biz.t", "id", false, r);
    assertThat(sql).contains("base.\"id\" <= :__hiN");
  }

  @Test
  void inactive_fallsBackToHashtext() {
    String sql = SqlTemplateExportDataPlugin.buildPagedSql(
        "SELECT id FROM biz.t", "id", false, ExportKeysetRange.INACTIVE_FOR(4, 2));
    assertThat(sql).contains("hashtext");
    assertThat(sql).doesNotContain(":__loN");
  }

  @Test
  void withCursor_keepsKeysetPagination() {
    ExportKeysetRange r = new ExportKeysetRange(true, new BigDecimal("0"), new BigDecimal("25"), false);
    String sql = SqlTemplateExportDataPlugin.buildPagedSql("SELECT id FROM biz.t", "id", true, r);
    assertThat(sql).contains("base.\"id\" > :__cursor").contains("base.\"id\" < :__hiN");
  }
}
```

> 注:测试用到 `ExportKeysetRange.INACTIVE_FOR(count,no)` —— 一个携带 partitionCount/No 但 active=false 的工厂,
> 供 hashtext 分支拿到 count/no 拼旧谓词。Task 1 的 INACTIVE 是无 count/no 的单例;此处需带值。
> **回到 Task 1 给 ExportKeysetRange 加** `int partitionCount` `int partitionNo` 两字段 + `INACTIVE_FOR(count,no)` 工厂,
> 并让 `equalWidth` 填入 count/no。(更新 Task 1 的 record 定义与 equalWidth;INACTIVE 单例 count/no=0。)

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl batch-worker-export test -Dtest=SqlTemplateExportKeysetRangeTest`
Expected: 编译失败(buildPagedSql 新重载 / INACTIVE_FOR 不存在)

- [ ] **Step 3: 改 ExportKeysetRange(加 count/no + INACTIVE_FOR)**

```java
public record ExportKeysetRange(
    boolean active, BigDecimal loN, BigDecimal hiN, boolean includeUpper,
    int partitionCount, int partitionNo) {

  static final ExportKeysetRange INACTIVE = new ExportKeysetRange(false, null, null, false, 0, 0);

  static ExportKeysetRange INACTIVE_FOR(int partitionCount, int partitionNo) {
    return new ExportKeysetRange(false, null, null, false, partitionCount, partitionNo);
  }

  static ExportKeysetRange equalWidth(BigDecimal lo, BigDecimal hi, int partitionCount, int partitionNo) {
    if (lo == null || hi == null || lo.compareTo(hi) >= 0 || partitionCount <= 1) {
      return INACTIVE_FOR(partitionCount, partitionNo);
    }
    java.math.BigDecimal span = hi.subtract(lo);
    java.math.BigDecimal loN = lo.add(span.multiply(BigDecimal.valueOf(partitionNo - 1))
        .divide(BigDecimal.valueOf(partitionCount), java.math.MathContext.DECIMAL64));
    boolean last = partitionNo == partitionCount;
    BigDecimal hiN = last ? hi : lo.add(span.multiply(BigDecimal.valueOf(partitionNo))
        .divide(BigDecimal.valueOf(partitionCount), java.math.MathContext.DECIMAL64));
    return new ExportKeysetRange(true, loN, hiN, last, partitionCount, partitionNo);
  }
}
```
> 同步把 Task 1 与 Task 2 测试里 `new ExportKeysetRange(...)` 的 4 参构造改为 6 参(补 count/no,如 `,4,1`)。

- [ ] **Step 4: 改 buildPagedSql(加 ExportKeysetRange 重载;旧 5 参重载内部转调)**

```java
  static String buildPagedSql(String baseSql, String cursorColumn, boolean hasCursor, ExportKeysetRange range) {
    String cursorIdent = com.example.batch.common.jdbc.JdbcMappedSqlValidator.quotePg(cursorColumn);
    StringBuilder where = new StringBuilder();
    if (range != null && range.active()) {
      where.append("WHERE base.%s >= :__loN%n".formatted(cursorIdent));
      where.append("AND base.%s %s :__hiN%n".formatted(cursorIdent, range.includeUpper() ? "<=" : "<"));
    } else if (range != null && range.partitionCount() > 1) {
      where.append("WHERE ((hashtext(base.%s::text) %% %d) + %d) %% %d = %d%n"
          .formatted(cursorIdent, range.partitionCount(), range.partitionCount(),
              range.partitionCount(), range.partitionNo() - 1));
    }
    if (hasCursor) {
      where.append(where.isEmpty() ? "WHERE " : "AND ").append("base.%s > :__cursor%n".formatted(cursorIdent));
    }
    return """
    WITH base AS (
    %s
    )
    SELECT *
    FROM base
    %sORDER BY base.%s ASC
    LIMIT :__limit
    """.formatted(baseSql, where, cursorIdent);
  }

  // 旧签名保留为兼容重载,转调新重载(供未改的调用点/测试)
  static String buildPagedSql(String baseSql, String cursorColumn, boolean hasCursor, int partitionCount, int partitionNo) {
    return buildPagedSql(baseSql, cursorColumn, hasCursor, ExportKeysetRange.INACTIVE_FOR(partitionCount, partitionNo));
  }
```

- [ ] **Step 5: 运行单测确认通过**

Run: `mvn -q -pl batch-worker-export test -Dtest=SqlTemplateExportKeysetRangeTest,ExportKeysetRangePlannerTest`
Expected: PASS

- [ ] **Step 6: 接 loadDetailPage(算 min/max + 用区间)**

在 `loadDetailPage` 内,`baseSql` 算出后、`buildPagedSql` 调用前替换为:

```java
    ExportKeysetRange range = keysetRangePlanner.resolve(context, () -> minMax(baseSql, baseParams));
    String sql = buildPagedSql(baseSql, spec.cursorColumn(), cursor != null, range);
    // params 注入边界(激活时)
    // ...在 params.put("__limit", limit) 附近:
    if (range.active()) {
      params.put("__loN", range.loN());
      params.put("__hiN", range.hiN());
    }
```

新增私有方法 + 字段:
```java
  private final ExportKeysetRangePlanner keysetRangePlanner = new ExportKeysetRangePlanner();

  /** 算游标列 [min,max];null/非数值 → 元素 null(planner 据此退 hashtext)。复用 RLS 只读 tx。 */
  private BigDecimal[] minMax(String baseSql, Map<String, Object> baseParams) {
    String cur = com.example.batch.common.jdbc.JdbcMappedSqlValidator.quotePg(
        // cursorColumn 从外层传入更稳;此处示意,实现时把 cursorColumn 作参数传进来
        "PLACEHOLDER_REPLACED_BY_CURSOR");
    String mmSql = "SELECT min(%s) AS lo, max(%s) AS hi FROM (%s) base".formatted(cur, cur, baseSql);
    Map<String, Object> row = txTemplate.execute(status -> {
      RlsTenantSessionSupport.applyIfPresent(businessDataSource);
      return jdbc.queryForMap(mmSql, baseParams);
    });
    return new BigDecimal[] {toBig(row.get("lo")), toBig(row.get("hi"))};
  }

  private static BigDecimal toBig(Object v) {
    if (v instanceof BigDecimal b) return b;
    if (v instanceof Number n) return new BigDecimal(n.toString());
    return null; // 非数值游标列 → 退 hashtext
  }
```
> 实现注意:把 `spec.cursorColumn()` 作参数传进 `minMax`,替换 PLACEHOLDER。`import java.math.BigDecimal;`。

- [ ] **Step 7: 运行 plugin 既有单测确保无回归**

Run: `mvn -q -pl batch-worker-export test -Dtest=SqlTemplateExportDataPluginTest,SqlTemplateExportPartitionTest`
Expected: PASS(既有 hashtext / 分页测试不受影响)

- [ ] **Step 8: Commit**

```bash
git add batch-worker-export/src/main/java/com/example/batch/worker/exports/plugin/ExportKeysetRange.java \
        batch-worker-export/src/main/java/com/example/batch/worker/exports/plugin/SqlTemplateExportDataPlugin.java \
        batch-worker-export/src/test/java/com/example/batch/worker/exports/plugin/SqlTemplateExportKeysetRangeTest.java \
        batch-worker-export/src/test/java/com/example/batch/worker/exports/plugin/ExportKeysetRangePlannerTest.java
git commit -m "feat(export-keyset): sql_template 接入 keyset 区间谓词 + min/max 边界"
```

---

## Task 4: 接入 GenericJdbcMappedExportDataPlugin

**Files:**
- Modify: `.../plugin/GenericJdbcMappedExportDataPlugin.java`
- Test: `.../plugin/GenericJdbcMappedExportKeysetRangeTest.java`

源是物理表 `schema.detailTable` + `fk = batchId`,游标列 = `detailOrderByColumn`。min/max =
`SELECT min(ob),max(ob) FROM schema.detailTable WHERE fk = ?`(走索引)。`buildDetailQuery` 的分片谓词
由 hashtext 换 `ob >= ? AND ob < ?`(末片 `<=`),其余 keyset/fk 不变。

- [ ] **Step 1: 写失败测试(buildDetailQuery range 变体,SQL 串 + args 断言)**

```java
package com.example.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GenericJdbcMappedExportKeysetRangeTest {
  // 用与 GenericJdbcMappedExportDataPlugin.buildDetailQuery 相同的 DetailSql 入参构造
  @Test
  void rangePredicate_whenActive() {
    var dsql = new GenericJdbcMappedExportDataPlugin.DetailSql(
        "\"id\",\"v\"", "biz.\"t\"", "\"batch_id\"", "\"id\"");
    var range = new ExportKeysetRange(true, new BigDecimal("0"), new BigDecimal("25"), false, 4, 1);
    var pq = GenericJdbcMappedExportDataPlugin.buildDetailQuery(dsql, 7L, null, 500, range);
    assertThat(pq.sql()).contains("\"id\" >= ?").contains("\"id\" < ?");
    assertThat(pq.sql()).doesNotContain("hashtext");
  }

  @Test
  void inactive_keepsHashtext() {
    var dsql = new GenericJdbcMappedExportDataPlugin.DetailSql("\"id\"", "biz.\"t\"", "\"batch_id\"", "\"id\"");
    var pq = GenericJdbcMappedExportDataPlugin.buildDetailQuery(dsql, 7L, null, 500, ExportKeysetRange.INACTIVE_FOR(4, 2));
    assertThat(pq.sql()).contains("hashtext");
  }
}
```
> 注:若现有 `buildDetailQuery` 签名是 `(DetailSql, batchId, cursor, pageSize, partitionCount, partitionNo)`,
> 改为接受 `ExportKeysetRange`;旧签名保留兼容重载转调(同 Task 3 模式)。`DetailSql`/`PagedQuery` 若为 private,
> 提升为 package-private(同包测试可见)或在测试包内用反射——优先提升可见性。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl batch-worker-export test -Dtest=GenericJdbcMappedExportKeysetRangeTest`
Expected: 编译失败

- [ ] **Step 3: 改 buildDetailQuery(range 变体)+ loadDetailPage 接 planner**

`buildDetailQuery` 内,分片谓词分支改为:
```java
    // 原 hashtext 分支前置判断
    if (range != null && range.active()) {
      where.add(ob + (range.includeUpper() ? " <= ?" : " < ?")); // 上界
      where.add(ob + " >= ?");                                   // 下界
      args.add(range.hiN());
      args.add(range.loN());
    } else if (range != null && range.partitionCount() > 1) {
      where.add("((hashtext(" + ob + "::text) % ?) + ?) % ? = ?");
      args.add(range.partitionCount()); args.add(range.partitionCount());
      args.add(range.partitionCount()); args.add(range.partitionNo() - 1);
    }
```
> 按现有 `buildDetailQuery` 实际 where/args 拼接风格对齐(占位符 `?` + args 顺序必须与 SQL 出现顺序一致)。

`loadDetailPage` 内 `buildDetailQuery(...)` 调用前:
```java
    ExportKeysetRange range = keysetRangePlanner.resolve(context, () -> minMax(spec, batchId));
    PagedQuery pq = buildDetailQuery(new DetailSql(cols.toString(), fq, fk, ob), batchId, cursor, pageSize, range);
```
新增:
```java
  private final ExportKeysetRangePlanner keysetRangePlanner = new ExportKeysetRangePlanner();

  private java.math.BigDecimal[] minMax(JdbcMappedExportSpec spec, Long batchId) {
    String fq = JdbcMappedSqlValidator.quotePg(spec.schema()) + "." + JdbcMappedSqlValidator.quotePg(spec.detailTable());
    String ob = JdbcMappedSqlValidator.quotePg(spec.detailOrderByColumn());
    String fk = JdbcMappedSqlValidator.quotePg(spec.detailFkColumn());
    String sql = "SELECT min(" + ob + ") lo, max(" + ob + ") hi FROM " + fq + " WHERE " + fk + " = ?";
    Map<String,Object> row = txTemplate.execute(status -> {
      RlsTenantSessionSupport.applyIfPresent(businessDataSource);
      return jdbcTemplate.queryForMap(sql, batchId);
    });
    return new java.math.BigDecimal[]{toBig(row.get("lo")), toBig(row.get("hi"))};
  }
  private static java.math.BigDecimal toBig(Object v) {
    if (v instanceof java.math.BigDecimal b) return b;
    if (v instanceof Number n) return new java.math.BigDecimal(n.toString());
    return null;
  }
```

- [ ] **Step 4: 运行确认通过 + 无回归**

Run: `mvn -q -pl batch-worker-export test -Dtest=GenericJdbcMappedExportKeysetRangeTest,GenericJdbcMappedExportPartitionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add batch-worker-export/src/main/java/com/example/batch/worker/exports/plugin/GenericJdbcMappedExportDataPlugin.java \
        batch-worker-export/src/test/java/com/example/batch/worker/exports/plugin/GenericJdbcMappedExportKeysetRangeTest.java
git commit -m "feat(export-keyset): jdbc_mapped 接入 keyset 区间谓词 + min/max 边界"
```

---

## Task 5: 集成测试(端到端正确性)

**Files:**
- Create: `.../plugin/ExportKeysetRangeIT.java`(继承 `AbstractIntegrationTest`,Testcontainers PG)

- [ ] **Step 1: 写 IT**

建一张 `biz` 测试表(如 `keyset_demo(tenant_id, id bigint, v text)`),插 1000 行(id=1..1000,含一段空洞如跳过 400..450 制造倾斜)。对 `partitionCount=4` 各片调 sql_template plugin 的 `loadDetailPage` 翻完所有页,断言:

```java
// 伪代码结构(实现按 AbstractIntegrationTest 约定补全 datasource/RLS/模板配置)
@Test void fourPartitions_disjoint_and_fullCoverage() {
  // opt-in partition_keyset_range=true,游标列 id
  Set<Long> all = new HashSet<>();
  for (int no = 1; no <= 4; no++) {
    Set<Long> part = collectAllPages(no, 4, /*optIn*/ true);
    assertThat(Collections.disjoint(all, part)).isTrue(); // 无重叠
    all.addAll(part);
  }
  assertThat(all).hasSize(950); // 1000 - 50 空洞
}

@Test void skew_stillNoLossNoDup() { /* 同上,断言并集=全集,允许各片 size 不均 */ }

@Test void notOptIn_fallsBackHashtext_stillCorrect() { /* optIn=false,断言并集=全集 */ }

@Test void boundaryComputedOnce_perPartition() {
  // 用 spy/计数 datasource 或日志断言:一个分片多页只跑一次 min/max SQL
}
```

- [ ] **Step 2: 运行 IT**

Run: `mvn -q -pl batch-worker-export verify -Dit.test=ExportKeysetRangeIT`
Expected: PASS(4 个用例)

- [ ] **Step 3: Commit**

```bash
git add batch-worker-export/src/test/java/com/example/batch/worker/exports/plugin/ExportKeysetRangeIT.java
git commit -m "test(export-keyset): 端到端 4 片无重叠+全覆盖+倾斜+退hashtext+边界只算一次 IT"
```

---

## Task 6: 文档状态更新 + 全量构建 + PR

- [ ] **Step 1: 更新 backlog 状态**

把 `docs/backlog/export-partition-keyset-range-2026-06-06.md` 顶部状态由「已设计,待实测瓶颈驱动」改为
「✅ 已实现(2026-06-06)」并在末尾加「实现记录」(对照 import backlog 的写法)。

- [ ] **Step 2: 模块全量测试(防止局部跑漏)**

Run: `mvn -q -pl batch-worker-export -am clean verify`
Expected: BUILD SUCCESS

- [ ] **Step 3: push 前 clean compile(stale cache 防假绿,见团队规约)**

Run: `mvn -q -pl batch-worker-export -am clean compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: push 分支 + 开 PR(base main),enable auto-merge squash**

```bash
git push -u origin feature/export-keyset-range
gh pr create --base main --head feature/export-keyset-range \
  --title "feat(export): 分片 keyset 区间优化 — hashtext N× 全扫降到 ~1×(opt-in)" \
  --body "实现 docs/backlog/export-partition-keyset-range-2026-06-06.md。激活(partitionCount>1 + partition_keyset_range=true + 数值游标列)时每分区 min/max 等宽区间走索引区间扫;否则退 hashtext。带 fallback 绝不失败。"
gh pr merge --auto --squash
```

- [ ] **Step 5: 合并后同步 main + 清理 worktree**

REQUIRED SUB-SKILL: `superpowers:using-git-worktrees`(清理);`superpowers:finishing-a-development-branch`。

---

## Self-Review notes(已自检)

- **Spec 覆盖**:激活条件(§2)→ planner.optedIn + partitionCount;每分区自算边界(§3.1)→ minMax 回调在 plugin 内、不经 orchestrator;等宽切(§3.2)→ ExportKeysetRange.equalWidth;谓词(§3.3)→ buildPagedSql/buildDetailQuery range 变体;倾斜接受(§4)→ IT skew 用例;组件改动(§5)→ Task 3/4;fallback(§5)→ planner try/catch + toBig null + INACTIVE;测试(§7)→ Task 1/2/3/4/5。✓
- **占位符**:`minMax` 里 PLACEHOLDER_REPLACED_BY_CURSOR 已显式标注实现时替换(把 cursorColumn 传入)——非遗漏,是实现指令。
- **类型一致**:`ExportKeysetRange` 在 Task 1 定 4 参、Task 3 升 6 参——已在 Task 3 Step 3 显式给出最终 6 参定义并要求回填 Task 1/2 测试构造。`buildDetailQuery` 增 `ExportKeysetRange` 重载 + 兼容旧重载,与 `buildPagedSql` 同模式。
- **可见性**:`DetailSql`/`PagedQuery` 需 package-private 供同包测试(Task 4 Step 1 注明)。
