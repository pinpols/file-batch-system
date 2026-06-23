# EXPORT 分片导出修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 EXPORT 真正按 partition 切数据(每片只取 1/N、各产独立分片文件),消除「分 N 片 → N× 重复/覆盖丢数据」的 bug。

**Architecture:** orchestrator 已正确切 N 片并把 `PARTITION_NO`/`PARTITION_COUNT` 注入 executionContext;修复全在 export worker 侧:把这两个值经 `ExportDataContext` 传进 2 个数据插件,在现有 keyset-分页 SQL 外层叠加 `hashtext % count = idx` 分片谓词;文件名/objectName 加 `_p{NO}of{COUNT}` 后缀使各片独立落地。产物形态 = N 个分片文件(与 import 分片、现有独立-partition 架构对称)。

**Tech Stack:** Java 17 + Spring Boot,MyBatis/JdbcTemplate,PostgreSQL(`hashtext`),JUnit5 + AssertJ + Mockito,Testcontainers(`AbstractIntegrationTest`)。

设计来源:[docs/backlog/export-partition-slice-fix-2026-06-06.md](../backlog/export-partition-slice-fix-2026-06-06.md)

---

## File Structure

| 文件 | 职责 / 改动 |
|---|---|
| `batch-common/.../plugin/ExportDataContext.java` | record 加 `int partitionNo`/`int partitionCount`;加 6 参兼容构造器(默认 1/1)使 RegisterStep 零改 |
| `batch-common/.../constants/BatchFileConstants.java` | 新增 `insertPartitionTag(name, no, count)` 工具 |
| `batch-worker-export/.../stage/GenerateStep.java` | `buildExportDataContext` 从 attributes 读 partition,用 8 参构造器注入 |
| `batch-worker-export/.../stage/PrepareStep.java` | 读 partition,fileName/objectName 加分片后缀 |
| `batch-worker-export/.../plugin/SqlTemplateExportDataPlugin.java` | `buildPagedSql` 叠加分片谓词;`loadDetailPage` 传入 partition |
| `batch-worker-export/.../plugin/GenericJdbcMappedExportDataPlugin.java` | `loadDetailPage` SQL 叠加分片谓词 |
| `batch-worker-export/src/test/.../plugin/SqlTemplateExportPartitionTest.java`(新) | buildPagedSql 分片单测 |
| `batch-worker-export/src/test/.../plugin/GenericJdbcMappedExportPartitionTest.java`(新) | JdbcMapped SQL 分片单测 |
| `batch-common/src/test/.../constants/BatchFileConstantsPartitionTagTest.java`(新) | insertPartitionTag 单测 |
| `batch-worker-export/src/test/.../plugin/ExportPartitionSliceIT.java`(新) | 真实 PG 分片完整性 IT(无重叠+全覆盖) |

**分片正确性前提**:sql_template 的 `cursorColumn` 缺省回退 `"id"`、jdbc_mapped 的 `detailOrderByColumn` 模板必填 —— 分片列恒存在,故**不单独做 fail-fast guard**(列不存在时现有 keyset 分页本就报错,非本次引入)。

---

## Task 0: 分支准备

**Files:** 无(git 操作)

- [ ] **Step 1: 从当前文档分支切出代码 feature 分支**

当前在 `docs/export-partition-slice-backlog-2026-06-06`(仅文档)。代码改动按 CLAUDE.md 走 `feature/<topic>`:

Run:
```bash
cd /Users/dengchao/Downloads/file-batch-system
git checkout -b feature/export-partition-slice
git branch --show-current
```
Expected: 输出 `feature/export-partition-slice`

---

## Task 1: ExportDataContext 加 partition 字段

**Files:**
- Modify: `batch-common/src/main/java/com/example/batch/common/plugin/ExportDataContext.java`

- [ ] **Step 1: 加字段 + 兼容构造器**

把 record 改为(新增 2 字段 + 一个 6 参兼容构造器,默认 partition 1/1,使 RegisterStep 等只关心数据头的调用点零改):

```java
package io.github.pinpols.batch.common.plugin;

import java.util.Map;

/** GENERATE-stage context for {@link ExportDataPlugin}. */
public record ExportDataContext(
    String tenantId,
    String jobCode,
    String batchNo,
    String templateCode,
    Map<String, Object> templateConfig,
    Map<String, Object> exportSnapshot,
    int partitionNo,
    int partitionCount) {

  /** 兼容构造器:不关心分片的调用点(如 RegisterStep.onRegistered)默认单片 1/1。 */
  public ExportDataContext(
      String tenantId,
      String jobCode,
      String batchNo,
      String templateCode,
      Map<String, Object> templateConfig,
      Map<String, Object> exportSnapshot) {
    this(tenantId, jobCode, batchNo, templateCode, templateConfig, exportSnapshot, 1, 1);
  }
}
```

