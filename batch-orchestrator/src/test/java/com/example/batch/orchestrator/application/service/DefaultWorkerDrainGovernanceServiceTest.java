package com.example.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.config.WorkerDrainProperties;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.repository.WorkerRegistryJdbcRepository;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultWorkerDrainGovernanceServiceTest {

    private WorkerRegistryRepository workerRegistryRepository;
    private WorkerRegistryJdbcRepository workerRegistryJdbcRepository;
    private JobTaskMapper jobTaskMapper;
    private RetryGovernanceService retryGovernanceService;
    private WorkerDrainProperties drainProperties;
    private DefaultWorkerDrainGovernanceService service;

    @BeforeEach
    void setUp() {
        workerRegistryRepository = mock(WorkerRegistryRepository.class);
        workerRegistryJdbcRepository = mock(WorkerRegistryJdbcRepository.class);
        jobTaskMapper = mock(JobTaskMapper.class);
        retryGovernanceService = mock(RetryGovernanceService.class);
        drainProperties = new WorkerDrainProperties();
        drainProperties.setDefaultTimeoutSeconds(300);
        service = new DefaultWorkerDrainGovernanceService(
                workerRegistryRepository, workerRegistryJdbcRepository, jobTaskMapper, retryGovernanceService, drainProperties);
    }

    // ── startDrain ────────────────────────────────────────────────────────────

    @Test
    void shouldThrowWhenTenantIdIsBlankOnStartDrain() {
        assertThatThrownBy(() -> service.startDrain("", "w1", null))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldThrowWhenWorkerCodeIsBlankOnStartDrain() {
        assertThatThrownBy(() -> service.startDrain("t1", "", null))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldThrowWhenWorkerNotRegisteredOnStartDrain() {
        when(workerRegistryRepository.findFirstByTenantIdAndWorkerCode("t1", "w1")).thenReturn(null);

        assertThatThrownBy(() -> service.startDrain("t1", "w1", null))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldThrowWhenWorkerAlreadyDecommissionedOnStartDrain() {
        WorkerRegistryRecord registry = onlineWorker("t1", "w1")
                .withStatus(WorkerRegistryStatus.DECOMMISSIONED.code(), Instant.now());
        when(workerRegistryRepository.findFirstByTenantIdAndWorkerCode("t1", "w1")).thenReturn(registry);

        assertThatThrownBy(() -> service.startDrain("t1", "w1", null))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldSetDrainingStatusWithDefaultTimeout() {
        WorkerRegistryRecord registry = onlineWorker("t1", "w1");
        when(workerRegistryRepository.findFirstByTenantIdAndWorkerCode("t1", "w1")).thenReturn(registry);
        when(workerRegistryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkerRegistryRecord result = service.startDrain("t1", "w1", null);

        assertThat(result.status()).isEqualTo(WorkerRegistryStatus.DRAINING.code());
        assertThat(result.drainStartedAt()).isNotNull();
        assertThat(result.drainDeadlineAt()).isNotNull();
        assertThat(result.drainDeadlineAt()).isAfter(result.drainStartedAt());
    }

    @Test
    void shouldUseCustomTimeoutWhenProvided() {
        WorkerRegistryRecord registry = onlineWorker("t1", "w1");
        when(workerRegistryRepository.findFirstByTenantIdAndWorkerCode("t1", "w1")).thenReturn(registry);
        when(workerRegistryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.startDrain("t1", "w1", 120);

        verify(workerRegistryRepository).save(any());
    }

    // ── forceOffline ─────────────────────────────────────────────────────────

    @Test
    void shouldThrowWhenTenantIdIsBlankOnForceOffline() {
        assertThatThrownBy(() -> service.forceOffline("", "w1"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldThrowWhenWorkerNotRegisteredOnForceOffline() {
        when(workerRegistryRepository.findFirstByTenantIdAndWorkerCode("t1", "w1")).thenReturn(null);

        assertThatThrownBy(() -> service.forceOffline("t1", "w1"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldMarkDecommissionedAndTakeoverTasksOnForceOffline() {
        WorkerRegistryRecord registry = onlineWorker("t1", "w1");
        WorkerRegistryRecord decommissioned = registry.withDecommissioned(Instant.now());
        when(workerRegistryRepository.findFirstByTenantIdAndWorkerCode("t1", "w1"))
                .thenReturn(registry)
                .thenReturn(registry)
                .thenReturn(decommissioned);
        when(workerRegistryJdbcRepository.markDecommissioned(eq("t1"), eq("w1"), any())).thenReturn(1);

        JobTaskEntity task = new JobTaskEntity();
        task.setId(100L);
        task.setTenantId("t1");
        when(jobTaskMapper.selectActiveByAssignedWorker("t1", "w1",
                TaskStatus.RUNNING.code(), TaskStatus.READY.code(), TaskStatus.CREATED.code())).thenReturn(List.of(task));

        WorkerRegistryRecord result = service.forceOffline("t1", "w1");

        assertThat(result.status()).isEqualTo(WorkerRegistryStatus.DECOMMISSIONED.code());
        verify(retryGovernanceService).reclaimTask(eq("t1"), eq(100L), anyString());
    }

    @Test
    void shouldCompleteForceOfflineEvenWhenNoActiveTasks() {
        WorkerRegistryRecord registry = onlineWorker("t1", "w1");
        WorkerRegistryRecord decommissioned = registry.withDecommissioned(Instant.now());
        when(workerRegistryRepository.findFirstByTenantIdAndWorkerCode("t1", "w1"))
                .thenReturn(registry)
                .thenReturn(registry)
                .thenReturn(decommissioned);
        when(workerRegistryJdbcRepository.markDecommissioned(eq("t1"), eq("w1"), any())).thenReturn(1);
        when(jobTaskMapper.selectActiveByAssignedWorker("t1", "w1",
                TaskStatus.RUNNING.code(), TaskStatus.READY.code(), TaskStatus.CREATED.code())).thenReturn(List.of());

        WorkerRegistryRecord result = service.forceOffline("t1", "w1");

        assertThat(result.status()).isEqualTo(WorkerRegistryStatus.DECOMMISSIONED.code());
        verify(retryGovernanceService, never()).reclaimTask(anyString(), anyLong(), anyString());
    }

    // ── listClaimedTasks ─────────────────────────────────────────────────────

    @Test
    void shouldThrowWhenWorkerCodeIsBlankOnListClaimedTasks() {
        assertThatThrownBy(() -> service.listClaimedTasks("t1", ""))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldReturnActiveTasksForWorker() {
        JobTaskEntity task = new JobTaskEntity();
        task.setId(200L);
        when(jobTaskMapper.selectActiveByAssignedWorker("t1", "w1",
                TaskStatus.RUNNING.code(), TaskStatus.READY.code(), TaskStatus.CREATED.code())).thenReturn(List.of(task));

        List<JobTaskEntity> tasks = service.listClaimedTasks("t1", "w1");

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getId()).isEqualTo(200L);
    }

    // ── takeoverAfterDrainTimeout ─────────────────────────────────────────────

    @Test
    void shouldDoNothingWhenTenantIdIsBlankOnTakeover() {
        service.takeoverAfterDrainTimeout("", "w1");
        verify(workerRegistryRepository, never()).findFirstByTenantIdAndWorkerCode(anyString(), anyString());
    }

    @Test
    void shouldDoNothingWhenWorkerCodeIsBlankOnTakeover() {
        service.takeoverAfterDrainTimeout("t1", "");
        verify(workerRegistryRepository, never()).findFirstByTenantIdAndWorkerCode(anyString(), anyString());
    }

    @Test
    void shouldDoNothingWhenRegistryNotFoundOnTakeover() {
        when(workerRegistryRepository.findFirstByTenantIdAndWorkerCode("t1", "w1")).thenReturn(null);

        service.takeoverAfterDrainTimeout("t1", "w1");

        verify(jobTaskMapper, never()).selectActiveByAssignedWorker(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldDoNothingWhenWorkerNotDrainingOnTakeover() {
        WorkerRegistryRecord registry = onlineWorker("t1", "w1");
        when(workerRegistryRepository.findFirstByTenantIdAndWorkerCode("t1", "w1")).thenReturn(registry);

        service.takeoverAfterDrainTimeout("t1", "w1");

        verify(jobTaskMapper, never()).selectActiveByAssignedWorker(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldTakeoverAndDecommissionWhenDrainingWorkerFound() {
        WorkerRegistryRecord registry = onlineWorker("t1", "w1")
                .withDrain(WorkerRegistryStatus.DRAINING.code(),
                        Instant.now().minusSeconds(600), Instant.now().minusSeconds(100),
                        Instant.now().minusSeconds(600));

        when(workerRegistryRepository.findFirstByTenantIdAndWorkerCode("t1", "w1"))
                .thenReturn(registry)
                .thenReturn(registry);
        when(workerRegistryJdbcRepository.markDecommissioned(eq("t1"), eq("w1"), any())).thenReturn(1);
        when(jobTaskMapper.selectActiveByAssignedWorker("t1", "w1",
                TaskStatus.RUNNING.code(), TaskStatus.READY.code(), TaskStatus.CREATED.code())).thenReturn(List.of());

        service.takeoverAfterDrainTimeout("t1", "w1");

        verify(workerRegistryJdbcRepository).markDecommissioned(eq("t1"), eq("w1"), any());
    }

    @Test
    void shouldContinueTakeoverWhenOneTaskRetryFails() {
        WorkerRegistryRecord registry = onlineWorker("t1", "w1")
                .withStatus(WorkerRegistryStatus.DRAINING.code(), Instant.now());
        WorkerRegistryRecord decommissioned = registry.withDecommissioned(Instant.now());
        when(workerRegistryRepository.findFirstByTenantIdAndWorkerCode("t1", "w1"))
                .thenReturn(registry)
                .thenReturn(registry)
                .thenReturn(decommissioned);
        when(workerRegistryJdbcRepository.markDecommissioned(eq("t1"), eq("w1"), any())).thenReturn(1);

        JobTaskEntity task1 = new JobTaskEntity();
        task1.setId(301L);
        task1.setTenantId("t1");
        JobTaskEntity task2 = new JobTaskEntity();
        task2.setId(302L);
        task2.setTenantId("t1");
        when(jobTaskMapper.selectActiveByAssignedWorker("t1", "w1",
                TaskStatus.RUNNING.code(), TaskStatus.READY.code(), TaskStatus.CREATED.code())).thenReturn(List.of(task1, task2));
        doThrow(new RuntimeException("retry failed")).when(retryGovernanceService)
                .reclaimTask(eq("t1"), eq(301L), anyString());

        // 即使某个重试失败也不应抛出异常
        service.takeoverAfterDrainTimeout("t1", "w1");

        verify(retryGovernanceService).reclaimTask(eq("t1"), eq(302L), anyString());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static WorkerRegistryRecord onlineWorker(String tenantId, String workerCode) {
        return new WorkerRegistryRecord(
                null, tenantId, workerCode, null, null, null,
                WorkerRegistryStatus.ONLINE.code(), Instant.now(), null, null, null);
    }
}
