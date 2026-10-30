package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.plugin.ExportDataContext;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.exports.config.JdbcMappedExportSecurityProperties;
import io.github.pinpols.batch.worker.exports.config.SqlTemplateExportSecurityProperties;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("导出多分片统一哈希的完整性")
class ExportPartitionMixedFallbackIntegrationTest {

  @BeforeEach
  void bindTenant() {
    RlsTenantContextHolder.set("tenant-a");
  }

  @AfterEach
  void clearTenant() {
    RlsTenantContextHolder.clear();
  }

  @ParameterizedTest
  @ValueSource(strings = {"sql_template", "jdbc_mapped"})
  @DisplayName("开启旧区间配置时仍统一哈希分片，不探测范围且不重不漏")
  void shouldUseHashWithoutRangeProbe_whenRangeOptionEnabled(String mode) throws Exception {
    try (PostgreSQLContainer postgres = TestPostgresContainers.platform()) {
      postgres.start();
      PGSimpleDataSource dataSource = new PGSimpleDataSource();
      dataSource.setURL(postgres.getJdbcUrl());
      dataSource.setUser(postgres.getUsername());
      dataSource.setPassword(postgres.getPassword());
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      String createSchemaSql = "CREATE SCHEMA biz";
      String createTableSql = """
          CREATE TABLE biz.export_detail (
              id bigint PRIMARY KEY, tenant_id text NOT NULL,
              batch_no text NOT NULL, batch_id bigint NOT NULL)
          """;
      String insertSql = """
          INSERT INTO biz.export_detail(id, tenant_id, batch_no, batch_id)
          SELECT n, 'tenant-a', 'batch-a', 1 FROM generate_series(1,10) n
          """;
      jdbc.execute(createSchemaSql);
      jdbc.execute(createTableSql);
      jdbc.update(insertSql);

      Map<String, Object> template = template(mode);
      FailMinMaxDataSource failingDataSource = new FailMinMaxDataSource(dataSource);
      ExportDataPlugin first = plugin(mode, dataSource);
      ExportDataPlugin second = plugin(mode, failingDataSource);
      Set<Long> partOne = ids(first.loadDetailPage(context(template, 1), 1L, 100, null));
      Set<Long> partTwo = ids(second.loadDetailPage(context(template, 2), 1L, 100, null));
      assertThat(failingDataSource.injected.get())
          .as("hash partitioning must not execute a range probe")
          .isFalse();
      Set<Long> overlap = new HashSet<>(partOne);
      overlap.retainAll(partTwo);
      Set<Long> union = new HashSet<>(partOne);
      union.addAll(partTwo);

      assertThat(overlap).as("partition overlap").isEmpty();
      assertThat(union)
          .as("partition coverage")
          .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    }
  }

  private static ExportDataContext context(Map<String, Object> template, int partition) {
    return new ExportDataContext(
        "tenant-a",
        "job-a",
        "batch-a",
        "template-a",
        template,
        new LinkedHashMap<>(),
        partition,
        2);
  }

  private static Map<String, Object> template(String mode) {
    if (mode.equals("sql_template")) {
      return Map.of(
          "default_query_sql",
          "SELECT id FROM biz.export_detail WHERE tenant_id = :tenantId AND batch_no = :batchNo",
          "partition_keyset_range",
          true);
    }
    return Map.of(
        "partition_keyset_range",
        true,
        "jdbc_mapped_export",
        Map.of(
            "schema",
            "biz",
            "batchTable",
            "export_detail",
            "batchTenantColumn",
            "tenant_id",
            "batchNoColumn",
            "batch_no",
            "batchSelectColumns",
            List.of("id"),
            "detailTable",
            "export_detail",
            "detailFkColumn",
            "batch_id",
            "detailOrderByColumn",
            "id",
            "detailSelectColumns",
            List.of("id")));
  }

  private static ExportDataPlugin plugin(String mode, DataSource dataSource) {
    ObjectMapper mapper = new ObjectMapper();
    if (mode.equals("sql_template")) {
      return new SqlTemplateExportDataPlugin(
          dataSource, mapper, new SqlTemplateExportSecurityProperties(), null);
    }
    return new GenericJdbcMappedExportDataPlugin(
        dataSource, mapper, new JdbcMappedExportSecurityProperties());
  }

  private static Set<Long> ids(ExportDataPlugin.DetailPage page) {
    Set<Long> result = new HashSet<>();
    for (Map<String, Object> row : page.rows()) {
      result.add(((Number) row.get("id")).longValue());
    }
    return result;
  }

  private static class FailMinMaxDataSource extends DelegatingDataSource {
    private final AtomicBoolean injected = new AtomicBoolean();

    FailMinMaxDataSource(DataSource target) {
      super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
      Connection connection = super.getConnection();
      return (Connection) Proxy.newProxyInstance(
          Connection.class.getClassLoader(),
          new Class<?>[] {Connection.class},
          (proxy, method, args) -> {
            if (method.getName().equals("prepareStatement")
                && args != null
                && args.length > 0
                && args[0] instanceof String sql
                && sql.startsWith("SELECT min(")
                && injected.compareAndSet(false, true)) {
              throw new SQLException("injected range probe failure");
            }
            try {
              return method.invoke(connection, args);
            } catch (InvocationTargetException ex) {
              throw ex.getCause();
            }
          });
    }
  }
}
