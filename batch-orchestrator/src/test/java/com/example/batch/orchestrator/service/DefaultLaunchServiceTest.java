package com.example.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import com.example.batch.orchestrator.application.service.task.PartitionDispatchService;
import com.example.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import com.example.batch.orchestrator.application.service.workflow.WorkflowDagService;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.BatchDayInstanceMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
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
  private OrchestratorJobMappers jobMappers;
  private OrchestratorWorkflowMappers workflowMappers;
  private JobInstanceMapper jobInstanceMapper;
  private TriggerRequestMapper triggerRequestMapper;
  private WorkflowRunMapper workflowRunMapper;
  private WorkflowNodeRunMapper workflowNodeRunMapper;
  private WorkflowDagService workflowDagService;
  private OrchestratorConfigCacheService configCacheService;
  private BatchDayInstanceMapper batchDayInstanceMapper;
  private JobExecutionLogMapper jobExecutionLogMapper;
  private LaunchBatchDayService launchBatchDayService;
  private LaunchParamResolver launchParamResolver;

  @SuppressWarnings("unchecked")
  private ObjectProvider<DefaultLaunchService> selfProvider;

  @SuppressWarnings("unchecked")
  private ObjectProvider<LaunchBatchDayService> batchDaySelfProvider;

  private DefaultLaunchService service;

  @BeforeEach
  void setUp() {
    launchValidationService = mock(LaunchValidationService.class);
    partitionDispatchService = mock(PartitionDispatchService.class);
    triggerRequestMapper = mock(TriggerRequestMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    workflowRunMapper = mock(WorkflowRunMapper.class);
    workflowNodeRunMapper = mock(WorkflowNodeRunMapper.class);
    jobMappers =
        new OrchestratorJobMappers(
            jobInstanceMapper,
            mock(JobPartitionMapper.class),
            mock(JobTaskMapper.class),
            mock(JobStepInstanceMapper.class),
            triggerRequestMapper);
    workflowMappers =
        new OrchestratorWorkflowMappers(
            mock(WorkflowNodeMapper.class), workflowRunMapper, workflowNodeRunMapper);
    workflowDagService = mock(WorkflowDagService.class);
    configCacheService = mock(OrchestratorConfigCacheService.class);
    batchDayInstanceMapper = mock(BatchDayInstanceMapper.class);
    jobExecutionLogMapper = mock(JobExecutionLogMapper.class);
    selfProvider = mock(ObjectProvider.class);
    batchDaySelfProvider = mock(ObjectProvider.class);
    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    launchBatchDayService =
        new LaunchBatchDayService(
            configCacheService,
            batchDayInstanceMapper,
            jobExecutionLogMapper,
            jobMappers,
            timezoneProvider,
            batchDaySelfProvider);
    when(batchDaySelfProvider.getObject()).thenReturn(launchBatchDayService);
    launchParamResolver = new LaunchParamResolver(timezoneProvider);
    service =
        new DefaultLaunchService(
            launchValidationService,
            partitionDispatchService,
            jobMappers,
            workflowMappers,
            workflowDagService,
            launchBatchDayService,
            launchParamResolver,
            selfProvider);
    when(selfProvider.getObject()).thenReturn(service);
  }

  @Test
  void shouldUpsertBatchDayInstanceOnFirstLaunch() {
    LaunchRequest request =
        new LaunchRequest(
            "t1",
            "IMPORT_JOB",
            LocalDate.of(2026, 3, 27),
            TriggerType.API,
            "req-001",
            "trace-001",
            Map.of());
    TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
    triggerRequest.setId(100L);
    triggerRequest.setDedupKey("dedup-001");

    JobDefinitionEntity jobDefinition = jobDefinition("BIZ_CAL");
    WorkflowDefinitionEntity workflowDefinition =
        new WorkflowDefinitionEntity(200L, "t1", "WF", "wf", "FLOW", 1, true);
    LaunchValidationService.LaunchLoadResult loaded =
        new LaunchValidationService.LaunchLoadResult(
            triggerRequest, jobDefinition, workflowDefinition, null);

    BusinessCalendarEntity calendar =
        new BusinessCalendarEntity(
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
            true);

    when(launchValidationService.load(request)).thenReturn(loaded);
    when(workflowDagService.resolveInitialNodes(eq(200L), anyString())).thenReturn(List.of());
    when(configCacheService.findEnabledBusinessCalendar("t1", "BIZ_CAL")).thenReturn(calendar);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "BIZ_CAL", request.bizDate()))
        .thenReturn(null);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    LaunchResponse response = service.launch(request);

    assertThat(response.instanceNo()).isNotBlank();
    assertThat(response.traceId()).isEqualTo("trace-001");
    ArgumentCaptor<JobInstanceEntity> jobCaptor = ArgumentCaptor.forClass(JobInstanceEntity.class);
    verify(jobInstanceMapper).insert(jobCaptor.capture());
    assertThat(jobCaptor.getValue().getDeadlineAt()).isEqualTo(expectedSlaDeadline());
    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).insert(captor.capture());
    BatchDayInstanceEntity saved = captor.getValue();
    assertThat(saved.tenantId()).isEqualTo("t1");
    assertThat(saved.calendarCode()).isEqualTo("BIZ_CAL");
    assertThat(saved.bizDate()).isEqualTo(request.bizDate());
    // day_status 在 first launch 时会根据 cutoff_time 是否已到达自动初始化为 OPEN 或 CUTOFF
    Instant now = Instant.now();
    Instant cutoffAt =
        request
            .bizDate()
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
    LaunchRequest request =
        new LaunchRequest(
            "t1",
            "IMPORT_JOB",
            LocalDate.of(2026, 3, 27),
            TriggerType.API,
            "req-002",
            "trace-002",
            Map.of());
    TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
    triggerRequest.setId(101L);
    triggerRequest.setDedupKey("dedup-002");

    JobDefinitionEntity jobDefinition = jobDefinition("BIZ_CAL");
    WorkflowDefinitionEntity workflowDefinition =
        new WorkflowDefinitionEntity(201L, "t1", "WF", "wf", "FLOW", 1, true);
    LaunchValidationService.LaunchLoadResult loaded =
        new LaunchValidationService.LaunchLoadResult(
            triggerRequest, jobDefinition, workflowDefinition, null);

    BusinessCalendarEntity calendar =
        new BusinessCalendarEntity(
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
            true);
    BatchDayInstanceEntity existing =
        new BatchDayInstanceEntity(
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
            "UTC",
            0L,
            Instant.parse("2026-03-28T00:00:00Z"),
            Instant.parse("2026-03-28T00:10:00Z"));

    when(launchValidationService.load(request)).thenReturn(loaded);
    when(workflowDagService.resolveInitialNodes(eq(201L), anyString())).thenReturn(List.of());
    when(configCacheService.findEnabledBusinessCalendar("t1", "BIZ_CAL")).thenReturn(calendar);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "BIZ_CAL", request.bizDate()))
        .thenReturn(existing);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    service.launch(request);

    ArgumentCaptor<JobInstanceEntity> jobCaptor = ArgumentCaptor.forClass(JobInstanceEntity.class);
    verify(jobInstanceMapper).insert(jobCaptor.capture());
    assertThat(jobCaptor.getValue().getDeadlineAt()).isEqualTo(expectedSlaDeadline());
    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).updateWithCas(captor.capture());
    BatchDayInstanceEntity saved = captor.getValue();
    assertThat(saved.dayStatus()).isEqualTo("CUTOFF");
    assertThat(saved.cutoffAt()).isEqualTo(existing.cutoffAt());
    assertThat(saved.slaDeadlineAt()).isEqualTo(expectedSlaDeadline());
  }

  @Test
  void shouldAcceptLateEventWithinTolerance() {
    LaunchRequest request =
        new LaunchRequest(
            "t1",
            "IMPORT_JOB",
            LocalDate.of(2026, 3, 27),
            TriggerType.EVENT,
            "req-003",
            "trace-003",
            Map.of());
    TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
    triggerRequest.setId(102L);
    triggerRequest.setDedupKey("dedup-003");
    triggerRequest.setTriggerType(TriggerType.EVENT.code());

    JobDefinitionEntity jobDefinition = jobDefinition("BIZ_CAL");
    WorkflowDefinitionEntity workflowDefinition =
        new WorkflowDefinitionEntity(202L, "t1", "WF", "wf", "FLOW", 1, true);
    LaunchValidationService.LaunchLoadResult loaded =
        new LaunchValidationService.LaunchLoadResult(
            triggerRequest, jobDefinition, workflowDefinition, null);

    BusinessCalendarEntity calendar =
        new BusinessCalendarEntity(
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
            true);
    Instant now = Instant.now();
    BatchDayInstanceEntity existing =
        new BatchDayInstanceEntity(
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
            "UTC",
            0L,
            now.minusSeconds(3_600),
            now.minusSeconds(600));

    when(launchValidationService.load(request)).thenReturn(loaded);
    when(workflowDagService.resolveInitialNodes(eq(202L), anyString())).thenReturn(List.of());
    when(configCacheService.findEnabledBusinessCalendar("t1", "BIZ_CAL")).thenReturn(calendar);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "BIZ_CAL", request.bizDate()))
        .thenReturn(existing);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    service.launch(request);

    verify(triggerRequestMapper, never())
        .updateTriggerType(anyString(), anyString(), anyString(), anyString());
    ArgumentCaptor<JobInstanceEntity> jobCaptor = ArgumentCaptor.forClass(JobInstanceEntity.class);
    verify(jobInstanceMapper).insert(jobCaptor.capture());
    assertThat(jobCaptor.getValue().getTriggerType()).isEqualTo(TriggerType.EVENT.code());

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).updateWithCas(captor.capture());
    BatchDayInstanceEntity saved = captor.getValue();
    assertThat(saved.dayStatus()).isEqualTo("IN_FLIGHT");
    assertThat(saved.lateCount()).isEqualTo(1);
    assertThat(saved.catchupCount()).isEqualTo(0);
  }

  @Test
  void shouldRouteLateEventOutsideToleranceToCatchUp() {
    LaunchRequest request =
        new LaunchRequest(
            "t1",
            "IMPORT_JOB",
            LocalDate.of(2026, 3, 27),
            TriggerType.EVENT,
            "req-004",
            "trace-004",
            Map.of());
    TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
    triggerRequest.setId(103L);
    triggerRequest.setDedupKey("dedup-004");
    triggerRequest.setTriggerType(TriggerType.EVENT.code());

    JobDefinitionEntity jobDefinition = jobDefinition("BIZ_CAL");
    WorkflowDefinitionEntity workflowDefinition =
        new WorkflowDefinitionEntity(203L, "t1", "WF", "wf", "FLOW", 1, true);
    LaunchValidationService.LaunchLoadResult loaded =
        new LaunchValidationService.LaunchLoadResult(
            triggerRequest, jobDefinition, workflowDefinition, null);

    BusinessCalendarEntity calendar =
        new BusinessCalendarEntity(
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
            true);
    BatchDayInstanceEntity existing =
        new BatchDayInstanceEntity(
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
            "UTC",
            0L,
            Instant.parse("2026-03-27T00:00:00Z"),
            Instant.parse("2026-03-27T06:00:00Z"));

    when(launchValidationService.load(request)).thenReturn(loaded);
    when(workflowDagService.resolveInitialNodes(eq(203L), anyString())).thenReturn(List.of());
    when(configCacheService.findEnabledBusinessCalendar("t1", "BIZ_CAL")).thenReturn(calendar);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "BIZ_CAL", request.bizDate()))
        .thenReturn(existing);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);
    when(triggerRequestMapper.updateTriggerType(
            "t1", "req-004", TriggerType.CATCH_UP.code(), TriggerType.EVENT.code()))
        .thenReturn(1);

    service.launch(request);

    verify(triggerRequestMapper)
        .updateTriggerType("t1", "req-004", TriggerType.CATCH_UP.code(), TriggerType.EVENT.code());
    ArgumentCaptor<JobInstanceEntity> jobCaptor = ArgumentCaptor.forClass(JobInstanceEntity.class);
    verify(jobInstanceMapper).insert(jobCaptor.capture());
    assertThat(jobCaptor.getValue().getTriggerType()).isEqualTo(TriggerType.CATCH_UP.code());

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).updateWithCas(captor.capture());
    BatchDayInstanceEntity saved = captor.getValue();
    assertThat(saved.dayStatus()).isEqualTo("IN_FLIGHT");
    assertThat(saved.catchupCount()).isEqualTo(1);
    assertThat(saved.lateCount()).isEqualTo(0);
  }

  private JobDefinitionEntity jobDefinition(String calendarCode) {
    return new JobDefinitionEntity(
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
        "desc",
        null,
        null);
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