- [ ] **Step 2: 编译验证(2 个旧构造点走兼容构造器,不报错)**

Run: `cd /Users/dengchao/Downloads/file-batch-system && mvn -q -pl batch-common -am compile`
Expected: BUILD SUCCESS(`GenerateStep`/`RegisterStep` 的 6 参 `new ExportDataContext(...)` 命中兼容构造器)

- [ ] **Step 3: Commit**

```bash
git add batch-common/src/main/java/com/example/batch/common/plugin/ExportDataContext.java
git commit -m "feat(export): ExportDataContext 增加 partitionNo/partitionCount 字段"
```

---

## Task 2: GenerateStep 注入真实 partition

**Files:**
- Modify: `batch-worker-export/src/main/java/com/example/batch/worker/exports/stage/GenerateStep.java`(`buildExportDataContext`,约 205-220 行)

- [ ] **Step 1: 读 partition 并用 8 参构造器注入**

`buildExportDataContext` 改为(复刻 import `ParseStep.intOrNull` 的读法;缺省 1/1):

```java
private ExportDataContext buildExportDataContext(
    ExportJobContext context, ExportPayload exportPayload) {
  Map<String, Object> tc = templateConfigMap(context);
  Object snap = context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT);
  Map<String, Object> snapMap = new LinkedHashMap<>();
  if (snap instanceof Map<?, ?> raw) {
    raw.forEach((k, v) -> snapMap.put(String.valueOf(k), v));
  }
  int partitionNo = intOrDefault(context.getAttributes().get(PipelineRuntimeKeys.PARTITION_NO), 1);
  int partitionCount =
      intOrDefault(context.getAttributes().get(PipelineRuntimeKeys.PARTITION_COUNT), 1);
  return new ExportDataContext(
      context.getTenantId(),
      context.getJobCode(),
      exportPayload.batchNo(),
      exportPayload.templateCode(),
      tc,
      snapMap,
      partitionNo,
      partitionCount);
}

private static int intOrDefault(Object value, int def) {
  if (value instanceof Number n) {
    return n.intValue();
  }
  if (value == null) {
    return def;
  }
  try {
    return Integer.parseInt(String.valueOf(value).trim());
  } catch (NumberFormatException ignored) {
    return def;
  }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd /Users/dengchao/Downloads/file-batch-system && mvn -q -pl batch-worker-export -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add batch-worker-export/src/main/java/com/example/batch/worker/exports/stage/GenerateStep.java
git commit -m "feat(export): GenerateStep 把 partition 注入 ExportDataContext"
```

---

## Task 3: SqlTemplate 插件分片谓词

**Files:**
- Test: `batch-worker-export/src/test/java/com/example/batch/worker/exports/plugin/SqlTemplateExportPartitionTest.java`(新)
- Modify: `.../plugin/SqlTemplateExportDataPlugin.java`(`buildPagedSql` 205-220、`loadDetailPage` 116 行)

- [ ] **Step 1: 写失败单测**

```java
package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlTemplateExportPartitionTest {

  @Test
  void shouldNotAddShardPredicate_whenSinglePartition() {
    String sql = SqlTemplateExportDataPlugin.buildPagedSql("SELECT * FROM t", "id", false, 1, 1);
    assertThat(sql).doesNotContain("hashtext");
  }

  @Test
  void shouldAddShardPredicate_whenMultiPartition() {
    String sql = SqlTemplateExportDataPlugin.buildPagedSql("SELECT * FROM t", "id", false, 4, 2);
    // partitionNo=2 → idx=1;正模避免负数
    assertThat(sql)
        .contains("((hashtext(base.\"id\"::text) % 4) + 4) % 4 = 1")
        .contains("WHERE");
  }

  @Test
  void shouldCombineShardAndCursor_whenMultiPartitionWithCursor() {
    String sql = SqlTemplateExportDataPlugin.buildPagedSql("SELECT * FROM t", "id", true, 4, 1);
    assertThat(sql).contains("hashtext").contains("AND base.\"id\" > :__cursor");
  }
}
```

