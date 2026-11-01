package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.service.readiness.ReadinessRecoveryService;
import io.github.pinpols.batch.trigger.infrastructure.scheduler.ReadinessWaitSweeper;
import io.github.pinpols.batch.trigger.mapper.ScheduledOccurrenceMapper;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.web.InternalReadinessRecoveryController;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * readiness 恢复的冻结版本与终态 occurrence 回归。
 *
 * <p>等待期间定义版本漂移时不得按新定义启动；扫尾器已将 occurrence 终结为 READINESS_TIMEOUT 后，
 * 恢复请求不得复活该义务，也不得谎报已启动。
 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "batch.security.bypass-mode=true",
      "spring.quartz.job-store-type=jdbc",
      "spring.quartz.jdbc.initialize-schema=always",
      "batch.trigger.reconcile-interval-millis=600000"
    })
@DisplayName("复现: readiness 恢复的冻结版本与终态 occurrence 边界")
class ReadinessRecoveryReproTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;

  private final TriggerDefinitionLoader triggerDefinitionLoader;

  private final TriggerService triggerService;

  private final ScheduledOccurrenceMapper scheduledOccurrenceMapper;

  private final TriggerGracefulShutdown triggerGracefulShutdown;

  private final MeterRegistry meterRegistry;

  private String tenantId;
  private String workflowCode;
  private Long workflowDefinitionId;

  @Autowired
  ReadinessRecoveryReproTest(
      JdbcTemplate jdbcTemplate,
      TriggerDefinitionLoader triggerDefinitionLoader,
      TriggerService triggerService,
      ScheduledOccurrenceMapper scheduledOccurrenceMapper,
      TriggerGracefulShutdown triggerGracefulShutdown,
      MeterRegistry meterRegistry) {
    this.jdbcTemplate = jdbcTemplate;
    this.triggerDefinitionLoader = triggerDefinitionLoader;
    this.triggerService = triggerService;
    this.scheduledOccurrenceMapper = scheduledOccurrenceMapper;
    this.triggerGracefulShutdown = triggerGracefulShutdown;
    this.meterRegistry = meterRegistry;
  }

  @BeforeEach
  void seedWorkflow() {
    tenantId = "readiness-repro-" + Long.toUnsignedString(System.nanoTime());
    workflowCode = "WF_" + UUID.randomUUID().toString().replace("-", "");
    String insertTenantSql =
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')";
    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    String insertBusinessCalendarSql = """
    insert into batch.business_calendar (
        tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule,
        catch_up_policy, catch_up_max_days, cutoff_time, late_arrival_tolerance_min,
        sla_offset_min, enabled
    ) values (?, 'repro_cal', 'Repro calendar', 'UTC', 'SKIP', 'AUTO', 3,
              ?, 30, 120, true)
    """;
    jdbcTemplate.update(insertBusinessCalendarSql, tenantId, LocalTime.MIDNIGHT);
    String insertWorkflowDefinitionSql = """
    insert into batch.workflow_definition (
        tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
        schedule_type, schedule_expr, timezone, trigger_mode, schedule_enabled,
        calendar_code, depends_on_job_code
    ) values (?, ?, ?, 'DAG', 1, true, 'CRON', '0 0 * * * ?', 'UTC', 'SCHEDULED', true,
              'repro_cal', 'UPSTREAM_A') returning id
    """;
    workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, tenantId, workflowCode, workflowCode);
  }

  @AfterEach
  void cleanUp() {
    String deleteTriggerOutboxEventSql =
        "delete from batch.trigger_outbox_event where tenant_id = ?";
    jdbcTemplate.update(deleteTriggerOutboxEventSql, tenantId);
    String deleteTriggerRequestSql = "delete from batch.trigger_request where tenant_id = ?";
    jdbcTemplate.update(deleteTriggerRequestSql, tenantId);
    String deleteScheduledOccurrenceSql =
        "delete from batch.scheduled_occurrence where tenant_id = ?";
    jdbcTemplate.update(deleteScheduledOccurrenceSql, tenantId);
    String deleteWorkflowDefinitionSql =
        "delete from batch.workflow_definition where tenant_id = ? and id = ?";
    jdbcTemplate.update(deleteWorkflowDefinitionSql, tenantId, workflowDefinitionId);
    String deleteBusinessCalendarSql =
        "delete from batch.business_calendar where tenant_id = ? and calendar_code = 'repro_cal'";
    jdbcTemplate.update(deleteBusinessCalendarSql, tenantId);
    String deleteTenantSql = "delete from batch.tenant where tenant_id = ?";
    jdbcTemplate.update(deleteTenantSql, tenantId);
  }

  @Test
  @DisplayName("D1: 等待期间定义被原地更新后，恢复必须拒绝漂移的 fire 身份，而不是按新定义启动")
  void shouldRejectRecovery_whenScheduleVersionDrifted() {
    Instant fireTime = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
    insertWaitingOccurrence(fireTime, Instant.now().plus(1, ChronoUnit.HOURS), 1);
    String updateWorkflowDefinitionSql = """
    update batch.workflow_definition
    set depends_on_job_code = null, version = version + 1, updated_at = current_timestamp
    where tenant_id = ? and id = ?
    """;

    // 模拟 Console updateScheduleConfiguration：同行原地更新，version 1 → 2，依赖被清掉。
    jdbcTemplate.update(updateWorkflowDefinitionSql, tenantId, workflowDefinitionId);

    InternalReadinessRecoveryController controller = new InternalReadinessRecoveryController(
        new ReadinessRecoveryService(triggerDefinitionLoader, triggerService));
    ResponseEntity<InternalReadinessRecoveryController.RecoverResponse> response =
        controller.recover(new InternalReadinessRecoveryController.RecoverRequest(
            tenantId, workflowDefinitionId, fireTime));
    String selectTriggerRequestSql =
        "select count(*) from batch.trigger_request where tenant_id = ?";

    Integer requestCount =
        jdbcTemplate.queryForObject(selectTriggerRequestSql, Integer.class, tenantId);
    String selectScheduledOccurrenceSql = "select schedule_version from batch.scheduled_occurrence"
        + " where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?";
    Integer launchedVersion = jdbcTemplate.queryForObject(
        selectScheduledOccurrenceSql,
        Integer.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));

    assertThat(response.getBody())
        .as("原 fire 身份(version=1)已在等待期间漂移(version=2)，恢复必须拒绝而非按新定义启动")
        .isNotNull()
        .extracting(InternalReadinessRecoveryController.RecoverResponse::outcome)
        .isNotEqualTo("LAUNCHED");
    assertThat(requestCount).as("漂移身份不得产生 launch 请求").isZero();
    assertThat(launchedVersion).as("occurrence 必须保持冻结版本 1").isEqualTo(1);
  }

  @Test
  @DisplayName("D2: occurrence 已被扫尾器终结为 READINESS_TIMEOUT 后，恢复不得把它复活")
  void shouldNotResurrect_whenOccurrenceAlreadyTimedOut() {
    Instant fireTime = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
    insertWaitingOccurrence(fireTime, Instant.now().minus(1, ChronoUnit.HOURS), 1);

    // Trigger 兜底扫尾器先跑：超窗 → READINESS_TIMEOUT。
    // 直接构造实例调用，绕开 @SchedulerLock 代理（测试环境拿不到 ShedLock 锁会静默跳过）。
    new ReadinessWaitSweeper(scheduledOccurrenceMapper, triggerGracefulShutdown, meterRegistry)
        .sweep();
    String selectScheduledOccurrenceSql = "select occurrence_status from batch.scheduled_occurrence"
        + " where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?";
    String statusAfterSweep = jdbcTemplate.queryForObject(
        selectScheduledOccurrenceSql,
        String.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));
    assertThat(statusAfterSweep).isEqualTo("READINESS_TIMEOUT");

    InternalReadinessRecoveryController controller = new InternalReadinessRecoveryController(
        new ReadinessRecoveryService(triggerDefinitionLoader, triggerService));
    ResponseEntity<InternalReadinessRecoveryController.RecoverResponse> response =
        controller.recover(new InternalReadinessRecoveryController.RecoverRequest(
            tenantId, workflowDefinitionId, fireTime));
    String selectScheduledOccurrenceSql2 =
        "select occurrence_status from batch.scheduled_occurrence"
            + " where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?";

    String statusAfterRecovery = jdbcTemplate.queryForObject(
        selectScheduledOccurrenceSql2,
        String.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));
    String selectTriggerRequestSql =
        "select count(*) from batch.trigger_request where tenant_id = ?";
    Integer requestCount =
        jdbcTemplate.queryForObject(selectTriggerRequestSql, Integer.class, tenantId);

    assertThat(requestCount).as("终态 occurrence 不得产生 launch 请求").isZero();
    assertThat(statusAfterRecovery)
        .as("READINESS_TIMEOUT 是终态，不得被恢复 upsert 覆盖回 REQUESTED")
        .isEqualTo("READINESS_TIMEOUT");
    assertThat(response.getBody())
        .as("终态 occurrence 的恢复必须如实上报：launchScheduled 返回幂等 skip，端点不得谎报 LAUNCHED")
        .isNotNull()
        .extracting(InternalReadinessRecoveryController.RecoverResponse::outcome)
        .isNotEqualTo("LAUNCHED");
  }

  private void insertWaitingOccurrence(Instant fireTime, Instant deadline, int scheduleVersion) {
    String insertScheduledOccurrenceSql = """
    insert into batch.scheduled_occurrence (
        tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
        scheduled_fire_time, biz_date, occurrence_status,
        readiness_deferred_since, readiness_deadline
    ) values (?, ?, ?, ?, 'repro_cal', ?, ?, 'WAITING_READINESS', ?, ?)
    """;
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        scheduleVersion,
        Timestamp.from(fireTime),
        fireTime.atZone(ZoneOffset.UTC).toLocalDate(),
        Timestamp.from(fireTime),
        Timestamp.from(deadline));
  }
}
