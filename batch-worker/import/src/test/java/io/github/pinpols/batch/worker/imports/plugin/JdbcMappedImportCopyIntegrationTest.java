package io.github.pinpols.batch.worker.imports.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.WorkerConfigException;
import io.github.pinpols.batch.common.plugin.ImportLoadContext;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.rls.RlsTenantSessionSupport;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.imports.config.JdbcMappedImportSecurityProperties;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("JDBC 映射导入拷贝集成测试:分区替换,阶段交换,批量更新与行级安全语义")
class JdbcMappedImportCopyIntegrationTest {

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.business();

  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private GenericJdbcMappedImportLoadPlugin plugin;

  @BeforeEach
  void setUp() {
    RlsTenantContextHolder.set("t1");
    dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    JdbcMappedImportSecurityProperties security = new JdbcMappedImportSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    plugin = new GenericJdbcMappedImportLoadPlugin(dataSource, new ObjectMapper(), security);

    final String dropSql = """
        DROP SCHEMA IF EXISTS biz CASCADE
    """;
    jdbcTemplate.execute(dropSql);
    final String createSql = """
        CREATE SCHEMA biz
    """;
    jdbcTemplate.execute(createSql);
    final String createBizCopyImportCustomerSql = """
        CREATE TABLE biz.copy_import_customer (
          id BIGSERIAL NOT NULL UNIQUE,
          tenant_id text NOT NULL,
          biz_date date NOT NULL,
          customer_no text NOT NULL,
          customer_name text,
          amount numeric(12,2),
          note text,
          PRIMARY KEY (tenant_id, biz_date, customer_no)
        )
        """;
    jdbcTemplate.execute(createBizCopyImportCustomerSql);
    final String alterBizCopyImportCustomerSql = """
        ALTER TABLE biz.copy_import_customer ENABLE ROW LEVEL SECURITY
    """;
    jdbcTemplate.execute(alterBizCopyImportCustomerSql);
    final String createBizCopyImportCustomerPartSql = """
        CREATE TABLE biz.copy_import_customer_part (
          tenant_id text NOT NULL,
          biz_date date NOT NULL,
          customer_no text NOT NULL,
          customer_name text,
          amount numeric(12,2),
          note text,
          PRIMARY KEY (tenant_id, biz_date, customer_no)
        ) PARTITION BY RANGE (biz_date)
        """;
    jdbcTemplate.execute(createBizCopyImportCustomerPartSql);
    final String createBizCopyImportCustomerPart20260607Sql = """
        CREATE TABLE biz.copy_import_customer_part_20260607
        PARTITION OF biz.copy_import_customer_part
        FOR VALUES FROM ('2026-06-07') TO ('2026-06-08')
        """;
    jdbcTemplate.execute(createBizCopyImportCustomerPart20260607Sql);
    final String createBizBatchUpsertTransactionSql = """
        CREATE TABLE biz.batch_upsert_transaction (
          tenant_id text NOT NULL,
          txn_no text NOT NULL,
          amount numeric(20,2) NOT NULL,
          txn_date date NOT NULL,
          remark text,
          PRIMARY KEY (tenant_id, txn_no, txn_date)
        )
        """;
    jdbcTemplate.execute(createBizBatchUpsertTransactionSql);
    final String insertBizCopyImportCustomerSql = """
        INSERT INTO biz.copy_import_customer (tenant_id,biz_date,customer_no,customer_name,amount,note)\s\
        VALUES (?,?,?,?,?,?)\
        """;
    jdbcTemplate.update(
        insertBizCopyImportCustomerSql,
        "t1",
        Date.valueOf("2026-06-07"),
        "old-target",
        "old target",
        new BigDecimal("1.00"),
        "delete me");
    final String insertBizCopyImportCustomerSqlValue1 = """
        INSERT INTO biz.copy_import_customer (tenant_id,biz_date,customer_no,customer_name,amount,note)\s\
        VALUES (?,?,?,?,?,?)\
        """;
    jdbcTemplate.update(
        insertBizCopyImportCustomerSqlValue1,
        "t1",
        Date.valueOf("2026-06-06"),
        "keep-date",
        "keep other date",
        new BigDecimal("2.00"),
        "keep");
    final String insertBizCopyImportCustomerSqlValue2 = """
        INSERT INTO biz.copy_import_customer (tenant_id,biz_date,customer_no,customer_name,amount,note)\s\
        VALUES (?,?,?,?,?,?)\
        """;
    jdbcTemplate.update(
        insertBizCopyImportCustomerSqlValue2,
        "t2",
        Date.valueOf("2026-06-07"),
        "keep-tenant",
        "keep other tenant",
        new BigDecimal("3.00"),
        "keep");
    final String insertBizCopyImportCustomerPartSql = """
        INSERT INTO biz.copy_import_customer_part VALUES (?,?,?,?,?,?)
    """;
    jdbcTemplate.update(
        insertBizCopyImportCustomerPartSql,
        "t1",
        Date.valueOf("2026-06-07"),
        "old-target",
        "old target",
        new BigDecimal("1.00"),
        "delete me");
  }

