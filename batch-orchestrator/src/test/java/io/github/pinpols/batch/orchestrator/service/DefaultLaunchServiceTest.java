package io.github.pinpols.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.constants.BatchStatusConstants;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.FailureClass;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateInstanceProgressParam;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Clock;
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
  private BatchDayGateService batchDayGateService;
  private LaunchBatchDayService launchBatchDayService;
  private LaunchParamResolver launchParamResolver;

  private ObjectProvider<DefaultLaunchService> selfProvider;

  private ObjectProvider<LaunchBatchDayService> batchDaySelfProvider;

  private DefaultLaunchService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    launchValidationService = mock(LaunchValidationService.class);
    partitionDispatchService = mock(PartitionDispatchService.class);
    triggerRequestMapper = mock(TriggerRequestMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    // V182:insert 现返回 dedup 账本受影响行数(1=成功,0=重复)。happy-path 默认 1;
    // DefaultLaunchService 据此判定幂等重复(inserted<=0 抛 DuplicateKey)。
    when(jobInstanceMapper.insert(any())).thenReturn(1);
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
    batchDayGateService = mock(BatchDayGateService.class);
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
            new BatchDayTimePolicyResolver(
                timezoneProvider,
                new io.github.pinpols.batch.orchestrator.service.CutoffScheduleResolver()),
            batchDaySelfProvider,
            new BatchDateTimeSupport(Clock.systemUTC(), timezoneProvider),
            mock(
                io.github.pinpols.batch.orchestrator.application.service.governance
                    .AlertEventService.class));
    when(batchDaySelfProvider.getObject()).thenReturn(launchBatchDayService);
    launchParamResolver =
        new LaunchParamResolver(
            timezoneProvider,
            new BatchDateTimeSupport(Clock.systemUTC(), timezoneProvider),
            mock(io.github.pinpols.batch.orchestrator.mapper.CustomTaskTypeRegistryMapper.class));
    service =
        new DefaultLaunchService(
            launchValidationService,
            partitionDispatchService,
            jobMappers,
            workflowMappers,
            workflowDagService,
            launchBatchDayService,
            batchDayGateService,
            launchParamResolver,
            jobExecutionLogMapper,
            selfProvider);
    when(selfProvider.getObject()).thenReturn(service);
    when(batchDayGateService.evaluateAndApply(any(), any(), any(), anyString()))
        .thenReturn(
            new BatchDayGateService.GateDecision(BatchDayGateService.GateDecisionType.ALLOW, null));
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
    assertThat(jobCaptor.getValue().getJobDefinitionVersion()).isEqualTo(jobDefinition.version());
    Map<?, ?> rerunPolicy =
        JsonUtils.fromJson(jobCaptor.getValue().getRerunPolicySnapshot(), Map.class);
    assertThat(rerunPolicy.get("resultIsolation")).isEqualTo("NEW_JOB_INSTANCE_PER_RUN_ATTEMPT");
    assertThat(rerunPolicy.get("configVersionPolicy"))
        .isEqualTo("SNAPSHOT_JOB_DEFINITION_VERSION_ON_CREATE");
    Map<?, ?> paramsSnapshot =
        JsonUtils.fromJson(jobCaptor.getValue().getParamsSnapshot(), Map.class);
    assertThat(paramsSnapshot.get("jobDefinitionVersion")).isEqualTo(jobDefinition.version());
    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).insert(captor.capture());
    BatchDayInstanceEntity saved = captor.getValue();
    assertThat(saved.tenantId()).isEqualTo("t1");
    assertThat(saved.calendarCode()).isEqualTo("BIZ_CAL");
    assertThat(saved.bizDate()).isEqualTo(request.bizDate());
    // day_status 在 first launch 时会根据 cutoff_time 是否已到达自动初始化为 OPEN 或 CUTOFF
    Instant now = BatchDateTimeSupport.utcNow();
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
    Instant now = BatchDateTimeSupport.utcNow();
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

  @Test
  void shouldMarkPreparedJobFailedWhenDispatchBusinessErrorOccursAfterT1() {
    LaunchRequest request =
        new LaunchRequest(
            "t1",
            "IMPORT_JOB",
            LocalDate.of(2026, 3, 27),
            TriggerType.API,
            "req-dispatch-reject",
            "trace-dispatch-reject",
            Map.of());
    TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
    triggerRequest.setId(104L);
    triggerRequest.setDedupKey("dedup-dispatch-reject");
    JobDefinitionEntity jobDefinition = jobDefinition("BIZ_CAL");
    WorkflowDefinitionEntity workflowDefinition =
        new WorkflowDefinitionEntity(204L, "t1", "WF", "wf", "FLOW", 1, true);
    LaunchValidationService.LaunchLoadResult loaded =
        new LaunchValidationService.LaunchLoadResult(
            triggerRequest, jobDefinition, workflowDefinition, null);
    BusinessCalendarEntity calendar =
        new BusinessCalendarEntity(
            5L,
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

    when(launchValidationService.load(request)).thenReturn(loaded);
    when(configCacheService.findEnabledBusinessCalendar("t1", "BIZ_CAL")).thenReturn(calendar);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "BIZ_CAL", request.bizDate()))
        .thenReturn(null);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);
    doAnswer(
            invocation -> {
              JobInstanceEntity entity = invocation.getArgument(0);
              entity.setId(501L);
              entity.setVersion(0L);
              return 1;
            })
        .when(jobInstanceMapper)
        .insert(any());
    when(jobInstanceMapper.updateProgress(any())).thenReturn(1);
    doThrow(
            BizException.of(
                ResultCode.BUSINESS_ERROR,
                "error.partition.dispatch_business_error",
                "TENANT_JOB_LIMIT",
                "tenant quota exceeded"))
        .when(partitionDispatchService)
        .dispatch(any());

    assertThatThrownBy(() -> service.launch(request))
        .isInstanceOf(BizException.class)
        .hasMessage("error.partition.dispatch_business_error");

    ArgumentCaptor<UpdateInstanceProgressParam> progressCaptor =
        ArgumentCaptor.forClass(UpdateInstanceProgressParam.class);
    verify(jobInstanceMapper).updateProgress(progressCaptor.capture());
    UpdateInstanceProgressParam progress = progressCaptor.getValue();
    assertThat(progress.getTenantId()).isEqualTo("t1");
    assertThat(progress.getId()).isEqualTo(501L);
    assertThat(progress.getInstanceStatus()).isEqualTo(JobInstanceStatus.FAILED.code());
    assertThat(progress.getExpectedVersion()).isZero();
    assertThat(progress.getFailureClass()).isEqualTo(FailureClass.BUSINESS_RULE.code());
    assertThat(progress.getResultSummary())
        .contains("DISPATCH_REJECTED")
        .contains("error.partition.dispatch_business_error")
        .contains("TENANT_JOB_LIMIT")
        .contains("tenant quota exceeded");
    verify(triggerRequestMapper)
        .updateAcceptance("t1", "req-dispatch-reject", BatchStatusConstants.REJECTED, 501L);

    ArgumentCaptor<JobExecutionLogEntity> logCaptor =
        ArgumentCaptor.forClass(JobExecutionLogEntity.class);
    verify(jobExecutionLogMapper, org.mockito.Mockito.atLeastOnce()).insert(logCaptor.capture());
    JobExecutionLogEntity log =
        logCaptor.getAllValues().stream()
            .filter(item -> "JOB_INSTANCE_DISPATCH_REJECTED".equals(item.getMessage()))
            .findFirst()
            .orElseThrow();
    assertThat(log.getTenantId()).isEqualTo("t1");
    assertThat(log.getJobInstanceId()).isEqualTo(501L);
    assertThat(log.getLogLevel()).isEqualTo("WARN");
    assertThat(log.getMessage()).isEqualTo("JOB_INSTANCE_DISPATCH_REJECTED");
    assertThat(log.getDetailRef()).isEqualTo("job_instance.dispatch_rejected");
    assertThat(log.getExtraJson()).contains("tenant quota exceeded");
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
