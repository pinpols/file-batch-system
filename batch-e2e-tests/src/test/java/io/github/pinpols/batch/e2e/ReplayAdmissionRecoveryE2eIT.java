package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplaySubmitCommand;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.config.OrchestratorKafkaConsumerConfiguration;
import io.github.pinpols.batch.orchestrator.config.TriggerConsumerProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDayReplayDispatcher;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.jdbc.Sql;

@E2eSpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.kafka.listener.auto-startup=true",
      "batch.outbox.poll-interval-millis=200",
      "batch.outbox.min-poll-interval-millis=200",
      "batch.replay.dispatch.enabled=true",
      "batch.replay.dispatch.poll-interval-millis=600000",
      "batch.replay.dispatch.lock-at-least-for=PT0S"
    })
@E2eBusinessSchema
@Sql(scripts = E2eTestSql.IMPORT_TEMPLATE_SEED)
@Tag("critical")
@DisplayName("重放预览到真实 Kafka 和 IMPORT：指定范围、冻结原配置与受理后恢复")
class ReplayAdmissionRecoveryE2eIT extends AbstractIntegrationTest {
  private static final String TENANT = "t1";
  private static final LocalDate DAY = LocalDate.of(2026, 1, 15);

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private WorkflowRunLaunchService launch;

  @Autowired
  private BatchDayReplayService replay;

  @Autowired
  private BatchDayReplayDispatcher dispatcher;

  @Autowired
  private KafkaListenerEndpointRegistry listeners;

  @Autowired
  private OrchestratorConfigCacheService cache;

  @Autowired
  private TriggerConsumerProperties consumerProperties;

  @Autowired
  @Qualifier("importBusinessDataSource")
  private DataSource business;

  private record Source(String jobCode, long jobId, long workflow, String customer) {}

