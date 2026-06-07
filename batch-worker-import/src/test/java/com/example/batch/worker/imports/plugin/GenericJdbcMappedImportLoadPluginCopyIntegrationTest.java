package com.example.batch.worker.imports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.plugin.ImportLoadContext;
import com.example.batch.worker.imports.config.JdbcMappedImportSecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class GenericJdbcMappedImportLoadPluginCopyIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
          .withDatabaseName("batch_business")
          .withUsername("batch_user")
          .withPassword("batch_pass_123")
          .withUrlParam("sslmode", "disable");

  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private GenericJdbcMappedImportLoadPlugin plugin;

  @BeforeEach
  void setUp() {
    dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl() + "&stringtype=unspecified");
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    JdbcMappedImportSecurityProperties security = new JdbcMappedImportSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    plugin = new GenericJdbcMappedImportLoadPlugin(dataSource, new ObjectMapper(), security);

    jdbcTemplate.execute("DROP SCHEMA IF EXISTS biz CASCADE");
    jdbcTemplate.execute("CREATE SCHEMA biz");
    jdbcTemplate.execute(
        """
        CREATE TABLE biz.copy_import_customer (
          tenant_id text NOT NULL,
          biz_date date NOT NULL,
          customer_no text NOT NULL,
          customer_name text,
          amount numeric(12,2),
          note text,
          PRIMARY KEY (tenant_id, biz_date, customer_no)
        )
        """);
    jdbcTemplate.execute(
        """
        CREATE TABLE biz.copy_import_customer_part (
          tenant_id text NOT NULL,
          biz_date date NOT NULL,
          customer_no text NOT NULL,
          customer_name text,
          amount numeric(12,2),
          note text,
          PRIMARY KEY (tenant_id, biz_date, customer_no)
        ) PARTITION BY RANGE (biz_date)
        """);
    jdbcTemplate.execute(
        """
        CREATE TABLE biz.copy_import_customer_part_20260607
        PARTITION OF biz.copy_import_customer_part
        FOR VALUES FROM ('2026-06-07') TO ('2026-06-08')
        """);
    jdbcTemplate.update(
        "INSERT INTO biz.copy_import_customer VALUES (?,?,?,?,?,?)",
        "t1",
        Date.valueOf("2026-06-07"),
        "old-target",
        "old target",
        "1.00",
        "delete me");
    jdbcTemplate.update(
        "INSERT INTO biz.copy_import_customer VALUES (?,?,?,?,?,?)",
        "t1",
        Date.valueOf("2026-06-06"),
        "keep-date",
        "keep other date",
        "2.00",
        "keep");
    jdbcTemplate.update(
        "INSERT INTO biz.copy_import_customer VALUES (?,?,?,?,?,?)",
        "t2",
        Date.valueOf("2026-06-07"),
        "keep-tenant",
        "keep other tenant",
        "3.00",
        "keep");
    jdbcTemplate.update(
        "INSERT INTO biz.copy_import_customer_part VALUES (?,?,?,?,?,?)",
        "t1",
        Date.valueOf("2026-06-07"),
        "old-target",
        "old target",
        "1.00",
        "delete me");
  }

  @Test
  void partitionReplaceCopyDeletesOnlyTargetPartitionThenCopiesRows() throws Exception {
    ImportLoadContext context =
        new ImportLoadContext(
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
    int copied =
        plugin.loadChunk(
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
    assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM biz.copy_import_customer
                WHERE tenant_id='t1' AND biz_date='2026-06-07'
                """,
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT customer_no FROM biz.copy_import_customer
                WHERE tenant_id='t1' AND biz_date='2026-06-07'
                ORDER BY customer_no
                """,
                String.class))
        .containsExactly("C001", "C002");
    assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT note FROM biz.copy_import_customer
                WHERE tenant_id='t1' AND biz_date='2026-06-07' AND customer_no='C001'
                """,
                String.class))
        .isEqualTo("quoted \"note\"");
    assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT note FROM biz.copy_import_customer
                WHERE tenant_id='t1' AND biz_date='2026-06-07' AND customer_no='C002'
                """,
                String.class))
        .isNull();
    assertThat(rowExists("t1", "2026-06-06", "keep-date")).isTrue();
    assertThat(rowExists("t2", "2026-06-07", "keep-tenant")).isTrue();
    assertThat(rowExists("t1", "2026-06-07", "old-target")).isFalse();
  }

  @Test
  void partitionStageSwapCopyLoadsStagingThenSwapsPhysicalPartition() throws Exception {
    ImportLoadContext context =
        new ImportLoadContext(
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
    int copied =
        plugin.loadChunk(
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
    assertThat(
            jdbcTemplate.queryForList(
                """
                SELECT customer_no FROM biz.copy_import_customer_part
                WHERE tenant_id='t1' AND biz_date='2026-06-07'
                ORDER BY customer_no
                """,
                String.class))
        .containsExactly("C101", "C102");
    assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM biz.copy_import_customer_part_20260607
                """,
                Integer.class))
        .isEqualTo(2);
  }

  private boolean rowExists(String tenantId, String bizDate, String customerNo) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM biz.copy_import_customer
            WHERE tenant_id=? AND biz_date=? AND customer_no=?
            """,
            Integer.class,
            tenantId,
            Date.valueOf(bizDate),
            customerNo);
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
}
