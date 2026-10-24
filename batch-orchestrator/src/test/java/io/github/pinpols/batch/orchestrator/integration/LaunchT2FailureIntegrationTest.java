package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.Month;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 集成测试：{@link WorkflowRunLaunchService#launch} 中 T1/T2 事务拆分。
 *
 * <p>场景：T2（{@link PartitionDispatchService#dispatch}）在 T1 （{@code prepareJobInstance}）已提交之后抛出异常。验证：
 *
 * <ol>
 *   <li>T1 写入的 {@code job_instance} 行在 T2 失败后依然存在。
 *   <li>不存在 {@code job_partition} 行（T2 已完整回滚）。
 *   <li>使用相同 {@code requestId} 的后续 {@code launch()} 命中去重路径， 返回已创建的实例而不再调用 T2。
 * </ol>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("作业启动两阶段事务拆分的失败语义验证,节点派发失败后保留 Workflow Run 且同请求重试恢复")
class LaunchT2FailureIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, Month.JANUARY, 15);

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @MockitoBean
  private PartitionDispatchService partitionDispatchService;

  @Test
  @DisplayName("派发阶段抛异常时异常向外传播,失败请求可重试,不留下部分 Job 执行数据且只创建一个实例")
  void shouldKeepCommittedInstanceAndDedup_whenDispatchPhaseFails() {
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.MANUAL, BIZ_DATE);

    Mockito.doThrow(new RuntimeException("simulated T2 failure"))
        .doNothing()
        .when(partitionDispatchService)
        .dispatch(any());

    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.MANUAL)
        .requestId(seed.requestId())
        .traceId("trace-t2-failure-test")
        .params(Map.of())
        .build();

    // 第一次调用：T2 失败 —— 异常从 Workflow launch 传播出去
    assertThatThrownBy(() -> launchService.launch(request))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("simulated T2 failure");

    String jobInstanceCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
        """;
    // 用于外部 EVENT 启动认证的最小只读投影，验证租户 API key。
    Long jobInstanceCountAfterDispatchFailure = jdbcTemplate.queryForObject(
        jobInstanceCountSql, Long.class, TENANT, seed.jobCode());
    assertThat(jobInstanceCountAfterDispatchFailure)
        .as("failed node launch should roll back the Job instance")
        .isZero();
    String workflowRunCountSql = """
        select count(*) from batch.workflow_run
        where tenant_id = ? and workflow_definition_id = ?
        """;
    Long workflowRunCount = jdbcTemplate.queryForObject(
        workflowRunCountSql, Long.class, TENANT, seed.workflowDefinitionId());
    assertThat(workflowRunCount)
        .as("the accepted Workflow run remains the retry identity")
        .isEqualTo(1L);

    // 使用相同 requestId 的第二次调用：走去重路径 —— T2 不会再被调用
    LaunchResponse retryResponse = launchService.launch(request);
    assertThat(retryResponse.instanceNo())
        .as("retry should resume the Workflow run created before node dispatch")
        .isNotBlank();
    String retryJobInstanceIdSql = """
        select node_run.job_instance_id from batch.workflow_node_run node_run
        join batch.workflow_run run on run.id = node_run.workflow_run_id
        where run.tenant_id = ? and run.id = ? and node_run.node_type = 'JOB'
        """;
    Long retriedWorkflowRunId = Long.valueOf(retryResponse.instanceNo());
    Long retryJobInstanceId = jdbcTemplate.queryForObject(
        retryJobInstanceIdSql, Long.class, TENANT, retriedWorkflowRunId);
    assertThat(retryJobInstanceId).isNotNull();
    Long jobInstanceCountAfterRetry = jdbcTemplate.queryForObject(
        jobInstanceCountSql, Long.class, TENANT, seed.jobCode());
    assertThat(jobInstanceCountAfterRetry)
        .isEqualTo(1L);
  }
}
