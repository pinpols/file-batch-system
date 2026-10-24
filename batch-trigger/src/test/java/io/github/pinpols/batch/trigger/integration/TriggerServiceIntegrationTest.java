package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.web.request.TriggerLaunchRequest;
import java.time.LocalDate;
import java.time.Month;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration",
      "batch.orchestrator.base-url=http://localhost:8082",
      // 关 QuartzMetricsConfiguration：本测试用 mock(Scheduler) 而非真 Quartz，
      // 否则 mock 的 getListenerManager() 返回 null 在 @PostConstruct 触发 NPE
      "batch.trigger.quartz-metrics.enabled=false",
      // ADR-010: 默认 true，test 1 走异步路径写 outbox；test 2 的 approvePendingCatchUp 仍直接调 adapter
    })
@Import(TriggerServiceIntegrationTest.TestConfig.class)
@DisplayName("Trigger 启动链路集成:同一幂等键重复提交只落一条 ACCEPTED 请求,审批补跑同事务写出箱事件而非直连编排服务")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class TriggerServiceIntegrationTest extends AbstractIntegrationTest {

  private final TriggerService triggerService;

  private final JdbcTemplate jdbcTemplate;

  private final TriggerDefinitionLoader triggerDefinitionLoader;

  TriggerServiceIntegrationTest(
      TriggerService triggerService,
      JdbcTemplate jdbcTemplate,
      TriggerDefinitionLoader triggerDefinitionLoader) {
    this.triggerService = triggerService;
    this.jdbcTemplate = jdbcTemplate;
    this.triggerDefinitionLoader = triggerDefinitionLoader;
  }

  @MockitoBean
  private RestClient orchestratorRestClient;

  @BeforeEach
  void cleanUp() {
    jdbcTemplate.update("delete from batch.trigger_outbox_event");
    jdbcTemplate.update("delete from batch.trigger_request");
  }

  @Test
  @DisplayName("同幂等键的两次启动只落一条 ACCEPTED 请求与一条 outbox 事件,且按 requestId/traceId 回查状态一致")
  void shouldPersistAcceptedRequestAndDeduplicateRepeatedLaunch() {
    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId("t1");
    request.setJobCode("IMPORT_JOB");
    request.setBizDate(LocalDate.of(2026, Month.MARCH, 27));
    request.setTriggerType(TriggerType.API);
    request.setParams(Map.of("source", "integration"));

    TriggerLaunchCommand command =
        new TriggerLaunchCommand(request, "idem-001", "req-001", "trace-001");

    triggerService.launch(command);
    triggerService.launch(command);

    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from batch.trigger_request where tenant_id = ? and dedup_key = ?",
        Integer.class,
        "t1",
        "idem-001");
    String status = jdbcTemplate.queryForObject(
        "select request_status from batch.trigger_request where tenant_id = ? and request_id ="
            + " ?",
        String.class,
        "t1",
        "req-001");

    assertThat(count).isEqualTo(1);
    assertThat(status).isEqualTo("ACCEPTED");
    // ADR-010: 异步路径写 trigger_outbox_event，不调 adapter
    Integer outboxCount = jdbcTemplate.queryForObject(
        "select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id ="
            + " ?",
        Integer.class,
        "t1",
        "req-001");
    assertThat(outboxCount).isEqualTo(1);

    var launchStatus = triggerService.findLaunchStatus("t1", "idem-001");
    assertThat(launchStatus.requestId()).isEqualTo("req-001");
    assertThat(launchStatus.traceId()).isEqualTo("trace-001");
    assertThat(launchStatus.requestStatus()).isEqualTo("ACCEPTED");
    assertThat(launchStatus.relatedJobInstanceId()).isNull();
    assertThat(launchStatus.instanceStatus()).isNull();
    assertThat(launchStatus.updatedAt()).isNotNull();
  }

  @Test
  @DisplayName("Workflow 触发保存独立 Workflow 身份且不伪造 JobCode")
  void shouldPersistWorkflowAsExplicitLaunchTarget() {
    String workflowCode = "direct-workflow-" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(
        "insert into batch.workflow_definition "
            + "(tenant_id, workflow_code, workflow_name, workflow_type, version, enabled) "
            + "values ('t1', ?, ?, 'DAG', 1, true)",
        workflowCode,
        workflowCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        "select id from batch.workflow_definition where tenant_id = 't1' and workflow_code = ?",
        Long.class,
        workflowCode);
    try {
      TriggerLaunchRequest request = new TriggerLaunchRequest();
      request.setTenantId("t1");
      request.setWorkflowDefinitionId(workflowDefinitionId);
      request.setBizDate(LocalDate.of(2026, Month.MARCH, 27));
      request.setTriggerType(TriggerType.API);

      triggerService.launch(
          new TriggerLaunchCommand(request, "idem-direct-workflow", "req-direct-wf", "trace-wf"));

      Map<String, Object> target = jdbcTemplate.queryForMap(
          "select job_code, workflow_definition_id from batch.trigger_request "
              + "where tenant_id = 't1' and request_id = 'req-direct-wf'");
      assertThat(target.get("job_code")).isNull();
      assertThat(((Number) target.get("workflow_definition_id")).longValue())
          .isEqualTo(workflowDefinitionId);
    } finally {
      jdbcTemplate.update(
          "delete from batch.trigger_request where tenant_id = 't1' and request_id = 'req-direct-wf'");
      jdbcTemplate.update(
          "delete from batch.workflow_definition where tenant_id = 't1' and id = ?",
          workflowDefinitionId);
    }
  }

  @Test
  @DisplayName("Trigger 启停只更新 Workflow 调度开关，不改变 Job 执行启用状态或版本")
  void shouldToggleWorkflowScheduleWithoutChangingJobExecutionState() {
    String jobCode = "version-" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(
        "insert into batch.job_definition "
            + "(tenant_id, job_code, job_name, job_type, schedule_type, timezone, enabled) "
            + "values (?, ?, ?, 'GENERAL', 'MANUAL', 'UTC', true)",
        "system",
        jobCode,
        jobCode);
    Long jobDefinitionId = jdbcTemplate.queryForObject(
        "select id from batch.job_definition where tenant_id = ? and job_code = ?",
        Long.class,
        "system",
        jobCode);
    String workflowCode = "SINGLE-" + jobCode;
    jdbcTemplate.update(
        "insert into batch.workflow_definition "
            + "(tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,"
            + " single_job_definition_id) values ('system', ?, ?, 'DAG', 1, true, ?)",
        workflowCode,
        workflowCode,
        jobDefinitionId);
    try {
      Integer initialVersion = jdbcTemplate.queryForObject(
          "select version from batch.job_definition where tenant_id = ? and job_code = ?",
          Integer.class,
          "system",
          jobCode);

      int updated = triggerDefinitionLoader.setScheduleEnabled("system", jobCode, false);

      Map<String, Object> job = jdbcTemplate.queryForMap(
          "select version, enabled from batch.job_definition where tenant_id = ? and job_code = ?",
          "system",
          jobCode);
      Boolean scheduleEnabled = jdbcTemplate.queryForObject(
          "select schedule_enabled from batch.workflow_definition where tenant_id = 'system'"
              + " and workflow_code = ?",
          Boolean.class,
          workflowCode);
      assertThat(updated).isEqualTo(1);
      assertThat(job.get("version")).isEqualTo(initialVersion);
      assertThat(job.get("enabled")).isEqualTo(true);
      assertThat(scheduleEnabled).isFalse();
    } finally {
      jdbcTemplate.update(
          "delete from batch.workflow_definition where tenant_id = ? and workflow_code = ?",
          "system",
          workflowCode);
      jdbcTemplate.update(
          "delete from batch.job_definition where tenant_id = ? and job_code = ?",
          "system",
          jobCode);
    }
  }

  @Test
  @DisplayName("定时定义从受管 Workflow 读取调度字段，并保留关联 Job 的执行目标")
  void shouldLoadScheduledFieldsFromManagedWorkflow() {
    String jobCode = "workflow-schedule-" + UUID.randomUUID().toString().replace("-", "");
    String workflowCode = "JOB-WF-" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(
        "insert into batch.job_definition "
            + "(tenant_id, job_code, job_name, job_type, schedule_type, schedule_expr, timezone,"
            + " trigger_mode, enabled) values (?, ?, ?, 'IMPORT', 'MANUAL', null, 'UTC', 'MANUAL',"
            + " true)",
        "system",
        jobCode,
        jobCode);
    Long jobDefinitionId = jdbcTemplate.queryForObject(
        "select id from batch.job_definition where tenant_id = ? and job_code = ?",
        Long.class,
        "system",
        jobCode);
    jdbcTemplate.update("""
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
            single_job_definition_id, schedule_type, schedule_expr, timezone, trigger_mode,
            calendar_code, depends_on_job_code
        ) values (?, ?, ?, 'DAG', 7, true, ?, 'CRON', '0 15 2 * * ?', 'Asia/Shanghai',
                  'SCHEDULED', 'BUSINESS_DAY', 'UPSTREAM')
        """, "system", workflowCode, workflowCode, jobDefinitionId);
    try {
      var descriptor = triggerDefinitionLoader.loadByJobCode("system", jobCode);

      assertThat(descriptor).isNotNull().satisfies(value -> {
        assertThat(value.getJobCode()).isEqualTo(jobCode);
        assertThat(value.getJobDefinitionId()).isEqualTo(jobDefinitionId);
        assertThat(value.getWorkflowCode()).isEqualTo(workflowCode);
        assertThat(value.getWorkflowVersion()).isEqualTo(7);
        assertThat(value.getScheduleType()).isEqualTo("CRON");
        assertThat(value.getScheduleExpression()).isEqualTo("0 15 2 * * ?");
        assertThat(value.getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(value.getTriggerMode()).isEqualTo("SCHEDULED");
        assertThat(value.getCalendarCode()).isEqualTo("BUSINESS_DAY");
        assertThat(value.getDependsOnJobCode()).isEqualTo("UPSTREAM");
        assertThat(value.isEnabled()).isTrue();
      });
      assertThat(triggerDefinitionLoader.loadAll())
          .anySatisfy(value -> assertThat(value.getWorkflowCode()).isEqualTo(workflowCode));
    } finally {
      jdbcTemplate.update(
          "delete from batch.workflow_definition where tenant_id = ? and workflow_code = ?",
          "system",
          workflowCode);
      jdbcTemplate.update(
          "delete from batch.job_definition where tenant_id = ? and job_code = ?",
          "system",
          jobCode);
    }
  }

  @Test
  @DisplayName("组合 Workflow 可按自身身份注册定时描述，不要求关联包装 Job")
  void shouldLoadScheduledComposedWorkflowWithoutJobDefinition() {
    String workflowCode = "composed-schedule-" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(
        "insert into batch.workflow_definition "
            + "(tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,"
            + " schedule_enabled, schedule_type, schedule_expr, timezone, trigger_mode) "
            + "values ('system', ?, ?, 'DAG', 1, true, true, 'CRON', '0 0 3 * * ?', 'UTC',"
            + " 'SCHEDULED')",
        workflowCode,
        workflowCode);
    try {
      var descriptor = triggerDefinitionLoader.loadByWorkflowCode("system", workflowCode);

      assertThat(descriptor).isNotNull().satisfies(value -> {
        assertThat(value.getWorkflowCode()).isEqualTo(workflowCode);
        assertThat(value.getWorkflowDefinitionId()).isNotNull();
        assertThat(value.getJobCode()).isNull();
        assertThat(value.getScheduleType()).isEqualTo("CRON");
        assertThat(value.isEnabled()).isTrue();
      });
      assertThat(triggerDefinitionLoader.loadAll())
          .anySatisfy(value -> assertThat(value.getWorkflowCode()).isEqualTo(workflowCode));

      int updated =
          triggerDefinitionLoader.setWorkflowScheduleEnabled("system", workflowCode, false);
      assertThat(updated).isEqualTo(1);
      assertThat(
              triggerDefinitionLoader.loadByWorkflowCode("system", workflowCode).isEnabled())
          .isFalse();
    } finally {
      jdbcTemplate.update(
          "delete from batch.workflow_definition where tenant_id = 'system' and workflow_code = ?",
          workflowCode);
    }
  }

  @Test
  @DisplayName("Trigger 只加载同日依赖执行早期 readiness gate，旧标量依赖仍兼容")
  void shouldLoadNormalizedDependenciesAndPreserveLegacyScalarWrites() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String upstreamA = "UP_A_" + suffix;
    String upstreamB = "UP_B_" + suffix;
    String downstream = "DOWN_" + suffix;
    try {
      insertScheduledDefinition(upstreamA, null);
      insertScheduledDefinition(upstreamB, null);
      insertScheduledDefinition(downstream, upstreamA);
      jdbcTemplate.update(
          "insert into batch.job_dependency"
              + " (tenant_id, downstream_job_code, upstream_job_code, dependency_order,"
              + " biz_date_alignment) values ('t1', ?, ?, 1, 'PREVIOUS_BIZ_DAY')",
          downstream,
          upstreamB);

      var descriptor = triggerDefinitionLoader.loadByJobCode("t1", downstream);
      assertThat(descriptor.getDependsOnJobCode()).isEqualTo(upstreamA);
      // Trigger 的早期 readiness gate 只做同业务日检查；跨日依赖留给 Orchestrator。
      assertThat(descriptor.getDependsOnJobCodes()).containsExactly(upstreamA);

      jdbcTemplate.update(
          "update batch.job_dependency set biz_date_alignment = 'PREVIOUS_BIZ_DAY' "
              + "where tenant_id = 't1' and downstream_job_code = ? and upstream_job_code = ?",
          downstream,
          upstreamA);
      var previousDayOnly = triggerDefinitionLoader.loadByJobCode("t1", downstream);
      assertThat(previousDayOnly.getDependsOnJobCode()).isEqualTo(upstreamA);
      assertThat(previousDayOnly.getDependsOnJobCodes()).isEmpty();

      jdbcTemplate.update(
          "delete from batch.job_dependency where tenant_id = 't1' and downstream_job_code = ?",
          downstream);

      jdbcTemplate.update(
          "update batch.job_definition set depends_on_job_code = ?"
              + " where tenant_id = 't1' and job_code = ?",
          upstreamB,
          downstream);
      var legacyUpdated = triggerDefinitionLoader.loadByJobCode("t1", downstream);
      assertThat(legacyUpdated.getDependsOnJobCode()).isEqualTo(upstreamB);
      assertThat(legacyUpdated.getDependsOnJobCodes()).containsExactly(upstreamB);
    } finally {
      jdbcTemplate.update(
          "delete from batch.job_definition where tenant_id = 't1' and job_code in (?, ?, ?)",
          downstream,
          upstreamA,
          upstreamB);
    }
  }

  private void insertScheduledDefinition(String jobCode, String dependsOnJobCode) {
    jdbcTemplate.update(
        "insert into batch.job_definition"
            + " (tenant_id, job_code, job_name, job_type, schedule_type, schedule_expr, timezone,"
            + " trigger_mode, depends_on_job_code) values ('t1', ?, ?, 'GENERAL', 'CRON',"
            + " '0 0 1 * * ?', 'Asia/Shanghai', 'SCHEDULED', ?)",
        jobCode,
        jobCode,
        dependsOnJobCode);
  }

  @Test
  @DisplayName("审批补跑请求后本地标记 LAUNCHED 并同事务写出一条 outbox 事件,且不通过编排 RestClient 发起 POST")
  void shouldApprovePendingCatchUpAndMarkRowLaunched() {
    jdbcTemplate.update(
        """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_status, trace_id
        ) values (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        "t1",
        "pending-001",
        TriggerType.CATCH_UP.code(),
        "EXPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 27),
        "dedup-pending-001",
        "ACCEPTED",
        "trace-pending-001");
    PendingCatchUpApprovalCommand command = new PendingCatchUpApprovalCommand();
    command.setTenantId("t1");
    command.setRequestId("pending-001");
    command.setReason("manual approval");

    LaunchResponse response = triggerService.approvePendingCatchUp(command);

    String status = jdbcTemplate.queryForObject(
        "select request_status from batch.trigger_request where tenant_id = ? and request_id ="
            + " ?",
        String.class,
        "t1",
        "pending-001");

    // ADR-010：审批走 outbox。trigger_request 标 LAUNCHED 后立刻返回，trigger_outbox_event 同事务写入数据库。
    assertThat(response.instanceNo()).isEqualTo("pending-001");
    assertThat(response.traceId()).isEqualTo("trace-pending-001");
    assertThat(status).isEqualTo("LAUNCHED");
    Long outboxCount = jdbcTemplate.queryForObject(
        "select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id ="
            + " ?",
        Long.class,
        "t1",
        "pending-001");
    assertThat(outboxCount).isEqualTo(1L);
    verify(orchestratorRestClient, never()).post();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    Scheduler scheduler() {
      return mock(Scheduler.class);
    }
  }
}
