package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.view.DispatchSecretBindingView;
import io.github.pinpols.batch.orchestrator.mapper.DispatchSecretBindingMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("Dispatch SecretVersion 选版 SQL 的真实 PostgreSQL 语义")
class DispatchSecretBindingMapperIntegrationTest {

  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();
  private static DataSource dataSource;
  private static JdbcTemplate jdbcTemplate;
  private static SqlSessionFactory sqlSessionFactory;

  @BeforeAll
  static void initializeDatabase() throws Exception {
    POSTGRES.start();
    PGSimpleDataSource postgresDataSource = new PGSimpleDataSource();
    postgresDataSource.setURL(POSTGRES.getJdbcUrl());
    postgresDataSource.setUser(POSTGRES.getUsername());
    postgresDataSource.setPassword(POSTGRES.getPassword());
    dataSource = postgresDataSource;
    jdbcTemplate = new JdbcTemplate(dataSource);
    String createBatchSchemaSql = """
        create schema batch
    """;
    jdbcTemplate.execute(createBatchSchemaSql);
    String createFileChannelConfigTableSql = """
        create table batch.file_channel_config (
          tenant_id varchar(64) not null,
          channel_code varchar(64) not null,
          config_json jsonb not null,
          is_deleted boolean not null default false
        )
        """;
    jdbcTemplate.execute(createFileChannelConfigTableSql);
    String createSecretVersionTableSql = """
        create table batch.secret_version (
          id bigint primary key,
          tenant_id varchar(64) not null,
          secret_ref varchar(128) not null,
          version_no integer not null,
          secret_status varchar(32) not null,
          current_version boolean not null,
          effective_from_at timestamptz,
          effective_to_at timestamptz,
          revoked_at timestamptz
        )
        """;
    jdbcTemplate.execute(createSecretVersionTableSql);
    String insertFileChannelConfigsSql = """
        insert into batch.file_channel_config (tenant_id, channel_code, config_json) values
          ('tenant-a', 'CH_CURRENT', '{"secret_ref":"dispatch/current"}'),
          ('tenant-a', 'CH_UNAVAILABLE', '{"secret_ref":"dispatch/unavailable"}'),
          ('tenant-b', 'CH_CURRENT', '{"secret_ref":"dispatch/current"}')
        """;
    jdbcTemplate.update(insertFileChannelConfigsSql);
    String insertSecretVersionsSql = """
        insert into batch.secret_version (
          id, tenant_id, secret_ref, version_no, secret_status, current_version,
          effective_from_at, effective_to_at, revoked_at
        ) values
          (101, 'tenant-a', 'dispatch/current', 1, 'PUBLISHED', false, null, null, null),
          (102, 'tenant-a', 'dispatch/current', 2, 'PUBLISHED', true, null, null, null),
          (103, 'tenant-a', 'dispatch/unavailable', 1, 'PUBLISHED', true, null,
             current_timestamp - interval '1 second', null),
          (201, 'tenant-b', 'dispatch/current', 1, 'PUBLISHED', true, null, null, null)
        """;
    jdbcTemplate.update(insertSecretVersionsSql);

    Configuration configuration = new Configuration(
        new Environment("dispatch-secret-binding-it", new JdbcTransactionFactory(), dataSource));
    String mapperPath = "mapper/DispatchSecretBindingMapper.xml";
    try (var mapperXml = Resources.getResourceAsStream(mapperPath)) {
      new XMLMapperBuilder(mapperXml, configuration, mapperPath, configuration.getSqlFragments())
          .parse();
    }
    sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  @DisplayName("只固定本租户当前有效版本,未配置可用版本时返回缺失绑定")
  void selectsCurrentTenantVersion_whenUnavailableSecretBindingExists() {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      DispatchSecretBindingMapper mapper = session.getMapper(DispatchSecretBindingMapper.class);

      List<DispatchSecretBindingView> bindings =
          mapper.selectCurrentByChannels("tenant-a", List.of("CH_CURRENT", "CH_UNAVAILABLE"));

      assertThat(bindings)
          .containsExactlyInAnyOrder(
              new DispatchSecretBindingView("CH_CURRENT", "dispatch/current", 102L, 2),
              new DispatchSecretBindingView("CH_UNAVAILABLE", "dispatch/unavailable", null, null));
      assertThat(mapper.selectCurrentByChannels("tenant-b", List.of("CH_CURRENT")))
          .containsExactly(
              new DispatchSecretBindingView("CH_CURRENT", "dispatch/current", 201L, 1));
    }
  }
}
