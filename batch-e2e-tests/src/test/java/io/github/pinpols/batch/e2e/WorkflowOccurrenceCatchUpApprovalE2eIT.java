package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication;
import io.github.pinpols.batch.e2e.apps.E2eTriggerApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.infrastructure.QuartzLaunchJob;
import io.github.pinpols.batch.trigger.infrastructure.TriggerSchedulerFacade;
import io.github.pinpols.batch.trigger.infrastructure.scheduler.TriggerReconciler;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.AopTestUtils;

/** Real Console HTTP → Trigger HTTP → central approval → Trigger outbox/Kafka → Workflow node path. */
@E2eSpringBootTest(
    classes = E2eConsoleImportApplication.class,
    webEnvironment =
        org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.console.security.default-tenant-id=t1",
      "batch.console.security.allowed-tenants=t1",
      "batch.console.orchestrator.base-url=http://127.0.0.1:${local.server.port}",
      "batch.worker.registry.fail-fast-on-startup=true",
      "batch.outbox.poll-interval-millis=200",
      "batch.outbox.min-poll-interval-millis=200",
      "batch.resource-scheduler.waiting-dispatch-interval-millis=200",
      "batch.workflow.cross-day-dep.poll-interval-millis=200",
      "batch.trigger.outbox.poll-interval-millis=200",
      "batch.trigger.kafka.send-timeout-seconds=5"
    })
