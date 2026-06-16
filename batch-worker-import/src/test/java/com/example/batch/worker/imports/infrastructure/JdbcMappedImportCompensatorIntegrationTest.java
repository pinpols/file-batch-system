package com.example.batch.worker.imports.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.support.CompensationResult;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("安全增量补偿 IMPORT：只删本 run 自己的 biz 行，别的 run / 租户不动；没绑 run 列则 SKIP 不删")
class JdbcMappedImportCompensatorIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
          .withDatabaseName("batch_business")
          .withUsername("batch_user")
          .withPassword("batch_pass_123")
          .withUrlParam("sslmode", "disable");

  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private JdbcMappedImportCompensator compensator;

  @BeforeEach
  void setUp() {
    dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl() + "&stringtype=unspecified");
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    compensator = new JdbcMappedImportCompensator(dataSource, new ObjectMapper());

    jdbcTemplate.execute("DROP SCHEMA IF EXISTS biz CASCADE");
    jdbcTemplate.execute("CREATE SCHEMA biz");
    jdbcTemplate.execute(
        """
        CREATE TABLE biz.import_customer (
          tenant_id text NOT NULL,
          source_batch_no text NOT NULL,
          customer_no text NOT NULL,
          customer_name text,
          PRIMARY KEY (tenant_id, source_batch_no, customer_no)
        )
        """);
    // 本 run（t1 / BATCH-1）的 2 行
    insert("t1", "BATCH-1", "C001", "this run a");
    insert("t1", "BATCH-1", "C002", "this run b");
    // 别的 run（同租户不同 batchNo）—— 不应被删
    insert("t1", "BATCH-2", "C001", "other run");
    // 别的租户（同 batchNo）—— 不应被删
    insert("t2", "BATCH-1", "C001", "other tenant");
  }

  @Test
  @DisplayName("opt-in on + 绑定 source_batch_no=${batchNo}：只删 t1/BATCH-1 的行")
  void reversesOnlyThisRunRows() {
    Map<String, Object> attributes = attributes("BATCH-1", templateConfigWithBatchNoBinding());

    CompensationResult result = compensator.compensate("t1", 100L, 5L, attributes);

    assertThat(result.outcome()).isEqualTo(CompensationResult.Outcome.REVERSED);
    assertThat(result.reversedCount()).isEqualTo(2L);
    assertThat(countRows("t1", "BATCH-1")).isZero();
    assertThat(countRows("t1", "BATCH-2")).isEqualTo(1);
    assertThat(countRows("t2", "BATCH-1")).isEqualTo(1);
  }

  @Test
  @DisplayName("重复跑幂等：第二次删 0 行，结果一致")
  void idempotentOnSecondRun() {
    Map<String, Object> attributes = attributes("BATCH-1", templateConfigWithBatchNoBinding());

    compensator.compensate("t1", 100L, 5L, attributes);
    CompensationResult second = compensator.compensate("t1", 100L, 5L, attributes);

    assertThat(second.outcome()).isEqualTo(CompensationResult.Outcome.REVERSED);
    assertThat(second.reversedCount()).isZero();
  }

  @Test
  @DisplayName("模板没绑任何 run 标识列：SKIP 不删，所有行原样保留")
  void skipsWhenNoRunIdentifierColumn() {
    Map<String, Object> attributes = attributes("BATCH-1", templateConfigWithoutRunBinding());

    CompensationResult result = compensator.compensate("t1", 100L, 5L, attributes);

    assertThat(result.outcome()).isEqualTo(CompensationResult.Outcome.SKIPPED);
    assertThat(result.reversedCount()).isZero();
    assertThat(result.detail()).contains("no run-identifier column");
    // 一行都没删
    assertThat(countRows("t1", "BATCH-1")).isEqualTo(2);
    assertThat(countRows("t1", "BATCH-2")).isEqualTo(1);
    assertThat(countRows("t2", "BATCH-1")).isEqualTo(1);
  }

  private void insert(String tenantId, String batchNo, String customerNo, String name) {
    jdbcTemplate.update(
        "INSERT INTO biz.import_customer VALUES (?,?,?,?)", tenantId, batchNo, customerNo, name);
  }

  private int countRows(String tenantId, String batchNo) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM biz.import_customer WHERE tenant_id=? AND source_batch_no=?",
            Integer.class,
            tenantId,
            batchNo);
    return count == null ? 0 : count;
  }

  private static Map<String, Object> attributes(
      String batchNo, Map<String, Object> templateConfig) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig);
    attributes.put(PipelineRuntimeKeys.TRACE_ID, "trace-xyz");
    // ImportPayload 23 字段；batchNo 是第 16 个（前 15 个 = fileCode..templateCode 置 null）。
    attributes.put(
        "importPayload",
        new ImportPayload(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, batchNo, null, null, null, null, null, null, null));
    return attributes;
  }

  private static Map<String, Object> templateConfigWithBatchNoBinding() {
    return Map.of(
        "compensate_on_failure",
        true,
        "jdbc_mapped_import",
        Map.of(
            "schema",
            "biz",
            "table",
            "import_customer",
            "tenantColumn",
            "tenant_id",
            "columnMappings",
            List.of(Map.of("from", "customerNo", "to", "customer_no")),
            "systemBindings",
            Map.of("source_batch_no", "${batchNo}")));
  }

  private static Map<String, Object> templateConfigWithoutRunBinding() {
    return Map.of(
        "compensate_on_failure",
        true,
        "jdbc_mapped_import",
        Map.of(
            "schema",
            "biz",
            "table",
            "import_customer",
            "tenantColumn",
            "tenant_id",
            "columnMappings",
            List.of(Map.of("from", "customerNo", "to", "customer_no"))));
  }
}
