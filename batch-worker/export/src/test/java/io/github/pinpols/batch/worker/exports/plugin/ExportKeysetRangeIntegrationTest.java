package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.plugin.ExportDataContext;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.exports.BatchWorkerExportApplication;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实 PostgreSQL 验证旧区间配置下统一哈希分片，覆盖两种插件、稀疏数据与多页读取。 */
@SpringBootTest(
    classes = BatchWorkerExportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("导出哈希分片集成测试:旧区间配置下验证真实库分片完整性")
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

  @Autowired
  SqlTemplateExportDataPlugin sqlTemplatePlugin;

  @Autowired
  GenericJdbcMappedExportDataPlugin jdbcMappedPlugin;

  @BeforeEach
  void setTenantContext() {
    RlsTenantContextHolder.set(TENANT_ID);
  }

  @AfterEach
  void clearTenantContext() {
    RlsTenantContextHolder.clear();
  }

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

      final String createIFSql = """
          CREATE TABLE IF NOT EXISTS biz.keyset_demo (
              id        BIGINT PRIMARY KEY,
              tenant_id VARCHAR(64)  NOT NULL,
              batch_no  VARCHAR(128) NOT NULL
          )
          """;
      stmt.execute(createIFSql);
      final String truncateBizKeysetDemoSql = """
          TRUNCATE TABLE biz.keyset_demo
      """;
      stmt.execute(truncateBizKeysetDemoSql);

      // 用例 1/3/4：连续 id=1..1000，batch_no = BATCH_NO
      StringBuilder bulk = new StringBuilder("""
          INSERT INTO biz.keyset_demo(id, tenant_id, batch_no) VALUES
          """);
      for (int i = 1; i <= ROW_COUNT; i++) {
        appendRow(bulk, i, BATCH_NO, i < ROW_COUNT);
      }
      String insertKeysetRowsSql = bulk.toString();
      stmt.execute(insertKeysetRowsSql);

      // 用例 2：倾斜数据——独立表，跳过 id ∈ [400,450]，实插 950 行
      final String createIFSqlValue1 = """
          CREATE TABLE IF NOT EXISTS biz.keyset_skew_demo (
              id        BIGINT PRIMARY KEY,
              tenant_id VARCHAR(64)  NOT NULL,
              batch_no  VARCHAR(128) NOT NULL
          )
          """;
      stmt.execute(createIFSqlValue1);
      final String truncateBizKeysetSkewDemoSql = """
          TRUNCATE TABLE biz.keyset_skew_demo
      """;
      stmt.execute(truncateBizKeysetSkewDemoSql);
      StringBuilder skew = new StringBuilder("""
          INSERT INTO biz.keyset_skew_demo(id, tenant_id, batch_no) VALUES
          """);
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
      String insertSkewedKeysetRowsSql = skew.toString();
      stmt.execute(insertSkewedKeysetRowsSql);

      // jdbc_mapped 变体：复用 settlement_batch + settlement_detail（id 数值列）
      final String insertBizSettlementBatchSql =
          """
          INSERT INTO biz.settlement_batch
              (id, tenant_id, batch_no, biz_date, accounting_period)
          VALUES
              (%d, '%s', '%s', '2024-01-01', '2024-01')
          ON CONFLICT DO NOTHING
          """.formatted(BATCH_ID_SEED, TENANT_ID, "KEYSET-JDBC-MAPPED");
      stmt.execute(insertBizSettlementBatchSql);
      final String deleteBizSettlementDetailSql = """
          DELETE FROM biz.settlement_detail WHERE batch_id = %d
          """.formatted(BATCH_ID_SEED);
      stmt.execute(deleteBizSettlementDetailSql);
      StringBuilder detail = new StringBuilder("""
          INSERT INTO biz.settlement_detail
              (tenant_id, batch_id, settlement_no, customer_no, biz_date, accounting_period,
               gross_amount, fee_amount, net_amount) VALUES
          """);
      for (int i = 1; i <= ROW_COUNT; i++) {
        detail.append("('%s',%d,'KS-SNO-%d','CUST-%d','2024-01-01','2024-01',0,0,0)"
            .formatted(TENANT_ID, BATCH_ID_SEED, i, i));
        if (i < ROW_COUNT) {
          detail.append(",");
        }
      }
      String insertSettlementDetailsSql = detail.toString();
      stmt.execute(insertSettlementDetailsSql);

      conn.commit();
    }
  }

  private static void appendRow(StringBuilder sb, int id, String batchNo, boolean trailingComma) {
    sb.append("(%d,'%s','%s')".formatted(id, TENANT_ID, batchNo));
    if (trailingComma) {
      sb.append(",");
    }
  }

  // ----------------------------------------------------------------
  //  用例 1：旧区间配置下，4 片无重叠 + 全覆盖
  // ----------------------------------------------------------------

  @Test
  @DisplayName("旧区间配置下 sql_template 哈希分片无重漏")
  void sqlTemplate_keyset_fourPartitions_disjointAndComplete() throws Exception {
    // 准备: 开启旧区间配置，运行时仍使用哈希。
    Map<String, Object> templateConfig = Map.of(
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
  @DisplayName("旧区间配置下稀疏数据的哈希分片无重漏")
  void sqlTemplate_keyset_skewed_disjointAndComplete() throws Exception {
    // 准备: 同表，batch_no = SKEW（id 有空洞 [400,450]）
    Map<String, Object> templateConfig = Map.of(
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
    Map<String, Object> templateConfig = Map.of(
        "default_query_sql",
        "SELECT id FROM biz.keyset_demo WHERE tenant_id = :tenantId AND batch_no = :batchNo");

    // 执行
    List<Set<Object>> partitions =
        collectAllPartitions(sqlTemplatePlugin, templateConfig, 0L, BATCH_NO);

    // 断言: hashtext 路径正确性不变（无重无漏）
    assertDisjointAndComplete(partitions, "sql_template+hashtext", ROW_COUNT);
  }

  // ----------------------------------------------------------------
  //  用例 4：旧区间配置下多页读取不再探测边界
  // ----------------------------------------------------------------

  @Test
  @DisplayName("旧区间配置启用时多页仍走哈希，不写入区间缓存")
  void shouldUseHashAcrossPages_whenRangeOptionEnabled() {
    // 准备: partitionNo=1，pageSize 小到保证多页（1000 行 / 4 片 ≈ 250 行，PAGE_SIZE=200 → ≥2 页）
    Map<String, Object> templateConfig = Map.of(
        "default_query_sql",
        "SELECT id FROM biz.keyset_demo WHERE tenant_id = :tenantId AND batch_no = :batchNo",
        "partition_keyset_range",
        true);

    // 单个 exportSnapshot 跨页共享，不应出现区间缓存
    Map<String, Object> sharedSnapshot = new LinkedHashMap<>();
    Object cursor = null;
    int pages = 0;

    while (true) {
      ExportDataContext ctx = new ExportDataContext(
          TENANT_ID, "JOB", BATCH_NO, "TPL", templateConfig, sharedSnapshot, 1, PARTITION_COUNT);
      ExportDataPlugin.DetailPage page =
          sqlTemplatePlugin.loadDetailPage(ctx, 0L, PAGE_SIZE, cursor);

      assertThat(sharedSnapshot).doesNotContainKey(ExportKeysetRangePlanner.SNAP_KEY);

      pages++;
      if (page.rows().isEmpty()) {
        break;
      }
      cursor = page.nextCursor();
      if (cursor == null) {
        break;
      }
    }

    assertThat(pages).as("分片1 应翻多页以验证哈希分页").isGreaterThanOrEqualTo(2);
  }

  // ----------------------------------------------------------------
  //  补充：jdbc_mapped 插件启用旧区间配置时的哈希分片
  // ----------------------------------------------------------------

  @Test
  @DisplayName("旧区间配置下 jdbc_mapped 哈希分片无重漏")
  void jdbcMapped_keyset_fourPartitions_disjointAndComplete() throws Exception {
    Map<String, Object> jdbcMappedSpec = Map.of(
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

  /** 对 1..PARTITION_COUNT 的每个分片分页收集所有 id。每分片用独立可变 exportSnapshot，用于核对没有写入区间边界。 */
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
    // 可变 snapshot：跨页共享，不应写入旧区间边界。
    Map<String, Object> snapshot = new LinkedHashMap<>();

    while (true) {
      ExportDataContext ctx = new ExportDataContext(
          TENANT_ID, "JOB", batchNo, "TPL", templateConfig, snapshot, partitionNo, PARTITION_COUNT);

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
