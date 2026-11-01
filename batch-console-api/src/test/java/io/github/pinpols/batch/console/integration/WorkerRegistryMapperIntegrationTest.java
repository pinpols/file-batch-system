package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.WorkerRegistryResponse;
import io.github.pinpols.batch.console.domain.ops.mapper.WorkerRegistryMapper;
import io.github.pinpols.batch.console.domain.ops.query.WorkerRegistryQuery;
import io.github.pinpols.batch.console.domain.ops.view.WorkerRegistryView;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
import java.util.List;
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

@DisplayName("Worker 注册查询：真实 SQL 的租户范围与负载字段映射")
class WorkerRegistryMapperIntegrationTest {

  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();
  private static SqlSessionFactory sessionFactory;

  @BeforeAll
  static void initialize() throws Exception {
    POSTGRES.start();
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String createSchemaSql = """
        CREATE SCHEMA batch;
        CREATE TABLE batch.worker_registry (
            id bigint primary key, tenant_id varchar(64), worker_code varchar(128),
            worker_group varchar(128), port integer, capability_tags jsonb, resource_tag varchar(64),
            task_capabilities jsonb, status varchar(32), heartbeat_at timestamptz,
            current_load integer, max_concurrent integer, drain_started_at timestamptz,
            drain_deadline_at timestamptz, is_self_hosted boolean
        )
        """;
    jdbc.execute(createSchemaSql);
    String insertSql = """
        INSERT INTO batch.worker_registry
          (id, tenant_id, worker_code, worker_group, status, heartbeat_at,
           current_load, max_concurrent, is_self_hosted)
        VALUES (1, 'tenant-a', 'self', 'default', 'ONLINE', now(), 3, 8, true),
               (2, 'tenant-a', 'managed', 'default', 'ONLINE', now(), 1, 4, false),
               (3, 'tenant-b', 'other', 'default', 'ONLINE', now(), 7, 9, true)
        """;
    jdbc.update(insertSql);

    Environment environment = new Environment("worker-registry", new JdbcTransactionFactory(), dataSource);
    Configuration configuration = new Configuration(environment);
    configuration.setMapUnderscoreToCamelCase(false);
    for (String resource : List.of("mapper/CommonFragments.xml", "mapper/WorkerRegistryMapper.xml")) {
      try (InputStream input = Resources.getResourceAsStream(resource)) {
        new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
      }
    }
    sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  @DisplayName("自托管列表仅返回本租户，负载和并发数进入响应")
  void shouldMapCapacityForSelfHostedWorkersWithinTenant() {
    try (SqlSession session = sessionFactory.openSession()) {
      WorkerRegistryMapper mapper = session.getMapper(WorkerRegistryMapper.class);
      List<WorkerRegistryView> workers = mapper.selectSelfHostedByTenant("tenant-a");
      assertThat(workers).hasSize(1);
      WorkerRegistryResponse response = WorkerRegistryResponse.from(workers.getFirst());
      assertThat(response.workerCode()).isEqualTo("self");
      assertThat(response.currentLoad()).isEqualTo(3);
      assertThat(response.maxConcurrent()).isEqualTo(8);

      WorkerRegistryQuery query = new WorkerRegistryQuery("tenant-a", null, null, new PageRequest(1, 10));
      assertThat(mapper.selectByQuery(query))
          .extracting(WorkerRegistryView::getTenantId)
          .containsOnly("tenant-a");
    }
  }
}