- [ ] **Step 2: 运行,确认失败**

Run: `cd /Users/dengchao/Downloads/file-batch-system && mvn -q -pl batch-worker-export test -Dtest=SqlTemplateExportPartitionTest`
Expected: FAIL — `buildPagedSql` 现签名是 3 参,编译/方法不匹配

- [ ] **Step 3: 改 buildPagedSql 签名 + 叠加分片谓词**

```java
static String buildPagedSql(
    String baseSql, String cursorColumn, boolean hasCursor, int partitionCount, int partitionNo) {
  String cursorIdent = io.github.pinpols.batch.common.jdbc.JdbcMappedSqlValidator.quotePg(cursorColumn);
  StringBuilder where = new StringBuilder();
  if (partitionCount > 1) {
    where.append(
        "WHERE ((hashtext(base.%s::text) %% %d) + %d) %% %d = %d%n"
            .formatted(
                cursorIdent, partitionCount, partitionCount, partitionCount, partitionNo - 1));
  }
  if (hasCursor) {
    where
        .append(where.isEmpty() ? "WHERE " : "AND ")
        .append("base.%s > :__cursor%n".formatted(cursorIdent));
  }
  return """
  WITH base AS (
  %s
  )
  SELECT *
  FROM base
  %sORDER BY base.%s ASC
  LIMIT :__limit
  """
      .formatted(baseSql, where, cursorIdent);
}
```

- [ ] **Step 4: loadDetailPage 传入 partition(116 行)**

将
```java
String sql = buildPagedSql(baseSql, spec.cursorColumn(), cursor != null);
```
改为
```java
String sql =
    buildPagedSql(
        baseSql,
        spec.cursorColumn(),
        cursor != null,
        context.partitionCount(),
        context.partitionNo());
```

- [ ] **Step 5: 运行,确认通过**

Run: `cd /Users/dengchao/Downloads/file-batch-system && mvn -q -pl batch-worker-export test -Dtest=SqlTemplateExportPartitionTest`
Expected: PASS(3 tests)

- [ ] **Step 6: Commit**

```bash
git add batch-worker-export/src/main/java/com/example/batch/worker/exports/plugin/SqlTemplateExportDataPlugin.java batch-worker-export/src/test/java/com/example/batch/worker/exports/plugin/SqlTemplateExportPartitionTest.java
git commit -m "feat(export): sql_template 分页 SQL 叠加 hashtext 分片谓词"
```

---

## Task 4: JdbcMapped 插件分片谓词

**Files:**
- Test: `batch-worker-export/src/test/java/com/example/batch/worker/exports/plugin/GenericJdbcMappedExportPartitionTest.java`(新)
- Modify: `.../plugin/GenericJdbcMappedExportDataPlugin.java`(`loadDetailPage` SQL/args 构造,约 107-126 行)

- [ ] **Step 1: 抽出可测的 SQL 构造(重构,行为不变)**

把 `loadDetailPage` 中拼 SQL + args 的逻辑抽成包级静态方法,便于单测(保持现有运行路径调用它):

```java
/** 构造明细分页 SQL + 顺序参数。partitionCount>1 时叠加 hashtext 分片谓词。 */
static record PagedQuery(String sql, Object[] args) {}

static PagedQuery buildDetailQuery(
    String cols,
    String fq,
    String fk,
    String ob,
    Long batchId,
    Object cursor,
    int pageSize,
    int partitionCount,
    int partitionNo) {
  StringBuilder sql =
      new StringBuilder("SELECT ").append(cols).append(" FROM ").append(fq)
          .append(" WHERE ").append(fk).append(" = ?");
  java.util.List<Object> args = new java.util.ArrayList<>();
  args.add(batchId);
  if (partitionCount > 1) {
    sql.append(" AND ((hashtext(").append(ob).append("::text) % ?) + ?) % ? = ?");
    args.add(partitionCount);
    args.add(partitionCount);
    args.add(partitionCount);
    args.add(partitionNo - 1);
  }
  if (cursor != null) {
    sql.append(" AND ").append(ob).append(" > ?");
    args.add(cursor);
  }
  sql.append(" ORDER BY ").append(ob).append(" ASC LIMIT ?");
  args.add(pageSize);
  return new PagedQuery(sql.toString(), args.toArray());
}
```