  @AfterEach
  void clearTenantContext() {
    RlsTenantContextHolder.clear();
  }

  @Test
  @DisplayName("分区替换只清目标租户与目标日期分区,再拷入新行且保留其它分区")
  void shouldReplaceOnlyTargetPartition_whenCopyingRows() throws Exception {
    ImportLoadContext context = new ImportLoadContext(
        "t1",
        "IMPORT_CUSTOMER",
        "trace-1",
        "worker-1",
        "customers.csv",
        "BATCH-1",
        "2026-06-07",
        "CUSTOMER",
        null,
        "TPL-COPY",
        templateConfig());

    plugin.preparePartitionReplace(context);
    int copied = plugin.loadChunk(
        context,
        List.of(
            Map.of(
                "customerNo",
                "C001",
                "customerName",
                "Alice, Inc.",
                "amount",
                "10.50",
                "note",
                "quoted \"note\""),
            rowWithNullNote()));

    assertThat(copied).isEqualTo(2);
    final String selectBizCopyImportCustomerSql = """
                SELECT count(*) FROM biz.copy_import_customer
                WHERE tenant_id='t1' AND biz_date='2026-06-07'
                """;
    Integer selectedCopyImportCustomerCount =
        jdbcTemplate.queryForObject(selectBizCopyImportCustomerSql, Integer.class);
    assertThat(selectedCopyImportCustomerCount).isEqualTo(2);
    final String selectBizCopyImportCustomerSqlValue3 = """
                SELECT customer_no FROM biz.copy_import_customer
                WHERE tenant_id='t1' AND biz_date='2026-06-07'
                ORDER BY customer_no
                """;
    List<String> selectedCustomerNoValues =
        jdbcTemplate.queryForList(selectBizCopyImportCustomerSqlValue3, String.class);
    assertThat(selectedCustomerNoValues).containsExactly("C001", "C002");
    final String selectBizCopyImportCustomerSqlValue4 = """
                SELECT note FROM biz.copy_import_customer
                WHERE tenant_id='t1' AND biz_date='2026-06-07' AND customer_no='C001'
                """;
    String selectedNote =
        jdbcTemplate.queryForObject(selectBizCopyImportCustomerSqlValue4, String.class);
    assertThat(selectedNote).isEqualTo("quoted \"note\"");
    final String selectBizCopyImportCustomerSqlValue5 = """
                SELECT note FROM biz.copy_import_customer
                WHERE tenant_id='t1' AND biz_date='2026-06-07' AND customer_no='C002'
                """;
    String selectedNoteRead2 =
        jdbcTemplate.queryForObject(selectBizCopyImportCustomerSqlValue5, String.class);
    assertThat(selectedNoteRead2).isNull();
    assertThat(rowExists("t1", "2026-06-06", "keep-date")).isTrue();
    assertThat(rowExists("t2", "2026-06-07", "keep-tenant")).isTrue();
    assertThat(rowExists("t1", "2026-06-07", "old-target")).isFalse();
  }

  @Test
  @DisplayName("阶段交换先写入中间分区,收尾时切换到按日期命名的物理分区")
  void shouldSwapPhysicalPartition_whenStageSwapFinishes() throws Exception {
    ImportLoadContext context = new ImportLoadContext(
        "t1",
        "IMPORT_CUSTOMER",
        "trace-stage-swap",
        "worker-1",
        "customers.csv",
        "BATCH-1",
        "2026-06-07",
        "CUSTOMER",
        null,
        "TPL-STAGE-SWAP",
        stageSwapTemplateConfig());

    plugin.preparePartitionReplace(context);
    int copied = plugin.loadChunk(
        context,
        List.of(
            Map.of(
                "customerNo",
                "C101",
                "customerName",
                "Stage Alice",
                "amount",
                "110.50",
                "note",
                "fresh"),
            rowWithNullNote("C102")));
    plugin.finishPartitionStageSwap(context);

    assertThat(copied).isEqualTo(2);
    final String selectBizCopyImportCustomerPartSql = """
                SELECT customer_no FROM biz.copy_import_customer_part
                WHERE tenant_id='t1' AND biz_date='2026-06-07'
                ORDER BY customer_no
                """;
    List<String> selectedCustomerNoValues =
        jdbcTemplate.queryForList(selectBizCopyImportCustomerPartSql, String.class);
    assertThat(selectedCustomerNoValues).containsExactly("C101", "C102");
    final String selectBizCopyImportCustomerPart20260607Sql = """
                SELECT count(*) FROM biz.copy_import_customer_part_20260607
                """;
    Integer selectedCopyImportCustomerPart20260607Count =
        jdbcTemplate.queryForObject(selectBizCopyImportCustomerPart20260607Sql, Integer.class);
    assertThat(selectedCopyImportCustomerPart20260607Count).isEqualTo(2);
  }

