package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiMonthlyUsageMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileArrivalGroupMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileRecordMapper;
import io.github.pinpols.batch.console.domain.file.query.FileArrivalGroupQuery;
import io.github.pinpols.batch.console.domain.file.query.FileRecordQuery;
import io.github.pinpols.batch.console.domain.ops.mapper.CustomTaskTypeMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.JobTaskMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserBatchOperationMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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

@DisplayName("Console 查询投影：真实 PostgreSQL 映射和租户范围")
class ConsoleQueryProjectionMapperIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();
  private static final UUID OPERATION_ID = UUID.fromString("6a320000-0000-4000-8000-000000000001");
  private static final UUID REQUEST_ID = UUID.fromString("6a320000-0000-4000-8000-000000000002");
  private static SqlSessionFactory sessionFactory;

  @BeforeAll
  static void initialize() throws Exception {
    POSTGRES.start();
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String schema = """
        CREATE SCHEMA batch;
        CREATE TABLE batch.job_task (
          id bigint, tenant_id text, task_status text, heartbeat_details jsonb,
          heartbeat_at timestamptz, cancel_requested boolean);
        CREATE TABLE batch.custom_task_type_registry (
          id bigint, tenant_id text, task_type_code text, display_name text, descriptor jsonb,
          descriptor_version text, source text, declared_by_worker_code text, status text,
          first_declared_at timestamptz, last_declared_at timestamptz);
        CREATE TABLE batch.console_ai_monthly_usage (
          tenant_id text, billing_month date, request_count bigint, prompt_tokens bigint,
          completion_tokens bigint, estimated_cost_usd numeric, reserved_cost_usd numeric);
        CREATE TABLE batch.file_record (
          id bigint, tenant_id text, biz_type text, file_name text, file_status text, biz_date date,
          trace_id text, metadata_json jsonb, created_at timestamptz, updated_at timestamptz);
        CREATE TABLE batch.console_user_batch_operation (
          operation_id uuid, request_id uuid, actor_username text, source_digest text,
          account_count integer, tenant_ids text, created_at timestamptz);
        """;
    jdbc.execute(schema);
    String fixture = """
        INSERT INTO batch.job_task VALUES
          (1, 'tenant-a', 'RUNNING', '{"processed":12}', now(), true),
          (2, 'tenant-b', 'RUNNING', '{"processed":99}', now(), false);
        INSERT INTO batch.custom_task_type_registry VALUES
          (1, 'tenant-a', 'tenant_a_load', 'Load', '{"version":2}', '2',
           'SDK_DECLARED', 'worker-a', 'ACTIVE', now(), now()),
          (2, 'tenant-b', 'tenant_b_load', 'Other', '{}', '1',
           'SDK_DECLARED', 'worker-b', 'ACTIVE', now(), now());
        INSERT INTO batch.console_ai_monthly_usage VALUES
          ('tenant-a', DATE '2026-11-01', 3, 100, 40, 1.25, 0.50),
          ('tenant-b', DATE '2026-11-01', 7, 200, 90, 3.00, 0.75);
        INSERT INTO batch.file_record VALUES
          (10, 'tenant-a', 'PAYROLL', 'a.csv', 'RECEIVED', DATE '2026-11-01', 'trace-a',
           '{"fileGroupCode":"GROUP_A","arrivalState":"WAITING_ARRIVAL"}', now(), now()),
          (11, 'tenant-a', 'PAYROLL', 'b.csv', 'RECEIVED', DATE '2026-11-01', 'trace-b',
           '{"fileGroupCode":"GROUP_A","arrivalState":"WAITING_ARRIVAL"}', now(), now()),
          (12, 'tenant-b', 'PAYROLL', 'other.csv', 'RECEIVED', DATE '2026-11-01', 'trace-c',
           '{"fileGroupCode":"GROUP_A","arrivalState":"WAITING_ARRIVAL"}', now(), now());
        """;
    jdbc.execute(fixture);
    String insertOperation = """
        INSERT INTO batch.console_user_batch_operation
          (operation_id, request_id, actor_username, source_digest, account_count, tenant_ids, created_at)
        VALUES (?, ?, 'operator-a', 'digest', 2, 'tenant-a,tenant-b', now())
        """;
    jdbc.update(insertOperation, OPERATION_ID, REQUEST_ID);

    Configuration configuration = new Configuration(
        new Environment("console-projections", new JdbcTransactionFactory(), dataSource));
    configuration.setMapUnderscoreToCamelCase(false);
    for (String resource : List.of(
        "mapper/CommonFragments.xml",
        "mapper/JobTaskMapper.xml",
        "mapper/CustomTaskTypeMapper.xml",
        "mapper/ConsoleAiMonthlyUsageMapper.xml",
        "mapper/FileArrivalGroupMapper.xml",
        "mapper/FileRecordMapper.xml",
        "mapper/ConsoleUserBatchOperationMapper.xml")) {
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
  @DisplayName("任务心跳只读取本租户并保留 JSON 和取消标记")
  void shouldMapHeartbeatWithinTenant() {
    try (SqlSession session = sessionFactory.openSession()) {
      JobTaskMapper mapper = session.getMapper(JobTaskMapper.class);
      var row = mapper.selectHeartbeatByTenantAndId("tenant-a", 1L);
      assertThat(row.getTaskStatus()).isEqualTo("RUNNING");
      assertThat(row.getHeartbeatDetails()).contains("12");
      assertThat(row.getCancelRequested()).isTrue();
      assertThat(mapper.selectHeartbeatByTenantAndId("tenant-b", 1L)).isNull();
    }
  }

  @Test
  @DisplayName("自定义任务类型映射描述符并按租户过滤")
  void shouldMapTaskTypeWithinTenant() {
    try (SqlSession session = sessionFactory.openSession()) {
      CustomTaskTypeMapper mapper = session.getMapper(CustomTaskTypeMapper.class);
      var rows = mapper.selectActiveByTenant("tenant-a");
      assertThat(rows).hasSize(1);
      assertThat(rows.getFirst().getTaskTypeCode()).isEqualTo("tenant_a_load");
      assertThat(rows.getFirst().getDescriptor()).contains("\"version\": 2");
      assertThat(mapper.selectByTenantAndCode("tenant-b", "tenant_a_load")).isNull();
    }
  }

  @Test
  @DisplayName("AI 月度用量按租户和月份映射计数与金额")
  void shouldMapMonthlyUsageWithinTenant() {
    try (SqlSession session = sessionFactory.openSession()) {
      ConsoleAiMonthlyUsageMapper mapper = session.getMapper(ConsoleAiMonthlyUsageMapper.class);
      var usage = mapper.find("tenant-a", LocalDate.of(2026, 11, 1));
      assertThat(usage.getRequestCount()).isEqualTo(3);
      assertThat(usage.getPromptTokens()).isEqualTo(100);
      assertThat(usage.getEstimatedCostUsd()).isEqualByComparingTo(new BigDecimal("1.25"));
      assertThat(mapper.find("tenant-a", LocalDate.of(2026, 10, 1))).isNull();
    }
  }

  @Test
  @DisplayName("文件组聚合和文件列表都只返回本租户数据")
  void shouldMapArrivalGroupAndFileListWithinTenant() {
    try (SqlSession session = sessionFactory.openSession()) {
      FileArrivalGroupMapper groups = session.getMapper(FileArrivalGroupMapper.class);
      var query = FileArrivalGroupQuery.ofState("tenant-a", "GROUP_A", null, new PageRequest(1, 10));
      var rows = groups.selectByQuery(query);
      assertThat(rows).hasSize(1);
      assertThat(rows.getFirst().getArrivedCount()).isEqualTo(2L);
      assertThat(rows.getFirst().getWaitingCount()).isEqualTo(2L);

      FileRecordMapper files = session.getMapper(FileRecordMapper.class);
      var fileQuery = FileRecordQuery.builder().tenantId("tenant-a").pageRequest(new PageRequest(1, 10)).build();
      var records = files.selectByQuery(fileQuery);
      assertThat(records).extracting(row -> row.getFileName()).containsExactly("b.csv", "a.csv");
      assertThat(records).extracting(row -> row.getTenantId()).containsOnly("tenant-a");
    }
  }

  @Test
  @DisplayName("开户批次投影仅供原操作人和目标租户查询")
  void shouldMapProvisioningOperationWithActorAndTenantScope() {
    try (SqlSession session = sessionFactory.openSession()) {
      ConsoleUserBatchOperationMapper mapper = session.getMapper(ConsoleUserBatchOperationMapper.class);
      var row = mapper.selectByOperationId(OPERATION_ID, "operator-a", "tenant-a");
      assertThat(row.getRequestId()).isEqualTo(REQUEST_ID);
      assertThat(row.getAccountCount()).isEqualTo(2);
      assertThat(mapper.selectByRequestId(REQUEST_ID, "operator-a", "tenant-b").getOperationId())
          .isEqualTo(OPERATION_ID);
      assertThat(mapper.selectByOperationId(OPERATION_ID, "operator-b", "tenant-a")).isNull();
      assertThat(mapper.selectByOperationId(OPERATION_ID, "operator-a", "tenant-c")).isNull();
    }
  }
}