`loadDetailPage` 原来手拼 SQL/args 的段(107-126 行)替换为:
```java
PagedQuery pq =
    buildDetailQuery(
        cols.toString(), fq, fk, ob, batchId, cursor, pageSize,
        context.partitionCount(), context.partitionNo());
final String finalSql = pq.sql();
final Object[] sqlArgs = pq.args();
```

- [ ] **Step 2: 写失败单测**

```java
package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.exports.plugin.GenericJdbcMappedExportDataPlugin.PagedQuery;
import org.junit.jupiter.api.Test;

class GenericJdbcMappedExportPartitionTest {

  @Test
  void shouldNotShard_whenSinglePartition() {
    PagedQuery pq =
        GenericJdbcMappedExportDataPlugin.buildDetailQuery(
            "c1,c2", "s.t", "\"fk\"", "\"id\"", 9L, null, 100, 1, 1);
    assertThat(pq.sql()).doesNotContain("hashtext");
    assertThat(pq.args()).containsExactly(9L, 100);
  }

  @Test
  void shouldShard_whenMultiPartition() {
    PagedQuery pq =
        GenericJdbcMappedExportDataPlugin.buildDetailQuery(
            "c1,c2", "s.t", "\"fk\"", "\"id\"", 9L, null, 100, 4, 3);
    assertThat(pq.sql()).contains("((hashtext(\"id\"::text) % ?) + ?) % ? = ?");
    // batchId, pc, pc, pc, idx(=2), pageSize
    assertThat(pq.args()).containsExactly(9L, 4, 4, 4, 2, 100);
  }
}
```

- [ ] **Step 3: 运行确认失败 → 已实现后通过**

Run: `cd /Users/dengchao/Downloads/file-batch-system && mvn -q -pl batch-worker-export test -Dtest=GenericJdbcMappedExportPartitionTest`
Expected: 先 FAIL(方法不存在),Step 1 实现后 PASS(2 tests)

- [ ] **Step 4: Commit**

```bash
git add batch-worker-export/src/main/java/com/example/batch/worker/exports/plugin/GenericJdbcMappedExportDataPlugin.java batch-worker-export/src/test/java/com/example/batch/worker/exports/plugin/GenericJdbcMappedExportPartitionTest.java
git commit -m "feat(export): jdbc_mapped 明细分页 SQL 叠加 hashtext 分片谓词"
```

---

## Task 5: 分片文件名后缀

**Files:**
- Test: `batch-common/src/test/java/com/example/batch/common/constants/BatchFileConstantsPartitionTagTest.java`(新)
- Modify: `batch-common/.../constants/BatchFileConstants.java`、`batch-worker-export/.../stage/PrepareStep.java`

- [ ] **Step 1: 写 insertPartitionTag 失败单测**

```java
package io.github.pinpols.batch.common.constants;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BatchFileConstantsPartitionTagTest {

  @Test
  void shouldInsertTagBeforeExtension() {
    assertThat(BatchFileConstants.insertPartitionTag("data.csv", 2, 4)).isEqualTo("data_p2of4.csv");
  }

  @Test
  void shouldInsertTagInPathFileName() {
    assertThat(BatchFileConstants.insertPartitionTag("outbound/a/b/data.csv", 1, 3))
        .isEqualTo("outbound/a/b/data_p1of3.csv");
  }

  @Test
  void shouldAppendTag_whenNoExtension() {
    assertThat(BatchFileConstants.insertPartitionTag("noext", 2, 4)).isEqualTo("noext_p2of4");
  }

  @Test
  void shouldReturnUnchanged_whenSinglePartition() {
    assertThat(BatchFileConstants.insertPartitionTag("data.csv", 1, 1)).isEqualTo("data.csv");
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /Users/dengchao/Downloads/file-batch-system && mvn -q -pl batch-common test -Dtest=BatchFileConstantsPartitionTagTest`
Expected: FAIL — 方法不存在

- [ ] **Step 3: 实现 insertPartitionTag**

在 `BatchFileConstants` 加:
```java
/** partitionCount>1 时在「扩展名之前」插入 _p{no}of{count} 分片标记;否则原样返回。 */
public static String insertPartitionTag(String name, int partitionNo, int partitionCount) {
  if (name == null || partitionCount <= 1) {
    return name;
  }
  String tag = "_p" + partitionNo + "of" + partitionCount;
  int dot = name.lastIndexOf('.');
  int slash = name.lastIndexOf('/');
  if (dot > slash) {
    return name.substring(0, dot) + tag + name.substring(dot);
  }
  return name + tag;
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd /Users/dengchao/Downloads/file-batch-system && mvn -q -pl batch-common test -Dtest=BatchFileConstantsPartitionTagTest`
Expected: PASS(4 tests)

