package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import com.example.batch.orchestrator.domain.value.JsonbString;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 集成测试：WorkerRegistryMapper 在真实数据库上的持久化和查询。 覆盖排空生命周期：ONLINE → DRAINING → DECOMMISSIONED。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkerRegistryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private WorkerRegistryMapper workerRegistryMapper;

  @Test
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
  void shouldTransitionToDrainingStatus() {
    WorkerRegistryEntity worker = onlineWorker("t1", "worker-it-drain", "DEFAULT");
    worker = workerRegistryMapper.saveLikeSdj(worker);

    Instant now = Instant.now();
    worker = worker.withDrain(WorkerRegistryStatus.DRAINING.code(), now, now.plusSeconds(300), now);
    workerRegistryMapper.saveLikeSdj(worker);

    WorkerRegistryEntity found =
        workerRegistryMapper.selectByTenantAndWorkerCode("t1", "worker-it-drain");
    assertThat(found.status()).isEqualTo(WorkerRegistryStatus.DRAINING.code());
    assertThat(found.drainStartedAt()).isNotNull();
    assertThat(found.drainDeadlineAt()).isNotNull();
  }

  @Test
  void shouldTransitionToDecommissionedStatus() {
    WorkerRegistryEntity worker = onlineWorker("t1", "worker-it-decom", "DEFAULT");
    worker = workerRegistryMapper.saveLikeSdj(worker);

    worker = worker.withDecommissioned(Instant.now());
    workerRegistryMapper.saveLikeSdj(worker);

    WorkerRegistryEntity found =
        workerRegistryMapper.selectByTenantAndWorkerCode("t1", "worker-it-decom");
    assertThat(found.status()).isEqualTo(WorkerRegistryStatus.DECOMMISSIONED.code());
    assertThat(found.drainStartedAt()).isNull();
    assertThat(found.drainDeadlineAt()).isNull();
  }

  @Test
  void shouldFindWorkersByStatusAndGroup() {
    String uniqueGroup = "GROUP-IT-" + System.currentTimeMillis();
    WorkerRegistryEntity w1 = onlineWorker("t1", "w-grp-1-" + uniqueGroup, uniqueGroup);
    WorkerRegistryEntity w2 = onlineWorker("t1", "w-grp-2-" + uniqueGroup, uniqueGroup);
    WorkerRegistryEntity w3 =
        onlineWorker("t1", "w-offline-" + uniqueGroup, uniqueGroup)
            .withStatus(WorkerRegistryStatus.OFFLINE.code(), Instant.now());
    workerRegistryMapper.saveLikeSdj(w1);
    workerRegistryMapper.saveLikeSdj(w2);
    workerRegistryMapper.saveLikeSdj(w3);

    List<WorkerRegistryEntity> online =
        workerRegistryMapper.selectByTenantAndWorkerGroupAndStatus(
            "t1", uniqueGroup, WorkerRegistryStatus.ONLINE.code());

    assertThat(online).hasSize(2);
    assertThat(online).allMatch(w -> WorkerRegistryStatus.ONLINE.code().equals(w.status()));
  }

  @Test
  void shouldCountActiveWorkersByGroup() {
    String uniqueGroup = "CNT-" + System.currentTimeMillis();
    WorkerRegistryEntity w1 = onlineWorker("t1", "cnt-w1-" + uniqueGroup, uniqueGroup);
    WorkerRegistryEntity w2 = onlineWorker("t1", "cnt-w2-" + uniqueGroup, uniqueGroup);
    workerRegistryMapper.saveLikeSdj(w1);
    workerRegistryMapper.saveLikeSdj(w2);

    long count =
        workerRegistryMapper.countByTenantAndWorkerGroupAndStatus(
            "t1", uniqueGroup, WorkerRegistryStatus.ONLINE.code());

    assertThat(count).isEqualTo(2);
  }

  @Test
  void shouldFindDrainingWorkers() {
    Instant pastDeadline = Instant.now().minusSeconds(10);
    WorkerRegistryEntity draining =
        onlineWorker("t1", "worker-draining-search-" + System.currentTimeMillis(), "DEFAULT")
            .withDrain(
                WorkerRegistryStatus.DRAINING.code(),
                Instant.now().minusSeconds(60),
                pastDeadline,
                Instant.now());
    workerRegistryMapper.saveLikeSdj(draining);

    List<WorkerRegistryEntity> drainingWorkers =
        workerRegistryMapper.selectByStatus(WorkerRegistryStatus.DRAINING.code());

    assertThat(drainingWorkers).isNotEmpty();
    assertThat(drainingWorkers)
        .allMatch(w -> WorkerRegistryStatus.DRAINING.code().equals(w.status()));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

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
        Instant.now(),
        0,
        10,
        null,
        null);
  }
}
