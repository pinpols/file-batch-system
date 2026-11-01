package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.web.TriggerCommandInputMapper;
import io.github.pinpols.batch.trigger.web.request.TriggerLaunchRequest;
import java.time.LocalDate;
import java.time.Month;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * REQUIRES_NEW 事务边界集成测试 — Trigger 去重（ADR-010 异步路径）。
 *
 * <p>{@code TriggerRequestPersistence#persistAndForward} 使用 {@code PROPAGATION_REQUIRES_NEW} 在同一事务内原子写
 * trigger_request、trigger_outbox_event 和 ACCEPTED 状态。验证：去重检查生效、相关写入原子提交。
 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.enabled=false",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration",
      "batch.orchestrator.base-url=http://localhost:8082",
      // 关 QuartzMetricsConfiguration：本测试用 mock(Scheduler) 而非真 Quartz，
      // 否则 mock 的 getListenerManager() 返回 null 在 @PostConstruct 触发 NPE
      "batch.trigger.quartz-metrics.enabled=false",
      // ADR-010: 默认 true，走异步路径写 trigger_outbox_event，不调 orchestrator HTTP
    })
@Import(TriggerDedupRequiresNewIntegrationTest.TestConfig.class)
@DisplayName("Trigger 异步去重:REQUIRES_NEW 内原子写入 request 与 outbox,相同 dedupKey 只落一条")
class TriggerDedupRequiresNewIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private TriggerService triggerService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanUp() {
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = 't1' and workflow_code like 'trigger-dedup-%%'\
        """;

    String ownedWorkflowIds = """
        select id from batch.workflow_definition where tenant_id = 't1'\s\
        and workflow_code like 'trigger-dedup-%%'\
        """;
    String ownedRequestIds = """
        select request_id from batch.trigger_request where tenant_id = 't1' and workflow_definition_id in (%s)\
        """.formatted(ownedWorkflowIds);

    final String deleteScheduledOccurrenceCatchUpAuditSql = """
        delete from batch.scheduled_occurrence_catch_up_audit where tenant_id = 't1'\s\
        and workflow_definition_id in (%s)\
        """.formatted(ownedWorkflowIds);
    jdbcTemplate.update(deleteScheduledOccurrenceCatchUpAuditSql);
    final String deleteScheduledOccurrenceCoverageSql = """
        delete from batch.scheduled_occurrence_coverage where tenant_id = 't1'\s\
        and workflow_definition_id in (%s)\
        """.formatted(ownedWorkflowIds);
    jdbcTemplate.update(deleteScheduledOccurrenceCoverageSql);
    final String deleteScheduledOccurrenceSql = """
        delete from batch.scheduled_occurrence where tenant_id = 't1' and workflow_definition_id in (%s)\
        """.formatted(ownedWorkflowIds);
    jdbcTemplate.update(deleteScheduledOccurrenceSql);
    final String deleteJobDependencyWaitingLaunchSql = """
        delete from batch.job_dependency_waiting_launch where tenant_id = 't1' and request_id in (%s)\
        """.formatted(ownedRequestIds);
    jdbcTemplate.update(deleteJobDependencyWaitingLaunchSql);
    final String deleteTriggerOutboxEventSql = """
        delete from batch.trigger_outbox_event where tenant_id = 't1' and request_id in (%s)\
        """.formatted(ownedRequestIds);
    jdbcTemplate.update(deleteTriggerOutboxEventSql);
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = 't1' and workflow_definition_id in (%s)\
        """.formatted(ownedWorkflowIds);
    jdbcTemplate.update(deleteTriggerRequestSql);
    jdbcTemplate.update(deleteWorkflowDefinitionSql);
  }

  @AfterEach
  void cleanUpOwnedFixtures() {
    cleanUp();
  }

  @Test
  @DisplayName("异步路径下 trigger_request 落 ACCEPTED 状态,outbox 事件在同一 REQUIRES_NEW 事务内原子写入")
  void asyncLaunch_writesBothRequestAndOutboxInSameTransaction() {
    final String selectCountSql = """
      select count(*) from batch.trigger_request where tenant_id = ? and dedup_key = ?
  """;
    final String selectRequestStatusSql = """
      select request_status from batch.trigger_request where tenant_id = ? and dedup_key = ?\
      """;
    final String selectCountSql2 = """
      select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id = ?\
      """;

    TriggerLaunchCommand command = buildCommand("ASYNC_WRITE", "idem-async", "req-async");

    var response = triggerService.launch(command);
    assertThat(response).isNotNull();

    // trigger_request 已写入数据库且状态为 ACCEPTED
    Integer requestCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, "t1", "idem-async");
    assertThat(requestCount).as("trigger_request should be persisted").isEqualTo(1);

    String status =
        jdbcTemplate.queryForObject(selectRequestStatusSql, String.class, "t1", "idem-async");
    assertThat(status).as("async path should set ACCEPTED status").isEqualTo("ACCEPTED");

    // trigger_outbox_event 与 trigger_request 在同一 REQUIRES_NEW 事务内原子写入数据库
    Integer outboxCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, "t1", "req-async");
    assertThat(outboxCount)
        .as("trigger_outbox_event should be written atomically with trigger_request")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("同一 dedupKey 连续发起两次触发,去重检查保证 trigger_request 只保留一条记录")
  void dedupCheck_preventsSecondInsert() {
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and dedup_key = ?
    """;

    TriggerLaunchCommand first = buildCommand("DEDUP_TWICE", "idem-twice", "req-first");
    TriggerLaunchCommand second = buildCommand("DEDUP_TWICE", "idem-twice", "req-second");

    triggerService.launch(first);
    triggerService.launch(second);

    // 两次调用相同 dedupKey，只产生一条记录
    Integer count = jdbcTemplate.queryForObject(selectCountSql, Integer.class, "t1", "idem-twice");
    assertThat(count).isEqualTo(1);
  }

  @Test
  @DisplayName("Outbox 必须绑定同租户请求且请求删除受 NO ACTION 保护")
  void outboxReference_requiresTenantRequestAndProtectsParentDelete() {
    final String insertTriggerOutboxEventSql = """
        insert into batch.trigger_outbox_event (tenant_id, request_id, payload) values (?, ?, '{}'::jsonb)\
        """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = ? and request_id = ?
    """;

    TriggerLaunchCommand command = buildCommand("OUTBOX_FK", "idem-outbox-fk", "req-outbox-fk");
    triggerService.launch(command);

    assertThatThrownBy(
            () -> jdbcTemplate.update(insertTriggerOutboxEventSql, "t1", "missing-request"))
        .hasMessageContaining("fk_trigger_outbox_event_request");

    assertThatThrownBy(() -> jdbcTemplate.update(deleteTriggerRequestSql, "t1", "req-outbox-fk"))
        .hasMessageContaining("fk_trigger_outbox_event_request");
  }

  private TriggerLaunchCommand buildCommand(String jobCode, String dedupKey, String requestId) {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, calendar_code) values ('t1', ?, ?, 'DAG', 1, true, 'TEST_CAL')\s\
        on conflict (tenant_id, workflow_code, version) do update set updated_at = now() returning id\
        """;

    String workflowCode = "trigger-dedup-" + jobCode.toLowerCase(Locale.ROOT);
    Long workflowId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, workflowCode, workflowCode);
    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId("t1");
    request.setWorkflowDefinitionId(workflowId);
    request.setBizDate(LocalDate.of(2026, Month.MARCH, 27));
    request.setTriggerType(TriggerType.API);
    request.setParams(Map.of());
    return new TriggerLaunchCommand(
        TriggerCommandInputMapper.toInput(request), dedupKey, requestId, "trace-dedup");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {
    @Bean
    Scheduler scheduler() {
      return mock(Scheduler.class);
    }
  }
}