- [ ] **Step 5: PrepareStep 应用后缀(约 91-95 行 + resolveObjectName 签名)**

读 partition,fileName 在调用处包后缀;`resolveObjectName` 内仅对 payload 显式分支补后缀(默认分支已用带后缀 fileName,避免重复):

主流程改:
```java
int partitionNo = intOrDefault(context.getAttributes().get(PipelineRuntimeKeys.PARTITION_NO), 1);
int partitionCount =
    intOrDefault(context.getAttributes().get(PipelineRuntimeKeys.PARTITION_COUNT), 1);
String fileName =
    BatchFileConstants.insertPartitionTag(
        resolveFileName(context, payload, templateConfig, fileFormatType, region),
        partitionNo,
        partitionCount);
String finalObjectName = resolveObjectName(context, payload, fileName, partitionNo, partitionCount);
String tempObjectName =
    BatchFileConstants.tempObjectName(context.getTenantId(), bizDate, fileName);
```
（`intOrDefault` 同 Task 2 加一个 private static 到 PrepareStep。）

`resolveObjectName` 改签名 + 仅显式分支补后缀:
```java
private String resolveObjectName(
    ExportJobContext context, ExportPayload payload, String fileName,
    int partitionNo, int partitionCount) {
  if (Texts.hasText(payload.objectName())) {
    return BatchFileConstants.insertPartitionTag(
        payload.objectName(), partitionNo, partitionCount);
  }
  String bizType = Texts.hasText(payload.bizType()) ? payload.bizType() : context.getJobCode();
  String bizDate = resolveBizDate(context, payload);
  return BatchFileConstants.outboundObjectName(
      bizType, bizDate, defaultText(payload.batchNo(), "batch"), "v1", fileName);
}
```

- [ ] **Step 6: 编译验证**

Run: `cd /Users/dengchao/Downloads/file-batch-system && mvn -q -pl batch-worker-export -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add batch-common/src/main/java/com/example/batch/common/constants/BatchFileConstants.java batch-common/src/test/java/com/example/batch/common/constants/BatchFileConstantsPartitionTagTest.java batch-worker-export/src/main/java/com/example/batch/worker/exports/stage/PrepareStep.java
git commit -m "feat(export): 分片导出文件名/objectName 加 _p{no}of{count} 后缀"
```

---

## Task 6: 分片完整性 IT(真实 PG hashtext)

**Files:**
- Test: `batch-worker-export/src/test/java/com/example/batch/worker/exports/plugin/ExportPartitionSliceIT.java`(新,继承 `AbstractIntegrationTest`)

- [ ] **Step 1: 写 IT —— sql_template 4 片无重叠 + 全覆盖**

要点(照 `AbstractIntegrationTest` 既有 Testcontainers PG 复用):造一张 M=1000 行表,对 `partitionCount=4`、`partitionNo=1..4` 各自反复 `loadDetailPage` 翻页到 `nextCursor==null`,收集每片主键集合,断言 4 片两两无交集且并集 = 全部 1000 行;再对 jdbc_mapped 模式重复同样断言。

```java
package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.plugin.ExportDataContext;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin.DetailPage;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExportPartitionSliceIT extends AbstractIntegrationTest {

  // 注入/构造被测 plugin 与测试表的细节,按本仓 AbstractIntegrationTest 既有约定补全
  // (Testcontainers PG 已由基类提供;建表 + 灌 1000 行 id 1..1000;模板配置 cursorColumn=id)。

  private Set<Object> collectPartition(
      ExportDataPlugin plugin, Map<String, Object> templateConfig, int partitionNo, int count) {
    Set<Object> ids = new HashSet<>();
    Object cursor = null;
    while (true) {
      ExportDataContext ctx =
          new ExportDataContext(
              "T1", "JOB", "B1", "TPL", templateConfig, Map.of(), partitionNo, count);
      DetailPage page = plugin.loadDetailPage(ctx, 1L, 200, cursor);
      if (page.rows().isEmpty()) {
        break;
      }
      page.rows().forEach(r -> ids.add(r.get("id")));
      cursor = page.nextCursor();
      if (cursor == null) {
        break;
      }
    }
    return ids;
  }

  @Test
  void sqlTemplate_4partitions_noOverlap_fullCoverage() {
    // arrange: 建表 biz.slice_demo(id) 灌 1..1000;templateConfig 指向它,cursorColumn=id
    // act
    Set<Object> all = new HashSet<>();
    Set<Object>[] parts = new Set[4];
    for (int p = 1; p <= 4; p++) {
      parts[p - 1] = collectPartition(sqlTemplatePlugin(), sqlTemplateConfig(), p, 4);
    }
    // assert: 两两无交集
    for (int i = 0; i < 4; i++) {
      for (int j = i + 1; j < 4; j++) {
        assertThat(parts[i]).doesNotContainAnyElementsOf(parts[j]);
      }
      all.addAll(parts[i]);
    }
    // 并集 = 1000
    assertThat(all).hasSize(1000);
  }

  @Test
  void jdbcMapped_4partitions_noOverlap_fullCoverage() {
    // 同上,换 jdbcMappedPlugin() + jdbcMappedConfig(),断言无重叠 + 并集 = 全集
  }
}
```

