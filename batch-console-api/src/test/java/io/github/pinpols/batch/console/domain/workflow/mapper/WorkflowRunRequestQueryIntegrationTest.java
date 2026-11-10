package io.github.pinpols.batch.console.domain.workflow.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowRunQuery;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.util.List;
import java.util.Map;
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

@DisplayName("工作流运行筛选与租户隔离")
class WorkflowRunRequestQueryIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();
  private static SqlSessionFactory sessions;

  @BeforeAll
  static void setUp() throws Exception {
    POSTGRES.start();
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String fixtures = """
        create schema batch;
        create table batch.trigger_request (
          id bigint primary key, tenant_id text not null, request_id text not null,
          unique (tenant_id, request_id));
        create table batch.workflow_definition (
          id bigint primary key, tenant_id text not null, workflow_code text not null,
          version integer not null, unique (tenant_id, workflow_code, version));
        create table batch.workflow_run (
          id bigint primary key, tenant_id text not null, trigger_request_id bigint,
          workflow_definition_id bigint, related_job_instance_id bigint, biz_date date,
          run_status text, current_node_code text, trace_id text, started_at timestamptz,
          finished_at timestamptz, created_at timestamptz, updated_at timestamptz, dry_run boolean);
        insert into batch.trigger_request values
          (1, 'tenant-a', 'same-request'), (2, 'tenant-b', 'same-request'),
          (3, 'tenant-a', 'waiting-request'), (4, 'tenant-e', 'shared-request'),
          (5, 'tenant-e', 'other-request'), (6, 'tenant-f', 'shared-request');
        insert into batch.workflow_run (id, tenant_id, trigger_request_id, run_status) values
          (11, 'tenant-a', 1, 'SUCCESS'), (12, 'tenant-b', 2, 'FAILED');
        insert into batch.workflow_definition (id, tenant_id, workflow_code, version) values
          (101, 'tenant-c', 'WF', 1), (102, 'tenant-c', 'WF', 2),
          (103, 'tenant-c', 'OTHER', 1), (104, 'tenant-d', 'WF', 1),
          (105, 'tenant-e', 'WF', 1), (106, 'tenant-e', 'WF', 2),
          (107, 'tenant-e', 'OTHER', 1), (108, 'tenant-f', 'WF', 1);
        insert into batch.workflow_run (id, tenant_id, workflow_definition_id, run_status) values
          (13, 'tenant-c', 101, 'FAILED'), (14, 'tenant-c', 102, 'TERMINATED'),
          (15, 'tenant-c', 103, 'FAILED'), (16, 'tenant-d', 104, 'FAILED');
        insert into batch.workflow_run
          (id, tenant_id, trigger_request_id, workflow_definition_id, run_status) values
          (17, 'tenant-e', 4, 105, 'FAILED'), (18, 'tenant-e', 4, 106, 'SUCCESS'),
          (19, 'tenant-e', 5, 105, 'RUNNING'), (20, 'tenant-e', 4, 107, 'SUCCESS'),
          (21, 'tenant-f', 6, 108, 'SUCCESS');
        """;
    jdbc.execute(fixtures);
    Configuration configuration = new Configuration(
        new Environment("workflow-request-it", new JdbcTransactionFactory(), dataSource));
    for (String resource : List.of("mapper/CommonFragments.xml", "mapper/WorkflowRunMapper.xml")) {
      try (var input = Resources.getResourceAsStream(resource)) {
        new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments())
            .parse();
      }
    }
    sessions = new SqlSessionFactoryBuilder().build(configuration);
  }

  @AfterAll
  static void tearDown() {
    POSTGRES.stop();
  }

  @Test
  @DisplayName("请求标识筛选仅返回当前租户的运行及对应总数")
  void requestFilter_shouldMatchTenantAndCount() {
    try (SqlSession session = sessions.openSession()) {
      WorkflowRunMapper mapper = session.getMapper(WorkflowRunMapper.class);
      WorkflowRunQuery tenantA = query("tenant-a", "same-request");
      WorkflowRunQuery tenantB = query("tenant-b", "same-request");
      assertThat(mapper.selectByQuery(tenantA)).extracting("id").containsExactly(11L);
      assertThat(mapper.countByQuery(tenantA)).isEqualTo(1);
      assertThat(mapper.selectByQuery(tenantB)).extracting("id").containsExactly(12L);
      assertThat(mapper.countByQuery(tenantB)).isEqualTo(1);
      WorkflowRunQuery waiting = query("tenant-a", "waiting-request");
      assertThat(mapper.selectByQuery(waiting)).isEmpty();
      assertThat(mapper.countByQuery(waiting)).isZero();
      WorkflowRunQuery legacy = query("tenant-a", null);
      assertThat(mapper.selectByQuery(legacy)).extracting("id").containsExactly(11L);
      assertThat(mapper.countByQuery(legacy)).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("工作流编码筛选包含当前租户内的所有定义版本")
  void workflowCodeFilter_shouldIncludeAllVersionsWithinTenant() {
    try (SqlSession session = sessions.openSession()) {
      Map<String, Object> query = Map.of(
          "tenantId", "tenant-c", "workflowCode", "WF", "pageRequest", new PageRequest(1, 20));
      String namespace = WorkflowRunMapper.class.getName();
      List<WorkflowRunEntity> runs = session.selectList(namespace + ".selectByQuery", query);
      Long total = session.selectOne(namespace + ".countByQuery", query);
      assertThat(runs).extracting("id").containsExactly(14L, 13L);
      assertThat(total).isEqualTo(2L);
      Map<String, Object> selectedVersion = Map.of(
          "tenantId",
          "tenant-c",
          "workflowCode",
          "WF",
          "workflowDefinitionId",
          101L,
          "pageRequest",
          new PageRequest(1, 20));
      List<WorkflowRunEntity> selectedRuns =
          session.selectList(namespace + ".selectByQuery", selectedVersion);
      Long selectedTotal = session.selectOne(namespace + ".countByQuery", selectedVersion);
      assertThat(selectedRuns).extracting("id").containsExactly(13L);
      assertThat(selectedTotal).isEqualTo(1L);
    }
  }

  @Test
  @DisplayName("工作流编码和请求标识组合筛选遵守租户与分页条件")
  void workflowCodeAndRequestId_shouldRespectTenantAndPagination() {
    try (SqlSession session = sessions.openSession()) {
      WorkflowRunMapper mapper = session.getMapper(WorkflowRunMapper.class);
      WorkflowRunQuery firstPage = WorkflowRunQuery.builder()
          .tenantId("tenant-e")
          .workflowCode("WF")
          .requestId("shared-request")
          .pageRequest(new PageRequest(1, 1))
          .build();
      WorkflowRunQuery secondPage = WorkflowRunQuery.builder()
          .tenantId("tenant-e")
          .workflowCode("WF")
          .requestId("shared-request")
          .pageRequest(new PageRequest(2, 1))
          .build();
      WorkflowRunQuery selectedVersion = WorkflowRunQuery.builder()
          .tenantId("tenant-e")
          .workflowDefinitionId(105L)
          .workflowCode("WF")
          .requestId("shared-request")
          .pageRequest(new PageRequest(1, 20))
          .build();

      assertThat(mapper.selectByQuery(firstPage)).extracting("id").containsExactly(18L);
      assertThat(mapper.selectByQuery(secondPage)).extracting("id").containsExactly(17L);
      assertThat(mapper.countByQuery(firstPage)).isEqualTo(2);
      assertThat(mapper.countByQuery(secondPage)).isEqualTo(2);
      assertThat(mapper.selectByQuery(selectedVersion)).extracting("id").containsExactly(17L);
      assertThat(mapper.countByQuery(selectedVersion)).isEqualTo(1);
    }
  }

  private WorkflowRunQuery query(String tenantId, String requestId) {
    return WorkflowRunQuery.builder()
        .tenantId(tenantId)
        .requestId(requestId)
        .pageRequest(new PageRequest(1, 20))
        .build();
  }
}