  @Test
  @DisplayName("批量更新按模板声明转换数值与日期类型,同主键二次写入转为更新")
  void shouldCoerceTypedValues_whenBatchUpserting() throws Exception {
    ImportLoadContext context = new ImportLoadContext(
        "t1",
        "IMPORT_TRANSACTION",
        "trace-upsert",
        "worker-1",
        "transactions.csv",
        "BATCH-1",
        "2026-06-07",
        "TRANSACTION",
        null,
        "TPL-UPsert",
        batchUpsertTemplateConfig());

    int inserted = plugin.loadChunk(
        context,
        List.of(Map.of(
            "txnNo", "T001",
            "amount", "101.50",
            "txnDate", "2026-06-07",
            "remark", "first")));
    int updated = plugin.loadChunk(
        context,
        List.of(Map.of(
            "txnNo", "T001",
            "amount", "202.75",
            "txnDate", "2026-06-07",
            "remark", "updated")));

    assertThat(inserted).isEqualTo(1);
    assertThat(updated).isEqualTo(1);
    final String selectBizBatchUpsertTransactionSql = """
        SELECT amount, txn_date, remark
        FROM biz.batch_upsert_transaction
        WHERE tenant_id='t1' AND txn_no='T001'
        """;
    Map<String, Object> row = jdbcTemplate.queryForMap(selectBizBatchUpsertTransactionSql);
    assertThat(row.get("amount")).isEqualTo(new BigDecimal("202.75"));
    assertThat(row.get("txn_date")).isEqualTo(Date.valueOf("2026-06-07"));
    assertThat(row.get("remark")).isEqualTo("updated");
  }

  @Test
  @DisplayName("BATCH_UPSERT 缺少冲突键时在写入前拒绝，不能以纯 INSERT 进入可重试链路")
  void shouldRejectBatchUpsertWithoutConflictColumnsBeforeWriting() {
    ImportLoadContext context = new ImportLoadContext(
        "t1",
        "IMPORT_TRANSACTION",
        "trace-upsert-no-key",
        "worker-1",
        "transactions.csv",
        "BATCH-2",
        "2026-06-07",
        "TRANSACTION",
        null,
        "TPL-UPsert-no-key",
        batchUpsertTemplateConfigWithoutConflictColumns());

    assertThatThrownBy(() -> plugin.loadChunk(
            context,
            List.of(Map.of(
                "txnNo", "T002",
                "amount", "101.50",
                "txnDate", "2026-06-07",
                "remark", "must not be inserted"))))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("conflictColumns");
    final String selectBizBatchUpsertTransactionSqlValue6 = """
        SELECT count(*) FROM biz.batch_upsert_transaction WHERE txn_no='T002'
    """;
    Integer selectedBatchUpsertTransactionCount =
        jdbcTemplate.queryForObject(selectBizBatchUpsertTransactionSqlValue6, Integer.class);
    assertThat(selectedBatchUpsertTransactionCount).isZero();
  }

