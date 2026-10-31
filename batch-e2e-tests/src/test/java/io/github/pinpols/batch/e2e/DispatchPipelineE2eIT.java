package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.storage.ObjectStoreException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eDispatchApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.SmtpAcceptThenDropServer;
import io.github.pinpols.batch.e2e.support.verifier.DispatchReceiptVerifier;
import io.github.pinpols.batch.orchestrator.application.service.governance.FileGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskClaimCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.command.FileGovernanceCommand;
import io.github.pinpols.batch.orchestrator.service.DispatchChannelSnapshotService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.TestContainerLabels;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.FrozenDispatchChannelResolver;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 端到端测试：Dispatch 主链路成功闭环。
 *
 * <p>链路路径：
 *
 * <pre>
 * launch → orchestrator 生成 task/outbox → Kafka 派发 → dispatch worker claim → 渠道投递（LOCAL）
 *      → 回写平台表（file_record/file_dispatch_record/file_audit_log）→ worker report → orchestrator 终态
 * </pre>
 *
 * <p>本用例不仅断言任务成功，还断言“交付可追溯”：
 *
 * <ul>
 *   <li>{@code file_record.file_status = DISPATCHED}
 *   <li>{@code file_dispatch_record.receipt_code} 被写入（便于对账）
 *   <li>{@code file_audit_log} 至少有一条审计记录（便于审计/排障）
 * </ul>
 */
@E2eSpringBootTest(
    classes = E2eDispatchApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.dispatch.worker-type=DISPATCH",
      "batch.worker.dispatch.http-channel.call-timeout-millis=500",
      "batch.retry.poll-interval-millis=600000",
      "batch.outbox.poll-interval-millis=600000"
    })
