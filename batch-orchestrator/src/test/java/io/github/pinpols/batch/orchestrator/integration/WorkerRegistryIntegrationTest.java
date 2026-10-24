package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.entity.CustomTaskTypeRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.param.CustomTaskTypeUpsertParam;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowReplayCapabilityService;
import io.github.pinpols.batch.orchestrator.mapper.CustomTaskTypeRegistryMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;

/** 集成测试：WorkerRegistryMapper 在真实数据库上的持久化和查询。 覆盖排空生命周期：ONLINE → DRAINING → DECOMMISSIONED。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("工作节点注册表持久化集成:验证真实数据库上注册写入与按键查询 + 排空及退役状态流转 + 条件更新防止旧状态覆盖 + 端口落库回读与不带端口的心跳保留")
class WorkerRegistryIntegrationTest extends AbstractIntegrationTest {

  private final WorkerRegistryMapper workerRegistryMapper;

  private final CustomTaskTypeRegistryMapper customTaskTypeRegistryMapper;

  private final WorkflowReplayCapabilityService replayCapabilityService;

  WorkerRegistryIntegrationTest(
      WorkerRegistryMapper workerRegistryMapper,
      CustomTaskTypeRegistryMapper customTaskTypeRegistryMapper,
      WorkflowReplayCapabilityService replayCapabilityService) {
    this.workerRegistryMapper = workerRegistryMapper;
    this.customTaskTypeRegistryMapper = customTaskTypeRegistryMapper;
    this.replayCapabilityService = replayCapabilityService;
  }

  @Test
  @DisplayName("自定义任务类型上报时由 Java 语义方法提供活跃状态并正常落库")
  void shouldPersistActiveCustomTaskTypeThroughSemanticMapperMethod() {
    String taskTypeCode = "custom_it_" + BatchDateTimeSupport.utcEpochMillis();
    CustomTaskTypeUpsertParam param = CustomTaskTypeUpsertParam.builder()
        .tenantId("t1")
        .taskTypeCode(taskTypeCode)
        .displayName("Custom integration task")
        .descriptor("{\"taskType\":\"" + taskTypeCode + "\"}")
        .descriptorVersion("1")
        .declaredByWorkerCode("worker-it-custom")
        .build();

    assertThat(customTaskTypeRegistryMapper.upsertDeclared(param)).isEqualTo(1);

    CustomTaskTypeRegistryEntity stored =
        customTaskTypeRegistryMapper.selectByTenantAndCode("t1", taskTypeCode);
    assertThat(stored).isNotNull();
    assertThat(stored.status())
        .isEqualTo(CustomTaskTypeRegistryMapper.RegistryStatus.ACTIVE.name());
  }

  @Test
  @DisplayName("在线节点入库后可按键读回,状态与所属分组保持写入值")
  void shouldSaveAndFindOnlineWorker() {
    WorkerRegistryEntity worker = onlineWorker("t1", "worker-it-001", "DEFAULT");
    workerRegistryMapper.saveLikeSdj(worker);

    WorkerRegistryEntity found =
        workerRegistryMapper.selectByTenantAndWorkerCode("t1", "worker-it-001");

    assertThat(found).isNotNull();
    assertThat(found.status()).isEqualTo(WorkerRegistryStatus.ONLINE.code());
    assertThat(found.workerGroup()).isEqualTo("DEFAULT");
  }

  @Test
  @DisplayName("节点处于在线状态时启动排空生效,状态转为排空中并写入排空起始与截止时刻")
  void shouldTransitionToDrainingStatus() {
    WorkerRegistryEntity worker = onlineWorker("t1", "worker-it-drain", "DEFAULT");
    workerRegistryMapper.saveLikeSdj(worker);

    int updated = workerRegistryMapper.startDrainIfCurrent(
        "t1", "worker-it-drain", WorkerRegistryStatus.ONLINE.code(), 300);

    WorkerRegistryEntity found =
        workerRegistryMapper.selectByTenantAndWorkerCode("t1", "worker-it-drain");
    assertThat(updated).isEqualTo(1);
    assertThat(found.status()).isEqualTo(WorkerRegistryStatus.DRAINING.code());
    assertThat(found.drainStartedAt()).isNotNull();
    assertThat(found.drainDeadlineAt()).isNotNull();
  }

  @Test
  @DisplayName("节点已进入排空后,基于旧在线状态的条件更新不生效,排空状态不被覆盖")
  void shouldKeepDrainingStatus_whenStaleUpdateArrives() {
    String workerCode = "worker-it-cas-" + BatchDateTimeSupport.utcEpochMillis();
    workerRegistryMapper.saveLikeSdj(onlineWorker("t1", workerCode, "DEFAULT"));
    assertThat(workerRegistryMapper.startDrainIfCurrent(
            "t1", workerCode, WorkerRegistryStatus.ONLINE.code(), 300))
        .isEqualTo(1);

    int staleUpdate = workerRegistryMapper.updateStatusIfCurrent(
        "t1", workerCode, WorkerRegistryStatus.ONLINE.code(), WorkerRegistryStatus.OFFLINE.code());

    assertThat(staleUpdate).isZero();
    assertThat(
            workerRegistryMapper.selectByTenantAndWorkerCode("t1", workerCode).status())
        .isEqualTo(WorkerRegistryStatus.DRAINING.code());
  }

  @Test
  @DisplayName("节点退役后状态转为已退役,且排空起始与截止时刻被清空")
  void shouldTransitionToDecommissionedStatus() {
    WorkerRegistryEntity worker = onlineWorker("t1", "worker-it-decom", "DEFAULT");
    worker = workerRegistryMapper.saveLikeSdj(worker);

    worker = worker.withDecommissioned(BatchDateTimeSupport.utcNow());
    workerRegistryMapper.saveLikeSdj(worker);

    WorkerRegistryEntity found =
        workerRegistryMapper.selectByTenantAndWorkerCode("t1", "worker-it-decom");
    assertThat(found.status()).isEqualTo(WorkerRegistryStatus.DECOMMISSIONED.code());
    assertThat(found.drainStartedAt()).isNull();
    assertThat(found.drainDeadlineAt()).isNull();
  }

  @Test
  @DisplayName("全局心跳超时回收应跨租户离线 stale worker 且保留新鲜和退役节点")
  void shouldMarkStaleWorkersOfflineAcrossTenantsWithoutChangingFreshOrDecommissionedWorkers() {
    String suffix = "heartbeat-timeout-" + BatchDateTimeSupport.utcEpochMillis();
    Instant staleHeartbeat = BatchDateTimeSupport.utcNow().minusSeconds(600);
    Instant freshHeartbeat = BatchDateTimeSupport.utcNow();
    WorkerRegistryEntity tenantAOnline = onlineWorker(
            "worker-tenant-a", "stale-online-" + suffix, "DEFAULT")
        .withStatus(WorkerRegistryStatus.ONLINE.code(), staleHeartbeat);
    WorkerRegistryEntity tenantBDraining = onlineWorker(
            "worker-tenant-b", "stale-draining-" + suffix, "DEFAULT")
        .withDrain(
            WorkerRegistryStatus.DRAINING.code(),
            staleHeartbeat.minusSeconds(300),
            staleHeartbeat.minusSeconds(60),
            staleHeartbeat);
    WorkerRegistryEntity freshOnline = onlineWorker(
            "worker-tenant-a", "fresh-online-" + suffix, "DEFAULT")
        .withStatus(WorkerRegistryStatus.ONLINE.code(), freshHeartbeat);
    WorkerRegistryEntity decommissioned = onlineWorker(
            "worker-tenant-b", "decommissioned-" + suffix, "DEFAULT")
        .withDecommissioned(staleHeartbeat);
    workerRegistryMapper.saveLikeSdj(tenantAOnline);
    workerRegistryMapper.saveLikeSdj(tenantBDraining);
    workerRegistryMapper.saveLikeSdj(freshOnline);
    workerRegistryMapper.saveLikeSdj(decommissioned);

    int updated = workerRegistryMapper.markStaleHeartbeatsOffline(60);

    assertThat(updated).isEqualTo(2);
    assertThat(workerRegistryMapper
            .selectByTenantAndWorkerCode("worker-tenant-a", tenantAOnline.workerCode())
            .status())
        .isEqualTo(WorkerRegistryStatus.OFFLINE.code());
    assertThat(workerRegistryMapper
            .selectByTenantAndWorkerCode("worker-tenant-b", tenantBDraining.workerCode())
            .status())
        .isEqualTo(WorkerRegistryStatus.OFFLINE.code());
    assertThat(workerRegistryMapper
            .selectByTenantAndWorkerCode("worker-tenant-a", freshOnline.workerCode())
            .status())
        .isEqualTo(WorkerRegistryStatus.ONLINE.code());
    assertThat(workerRegistryMapper
            .selectByTenantAndWorkerCode("worker-tenant-b", decommissioned.workerCode())
            .status())
        .isEqualTo(WorkerRegistryStatus.DECOMMISSIONED.code());
  }

  @Test
  @DisplayName("按分组与在线状态查询只返回在线节点,同组离线节点被排除")
  void shouldFindWorkersByStatusAndGroup() {
    String uniqueGroup = "GROUP-IT-" + BatchDateTimeSupport.utcEpochMillis();
    WorkerRegistryEntity w1 = onlineWorker("t1", "w-grp-1-" + uniqueGroup, uniqueGroup);
    WorkerRegistryEntity w2 = onlineWorker("t1", "w-grp-2-" + uniqueGroup, uniqueGroup);
    WorkerRegistryEntity w3 = onlineWorker("t1", "w-offline-" + uniqueGroup, uniqueGroup)
        .withStatus(WorkerRegistryStatus.OFFLINE.code(), BatchDateTimeSupport.utcNow());
    workerRegistryMapper.saveLikeSdj(w1);
    workerRegistryMapper.saveLikeSdj(w2);
    workerRegistryMapper.saveLikeSdj(w3);

    List<WorkerRegistryEntity> online = workerRegistryMapper.selectByTenantAndWorkerGroupAndStatus(
        "t1", uniqueGroup, WorkerRegistryStatus.ONLINE.code());

    assertThat(online).hasSize(2);
    assertThat(online).allMatch(w -> WorkerRegistryStatus.ONLINE.code().equals(w.status()));
  }

  @Test
  @DisplayName("按分组统计在线节点数量,结果与库中在线条目数一致")
  void shouldCountActiveWorkersByGroup() {
    String uniqueGroup = "CNT-" + BatchDateTimeSupport.utcEpochMillis();
    WorkerRegistryEntity w1 = onlineWorker("t1", "cnt-w1-" + uniqueGroup, uniqueGroup);
    WorkerRegistryEntity w2 = onlineWorker("t1", "cnt-w2-" + uniqueGroup, uniqueGroup);
    workerRegistryMapper.saveLikeSdj(w1);
    workerRegistryMapper.saveLikeSdj(w2);

    long count = workerRegistryMapper.countByTenantAndWorkerGroupAndStatus(
        "t1", uniqueGroup, WorkerRegistryStatus.ONLINE.code());

    assertThat(count).isEqualTo(2);
  }

  @Test
  @DisplayName("按状态查询排空节点返回全部排空中条目,且每条状态一致")
  void shouldFindDrainingWorkers() {
    Instant pastDeadline = BatchDateTimeSupport.utcNow().minusSeconds(10);
    WorkerRegistryEntity draining = onlineWorker(
            "t1", "worker-draining-search-" + BatchDateTimeSupport.utcEpochMillis(), "DEFAULT")
        .withDrain(
            WorkerRegistryStatus.DRAINING.code(),
            BatchDateTimeSupport.utcNow().minusSeconds(60),
            pastDeadline,
            BatchDateTimeSupport.utcNow());
    workerRegistryMapper.saveLikeSdj(draining);

    List<WorkerRegistryEntity> drainingWorkers =
        workerRegistryMapper.selectByStatus(WorkerRegistryStatus.DRAINING.code());

    assertThat(drainingWorkers).isNotEmpty();
    assertThat(drainingWorkers)
        .allMatch(w -> WorkerRegistryStatus.DRAINING.code().equals(w.status()));
  }

  @Test
  @DisplayName("节点端口写入后可完整读回,写入侧与读取映射两侧一致")
  void shouldPersistWorkerPortAndReadItBack() {
    // V221：port 列在 insert 与 resultMap（constructor 映射）两侧都要接对，否则端口只落库读不回。
    String workerCode = "worker-it-port-" + BatchDateTimeSupport.utcEpochMillis();
    workerRegistryMapper.saveLikeSdj(workerWithPort("t1", workerCode, 18083));

    WorkerRegistryEntity found = workerRegistryMapper.selectByTenantAndWorkerCode("t1", workerCode);

    assertThat(found).isNotNull();
    assertThat(found.port()).isEqualTo(18083);
  }

  @Test
  @DisplayName("后续心跳未携带端口时保留库中已登记的端口,不被覆盖为空")
  void shouldKeepStoredPort_whenHeartbeatWithoutPort() {
    String workerCode = "worker-it-port-keep-" + BatchDateTimeSupport.utcEpochMillis();
    WorkerRegistryEntity stored = workerWithPort("t1", workerCode, 18083);
    workerRegistryMapper.saveLikeSdj(stored);

    // 老 worker / 非 web 上下文的心跳不带 port：coalesce 必须保留已落库的值，不能抹成 NULL。
    WorkerRegistryEntity heartbeatWithoutPort = stored.withFingerprint(
        stored.hostName(),
        stored.hostIp(),
        stored.processId(),
        null,
        stored.buildId(),
        stored.sdkVersion());
    int updated = workerRegistryMapper.updateRegistrationIfCurrent(
        heartbeatWithoutPort, WorkerRegistryStatus.ONLINE.code());

    assertThat(updated).isEqualTo(1);
    assertThat(
            workerRegistryMapper.selectByTenantAndWorkerCode("t1", workerCode).port())
        .isEqualTo(18083);
  }

  @Test
  @DisplayName("注册能力摘要写入 JSONB 后可从注册表读回")
  void shouldPersistTaskCapabilitiesAndReadThemBack() {
    String workerCode = "worker-it-capabilities-" + BatchDateTimeSupport.utcEpochMillis();
    String capabilities = "[{\"taskType\":\"file_sha256\",\"resourceKinds\":[\"DISK\"],"
        + "\"idempotent\":true,\"cancellable\":false,\"recommendedTimeoutMillis\":300000}]";
    WorkerRegistryEntity worker = new WorkerRegistryEntity(
        null,
        "t1",
        workerCode,
        "DEFAULT",
        new JsonbString("{}"),
        null,
        WorkerRegistryStatus.ONLINE.code(),
        BatchDateTimeSupport.utcNow(),
        0,
        10,
        null,
        null,
        "host-it",
        "1.2.3.4",
        "pid-it",
        18083,
        "build-it",
        "sdk-it",
        workerCode,
        new JsonbString(capabilities));

    workerRegistryMapper.saveLikeSdj(worker);

    WorkerRegistryEntity found = workerRegistryMapper.selectByTenantAndWorkerCode("t1", workerCode);
    assertThat(found).isNotNull();
    assertThat(found.taskCapabilities()).isNotNull();
    assertThat(found.taskCapabilities().getValue()).contains("file_sha256").contains("300000");
  }

  @Test
  @DisplayName("UNKNOWN 插件目录经 PostgreSQL 持久化后阻止对应 Worker 组重放准入")
  void shouldRejectReplayForUnknownImportProcessAndSqlTemplateCatalogues() {
    String tenantId = "replay-capability-" + BatchDateTimeSupport.utcEpochMillis();
    assertUnknownCapabilityBlocksReplay(
        tenantId, "IMPORT", WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED);
    assertUnknownCapabilityBlocksReplay(tenantId, "PROCESS", "sqlTransformCompute");
    assertUnknownCapabilityBlocksReplay(
        tenantId, "EXPORT", WorkerPluginIds.EXPORT_DATA_SQL_TEMPLATE);
  }

  @Test
  @DisplayName("同组任一在线 Worker 为 UNKNOWN 时，即使先读到安全声明也阻断整组重放")
  void shouldRejectReplayWhenAnyOnlineWorkerHasUnknownCapability() {
    String suffix = Long.toString(BatchDateTimeSupport.utcEpochMillis());
    String tenantId = "replay-capability-mixed-" + suffix;
    String workerGroup = "EXPORT";
    String pluginId = WorkerPluginIds.EXPORT_DATA_JDBC_MAPPED;
    String safeWorkerCode = "a-safe-replay-" + suffix;
    String unknownWorkerCode = "z-unknown-replay-" + suffix;
    workerRegistryMapper.saveLikeSdj(onlineWorker(tenantId, safeWorkerCode, workerGroup));
    workerRegistryMapper.saveLikeSdj(onlineWorker(tenantId, unknownWorkerCode, workerGroup));

    ReplayCapabilityDeclaration declared = new ReplayCapabilityDeclaration(
        pluginId,
        "1",
        List.of("BUSINESS_DATABASE_READ"),
        IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC,
        "tenant+business-key",
        "READ_ONLY_QUERY_V1",
        ReplayCapabilityDeclaration.CURRENT_CONTRACT_VERSION);
    String declaredCatalogue = JsonUtils.toJson(List.of(declared));
    String unknownCatalogue = JsonUtils.toJson(List.of(ReplayCapabilityDeclaration.unknown(pluginId)));
    assertThat(workerRegistryMapper.updateReplayCapabilities(tenantId, safeWorkerCode, declaredCatalogue))
        .isEqualTo(1);
    assertThat(workerRegistryMapper.updateReplayCapabilities(tenantId, unknownWorkerCode, unknownCatalogue))
        .isEqualTo(1);

    List<String> persistedCatalogues = workerRegistryMapper.selectReplayCapabilitiesByTenantAndWorkerGroup(
        tenantId, workerGroup, List.of(WorkerRegistryStatus.ONLINE.code()));
    assertThat(persistedCatalogues).hasSize(2);
    WorkflowReplayCapabilityService.Assessment assessment =
        replayCapabilityService.assess(tenantId, workerGroup, pluginId);

    assertThat(assessment.status())
        .isEqualTo(WorkflowReplayCapabilityService.Status.REPLAY_SAFETY_UNPROVEN);
    assertThat(assessment.workerCount()).isEqualTo(2);
    assertThat(assessment.declaration()).isEqualTo(ReplayCapabilityDeclaration.unknown(pluginId));
  }

  private void assertUnknownCapabilityBlocksReplay(
      String tenantId, String workerGroup, String pluginId) {
    String workerCode = workerGroup.toLowerCase() + "-replay-" + BatchDateTimeSupport.utcEpochMillis();
    workerRegistryMapper.saveLikeSdj(onlineWorker(tenantId, workerCode, workerGroup));
    String capabilityCatalogue =
        JsonUtils.toJson(List.of(ReplayCapabilityDeclaration.unknown(pluginId)));

    assertThat(workerRegistryMapper.updateReplayCapabilities(tenantId, workerCode, capabilityCatalogue))
        .isEqualTo(1);
    List<String> persistedCatalogues = workerRegistryMapper.selectReplayCapabilitiesByTenantAndWorkerGroup(
        tenantId, workerGroup, List.of(WorkerRegistryStatus.ONLINE.code()));
    assertThat(persistedCatalogues).hasSize(1);
    assertThat(persistedCatalogues.getFirst()).contains(pluginId, "UNKNOWN");

    WorkflowReplayCapabilityService.Assessment assessment =
        replayCapabilityService.assess(tenantId, workerGroup, pluginId);
    assertThat(assessment.status())
        .isEqualTo(WorkflowReplayCapabilityService.Status.REPLAY_SAFETY_UNPROVEN);
    assertThat(assessment.declaration()).isEqualTo(ReplayCapabilityDeclaration.unknown(pluginId));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static WorkerRegistryEntity workerWithPort(String tenantId, String workerCode, int port) {
    return new WorkerRegistryEntity(
        null,
        tenantId,
        workerCode,
        "DEFAULT",
        new JsonbString("{}"),
        null,
        WorkerRegistryStatus.ONLINE.code(),
        BatchDateTimeSupport.utcNow(),
        0,
        10,
        null,
        null,
        "host-it",
        "1.2.3.4",
        "pid-it",
        port,
        "build-it",
        "sdk-it",
        workerCode);
  }

  private static WorkerRegistryEntity onlineWorker(
      String tenantId, String workerCode, String workerGroup) {
    return new WorkerRegistryEntity(
        null,
        tenantId,
        workerCode,
        workerGroup,
        new JsonbString("{}"),
        null,
        WorkerRegistryStatus.ONLINE.code(),
        BatchDateTimeSupport.utcNow(),
        0,
        10,
        null,
        null);
  }
}
