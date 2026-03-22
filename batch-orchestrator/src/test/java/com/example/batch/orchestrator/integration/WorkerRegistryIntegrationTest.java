package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration test: WorkerRegistryRepository persistence and query against real DB.
 * Covers the drain lifecycle: ONLINE → DRAINING → DECOMMISSIONED.
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkerRegistryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WorkerRegistryRepository workerRegistryRepository;

    @Test
    void shouldSaveAndFindOnlineWorker() {
        WorkerRegistryRecord worker = onlineWorker("t1", "worker-it-001", "DEFAULT");
        workerRegistryRepository.save(worker);

        WorkerRegistryRecord found = workerRegistryRepository
                .findFirstByTenantIdAndWorkerCode("t1", "worker-it-001");

        assertThat(found).isNotNull();
        assertThat(found.getStatus()).isEqualTo(WorkerRegistryStatus.ONLINE.code());
        assertThat(found.getWorkerGroup()).isEqualTo("DEFAULT");
    }

    @Test
    void shouldTransitionToDrainingStatus() {
        WorkerRegistryRecord worker = onlineWorker("t1", "worker-it-drain", "DEFAULT");
        worker = workerRegistryRepository.save(worker);

        worker.setStatus(WorkerRegistryStatus.DRAINING.code());
        worker.setDrainStartedAt(Instant.now());
        worker.setDrainDeadlineAt(Instant.now().plusSeconds(300));
        workerRegistryRepository.save(worker);

        WorkerRegistryRecord found = workerRegistryRepository
                .findFirstByTenantIdAndWorkerCode("t1", "worker-it-drain");
        assertThat(found.getStatus()).isEqualTo(WorkerRegistryStatus.DRAINING.code());
        assertThat(found.getDrainStartedAt()).isNotNull();
        assertThat(found.getDrainDeadlineAt()).isNotNull();
    }

    @Test
    void shouldTransitionToDecommissionedStatus() {
        WorkerRegistryRecord worker = onlineWorker("t1", "worker-it-decom", "DEFAULT");
        worker = workerRegistryRepository.save(worker);

        worker.setStatus(WorkerRegistryStatus.DECOMMISSIONED.code());
        worker.setDrainStartedAt(null);
        worker.setDrainDeadlineAt(null);
        workerRegistryRepository.save(worker);

        WorkerRegistryRecord found = workerRegistryRepository
                .findFirstByTenantIdAndWorkerCode("t1", "worker-it-decom");
        assertThat(found.getStatus()).isEqualTo(WorkerRegistryStatus.DECOMMISSIONED.code());
        assertThat(found.getDrainStartedAt()).isNull();
        assertThat(found.getDrainDeadlineAt()).isNull();
    }

    @Test
    void shouldFindWorkersByStatusAndGroup() {
        String uniqueGroup = "GROUP-IT-" + System.currentTimeMillis();
        WorkerRegistryRecord w1 = onlineWorker("t1", "w-grp-1-" + uniqueGroup, uniqueGroup);
        WorkerRegistryRecord w2 = onlineWorker("t1", "w-grp-2-" + uniqueGroup, uniqueGroup);
        WorkerRegistryRecord w3 = onlineWorker("t1", "w-offline-" + uniqueGroup, uniqueGroup);
        w3.setStatus(WorkerRegistryStatus.OFFLINE.code());
        workerRegistryRepository.save(w1);
        workerRegistryRepository.save(w2);
        workerRegistryRepository.save(w3);

        List<WorkerRegistryRecord> online = workerRegistryRepository
                .findByTenantIdAndWorkerGroupAndStatus("t1", uniqueGroup, WorkerRegistryStatus.ONLINE.code());

        assertThat(online).hasSize(2);
        assertThat(online).allMatch(w -> WorkerRegistryStatus.ONLINE.code().equals(w.getStatus()));
    }

    @Test
    void shouldCountActiveWorkersByGroup() {
        String uniqueGroup = "CNT-" + System.currentTimeMillis();
        WorkerRegistryRecord w1 = onlineWorker("t1", "cnt-w1-" + uniqueGroup, uniqueGroup);
        WorkerRegistryRecord w2 = onlineWorker("t1", "cnt-w2-" + uniqueGroup, uniqueGroup);
        workerRegistryRepository.save(w1);
        workerRegistryRepository.save(w2);

        long count = workerRegistryRepository.countByTenantIdAndWorkerGroupAndStatus(
                "t1", uniqueGroup, WorkerRegistryStatus.ONLINE.code());

        assertThat(count).isEqualTo(2);
    }

    @Test
    void shouldFindDrainingWorkers() {
        WorkerRegistryRecord draining = onlineWorker("t1", "worker-draining-search-" + System.currentTimeMillis(), "DEFAULT");
        draining.setStatus(WorkerRegistryStatus.DRAINING.code());
        draining.setDrainStartedAt(Instant.now().minusSeconds(60));
        draining.setDrainDeadlineAt(Instant.now().minusSeconds(10)); // already past deadline
        workerRegistryRepository.save(draining);

        List<WorkerRegistryRecord> drainingWorkers = workerRegistryRepository
                .findByStatus(WorkerRegistryStatus.DRAINING.code());

        assertThat(drainingWorkers).isNotEmpty();
        assertThat(drainingWorkers).allMatch(w -> WorkerRegistryStatus.DRAINING.code().equals(w.getStatus()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static WorkerRegistryRecord onlineWorker(String tenantId, String workerCode, String workerGroup) {
        WorkerRegistryRecord r = new WorkerRegistryRecord();
        r.setTenantId(tenantId);
        r.setWorkerCode(workerCode);
        r.setWorkerGroup(workerGroup);
        r.setStatus(WorkerRegistryStatus.ONLINE.code());
        r.setHeartbeatAt(Instant.now());
        r.setCurrentLoad(0);
        return r;
    }
}