  @Test
  @DisplayName("指定 Job 重放在受理后恢复消费，继续执行原版本并拒绝重复信封新增运行")
  void shouldRecoverFrozenReplayThroughKafka_whenConsumerResumesAfterAcceptance() throws Exception {
    final String selectCountSql = """
        select count(*) from batch.outbox_event where tenant_id=? and event_type='WORKFLOW_LAUNCH'\s\
        and publish_status='PUBLISHED'
    """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set version=version+1,default_params=?::jsonb where tenant_id=?\s\
        and job_code=?
    """;
    final String selectPayloadJsonSql = """
        select payload_json::text from batch.outbox_event where tenant_id=? and event_type='WORKFLOW_LAUNCH'
    """;
    final String selectStatusSql = """
        select status from batch.batch_day_replay_session where tenant_id=? and id=?
    """;
    final String selectStatementSql = """
        select * from batch.batch_day_replay_entry where tenant_id=? and session_id=?
    """;
    final String selectStatementSql2 = """
        select * from batch.job_instance where tenant_id=? and id=?
    """;
    final String selectWorkflowRunIdSql = """
        select n.workflow_run_id from batch.workflow_node_run n\s\
        join batch.workflow_run w on w.id=n.workflow_run_id where w.tenant_id=? and n.job_instance_id=?
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id=? and id=?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.batch_day_replay_entry where tenant_id=? and session_id=?
    """;

    Source selected = source("R25A"), unselected = source("R25B");
    var preview = replay.preview(command(selected.jobCode(), null));
    assertThat(preview.totalCount()).isEqualTo(1);
    assertThat(preview.entries())
        .singleElement()
        .satisfies(entry -> assertThat(entry.sourceInstanceId()).isEqualTo(selected.jobId()));
    var session = replay.submit(command(selected.jobCode(), preview.previewToken()));
    var listener = listeners.getListenerContainer(
        OrchestratorKafkaConsumerConfiguration.TRIGGER_LAUNCH_LISTENER_ID);
    assertThat(listener).isNotNull();
    listener.stop();
    String changedCustomer = "R25CHANGED" + Long.toUnsignedString(System.nanoTime());
    String envelope;
    try {
      assertThat(listener.isRunning()).isFalse();
      dispatcher.scheduledDispatch();
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        Integer selectedOutboxEventCount =
            jdbc.queryForObject(selectCountSql, Integer.class, TENANT);
        assertThat(selectedOutboxEventCount).isEqualTo(1);
      });
      assertThat(jobCount(selected.jobCode())).isEqualTo(1);
      jdbc.update(updateJobDefinitionSql, parameters(changedCustomer), TENANT, selected.jobCode());
      cache.evictJobDefinition(TENANT, selected.jobCode());
      envelope = jdbc.queryForObject(selectPayloadJsonSql, String.class, TENANT);
    } finally {
      listener.start();
    }
    await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
      String selectedStatus =
          jdbc.queryForObject(selectStatusSql, String.class, TENANT, session.id());
      assertThat(selectedStatus).isEqualTo("SUCCEEDED");
    });
    var entry = jdbc.queryForMap(selectStatementSql, TENANT, session.id());
    long rerun = ((Number) entry.get("rerun_instance_id")).longValue();
    assertThat(rerun).isNotEqualTo(selected.jobId());
    assertThat(entry.get("source_instance_id")).isEqualTo(selected.jobId());
    var job = jdbc.queryForMap(selectStatementSql2, TENANT, rerun);
    assertThat(job.get("job_definition_version")).isEqualTo(1);
    assertThat(job.get("parent_instance_id")).isEqualTo(selected.jobId());
    assertThat(job.get("biz_date").toString()).isEqualTo("2026-01-15");
    assertThat(job.get("instance_status")).isEqualTo("SUCCESS");
    assertThat(jobCount(selected.jobCode())).isEqualTo(2);
    assertThat(jobCount(unselected.jobCode())).isEqualTo(1);
    assertThat(customerCount(selected.customer())).isEqualTo(1);
    assertThat(customerCount(unselected.customer())).isEqualTo(1);
    assertThat(customerCount(changedCustomer)).isZero();
    long workflow = jdbc.queryForObject(selectWorkflowRunIdSql, Long.class, TENANT, rerun);
    String selectedRunStatus =
        jdbc.queryForObject(selectRunStatusSql, String.class, TENANT, workflow);
    assertThat(selectedRunStatus).isEqualTo("SUCCESS");
    try (var admin = Admin.create(Map.of("bootstrap.servers", kafkaBootstrapServers()));
        var producer = new KafkaProducer<String, String>(Map.of(
            "bootstrap.servers",
            kafkaBootstrapServers(),
            "key.serializer",
            StringSerializer.class,
            "value.serializer",
            StringSerializer.class,
            "acks",
            "all"))) {
      var record = producer
          .send(new ProducerRecord<>(BatchTopics.TRIGGER_LAUNCH_V1, "r25-duplicate", envelope))
          .get(10, TimeUnit.SECONDS);
      await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
        var offsets = admin
            .listConsumerGroupOffsets(consumerProperties.getGroupId())
            .partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS);
        var partition = new TopicPartition(record.topic(), record.partition());
        assertThat(offsets).containsKey(partition);
        assertThat(offsets.get(partition).offset()).isGreaterThanOrEqualTo(record.offset() + 1);
      });
    }
    assertThat(jobCount(selected.jobCode())).isEqualTo(2);
    assertThat(jobCount(unselected.jobCode())).isEqualTo(1);
    Integer selectedBatchDayReplayEntryCount =
        jdbc.queryForObject(selectCountSql2, Integer.class, TENANT, session.id());
    assertThat(selectedBatchDayReplayEntryCount).isEqualTo(1);
    assertThat(customerCount(changedCustomer)).isZero();
  }

  private Source source(String prefix) {
    final String updateJobDefinitionSql = """
        update batch.job_definition set default_params=?::jsonb where tenant_id=? and job_code=?
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id=? and id=?
    """;
    final String selectJobInstanceIdSql = """
        select n.job_instance_id from batch.workflow_node_run n\s\
        join batch.workflow_run w on w.id=n.workflow_run_id where w.tenant_id=? and w.id=? and n.node_code='JOB'
    """;
    final String selectCalendarCodeSql = """
        select calendar_code from batch.job_instance where tenant_id=? and id=?
    """;

    var seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, TENANT, "IMPORT", "import", TriggerType.API);
    String customer = prefix + Long.toUnsignedString(System.nanoTime());
    jdbc.update(updateJobDefinitionSql, parameters(customer), TENANT, seed.jobCode());
    var request = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(DAY)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("r25-replay-e2e")
        .params(Map.of("operatorId", "r25-e2e"))
        .build();
    long workflow = Long.parseLong(launch.launch(request).instanceNo());
    await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
      String selectedRunStatus =
          jdbc.queryForObject(selectRunStatusSql, String.class, TENANT, workflow);
      assertThat(selectedRunStatus).isEqualTo("SUCCESS");
    });
    long job = jdbc.queryForObject(selectJobInstanceIdSql, Long.class, TENANT, workflow);
    String selectedCalendarCode =
        jdbc.queryForObject(selectCalendarCodeSql, String.class, TENANT, job);
    assertThat(selectedCalendarCode).isEqualTo("e2e_calendar");
    return new Source(seed.jobCode(), job, workflow, customer);
  }

  private static String parameters(String customer) {
    String content = JsonUtils.toJson(List.of(Map.of(
        "customerNo",
        customer,
        "customerName",
        "R25 replay original",
        "customerType",
        "PERSONAL",
        "certificateNo",
        customer,
        "status",
        "ACTIVE")));
    return JsonUtils.toJson(Map.of(
        "fileFormatType",
        "JSON",
        "templateCode",
        "IMP-CUSTOMER-JSON-ARRAY",
        "bizType",
        "CUSTOMER",
        "content",
        content));
  }

  private static BatchDayReplaySubmitCommand command(String job, String token) {
    return BatchDayReplaySubmitCommand.builder()
        .tenantId(TENANT)
        .calendarCode("e2e_calendar")
        .bizDate(DAY)
        .scope("SUBSET_JOB_CODES")
        .jobCodes(List.of(job))
        .resultPolicy("CREATE_NEW_VERSION")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .reason("R25 frozen subset replay")
        .requestedBy("r25-e2e")
        .autoApprove(true)
        .executionMode("REPLAY")
        .candidateSource("EXISTING_INSTANCES")
        .previewToken(token)
        .build();
  }

  private int jobCount(String code) {
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id=? and job_code=?
    """;

    return jdbc.queryForObject(selectCountSql, Integer.class, TENANT, code);
  }

  private int customerCount(String customer) {
    final String selectCountSql = """
        select count(*) from biz.customer_account where tenant_id=? and customer_no=?
    """;

    return new JdbcTemplate(business)
        .queryForObject(selectCountSql, Integer.class, TENANT, customer);
  }
}
