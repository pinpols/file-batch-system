package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
import java.time.Instant;
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
@DisplayName("Workflow 节点长错误消息在真实 PostgreSQL 中被限制到列宽")
class WorkflowNodeRunErrorMessageIntegrationTest {

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      TestPostgresContainers.create("workflow_node_error_message_it");

  private static SingleConnectionDataSource dataSource;
  private static JdbcTemplate jdbcTemplate;
  private static SqlSessionFactory sqlSessionFactory;

  @BeforeAll
  static void setUp() throws Exception {
    dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    jdbcTemplate = new JdbcTemplate(dataSource);
    String createBatchSchemaSql = """
        create schema batch
    """;
    jdbcTemplate.execute(createBatchSchemaSql);
    String createWorkflowNodeRunTableSql = """
        create table batch.workflow_node_run (
          id bigint primary key,
          node_status varchar(32) not null,
          error_code varchar(128),
          error_message varchar(1024),
          error_key varchar(128),
          error_args jsonb,
          duration_ms bigint,
          finished_at timestamptz,
          output jsonb
        )
        """;
    jdbcTemplate.execute(createWorkflowNodeRunTableSql);

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

  @Test
  @DisplayName("记录长失败原因时仍提交 FAILED 状态和有界错误文本")
  void shouldPersistFailure_whenErrorMessageExceedsColumnLimit() {
    String longMessage = "root-cause-".repeat(200);
    String insertRunningWorkflowNodeSql = """
        insert into batch.workflow_node_run(id, node_status) values (1, 'RUNNING')
    """;
    jdbcTemplate.update(insertRunningWorkflowNodeSql);

    UpdateNodeRunStatusParam failure = UpdateNodeRunStatusParam.builder()
        .id(1L)
        .nodeStatus("FAILED")
        .errorCode("WORKER_EXECUTION_ERROR")
        .errorMessage(longMessage)
        .durationMs(12L)
        .finishedAt(Instant.now())
        .build();

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      assertThat(session.getMapper(WorkflowNodeRunMapper.class).updateStatus(failure))
          .isEqualTo(1);
    }

    String workflowNodeStatusSql = """
        select node_status from batch.workflow_node_run where id = 1
    """;
    String persistedNodeStatus = jdbcTemplate.queryForObject(workflowNodeStatusSql, String.class);
    assertThat(persistedNodeStatus).isEqualTo("FAILED");
    String workflowNodeErrorMessageSql = """
        select error_message from batch.workflow_node_run where id = 1
    """;
    String persistedErrorMessage =
        jdbcTemplate.queryForObject(workflowNodeErrorMessageSql, String.class);
    String boundedErrorMessage = longMessage.substring(0, 1024);
    assertThat(persistedErrorMessage).isEqualTo(boundedErrorMessage);
  }
}
