package com.example.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.engine.OutboxPublisher;
import com.example.batch.orchestrator.config.BatchMqTopicsProperties;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.domain.query.OutboxEventQuery;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Worker 进程重启恢复 E2E 测试：验证 Dispatch Worker 进程重启后，
 * 重新注册并恢复消费 Kafka 派发消息，完成文件分发任务。
 *
 * <p>测试流程：启动 worker1 → 杀掉 worker1 → 启动 worker2（同 consumer group）→
 * 等待 Kafka rebalance 完成 → 触发 job → 等待任务成功。
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,"
                        + "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration,"
                        + "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration,"
                        + "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration,"
                        + "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration,"
                        + "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration",
                "batch.sla.enabled=false",
                "batch.worker.drain.enabled=false",
                "batch.file-governance.latency.enabled=false",
                "batch.file-governance.archive.enabled=false",
                "batch.file-governance.reconcile.enabled=false",
                "batch.file-governance.arrival.enabled=false",
                "batch.file-governance.access.enabled=false",
                "batch.outbox.poll-interval-millis=600000",
                "batch.retry.poll-interval-millis=600000",
                "batch.partition-lease.reclaim-interval-millis=600000",
                // 必须保持较短间隔：如果 launch 在 Worker 注册表可见之前就已执行，WaitingPartitionDispatchScheduler
                // 是唯一为 CREATED/WAITING 任务写入派发 outbox 的路径。
                "batch.resource-scheduler.waiting-dispatch-interval-millis=500",
                "batch.resource-scheduler.quota-reset-scan-interval-millis=600000",
                "batch.scheduler.snapshot-persist-enabled=false"
        })