- [ ] **Step 2: 运行 IT**

Run: `cd /Users/dengchao/Downloads/file-batch-system && mvn -q -pl batch-worker-export verify -Dit.test=ExportPartitionSliceIT -DfailIfNoTests=false`
Expected: PASS — 两个用例均 4 片无重叠且并集 = 1000

- [ ] **Step 3: Commit**

```bash
git add batch-worker-export/src/test/java/com/example/batch/worker/exports/plugin/ExportPartitionSliceIT.java
git commit -m "test(export): 分片完整性 IT — 4 片无重叠 + 全覆盖(sql_template + jdbc_mapped)"
```

---

## Task 7: 全量验证 + 文档收尾

**Files:**
- Modify: `docs/backlog/export-partition-slice-fix-2026-06-06.md`(状态改「已实现」)
- Modify: `docs/changelog.md`(追加一行 bugfix,日期倒序)

- [ ] **Step 1: 全模块 clean 编译 + 测试**

> mvn 注意(本仓约定):`e2e-tests` 纯测试模块用 `verify` 不用 `install`;推前必须 `clean` 防 stale cache。

Run: `cd /Users/dengchao/Downloads/file-batch-system && mvn -q clean test -pl batch-common,batch-worker-export -am`
Expected: BUILD SUCCESS,新单测全部通过

- [ ] **Step 2: 更新设计文档状态**

把 `docs/backlog/export-partition-slice-fix-2026-06-06.md` 顶部状态由「已设计,待实现」改为「已实现(feature/export-partition-slice)」。

- [ ] **Step 3: changelog 追加一行**

在 `docs/changelog.md` 顶部按日期倒序加:
```
- 2026-06-06 fix(export): EXPORT 分片不切数据(N× 重复/覆盖)修复 — partition-aware hashtext 分片 + 分片文件名后缀
```

- [ ] **Step 4: Commit**

```bash
git add docs/backlog/export-partition-slice-fix-2026-06-06.md docs/changelog.md
git commit -m "docs(export): 分片修复落地,更新 backlog 状态 + changelog"
```

---

## Self-Review

**Spec coverage**:① partition 透传(Task 1-2)② sql_template 分片(Task 3)③ jdbc_mapped 分片(Task 4)④ N 个分片文件/文件名后缀(Task 5)⑤ 分片完整性验证(Task 6)⑥ 向后兼容 partitionCount==1(每个分片点都 `>1` 才生效,Task 3/4/5 单测覆盖单片路径)—— 设计各点均有对应 task。fail-fast guard 经评估去除(分片列恒存在),已在 File Structure 注明。

**Placeholder scan**:Task 6 IT 的建表/灌数/模板配置标注「按 AbstractIntegrationTest 既有约定补全」——这是因测试基建细节需执行时对照基类,非逻辑占位;分片断言逻辑(无重叠 + 并集 = 1000)已完整给出。其余步骤均有完整代码 + 精确命令。

**Type consistency**:`ExportDataContext` 8 参构造器(Task 1)↔ GenerateStep 8 参调用(Task 2)↔ IT 8 参构造(Task 6)一致;`buildPagedSql` 5 参(Task 3)、`buildDetailQuery`/`PagedQuery`(Task 4)、`insertPartitionTag`(Task 5)签名前后一致;分片谓词正模式 `((hashtext(..) % cnt) + cnt) % cnt = idx`、`idx = partitionNo-1` 在 sql_template / jdbc_mapped / 单测三处一致。