  @Test
  @DisplayName("非特权角色下行级安全生效:本租户可写,他租户写入被拒")
  void shouldEnforceTenantPolicy_whenRoleIsNotPrivileged() throws Exception {
    String role = "copy_import_rls_" + UUID.randomUUID().toString().replace("-", "");
    final String createRoleSql = """
        CREATE ROLE %s LOGIN PASSWORD 'test-password' NOSUPERUSER NOBYPASSRLS
        """.formatted(role);
    jdbcTemplate.execute(createRoleSql);
    final String grantUsageSql = """
        GRANT USAGE ON SCHEMA biz TO %s
    """.formatted(role);
    jdbcTemplate.execute(grantUsageSql);
    final String grantSelectInsertSql =
        """
        GRANT SELECT, INSERT ON biz.copy_import_customer TO %s
    """.formatted(role);
    jdbcTemplate.execute(grantSelectInsertSql);
    final String grantSequenceUsageSql =
        """
        GRANT USAGE, SELECT ON SEQUENCE biz.copy_import_customer_id_seq TO %s
    """.formatted(role);
    jdbcTemplate.execute(grantSequenceUsageSql);
    final String alterBizCopyImportCustomerSqlValue7 = """
        ALTER TABLE biz.copy_import_customer FORCE ROW LEVEL SECURITY
    """;
    jdbcTemplate.execute(alterBizCopyImportCustomerSqlValue7);
    final String createSqlValue8 = """
        CREATE POLICY tenant_isolation_test ON biz.copy_import_customer
          AS PERMISSIVE
          FOR ALL
          TO PUBLIC
          USING (tenant_id = current_setting('app.tenant_id', true))
          WITH CHECK (tenant_id = current_setting('app.tenant_id', true))
        """;
    jdbcTemplate.execute(createSqlValue8);

    DriverManagerDataSource rlsDataSource = new DriverManagerDataSource();
    rlsDataSource.setDriverClassName("org.postgresql.Driver");
    rlsDataSource.setUrl(POSTGRES.getJdbcUrl());
    rlsDataSource.setUsername(role);
    rlsDataSource.setPassword("test-password");
    JdbcTemplate rlsJdbcTemplate = new JdbcTemplate(rlsDataSource);
    JdbcMappedImportSecurityProperties security = new JdbcMappedImportSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    GenericJdbcMappedImportLoadPlugin rlsPlugin =
        new GenericJdbcMappedImportLoadPlugin(rlsDataSource, new ObjectMapper(), security);

    final String selectPgRolesSql = """
        SELECT r.rolsuper, r.rolbypassrls
        FROM pg_roles r
        WHERE r.rolname = current_user
        """;
    Map<String, Object> roleAttributes = rlsJdbcTemplate.queryForMap(selectPgRolesSql);
    assertThat(roleAttributes.get("rolsuper")).isEqualTo(false);
    assertThat(roleAttributes.get("rolbypassrls")).isEqualTo(false);

    ImportLoadContext context = new ImportLoadContext(
        "t1",
        "IMPORT_CUSTOMER",
        "trace-rls",
        "worker-1",
        "customers.csv",
        "BATCH-RLS",
        "2026-06-07",
        "CUSTOMER",
        null,
        "TPL-COPY",
        templateConfig());
    Map<String, Object> row = Map.of(
        "customerNo", "RLS-ALLOWED",
        "customerName", "Tenant one",
        "amount", "10.50",
        "note", "policy checked");

    assertThat(rlsPlugin.loadChunk(context, List.of(row))).isEqualTo(1);
    assertThat(countVisibleRows(rlsDataSource, "t1", "RLS-ALLOWED")).isEqualTo(1);
    assertThat(countVisibleRows(rlsDataSource, "t2", "RLS-ALLOWED")).isZero();

    RlsTenantContextHolder.set("t2");
    assertThatThrownBy(() -> rlsPlugin.loadChunk(context, List.of(rowWithCustomerNo("RLS-DENIED"))))
        .hasStackTraceContaining("row-level security");
    final String selectBizCopyImportCustomerSqlValue9 = """
        SELECT count(*) FROM biz.copy_import_customer WHERE customer_no='RLS-DENIED'
    """;
    Integer selectedCopyImportCustomerCount =
        jdbcTemplate.queryForObject(selectBizCopyImportCustomerSqlValue9, Integer.class);
    assertThat(selectedCopyImportCustomerCount).isZero();
  }

  private int countVisibleRows(DriverManagerDataSource source, String tenantId, String customerNo) {
    RlsTenantContextHolder.set(tenantId);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    return transaction.execute(status -> {
      RlsTenantSessionSupport.applyIfPresent(source);
      final String selectBizCopyImportCustomerSqlValue10 = """
          SELECT count(*) FROM biz.copy_import_customer WHERE customer_no=?
      """;
      return new JdbcTemplate(source)
          .queryForObject(
              selectBizCopyImportCustomerSqlValue10,
              Integer.class,
              customerNo);
    });
  }

