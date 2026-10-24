package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.http.OutboundHttpTransport;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionVersionMapper;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionInsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionUpdateParam;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowDefinitionQuery;
import io.github.pinpols.batch.console.support.http.OkHttpConsoleExternalHttpTransport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** 使用真实 Postgres、Kafka、对象存储的控制台 API 测试；Flyway 在平台库上执行编排器 {@code db/migration} 迁移。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("控制台 API 集成基座: 上下文可启动,外部 HTTP 通道装配为既定实现")
class BatchConsoleApiApplicationIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  ApplicationContext applicationContext;

  @Autowired
  WorkflowDefinitionMapper workflowDefinitionMapper;

  @Autowired
  WorkflowDefinitionVersionMapper workflowDefinitionVersionMapper;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @Autowired
  DataSource dataSource;

  @Test
  @DisplayName("触发回执查询按租户、Workflow和原键隔离，支持别名及停用后核对")
  void shouldResolveOriginalTriggerReceipt_whenTenantAndWorkflowMatch() throws Exception {
    WorkflowTriggerReceiptFixture.verify(dataSource);
  }

  @Test
  @DisplayName("应用启动: 上下文加载成功,外部 HTTP 通道装配为既定实现")
  void shouldLoadContextAndWireHttpTransport_whenApplicationStarts() {
    assertThat(applicationContext).isNotNull();
    assertThat(applicationContext.getBean(OutboundHttpTransport.class))
        .isInstanceOf(OkHttpConsoleExternalHttpTransport.class);
  }

  @Test
  @DisplayName("Flyway 为热表和冷归档节点运行表创建跳过原因列")
  void shouldMigrateWorkflowNodeSkipReasonColumns() {
    assertThat(skipReasonColumns("batch", "workflow_node_run"))
        .containsExactlyInAnyOrder("skip_reason_code", "skip_reason");
    assertThat(skipReasonColumns("archive", "workflow_node_run_archive"))
        .containsExactlyInAnyOrder("skip_reason_code", "skip_reason");
  }

  private List<String> skipReasonColumns(String schema, String table) {
    String skipReasonColumnsSql = """
        select column_name from information_schema.columns
        where table_schema = ? and table_name = ?
          and column_name in ('skip_reason_code', 'skip_reason')
        """;
    return jdbcTemplate.queryForList(skipReasonColumnsSql, String.class, schema, table);
  }

  @Test
  @DisplayName("按 Job 编码筛选关联工作流的查询可在 PostgreSQL 执行")
  void shouldExecuteRelatedJobWorkflowQuery() {
    WorkflowDefinitionQuery query = WorkflowDefinitionQuery.builder()
        .tenantId("tenant-with-no-jobs")
        .relatedJobCode("IMPORT_ORDERS")
        .pageRequest(new PageRequest(1, 10))
        .build();

    assertThat(workflowDefinitionMapper.selectByQuery(query)).isEmpty();
    assertThat(workflowDefinitionMapper.countByQuery(query)).isZero();
    assertThat(workflowDefinitionMapper.selectBySingleJobDefinitionId("tenant-with-no-jobs", -1L))
        .isNull();

    for (boolean singleJobWorkflow : new boolean[] {true, false}) {
      WorkflowDefinitionQuery categoryQuery = WorkflowDefinitionQuery.builder()
          .tenantId("tenant-with-no-jobs")
          .singleJobWorkflow(singleJobWorkflow)
          .build();
      assertThat(workflowDefinitionMapper.selectByQuery(categoryQuery)).isEmpty();
      assertThat(workflowDefinitionMapper.countByQuery(categoryQuery)).isZero();
    }
  }

  @Test
  @DisplayName("Workflow 配置导入 Mapper 将调度字段写入 PostgreSQL 定义")
  void shouldPersistWorkflowScheduleConfiguration() {
    String tenantId = "config-import-it";
    String workflowCode = "WF-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    WorkflowDefinitionUpsertParam param = new WorkflowDefinitionUpsertParam();
    param.setTenantId(tenantId);
    param.setWorkflowCode(workflowCode);
    param.setWorkflowName("Config import schedule");
    param.setWorkflowType("DAG");
    param.setVersion(1);
    param.setEnabled(true);
    param.setScheduleEnabled(true);
    param.setScheduleType("CRON");
    param.setScheduleExpr("0 0 2 * * *");
    param.setTimezone("Asia/Shanghai");
    param.setTriggerMode("SCHEDULED");
    param.setCreatedBy("integration-test");
    param.setUpdatedBy("integration-test");

    workflowDefinitionMapper.upsertWorkflowDefinition(param);
    try {
      var saved = workflowDefinitionMapper.selectByUniqueKey(tenantId, workflowCode, 1);
      assertThat(saved).isNotNull();
      assertThat(saved.getScheduleEnabled()).isTrue();
      assertThat(saved.getScheduleType()).isEqualTo("CRON");
      assertThat(saved.getScheduleExpr()).isEqualTo("0 0 2 * * *");
      assertThat(saved.getTimezone()).isEqualTo("Asia/Shanghai");
      assertThat(saved.getTriggerMode()).isEqualTo("SCHEDULED");

      assertThat(workflowDefinitionMapper.updateAndBumpVersion(
              new WorkflowDefinitionVersionUpdateParam(
                  tenantId,
                  saved.getId(),
                  1,
                  "Config import schedule updated",
                  "DAG",
                  true,
                  false,
                  "MANUAL",
                  null,
                  "Asia/Shanghai",
                  "MANUAL",
                  null,
                  null)))
          .isEqualTo(1);
      var updated = workflowDefinitionMapper.selectByUniqueKey(tenantId, workflowCode, 2);
      assertThat(updated).isNotNull();
      assertThat(updated.getScheduleEnabled()).isFalse();

      WorkflowDefinitionVersionInsertParam snapshot = new WorkflowDefinitionVersionInsertParam();
      snapshot.setTenantId(tenantId);
      snapshot.setWorkflowDefinitionId(saved.getId());
      snapshot.setWorkflowCode(workflowCode);
      snapshot.setVersion(2);
      snapshot.setWorkflowName("Config import schedule updated");
      snapshot.setWorkflowType("DAG");
      snapshot.setEnabled(true);
      snapshot.setScheduleEnabled(false);
      snapshot.setScheduleType("MANUAL");
      snapshot.setTimezone("Asia/Shanghai");
      snapshot.setTriggerMode("MANUAL");
      snapshot.setNodesJson("[]");
      snapshot.setEdgesJson("[]");
      snapshot.setSavedBy("integration-test");
      assertThat(workflowDefinitionVersionMapper.insertVersionSnapshot(snapshot))
          .isEqualTo(1);
      assertThat(workflowDefinitionVersionMapper
              .findByDefinitionIdAndVersion(tenantId, saved.getId(), 2)
              .getScheduleEnabled())
          .isFalse();
    } finally {
      var saved = workflowDefinitionMapper.selectByUniqueKey(tenantId, workflowCode, 1);
      if (saved != null) {
        workflowDefinitionMapper.deleteByTenantAndId(tenantId, saved.getId());
      }
    }
  }
}
