package com.example.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.exports.BatchWorkerExportApplication;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 导出分片 keyset 区间优化的端到端集成测试：连真实 PostgreSQL（Testcontainers）验证 keyset-range 分片语义。
 *
 * <p>镜像 {@link ExportPartitionSliceIT}（hashtext 分片的同款 harness），差异仅在 templateConfig 里加 {@code
 * partition_keyset_range = true} 激活 keyset 路径，并补充 keyset 专有断言（边界探测只算一次）。
 *
 * <p>被测行为：{@code partitionCount>1} + opt-in + 数值游标列 min/max 可取 → 每分片谓词走 {@code cur >= loN AND cur
 * <(=) hiN}（等宽，末片含上界）；否则退回 hashtext。
 *
 * <p>覆盖用例：
 *
 * <ol>
 *   <li>4 片无重叠 + 全覆盖（opt-in keyset，1..1000）；
 *   <li>倾斜（造空洞，实插 950 行）仍无重无漏；
 *   <li>未 opt-in 退回 hashtext 仍正确；
 *   <li>边界只算一次/分片（多页复用 exportSnapshot 缓存键 {@code __export_keyset_range}）。
 * </ol>
 *
 * <p>主覆盖 {@link SqlTemplateExportDataPlugin}（与 ExportPartitionSliceIT 同款、游标列 = default_query_sql 的
 * cursorColumn）。另对 {@link GenericJdbcMappedExportDataPlugin}（游标列 = detailOrderByColumn 物理表）补一个
 * keyset 变体，确保两个 plugin 都覆盖。
 */
