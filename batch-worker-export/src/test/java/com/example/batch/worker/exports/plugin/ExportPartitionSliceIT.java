package com.example.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.exports.BatchWorkerExportApplication;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
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
 * 分片完整性集成测试：验证 EXPORT 分片逻辑在真实 PostgreSQL 上满足 <b>4 片无重叠 + 全集覆盖</b>两个不变量。
 *
 * <p>依赖 {@code hashtext()} 函数——该函数为 Postgres 内置，无法用单测替代，必须跑真实 PG（Testcontainers）。
 *
 * <p>覆盖两种插件：
 *
 * <ol>
 *   <li>{@link SqlTemplateExportDataPlugin}（sql_template 模式）
 *   <li>{@link GenericJdbcMappedExportDataPlugin}（jdbc_mapped 模式）
 * </ol>
 *
 * <p>测试数据：在 {@code biz.slice_demo}（本测试自建）插入 id=1..1000， 对 jdbc_mapped 模式同时在 {@code
 * biz.settlement_batch} 和 {@code biz.settlement_detail} 插入 1000 条明细。
 */
@SpringBootTest(
    classes = BatchWorkerExportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ExportPartitionSliceIT extends AbstractIntegrationTest {

  private static final int ROW_COUNT = 1000;
  private static final int PARTITION_COUNT = 4;
  private static final int PAGE_SIZE = 200;
  private static final String TENANT_ID = "test-tenant";
  private static final String BATCH_NO = "SLICE-IT-BATCH";
  private static final long BATCH_ID_SEED = 88_000L;

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
    // 通过 AbstractIntegrationTest 提供的 business JDBC URL 直接连接
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setURL(businessJdbcUrl());
    ds.setUser("batch_user");
    ds.setPassword("batch_pass_123");

    try (Connection conn = ds.getConnection();
        Statement stmt = conn.createStatement()) {
      conn.setAutoCommit(false);

      // --- sql_template 测试表 ---
      stmt.execute(
          """
          CREATE TABLE IF NOT EXISTS biz.slice_demo (
              id        BIGINT PRIMARY KEY,
              tenant_id VARCHAR(64)  NOT NULL,
              batch_no  VARCHAR(128) NOT NULL
          )
          """);
      stmt.execute("TRUNCATE TABLE biz.slice_demo");

      StringBuilder bulk =
          new StringBuilder("INSERT INTO biz.slice_demo(id, tenant_id, batch_no) VALUES ");
      for (int i = 1; i <= ROW_COUNT; i++) {
        bulk.append("(")
            .append(i)
            .append(",'")
            .append(TENANT_ID)
            .append("','")
            .append(BATCH_NO)
            .append("')");
        if (i < ROW_COUNT) {
          bulk.append(",");
        }
      }
      stmt.execute(bulk.toString());

      // --- jdbc_mapped 测试：biz.settlement_batch + biz.settlement_detail ---
      // 插入一条 batch（id = BATCH_ID_SEED 确保不与其他 IT 冲突）
      stmt.execute(
          """
          INSERT INTO biz.settlement_batch
              (id, tenant_id, batch_no, biz_date, accounting_period)
          VALUES
              (%d, '%s', '%s', '2024-01-01', '2024-01')
          ON CONFLICT DO NOTHING
          """
              .formatted(BATCH_ID_SEED, TENANT_ID, "JDBC-MAPPED-BATCH"));

      // 清理旧明细（幂等）
      stmt.execute("DELETE FROM biz.settlement_detail WHERE batch_id = " + BATCH_ID_SEED);

      // 批量插入 1000 条明细
      StringBuilder detailBulk =
          new StringBuilder(
              "INSERT INTO biz.settlement_detail"
                  + "(tenant_id, batch_id, settlement_no, customer_no, biz_date, accounting_period,"
                  + " gross_amount, fee_amount, net_amount) VALUES ");
      for (int i = 1; i <= ROW_COUNT; i++) {
        detailBulk
            .append("('")
            .append(TENANT_ID)
            .append("',")
            .append(BATCH_ID_SEED)
            .append(",'SNO-")
            .append(i)
            .append("','CUST-")
            .append(i)
            .append("','2024-01-01','2024-01',0,0,0)");
        if (i < ROW_COUNT) {
          detailBulk.append(",");
        }
      }
      stmt.execute(detailBulk.toString());

      conn.commit();
    }
  }

  // ----------------------------------------------------------------
  //  sql_template 模式分片测试
  // ----------------------------------------------------------------

  @Test
  @DisplayName("sql_template: 4 片无重叠且合集恰好等于全量 1000 行")
  void sqlTemplate_fourPartitions_disjointAndComplete() throws Exception {
    // arrange: templateConfig — SELECT id FROM biz.slice_demo WHERE tenant_id=:tenantId AND
    // batch_no=:batchNo
    Map<String, Object> templateConfig =
        Map.of(
            "default_query_sql",
            "SELECT id FROM biz.slice_demo WHERE tenant_id = :tenantId AND batch_no = :batchNo");

    // act: 收集各片 id 集合
    List<Set<Object>> partitions =
        collectAllPartitions(sqlTemplatePlugin, templateConfig, 0L /* batchId ignored */);

    // assert: 不重叠 + 全覆盖
    assertDisjointAndComplete(partitions, "sql_template");
  }

  // ----------------------------------------------------------------
  //  jdbc_mapped 模式分片测试
  // ----------------------------------------------------------------

  @Test
  @DisplayName("jdbc_mapped: 4 片无重叠且合集恰好等于全量 1000 行")
  void jdbcMapped_fourPartitions_disjointAndComplete() throws Exception {
    // arrange: templateConfig — biz.settlement_batch + biz.settlement_detail
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

    Map<String, Object> templateConfig = Map.of("jdbc_mapped_export", jdbcMappedSpec);

    // act: 收集各片 id 集合（batchId = BATCH_ID_SEED）
    List<Set<Object>> partitions =
        collectAllPartitions(jdbcMappedPlugin, templateConfig, BATCH_ID_SEED);

    // assert: 不重叠 + 全覆盖
    assertDisjointAndComplete(partitions, "jdbc_mapped");
  }

  // ----------------------------------------------------------------
  //  辅助方法
  // ----------------------------------------------------------------

  /** 对 1..PARTITION_COUNT 的每个分片调用 {@code loadDetailPage} 分页，收集所有 id。 */
  private List<Set<Object>> collectAllPartitions(
      ExportDataPlugin plugin, Map<String, Object> templateConfig, long batchId) throws Exception {
    java.util.List<Set<Object>> result = new java.util.ArrayList<>();
    for (int partNo = 1; partNo <= PARTITION_COUNT; partNo++) {
      result.add(collectPartition(plugin, templateConfig, batchId, partNo));
    }
    return result;
  }

  /** 对单个分片分页收集所有行的 {@code id} 值。 */
  private Set<Object> collectPartition(
      ExportDataPlugin plugin, Map<String, Object> templateConfig, long batchId, int partitionNo)
      throws Exception {
    Set<Object> ids = new HashSet<>();
    Object cursor = null;

    while (true) {
      ExportDataContext ctx =
          new ExportDataContext(
              TENANT_ID,
              "JOB",
              BATCH_NO,
              "TPL",
              templateConfig,
              Map.of(),
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

  /** 核心断言：4 片两两不相交，合集大小 == ROW_COUNT。 */
  private void assertDisjointAndComplete(List<Set<Object>> partitions, String mode) {
    // 1. 验证两两不相交
    for (int i = 0; i < partitions.size(); i++) {
      for (int j = i + 1; j < partitions.size(); j++) {
        Set<Object> intersection = new HashSet<>(partitions.get(i));
        intersection.retainAll(partitions.get(j));
        assertThat(intersection)
            .as("[%s] 分片 %d 与分片 %d 存在重叠 id: %s", mode, i + 1, j + 1, intersection)
            .isEmpty();
      }
    }

    // 2. 验证合集大小 == ROW_COUNT
    Set<Object> union = new HashSet<>();
    for (Set<Object> p : partitions) {
      union.addAll(p);
    }
    assertThat(union)
        .as("[%s] 4 片合集大小应为 %d，实际为 %d", mode, ROW_COUNT, union.size())
        .hasSize(ROW_COUNT);
  }
}
