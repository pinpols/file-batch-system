package com.example.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.orchestrator.application.service.PartitionDispatchService;
import com.example.batch.orchestrator.application.service.WorkflowDagService;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceRecord;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarRecord;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionRecord;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import com.example.batch.orchestrator.repository.BatchDayInstanceRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class DefaultLaunchServiceTest {

    private LaunchValidationService launchValidationService;
    private PartitionDispatchService partitionDispatchService;
    private TriggerRequestMapper triggerRequestMapper;
    private JobInstanceMapper jobInstanceMapper;
    private WorkflowRunMapper workflowRunMapper;
    private WorkflowNodeRunMapper workflowNodeRunMapper;
    private WorkflowDagService workflowDagService;
    private OrchestratorConfigCacheService configCacheService;
    private BatchDayInstanceRepository batchDayInstanceRepository;
    private JobExecutionLogMapper jobExecutionLogMapper;
    @SuppressWarnings("unchecked")
    private ObjectProvider<DefaultLaunchService> selfProvider;
    private DefaultLaunchService service;

    @BeforeEach
    void setUp() {
        launchValidationService = mock(LaunchValidationService.class);
        partitionDispatchService = mock(PartitionDispatchService.class);
        triggerRequestMapper = mock(TriggerRequestMapper.class);
        jobInstanceMapper = mock(JobInstanceMapper.class);
        workflowRunMapper = mock(WorkflowRunMapper.class);
        workflowNodeRunMapper = mock(WorkflowNodeRunMapper.class);
        workflowDagService = mock(WorkflowDagService.class);
        configCacheService = mock(OrchestratorConfigCacheService.class);
        batchDayInstanceRepository = mock(BatchDayInstanceRepository.class);
        jobExecutionLogMapper = mock(JobExecutionLogMapper.class);
        selfProvider = mock(ObjectProvider.class);
        service = new DefaultLaunchService(
                launchValidationService,
                partitionDispatchService,
                triggerRequestMapper,
                jobInstanceMapper,
                workflowRunMapper,
                workflowNodeRunMapper,
                workflowDagService,
                configCacheService,
                batchDayInstanceRepository,
                jobExecutionLogMapper,
                selfProvider
        );
        when(selfProvider.getObject()).thenReturn(service);
    }

    @Test
    void shouldUpsertBatchDayInstanceOnFirstLaunch() {
        LaunchRequest request = new LaunchRequest(
                "t1",
                "IMPORT_JOB",
                LocalDate.of(2026, 3, 27),
                TriggerType.API,
                "req-001",
                "trace-001",
                Map.of()
        );
        TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
        triggerRequest.setId(100L);
        triggerRequest.setDedupKey("dedup-001");

        JobDefinitionRecord jobDefinition = jobDefinition("BIZ_CAL");
        WorkflowDefinitionRecord workflowDefinition = new WorkflowDefinitionRecord(200L, "t1", "WF", "wf", "FLOW", 1, true);
        LaunchValidationService.LaunchLoadResult loaded = new LaunchValidationService.LaunchLoadResult(
                triggerRequest, jobDefinition, workflowDefinition, null);

        BusinessCalendarRecord calendar = new BusinessCalendarRecord(
                1L,
                "t1",
                "BIZ_CAL",
                "biz",
                "Asia/Shanghai",
                "SKIP",
                "AUTO",
                30,
                LocalTime.of(6, 0),
                15,
                120,
                true
        );

        when(launchValidationService.load(request)).thenReturn(loaded);
        when(workflowDagService.resolveInitialNodes(eq(200L), anyString())).thenReturn(List.of());
        when(configCacheService.findEnabledBusinessCalendar("t1", "BIZ_CAL"))
                .thenReturn(calendar);
        when(batchDayInstanceRepository.findFirstByTenantIdAndCalendarCodeAndBizDate("t1", "BIZ_CAL", request.bizDate()))
                .thenReturn(null);
        when(batchDayInstanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LaunchResponse response = service.launch(request);

        assertThat(response.instanceNo()).isNotBlank();
        assertThat(response.traceId()).isEqualTo("trace-001");
        ArgumentCaptor<JobInstanceEntity> jobCaptor = ArgumentCaptor.forClass(JobInstanceEntity.class);
        verify(jobInstanceMapper).insert(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getDeadlineAt()).isEqualTo(expectedSlaDeadline());
        ArgumentCaptor<BatchDayInstanceRecord> captor = ArgumentCaptor.forClass(BatchDayInstanceRecord.class);
        verify(batchDayInstanceRepository).save(captor.capture());
        BatchDayInstanceRecord saved = captor.getValue();
        assertThat(saved.tenantId()).isEqualTo("t1");
        assertThat(saved.calendarCode()).isEqualTo("BIZ_CAL");
        assertThat(saved.bizDate()).isEqualTo(request.bizDate());
        // day_status 在 first launch 时会根据 cutoff_time 是否已到达自动初始化为 OPEN 或 CUTOFF
        Instant now = Instant.now();
        Instant cutoffAt = request.bizDate()
                .plusDays(1)
                .atTime(LocalTime.of(6, 0))
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
        String expectedDayStatus = !now.isBefore(cutoffAt) ? "CUTOFF" : "OPEN";
        assertThat(saved.dayStatus()).isEqualTo(expectedDayStatus);
        assertThat(saved.slaDeadlineAt()).isEqualTo(expectedSlaDeadline());
    }

    @Test
    void shouldPreserveExistingStatusWhenFillingMissingSlaDeadline() {
        LaunchRequest request = new LaunchRequest(
                "t1",
                "IMPORT_JOB",
                LocalDate.of(2026, 3, 27),
                TriggerType.API,
                "req-002",
                "trace-002",
                Map.of()
        );
        TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
        triggerRequest.setId(101L);
        triggerRequest.setDedupKey("dedup-002");

        JobDefinitionRecord jobDefinition = jobDefinition("BIZ_CAL");
        WorkflowDefinitionRecord workflowDefinition = new WorkflowDefinitionRecord(201L, "t1", "WF", "wf", "FLOW", 1, true);
        LaunchValidationService.LaunchLoadResult loaded = new LaunchValidationService.LaunchLoadResult(
                triggerRequest, jobDefinition, workflowDefinition, null);

        BusinessCalendarRecord calendar = new BusinessCalendarRecord(
                2L,
                "t1",
                "BIZ_CAL",
                "biz",
                "Asia/Shanghai",
                "SKIP",
                "AUTO",
                30,
                LocalTime.of(6, 0),
                15,
                120,
                true
        );
        BatchDayInstanceRecord existing = new BatchDayInstanceRecord(
                88L,
                "t1",
                "BIZ_CAL",
                request.bizDate(),
                "CUTOFF",
                Instant.parse("2026-03-28T00:00:00Z"),
                Instant.parse("2026-03-28T00:10:00Z"),
                null,
                null,
                0,
                0,
                Instant.parse("2026-03-28T00:00:00Z"),
                Instant.parse("2026-03-28T00:10:00Z")
        );

        when(launchValidationService.load(request)).thenReturn(loaded);
        when(workflowDagService.resolveInitialNodes(eq(201L), anyString())).thenReturn(List.of());
        when(configCacheService.findEnabledBusinessCalendar("t1", "BIZ_CAL"))
                .thenReturn(calendar);
        when(batchDayInstanceRepository.findFirstByTenantIdAndCalendarCodeAndBizDate("t1", "BIZ_CAL", request.bizDate()))
                .thenReturn(existing);
        when(batchDayInstanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.launch(request);

        ArgumentCaptor<JobInstanceEntity> jobCaptor = ArgumentCaptor.forClass(JobInstanceEntity.class);
        verify(jobInstanceMapper).insert(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getDeadlineAt()).isEqualTo(expectedSlaDeadline());
        ArgumentCaptor<BatchDayInstanceRecord> captor = ArgumentCaptor.forClass(BatchDayInstanceRecord.class);
        verify(batchDayInstanceRepository).save(captor.capture());
        BatchDayInstanceRecord saved = captor.getValue();
        assertThat(saved.dayStatus()).isEqualTo("CUTOFF");
        assertThat(saved.cutoffAt()).isEqualTo(existing.cutoffAt());
        assertThat(saved.slaDeadlineAt()).isEqualTo(expectedSlaDeadline());
    }

    @Test
    void shouldAcceptLateEventWithinTolerance() {
        LaunchRequest request = new LaunchRequest(
                "t1",
                "IMPORT_JOB",
                LocalDate.of(2026, 3, 27),
                TriggerType.EVENT,
                "req-003",
                "trace-003",
                Map.of()
        );
        TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
        triggerRequest.setId(102L);
        triggerRequest.setDedupKey("dedup-003");
        triggerRequest.setTriggerType(TriggerType.EVENT.code());

        JobDefinitionRecord jobDefinition = jobDefinition("BIZ_CAL");
        WorkflowDefinitionRecord workflowDefinition = new WorkflowDefinitionRecord(202L, "t1", "WF", "wf", "FLOW", 1, true);
        LaunchValidationService.LaunchLoadResult loaded = new LaunchValidationService.LaunchLoadResult(
                triggerRequest, jobDefinition, workflowDefinition, null);

        BusinessCalendarRecord calendar = new BusinessCalendarRecord(
                3L,
                "t1",
                "BIZ_CAL",
                "biz",
                "Asia/Shanghai",
                "SKIP",
                "AUTO",
                30,
                LocalTime.of(6, 0),
                30,
                120,
                true
        );
        Instant now = Instant.now();
        BatchDayInstanceRecord existing = new BatchDayInstanceRecord(
                89L,
                "t1",
                "BIZ_CAL",
                request.bizDate(),
                "CUTOFF",
                now.minusSeconds(3_600),
                now.minusSeconds(600),
                null,
                expectedSlaDeadline(),
                0,
                0,
                now.minusSeconds(3_600),
                now.minusSeconds(600)
        );

        when(launchValidationService.load(request)).thenReturn(loaded);
        when(workflowDagService.resolveInitialNodes(eq(202L), anyString())).thenReturn(List.of());
        when(configCacheService.findEnabledBusinessCalendar("t1", "BIZ_CAL"))
                .thenReturn(calendar);
        when(batchDayInstanceRepository.findFirstByTenantIdAndCalendarCodeAndBizDate("t1", "BIZ_CAL", request.bizDate()))
                .thenReturn(existing);
        when(batchDayInstanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.launch(request);

        verify(triggerRequestMapper, never()).updateTriggerType(anyString(), anyString(), anyString());
        ArgumentCaptor<JobInstanceEntity> jobCaptor = ArgumentCaptor.forClass(JobInstanceEntity.class);
        verify(jobInstanceMapper).insert(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getTriggerType()).isEqualTo(TriggerType.EVENT.code());

        ArgumentCaptor<BatchDayInstanceRecord> captor = ArgumentCaptor.forClass(BatchDayInstanceRecord.class);
        verify(batchDayInstanceRepository).save(captor.capture());
        BatchDayInstanceRecord saved = captor.getValue();
        assertThat(saved.dayStatus()).isEqualTo("IN_FLIGHT");
        assertThat(saved.lateCount()).isEqualTo(1);
        assertThat(saved.catchupCount()).isEqualTo(0);
    }

    @Test
    void shouldRouteLateEventOutsideToleranceToCatchUp() {
        LaunchRequest request = new LaunchRequest(
                "t1",
                "IMPORT_JOB",
                LocalDate.of(2026, 3, 27),
                TriggerType.EVENT,
                "req-004",
                "trace-004",
                Map.of()
        );
        TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
        triggerRequest.setId(103L);
        triggerRequest.setDedupKey("dedup-004");
        triggerRequest.setTriggerType(TriggerType.EVENT.code());

        JobDefinitionRecord jobDefinition = jobDefinition("BIZ_CAL");
        WorkflowDefinitionRecord workflowDefinition = new WorkflowDefinitionRecord(203L, "t1", "WF", "wf", "FLOW", 1, true);
        LaunchValidationService.LaunchLoadResult loaded = new LaunchValidationService.LaunchLoadResult(
                triggerRequest, jobDefinition, workflowDefinition, null);

        BusinessCalendarRecord calendar = new BusinessCalendarRecord(
                4L,
                "t1",
                "BIZ_CAL",
                "biz",
                "Asia/Shanghai",
                "SKIP",
                "AUTO",
                30,
                LocalTime.of(6, 0),
                30,
                120,
                true
        );
        BatchDayInstanceRecord existing = new BatchDayInstanceRecord(
                90L,
                "t1",
                "BIZ_CAL",
                request.bizDate(),
                "FAILED",
                Instant.parse("2026-03-27T00:00:00Z"),
                Instant.parse("2026-03-27T06:00:00Z"),
                null,
                expectedSlaDeadline(),
                0,
                0,
                Instant.parse("2026-03-27T00:00:00Z"),
                Instant.parse("2026-03-27T06:00:00Z")
        );

        when(launchValidationService.load(request)).thenReturn(loaded);
        when(workflowDagService.resolveInitialNodes(eq(203L), anyString())).thenReturn(List.of());
        when(configCacheService.findEnabledBusinessCalendar("t1", "BIZ_CAL"))
                .thenReturn(calendar);
        when(batchDayInstanceRepository.findFirstByTenantIdAndCalendarCodeAndBizDate("t1", "BIZ_CAL", request.bizDate()))
                .thenReturn(existing);
        when(batchDayInstanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.launch(request);

        verify(triggerRequestMapper).updateTriggerType("t1", "req-004", TriggerType.CATCH_UP.code());
        ArgumentCaptor<JobInstanceEntity> jobCaptor = ArgumentCaptor.forClass(JobInstanceEntity.class);
        verify(jobInstanceMapper).insert(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getTriggerType()).isEqualTo(TriggerType.CATCH_UP.code());

        ArgumentCaptor<BatchDayInstanceRecord> captor = ArgumentCaptor.forClass(BatchDayInstanceRecord.class);
        verify(batchDayInstanceRepository).save(captor.capture());
        BatchDayInstanceRecord saved = captor.getValue();
        assertThat(saved.dayStatus()).isEqualTo("IN_FLIGHT");
        assertThat(saved.catchupCount()).isEqualTo(1);
        assertThat(saved.lateCount()).isEqualTo(0);
    }

    private JobDefinitionRecord jobDefinition(String calendarCode) {
        return new JobDefinitionRecord(
                11L,
                "t1",
                "IMPORT_JOB",
                "Import Job",
                "TYPE",
                "BIZ",
                "CRON",
                "0 0/5 * * * ?",
                "Asia/Shanghai",
                "WG",
                "QUEUE",
                calendarCode,
                "WINDOW",
                "DEFAULT",
                true,
                "HASH",
                "NONE",
                3,
                600,
                "handler",
                Map.of(),
                5,
                Map.of(),
                1,
                true,
                "desc"
        );
    }

    private Instant expectedSlaDeadline() {
        return LocalDate.of(2026, 3, 27)
                .plusDays(1)
                .atTime(LocalTime.of(6, 0))
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .plusSeconds(120L * 60L);
    }
}
