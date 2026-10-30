package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.e2e.apps.E2eReportRecoveryOrchestratorApplication;
import io.github.pinpols.batch.e2e.apps.E2eTriggerApplication;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.orchestrator.application.readiness.ReadinessWaitService;
import io.github.pinpols.batch.orchestrator.application.readiness.TriggerInternalProperties;
import io.github.pinpols.batch.orchestrator.application.readiness.TriggerReadinessRecoveryClient;
import io.github.pinpols.batch.orchestrator.application.service.readiness.ReadinessService;
import io.github.pinpols.batch.orchestrator.infrastructure.http.OkHttpOrchestratorExternalHttpTransport;
import io.github.pinpols.batch.orchestrator.mapper.ReadinessWaitMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.infrastructure.readiness.ReadinessWaitHandoverClient;
import io.github.pinpols.batch.trigger.infrastructure.scheduler.ReadinessWaitSweeper;
import io.github.pinpols.batch.trigger.mapper.ScheduledOccurrenceMapper;
import io.github.pinpols.batch.trigger.service.ScheduledOccurrenceRecorder;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 双 HTTP 上下文与真实 PG：移交后不运行 Quartz，仅由持久等待扫描恢复或超时。 */
@E2eSpringBootTest(
    classes = E2eReportRecoveryOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.readiness-wait.sweep-interval-millis=600000"})
@DisplayName("readiness 跨服务持久移交、恢复与日结义务终结")
class ReadinessHandoverE2eIT extends AbstractIntegrationTest {

  private static final String INTERNAL_SECRET = UUID.randomUUID().toString();

  @DynamicPropertySource
  static void internalSecurity(DynamicPropertyRegistry registry) {
    registry.add("batch.security.internal-secret", () -> INTERNAL_SECRET);
  }

  private final JdbcTemplate jdbc;

  private final ReadinessWaitMapper waits;

  private final ReadinessService readiness;

  private final BatchSecurityProperties security;

  private final MeterRegistry metrics;

  @LocalServerPort
  private int orchestratorPort;

  @Autowired
  ReadinessHandoverE2eIT(
      JdbcTemplate jdbc,
      ReadinessWaitMapper waits,
      ReadinessService readiness,
      BatchSecurityProperties security,
      MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.waits = waits;
    this.readiness = readiness;
    this.security = security;
    this.metrics = metrics;
  }

  @Test
  @DisplayName("同一 fire 重试窗口变化时两端仍保留首次截止时间")
  void shouldKeepFirstDeadlineAcrossRepeatedHandover() {
    try (ConfigurableApplicationContext trigger = startTrigger()) {
      Scenario scenario = prepare(trigger, false);
      handover(trigger, scenario);
      Instant changedDeadline = scenario.deadline().plusSeconds(3600);
      assertThat(trigger
              .getBean(ScheduledOccurrenceRecorder.class)
              .recordWaitingReadiness(
                  scenario.descriptor(),
                  scenario.fire(),
                  scenario.bizDate(),
                  scenario.fire(),
                  changedDeadline))
          .isTrue();
      trigger
          .getBean(ReadinessWaitHandoverClient.class)
          .handover(
              scenario.descriptor(),
              scenario.fire(),
              scenario.bizDate(),
              scenario.fire(),
              changedDeadline);
      String sql = """
          select occurrence.readiness_deadline, wait.deadline
          from batch.scheduled_occurrence occurrence join batch.readiness_wait wait
            on wait.tenant_id=occurrence.tenant_id
           and wait.workflow_definition_id=occurrence.workflow_definition_id
           and wait.scheduled_fire_time=occurrence.scheduled_fire_time
          where occurrence.tenant_id='t1' and occurrence.workflow_definition_id=?
          """;
      Map<String, Object> result = jdbc.queryForMap(sql, scenario.workflowId());
      assertThat(((Timestamp) result.get("readiness_deadline")).toInstant())
          .isEqualTo(scenario.deadline());
      assertThat(((Timestamp) result.get("deadline")).toInstant()).isEqualTo(scenario.deadline());
    }
  }

  @Test
  @DisplayName("真实 HTTP 移交保留全部快照，重建服务后无 Quartz 回调仍能恢复原 fire")
  void shouldPersistAndRecoverOriginalFireWithoutQuartz() {
    try (ConfigurableApplicationContext trigger = startTrigger()) {
      Scenario scenario = prepare(trigger, false);
      handover(trigger, scenario);
      String selectWaitSql = """
          select depends_on_job_codes, schedule_version, biz_date, scheduled_fire_time,
                 deferred_since, deadline, wait_status from batch.readiness_wait
           where tenant_id = 't1' and workflow_definition_id = ?
          """;
      Map<String, Object> persisted = jdbc.queryForMap(selectWaitSql, scenario.workflowId());
      assertThat(persisted)
          .containsEntry("depends_on_job_codes", scenario.upstream())
          .containsEntry("schedule_version", 1)
          .containsEntry("wait_status", "PENDING");
      assertThat(persisted.get("biz_date").toString())
          .isEqualTo(scenario.bizDate().toString());
      assertThat(((Timestamp) persisted.get("scheduled_fire_time")).toInstant())
          .isEqualTo(scenario.fire());
      assertThat(((Timestamp) persisted.get("deferred_since")).toInstant())
          .isEqualTo(scenario.fire());
      assertThat(((Timestamp) persisted.get("deadline")).toInstant())
          .isEqualTo(scenario.deadline());
      ReadinessWaitService restarted = service(trigger);
      restarted.sweep(100);
      assertThat(waitStatus(scenario)).isEqualTo("PENDING");
      makeUpstreamEffective(scenario);
      assertThat(readiness
              .checkJobReady("t1", scenario.upstream(), scenario.bizDate())
              .ready())
          .isTrue();
      restarted.sweep(100);
      String selectOccurrenceSql = """
          select occurrence_status, schedule_version, biz_date from batch.scheduled_occurrence
           where tenant_id = 't1' and workflow_definition_id = ? and scheduled_fire_time = ?
          """;
      Map<String, Object> occurrence = jdbc.queryForMap(
          selectOccurrenceSql, scenario.workflowId(), Timestamp.from(scenario.fire()));
      // 正常启动会回调关闭等待，与恢复成功后的 RELEASED CAS 竞争；两种终态都不再等待。
      assertThat(waitStatus(scenario)).isIn("RELEASED", "CANCELLED");
      assertThat(occurrence)
          .containsEntry("occurrence_status", "REQUESTED")
          .containsEntry("schedule_version", 1);
      assertThat(occurrence.get("biz_date").toString())
          .isEqualTo(scenario.bizDate().toString());
      String countRequestsSql =
          "select count(*) from batch.trigger_request where tenant_id = 't1' and workflow_definition_id = ?";
      Integer requests =
          jdbc.queryForObject(countRequestsSql, Integer.class, scenario.workflowId());
      assertThat(requests).isEqualTo(1);
      restarted.sweep(100);
      Integer repeatedRequests =
          jdbc.queryForObject(countRequestsSql, Integer.class, scenario.workflowId());
      assertThat(repeatedRequests).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("无 Quartz 回调时超窗结束持久等待和 occurrence，清除日结阻塞")
  void shouldTerminateWaitAndOccurrenceAfterDeadline() {
    try (ConfigurableApplicationContext trigger = startTrigger()) {
      Scenario scenario = prepare(trigger, true);
      handover(trigger, scenario);
      service(trigger).sweep(100);
      assertThat(waitStatus(scenario)).isEqualTo("TIMED_OUT");
      ReadinessWaitSweeper sweeper = new ReadinessWaitSweeper(
          trigger.getBean(ScheduledOccurrenceMapper.class),
          trigger.getBean(TriggerGracefulShutdown.class),
          trigger.getBean(MeterRegistry.class));
      sweeper.sweep();
      String selectOccurrenceSql =
          "select occurrence_status from batch.scheduled_occurrence where tenant_id = 't1' and workflow_definition_id = ?";
      String status = jdbc.queryForObject(selectOccurrenceSql, String.class, scenario.workflowId());
      assertThat(status).isEqualTo("READINESS_TIMEOUT");
      String countBlockingSql = """
          select count(*) from batch.scheduled_occurrence
           where tenant_id = 't1' and workflow_definition_id = ?
             and occurrence_status in ('PLANNED', 'WAITING_READINESS', 'MISFIRE_PENDING', 'REQUESTED')
          """;
      Integer blocking =
          jdbc.queryForObject(countBlockingSql, Integer.class, scenario.workflowId());
      assertThat(blocking).isZero();
    }
  }

  private ConfigurableApplicationContext startTrigger() {
    return new SpringApplicationBuilder(E2eTriggerApplication.class)
        .profiles("test", "e2e")
        .run(
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--spring.datasource.url=" + platformJdbcUrl(),
            "--spring.datasource.username=" + platformJdbcUsername(),
            "--spring.datasource.password=" + platformJdbcPassword(),
            "--spring.kafka.bootstrap-servers=" + kafkaBootstrapServers(),
            "--spring.flyway.enabled=false",
            "--spring.quartz.auto-startup=false",
            "--batch.startup-self-check.enabled=false",
            "--batch.security.bypass-mode=true",
            "--batch.security.internal-secret=" + security.getInternalSecret(),
            "--batch.orchestrator.base-url=http://127.0.0.1:" + orchestratorPort,
            "--batch.trigger.reconcile-interval-millis=600000");
  }

  private Scenario prepare(ConfigurableApplicationContext trigger, boolean expired) {
    var seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, "t1", "ATOMIC", "readiness-e2e", TriggerType.API);
    String deleteRequestSql =
        "delete from batch.trigger_request where tenant_id = 't1' and request_id = ?";
    jdbc.update(deleteRequestSql, seed.requestId());
    String upstream = "UP_" + seed.workflowDefinitionId();
    String updateScheduleSql = """
        update batch.workflow_definition set schedule_type = 'CRON', schedule_expr = '0 0 * * * ?',
            timezone = 'UTC', trigger_mode = 'SCHEDULED', schedule_enabled = true,
            depends_on_job_code = ?, calendar_code = 'e2e_calendar'
         where tenant_id = 't1' and id = ?
        """;
    jdbc.update(updateScheduleSql, upstream, seed.workflowDefinitionId());
    Instant fire = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    Instant deadline = expired ? fire.plus(1, ChronoUnit.HOURS) : fire.plus(4, ChronoUnit.HOURS);
    LocalDate bizDate = fire.atZone(ZoneOffset.UTC).toLocalDate();
    String insertOccurrenceSql = """
        insert into batch.scheduled_occurrence
            (tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
             scheduled_fire_time, biz_date, occurrence_status, readiness_deferred_since, readiness_deadline)
        select tenant_id, id, workflow_code, version, calendar_code, ?, ?, 'WAITING_READINESS', ?, ?
          from batch.workflow_definition where tenant_id = 't1' and id = ?
        """;
    jdbc.update(
        insertOccurrenceSql,
        Timestamp.from(fire),
        bizDate,
        Timestamp.from(fire),
        Timestamp.from(deadline),
        seed.workflowDefinitionId());
    TriggerDescriptor descriptor = trigger
        .getBean(TriggerDefinitionLoader.class)
        .loadByWorkflowDefinitionId("t1", seed.workflowDefinitionId());
    return new Scenario(seed.workflowDefinitionId(), upstream, fire, deadline, bizDate, descriptor);
  }

  private void handover(ConfigurableApplicationContext trigger, Scenario scenario) {
    trigger
        .getBean(ReadinessWaitHandoverClient.class)
        .handover(
            scenario.descriptor(),
            scenario.fire(),
            scenario.bizDate(),
            scenario.fire(),
            scenario.deadline());
  }

  private ReadinessWaitService service(ConfigurableApplicationContext trigger) {
    int triggerPort = ((WebServerApplicationContext) trigger).getWebServer().getPort();
    TriggerInternalProperties properties = new TriggerInternalProperties();
    properties.setBaseUrl("http://127.0.0.1:" + triggerPort);
    TriggerReadinessRecoveryClient recovery = new TriggerReadinessRecoveryClient(
        properties, security, new OkHttpOrchestratorExternalHttpTransport());
    return new ReadinessWaitService(waits, readiness, recovery, metrics);
  }

  private String waitStatus(Scenario scenario) {
    String selectStatusSql =
        "select wait_status from batch.readiness_wait where tenant_id = 't1' and workflow_definition_id = ?";
    return jdbc.queryForObject(selectStatusSql, String.class, scenario.workflowId());
  }

  private void makeUpstreamEffective(Scenario scenario) {
    String insertVersionSql = """
        insert into batch.result_version
            (tenant_id, business_key, version_no, job_instance_id, status, effective_at,
             payload_storage, payload_json, generated_at, generated_by, promotion_policy)
        values ('t1', ?, 1, 900001, 'EFFECTIVE', current_timestamp,
                'INLINE_JSON', '{}'::jsonb, current_timestamp, 'readiness-e2e', 'AUTO_LATEST') returning id
        """;
    Long version = jdbc.queryForObject(insertVersionSql, Long.class, scenario.upstream());
    String insertAssetSql = """
        insert into batch.data_asset (tenant_id, asset_code, asset_type, display_name, owner_job_code)
        values ('t1', ?, 'JOB', ?, ?) returning id
        """;
    Long asset = jdbc.queryForObject(
        insertAssetSql, Long.class, scenario.upstream(), scenario.upstream(), scenario.upstream());
    String insertPartitionSql = """
        insert into batch.asset_partition
            (tenant_id, asset_id, asset_code, partition_key, biz_date, result_version_id,
             business_key, freshness_status, effective_at, job_instance_id, payload_storage)
        values ('t1', ?, ?, ?, ?, ?, ?, 'EFFECTIVE', current_timestamp, 900001, 'INLINE_JSON')
        """;
    jdbc.update(
        insertPartitionSql,
        asset,
        scenario.upstream(),
        scenario.bizDate().toString(),
        scenario.bizDate(),
        version,
        scenario.upstream());
  }

  private record Scenario(
      Long workflowId,
      String upstream,
      Instant fire,
      Instant deadline,
      LocalDate bizDate,
      TriggerDescriptor descriptor) {}
}