@SpringBootTest(
    classes = BatchWorkerExportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ExportKeysetRangeIT extends AbstractIntegrationTest {

  private static final int ROW_COUNT = 1000;
  private static final int SKEW_GAP_LO = 400; // 空洞区间 [400,450] 不插，制造倾斜
  private static final int SKEW_GAP_HI = 450;
  private static final int SKEW_ROW_COUNT = ROW_COUNT - (SKEW_GAP_HI - SKEW_GAP_LO + 1); // 950
  private static final int PARTITION_COUNT = 4;
  private static final int PAGE_SIZE = 200;
  private static final String TENANT_ID = "test-tenant";
  private static final String BATCH_NO = "KEYSET-IT-BATCH";
  private static final String SKEW_BATCH_NO = "KEYSET-IT-SKEW";
  private static final long BATCH_ID_SEED = 89_000L;

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired SqlTemplateExportDataPlugin sqlTemplatePlugin;

  @Autowired GenericJdbcMappedExportDataPlugin jdbcMappedPlugin;

  // ----------------------------------------------------------------
  //  Test-data bootstrap（执行一次，所有方法共享）
  // ----------------------------------------------------------------

  @BeforeAll
  static void setupTestData() throws Exception {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setURL(businessJdbcUrl());
    ds.setUser("batch_user");
    ds.setPassword("batch_pass_123");

    try (Connection conn = ds.getConnection();
        Statement stmt = conn.createStatement()) {
      conn.setAutoCommit(false);

      stmt.execute(
          """
          CREATE TABLE IF NOT EXISTS biz.keyset_demo (
              id        BIGINT PRIMARY KEY,
              tenant_id VARCHAR(64)  NOT NULL,
              batch_no  VARCHAR(128) NOT NULL
          )
          """);
      stmt.execute("TRUNCATE TABLE biz.keyset_demo");

      // 用例 1/3/4：连续 id=1..1000，batch_no = BATCH_NO
      StringBuilder bulk =
          new StringBuilder("INSERT INTO biz.keyset_demo(id, tenant_id, batch_no) VALUES ");
      for (int i = 1; i <= ROW_COUNT; i++) {
        appendRow(bulk, i, BATCH_NO, i < ROW_COUNT);
      }
      stmt.execute(bulk.toString());

      // 用例 2：倾斜数据——独立表，跳过 id ∈ [400,450]，实插 950 行
      stmt.execute(
          """
          CREATE TABLE IF NOT EXISTS biz.keyset_skew_demo (
              id        BIGINT PRIMARY KEY,
              tenant_id VARCHAR(64)  NOT NULL,
              batch_no  VARCHAR(128) NOT NULL
          )
          """);
      stmt.execute("TRUNCATE TABLE biz.keyset_skew_demo");
      StringBuilder skew =
          new StringBuilder("INSERT INTO biz.keyset_skew_demo(id, tenant_id, batch_no) VALUES ");
      boolean first = true;
      for (int i = 1; i <= ROW_COUNT; i++) {
        if (i >= SKEW_GAP_LO && i <= SKEW_GAP_HI) {
          continue;
        }
        if (!first) {
          skew.append(",");
        }
        appendRow(skew, i, SKEW_BATCH_NO, false);
        first = false;
      }
      stmt.execute(skew.toString());

      // jdbc_mapped keyset 变体：复用 settlement_batch + settlement_detail（id 数值列）
      stmt.execute(
          """
          INSERT INTO biz.settlement_batch
              (id, tenant_id, batch_no, biz_date, accounting_period)
          VALUES
              (%d, '%s', '%s', '2024-01-01', '2024-01')
          ON CONFLICT DO NOTHING
          """
              .formatted(BATCH_ID_SEED, TENANT_ID, "KEYSET-JDBC-MAPPED"));
      stmt.execute("DELETE FROM biz.settlement_detail WHERE batch_id = " + BATCH_ID_SEED);
      StringBuilder detail =
          new StringBuilder(
              "INSERT INTO biz.settlement_detail"
                  + "(tenant_id, batch_id, settlement_no, customer_no, biz_date, accounting_period,"
                  + " gross_amount, fee_amount, net_amount) VALUES ");
      for (int i = 1; i <= ROW_COUNT; i++) {
        detail
            .append("('")
            .append(TENANT_ID)
            .append("',")
            .append(BATCH_ID_SEED)
            .append(",'KS-SNO-")
            .append(i)
            .append("','CUST-")
            .append(i)
            .append("','2024-01-01','2024-01',0,0,0)");
        if (i < ROW_COUNT) {
          detail.append(",");
        }
      }
      stmt.execute(detail.toString());

      conn.commit();
    }
  }

  private static void appendRow(StringBuilder sb, int id, String batchNo, boolean trailingComma) {
    sb.append("(")
        .append(id)
        .append(",'")
        .append(TENANT_ID)
        .append("','")
        .append(batchNo)
        .append("')");
    if (trailingComma) {
      sb.append(",");
    }
  }

  // ----------------------------------------------------------------
  //  用例 1：opt-in keyset，4 片无重叠 + 全覆盖
  // ----------------------------------------------------------------

  @Test
  @DisplayName("用例1 sql_template+keyset: 4 片无重叠且合集恰好等于全量 1000 行")
  void sqlTemplate_keyset_fourPartitions_disjointAndComplete() throws Exception {
    // 准备: opt-in keyset
    Map<String, Object> templateConfig =
        Map.of(
            "default_query_sql",
            "SELECT id FROM biz.keyset_demo WHERE tenant_id = :tenantId AND batch_no = :batchNo",
            "partition_keyset_range",
            true);

    // 执行
    List<Set<Object>> partitions =
        collectAllPartitions(sqlTemplatePlugin, templateConfig, 0L, BATCH_NO);

    // 断言
    assertDisjointAndComplete(partitions, "sql_template+keyset", ROW_COUNT);
  }

  // ----------------------------------------------------------------
  //  用例 2：倾斜（空洞）仍无重无漏
  // ----------------------------------------------------------------

  @Test
  @DisplayName("用例2 sql_template+keyset: 倾斜空洞下 4 片仍无重叠且合集等于实插 950 行")
  void sqlTemplate_keyset_skewed_disjointAndComplete() throws Exception {
    // 准备: 同表，batch_no = SKEW（id 有空洞 [400,450]）
    Map<String, Object> templateConfig =
        Map.of(
            "default_query_sql",
            "SELECT id FROM biz.keyset_skew_demo WHERE tenant_id = :tenantId AND batch_no ="
                + " :batchNo",
            "partition_keyset_range",
            true);

    // 执行
    List<Set<Object>> partitions =
        collectAllPartitions(sqlTemplatePlugin, templateConfig, 0L, SKEW_BATCH_NO);

    // 断言: 允许各片 size 不均，但无重无漏
    assertDisjointAndComplete(partitions, "sql_template+keyset+skew", SKEW_ROW_COUNT);
  }

  // ----------------------------------------------------------------
  //  用例 3：未 opt-in 退回 hashtext 仍正确
  // ----------------------------------------------------------------

  @Test
  @DisplayName("用例3 sql_template 未 opt-in: 退回 hashtext 路径，4 片合集仍等于全量 1000 行")
  void sqlTemplate_noOptIn_fallbackHashtext_complete() throws Exception {
    // 准备: 不设 partition_keyset_range（走 hashtext 分片）
    Map<String, Object> templateConfig =
        Map.of(
            "default_query_sql",
            "SELECT id FROM biz.keyset_demo WHERE tenant_id = :tenantId AND batch_no = :batchNo");

    // 执行
    List<Set<Object>> partitions =
        collectAllPartitions(sqlTemplatePlugin, templateConfig, 0L, BATCH_NO);

    // 断言: hashtext 路径正确性不变（无重无漏）
    assertDisjointAndComplete(partitions, "sql_template+hashtext", ROW_COUNT);
  }

  // ----------------------------------------------------------------
  //  用例 4：边界探测只算一次/分片（多页复用 exportSnapshot 缓存）
  // ----------------------------------------------------------------

  @Test
  @DisplayName("用例4 sql_template+keyset: 单分片翻多页时 min/max 边界缓存进 exportSnapshot 且跨页稳定")
  void sqlTemplate_keyset_boundaryComputedOncePerPartition() throws Exception {
    // 准备: partitionNo=1，pageSize 小到保证多页（1000 行 / 4 片 ≈ 250 行，PAGE_SIZE=200 → ≥2 页）
    Map<String, Object> templateConfig =
        Map.of(
            "default_query_sql",
            "SELECT id FROM biz.keyset_demo WHERE tenant_id = :tenantId AND batch_no = :batchNo",
            "partition_keyset_range",
            true);

    // 单个 exportSnapshot 跨页共享（plugin 内 planner 把结果缓存进它）
    Map<String, Object> sharedSnapshot = new LinkedHashMap<>();
    Object cursor = null;
    int pages = 0;
    ExportKeysetRange firstCached = null;

    while (true) {
      ExportDataContext ctx =
          new ExportDataContext(
              TENANT_ID,
              "JOB",
              BATCH_NO,
              "TPL",
              templateConfig,
              sharedSnapshot,
              1,
              PARTITION_COUNT);
      ExportDataPlugin.DetailPage page =
          sqlTemplatePlugin.loadDetailPage(ctx, 0L, PAGE_SIZE, cursor);

      // 每页调用后，缓存键必须已写入且 value 为 active keyset
      Object cached = sharedSnapshot.get(ExportKeysetRangePlanner.SNAP_KEY);
      assertThat(cached)
          .as("第 %d 页后 exportSnapshot 应含缓存键 %s", pages + 1, ExportKeysetRangePlanner.SNAP_KEY)
          .isInstanceOf(ExportKeysetRange.class);
      ExportKeysetRange range = (ExportKeysetRange) cached;
      assertThat(range.active()).as("keyset 应已激活").isTrue();
      if (firstCached == null) {
        firstCached = range;
      } else {
        // 跨页 value 稳定：同一 [loN, hiN) 不被重算覆盖
        assertThat(range).as("跨页缓存的 keyset 区间应稳定不变（只算一次）").isEqualTo(firstCached);
      }

      pages++;
      if (page.rows().isEmpty()) {
        break;
      }
      cursor = page.nextCursor();
      if (cursor == null) {
        break;
      }
    }

    assertThat(pages).as("分片1 应翻多页以验证跨页复用").isGreaterThanOrEqualTo(2);
    assertThat(firstCached).isNotNull();
  }

  // ----------------------------------------------------------------
  //  补充：jdbc_mapped plugin 的 keyset 变体（两个 plugin 都覆盖）
  // ----------------------------------------------------------------

  @Test
  @DisplayName("补充 jdbc_mapped+keyset: 4 片无重叠且合集等于全量 1000 行")
  void jdbcMapped_keyset_fourPartitions_disjointAndComplete() throws Exception {
    Map<String, Object> jdbcMappedSpec =
        Map.of(
            "schema", "biz",
            "batchTable", "settlement_batch",
            "batchTenantColumn", "tenant_id",
            "batchNoColumn", "batch_no",
            "batchSelectColumns", List.of("id", "batch_no", "tenant_id"),
            "detailTable", "settlement_detail",
            "detailFkColumn", "batch_id",
            "detailOrderByColumn", "id",
            "detailSelectColumns", List.of("id", "batch_id", "customer_no"));

    Map<String, Object> templateConfig =
        Map.of("jdbc_mapped_export", jdbcMappedSpec, "partition_keyset_range", true);

    List<Set<Object>> partitions =
        collectAllPartitions(jdbcMappedPlugin, templateConfig, BATCH_ID_SEED, BATCH_NO);

    assertDisjointAndComplete(partitions, "jdbc_mapped+keyset", ROW_COUNT);
  }

  // ----------------------------------------------------------------
  //  辅助方法
  // ----------------------------------------------------------------

  /** 对 1..PARTITION_COUNT 的每个分片分页收集所有 id。每分片用独立可变 exportSnapshot（planner 缓存边界用）。 */
  private List<Set<Object>> collectAllPartitions(
      ExportDataPlugin plugin, Map<String, Object> templateConfig, long batchId, String batchNo)
      throws Exception {
    List<Set<Object>> result = new ArrayList<>();
    for (int partNo = 1; partNo <= PARTITION_COUNT; partNo++) {
      result.add(collectPartition(plugin, templateConfig, batchId, batchNo, partNo));
    }
    return result;
  }

  private Set<Object> collectPartition(
      ExportDataPlugin plugin,
      Map<String, Object> templateConfig,
      long batchId,
      String batchNo,
      int partitionNo)
      throws Exception {
    Set<Object> ids = new HashSet<>();
    Object cursor = null;
    // 可变 snapshot：planner 把 keyset 边界缓存进来，跨页复用
    Map<String, Object> snapshot = new LinkedHashMap<>();

    while (true) {
      ExportDataContext ctx =
          new ExportDataContext(
              TENANT_ID,
              "JOB",
              batchNo,
              "TPL",
              templateConfig,
              snapshot,
              partitionNo,
              PARTITION_COUNT);

      ExportDataPlugin.DetailPage page = plugin.loadDetailPage(ctx, batchId, PAGE_SIZE, cursor);

      if (page.rows().isEmpty()) {
        break;
      }
      for (Map<String, Object> row : page.rows()) {
        ids.add(row.get("id"));
      }
      cursor = page.nextCursor();
      if (cursor == null) {
        break;
      }
    }
    return ids;
  }

  /** 核心断言：各片两两不相交（Collections.disjoint），合集大小 == expectedTotal。 */
  private void assertDisjointAndComplete(
      List<Set<Object>> partitions, String mode, int expectedTotal) {
    for (int i = 0; i < partitions.size(); i++) {
      for (int j = i + 1; j < partitions.size(); j++) {
        assertThat(Collections.disjoint(partitions.get(i), partitions.get(j)))
            .as("[%s] 分片 %d 与分片 %d 存在重叠 id", mode, i + 1, j + 1)
            .isTrue();
      }
    }

    Set<Object> union = new HashSet<>();
    for (Set<Object> p : partitions) {
      union.addAll(p);
    }
    assertThat(union)
        .as("[%s] %d 片合集大小应为 %d，实际为 %d", mode, PARTITION_COUNT, expectedTotal, union.size())
        .hasSize(expectedTotal);
  }
}
