package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@DisplayName("失败节点重放来源查询 PostgreSQL 集成")
class WorkflowNodeReplayTargetMapperIntegrationTest {

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      TestPostgresContainers.create("workflow_node_replay_target_it");

  private static SingleConnectionDataSource dataSource;
  private static SqlSessionFactory sqlSessionFactory;

  @BeforeAll
  static void setUp() throws Exception {
    dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    String createBatchSchemaSql = """
        create schema batch
    """;
    jdbcTemplate.execute(createBatchSchemaSql);
    String createWorkflowRunTableSql = """
        create table batch.workflow_run (
          id bigint primary key,
          tenant_id varchar(64) not null
        )
        """;
    jdbcTemplate.execute(createWorkflowRunTableSql);
    String createWorkflowNodeRunTableSql = """
        create table batch.workflow_node_run (
          id bigint primary key,
          workflow_run_id bigint not null,
          job_instance_id bigint,
          node_code varchar(128) not null,
          node_type varchar(32) not null,
          run_seq integer not null,
          node_status varchar(32) not null
        )
        """;
    jdbcTemplate.execute(createWorkflowNodeRunTableSql);
    String insertTenantAWorkflowRunSql = """
        insert into batch.workflow_run (id, tenant_id) values (71, 'tenant-a')
    """;
    jdbcTemplate.update(insertTenantAWorkflowRunSql);
    String insertTenantBWorkflowRunSql = """
        insert into batch.workflow_run (id, tenant_id) values (72, 'tenant-b')
    """;
    jdbcTemplate.update(insertTenantBWorkflowRunSql);
    String insertWorkflowNodeRunsSql = """
        insert into batch.workflow_node_run
          (id, workflow_run_id, job_instance_id, node_code, node_type, run_seq, node_status)
        values (81, 71, 91, 'IMPORT', 'JOB', 1, 'FAILED'),
               (82, 72, 92, 'IMPORT', 'JOB', 1, 'FAILED')
        """;
    jdbcTemplate.update(insertWorkflowNodeRunsSql);

    Configuration configuration =
        new Configuration(new Environment("it", new JdbcTransactionFactory(), dataSource));
    try (InputStream mapperXml =
        Resources.getResourceAsStream("mapper/WorkflowNodeRunMapper.xml")) {
      new XMLMapperBuilder(
              mapperXml,
              configuration,
              "mapper/WorkflowNodeRunMapper.xml",
              configuration.getSqlFragments())
          .parse();
    }
    sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @AfterAll
  static void tearDown() {
    if (dataSource != null) {
      dataSource.destroy();
    }
  }

  @DisplayName("仅在租户与根运行匹配时读取精确失败节点")
  @Test
  void shouldSelectExactFailedNode_whenTenantAndRootMatch() {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      WorkflowNodeRunMapper mapper = session.getMapper(WorkflowNodeRunMapper.class);

      var found = mapper.selectByTenantRunAndIdForUpdate("tenant-a", 71L, 81L);
      var crossTenant = mapper.selectByTenantRunAndIdForUpdate("tenant-a", 72L, 82L);
      var wrongRoot = mapper.selectByTenantRunAndIdForUpdate("tenant-a", 71L, 82L);

      assertThat(found).isNotNull();
      assertThat(found.getWorkflowRunId()).isEqualTo(71L);
      assertThat(found.getJobInstanceId()).isEqualTo(91L);
      assertThat(crossTenant).isNull();
      assertThat(wrongRoot).isNull();
    }
  }
}
