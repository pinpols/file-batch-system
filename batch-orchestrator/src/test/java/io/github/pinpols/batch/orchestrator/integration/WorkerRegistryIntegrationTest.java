package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 集成测试：WorkerRegistryMapper 在真实数据库上的持久化和查询。 覆盖排空生命周期：ONLINE → DRAINING → DECOMMISSIONED。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("工作节点注册表持久化集成:验证真实数据库上注册写入与按键查询 + 排空及退役状态流转 + 条件更新防止旧状态覆盖 + 端口落库回读与不带端口的心跳保留")
class WorkerRegistryIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private WorkerRegistryMapper workerRegistryMapper;

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