@ActiveProfiles({"test", "e2e"})
@Tag("e2e")
class WorkerProcessRestartRecoveryE2eIT extends AbstractIntegrationTest {

    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);
    private static final String JAVA_BIN = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    private static final long WORKER_START_TIMEOUT_SECONDS = 60L;
    private static final long WORKER_BUILD_TIMEOUT_MINUTES = 10L;

    @Autowired
    private LaunchService launchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @Autowired
    private BatchMqTopicsProperties batchMqTopicsProperties;

    @Value("${spring.kafka.bootstrap-servers}")
    private String kafkaBootstrapServers;

    @LocalServerPort
    private int localServerPort;

    @Test
    void workerProcessCanRestartAndContinueDispatching() throws Exception {
        String suffix = Long.toUnsignedString(System.nanoTime());
        String tenantId = "t-restart-" + suffix;
        String workerCode = "restart-dispatch-" + suffix;
        String consumerGroupId = "batch-worker-dispatch-restart-" + suffix;
        String channelCode = "local-" + suffix;
        Path targetDir = Files.createTempDirectory("dispatch-restart-target-");
        Path sourceFile = Files.createTempFile("dispatch-restart-source-", ".txt");
        byte[] content = ("restart-payload-" + suffix).getBytes(StandardCharsets.UTF_8);
        Files.write(sourceFile, content);

        seedDispatchChannel(tenantId, channelCode, targetDir);
        Long fileId = seedDispatchFile(tenantId, sourceFile, suffix);
        LaunchSeed seed = seedDispatchJob(tenantId, "dispatch", TriggerType.API, suffix);

        Process worker1 = null;
        Process worker2 = null;
        Path worker1Log = Files.createTempFile("dispatch-worker-1-", ".log");
        Path worker2Log = Files.createTempFile("dispatch-worker-2-", ".log");
        try {
            worker1 = startDispatchWorker(tenantId, workerCode, consumerGroupId, worker1Log);
            awaitLogContains(worker1Log, "Started BatchWorkerDispatchApplication");

            stopProcess(worker1);
            worker1 = null;

            worker2 = startDispatchWorker(tenantId, workerCode, consumerGroupId, worker2Log);
            awaitLogContains(worker2Log, "Started BatchWorkerDispatchApplication");
            awaitWorkerOnlineInRegistry(tenantId, workerCode);
            // Kafka Consumer Group Rebalance 在 Worker 注册后还需要额外时间完成分区分配。
            // 必须等到 consumer group 进入 STABLE 状态后再触发 job，否则 dispatch 消息
            // 到达时 worker 尚未被分配分区，导致任务停留在 READY 状态直到超时。
            awaitKafkaConsumerGroupStable(consumerGroupId);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("fileId", String.valueOf(fileId));
            params.put("channelCode", channelCode);
            params.put("dispatchTarget", targetDir.toString());
            params.put("externalRequestId", "restart-ext-" + suffix);
            params.put("receiptCode", "R-RESTART-" + suffix);
            params.put("ackRequired", false);
            params.put("forceRetry", false);

            launchService.launch(new LaunchRequest(
                    tenantId,
                    seed.jobCode(),
                    BIZ_DATE,
                    TriggerType.API,
                    seed.requestId(),
                    "restart-trace-" + suffix,
                    params));

            publishPendingOutbox(tenantId);

            await().atMost(Duration.ofSeconds(300)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                String status = jdbcTemplate.queryForObject(
                        """
                                select t.task_status
                        from batch.job_task t
                        join batch.job_instance ji on ji.id = t.job_instance_id
                        where ji.tenant_id = ? and ji.dedup_key = ?
                        """,
                        String.class,
                        tenantId,
                        seed.dedupKey());
                assertThat(status).isEqualTo("SUCCESS");
            });

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(hasAnyFile(targetDir)).isTrue());
        } finally {
            stopProcess(worker1);
            stopProcess(worker2);
        }
    }

    private void awaitKafkaConsumerGroupStable(String consumerGroupId) {
        Map<String, Object> config = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        try (AdminClient adminClient = AdminClient.create(config)) {
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
                Map<String, ConsumerGroupDescription> descriptions =
                        adminClient.describeConsumerGroups(List.of(consumerGroupId)).all().get(10, TimeUnit.SECONDS);
                ConsumerGroupDescription desc = descriptions.get(consumerGroupId);
                assertThat(desc).isNotNull();
                assertThat(desc.state()).isEqualTo(ConsumerGroupState.STABLE);
                assertThat(desc.members()).isNotEmpty();
            });
        }
    }

    private void seedDispatchChannel(String tenantId, String channelCode, Path targetDir) {
        jdbcTemplate.update(
                "delete from batch.file_channel_config where tenant_id = ? and channel_code = ?",
                tenantId,
                channelCode);
        jdbcTemplate.update(
                """
                        insert into batch.file_channel_config (
                            tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
                            config_json, receipt_policy, timeout_seconds, enabled
                        ) values (
                            ?, ?, ?, 'LOCAL', ?, 'NONE',
                            jsonb_build_object(
                                'target_endpoint', ?,
                                'receipt_policy', 'NONE',
                                'channel_type', 'LOCAL',
                                'channel_code', ?
                            ),
                            'NONE', 10, true
                        )
                        """,
                tenantId,
                channelCode,
                "Restart local dispatch " + channelCode,
                targetDir.toString(),
                targetDir.toString(),
                channelCode);
    }

    private Long seedDispatchFile(String tenantId, Path sourceFile, String suffix) {
        return jdbcTemplate.queryForObject(
                """
                        insert into batch.file_record (
                            tenant_id, file_code, biz_type, file_category, file_name, original_file_name, file_ext,
                            file_format_type, charset, mime_type, file_size_bytes, checksum_type, storage_type,
                            storage_path, source_type, file_status, biz_date, trace_id
                        ) values (
                            ?, ?, 'FILE', 'OUTPUT', ?, ?, 'txt',
                            'DELIMITED', 'UTF-8', 'text/plain', ?, 'NONE', 'LOCAL',
                            ?, 'SYSTEM', 'GENERATED', ?, ?
                        ) returning id
                        """,
                Long.class,
                tenantId,
                "dispatch-file-" + suffix,
                sourceFile.getFileName().toString(),
                sourceFile.getFileName().toString(),
                sourceFile.toFile().length(),
                sourceFile.toString(),
                BIZ_DATE,
                "restart-file-trace-" + suffix);
    }

    private LaunchSeed seedDispatchJob(String tenantId, String workerGroup, TriggerType triggerType, String suffix) {
        String jobCode = "RESTART_DISPATCH_" + suffix;
        String requestId = "restart-req-" + suffix;
        String dedupKey = "restart-dedup-" + suffix;

        jdbcTemplate.update(
                "delete from batch.trigger_request where tenant_id = ? and request_id = ?",
                tenantId,
                requestId);
        jdbcTemplate.update(
                "delete from batch.workflow_definition where tenant_id = ? and workflow_code = ?",
                tenantId,
                jobCode);
        jdbcTemplate.update(
                "delete from batch.job_definition where tenant_id = ? and job_code = ?",
                tenantId,
                jobCode);

        jdbcTemplate.update(
                """
                        insert into batch.job_definition (
                            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
                            priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
                            retry_policy, retry_max_count, timeout_seconds, enabled, version
                        ) values (?, ?, ?, 'DISPATCH', 'FILE', 'MANUAL', 'UTC',
                            5, 'q-restart', ?, 'API', false, 'NONE',
                            'NONE', 0, 0, true, 1)
                        """,
                tenantId, jobCode, "restart dispatch " + jobCode, workerGroup);

        jdbcTemplate.update(
                """
                        insert into batch.workflow_definition (
                            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
                        ) values (?, ?, 'restart wf', 'DAG', 1, true)
                        """,
                tenantId, jobCode);

        jdbcTemplate.update(
                """
                        insert into batch.trigger_request (
                            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_status, trace_id
                        ) values (?, ?, ?, ?, ?, ?, 'ACCEPTED', ?)
                        """,
                tenantId, requestId, triggerType.code(), jobCode, BIZ_DATE, dedupKey, "restart-trace-" + suffix);

        return new LaunchSeed(jobCode, requestId, dedupKey);
    }

    private void publishPendingOutbox(String tenantId) {
        List<OutboxEventEntity> pending = outboxEventMapper.selectPending(
                new OutboxEventQuery(
                        tenantId,
                        null,
                        null,
                        new PageRequest(1, 500),
                        OutboxPublishStatus.NEW.code(),
                        OutboxPublishStatus.FAILED.code(),
                        null,
                        null,
                        null));
        ensureTopicsExist(pending);
        for (OutboxEventEntity event : pending) {
            outboxPublisher.publish(event);
        }
    }

    private void ensureTopicsExist(List<OutboxEventEntity> pendingEvents) {
        if (pendingEvents == null || pendingEvents.isEmpty()) {
            return;
        }
        Set<String> topics = pendingEvents.stream()
                .map(this::resolveTargetTopic)
                .filter(topic -> topic != null && !topic.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (topics.isEmpty()) {
            return;
        }
        Map<String, Object> config = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        try (AdminClient adminClient = AdminClient.create(config)) {
            List<NewTopic> newTopics = topics.stream()
                    .map(topic -> new NewTopic(topic, 1, (short) 1))
                    .toList();
            adminClient.createTopics(newTopics).all().get();
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof TopicExistsException) {
                return;
            }
            throw new IllegalStateException("failed to create dispatch topics: " + topics, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to create dispatch topics: " + topics, ex);
        }
    }

    private String resolveTargetTopic(OutboxEventEntity event) {
        String baseTopic = batchMqTopicsProperties.resolveDispatchTopic(event.getEventType());
        if (baseTopic == null || baseTopic.isBlank()) {
            return null;
        }
        Object payload = JsonUtils.fromJson(event.getPayloadJson(), Object.class);
        if (payload instanceof Map<?, ?> payloadMap) {
            Object selectedWorkerId = payloadMap.get("selectedWorkerId");
            if (selectedWorkerId != null && !String.valueOf(selectedWorkerId).isBlank()) {
                return BatchTopics.directDispatchTopic(baseTopic, String.valueOf(selectedWorkerId));
            }
        }
        return baseTopic;
    }

    private Process startDispatchWorker(String tenantId, String workerCode, String consumerGroupId, Path logFile)
            throws IOException, InterruptedException {
        Path workerExecJar = locateWorkerExecJar();
        List<String> command = List.of(
                JAVA_BIN,
                "-jar",
                workerExecJar.toString(),
                "--spring.main.web-application-type=none",
                "--spring.datasource.url=" + platformJdbcUrl(),
                "--spring.datasource.username=batch_user",
                "--spring.datasource.password=batch_pass_123",
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.kafka.bootstrap-servers=" + kafkaBootstrapServers,
                "--batch.orchestrator.base-url=http://127.0.0.1:" + localServerPort,
                "--batch.worker.task-client.base-url=http://127.0.0.1:" + localServerPort,
                "--batch.worker.registry.fail-fast-on-startup=false",
                "--batch.security.testing-open=true",
                "--batch.security.kms.default-key-ref=DEFAULT_TEST",
                "--batch.security.kms.keys.DEFAULT_TEST=AAAAAAAAAAAAAAAAAAAAAA==",
                "--batch.storage.minio.endpoint=" + minioEndpoint(),
                "--batch.storage.minio.access-key=minioadmin",
                "--batch.storage.minio.secret-key=minioadmin123",
                "--batch.storage.minio.bucket=" + minioBucket(),
                "--batch.worker.type=DISPATCH",
                "--batch.worker.dispatch.worker-code=" + workerCode,
                "--batch.worker.dispatch.worker-type=DISPATCH",
                "--batch.worker.dispatch.tenant-id=" + tenantId,
                "--batch.worker.dispatch.heartbeat-interval-millis=1000",
                "--batch.worker.dispatch.topic=batch.task.dispatch.dispatch",
                "--batch.worker.dispatch.consumer-group-id=" + consumerGroupId,
                "--batch.worker.dispatch.circuit-breaker.enabled=false",
                "--batch.worker.dispatch.health.enabled=false",
                "--batch.worker.dispatch.receipt-poll.enabled=false",
                "--batch.worker.lease.renew-interval-millis=1000"
        );
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        return builder.start();
    }

    private void awaitLogContains(Path logFile, String expectedText) {
        await().atMost(Duration.ofSeconds(WORKER_START_TIMEOUT_SECONDS)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            String logContent = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
            assertThat(logContent).contains(expectedText);
        });
    }

    private void awaitWorkerOnlineInRegistry(String tenantId, String workerCode) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                    """
                            select count(1)::int from batch.worker_registry
                            where tenant_id = ? and worker_code = ? and status = 'ONLINE' and worker_group = 'dispatch'
                            """,
                    Integer.class,
                    tenantId,
                    workerCode);
            assertThat(count).isEqualTo(1);
        });
    }

    private Path locateWorkerExecJar() throws IOException, InterruptedException {
        Path workerTargetDir = projectRoot().resolve("batch-worker-dispatch/target");
        Path execJar = findLatestExecJar(workerTargetDir);
        if (execJar != null) {
            return execJar;
        }
        buildWorkerDispatchArtifact();
        execJar = findLatestExecJar(workerTargetDir);
        if (execJar != null) {
            return execJar;
        }
        throw new IllegalStateException("worker-dispatch exec jar not found under " + workerTargetDir);
    }

    private void buildWorkerDispatchArtifact() throws IOException, InterruptedException {
        Path rootDir = projectRoot();
        Path logFile = Files.createTempFile("worker-dispatch-build-", ".log");
        ProcessBuilder builder = new ProcessBuilder(
                "mvn",
                "-q",
                "-pl",
                "batch-worker-dispatch",
                "-am",
                "-DskipTests",
                "package",
                "-Dsurefire.failIfNoSpecifiedTests=false");
        builder.directory(rootDir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process process = builder.start();
        try {
            if (!process.waitFor(WORKER_BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("timed out building batch-worker-dispatch; see " + logFile);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("failed to build batch-worker-dispatch; see " + logFile);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private Path findLatestExecJar(Path targetDir) throws IOException {
        if (!Files.isDirectory(targetDir)) {
            return null;
        }
        try (var stream = Files.list(targetDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith("-exec.jar"))
                    .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElse(null);
        }
    }

    private Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("batch-orchestrator/pom.xml"))
                    && Files.isRegularFile(current.resolve("batch-worker-dispatch/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("unable to locate project root from " + System.getProperty("user.dir"));
    }

    private void stopProcess(Process process) {
        if (process == null) {
            return;
        }
        if (!process.isAlive()) {
            return;
        }
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean hasAnyFile(Path directory) {
        try (var stream = Files.list(directory)) {
            return stream.findAny().isPresent();
        } catch (IOException ex) {
            return false;
        }
    }

    private record LaunchSeed(String jobCode, String requestId, String dedupKey) {
    }
}
