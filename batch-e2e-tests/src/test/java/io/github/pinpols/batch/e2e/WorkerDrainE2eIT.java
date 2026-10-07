package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.e2e.apps.E2eImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.governance.WorkerDrainGovernanceService;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;
import io.github.pinpols.batch.worker.core.infrastructure.WorkerRuntimeState;
import io.github.pinpols.batch.worker.core.support.HeartbeatService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;

@E2eSpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.import.worker-type=IMPORT",
      "batch.worker.import.worker-code=e2e-import-drain-1",
      "batch.worker.drain.enabled=true",
      "batch.worker.drain.check-interval-millis=600000"
    })
@E2eBusinessSchema
@Sql(
    scripts = {
      E2eTestSql.IMPORT_TEMPLATE_SEED,
    })
// CI 上字母序末位测试,前 22 个 E2E @SpringBootTest context cache 持续持有 Kafka consumer
// 连接,叠加 RANDOM_PORT embedded server 启动到本测试时资源压力撞 GHA runner 上限。
// 显式 AFTER_CLASS DirtiesContext 让 Spring 在本类结束时释放 context,缓解资源累积。
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Worker 排空回收端到端:排空请求受理后进入排空态,超时接管把运行中任务退回就绪并让 worker 下线")
class WorkerDrainE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String WORKER_CODE = "e2e-import-drain-1";
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  private final LaunchService launchService;
  private final JdbcTemplate jdbcTemplate;
  private final WorkerDrainGovernanceService workerDrainGovernanceService;
  private final HeartbeatService heartbeatService;
  private final WorkerRuntimeState workerRuntimeState;
  private final KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;
  private final int localServerPort;

  @Autowired
  WorkerDrainE2eIT(
      LaunchService launchService,
      JdbcTemplate jdbcTemplate,
      WorkerDrainGovernanceService workerDrainGovernanceService,
      HeartbeatService heartbeatService,
      WorkerRuntimeState workerRuntimeState,
      KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry,
      @Value("${local.server.port}") int localServerPort) {
    this.launchService = launchService;
    this.jdbcTemplate = jdbcTemplate;
    this.workerDrainGovernanceService = workerDrainGovernanceService;
    this.heartbeatService = heartbeatService;
    this.workerRuntimeState = workerRuntimeState;
    this.kafkaListenerEndpointRegistry = kafkaListenerEndpointRegistry;
    this.localServerPort = localServerPort;
  }

  @BeforeEach
  void restoreWorkerOnline() {
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                "select count(*) from batch.worker_registry where tenant_id = ? and worker_code = ?",
                Integer.class,
                TENANT,
                WORKER_CODE))
            .isEqualTo(1));
    WorkerRegistration registration = workerRuntimeState.get(WORKER_CODE);
    if (registration != null) {
      registration.setStatus(WorkerRegistryStatus.ONLINE.code());
    }
    jdbcTemplate.update("""
        update batch.worker_registry
        set status = 'ONLINE',
            drain_started_at = null,
            drain_deadline_at = null,
            updated_at = current_timestamp
        where tenant_id = ? and worker_code = ?
        """, TENANT, WORKER_CODE);
    heartbeatService.beat(WORKER_CODE);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(kafkaListenerEndpointRegistry
                .getListenerContainer("import-task-consumer")
                .isPauseRequested())
            .isFalse());
  }

  @Test
  @DisplayName("排空超时后接管:worker 在排空态后转为已下线并清空排空时间戳,运行中任务退回就绪且解除占用")
  void shouldReclaimRunningTaskAndDecommissionWorker_whenDrainTimeoutExpires() throws Exception {
    Long taskId = seedClaimedTask("timeout");

    HttpResponse<String> drainResponse = sendDrainRequest(1);
    assertThat(drainResponse.statusCode()).isBetween(200, 299);

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
      Map<String, Object> worker = jdbcTemplate.queryForMap("""
                      select status, drain_started_at, drain_deadline_at
                      from batch.worker_registry
                      where tenant_id = ? and worker_code = ?
                      """, TENANT, WORKER_CODE);
      assertThat(worker.get("status")).isEqualTo("DRAINING");
      assertThat(worker.get("drain_started_at")).isNotNull();
      assertThat(worker.get("drain_deadline_at")).isNotNull();
    });

    jdbcTemplate.update("""
        update batch.worker_registry
        set drain_deadline_at = current_timestamp - interval '1 second',
            updated_at = current_timestamp
        where tenant_id = ? and worker_code = ?
        """, TENANT, WORKER_CODE);
    workerDrainGovernanceService.takeoverAfterDrainTimeout(TENANT, WORKER_CODE);

    assertWorkerAndTaskReclaimed(taskId);
  }

  @Test
  @DisplayName("已认领任务查询:只返回指定租户和 Worker 当前持有的活跃任务")
  void shouldReturnClaimedTasksForWorker() throws Exception {
    Long taskId = seedClaimedTask("claimed-query");

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + localServerPort + "/internal/workers/" + WORKER_CODE
            + "/claimed-tasks?tenantId=" + TENANT))
        .GET()
        .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isBetween(200, 299);
    assertThat(response.body())
        .contains("\"id\":" + taskId)
        .contains("\"assignedWorkerCode\":\"" + WORKER_CODE + "\"")
        .contains("\"taskStatus\":\"RUNNING\"");
  }

  @Test
  @DisplayName("心跳排空指令:内置 Worker 暂停 Kafka 拉取且未认领消息保留重投")
  void shouldPauseKafkaConsumption_whenHeartbeatReceivesDrainDirective() throws Exception {
    HttpResponse<String> response = sendDrainRequest(30);
    assertThat(response.statusCode()).isBetween(200, 299);

    heartbeatService.beat(WORKER_CODE);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(kafkaListenerEndpointRegistry
                .getListenerContainer("import-task-consumer")
                .isPauseRequested())
            .isTrue());
  }

  @Test
  @DisplayName("强制下线:立即回收 Worker 持有的运行中任务并把节点标记为已退役")
  void shouldReclaimTaskImmediately_whenWorkerForcedOffline() throws Exception {
    Long taskId = seedClaimedTask("force-offline");

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + localServerPort + "/internal/workers/" + WORKER_CODE
            + "/force-offline"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"tenantId\":\"" + TENANT + "\"}"))
        .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isBetween(200, 299);
    assertWorkerAndTaskReclaimed(taskId);
  }

  private Long seedClaimedTask(String scenario) {
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
    params.put("bizType", "CUSTOMER");
    params.put(
        "content",
        "[{\"customerNo\":\"DRAIN-E2E-" + scenario + "\",\"customerName\":\"Drain"
            + " User\",\"customerType\":\"PERSONAL\","
            + "\"certificateNo\":\"ID-20260115-DRN1\",\"mobileNo\":\"13800009999\","
            + "\"email\":\"drain@example.com\",\"status\":\"ACTIVE\"}]");

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.jobCode(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-worker-drain",
        params));

    Long taskId = jdbcTemplate.queryForObject("""
            select t.id
            from batch.job_task t
                     join batch.job_instance ji on ji.id = t.job_instance_id
            where ji.tenant_id = ?
              and ji.dedup_key = ?
            order by t.id asc
            limit 1
            """, Long.class, TENANT, seed.dedupKey());
    assertThat(taskId).isNotNull();

    jdbcTemplate.update(
        """
        update batch.job_task
        set task_status = 'RUNNING',
            assigned_worker_code = ?,
            started_at = ?,
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """,
        WORKER_CODE,
        Timestamp.from(BatchDateTimeSupport.utcNow().minusSeconds(600)),
        TENANT,
        taskId);
    return taskId;
  }

  private HttpResponse<String> sendDrainRequest(int timeoutSeconds) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(
            "http://127.0.0.1:" + localServerPort + "/internal/workers/" + WORKER_CODE + "/drain"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(
            "{\"tenantId\":\"" + TENANT + "\",\"timeoutSeconds\":" + timeoutSeconds + "}"))
        .build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void assertWorkerAndTaskReclaimed(Long taskId) {
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
      Map<String, Object> workerAfterTimeout = jdbcTemplate.queryForMap("""
                      select status, drain_started_at, drain_deadline_at
                      from batch.worker_registry
                      where tenant_id = ? and worker_code = ?
                      """, TENANT, WORKER_CODE);
      assertThat(workerAfterTimeout.get("status")).isEqualTo("DECOMMISSIONED");
      assertThat(workerAfterTimeout.get("drain_started_at")).isNull();
      assertThat(workerAfterTimeout.get("drain_deadline_at")).isNull();
    });

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
      Map<String, Object> taskAfterTimeout = jdbcTemplate.queryForMap("""
                      select task_status, assigned_worker_code
                      from batch.job_task
                      where id = ?
                      """, taskId);
      assertThat(taskAfterTimeout.get("task_status")).isEqualTo("READY");
      assertThat(taskAfterTimeout.get("assigned_worker_code")).isNull();
    });
  }
}