@Tag("critical")
@DisplayName("计划 occurrence 补跑经真实 Console/Trigger HTTP 与集中审批后创建 WorkflowRun 和 JOB 节点实例")
@E2eBusinessSchema
@Sql(scripts = E2eTestSql.IMPORT_TEMPLATE_SEED)
class WorkflowOccurrenceCatchUpApprovalE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String OPERATOR = "e2e-catch-up-operator";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 4, 30);
  private static final Instant FIRE_TIME = Instant.parse("2026-04-30T09:30:00Z");
  private static final int TRIGGER_PORT = reservePort();
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
  private static ConfigurableApplicationContext triggerContext;

  @DynamicPropertySource
  static void registerTriggerUrl(DynamicPropertyRegistry registry) {
    registry.add("batch.console.trigger.base-url", () -> "http://127.0.0.1:" + TRIGGER_PORT);
  }

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  @Qualifier("importBusinessDataSource")
  private DataSource businessDataSource;

  @Value("${local.server.port}")
  private int consolePort;

  @AfterAll
  static void stopTriggerContext() {
    if (triggerContext != null) {
      triggerContext.close();
    }
  }

  @Test
  @DisplayName("MANUAL_APPROVAL 下从关闭日拒绝 occurrence 发起补跑,审批后恢复并只生成一个 Workflow 节点实例")
  void approvalOfConsoleCatchUp_reconcilesAndLaunchesWorkflowNode() throws Exception {
    startTriggerHttpServer();
    awaitWorkerOnline();
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String customerNo = "CATCHUP_" + UUID.randomUUID().toString().replace("-", "");
    String customerContent = """
        [{"customerNo":"%s","customerName":"Catch-up customer",
          "customerType":"PERSONAL","certificateNo":"ID-%s","status":"ACTIVE"}]
        """.formatted(customerNo, customerNo);
    jdbcTemplate.update(
        "update batch.job_definition set default_params = ?::jsonb "
            + "where tenant_id = ? and job_code = ?",
        objectMapper.writeValueAsString(Map.of(
            "fileFormatType", "JSON",
            "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
            "bizType", "CUSTOMER",
            "content", customerContent)),
        TENANT,
        seed.jobCode());
    jdbcTemplate.update(
        "update batch.business_calendar set catch_up_policy = 'MANUAL_APPROVAL' "
            + "where tenant_id = ? and calendar_code = 'e2e_calendar'",
        TENANT);
    jdbcTemplate.update(
        "insert into batch.batch_day_instance "
            + "(tenant_id, calendar_code, biz_date, day_status, open_at, settled_at) "
            + "values (?, 'e2e_calendar', ?, 'SETTLED', current_timestamp, current_timestamp)",
        TENANT,
        BIZ_DATE);

    fireClosedDayScheduleThroughQuartz(seed);
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                "select occurrence_status from batch.scheduled_occurrence "
                    + "where tenant_id = ? and workflow_definition_id = ? "
                    + "and scheduled_fire_time = ?",
                String.class,
                TENANT,
                seed.workflowDefinitionId(),
                Timestamp.from(FIRE_TIME)))
            .isEqualTo("CLOSED_DAY_REJECTED"));

    JsonNode catchUp = postConsoleJson(
        "/api/console/workflow-definitions/" + seed.workflowDefinitionId()
            + "/scheduled-occurrences/catch-up",
        """
        {"tenantId":"t1","scheduledFireTime":"2026-04-30T09:30:00Z",
         "bizDate":"2026-04-30","reason":"recover rejected scheduled fire"}
        """,
        "catch-up-submit-" + UUID.randomUUID());
    String requestId = catchUp.path("data").path("requestId").asText();
    String approvalNo = catchUp.path("data").path("approvalId").asText();
    assertThat(requestId).isNotBlank();
    assertThat(approvalNo).isNotBlank();
    assertThat(catchUp.path("data").path("status").asText()).isEqualTo("PENDING_APPROVAL");

    assertThat(jdbcTemplate.queryForObject(
            "select occurrence_status from batch.scheduled_occurrence "
                + "where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
            String.class,
            TENANT,
            seed.workflowDefinitionId(),
            Timestamp.from(FIRE_TIME)))
        .isEqualTo("MISFIRE_PENDING");
    assertThat(jdbcTemplate.queryForObject(
            "select status from batch.trigger_misfire_pending "
                + "where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
            String.class,
            TENANT,
            seed.workflowDefinitionId(),
            Timestamp.from(FIRE_TIME)))
        .isEqualTo("PENDING");

    JsonNode approved = postConsoleJson(
        "/api/console/approvals/" + approvalNo + "/approve",
        """
        {"tenantId":"t1","operatorId":"e2e-approver","reason":"approved recovery"}
        """,
        "catch-up-approve-" + UUID.randomUUID());
    assertThat(approved.path("code").asText()).isIn("0", "SUCCESS", "OK");

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          assertThat(jdbcTemplate.queryForObject(
                  "select status from batch.trigger_misfire_pending "
                      + "where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
                  String.class,
                  TENANT,
                  seed.workflowDefinitionId(),
                  Timestamp.from(FIRE_TIME)))
              .isEqualTo("APPROVED");
          assertThat(jdbcTemplate.queryForObject(
                  "select occurrence_status from batch.scheduled_occurrence "
                      + "where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
                  String.class,
                  TENANT,
                  seed.workflowDefinitionId(),
                  Timestamp.from(FIRE_TIME)))
              .isEqualTo("REQUESTED");
          assertThat(jdbcTemplate.queryForObject(
                  "select count(*) from batch.workflow_run where tenant_id = ? "
                      + "and workflow_definition_id = ? and trigger_request_id = "
                      + "(select id from batch.trigger_request where tenant_id = ? and request_id = ?)",
                  Integer.class,
                  TENANT,
                  seed.workflowDefinitionId(),
                  TENANT,
                  requestId))
              .isEqualTo(1);
          assertThat(jdbcTemplate.queryForObject(
                  """
                  select count(*) from batch.workflow_node_run nr
                  join batch.workflow_run wr on wr.id = nr.workflow_run_id
                  where wr.tenant_id = ? and wr.workflow_definition_id = ?
                  and wr.trigger_request_id = (select id from batch.trigger_request
                  where tenant_id = ? and request_id = ?)
                  and nr.node_code = 'JOB' and nr.job_instance_id is not null
                  """,
                  Integer.class,
                  TENANT,
                  seed.workflowDefinitionId(),
                  TENANT,
                  requestId))
              .isEqualTo(1);
        });

    assertThat(jdbcTemplate.queryForObject(
            "select request_status from batch.trigger_request where tenant_id = ? and request_id = ?",
            String.class,
            TENANT,
            requestId))
        .isEqualTo("LAUNCHED");
    assertThat(jdbcTemplate.queryForObject(
            "select approval_status from batch.approval_command "
                + "where tenant_id = ? and approval_no = ? and target_id = ?",
            String.class,
            TENANT,
            approvalNo,
            requestId))
        .isEqualTo("EXECUTED");
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.scheduled_occurrence_catch_up_audit "
                + "where tenant_id = ? and workflow_definition_id = ? "
                + "and scheduled_fire_time = ? and requested_by = ? "
                + "and reason = 'recover rejected scheduled fire' and request_id = ?",
            Integer.class,
            TENANT,
            seed.workflowDefinitionId(),
            Timestamp.from(FIRE_TIME),
            OPERATOR,
            requestId))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_outbox_event where tenant_id = ? "
                + "and request_id = ? and publish_status = 'PUBLISHED'",
            Integer.class,
            TENANT,
            requestId))
        .isEqualTo(1);

    Long jobInstanceId = jdbcTemplate.queryForObject(
        """
        select nr.job_instance_id from batch.workflow_node_run nr
        join batch.workflow_run wr on wr.id = nr.workflow_run_id
        where wr.tenant_id = ? and wr.workflow_definition_id = ?
        and wr.trigger_request_id = (select id from batch.trigger_request
        where tenant_id = ? and request_id = ?) and nr.node_code = 'JOB'
        """,
        Long.class,
        TENANT,
        seed.workflowDefinitionId(),
        TENANT,
        requestId);
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          assertThat(jdbcTemplate.queryForObject(
                  "select instance_status from batch.job_instance where tenant_id = ? and id = ?",
                  String.class,
                  TENANT,
                  jobInstanceId))
              .isEqualTo("SUCCESS");
          assertThat(jdbcTemplate.queryForObject(
                  "select count(*) from batch.job_task where tenant_id = ? "
                      + "and job_instance_id = ? and task_status = 'SUCCESS'",
                  Integer.class,
                  TENANT,
                  jobInstanceId))
              .isGreaterThan(0);
          assertThat(jdbcTemplate.queryForObject(
                  "select run_status from batch.workflow_run where tenant_id = ? and trigger_request_id = "
                      + "(select id from batch.trigger_request where tenant_id = ? and request_id = ?)",
                  String.class,
                  TENANT,
                  TENANT,
                  requestId))
              .isEqualTo("SUCCESS");
          assertThat(new org.springframework.jdbc.core.JdbcTemplate(businessDataSource)
                  .queryForObject(
                      "select count(*)::int from biz.customer_account "
                          + "where tenant_id = ? and customer_no = ?",
                      Integer.class,
                      TENANT,
                      customerNo))
              .isEqualTo(1);
        });

    Long parentWorkflowRunId = jdbcTemplate.queryForObject(
        "select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ? "
            + "and trigger_request_id = (select id from batch.trigger_request "
            + "where tenant_id = ? and request_id = ?)",
        Long.class,
        TENANT,
        seed.workflowDefinitionId(),
        TENANT,
        requestId);
    assertThat(jdbcTemplate.update(
            "update batch.workflow_run set run_status = 'FAILED' where tenant_id = ? and id = ?",
            TENANT,
            parentWorkflowRunId))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
            String.class,
            TENANT,
            parentWorkflowRunId))
        .isEqualTo("FAILED");
    assertThat(jdbcTemplate.update(
            "update batch.batch_day_instance set day_status = 'SETTLED' "
                + "where tenant_id = ? and calendar_code = 'e2e_calendar' and biz_date = ?",
            TENANT,
            BIZ_DATE))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select day_status from batch.batch_day_instance "
                + "where tenant_id = ? and calendar_code = 'e2e_calendar' and biz_date = ?",
            String.class,
            TENANT,
            BIZ_DATE))
        .isEqualTo("SETTLED");
    String inactiveParentChildRequestId = "inactive-parent-child-" + UUID.randomUUID();
    assertThatThrownBy(() -> jdbcTemplate.update(
            "insert into batch.trigger_request "
                + "(tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, "
                + "request_status, parent_workflow_run_id, parent_node_code) "
                + "values (?, ?, 'CATCH_UP', ?, ?, ?, 'ACCEPTED', ?, 'JOB')",
            TENANT,
            inactiveParentChildRequestId,
            seed.jobCode(),
            BIZ_DATE,
            "inactive-parent-child-dedup-" + UUID.randomUUID(),
            parentWorkflowRunId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("active parent WorkflowRun is required");
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?",
            Integer.class,
            TENANT,
            inactiveParentChildRequestId))
        .isZero();

    String invalidChildRequestId = "invalid-date-child-" + UUID.randomUUID();
    assertThatThrownBy(() -> jdbcTemplate.update(
            "insert into batch.trigger_request "
                + "(tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, "
                + "request_status, parent_workflow_run_id, parent_node_code) "
                + "values (?, ?, 'EVENT', ?, ?, ?, 'ACCEPTED', ?, 'JOB')",
            TENANT,
            invalidChildRequestId,
            seed.jobCode(),
            BIZ_DATE.plusDays(1),
            "invalid-date-child-dedup-" + UUID.randomUUID(),
            parentWorkflowRunId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("does not match parent WorkflowRun business date");
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?",
            Integer.class,
            TENANT,
            invalidChildRequestId))
        .isZero();
  }

  @Test
  @DisplayName("Workflow 完整停用后真实 Quartz AUTO misfire 仍恢复并完成 Worker 业务写入")
  void disabledWorkflow_autoMisfireReconcilesToWorkerBusinessSuccess() throws Exception {
    startTriggerHttpServer();
    awaitWorkerOnline();
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE') "
            + "on conflict (tenant_id) do update set status = 'ACTIVE'",
        TENANT,
        "disabled Workflow misfire E2E");
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String customerNo = "DISABLED_MISFIRE_" + UUID.randomUUID().toString().replace("-", "");
    String customerContent = """
        [{"customerNo":"%s","customerName":"Disabled misfire customer",
          "customerType":"PERSONAL","certificateNo":"ID-%s","status":"ACTIVE"}]
        """.formatted(customerNo, customerNo);
    jdbcTemplate.update(
        "update batch.job_definition set default_params = ?::jsonb "
            + "where tenant_id = ? and job_code = ?",
        objectMapper.writeValueAsString(Map.of(
            "fileFormatType", "JSON",
            "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
            "bizType", "CUSTOMER",
            "content", customerContent)),
        TENANT,
        seed.jobCode());
    jdbcTemplate.update(
        "update batch.business_calendar set catch_up_policy = 'AUTO', catch_up_max_days = 3 "
            + "where tenant_id = ? and calendar_code = 'e2e_calendar'",
        TENANT);
    jdbcTemplate.update(
        "update batch.workflow_definition set schedule_type = 'CRON', "
            + "schedule_expr = '0 * * * * ?', timezone = 'UTC', trigger_mode = 'SCHEDULED', "
            + "schedule_enabled = true where tenant_id = ? and id = ?",
        TENANT,
        seed.workflowDefinitionId());

    Scheduler scheduler = triggerContext.getBean(Scheduler.class);
    assertThat(scheduler.getMetaData().isJobStoreSupportsPersistence())
        .as("this scenario requires the production-style JDBC Quartz JobStore")
        .isTrue();
    // Start Quartz so its JDBC misfire handler thread is active, then hold job execution while
    // still allowing the durable callback to observe the overdue CronTrigger.
    scheduler.start();
    scheduler.standby();
    Instant startAt = Instant.now().minus(Duration.ofMinutes(4)).truncatedTo(ChronoUnit.MINUTES);
    JobKey primaryJobKey = JobKey.jobKey(
        TENANT + ":WF:" + seed.jobCode(), TriggerSchedulerFacade.JOB_GROUP);
    TriggerKey primaryTriggerKey = TriggerKey.triggerKey(
        primaryJobKey.getName(), primaryJobKey.getGroup());
    JobDataMap jobData = new JobDataMap();
    jobData.put(QuartzLaunchJob.TENANT_ID, TENANT);
    jobData.put(QuartzLaunchJob.JOB_CODE, seed.jobCode());
    jobData.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, seed.workflowDefinitionId());
    jobData.put(QuartzLaunchJob.WORKFLOW_CODE, seed.jobCode());
    jobData.put(QuartzLaunchJob.WORKFLOW_VERSION, 1);
    jobData.put(QuartzLaunchJob.JOB_DEFINITION_VERSION, 1);
    jobData.put(QuartzLaunchJob.SCHEDULE_TYPE, "CRON");
    jobData.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, "0 * * * * ?");
    jobData.put(QuartzLaunchJob.TIMEZONE, "UTC");
    jobData.put(QuartzLaunchJob.TRIGGER_MODE, "SCHEDULED");
    jobData.put(QuartzLaunchJob.CALENDAR_CODE, "e2e_calendar");
    jobData.put(QuartzLaunchJob.CATCH_UP_POLICY, "AUTO");
    jobData.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, 3);
    JobDetail job = JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(primaryJobKey)
        .usingJobData(jobData)
        .storeDurably()
        .build();
    JobDataMap triggerIdentity = new JobDataMap();
    triggerIdentity.put(QuartzLaunchJob.TENANT_ID, TENANT);
    triggerIdentity.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, seed.workflowDefinitionId());
    CronTrigger cronTrigger = TriggerBuilder.newTrigger()
        .withIdentity(primaryTriggerKey)
        .forJob(job)
        .usingJobData(triggerIdentity)
        .startAt(Date.from(startAt))
        .withSchedule(CronScheduleBuilder.cronSchedule("0 * * * * ?")
            .inTimeZone(TimeZone.getTimeZone("UTC"))
            .withMisfireHandlingInstructionDoNothing())
        .build();
    Date originalFireTime = cronTrigger.getFireTimeAfter(Date.from(startAt.minusMillis(1)));
    assertThat(originalFireTime)
        .as("the JDBC JobStore must expose an overdue original cron fire")
        .isBefore(Date.from(Instant.now().minusSeconds(60)));
    LocalDate bizDate = originalFireTime.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    jdbcTemplate.update(
        "insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, "
            + "workflow_code, schedule_version, calendar_code, scheduled_fire_time, biz_date, "
            + "occurrence_status) values (?, ?, ?, 1, 'e2e_calendar', ?, ?, 'PLANNED')",
        TENANT,
        seed.workflowDefinitionId(),
        seed.jobCode(),
        Timestamp.from(originalFireTime.toInstant()),
        bizDate);
    scheduler.scheduleJob(job, cronTrigger);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                "select occurrence_status from batch.scheduled_occurrence "
                    + "where tenant_id = ? and workflow_definition_id = ? "
                    + "and scheduled_fire_time = ?",
                String.class,
                TENANT,
                seed.workflowDefinitionId(),
                Timestamp.from(originalFireTime.toInstant())))
            .isEqualTo("MISFIRE_PENDING"));
    assertThat(jdbcTemplate.queryForObject(
            "select misfire_detected_at is not null from batch.scheduled_occurrence "
                + "where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
            Boolean.class,
            TENANT,
            seed.workflowDefinitionId(),
            Timestamp.from(originalFireTime.toInstant())))
        .isTrue();

    JsonNode disabled = patchConsoleJson(
        "/api/console/workflow-definitions/" + seed.workflowDefinitionId(),
        "{\"tenantId\":\"t1\",\"enabled\":false}",
        "disable-before-misfire-recovery-" + UUID.randomUUID());
    assertThat(disabled.path("code").asText()).isIn("0", "SUCCESS", "OK");
    assertThat(jdbcTemplate.queryForObject(
            "select enabled from batch.workflow_definition where tenant_id = ? and id = ?",
            Boolean.class,
            TENANT,
            seed.workflowDefinitionId()))
        .isFalse();

    AopTestUtils.<TriggerReconciler>getTargetObject(
            triggerContext.getBean(TriggerReconciler.class))
        .reconcile();
    JobKey recoveryJobKey = JobKey.jobKey(
        TENANT + ":RECOVERY:" + seed.workflowDefinitionId(), TriggerSchedulerFacade.JOB_GROUP);
    assertThat(scheduler.checkExists(primaryJobKey))
        .as("disabling a Workflow removes its ordinary Quartz schedule")
        .isFalse();
    assertThat(scheduler.checkExists(recoveryJobKey))
        .as("the reconciler retains a durable JobDetail for the pending fire")
        .isTrue();
    TriggerKey recoveryTriggerKey = TriggerKey.triggerKey(
        "misfire-recovery-" + seed.workflowDefinitionId() + "-"
            + originalFireTime.toInstant().toEpochMilli(),
        "batch-trigger-recovery");
    assertThat(scheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals("batch-trigger-recovery")))
        .containsExactly(recoveryTriggerKey);
    assertThat(scheduler.getTrigger(recoveryTriggerKey).getJobKey()).isEqualTo(recoveryJobKey);

    scheduler.start();
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          assertThat(jdbcTemplate.queryForObject(
                  "select occurrence_status from batch.scheduled_occurrence "
                      + "where tenant_id = ? and workflow_definition_id = ? "
                      + "and scheduled_fire_time = ?",
                  String.class,
                  TENANT,
                  seed.workflowDefinitionId(),
                  Timestamp.from(originalFireTime.toInstant())))
              .isEqualTo("REQUESTED");
          assertThat(jdbcTemplate.queryForObject(
                  "select count(*) from batch.trigger_request where tenant_id = ? "
                      + "and workflow_definition_id = ? and trigger_type = 'CATCH_UP'",
                  Integer.class,
                  TENANT,
                  seed.workflowDefinitionId()))
              .isEqualTo(1);
        });
    String recoveryRequestId = jdbcTemplate.queryForObject(
        "select request_id from batch.trigger_request where tenant_id = ? "
            + "and workflow_definition_id = ? and trigger_type = 'CATCH_UP'",
        String.class,
        TENANT,
        seed.workflowDefinitionId());
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                """
                select count(*) from batch.workflow_node_run nr
                join batch.workflow_run wr on wr.id = nr.workflow_run_id
                where wr.tenant_id = ? and wr.workflow_definition_id = ?
                and wr.trigger_request_id = (select id from batch.trigger_request
                where tenant_id = ? and request_id = ?)
                and nr.node_code = 'JOB' and nr.job_instance_id is not null
                """,
                Integer.class,
                TENANT,
                seed.workflowDefinitionId(),
                TENANT,
                recoveryRequestId))
            .isEqualTo(1));
    Long jobInstanceId = jdbcTemplate.queryForObject(
        """
        select nr.job_instance_id from batch.workflow_node_run nr
        join batch.workflow_run wr on wr.id = nr.workflow_run_id
        where wr.tenant_id = ? and wr.workflow_definition_id = ?
        and wr.trigger_request_id = (select id from batch.trigger_request
        where tenant_id = ? and request_id = ?) and nr.node_code = 'JOB'
        """,
        Long.class,
        TENANT,
        seed.workflowDefinitionId(),
        TENANT,
        recoveryRequestId);
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          assertThat(jdbcTemplate.queryForObject(
                  "select instance_status from batch.job_instance where tenant_id = ? and id = ?",
                  String.class,
                  TENANT,
                  jobInstanceId))
              .isEqualTo("SUCCESS");
          assertThat(jdbcTemplate.queryForObject(
                  "select run_status from batch.workflow_run where tenant_id = ? "
                      + "and trigger_request_id = (select id from batch.trigger_request "
                      + "where tenant_id = ? and request_id = ?)",
                  String.class,
                  TENANT,
                  TENANT,
                  recoveryRequestId))
              .isEqualTo("SUCCESS");
          assertThat(new JdbcTemplate(businessDataSource)
                  .queryForObject(
                      "select count(*)::int from biz.customer_account "
                          + "where tenant_id = ? and customer_no = ?",
                      Integer.class,
                      TENANT,
                      customerNo))
              .isEqualTo(1);
        });
    assertThat(jdbcTemplate.queryForObject(
            "select biz_date from batch.trigger_request where tenant_id = ? and request_id = ?",
            LocalDate.class,
            TENANT,
            recoveryRequestId))
        .isEqualTo(bizDate);
    assertThat(jdbcTemplate.queryForObject(
            "select enabled from batch.workflow_definition where tenant_id = ? and id = ?",
            Boolean.class,
            TENANT,
            seed.workflowDefinitionId()))
        .isFalse();
    assertThat(scheduler.getTrigger(primaryTriggerKey)).isNull();
  }

  @Test
  @DisplayName("根 Workflow 跨日依赖等待后由恢复扫描器派发,Worker 消费恢复参数并完成业务写入")
  void crossDayDependencyWait_recoversRootPayloadAndCompletesImportBusinessEffect()
      throws Exception {
    LocalDate crossDayBizDate = BIZ_DATE.plusDays(2);
    startTriggerHttpServer();
    awaitWorkerOnline();

    LaunchSeed source = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);
    Long sourceJobDefinitionId = jdbcTemplate.queryForObject(
        "select id from batch.job_definition where tenant_id = ? and job_code = ?",
        Long.class,
        TENANT,
        source.jobCode());
    Long sourceTriggerRequestId = jdbcTemplate.queryForObject(
        "select id from batch.trigger_request where tenant_id = ? and request_id = ?",
        Long.class,
        TENANT,
        source.requestId());
    String sourceInstanceNo = "cross-day-source-" + UUID.randomUUID();
    String sourceDedupKey = "cross-day-source-dedup-" + UUID.randomUUID();
    Long sourceJobInstanceId = jdbcTemplate.queryForObject(
        "insert into batch.job_instance (tenant_id, job_definition_id, trigger_request_id, "
            + "job_code, instance_no, biz_date, trigger_type, instance_status, queue_code, "
            + "worker_group, priority, dedup_key, run_attempt, version, "
            + "expected_partition_count, success_partition_count, failed_partition_count, "
            + "params_snapshot) values (?, ?, ?, ?, ?, ?, 'API', 'SUCCESS', 'e2e-q', "
            + "'DISPATCH', 5, ?, 1, 0, 0, 0, 0, '{}'::jsonb) returning id",
        Long.class,
        TENANT,
        sourceJobDefinitionId,
        sourceTriggerRequestId,
        source.jobCode(),
        sourceInstanceNo,
        crossDayBizDate.minusDays(1),
        sourceDedupKey);
    jdbcTemplate.update(
        "update batch.trigger_request set request_status = 'LAUNCHED' "
            + "where tenant_id = ? and request_id = ?",
        TENANT,
        source.requestId());
    LaunchSeed downstream = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    jdbcTemplate.update(
        "update batch.workflow_node set cross_day_dependencies = ?::jsonb, "
            + "cross_day_dependency_timeout_seconds = 600 "
            + "where tenant_id = ? and workflow_definition_id = ? and node_code = 'JOB'",
        """
        [{"alias":"prior_day","jobCode":"%s","bizDateOffset":-1,"scope":"REQUIRED",
          "consumeVersionStrategy":"EFFECTIVE_ONLY"}]
        """.formatted(source.jobCode()),
        TENANT,
        downstream.workflowDefinitionId());
    jdbcTemplate.update(
        "delete from batch.trigger_request where tenant_id = ? and request_id = ?",
        TENANT,
        downstream.requestId());

    String customerNo = "CROSSDAY_" + UUID.randomUUID().toString().replace("-", "");
    String rootMarker = "root-" + UUID.randomUUID();
    String content = "[{\"customerNo\":\"" + customerNo
        + "\",\"customerName\":\"Cross-day customer\",\"customerType\":\"PERSONAL\","
        + "\"certificateNo\":\"ID-" + customerNo + "\",\"status\":\"ACTIVE\"}]";
    String requestId = "cross-day-root-" + UUID.randomUUID();
    Map<String, Object> request = Map.of(
        "tenantId", TENANT,
        "workflowDefinitionId", downstream.workflowDefinitionId(),
        "bizDate", crossDayBizDate.toString(),
        "triggerType", "API",
        "params",
            Map.of(
                "fileFormatType", "JSON",
                "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
                "bizType", "CUSTOMER",
                "content", content,
                "rootMarker", rootMarker));
    HttpRequest triggerRequest = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + TRIGGER_PORT + "/api/triggers/launch"))
        .header(
            CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "cross-day-idem-" + UUID.randomUUID())
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestId)
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, "trace-" + requestId)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
        .build();
    HttpResponse<String> triggerResponse =
        HTTP_CLIENT.send(triggerRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(triggerResponse.statusCode())
        .as("Trigger launch failed: %s", triggerResponse.body())
        .isBetween(200, 299);

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                "select count(*) from batch.workflow_run where tenant_id = ? "
                    + "and workflow_definition_id = ? and trigger_request_id = "
                    + "(select id from batch.trigger_request where tenant_id = ? and request_id = ?)",
                Integer.class,
                TENANT,
                downstream.workflowDefinitionId(),
                TENANT,
                requestId))
            .isEqualTo(1));
    Long workflowRunId = jdbcTemplate.queryForObject(
        "select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ? "
            + "and trigger_request_id = (select id from batch.trigger_request "
            + "where tenant_id = ? and request_id = ?)",
        Long.class,
        TENANT,
        downstream.workflowDefinitionId(),
        TENANT,
        requestId);
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          String nodeRuns = jdbcTemplate.queryForObject(
              "select coalesce(string_agg(node_code || ':' || node_status, ',' order by id), '') "
                  + "from batch.workflow_node_run where workflow_run_id = ?",
              String.class,
              workflowRunId);
          String runState = jdbcTemplate.queryForObject(
              "select run_status || ':' || coalesce(current_node_code, '') "
                  + "from batch.workflow_run where tenant_id = ? and id = ?",
              String.class,
              TENANT,
              workflowRunId);
          assertThat(jdbcTemplate.query(
                  "select node_status from batch.workflow_node_run where workflow_run_id = ? "
                      + "and node_code = 'JOB' order by run_seq desc limit 1",
                  (rs, rowNum) -> rs.getString(1),
                  workflowRunId))
              .as("workflowRun state=%s nodeRuns=%s", runState, nodeRuns)
              .containsExactly("WAITING_DEPENDENCY");
        });
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.workflow_node_run where workflow_run_id = ? "
                + "and node_code = 'JOB' and job_instance_id is not null",
            Integer.class,
            workflowRunId))
        .isZero();

    String sourceBusinessKey = "job:" + source.jobCode() + ":" + crossDayBizDate.minusDays(1);
    jdbcTemplate.update(
        """
        insert into batch.result_version (tenant_id, business_key, version_no, job_instance_id,
          status, effective_at, payload_storage, payload_json, generated_at, generated_by,
          promotion_policy) values (?, ?, 1, ?, 'EFFECTIVE', current_timestamp,
          'INLINE_JSON', '{"sourceBatch":"previous-day"}'::jsonb, current_timestamp,
          'cross-day-e2e', 'AUTO_LATEST')
        """,
        TENANT,
        sourceBusinessKey,
        sourceJobInstanceId);

    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          java.util.List<Long> jobInstanceIds = jdbcTemplate.query(
              "select job_instance_id from batch.workflow_node_run where workflow_run_id = ? "
                  + "and node_code = 'JOB' and job_instance_id is not null order by run_seq desc limit 1",
              (rs, rowNum) -> rs.getLong(1),
              workflowRunId);
          assertThat(jobInstanceIds).hasSize(1);
          Long jobInstanceId = jobInstanceIds.getFirst();
          assertThat(jdbcTemplate.queryForObject(
                  "select params_snapshot->'effectiveParams'->>'rootMarker' "
                      + "from batch.job_instance where tenant_id = ? and id = ?",
                  String.class,
                  TENANT,
                  jobInstanceId))
              .isEqualTo(rootMarker);
          assertThat(jdbcTemplate.queryForObject(
                  "select params_snapshot->'effectiveParams'->'crossDay'->'prior_day'->'output'"
                      + "->>'sourceBatch' from batch.job_instance where tenant_id = ? and id = ?",
                  String.class,
                  TENANT,
                  jobInstanceId))
              .isEqualTo("previous-day");
          assertThat(jdbcTemplate.queryForObject(
                  "select instance_status from batch.job_instance where tenant_id = ? and id = ?",
                  String.class,
                  TENANT,
                  jobInstanceId))
              .isEqualTo("SUCCESS");
          assertThat(jdbcTemplate.queryForObject(
                  "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
                  String.class,
                  TENANT,
                  workflowRunId))
              .isEqualTo("SUCCESS");
          assertThat(new JdbcTemplate(businessDataSource)
                  .queryForObject(
                      "select count(*)::int from biz.customer_account "
                          + "where tenant_id = ? and customer_no = ?",
                      Integer.class,
                      TENANT,
                      customerNo))
              .isEqualTo(1);
        });
  }

  private void startTriggerHttpServer() {
    if (triggerContext != null) {
      return;
    }
    triggerContext = new SpringApplicationBuilder(E2eTriggerApplication.class)
        .profiles("test", "e2e")
        .run(
            "--spring.main.web-application-type=servlet",
            "--server.port=" + TRIGGER_PORT,
            "--spring.datasource.url=" + platformJdbcUrl(),
            "--spring.datasource.username=" + platformJdbcUsername(),
            "--spring.datasource.password=" + platformJdbcPassword(),
            "--spring.kafka.bootstrap-servers=" + kafkaBootstrapServers(),
            "--spring.flyway.enabled=false",
            "--spring.quartz.job-store-type=jdbc",
            "--spring.quartz.jdbc.initialize-schema=never",
            "--spring.quartz.auto-startup=false",
            "--spring.quartz.properties.org.quartz.scheduler.instanceName=E2eTriggerScheduler",
            "--spring.quartz.properties.org.quartz.jobStore.class=org.quartz.impl.jdbcjobstore.JobStoreTX",
            "--spring.quartz.properties.org.quartz.jobStore.dataSource=quartzJobstoreDs",
            "--spring.quartz.properties.org.quartz.jobStore.tablePrefix=quartz.QRTZ_",
            "--spring.quartz.properties.org.quartz.jobStore.driverDelegateClass=org.quartz.impl.jdbcjobstore.PostgreSQLDelegate",
            "--spring.quartz.properties.org.quartz.jobStore.isClustered=false",
            "--spring.quartz.properties.org.quartz.jobStore.misfireThreshold=1000",
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.driver=org.postgresql.Driver",
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.provider=hikaricp",
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.URL=" + platformJdbcUrl(),
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.user=" + platformJdbcUsername(),
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.password=" + platformJdbcPassword(),
            "--spring.quartz.properties.org.quartz.dataSource.quartzJobstoreDs.maxConnections=5",
            "--batch.startup-self-check.enabled=false",
            "--batch.security.bypass-mode=true",
            "--batch.orchestrator.base-url=http://127.0.0.1:" + consolePort,
            "--batch.trigger.outbox.poll-interval-millis=200",
            "--batch.trigger.misfire-pending-reconcile-interval-millis=200",
            "--batch.trigger.kafka.send-timeout-seconds=5");
  }

  private void awaitWorkerOnline() {
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                "select count(1)::int from batch.worker_registry "
                    + "where tenant_id = ? and worker_code = 'e2e-import-1' "
                    + "and worker_group = 'IMPORT' and status = 'ONLINE'",
                Integer.class,
                TENANT))
            .isEqualTo(1));
  }

  private void fireClosedDayScheduleThroughQuartz(LaunchSeed seed) throws Exception {
    Scheduler scheduler = triggerContext.getBean(Scheduler.class);
    JobDataMap jobData = new JobDataMap();
    jobData.put(QuartzLaunchJob.TENANT_ID, TENANT);
    jobData.put(QuartzLaunchJob.JOB_CODE, seed.jobCode());
    jobData.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, seed.workflowDefinitionId());
    jobData.put(QuartzLaunchJob.WORKFLOW_CODE, seed.jobCode());
    jobData.put(QuartzLaunchJob.WORKFLOW_VERSION, 1);
    jobData.put(QuartzLaunchJob.JOB_DEFINITION_VERSION, 1);
    jobData.put(QuartzLaunchJob.SCHEDULE_TYPE, "CRON");
    jobData.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, "0 30 9 * * ?");
    jobData.put(QuartzLaunchJob.TIMEZONE, "UTC");
    jobData.put(QuartzLaunchJob.TRIGGER_MODE, "SCHEDULED");
    jobData.put(QuartzLaunchJob.CALENDAR_CODE, "e2e_calendar");
    jobData.put(QuartzLaunchJob.CATCH_UP_POLICY, "NONE");
    jobData.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, 0);
    jobData.put(QuartzLaunchJob.MISFIRE_ORIGINAL_FIRE_TIME, FIRE_TIME.toEpochMilli());
    scheduler.start();
    scheduler.scheduleJob(
        JobBuilder.newJob(QuartzLaunchJob.class)
            .withIdentity("closed-day-occurrence-e2e", "workflow-daily-e2e")
            .usingJobData(jobData)
            .build(),
        TriggerBuilder.newTrigger()
            .withIdentity("closed-day-occurrence-fire-e2e", "workflow-daily-e2e")
            .startNow()
            .build());
  }

  private JsonNode postConsoleJson(String path, String body, String idempotencyKey)
      throws Exception {
    return sendConsoleJson("POST", path, body, idempotencyKey);
  }

  private JsonNode patchConsoleJson(String path, String body, String idempotencyKey)
      throws Exception {
    return sendConsoleJson("PATCH", path, body, idempotencyKey);
  }

  private JsonNode sendConsoleJson(
      String method, String path, String body, String idempotencyKey) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + consolePort + path))
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .header("X-Console-User", OPERATOR)
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, "e2e-" + UUID.randomUUID())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, "e2e-" + UUID.randomUUID())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .method(method, HttpRequest.BodyPublishers.ofString(body))
        .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("Console request failed: %s", response.body())
        .isBetween(200, 299);
    return objectMapper.readTree(response.body());
  }

  private static int reservePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("Cannot reserve an E2E Trigger HTTP port", exception);
    }
  }
}