  private static Map<String, Object> rowWithCustomerNo(String customerNo) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("customerNo", customerNo);
    row.put("customerName", "Tenant one");
    row.put("amount", "10.50");
    row.put("note", "policy checked");
    return row;
  }

  private boolean rowExists(String tenantId, String bizDate, String customerNo) {
    final String selectBizCopyImportCustomerSqlValue11 = """
            SELECT count(*) FROM biz.copy_import_customer
            WHERE tenant_id=? AND biz_date=? AND customer_no=?
            """;
    Integer count = jdbcTemplate.queryForObject(
        selectBizCopyImportCustomerSqlValue11, Integer.class, tenantId, Date.valueOf(bizDate), customerNo);
    return count != null && count > 0;
  }

  private static Map<String, Object> rowWithNullNote() {
    return rowWithNullNote("C002");
  }

  private static Map<String, Object> rowWithNullNote(String customerNo) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("customerNo", customerNo);
    row.put("customerName", "Bob");
    row.put("amount", "20.00");
    row.put("note", null);
    return row;
  }

  private static Map<String, Object> templateConfig() {
    return Map.of(
        "jdbc_mapped_import",
        Map.of(
            "schema",
            "biz",
            "table",
            "copy_import_customer",
            "tenantColumn",
            "tenant_id",
            "columnMappings",
            List.of(
                Map.of("from", "customerNo", "to", "customer_no"),
                Map.of("from", "customerName", "to", "customer_name"),
                Map.of("from", "amount", "to", "amount"),
                Map.of("from", "note", "to", "note")),
            "systemBindings",
            Map.of("biz_date", "${bizDate}"),
            "loadStrategy",
            "PARTITION_REPLACE_COPY",
            "conflictColumns",
            List.of("tenant_id", "customer_no"),
            "replacePartitionColumns",
            List.of("tenant_id", "biz_date")));
  }

  private static Map<String, Object> stageSwapTemplateConfig() {
    return Map.of(
        "jdbc_mapped_import",
        Map.of(
            "schema",
            "biz",
            "table",
            "copy_import_customer_part",
            "tenantColumn",
            "tenant_id",
            "columnMappings",
            List.of(
                Map.of("from", "customerNo", "to", "customer_no"),
                Map.of("from", "customerName", "to", "customer_name"),
                Map.of("from", "amount", "to", "amount"),
                Map.of("from", "note", "to", "note")),
            "systemBindings",
            Map.of("biz_date", "${bizDate}"),
            "loadStrategy",
            "PARTITION_STAGE_SWAP_COPY",
            "replacePartitionColumns",
            List.of("tenant_id", "biz_date"),
            "stageSwap",
            Map.of(
                "partitionTable",
                "copy_import_customer_part_20260607",
                "attachClause",
                "FOR VALUES FROM ('2026-06-07') TO ('2026-06-08')")));
  }

  private static Map<String, Object> batchUpsertTemplateConfig() {
    return Map.of(
        "field_mappings",
        List.of(
            Map.of("name", "txnNo", "targetColumn", "txn_no", "type", "STRING"),
            Map.of("name", "amount", "targetColumn", "amount", "type", "DECIMAL"),
            Map.of(
                "name",
                "txnDate",
                "targetColumn",
                "txn_date",
                "type",
                "DATE",
                "format",
                "yyyy-MM-dd"),
            Map.of("name", "remark", "targetColumn", "remark", "type", "STRING")),
        "jdbc_mapped_import",
        Map.of(
            "schema",
            "biz",
            "table",
            "batch_upsert_transaction",
            "tenantColumn",
            "tenant_id",
            "columnMappings",
            List.of(
                Map.of("from", "txnNo", "to", "txn_no"),
                Map.of("from", "amount", "to", "amount"),
                Map.of("from", "txnDate", "to", "txn_date"),
                Map.of("from", "remark", "to", "remark")),
            "conflictColumns",
            List.of("tenant_id", "txn_no", "txn_date")));
  }

  private static Map<String, Object> batchUpsertTemplateConfigWithoutConflictColumns() {
    return Map.of(
        "field_mappings",
        List.of(
            Map.of("name", "txnNo", "targetColumn", "txn_no", "type", "STRING"),
            Map.of("name", "amount", "targetColumn", "amount", "type", "DECIMAL"),
            Map.of(
                "name",
                "txnDate",
                "targetColumn",
                "txn_date",
                "type",
                "DATE",
                "format",
                "yyyy-MM-dd"),
            Map.of("name", "remark", "targetColumn", "remark", "type", "STRING")),
        "jdbc_mapped_import",
        Map.of(
            "schema",
            "biz",
            "table",
            "batch_upsert_transaction",
            "tenantColumn",
            "tenant_id",
            "columnMappings",
            List.of(
                Map.of("from", "txnNo", "to", "txn_no"),
                Map.of("from", "amount", "to", "amount"),
                Map.of("from", "txnDate", "to", "txn_date"),
                Map.of("from", "remark", "to", "remark"))));
  }
}