@E2eBusinessSchema
@Tag("smoke")
@DisplayName("端到端测试: 派发主链路从触发受理, 事件派发, 任务领取到渠道投递的成功闭环, 验证任务成功终态, 平台表文件状态, 投递回执与审计记录四处一致")
class DispatchPipelineE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String SFTP_USER = "batch";
  private static final String SFTP_PASSWORD = "batch-pass";

  @Container
  @SuppressWarnings("resource")
  private static final GenericContainer<?> SFTP_SERVER = new GenericContainer<>(
          DockerImageName.parse("atmoz/sftp:alpine"))
      .withLabel(TestContainerLabels.OWNER, TestContainerLabels.OWNER_VALUE)
      .withExposedPorts(22)
      .withCommand(SFTP_USER + ":" + SFTP_PASSWORD + ":::upload")
      .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

  @TempDir
  Path tempDir;

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  private final KafkaTemplate<String, String> kafkaTemplate;

  private final RetryGovernanceService retryGovernanceService;

  private final FileGovernanceService fileGovernanceService;

  private final DispatchChannelSnapshotService dispatchChannelSnapshots;

  private final FrozenDispatchChannelResolver frozenDispatchChannels;

  @MockitoSpyBean
  private TaskControllerApplicationService taskControllerApplicationService;

  @MockitoSpyBean
  private BatchObjectStore objectStore;

  @Autowired
  DispatchPipelineE2eIT(
      KafkaTemplate<String, String> kafkaTemplate,
      RetryGovernanceService retryGovernanceService,
      FileGovernanceService fileGovernanceService,
      DispatchChannelSnapshotService dispatchChannelSnapshots,
      FrozenDispatchChannelResolver frozenDispatchChannels) {
    this.kafkaTemplate = kafkaTemplate;
    this.retryGovernanceService = retryGovernanceService;
    this.fileGovernanceService = fileGovernanceService;
    this.dispatchChannelSnapshots = dispatchChannelSnapshots;
    this.frozenDispatchChannels = frozenDispatchChannels;
  }

  @Test
  @DisplayName("整图重放审批快照跨 Orchestrator 与 Worker 保留选定渠道配置")
  void shouldResolveApprovedReplayChannel_whenMetadataContainsNonChannelValue() {
    final String updateChannelSql = """
        update batch.file_channel_config
        set config_json = jsonb_set(config_json, '{target_endpoint}', to_jsonb(cast(? as text)))
        where tenant_id = ? and channel_code = ?
        """;
    String channel = "e2e-replay-" + Long.toUnsignedString(System.nanoTime());
    String originalEndpoint = tempDir.resolve("local-outbox").toString();
    String latestEndpoint = tempDir.resolve("latest-outbox").toString();
    String changedEndpoint = tempDir.resolve("changed-after-preview").toString();
    upsertLocalChannel(channel);
    Map<String, Object> params = Map.of(
        "channelCode",
        channel,
        "fileId",
        "1",
        "operationType",
        "WORKFLOW_RUN",
        "metadata",
        Map.of("targetRef", "not-a-channel"));
    Map<String, Object> original =
        dispatchChannelSnapshots.pin(TENANT, JobType.DISPATCH.code(), params);
    assertThat(jdbcTemplate.update(updateChannelSql, latestEndpoint, TENANT, channel))
        .isEqualTo(1);

    var originalSelection = dispatchChannelSnapshots.captureForWorkflowReplay(
        new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
            TENANT,
            JobType.DISPATCH.code(),
            params,
            ConfigVersionPolicy.USE_ORIGINAL_CONFIG,
            original,
            Map.of()));
    var latestSelection = dispatchChannelSnapshots.captureForWorkflowReplay(
        new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
            TENANT,
            JobType.DISPATCH.code(),
            params,
            ConfigVersionPolicy.USE_LATEST_CONFIG,
            original,
            Map.of()));
    assertThat(jdbcTemplate.update(updateChannelSql, changedEndpoint, TENANT, channel))
        .isEqualTo(1);

    for (var selection : List.of(originalSelection, latestSelection)) {
      Map<String, Object> pinned = dispatchChannelSnapshots.pinWorkflowReplay(
          TENANT, JobType.DISPATCH.code(), params, selection.protectedSnapshots(), Map.of());
      Map<String, Object> resolved = frozenDispatchChannels
          .resolve(TENANT, channel, JsonUtils.toJson(pinned))
          .orElseThrow();
      JsonNode config =
          JsonUtils.fromJson(JsonUtils.toJson(resolved.get("config_json")), JsonNode.class);
      String expected = selection == originalSelection ? originalEndpoint : latestEndpoint;
      assertThat(config.path("target_endpoint").asText()).isEqualTo(expected);
    }
  }

  @Test
  @DisplayName("仅业务 metadata 有渠道时，即使渠道随后创建也不执行投递")
  void shouldRejectMissingTarget_whenMetadataChannelAppearsAfterLaunch() {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String channelCode = "e2e-late-" + suffix;
    String requestId = "no-target-" + suffix;
    Long fileId =
        insertGeneratedFile("no-target-" + suffix, "file.json", "/tmp/" + suffix, requestId);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileId", fileId.toString());
    params.put("metadata", Map.of("channelCode", channelCode));
    params.put("externalRequestId", requestId);
    params.put("ackRequired", false);
    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-no-target-" + suffix,
        params));
    String payloadSql = """
        select task.task_payload::text from batch.job_task task
        join batch.job_instance instance on instance.tenant_id=task.tenant_id
          and instance.id=task.job_instance_id
        where instance.tenant_id=? and instance.dedup_key=?
        """;
    JsonNode taskPayload = JsonUtils.fromJson(
        jdbcTemplate.queryForObject(payloadSql, String.class, TENANT, seed.dedupKey()),
        JsonNode.class);
    assertThat(taskPayload.has("channelCode")).isFalse();
    assertThat(taskPayload.has("_frozenDispatchChannelsProtected")).isFalse();
    assertThat(taskPayload.path("metadata").path("channelCode").asText()).isEqualTo(channelCode);
    upsertLocalChannel(channelCode);
    e2eOutboxPublishSupport.publishAllPending(TENANT);
    String taskSql = """
        select task.task_status, task.error_code from batch.job_task task
        join batch.job_instance instance on instance.tenant_id=task.tenant_id
          and instance.id=task.job_instance_id
        where instance.tenant_id=? and instance.dedup_key=?
        """;
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Map<String, Object> task = jdbcTemplate.queryForMap(taskSql, TENANT, seed.dedupKey());
          assertThat(task)
              .containsEntry("task_status", "FAILED")
              .containsEntry("error_code", "DISPATCH_PREPARE_CHANNEL_NOT_FOUND");
        });
    String dispatchSql =
        "select count(*) from batch.file_dispatch_record where tenant_id=? and file_id=?";
    assertThat(jdbcTemplate.queryForObject(dispatchSql, Long.class, TENANT, fileId))
        .isZero();
    assertThat(tempDir.resolve("local-outbox").resolve(channelCode + "-" + requestId + ".json"))
        .doesNotExist();
  }

  @Test
  @DisplayName("部分候选渠道不存在且目标地址变化时,Worker 仍使用冻结的目标地址")
  void shouldUseFrozenChannel_whenCandidateIsMissingAndTargetChangesAfterLaunch()
      throws IOException {
    final String updateChannelSql = """
        update batch.file_channel_config
        set config_json = jsonb_set(config_json, '{target_endpoint}', to_jsonb(cast(? as text)))
        where tenant_id = ? and channel_code = ?
        """;
    final String selectStatusSql = """
        select t.task_status from batch.job_task t
        join batch.job_instance ji on ji.tenant_id = t.tenant_id and ji.id = t.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """;
    String suffix = Long.toUnsignedString(System.nanoTime());
    String channelCode = "e2e-frozen-" + suffix;
    String requestId = "frozen-" + suffix;
    Path originalDirectory = tempDir.resolve("local-outbox");
    Path changedDirectory = tempDir.resolve("changed-outbox");
    upsertLocalChannel(channelCode);
    Long fileId =
        insertGeneratedFile("frozen-" + suffix, "frozen.json", "/tmp/" + suffix, requestId);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileId", fileId.toString());
    params.put("channelCode", channelCode);
    params.put("metadata", Map.of("channelCode", "missing-" + suffix));
    params.put("externalRequestId", requestId);
    params.put("ackRequired", false);
    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-frozen-" + suffix,
        params));
    assertThat(
            jdbcTemplate.update(updateChannelSql, changedDirectory.toString(), TENANT, channelCode))
        .isEqualTo(1);
    e2eOutboxPublishSupport.publishAllPending(TENANT);
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(
                jdbcTemplate.queryForObject(selectStatusSql, String.class, TENANT, seed.dedupKey()))
            .isEqualTo("SUCCESS"));
    Path envelope = originalDirectory.resolve(channelCode + "-" + requestId + ".json");
    assertThat(envelope).isRegularFile();
    JsonNode payload =
        JsonUtils.fromJson(Files.readString(envelope), JsonNode.class).path("payload");
    assertThat(payload.path("fileId").asLong()).isEqualTo(fileId);
    assertThat(payload.path("channelCode").asText()).isEqualTo(channelCode);
    assertThat(changedDirectory.resolve(envelope.getFileName())).doesNotExist();
  }

  @Test
  @DisplayName("单个文件派发任务经事件派发与任务领取后完成本地渠道投递, 任务收敛为成功终态, 文件状态为已派发, 回执落库且至少一条审计记录")
  void shouldReportSuccess_whenSingleFileDispatchTaskClaimedAndDelivered() {
    final String selectTaskStatusSql = """
                      select t.task_status from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ?
                      """;

    upsertLocalChannel("e2e_local_dispatch");
    String path = "/tmp/e2e-dispatch-" + System.nanoTime() + ".json";
    Long fileId = insertGeneratedFile("e2e-dis", "e2e.json", path, "e2e-dis-trace");
    assertThat(fileId).isNotNull();

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileId", String.valueOf(fileId));
    params.put("channelCode", "e2e_local_dispatch");
    params.put("dispatchTarget", "/tmp");
    params.put("externalRequestId", "e2e-ext-" + System.nanoTime());
    params.put("receiptCode", "R-E2E-DISPATCH");
    params.put("ackRequired", false);
    params.put("forceRetry", false);

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-dispatch",
        params));

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String status = jdbcTemplate.queryForObject(
              selectTaskStatusSql, String.class, TENANT, seed.dedupKey());
          assertThat(status).isEqualTo("SUCCESS");
        });

    // Content-level triple-check (状态 + 回执 + 审计) via DispatchReceiptVerifier
    DispatchReceiptVerifier.forTenant(TENANT)
        .fileId(fileId)
        .platformJdbc(jdbcTemplate)
        .expectedFileStatus(FileStatus.DISPATCHED.code())
        .expectedReceiptCode("R-E2E-DISPATCH")
        .expectedChannelCode("e2e_local_dispatch")
        .expectedMinAuditCount(1)
        .build()
        .verify();
  }

  @Test
  @DisplayName("DISPATCH 消息重复投递时,已派发文件不重复生成回执或审计记录")
  void shouldDeliverOnce_whenDispatchMessageIsRedelivered() throws Exception {
    final String selectIdSql = """
        select task.id
        from batch.job_task task
        join batch.job_instance ji on ji.id = task.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """;
    final String selectPayloadJsonSql = """
        select payload_json::text
        from batch.outbox_event
        where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
        order by id
        limit 1
        """;
    final String selectTaskStatusSql = """
        select task_status from batch.job_task where tenant_id = ? and id = ?
    """;
    final String selectFileStatusSql = """
        select file_status from batch.file_record where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
        select count(*)::int from batch.file_dispatch_record where tenant_id = ? and file_id = ?\s\
        and channel_code = ?\
        """;
    final String selectCountSql2 = """
        select count(*)::int from batch.file_audit_log where tenant_id = ? and file_id = ?\
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String channelCode = "e2e-redelivery-" + suffix;
    upsertLocalChannel(channelCode);
    Long fileId = insertGeneratedFile(
        "e2e-dis-redelivery-" + suffix,
        "e2e-dis-redelivery-" + suffix + ".json",
        "/tmp/e2e-dispatch-redelivery-" + suffix + ".json",
        "e2e-dis-redelivery-trace-" + suffix);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileId", String.valueOf(fileId));
    params.put("channelCode", channelCode);
    params.put("dispatchTarget", "/tmp");
    params.put("externalRequestId", "e2e-ext-redelivery-" + suffix);
    params.put("receiptCode", "R-E2E-REDISPATCH-" + suffix);
    params.put("ackRequired", false);
    params.put("forceRetry", false);
    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-dispatch-redelivery-" + suffix,
        params));

    Long taskId = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
    String payload =
        jdbcTemplate.queryForObject(selectPayloadJsonSql, String.class, TENANT, taskId);
    TaskDispatchMessage message = JsonUtils.fromJson(payload, TaskDispatchMessage.class);
    assertThat(message).isNotNull();
    assertThat(message.selectedWorkerId()).isNotBlank();

    AtomicInteger claimAttempts = new AtomicInteger();
    doAnswer(invocation -> {
          if (Long.valueOf(taskId).equals(invocation.getArgument(0))) {
            claimAttempts.incrementAndGet();
          }
          return invocation.callRealMethod();
        })
        .when(taskControllerApplicationService)
        .claim(any(Long.class), any(TaskClaimCommand.class));

    e2eOutboxPublishSupport.publishAllPending(TENANT);
    String dispatchTopic = BatchTopics.directDispatchTopic(
        BatchTopics.TASK_DISPATCH_DISPATCH, message.selectedWorkerId());
    String kafkaKey =
        TENANT + ":" + message.jobCode() + ":" + message.instanceNo() + ":task:" + message.taskId();
    kafkaTemplate.send(dispatchTopic, kafkaKey, payload).get();
    kafkaTemplate.send(dispatchTopic, kafkaKey, payload).get();

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          assertThat(claimAttempts.get()).isEqualTo(3);
          String taskStatus =
              jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, taskId);
          String fileStatus =
              jdbcTemplate.queryForObject(selectFileStatusSql, String.class, TENANT, fileId);
          assertThat(taskStatus).isEqualTo("SUCCESS");
          assertThat(fileStatus).isEqualTo(FileStatus.DISPATCHED.code());
        });

    Integer dispatchCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, fileId, channelCode);
    Integer auditCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, fileId);
    assertThat(dispatchCount).isEqualTo(1);
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  @DisplayName("DISPATCH 接收端已处理但响应超时后 retry 复用幂等键,过期重投不重复派发")
  void shouldReuseReceiverIdempotencyKey_whenTimedOutDispatchIsRetriedAndRedelivered()
      throws Exception {
    final String insertFileChannelConfigSql = """
        insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
            config_json, receipt_policy, timeout_seconds, enabled
        ) values (
            ?, ?, 'E2E HTTP dispatch', 'API', ?, 'NONE',
            jsonb_build_object(
                'channel_type', 'API',
                'channel_code', ?,
                'receiver_idempotency_supported', true),
            'NONE', 10, true
        )
        """;
    final String updateJobDefinitionSql = """
            update batch.job_definition set retry_policy = ?, retry_max_count = ? where tenant_id = ?\s\
            and job_code = ?\
            """;
    final String selectIdSql = """
          select task.id
          from batch.job_task task
          join batch.job_instance ji on ji.id = task.job_instance_id
          where ji.tenant_id = ? and ji.dedup_key = ?
          """;
    final String selectIdSql2 = """
          select partition.id
          from batch.job_partition partition
          join batch.job_instance ji on ji.tenant_id = partition.tenant_id
              and ji.id = partition.job_instance_id
          where ji.tenant_id = ? and ji.dedup_key = ?
          """;
    final String selectCountSql = """
                select count(*)::int from batch.retry_schedule
                where tenant_id = ? and related_type = 'JOB_PARTITION'
                    and related_id = ? and retry_status = 'WAITING'
                """;
    final String selectTaskStatusSql = """
            select task_status from batch.job_task where tenant_id = ? and id = ?
        """;
    final String updateRetryScheduleSql = """
          update batch.retry_schedule set next_retry_at = now() - interval '1 second'
          where tenant_id = ? and related_type = 'JOB_PARTITION'
              and related_id = ? and retry_status = 'WAITING'
          """;
    final String selectPayloadJsonSql = """
          select payload_json::text
          from batch.outbox_event
          where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
          order by id desc
          limit 1
          """;
    final String selectFileStatusSql = """
            select file_status from batch.file_record where tenant_id = ? and id = ?
        """;
    final String selectCountSql2 = """
            select count(*)::int from batch.file_dispatch_record where tenant_id = ? and file_id = ?\s\
            and channel_code = ?\
            """;

    AtomicInteger requestCount = new AtomicInteger();
    AtomicInteger receiverSideEffects = new AtomicInteger();
    AtomicInteger activeRequests = new AtomicInteger();
    Map<String, String> acceptedResults = new ConcurrentHashMap<>();
    Set<String> idempotencyKeys = ConcurrentHashMap.newKeySet();
    CountDownLatch firstSideEffectCommitted = new CountDownLatch(1);
    CountDownLatch releaseFirstResponse = new CountDownLatch(1);
    ExecutorService serverExecutor = Executors.newCachedThreadPool();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(serverExecutor);
    server.createContext("/dispatch", exchange -> {
      activeRequests.incrementAndGet();
      String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
      idempotencyKeys.add(idempotencyKey);
      String acceptedResult = acceptedResults.computeIfAbsent(
          idempotencyKey, key -> "accepted-" + receiverSideEffects.incrementAndGet());
      int attempt = requestCount.incrementAndGet();
      if (attempt == 1) {
        firstSideEffectCommitted.countDown();
        try {
          releaseFirstResponse.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      boolean failPipelineRetry = attempt == 2;
      byte[] body = (failPipelineRetry ? "temporarily unavailable" : acceptedResult)
          .getBytes(StandardCharsets.UTF_8);
      try {
        exchange.sendResponseHeaders(failPipelineRetry ? 503 : 200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
          output.write(body);
        }
      } catch (IOException responseClosedByTimedOutClient) {
        // 每个 Workflow 业务日只能存在一个活跃 WorkflowRun。
      } finally {
        exchange.close();
        activeRequests.decrementAndGet();
      }
    });
    server.start();
    try {
      String suffix = Long.toUnsignedString(System.nanoTime());
      Long fileId = insertGeneratedFile(
          "e2e-dis-timeout-" + suffix,
          "e2e-dis-timeout-" + suffix + ".json",
          "/tmp/e2e-dispatch-timeout-" + suffix + ".json",
          "e2e-dis-timeout-trace-" + suffix);
      String channelCode = "e2e-http-dispatch-" + suffix;
      String externalRequestId = "e2e-http-request-" + suffix;
      jdbcTemplate.update(
          insertFileChannelConfigSql,
          TENANT,
          channelCode,
          "http://127.0.0.1:" + server.getAddress().getPort() + "/dispatch",
          channelCode);
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);
      jdbcTemplate.update(updateJobDefinitionSql, "FIXED", 1, TENANT, seed.jobCode());
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("fileId", String.valueOf(fileId));
      params.put("channelCode", channelCode);
      params.put("dispatchTarget", "/tmp");
      params.put("externalRequestId", externalRequestId);
      params.put("receiptCode", "R-E2E-TIMEOUT-" + suffix);
      params.put("ackRequired", false);
      params.put("forceRetry", false);
      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          LocalDate.of(2026, 1, 15),
          TriggerType.API,
          seed.requestId(),
          "e2e-tr-dispatch-timeout-" + suffix,
          params));

      AtomicReference<Long> observedTaskId = new AtomicReference<>();
      AtomicInteger claimAttempts = new AtomicInteger();
      AtomicInteger successfulClaims = new AtomicInteger();
      doAnswer(invocation -> {
            if (observedTaskId.get() != null
                && observedTaskId.get().equals(invocation.getArgument(0))) {
              claimAttempts.incrementAndGet();
            }
            Object result = invocation.callRealMethod();
            if (observedTaskId.get() != null
                && observedTaskId.get().equals(invocation.getArgument(0))) {
              successfulClaims.incrementAndGet();
            }
            return result;
          })
          .when(taskControllerApplicationService)
          .claim(any(Long.class), any(TaskClaimCommand.class));

      Long taskId = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
      Long partitionId =
          jdbcTemplate.queryForObject(selectIdSql2, Long.class, TENANT, seed.dedupKey());
      observedTaskId.set(taskId);
      e2eOutboxPublishSupport.publishAllPending(TENANT);
      assertThat(firstSideEffectCommitted.await(10, TimeUnit.SECONDS))
          .as("receiver has committed the first side effect before the client timeout")
          .isTrue();

      await()
          .atMost(Duration.ofSeconds(30))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(() -> {
            assertThat(requestCount.get()).isGreaterThanOrEqualTo(1);
            assertThat(receiverSideEffects).hasValue(1);
            Integer waitingRetryCount =
                jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, partitionId);
            String taskStatus =
                jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, taskId);
            assertThat(waitingRetryCount).isEqualTo(1);
            assertThat(taskStatus).isEqualTo("FAILED");
          });
      releaseFirstResponse.countDown();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(activeRequests).hasValue(0));
      assertThat(requestCount)
          .as("first timeout plus one failed in-pipeline retry precede job-level retry")
          .hasValue(2);
      int requestsBeforeGovernedRetry = requestCount.get();
      int affectedRetryScheduleRows =
          jdbcTemplate.update(updateRetryScheduleSql, TENANT, partitionId);
      assertThat(affectedRetryScheduleRows).isEqualTo(1);
      retryGovernanceService.dispatchDueRetries();
      String retryPayload =
          jdbcTemplate.queryForObject(selectPayloadJsonSql, String.class, TENANT, taskId);
      TaskDispatchMessage retryMessage =
          JsonUtils.fromJson(retryPayload, TaskDispatchMessage.class);
      assertThat(retryMessage).isNotNull();
      e2eOutboxPublishSupport.publishAllPending(TENANT);
      await()
          .atMost(Duration.ofSeconds(30))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(() -> {
            assertThat(requestCount).hasValue(requestsBeforeGovernedRetry + 1);
            assertThat(receiverSideEffects).hasValue(1);
            assertThat(successfulClaims).hasValue(2);
            String taskStatus =
                jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, taskId);
            assertThat(taskStatus).isEqualTo("SUCCESS");
          });
      releaseFirstResponse.countDown();
      int attemptsBeforeReplay = claimAttempts.get();
      String retryTopic = BatchTopics.directDispatchTopic(
          BatchTopics.TASK_DISPATCH_DISPATCH, retryMessage.selectedWorkerId());
      String retryKey = TENANT + ":" + retryMessage.jobCode() + ":" + retryMessage.instanceNo()
          + ":task:" + retryMessage.taskId();
      kafkaTemplate.send(retryTopic, retryKey, retryPayload).get();
      kafkaTemplate.send(retryTopic, retryKey, retryPayload).get();
      // 重复投递的副作用幂等性由后续精确断言保证；这里只要求至少处理两次重投，
      // 避免慢 CI/重投递重排产生额外一次 claim 调用导致精确计数偶发失败。
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(() ->
              assertThat(claimAttempts.get()).isGreaterThanOrEqualTo(attemptsBeforeReplay + 2));

      assertThat(successfulClaims).hasValue(2);
      assertThat(requestCount).hasValue(requestsBeforeGovernedRetry + 1);
      assertThat(receiverSideEffects).hasValue(1);
      assertThat(idempotencyKeys).hasSize(1);
      String fileStatus =
          jdbcTemplate.queryForObject(selectFileStatusSql, String.class, TENANT, fileId);
      Integer dispatchCount =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, fileId, channelCode);
      assertThat(fileStatus).isEqualTo(FileStatus.DISPATCHED.code());
      assertThat(dispatchCount).isEqualTo(1);
    } finally {
      releaseFirstResponse.countDown();
      server.stop(0);
      serverExecutor.shutdownNow();
    }
  }

  @Test
  @DisplayName("API_PUSH 接收后响应丢失且未声明幂等时保留未知结果,凭据正确且没有自动重发预算")
  void shouldNotRetry_whenApiPushReceiverCommittedButResponseWasLost() throws Exception {
    final String insertChannelSql = """
        insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint,
            auth_type, config_json, receipt_policy, timeout_seconds, enabled
        ) values (?, ?, '推送接收后响应丢失', 'API_PUSH', ?, 'NONE',
            jsonb_build_object('api_push_api_key', ?, 'authorization', ?), 'NONE', 10, true)
        """;
    final String updateRetryPolicySql = """
        update batch.job_definition set retry_policy='FIXED', retry_max_count=3
        where tenant_id=? and job_code=?
        """;
    String suffix = Long.toUnsignedString(System.nanoTime());
    String channelCode = "api-push-unknown-" + suffix;
    String externalRequestId = "api-push-request-" + suffix;
    String apiKey = "test-api-push-key-" + suffix;
    String authorization = "Bearer test-api-push-token-" + suffix;
    Path receiverLedger = tempDir.resolve("api-push-receiver-" + suffix + ".jsonl");
    Path source = tempDir.resolve("api-push-source-" + suffix + ".json");
    Files.writeString(source, "{\"dispatch\":\"once\"}", StandardCharsets.UTF_8);
    AtomicInteger requestCount = new AtomicInteger();
    AtomicInteger receiverSideEffects = new AtomicInteger();
    AtomicInteger activeRequests = new AtomicInteger();
    AtomicReference<String> receivedApiKey = new AtomicReference<>();
    AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    AtomicReference<String> receivedIdempotencyKey = new AtomicReference<>();
    AtomicReference<String> receivedPayload = new AtomicReference<>();
    CountDownLatch sideEffectCommitted = new CountDownLatch(1);
    CountDownLatch releaseResponse = new CountDownLatch(1);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService serverExecutor = Executors.newCachedThreadPool();
    server.setExecutor(serverExecutor);
    server.createContext("/dispatch", exchange -> {
      if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
        return;
      }
      activeRequests.incrementAndGet();
      requestCount.incrementAndGet();
      try {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        String requestJson = new String(requestBody, StandardCharsets.UTF_8);
        receivedPayload.set(requestJson);
        receivedApiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
        receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        receivedIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        Files.writeString(
            receiverLedger,
            requestJson + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
        receiverSideEffects.incrementAndGet();
        sideEffectCommitted.countDown();
        // 接收端已写入真实台账，保持响应未返回，直至客户端超时并完成 UNKNOWN 回报。
        try {
          releaseResponse.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        try {
          exchange.sendResponseHeaders(200, -1);
        } catch (IOException responseClosedByTimedOutClient) {
          // 客户端在故障注入期间已超时断开，接收台账仍保留真实副作用。
        }
      } finally {
        exchange.close();
        activeRequests.decrementAndGet();
      }
    });
    server.start();
    try {
      String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/dispatch";
      Long fileId = insertGeneratedFile(
          "api-push-file-" + suffix,
          source.getFileName().toString(),
          source.toString(),
          "api-push-source-" + suffix);
      jdbcTemplate.update(insertChannelSql, TENANT, channelCode, endpoint, apiKey, authorization);
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);
      jdbcTemplate.update(updateRetryPolicySql, TENANT, seed.jobCode());
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("fileId", fileId.toString());
      params.put("channelCode", channelCode);
      params.put("dispatchTarget", endpoint);
      params.put("externalRequestId", externalRequestId);
      params.put("ackRequired", false);
      params.put("forceRetry", false);
      LaunchRequest launchRequest = new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          LocalDate.of(2026, 1, 15),
          TriggerType.API,
          seed.requestId(),
          "api-push-unknown-" + suffix,
          params);
      launchService.launch(launchRequest);
      e2eOutboxPublishSupport.publishAllPending(TENANT);

      boolean committedBeforeResponse = sideEffectCommitted.await(10, TimeUnit.SECONDS);
      assertThat(committedBeforeResponse).as("API_PUSH 接收端在响应丢失前完成真实台账写入").isTrue();
      assertUnknownDispatchFailure(seed, fileId, channelCode);
      releaseResponse.countDown();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(activeRequests).hasValue(0));

      assertThat(requestCount).hasValue(1);
      assertThat(receiverSideEffects).hasValue(1);
      String acceptedApiKey = receivedApiKey.get();
      String acceptedAuthorization = receivedAuthorization.get();
      String acceptedIdempotencyKey = receivedIdempotencyKey.get();
      assertThat(acceptedApiKey).isEqualTo(apiKey);
      assertThat(acceptedAuthorization).isEqualTo(authorization);
      assertThat(acceptedIdempotencyKey).isNotBlank();
      List<String> committedReceipts = Files.readAllLines(receiverLedger, StandardCharsets.UTF_8);
      String acceptedPayload = receivedPayload.get();
      assertThat(committedReceipts).containsExactly(acceptedPayload);
      JsonNode acceptedRequest = JsonUtils.fromJson(acceptedPayload, JsonNode.class);
      String acceptedTenant = acceptedRequest.path("tenantId").asText();
      String acceptedExternalRequestId =
          acceptedRequest.path("externalRequestId").asText();
      assertThat(acceptedTenant).isEqualTo(TENANT);
      assertThat(acceptedExternalRequestId).isEqualTo(externalRequestId);
    } finally {
      releaseResponse.countDown();
      server.stop(0);
      serverExecutor.shutdownNow();
    }
  }

  @Test
  @DisplayName("OSS 对象与清单已提交但写入确认丢失时保留未知结果,禁止自动重发")
  void shouldNotRetry_whenOssManifestCommittedButAcknowledgementWasLost() throws Exception {
    final String insertChannelSql = """
        insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint,
            auth_type, config_json, receipt_policy, timeout_seconds, enabled
        ) values (?, ?, '对象存储确认丢失', 'OSS', ?, 'NONE',
            jsonb_build_object('oss_bucket', ?, 'oss_object_prefix', ?, 'oss_object_name', ?,
                              'dispatch_manifest_enabled', true), 'SYNC', 30, true)
        """;
    final String updateRetryPolicySql = """
        update batch.job_definition set retry_policy='FIXED', retry_max_count=3
        where tenant_id=? and job_code=?
        """;
    String suffix = Long.toUnsignedString(System.nanoTime());
    String channelCode = "oss-unknown-" + suffix;
    String externalRequestId = "oss-request-" + suffix;
    String bucket = s3Bucket();
    String prefix = "dispatch-unknown/" + suffix;
    String fileName = "payload.txt";
    String objectKey = prefix + "/" + fileName;
    String manifestKey = objectKey + ".chk";
    String endpoint = "oss://" + bucket + "/" + prefix;
    byte[] content = ("published-once-" + suffix).getBytes(StandardCharsets.UTF_8);
    Path source = tempDir.resolve("source-" + suffix + ".txt");
    Files.write(source, content);
    Long fileId = insertGeneratedFile(
        "oss-file-" + suffix, fileName, source.toString(), "oss-source-" + suffix);
    jdbcTemplate.update(insertChannelSql, TENANT, channelCode, endpoint, bucket, prefix, fileName);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);
    jdbcTemplate.update(updateRetryPolicySql, TENANT, seed.jobCode());
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileId", fileId.toString());
    params.put("channelCode", channelCode);
    params.put("dispatchTarget", endpoint);
    params.put("externalRequestId", externalRequestId);
    params.put("ackRequired", false);
    params.put("forceRetry", false);
    AtomicBoolean acknowledgementLost = new AtomicBoolean();
    doAnswer(invocation -> {
          // 先完成真实对象写入，再丢弃清单确认，不以 mock 替代持久化副作用。
          invocation.callRealMethod();
          if (acknowledgementLost.compareAndSet(false, true)) {
            throw new ObjectStoreException("simulated acknowledgement loss after manifest commit");
          }
          return null;
        })
        .when(objectStore)
        .put(eq(bucket), eq(manifestKey), any(InputStream.class), anyLong(), anyString());

    try {
      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          LocalDate.of(2026, 1, 15),
          TriggerType.API,
          seed.requestId(),
          "oss-unknown-" + suffix,
          params));
      e2eOutboxPublishSupport.publishAllPending(TENANT);
      assertUnknownDispatchFailure(seed, fileId, channelCode);

      assertThat(acknowledgementLost).isTrue();
      byte[] storedContent = readObject(bucket, objectKey);
      assertThat(storedContent).isEqualTo(content);
      byte[] storedManifest = readObject(bucket, manifestKey);
      String manifestJson = new String(storedManifest, StandardCharsets.UTF_8);
      JsonNode manifest = JsonUtils.fromJson(manifestJson, JsonNode.class);
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      byte[] payloadDigest = sha256.digest(content);
      String expectedChecksum = HexFormat.of().formatHex(payloadDigest);
      assertThat(manifest.path("sizeBytes").asLong()).isEqualTo(content.length);
      assertThat(manifest.path("checksumValue").asText()).isEqualTo(expectedChecksum);
      assertThat(manifest.path("externalRequestId").asText()).isEqualTo(externalRequestId);
      verify(objectStore, times(1))
          .put(
              eq(bucket), eq(objectKey), any(InputStream.class), anyLong(), eq("application/json"));
      verify(objectStore, times(1))
          .put(
              eq(bucket),
              eq(manifestKey),
              any(InputStream.class),
              anyLong(),
              eq("application/json; charset=utf-8"));
    } finally {
      objectStore.delete(bucket, objectKey);
      objectStore.delete(bucket, manifestKey);
      boolean payloadRemains = objectStore.exists(bucket, objectKey);
      boolean manifestRemains = objectStore.exists(bucket, manifestKey);
      assertThat(payloadRemains).isFalse();
      assertThat(manifestRemains).isFalse();
    }
  }

  @Test
  @DisplayName("LOCAL 信封已发布但清单失败时保留未知结果,工作流失败且没有自动重发预算")
  void shouldNotRetry_whenLocalEnvelopeWasPublishedButManifestFailed() throws Exception {
    final String insertChannelSql = """
        insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint,
            auth_type, config_json, receipt_policy, timeout_seconds, enabled
        ) values (?, ?, '本地出箱部分发布', 'LOCAL', ?, 'NONE',
                  '{"dispatch_manifest_enabled":true}'::jsonb, 'SYNC', 30, true)
        """;
    final String updateRetryPolicySql = """
        update batch.job_definition set retry_policy='FIXED', retry_max_count=3
        where tenant_id=? and job_code=?
        """;
    String suffix = Long.toUnsignedString(System.nanoTime());
    String channelCode = "local-unknown-" + suffix;
    String externalRequestId = "local-request-" + suffix;
    Path target = tempDir.resolve("outbox-" + suffix);
    Files.createDirectories(target);
    Path envelope = target.resolve(channelCode + "-" + externalRequestId + ".json");
    Path manifest = target.resolve(envelope.getFileName() + ".chk");
    Files.createDirectory(manifest);
    Path occupied = manifest.resolve("occupied");
    Files.writeString(occupied, "keep", StandardCharsets.UTF_8);
    Path source = tempDir.resolve("source-" + suffix + ".json");
    Files.writeString(source, "{}", StandardCharsets.UTF_8);
    Long fileId = insertGeneratedFile(
        "local-file-" + suffix,
        source.getFileName().toString(),
        source.toString(),
        "local-source-" + suffix);
    jdbcTemplate.update(insertChannelSql, TENANT, channelCode, target.toString());
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);
    jdbcTemplate.update(updateRetryPolicySql, TENANT, seed.jobCode());
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileId", fileId.toString());
    params.put("channelCode", channelCode);
    params.put("dispatchTarget", target.toString());
    params.put("externalRequestId", externalRequestId);
    params.put("ackRequired", false);
    params.put("forceRetry", false);

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "local-unknown-" + suffix,
        params));
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    assertUnknownDispatchFailure(seed, fileId, channelCode);
    assertThat(envelope).isRegularFile();
    String publishedEnvelope = Files.readString(envelope, StandardCharsets.UTF_8);
    assertThat(publishedEnvelope).contains(externalRequestId, channelCode);
    assertThat(occupied).hasContent("keep");
  }

  @Test
  @DisplayName("SFTP 主文件已发布但清单冲突时经 Worker 与 Orchestrator 以 UNKNOWN 失败且不重试")
  void shouldNotRetry_whenSftpFileWasPublishedButManifestFailed() throws Exception {
    final String insertFileChannelConfigSql = """
        insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
            config_json, receipt_policy, timeout_seconds, enabled
        ) values (
            ?, ?, 'E2E SFTP dispatch', 'SFTP', ?, 'NONE',
            jsonb_build_object(
                'channel_type', 'SFTP',
                'channel_code', ?,
                'sftp_host', ?,
                'sftp_port', ?,
                'sftp_user', ?,
                'sftp_password', ?,
                'sftp_remote_directory', '/upload/',
                'sftp_remote_file_name', ?,
                'sftp_strict_host_key_checking', 'no',
                'dispatch_manifest_enabled', true
            ),
            'SYNC', 30, true
        )
        """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set retry_policy = ?, retry_max_count = ? where tenant_id = ?\s\
        and job_code = ?\
        """;
    final String selectIdSql = """
        select partition.id
        from batch.job_partition partition
        join batch.job_instance ji on ji.tenant_id = partition.tenant_id
            and ji.id = partition.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """;
    final String selectIdSql2 = """
        select task.id
        from batch.job_task task
        join batch.job_instance ji on ji.tenant_id = task.tenant_id
            and ji.id = task.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """;
    final String selectTaskStatusSql = """
        select task_status from batch.job_task where tenant_id = ? and id = ?
    """;
    final String selectErrorCodeSql = """
        select error_code from batch.job_task where tenant_id = ? and id = ?
    """;
    final String selectFailureClassSql = """
        select failure_class from batch.job_task where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
              select count(*)::int from batch.retry_schedule
              where tenant_id = ? and related_type = 'JOB_PARTITION'
                  and related_id = ? and retry_status = 'WAITING'
              """;
    final String selectMaxReplayCountSql = """
              select coalesce(max(max_replay_count), 0)::int from batch.dead_letter_task
              where tenant_id = ? and source_type = 'JOB_PARTITION' and source_id = ?
              """;
    final String selectRunStatusSql = """
        select wr.run_status
        from batch.trigger_request tr
        join batch.workflow_run wr on wr.tenant_id = tr.tenant_id
            and wr.trigger_request_id = tr.id
        where tr.tenant_id = ? and tr.request_id = ?
        """;
    final String selectCountSql2 = """
        select count(*)::int from batch.file_dispatch_record
        where tenant_id = ? and file_id = ? and channel_code = ?
        """;
    final String selectFileStatusSql = """
        select file_status from batch.file_record where tenant_id = ? and id = ?
    """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String channelCode = "e2e-sftp-unknown-" + suffix;
    String fileName = "dispatch-" + suffix + ".txt";
    String remotePath = "/home/" + SFTP_USER + "/upload/" + fileName;
    String remoteManifestPath = remotePath + ".chk";
    ExecResult manifestConflict = SFTP_SERVER.execInContainer(
        "sh",
        "-lc",
        "mkdir -p " + remoteManifestPath + " && printf occupied > " + remoteManifestPath
            + "/occupied");
    assertThat(manifestConflict.getExitCode()).isZero();

    byte[] content =
        ("published-before-manifest-failure-" + suffix).getBytes(StandardCharsets.UTF_8);
    Path sourcePath = Files.createTempFile(tempDir, "dispatch-unknown-", ".txt");
    Files.write(sourcePath, content);
    Long fileId = insertGeneratedFile(
        "e2e-dis-unknown-" + suffix,
        fileName,
        sourcePath.toString(),
        "e2e-dis-unknown-trace-" + suffix);
    jdbcTemplate.update(
        insertFileChannelConfigSql,
        TENANT,
        channelCode,
        "sftp://" + SFTP_SERVER.getHost() + ":" + SFTP_SERVER.getMappedPort(22) + "/upload",
        channelCode,
        SFTP_SERVER.getHost(),
        SFTP_SERVER.getMappedPort(22),
        SFTP_USER,
        SFTP_PASSWORD,
        fileName);

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);
    jdbcTemplate.update(updateJobDefinitionSql, "FIXED", 3, TENANT, seed.jobCode());

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileId", String.valueOf(fileId));
    params.put("channelCode", channelCode);
    params.put("dispatchTarget", "/upload/");
    params.put("externalRequestId", "e2e-sftp-request-" + suffix);
    params.put("receiptCode", "R-E2E-SFTP-" + suffix);
    params.put("ackRequired", false);
    params.put("forceRetry", false);
    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-dispatch-sftp-" + suffix,
        params));
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    Long partitionId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
    Long taskId = jdbcTemplate.queryForObject(selectIdSql2, Long.class, TENANT, seed.dedupKey());

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String taskStatus =
              jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, taskId);
          String taskErrorCode =
              jdbcTemplate.queryForObject(selectErrorCodeSql, String.class, TENANT, taskId);
          String taskFailureClass =
              jdbcTemplate.queryForObject(selectFailureClassSql, String.class, TENANT, taskId);
          Integer waitingRetryCount =
              jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, partitionId);
          Integer maxReplayCount = jdbcTemplate.queryForObject(
              selectMaxReplayCountSql, Integer.class, TENANT, partitionId);
          assertThat(taskStatus).isEqualTo("FAILED");
          assertThat(taskErrorCode).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
          assertThat(taskFailureClass).isEqualTo("UNKNOWN");
          assertThat(waitingRetryCount).isZero();
          assertThat(maxReplayCount).isZero();
        });

    String runStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, seed.requestId());
    Integer dispatchCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, fileId, channelCode);
    String fileStatus =
        jdbcTemplate.queryForObject(selectFileStatusSql, String.class, TENANT, fileId);
    assertThat(runStatus).isEqualTo("FAILED");
    assertThat(dispatchCount).isEqualTo(1);
    assertThat(fileStatus).isNotEqualTo(FileStatus.DISPATCHED.code());

    ExecResult remoteFile = SFTP_SERVER.execInContainer(
        "sh",
        "-lc",
        "test -f " + remotePath + " && cat " + remotePath + " && test -f " + remoteManifestPath
            + "/occupied");
    assertThat(remoteFile.getExitCode()).isZero();
    assertThat(remoteFile.getStdout()).isEqualTo(new String(content, StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("SMTP 已接收邮件但最终响应丢失时经 Worker 链路收敛为 UNKNOWN 且不安排重试")
  void shouldNotRetry_whenSmtpAcceptedMessageButFinalResponseWasLost() throws Exception {
    final String insertEmailChannelSql = """
        insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
            config_json, receipt_policy, timeout_seconds, enabled
        ) values (
            ?, ?, 'E2E SMTP accept then drop', 'EMAIL', ?, 'NONE',
            jsonb_build_object(
                'channel_type', 'EMAIL',
                'smtp_host', '127.0.0.1',
                'smtp_port', ?,
                'smtp_username', 'sender@example.test',
                'smtp_password', 'test-secret',
                'smtp_starttls', false,
                'mail_from', 'sender@example.test',
                'mail_to', 'receiver@example.test'
            ),
            'SYNC', 10, true
        )
        """;
    final String updateJobRetrySql = """
        update batch.job_definition set retry_policy = ?, retry_max_count = ?
        where tenant_id = ? and job_code = ?
        """;
    final String selectTaskIdSql = """
        select task.id
        from batch.job_task task
        join batch.job_instance ji on ji.tenant_id = task.tenant_id
            and ji.id = task.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """;
    final String selectPartitionIdSql = """
        select partition.id
        from batch.job_partition partition
        join batch.job_instance ji on ji.tenant_id = partition.tenant_id
            and ji.id = partition.job_instance_id
        where ji.tenant_id = ? and ji.dedup_key = ?
        """;
    final String selectRetryCountSql = """
        select count(*)::int from batch.retry_schedule
        where tenant_id = ? and related_type = 'JOB_PARTITION'
            and related_id = ? and retry_status = 'WAITING'
        """;
    final String selectDispatchCountSql = """
        select count(*)::int from batch.file_dispatch_record
        where tenant_id = ? and file_id = ? and channel_code = ?
        """;
    final String selectUnknownDispatchCountSql = """
        select count(*)::int from batch.file_dispatch_record
        where tenant_id = ? and file_id = ? and channel_code = ?
            and error_code = 'EXTERNAL_OUTCOME_UNKNOWN'
        """;
    final String selectFileStatusSql = """
        select file_status from batch.file_record where tenant_id = ? and id = ?
        """;
    final String selectTaskStatusByIdSql = """
        select task_status from batch.job_task where tenant_id = ? and id = ?
        """;
    final String selectTaskErrorCodeByIdSql = """
        select error_code from batch.job_task where tenant_id = ? and id = ?
        """;

    try (SmtpAcceptThenDropServer smtpServer = new SmtpAcceptThenDropServer()) {
      String suffix = Long.toUnsignedString(System.nanoTime());
      String channelCode = "e2e-smtp-unknown-" + suffix;
      byte[] content = ("smtp-unknown-content-" + suffix).getBytes(StandardCharsets.UTF_8);
      Path sourcePath = Files.createTempFile(tempDir, "dispatch-smtp-unknown-", ".txt");
      Files.write(sourcePath, content);
      Long fileId = insertGeneratedFile(
          "e2e-dis-smtp-" + suffix,
          "dispatch-smtp-" + suffix + ".txt",
          sourcePath.toString(),
          "e2e-dis-smtp-trace-" + suffix);
      jdbcTemplate.update(
          insertEmailChannelSql,
          TENANT,
          channelCode,
          "smtp://127.0.0.1:" + smtpServer.port(),
          smtpServer.port());

      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);
      jdbcTemplate.update(updateJobRetrySql, "FIXED", 3, TENANT, seed.jobCode());
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("fileId", String.valueOf(fileId));
      params.put("channelCode", channelCode);
      params.put("dispatchTarget", "smtp://127.0.0.1:" + smtpServer.port());
      params.put("externalRequestId", "smtp-request-" + suffix);
      params.put("receiptCode", "R-E2E-SMTP-" + suffix);
      params.put("ackRequired", false);
      params.put("forceRetry", false);
      launchService.launch(new LaunchRequest(
          TENANT,
          seed.workflowDefinitionId(),
          LocalDate.of(2026, 1, 15),
          TriggerType.API,
          seed.requestId(),
          "e2e-tr-smtp-" + suffix,
          params));
      e2eOutboxPublishSupport.publishAllPending(TENANT);

      Long taskId =
          jdbcTemplate.queryForObject(selectTaskIdSql, Long.class, TENANT, seed.dedupKey());
      Long partitionId =
          jdbcTemplate.queryForObject(selectPartitionIdSql, Long.class, TENANT, seed.dedupKey());
      await()
          .atMost(Duration.ofSeconds(120))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(() -> {
            final String taskStatus =
                jdbcTemplate.queryForObject(selectTaskStatusByIdSql, String.class, TENANT, taskId);
            final String taskErrorCode = jdbcTemplate.queryForObject(
                selectTaskErrorCodeByIdSql, String.class, TENANT, taskId);
            final Integer waitingRetryCount = jdbcTemplate.queryForObject(
                selectRetryCountSql, Integer.class, TENANT, partitionId);

            assertThat(taskStatus).isEqualTo("FAILED");
            assertThat(taskErrorCode).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
            assertThat(waitingRetryCount).isZero();
          });

      assertThat(smtpServer.awaitAcceptedMessage(Duration.ofSeconds(1))).isTrue();
      assertThat(smtpServer.acceptedMessageCount()).isEqualTo(1);
      final Integer dispatchCount = jdbcTemplate.queryForObject(
          selectDispatchCountSql, Integer.class, TENANT, fileId, channelCode);
      final Integer unknownDispatchCount = jdbcTemplate.queryForObject(
          selectUnknownDispatchCountSql, Integer.class, TENANT, fileId, channelCode);
      final String fileStatus =
          jdbcTemplate.queryForObject(selectFileStatusSql, String.class, TENANT, fileId);

      assertThat(dispatchCount).isEqualTo(1);
      assertThat(unknownDispatchCount).isEqualTo(1);
      assertThat(fileStatus).isEqualTo(FileStatus.FAILED.code());
    }
  }

  @Test
  @DisplayName("DISPATCH dry-run 成功收口但不改变文件状态、不写回执或审计")
  void shouldSkipChannelAndAuditSideEffects_whenDispatchDryRunCompletes() {
    final String selectRunStatusSql = """
              select wr.run_status, ji.instance_status, task.task_status
              from batch.trigger_request tr
              join batch.workflow_run wr on wr.tenant_id = tr.tenant_id
                  and wr.trigger_request_id = tr.id
              join batch.workflow_node_run node_run on node_run.workflow_run_id = wr.id
                  and node_run.node_type = 'JOB'
              join batch.job_instance ji on ji.tenant_id = wr.tenant_id
                  and ji.id = node_run.job_instance_id
              join batch.job_task task on task.tenant_id = ji.tenant_id
                  and task.job_instance_id = ji.id
              where tr.tenant_id = ? and tr.request_id = ?
              """;
    final String selectFileStatusSql = """
        select file_status from batch.file_record where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
        select count(*)::int from batch.file_dispatch_record where tenant_id = ? and file_id = ?\
        """;
    final String selectCountSql2 = """
        select count(*)::int from batch.file_audit_log where tenant_id = ? and file_id = ?\
        """;

    upsertLocalChannel("e2e_local_dispatch");
    String suffix = Long.toUnsignedString(System.nanoTime());
    Long fileId = insertGeneratedFile(
        "e2e-dry-dis-" + suffix,
        "e2e-dry-dis-" + suffix + ".json",
        "/tmp/e2e-dry-dispatch-" + suffix + ".json",
        "e2e-dry-dis-trace-" + suffix);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        new E2eScenarioFixture.LaunchPreparationSpec(
                jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API)
            .dryRun(true));

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileId", String.valueOf(fileId));
    params.put("channelCode", "e2e_local_dispatch");
    params.put("dispatchTarget", "/tmp");
    params.put("externalRequestId", "e2e-dry-ext-" + suffix);
    params.put("receiptCode", "R-E2E-DRY-DISPATCH");
    params.put("ackRequired", false);
    params.put("forceRetry", false);

    launchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(LocalDate.of(2026, 1, 15))
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("e2e-tr-dispatch-dry-run-" + suffix)
        .params(params)
        .dryRun(true)
        .build());
    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Map<String, Object> run =
              jdbcTemplate.queryForMap(selectRunStatusSql, TENANT, seed.requestId());
          assertThat(run)
              .containsEntry("run_status", "SUCCESS_DRY_RUN")
              .containsEntry("instance_status", "SUCCESS_DRY_RUN")
              .containsEntry("task_status", "SUCCESS");
        });

    String fileStatus =
        jdbcTemplate.queryForObject(selectFileStatusSql, String.class, TENANT, fileId);
    Integer dispatchCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, fileId);
    Integer auditCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, fileId);
    assertThat(fileStatus).isEqualTo(FileStatus.GENERATED.code());
    assertThat(dispatchCount).isZero();
    assertThat(auditCount).isZero();
  }

  @Test
  @DisplayName("已知失败的文件治理重派经真实 Worker 再次投递并写回执")
  void shouldDeliverRedispatchedFileThroughWorker() throws IOException {
    assertRedispatchedFileThroughWorker(false);
  }

  @Test
  @DisplayName("同一文件多渠道时仅重派目标任务并由 Worker 投递到目标渠道")
  void shouldDeliverOnlySelectedChannelWhenRedispatchingSameFile() throws IOException {
    assertRedispatchedFileThroughWorker(true);
  }

  private void assertRedispatchedFileThroughWorker(boolean competingChannel) throws IOException {
    final String selectJobTaskSql1 = """
        select t.id as task_id, t.job_partition_id, t.job_instance_id, ji.job_code
        from batch.job_task t join batch.job_instance ji on ji.tenant_id=t.tenant_id and ji.id=t.job_instance_id
        where t.tenant_id=? and ji.dedup_key=?
        """;
    final String updateOutboxEventSql2 =
        "update batch.outbox_event set publish_status='PUBLISHED' where tenant_id=? and aggregate_type='JOB_TASK' and aggregate_id=?";
    final String updateJobTaskSql3 =
        "update batch.job_task set task_status='FAILED', version=version+1 where tenant_id=? and id=?";
    final String updateJobPartitionSql4 =
        "update batch.job_partition set partition_status='FAILED', version=version+1 where tenant_id=? and id=?";
    final String insertJobPartitionSql5 = """
          insert into batch.job_partition
            (tenant_id, job_instance_id, job_instance_biz_date, partition_no, partition_status,
             business_key, idempotency_key)
          select tenant_id, id, biz_date, 2, 'FAILED', ?, ? from batch.job_instance
          where tenant_id = ? and id = ? returning id
          """;
    final String insertJobTaskSql6 = """
          insert into batch.job_task
            (tenant_id, job_instance_id, job_instance_biz_date, job_partition_id, task_type,
             task_seq, task_status, task_payload)
          select tenant_id, id, biz_date, ?, 'DISPATCH', 2, 'FAILED',
                 jsonb_build_object('fileId', ?, 'channelCode', ?)
          from batch.job_instance where tenant_id = ? and id = ? returning id
          """;
    final String insertJobStepInstanceSql7 = """
          insert into batch.job_step_instance
            (tenant_id, job_instance_id, job_partition_id, job_task_id, step_code,
             step_type, step_status, related_file_id)
          values (?, ?, ?, ?, 'DISPATCH_OTHER', 'DISPATCH', 'FAILED', ?)
          """;
    final String insertPipelineInstanceSql8 = """
        insert into batch.pipeline_instance
          (tenant_id, system_pipeline_code, system_pipeline_version, job_code, pipeline_type,
           file_id, related_job_instance_id, run_status)
        values (?, 'DISPATCH', 1, ?, 'DISPATCH', ?, ?, 'FAILED') returning id
        """;
    final String insertFileDispatchRecordSql9 = """
        insert into batch.file_dispatch_record
          (tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_status, receipt_status)
        values (?, ?, ?, ?, 'FAILED', 'FAILED')
        """;
    final String selectJobTaskSql10 =
        "select task_payload ->> 'run_mode' from batch.job_task where tenant_id=? and id=?";
    final String selectJobTaskSql11 =
        "select task_status from batch.job_task where tenant_id=? and id=?";
    final String selectFileDispatchRecordSql12 =
        "select dispatch_status from batch.file_dispatch_record where tenant_id=? and file_id=? and channel_code=?";
    final String selectJobTaskSql13 =
        "select task_status from batch.job_task where tenant_id=? and id=?";
    final String selectFileDispatchRecordSql14 =
        "select count(*) from batch.file_dispatch_record where tenant_id=? and file_id=? and channel_code=?";
    final String selectOutboxEventSql15 =
        "select count(*) from batch.outbox_event where tenant_id=? and aggregate_type='JOB_TASK' and aggregate_id=?";
    String channelCode = "e2e_redispatch_" + Long.toUnsignedString(System.nanoTime());
    String otherChannel = channelCode + "_other";
    upsertLocalChannel(channelCode);
    Long fileId = insertGeneratedFile(
        "redispatch-" + channelCode, "redispatch.json", "/tmp/" + channelCode, channelCode);
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        new E2eScenarioFixture.LaunchPreparationSpec(
            jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API));
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileId", String.valueOf(fileId));
    params.put("channelCode", channelCode);
    params.put("ackRequired", false);
    params.put("forceRetry", false);
    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.API,
        seed.requestId(),
        "redispatch-" + channelCode,
        params));
    Map<String, Object> task = jdbcTemplate.queryForMap(selectJobTaskSql1, TENANT, seed.dedupKey());
    long taskId = ((Number) task.get("task_id")).longValue();
    long partitionId = ((Number) task.get("job_partition_id")).longValue();
    long instanceId = ((Number) task.get("job_instance_id")).longValue();
    // 构造已知失败现场；初始事件未交给 Worker，避免与重派事件竞争。
    jdbcTemplate.update(updateOutboxEventSql2, TENANT, taskId);
    jdbcTemplate.update(updateJobTaskSql3, TENANT, taskId);
    jdbcTemplate.update(updateJobPartitionSql4, TENANT, partitionId);
    Long otherTaskId = null;
    if (competingChannel) {
      upsertLocalChannel(otherChannel);
      Long otherPartitionId = jdbcTemplate.queryForObject(
          insertJobPartitionSql5, Long.class, otherChannel, otherChannel, TENANT, instanceId);
      otherTaskId = jdbcTemplate.queryForObject(
          insertJobTaskSql6,
          Long.class,
          otherPartitionId,
          fileId,
          otherChannel,
          TENANT,
          instanceId);
      jdbcTemplate.update(
          insertJobStepInstanceSql7, TENANT, instanceId, otherPartitionId, otherTaskId, fileId);
    }
    Long pipelineId = jdbcTemplate.queryForObject(
        insertPipelineInstanceSql8, Long.class, TENANT, task.get("job_code"), fileId, instanceId);
    jdbcTemplate.update(insertFileDispatchRecordSql9, TENANT, fileId, pipelineId, channelCode);

    assertThat(fileGovernanceService.redispatchFile(FileGovernanceCommand.builder()
            .tenantId(TENANT)
            .fileId(fileId)
            .channelCode(channelCode)
            .operatorId("e2e")
            .build()))
        .isEqualTo("REDISPATCH_ACCEPTED");
    assertThat(jdbcTemplate.queryForObject(selectJobTaskSql10, String.class, TENANT, taskId))
        .isEqualTo("COMPENSATE");
    e2eOutboxPublishSupport.publishAllPending(TENANT);
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(() -> {
          assertThat(jdbcTemplate.queryForObject(selectJobTaskSql11, String.class, TENANT, taskId))
              .isEqualTo("SUCCESS");
          assertThat(jdbcTemplate.queryForObject(
                  selectFileDispatchRecordSql12, String.class, TENANT, fileId, channelCode))
              .isIn("SENT", "ACKED");
        });
    if (competingChannel) {
      assertThat(jdbcTemplate.queryForObject(selectJobTaskSql13, String.class, TENANT, otherTaskId))
          .isEqualTo("FAILED");
      assertThat(jdbcTemplate.queryForObject(
              selectFileDispatchRecordSql14, Integer.class, TENANT, fileId, otherChannel))
          .isZero();
      assertThat(jdbcTemplate.queryForObject(
              selectOutboxEventSql15, Integer.class, TENANT, otherTaskId))
          .isZero();
    }
    try (var files = Files.list(tempDir.resolve("local-outbox"))) {
      List<Path> envelopes = files
          .filter(path -> path.getFileName().toString().startsWith(channelCode + "-"))
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .toList();
      assertThat(envelopes).hasSize(1);
      JsonNode payload = JsonUtils.fromJson(Files.readString(envelopes.getFirst()), JsonNode.class)
          .path("payload");
      assertThat(payload.path("fileId").asLong()).isEqualTo(fileId);
      assertThat(payload.path("channelCode").asText()).isEqualTo(channelCode);
    }
    if (competingChannel) {
      try (var files = Files.list(tempDir.resolve("local-outbox"))) {
        assertThat(
                files.filter(path -> path.getFileName().toString().startsWith(otherChannel + "-")))
            .isEmpty();
      }
    }
  }

  @Test
  @DisplayName("两个文件组成的批量派发任务展开为两个子任务并各自完成渠道投递, 两个任务均为成功终态, 两个文件状态为已派发且各写入一条投递回执")
  void shouldDeliverEveryFile_whenBundleDispatchTaskExpandsIntoSubtasks() throws IOException {
    final String selectCountSql = """
                      select count(*)::int from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ? and t.task_status = 'SUCCESS'
                      """;
    final String selectCountSql2 = """
            select count(*)::int from batch.file_record
            where tenant_id = ? and id in (?, ?) and file_status = 'DISPATCHED'
            """;
    final String selectCountSql3 = """
            select count(*)::int from batch.file_dispatch_record
            where tenant_id = ? and file_id in (?, ?) and channel_code = ?
            """;
    final String selectBoundStepsSql = """
            select count(*)::int from batch.job_task t
            join batch.job_instance ji on ji.tenant_id = t.tenant_id and ji.id = t.job_instance_id
            join batch.job_partition p on p.tenant_id = t.tenant_id and p.id = t.job_partition_id
            join batch.job_step_instance s on s.tenant_id = t.tenant_id and s.job_task_id = t.id
            where ji.tenant_id = ? and ji.dedup_key = ? and s.related_file_id = p.source_file_id
              and (t.task_payload ->> 'bundleSourceFileId')::bigint = p.source_file_id
              and t.task_payload ->> 'bundleTargetRef' = p.target_ref
            """;

    upsertLocalChannel("e2e_local_dispatch");
    upsertLocalChannel("e2e_decoy_dispatch");
    Long fileOne = insertGeneratedFile(
        "e2e-bundle-dis-1",
        "e2e-bundle-1.json",
        "/tmp/e2e-bundle-dispatch-" + System.nanoTime() + "-1.json",
        "e2e-bundle-dis-trace-1");
    Long fileTwo = insertGeneratedFile(
        "e2e-bundle-dis-2",
        "e2e-bundle-2.json",
        "/tmp/e2e-bundle-dispatch-" + System.nanoTime() + "-2.json",
        "e2e-bundle-dis-trace-2");

    LaunchSeed seed = E2eScenarioFixture.prepareBundleLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "BUNDLE_DISPATCH", "dispatch");

    Map<String, Object> params = new LinkedHashMap<>();
    // 冲突的通用及束字段不能覆盖每个分区的文件与渠道绑定。
    params.put("fileId", String.valueOf(fileOne));
    params.put("channelCode", "e2e_decoy_dispatch");
    params.put("bundleSourceFileId", fileOne);
    params.put("bundleTargetRef", "e2e_decoy_dispatch");
    params.put("receiptCode", "R-E2E-BUNDLE-DISPATCH");
    params.put("ackRequired", false);
    params.put("forceRetry", false);
    params.put(
        "bundleFiles",
        List.of(
            Map.of("sourceFileId", fileOne, "targetRef", "e2e_local_dispatch"),
            Map.of("sourceFileId", fileTwo, "targetRef", "e2e_local_dispatch")));

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        LocalDate.of(2026, 1, 15),
        TriggerType.EVENT,
        seed.requestId(),
        "e2e-tr-bundle-dispatch",
        params));

    e2eOutboxPublishSupport.publishAllPending(TENANT);

    await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(() -> {
          Integer successfulTasks =
              jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, seed.dedupKey());
          assertThat(successfulTasks).isEqualTo(2);
        });

    Integer dispatchedFiles =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, fileOne, fileTwo);
    assertThat(dispatchedFiles).isEqualTo(2);

    Integer receipts = jdbcTemplate.queryForObject(
        selectCountSql3, Integer.class, TENANT, fileOne, fileTwo, "e2e_local_dispatch");
    assertThat(receipts).isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject(
            selectCountSql3, Integer.class, TENANT, fileOne, fileTwo, "e2e_decoy_dispatch"))
        .isZero();
    assertThat(jdbcTemplate.queryForObject(
            selectBoundStepsSql, Integer.class, TENANT, seed.dedupKey()))
        .isEqualTo(2);
    try (var files = Files.list(tempDir.resolve("local-outbox"))) {
      List<Path> envelopes =
          files.filter(path -> path.getFileName().toString().endsWith(".json")).toList();
      assertThat(envelopes).hasSize(2);
      assertThat(envelopes)
          .allSatisfy(
              path -> assertThat(path.getFileName().toString()).startsWith("e2e_local_dispatch-"));
      List<Long> publishedFiles = new ArrayList<>();
      for (Path envelope : envelopes) {
        JsonNode published = JsonUtils.fromJson(Files.readString(envelope), JsonNode.class);
        publishedFiles.add(published.path("payload").path("fileId").asLong());
        assertThat(published.path("payload").path("channelCode").asText())
            .isEqualTo("e2e_local_dispatch");
      }
      assertThat(publishedFiles).containsExactlyInAnyOrder(fileOne, fileTwo);
    }
  }

  private void assertUnknownDispatchFailure(LaunchSeed seed, Long fileId, String channelCode) {
    final String selectTaskOutcomeSql = """
        select task.task_status, task.error_code, task.failure_class, task.job_partition_id,
               instance.instance_status
        from batch.job_instance instance
        join batch.job_task task on task.tenant_id=instance.tenant_id
            and task.job_instance_id=instance.id
        where instance.tenant_id=? and instance.dedup_key=?
        """;
    final String selectRetryCountSql = """
        select count(*) from batch.retry_schedule
        where tenant_id=? and related_type='JOB_PARTITION' and related_id=?
        """;
    final String selectDeadLetterSql = """
        select count(*) as dead_letter_count, coalesce(max(max_replay_count), -1) as replay_budget
        from batch.dead_letter_task
        where tenant_id=? and source_type='JOB_PARTITION' and source_id=?
        """;
    final String selectDispatchSql = """
        select dispatch_status, dispatch_attempt, error_code
        from batch.file_dispatch_record where tenant_id=? and file_id=? and channel_code=?
        """;
    final String selectWorkflowStatusSql = """
        select run.run_status from batch.trigger_request request
        join batch.workflow_run run on run.tenant_id=request.tenant_id
            and run.trigger_request_id=request.id
        where request.tenant_id=? and request.request_id=?
        """;
    final String selectFileStatusSql = """
        select file_status from batch.file_record where tenant_id=? and id=?
        """;
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Map<String, Object> outcome =
              jdbcTemplate.queryForMap(selectTaskOutcomeSql, TENANT, seed.dedupKey());
          assertThat(outcome.get("task_status")).isEqualTo("FAILED");
          assertThat(outcome.get("error_code")).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
          assertThat(outcome.get("failure_class")).isEqualTo("UNKNOWN");
          assertThat(outcome.get("instance_status")).isEqualTo("FAILED");
          Long partitionId = ((Number) outcome.get("job_partition_id")).longValue();
          Long scheduledRetries =
              jdbcTemplate.queryForObject(selectRetryCountSql, Long.class, TENANT, partitionId);
          assertThat(scheduledRetries).isZero();
          Map<String, Object> deadLetter =
              jdbcTemplate.queryForMap(selectDeadLetterSql, TENANT, partitionId);
          assertThat(deadLetter.get("dead_letter_count")).isEqualTo(1L);
          assertThat(deadLetter.get("replay_budget")).isEqualTo(0);
          String workflowStatus = jdbcTemplate.queryForObject(
              selectWorkflowStatusSql, String.class, TENANT, seed.requestId());
          assertThat(workflowStatus).isEqualTo("FAILED");
        });
    Map<String, Object> dispatch =
        jdbcTemplate.queryForMap(selectDispatchSql, TENANT, fileId, channelCode);
    assertThat(dispatch.get("dispatch_status")).isEqualTo("FAILED");
    assertThat(dispatch.get("dispatch_attempt")).isEqualTo(1);
    assertThat(dispatch.get("error_code")).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
    String fileStatus =
        jdbcTemplate.queryForObject(selectFileStatusSql, String.class, TENANT, fileId);
    assertThat(fileStatus).isEqualTo("FAILED");
  }

  private void upsertLocalChannel(String channelCode) {
    final String insertFileChannelConfigSql = """
        insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
            config_json, receipt_policy, timeout_seconds, enabled
        )
        select
            ?, ?, ?, 'LOCAL', null, 'NONE',
            jsonb_build_object(
                'target_endpoint', ?,
                'receipt_policy', 'NONE',
                'channel_type', 'LOCAL',
                'channel_code', ?
            ),
            'NONE', 10, true
        on conflict (tenant_id, channel_code) do update
        set target_endpoint=excluded.target_endpoint, config_json=excluded.config_json
        """;

    Path outboxDirectory = tempDir.resolve("local-outbox");
    String outboxEndpoint = outboxDirectory.toString();
    jdbcTemplate.update(
        insertFileChannelConfigSql,
        TENANT,
        channelCode,
        "E2E local dispatch",
        outboxEndpoint,
        channelCode);
  }

  private Long insertGeneratedFile(
      String fileCode, String fileName, String storagePath, String traceId) {
    final String insertFileRecordSql = """
        insert into batch.file_record (
            tenant_id, file_code, biz_type, file_category, file_name, original_file_name, file_ext,
            file_format_type, charset, mime_type, file_size_bytes, checksum_type, storage_type,
            storage_path, source_type, file_status, biz_date, trace_id
        ) values (
            ?, ?, 'OUTPUT', 'OUTPUT', ?, ?, 'json',
            'JSON', 'UTF-8', 'application/json', 32, 'NONE', 'LOCAL',
            ?, 'SYSTEM', 'GENERATED', date '2026-01-15', ?
        ) returning id
        """;

    return jdbcTemplate.queryForObject(
        insertFileRecordSql,
        Long.class,
        TENANT,
        fileCode,
        fileName,
        fileName,
        storagePath,
        traceId);
  }
}
