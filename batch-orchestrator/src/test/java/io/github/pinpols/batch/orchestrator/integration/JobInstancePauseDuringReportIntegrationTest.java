package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskAssignmentService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("真实 PostgreSQL 作业暂停期间的在途回报: 回报不撤销人工暂停")
class JobInstancePauseDuringReportIntegrationTest extends AbstractIntegrationTest {

  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 11, 14);

  private final JdbcTemplate jdbc;
  private final WorkflowRunLaunchService launchService;
  private final TaskAssignmentService taskAssignmentService;
  private final TaskOutcomeService taskOutcomeService;
  private final InstanceManagementApplicationService instanceManagement;
  private final JobTaskMapper tasks;
  private final JobPartitionMapper partitions;
  private final JobInstanceMapper instances;

  @Autowired
  JobInstancePauseDuringReportIntegrationTest(
      JdbcTemplate jdbc,
      WorkflowRunLaunchService launchService,
      TaskAssignmentService taskAssignmentService,
      TaskOutcomeService taskOutcomeService,
      InstanceManagementApplicationService instanceManagement,
      JobTaskMapper tasks,
      JobPartitionMapper partitions,
      JobInstanceMapper instances) {
    this.jdbc = jdbc;
    this.launchService = launchService;
    this.taskAssignmentService = taskAssignmentService;
    this.taskOutcomeService = taskOutcomeService;
    this.instanceManagement = instanceManagement;
    this.tasks = tasks;
    this.partitions = partitions;
    this.instances = instances;
  }

  @Test
  @Timeout(90)
  @DisplayName("暂停后在途任务回报失败并安排重试, 分区未结束时作业实例仍保持暂停")
  void shouldKeepJobPaused_whenInflightTaskFailsAndRetryIsScheduled() {
    final String updateJobDefinitionSql = """
        update batch.job_definition set retry_policy = 'FIXED', retry_max_count = 1
        where tenant_id = ? and job_code = ?
        """;
    String tenant = "pause-report-" + UUID.randomUUID().toString().substring(0, 12);
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbc, tenant, "IMPORT", "DEFAULT", TriggerType.API, BIZ_DATE);
    jdbc.update(updateJobDefinitionSql, tenant, seed.jobCode());
    LaunchResponse response = launchService.launch(LaunchRequest.builder()
        .tenantId(tenant)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-" + tenant)
        .params(Map.of())
        .build());
    Long instanceId =
        LaunchIntegrationFixture.selectDispatchedJobInstanceId(jdbc, tenant, response.instanceNo());
    List<JobTaskEntity> instanceTasks =
        tasks.selectByQuery(JobTaskQuery.ofJobInstance(tenant, instanceId));
    assertThat(instanceTasks).hasSize(1);
    JobTaskEntity task = instanceTasks.getFirst();
    assertThat(taskAssignmentService
            .assignWorker(tenant, task.getId(), seed.workerCode())
            .getTaskStatus())
        .isEqualTo("RUNNING");

    instanceManagement.pause(tenant, instanceId);
    assertThat(instances.selectById(tenant, instanceId).getInstanceStatus()).isEqualTo("PAUSED");

    String invocationId =
        partitions.selectById(tenant, task.getJobPartitionId()).getCurrentInvocationId();
    TaskOutcomeCommand failedOutcome = TaskOutcomeCommand.builder()
        .tenantId(tenant)
        .taskId(task.getId())
        .workerId(seed.workerCode())
        .success(false)
        .errorCode("SIM_SYSTEM_FAILURE")
        .errorMessage("failure while paused")
        .partitionInvocationId(invocationId)
        .build();
    taskOutcomeService.applyTaskOutcome(failedOutcome);

    assertThat(partitions.selectById(tenant, task.getJobPartitionId()).getPartitionStatus())
        .as("重试已安排, 分区尚未结束")
        .isEqualTo("RETRYING");
    assertThat(instances.selectById(tenant, instanceId).getInstanceStatus())
        .as("在途回报不能撤销人工暂停")
        .isEqualTo("PAUSED");

    instanceManagement.resume(tenant, instanceId);
    assertThat(instances.selectById(tenant, instanceId).getInstanceStatus())
        .as("只有人工恢复才回到运行")
        .isEqualTo("RUNNING");
  }
}
