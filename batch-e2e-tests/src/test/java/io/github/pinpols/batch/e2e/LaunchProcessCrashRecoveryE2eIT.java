package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eImportApplication;
import io.github.pinpols.batch.e2e.apps.E2eReportRecoveryOrchestratorApplication;
import io.github.pinpols.batch.e2e.apps.E2eTriggerApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.config.OrchestratorKafkaConsumerConfiguration;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.jdbc.Sql;

/** Real JVM exits at durable launch boundaries; recovery uses production polling and Kafka offsets. */
@E2eSpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.outbox.poll-interval-millis=200",
      "batch.outbox.min-poll-interval-millis=200",
      "batch.trigger.launch.reconcile.min-age-seconds=3600"
    })
@E2eBusinessSchema
@Sql(scripts = E2eTestSql.IMPORT_TEMPLATE_SEED)
@Tag("critical")
@DisplayName("请求接纳与 Kafka 消费确认窗口的独立进程强杀恢复")
class LaunchProcessCrashRecoveryE2eIT extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private KafkaListenerEndpointRegistry listeners;
  @Autowired @Qualifier("importBusinessDataSource") private DataSource businessDataSource;

  private enum CrashWindow { PUBLISHER_AFTER_ACCEPTANCE, CONSUMER_BEFORE_OFFSET }

  @ParameterizedTest
  @EnumSource(CrashWindow.class)
  @DisplayName("持久化事实提交后强杀发布或消费 JVM,新进程自动恢复同一请求且业务结果唯一")
  void shouldRecoverAcceptedLaunch_whenProcessDiesBeforeDeliveryAcknowledgement(CrashWindow window)
      throws Exception {
    // Only the independent Orchestrator JVM may consume this test's launch topic.
    var parentListener = listeners.getListenerContainer(
        OrchestratorKafkaConsumerConfiguration.TRIGGER_LAUNCH_LISTENER_ID);
    assertThat(parentListener).isNotNull();
    parentListener.stop();
    await().atMost(Duration.ofSeconds(15)).until(() -> !parentListener.isRunning());
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, "t1", "IMPORT", "import", TriggerType.API);
    jdbc.update("delete from batch.trigger_request where tenant_id='t1' and request_id=?",
        seed.requestId());
    String customer = "JVMLOSS" + Long.toUnsignedString(System.nanoTime());
    LocalDate bizDate = LocalDate.of(2026, 1, 15).plusDays(window.ordinal());
    String group = "r22-launch-" + seed.requestId();
    long lockKey = System.nanoTime();
    boolean publisherCrash = window == CrashWindow.PUBLISHER_AFTER_ACCEPTANCE;
    String table = publisherCrash ? "trigger_outbox_event" : "trigger_request";
    String condition = publisherCrash
        ? "old.publish_status = 'NEW' and new.publish_status = 'PUBLISHING'"
        : "old.request_status = 'ACCEPTED' and new.request_status = 'LAUNCHED'";
    List<Child> children = new ArrayList<>();
    jdbc.execute("""
        create function batch.e2e_hold_launch_boundary() returns trigger language plpgsql as $$
        begin perform pg_advisory_xact_lock(TG_ARGV[0]::bigint); return new; end $$
        """);
    try {
      jdbc.execute("""
          create trigger e2e_hold_launch_boundary before update on batch.%s
          for each row when (new.tenant_id = 't1' and new.request_id = '%s' and (%s))
          execute function batch.e2e_hold_launch_boundary('%s')
          """.formatted(table, seed.requestId(), condition, lockKey));
      Child trigger = startChild(E2eTriggerApplication.class, group, children);
      Child consumer = startChild(E2eReportRecoveryOrchestratorApplication.class, group, children);
      ConsumerRecord<String, String> delivered = null;
      Long originalRoot = null;
      try (Connection gate = DriverManager.getConnection(
              platformJdbcUrl(), platformJdbcUsername(), platformJdbcPassword());
          var sql = gate.createStatement()) {
        sql.execute("select pg_advisory_lock(" + lockKey + ")");
        int gatePid;
        try (var rows = sql.executeQuery("select pg_backend_pid()")) {
          assertThat(rows.next()).isTrue();
          gatePid = rows.getInt(1);
        }
        HttpResponse<String> accepted = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + trigger.port() + "/api/triggers/launch"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", seed.dedupKey())
                .header("X-Request-Id", seed.requestId())
                .header("X-Trace-Id", "r22-" + seed.requestId())
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(Map.of(
                    "tenantId", "t1", "workflowDefinitionId", seed.workflowDefinitionId(),
                    "bizDate", bizDate.toString(), "triggerType", "API", "params", Map.of(
                        "fileFormatType", "JSON", "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
                        "bizType", "CUSTOMER", "content", JsonUtils.toJson(List.of(Map.of(
                            "customerNo", customer, "customerName", "Crash recovery",
                            "customerType", "PERSONAL", "certificateNo", customer, "status", "ACTIVE"))))))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("""
                select count(*) from pg_stat_activity
                where ? = any(pg_blocking_pids(pid)) and query ilike ?
                """, Integer.class, gatePid, "%update batch." + table + "%")).isEqualTo(1));
        assertThat(jdbc.queryForObject("""
            select request_status from batch.trigger_request where tenant_id='t1' and request_id=?
            """, String.class, seed.requestId())).isEqualTo("ACCEPTED");
        if (publisherCrash) {
          assertThat(rootCount(seed)).isZero();
          assertThat(jdbc.queryForObject("""
              select publish_status from batch.trigger_outbox_event where tenant_id='t1' and request_id=?
              """, String.class, seed.requestId())).isEqualTo("NEW");
          trigger.kill();
        } else {
          assertThat(rootCount(seed)).isEqualTo(1);
          originalRoot = rootId(seed);
          assertThat(jobCount(seed)).isEqualTo(1);
          delivered = readLaunchRecord(seed.requestId());
          assertOffsetNotCommitted(group, delivered);
          consumer.kill();
          assertOffsetNotCommitted(group, delivered);
        }
      }
      // No manual publish, Kafka send, offset reset or recovery-state update.
      jdbc.execute("drop trigger e2e_hold_launch_boundary on batch." + table);
      if (publisherCrash) {
        startChild(E2eTriggerApplication.class, group, children);
      } else {
        startChild(E2eReportRecoveryOrchestratorApplication.class, group, children);
      }
      await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
        assertThat(rootCount(seed)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select run_status from batch.workflow_run where tenant_id='t1' and workflow_definition_id=?
            """, String.class, seed.workflowDefinitionId())).isEqualTo("SUCCESS");
        assertThat(new JdbcTemplate(businessDataSource).queryForObject("""
            select count(*) from biz.customer_account where tenant_id='t1' and customer_no=?
            """, Integer.class, customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select request_status from batch.trigger_request where tenant_id='t1' and request_id=?
            """, String.class, seed.requestId())).isEqualTo("LAUNCHED");
        assertThat(jdbc.queryForObject("""
            select publish_status from batch.trigger_outbox_event where tenant_id='t1' and request_id=?
            """, String.class, seed.requestId())).isEqualTo("PUBLISHED");
      });
      if (!publisherCrash) {
        assertThat(rootId(seed)).isEqualTo(originalRoot);
      }
      ConsumerRecord<String, String> record = publisherCrash ? readLaunchRecord(seed.requestId()) : delivered;
      // SIGKILL cannot leave the group. Kafka's unchanged 45s session timeout must expire
      // before replacement members can receive the partition and acknowledge the old record.
      await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
          assertThat(committedOffset(group, record)).isGreaterThan(record.offset()));
      assertThat(jobCount(seed)).isEqualTo(1);
      assertThat(jdbc.queryForObject("""
          select count(*) from batch.job_task t join batch.job_instance j
          on j.id=t.job_instance_id and j.tenant_id=t.tenant_id
          where j.tenant_id='t1' and j.job_code=?
          """, Integer.class, seed.jobCode())).isEqualTo(1);
      assertThat(jdbc.queryForObject("""
          select count(*) from batch.outbox_event where tenant_id='t1'
          and event_type='IMPORT' and payload_json->>'jobCode'=?
          """, Integer.class, seed.jobCode())).isEqualTo(1);
    } finally {
      for (Child child : children.reversed()) {
        child.close();
      }
      jdbc.execute("drop trigger if exists e2e_hold_launch_boundary on batch." + table);
      jdbc.execute("drop function if exists batch.e2e_hold_launch_boundary()");
    }
  }

  private int rootCount(LaunchSeed seed) {
    return jdbc.queryForObject("select count(*) from batch.workflow_run where tenant_id='t1' and workflow_definition_id=?",
        Integer.class, seed.workflowDefinitionId());
  }

  private Long rootId(LaunchSeed seed) {
    return jdbc.queryForObject("select id from batch.workflow_run where tenant_id='t1' and workflow_definition_id=?",
        Long.class, seed.workflowDefinitionId());
  }

  private int jobCount(LaunchSeed seed) {
    return jdbc.queryForObject("select count(*) from batch.job_instance where tenant_id='t1' and job_code=?",
        Integer.class, seed.jobCode());
  }

  private ConsumerRecord<String, String> readLaunchRecord(String requestId) {
    try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(Map.of(
        "bootstrap.servers", kafkaBootstrapServers(), "enable.auto.commit", false,
        "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
      var partitions = reader.partitionsFor(BatchTopics.TRIGGER_LAUNCH_V1).stream()
          .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
      reader.assign(partitions);
      reader.seekToBeginning(partitions);
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (System.nanoTime() < deadline) {
        for (var record : reader.poll(Duration.ofMillis(200))) {
          if (record.value().contains(requestId)) {
            return record;
          }
        }
      }
      throw new AssertionError("launch record missing for " + requestId);
    }
  }

  private void assertOffsetNotCommitted(String group, ConsumerRecord<String, String> record) throws Exception {
    assertThat(committedOffset(group, record)).isLessThanOrEqualTo(record.offset());
  }

  private long committedOffset(String group, ConsumerRecord<String, String> record) throws Exception {
    try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafkaBootstrapServers()))) {
      var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
      var offset = offsets.get(new TopicPartition(record.topic(), record.partition()));
      return EmptyChecks.isNull(offset) ? -1 : offset.offset();
    }
  }

  private Child startChild(Class<?> application, String group, List<Child> children) throws Exception {
    boolean trigger = application == E2eTriggerApplication.class;
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    AbstractIntegrationTest.registerDynamicProperties((name, supplier) -> properties.put(name, supplier.get()));
    properties.put("spring.profiles.active", "test,e2e");
    properties.put("spring.flyway.enabled", false);
    properties.put("spring.main.web-application-type", trigger ? "servlet" : "none");
    properties.put("server.port", port);
    properties.put("spring.quartz.auto-startup", false);
    properties.put("batch.orchestrator.base-url", "http://127.0.0.1:1");
    properties.put("batch.startup-self-check.enabled", false);
    properties.put("batch.trigger.outbox.poll-interval-millis", 200);
    properties.put("batch.trigger.outbox.publishing-timeout-seconds", 3);
    properties.put("batch.trigger.consumer.group-id", group);
    properties.put("batch.trigger.launch.reconcile.min-age-seconds", 3600);
    List<String> command = new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), application.getName()));
    properties.forEach((name, value) -> command.add("--" + name + "=" + value));
    Path directory = Path.of("../logs/verification/workflow").toAbsolutePath().normalize();
    Files.createDirectories(directory);
    Path log = Files.createTempFile(directory, "r22-" + (trigger ? "trigger-" : "consumer-"), ".log");
    Child child = new Child(new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start(), port, log);
    children.add(child);
    await().atMost(Duration.ofSeconds(90)).until(() ->
        Files.readString(log).contains("Started " + application.getSimpleName()) || !child.process().isAlive());
    assertThat(child.process().isAlive()).as("child startup log: %s", log).isTrue();
    return child;
  }

  private record Child(Process process, int port, Path log) implements AutoCloseable {
    void kill() throws Exception {
      process.destroyForcibly();
      assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("force-killed child: %s", log).isTrue();
    }

    @Override public void close() throws Exception {
      if (process.isAlive()) {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
          kill();
        }
      }
      assertThat(process.isAlive()).isFalse();
    }
  }
}
