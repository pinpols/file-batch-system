package io.github.pinpols.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.dto.DependencyResultReference;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.dto.ReplayExecutionGuard;
import io.github.pinpols.batch.common.enums.FailureClass;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.orchestrator.application.engine.JobTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.failure.PersistenceConflictDetectionPort;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RelatedFileReferenceValidator;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.PartitionDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateInstanceProgressParam;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.CustomTaskTypeRegistryMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.orchestrator.observability.LaunchPhaseMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@DisplayName("默认启动服务:批量日与作业实例落库,迟到事件路由,派发异常失败标记及重复投递幂等")
class DefaultLaunchServiceTest {

  private LaunchValidationService launchValidationService;
  private PartitionDispatchService partitionDispatchService;
  private OrchestratorJobMappers jobMappers;
  private OrchestratorWorkflowMappers workflowMappers;
  private JobInstanceMapper jobInstanceMapper;
  private TriggerRequestMapper triggerRequestMapper;
  private WorkflowRunMapper workflowRunMapper;
  private WorkflowNodeRunMapper workflowNodeRunMapper;
  private OrchestratorConfigCacheService configCacheService;
  private BatchDayInstanceMapper batchDayInstanceMapper;
  private JobExecutionLogMapper jobExecutionLogMapper;
  private BatchDayGateService batchDayGateService;
  private LaunchBatchDayService launchBatchDayService;
  private LaunchParamResolver launchParamResolver;
  private JobDependencyWaitingLaunchService jobDependencyWaitingLaunchService;
  private RelatedFileReferenceValidator relatedFileReferenceValidator;
  private FileTemplateSnapshotService fileTemplateSnapshots;

  private DefaultLaunchService service;
  private final JobTerminalOutboxService terminalOutbox = mock(JobTerminalOutboxService.class);

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
    jobMappers = new OrchestratorJobMappers(
        jobInstanceMapper,
        mock(JobPartitionMapper.class),
        mock(JobTaskMapper.class),
        mock(JobStepInstanceMapper.class),
        triggerRequestMapper);
    workflowMappers = new OrchestratorWorkflowMappers(
        mock(WorkflowNodeMapper.class), workflowRunMapper, workflowNodeRunMapper);
    configCacheService = mock(OrchestratorConfigCacheService.class);
    batchDayInstanceMapper = mock(BatchDayInstanceMapper.class);
    jobExecutionLogMapper = mock(JobExecutionLogMapper.class);
    batchDayGateService = mock(BatchDayGateService.class);
    PlatformTransactionManager batchDayTransactionManager = mock(PlatformTransactionManager.class);
    when(batchDayTransactionManager.getTransaction(any()))
        .thenReturn(mock(TransactionStatus.class));
    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    launchBatchDayService = new LaunchBatchDayService(
        configCacheService,
        batchDayInstanceMapper,
        jobExecutionLogMapper,
        jobMappers,
        timezoneProvider,
        new BatchDayTimePolicyResolver(timezoneProvider, new CutoffScheduleResolver()),
        new BatchDateTimeSupport(Clock.systemUTC(), timezoneProvider),
        mock(AlertEventService.class),
        batchDayTransactionManager);
    launchParamResolver = new LaunchParamResolver(
        timezoneProvider,
        new BatchDateTimeSupport(Clock.systemUTC(), timezoneProvider),
        mock(CustomTaskTypeRegistryMapper.class));
    jobDependencyWaitingLaunchService = mock(JobDependencyWaitingLaunchService.class);
    relatedFileReferenceValidator = mock(RelatedFileReferenceValidator.class);
    lenient()
        .when(jobDependencyWaitingLaunchService.captureOrDefer(any()))
        .thenReturn(Optional.of(List.of()));
    DispatchSecretSnapshotResolver secretSnapshotResolver =
        mock(DispatchSecretSnapshotResolver.class);
    when(secretSnapshotResolver.pin(anyString(), anyString(), anyMap()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    DispatchChannelSnapshotService dispatchChannelSnapshotService =
        mock(DispatchChannelSnapshotService.class);
    when(dispatchChannelSnapshotService.pin(anyString(), anyString(), anyMap()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    when(dispatchChannelSnapshotService.pinWorkflowReplay(
            anyString(), anyString(), anyMap(), anyMap(), anyMap()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    fileTemplateSnapshots = mock(FileTemplateSnapshotService.class);
    when(fileTemplateSnapshots.pin(any(), any(), anyMap()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    service = new DefaultLaunchService(
        launchValidationService,
        partitionDispatchService,
        jobMappers,
        workflowMappers,
        launchBatchDayService,
        batchDayGateService,
        jobDependencyWaitingLaunchService,
        launchParamResolver,
        relatedFileReferenceValidator,
        secretSnapshotResolver,
        dispatchChannelSnapshotService,
        fileTemplateSnapshots,
        jobExecutionLogMapper,
        batchDayTransactionManager,
        new LaunchPhaseMetrics(new SimpleMeterRegistry()),
        mock(PersistenceConflictDetectionPort.class),
        terminalOutbox);
    when(batchDayGateService.evaluateAndApply(any(), any(), any(), anyString()))
        .thenReturn(
            new BatchDayGateService.GateDecision(BatchDayGateService.GateDecisionType.ALLOW, null));
  }

  @Test
  @DisplayName("首次启动时新增作业实例与批量日记录,回写受理为已启动并保存时限与重跑策略快照")
  void shouldUpsertBatchDayInstanceOnFirstLaunch() {
    when(fileTemplateSnapshots.pin(any(), any(), anyMap())).thenAnswer(invocation -> {
      Map<String, Object> pinned = new java.util.LinkedHashMap<>(invocation.getArgument(2));
      pinned.put("frozenFileTemplateProtected", "captured-template");
      return pinned;
    });
    LaunchRequest request = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 27),
        TriggerType.API,
        "req-001",
        "trace-001",
        Map.of());
    TriggerRequestEntity triggerRequest = acceptedTriggerRequest(request);
    triggerRequest.setId(100L);
    triggerRequest.setDedupKey("dedup-001");

    JobDefinitionEntity jobDefinition = jobDefinition("BIZ_CAL");
    LaunchValidationService.LaunchLoadResult loaded = new LaunchValidationService.LaunchLoadResult(
        triggerRequest, jobDefinition, null, "BIZ_CAL", null);

    BusinessCalendarEntity calendar = new BusinessCalendarEntity(
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
    when(configCacheService.findEnabledBusinessCalendar("t1", "BIZ_CAL")).thenReturn(calendar);
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate("t1", "BIZ_CAL", request.bizDate()))
        .thenReturn(null);
    when(batchDayInstanceMapper.insert(any())).thenReturn(1);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);
    when(jobDependencyWaitingLaunchService.captureOrDefer(any()))
        .thenReturn(Optional.of(List.of(new DependencyResultReference(
            "UPSTREAM_JOB",
            "SAME_DAY",
            request.bizDate(),
            "job:UPSTREAM_JOB:" + request.bizDate(),
            4,
            401L,
            301L,
            Instant.parse("2026-03-27T04:00:00Z")))));

    LaunchResponse response = service.launch(request);

    assertThat(response.instanceNo()).isNotBlank();
    assertThat(response.traceId()).isEqualTo("trace-001");
    ArgumentCaptor<JobInstanceEntity> jobCaptor = ArgumentCaptor.forClass(JobInstanceEntity.class);
    verify(jobInstanceMapper).insert(jobCaptor.capture());
    verify(triggerRequestMapper)
        .updateAcceptance(
            "t1",
            "req-001",
            TriggerRequestStatus.LAUNCHED.code(),
            jobCaptor.getValue().getId());
    assertThat(jobCaptor.getValue().getDeadlineAt()).isEqualTo(expectedSlaDeadline());
    assertThat(jobCaptor.getValue().getJobDefinitionVersion()).isEqualTo(jobDefinition.version());
    verify(workflowRunMapper, never()).insert(any());
    Map<String, Object> rerunPolicy =
        JsonUtils.fromJson(jobCaptor.getValue().getRerunPolicySnapshot(), new TypeReference<>() {});
    assertThat(rerunPolicy).containsEntry("resultIsolation", "NEW_JOB_INSTANCE_PER_RUN_ATTEMPT");
    assertThat(rerunPolicy)
        .containsEntry("configVersionPolicy", "SNAPSHOT_JOB_DEFINITION_VERSION_ON_CREATE");
    Map<String, Object> paramsSnapshot =
        JsonUtils.fromJson(jobCaptor.getValue().getParamsSnapshot(), new TypeReference<>() {});
    assertThat(paramsSnapshot.get("effectiveParams"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("frozenFileTemplateProtected", "captured-template");
    ArgumentCaptor<PartitionDispatchService.DispatchContext> dispatchCaptor =
        ArgumentCaptor.forClass(PartitionDispatchService.DispatchContext.class);
    verify(partitionDispatchService).dispatch(dispatchCaptor.capture());
    assertThat(dispatchCaptor.getValue().effectiveParams())
        .containsEntry("frozenFileTemplateProtected", "captured-template");
    assertThat(paramsSnapshot.get("dependencyResultSnapshot"))
        .asString()
        .contains("UPSTREAM_JOB", "job:UPSTREAM_JOB:" + request.bizDate(), "401");
    assertThat(paramsSnapshot).containsEntry("jobDefinitionVersion", jobDefinition.version());
    Object executionPolicyValue = paramsSnapshot.get("executionPolicy");
    assertThat(executionPolicyValue).isInstanceOf(Map.class);
    Map<?, ?> executionPolicy = (Map<?, ?>) executionPolicyValue;
    assertThat(executionPolicy.get("retryPolicy")).isEqualTo(jobDefinition.retryPolicy());
    assertThat(executionPolicy.get("retryMaxCount")).isEqualTo(jobDefinition.retryMaxCount());
    assertThat(executionPolicy.get("timeoutSeconds")).isEqualTo(jobDefinition.timeoutSeconds());
    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).insert(captor.capture());
    BatchDayInstanceEntity saved = captor.getValue();
    assertThat(saved.tenantId()).isEqualTo("t1");
    assertThat(saved.calendarCode()).isEqualTo("BIZ_CAL");
    assertThat(saved.bizDate()).isEqualTo(request.bizDate());
    // day_status 在 first launch 时会根据 cutoff_time 是否已到达自动初始化为 OPEN 或 CUTOFF
    Instant now = BatchDateTimeSupport.utcNow();
    Instant cutoffAt = request
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
  @DisplayName("外租户或不存在的 rerun 父实例在写入 JobInstance 前被拒绝")
  void shouldRejectMissingTenantParentInstanceBeforeInsert() {
    LaunchRequest request = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 27),
        TriggerType.RERUN,
        "req-cross-tenant-parent",
        "trace-cross-tenant-parent",
        Map.of("parentInstanceId", 999L));
    TriggerRequestEntity triggerRequest = acceptedTriggerRequest(request);
    triggerRequest.setId(999L);
    triggerRequest.setRequestId(request.requestId());
    triggerRequest.setDedupKey("dedup-cross-tenant-parent");
    when(launchValidationService.load(request))
        .thenReturn(new LaunchValidationService.LaunchLoadResult(
            triggerRequest, jobDefinition(null), null, null));

    assertThatThrownBy(() -> service.launch(request))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.job.instance_not_found");

    verify(jobInstanceMapper).selectById("t1", 999L);
    verify(triggerRequestMapper)
        .updateAcceptance("t1", request.requestId(), TriggerRequestStatus.REJECTED.code(), null);
    verify(jobInstanceMapper, never()).insert(any());
    verify(partitionDispatchService, never()).dispatch(any());
  }

  @Test
  @DisplayName("等待释放后实际创建子 Job 时在实例事务内关联回 Workflow 节点")
  void shouldAttachReleasedChildJobToWorkflowNodeDuringInstanceCreation() {
    LaunchRequest request = new LaunchRequest(
        "t1",
        "CHILD_JOB",
        LocalDate.of(2026, Month.MARCH, 27),
        TriggerType.EVENT,
        "req-child-release",
        "trace-child-release",
        Map.of("_parentWorkflowRunId", 900L, "_parentNodeCode", "N1"));
    TriggerRequestEntity triggerRequest = acceptedTriggerRequest(request);
    triggerRequest.setId(901L);
    triggerRequest.setTenantId("t1");
    triggerRequest.setRequestId(request.requestId());
    triggerRequest.setDedupKey("dedup-child-release");
    triggerRequest.setParentWorkflowRunId(900L);
    triggerRequest.setParentNodeCode("N1");
    LaunchValidationService.LaunchLoadResult loaded = new LaunchValidationService.LaunchLoadResult(
        triggerRequest, jobDefinition(null), null, null);
    when(launchValidationService.load(request)).thenReturn(loaded);
    WorkflowRunEntity parentRun = new WorkflowRunEntity();
    parentRun.setId(900L);
    parentRun.setTenantId("t1");
    parentRun.setWorkflowDefinitionId(90L);
    parentRun.setWorkflowVersionSnapshot(1);
    Map<String, Object> parentExecutionSnapshot = Map.of(
        "schemaVersion",
        2,
        "workflowDefinitionId",
        90,
        "workflowVersion",
        1,
        "nodes",
        List.of(Map.of(
            "nodeCode",
            "START",
            "nodeType",
            "START",
            "tenantId",
            "t1",
            "workflowDefinitionId",
            90)),
        "edges",
        List.of(),
        "jobDefinitions",
        Map.of());
    String serializedParentExecutionSnapshot = JsonUtils.toJson(parentExecutionSnapshot);
    parentRun.setExecutionSnapshot(serializedParentExecutionSnapshot);
    when(workflowRunMapper.selectById("t1", 900L)).thenReturn(parentRun);
    Mockito.doAnswer(invocation -> {
          JobInstanceEntity instance = invocation.getArgument(0);
          instance.setId(902L);
          return 1;
        })
        .when(jobInstanceMapper)
        .insert(any());
    WorkflowNodeRunEntity nodeRun = new WorkflowNodeRunEntity();
    nodeRun.setId(903L);
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(900L, "N1"))
        .thenReturn(nodeRun);
    when(workflowNodeRunMapper.attachJobInstance("t1", 903L, 902L)).thenReturn(1);

    LaunchResponse response = service.launch(request);

    assertThat(response.instanceNo()).isNotBlank();
    verify(workflowNodeRunMapper).attachJobInstance("t1", 903L, 902L);
  }

  @Test
  @DisplayName("依赖未就绪时保留持久等待且不创建作业实例")
  void shouldDeferLaunchWhenDependencyGateIsNotReady() {
    LaunchRequest request = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 27),
        TriggerType.EVENT,
        "req-dependency-wait",
        "trace-dependency-wait",
        Map.of("arrivalGroup", "arrivals-1"));
    TriggerRequestEntity triggerRequest = acceptedTriggerRequest(request);
    triggerRequest.setId(101L);
    triggerRequest.setDedupKey("dedup-dependency-wait");
    LaunchValidationService.LaunchLoadResult loaded = new LaunchValidationService.LaunchLoadResult(
        triggerRequest, jobDefinition(null), null, null);
    when(launchValidationService.load(request)).thenReturn(loaded);
    when(jobDependencyWaitingLaunchService.captureOrDefer(request)).thenReturn(Optional.empty());

    LaunchResponse response = service.launch(request);

    assertThat(response.instanceNo()).isNull();
    assertThat(response.traceId()).isEqualTo("trace-dependency-wait");
    verify(jobDependencyWaitingLaunchService).captureOrDefer(request);
    verify(jobInstanceMapper, never()).insert(any());
    verify(partitionDispatchService, never()).dispatch(any());
  }

  @Test
  @DisplayName("Bundle 启动缺少绑定时先拒绝请求，不创建 CREATED 实例")
  void shouldRejectBundleLaunchBeforeCreatingInstanceWhenBindingsMissing() {
    LaunchRequest request = new LaunchRequest(
        "t1",
        "BUNDLE_JOB",
        LocalDate.of(2026, Month.MARCH, 27),
        TriggerType.API,
        "req-bundle-invalid",
        "trace-bundle-invalid",
        Map.of());
    TriggerRequestEntity triggerRequest = acceptedTriggerRequest(request);
    JobDefinitionEntity definition = JobDefinitionEntity.builder()
        .id(501L)
        .tenantId("t1")
        .jobCode("BUNDLE_JOB")
        .jobType(JobType.BUNDLE_DISPATCH.code())
        .defaultParams(Map.of())
        .build();
    when(launchValidationService.load(request))
        .thenReturn(
            new LaunchValidationService.LaunchLoadResult(triggerRequest, definition, null, null));

    assertThatThrownBy(() -> service.launch(request))
        .isInstanceOf(BizException.class)
        .hasMessage("error.partition.dispatch_business_error");

    verify(triggerRequestMapper)
        .updateAcceptance("t1", "req-bundle-invalid", TriggerRequestStatus.REJECTED.code(), null);
    verify(jobInstanceMapper, never()).insert(any());
    verify(partitionDispatchService, never()).dispatch(any());
  }

  @Test
  @DisplayName("跨租户文件引用在创建运行实例前拒绝并终结触发请求")
  void shouldRejectLaunchWhenRelatedFileDoesNotBelongToTenant() {
    LaunchRequest request = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 27),
        TriggerType.API,
        "req-foreign-file",
        "trace-foreign-file",
        Map.of("relatedFileId", 987L));
    TriggerRequestEntity triggerRequest = acceptedTriggerRequest(request);
    triggerRequest.setId(987L);
    triggerRequest.setDedupKey("dedup-foreign-file");
    JobDefinitionEntity definition = jobDefinition("BIZ_CAL");
    when(launchValidationService.load(request))
        .thenReturn(new LaunchValidationService.LaunchLoadResult(
            triggerRequest, definition, null, "BIZ_CAL", null));
    Mockito.doThrow(BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found"))
        .when(relatedFileReferenceValidator)
        .validate("t1", 987L);

    assertThatThrownBy(() -> service.launch(request))
        .isInstanceOf(BizException.class)
        .hasMessage("error.file.record_not_found");

    verify(triggerRequestMapper)
        .updateAcceptance("t1", "req-foreign-file", TriggerRequestStatus.REJECTED.code(), null);
    verify(jobInstanceMapper, never()).insert(any());
    verify(partitionDispatchService, never()).dispatch(any());
  }

  @Test
  @DisplayName("批量日已存在且缺少服务时限时保留原状态与截止时刻,仅补写服务时限")
  void shouldPreserveExistingStatusWhenFillingMissingSlaDeadline() {
    LaunchRequest request = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 27),
        TriggerType.API,
        "req-002",
        "trace-002",
        Map.of());
    TriggerRequestEntity triggerRequest = acceptedTriggerRequest(request);
    triggerRequest.setId(101L);
    triggerRequest.setDedupKey("dedup-002");

    JobDefinitionEntity jobDefinition = jobDefinition("BIZ_CAL");
    LaunchValidationService.LaunchLoadResult loaded = new LaunchValidationService.LaunchLoadResult(
        triggerRequest, jobDefinition, null, "BIZ_CAL", null);

    BusinessCalendarEntity calendar = new BusinessCalendarEntity(
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
    BatchDayInstanceEntity existing = new BatchDayInstanceEntity(
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
  @DisplayName("事件迟到但在容差内时保持原触发类型,累计迟到次数且不增加补跑次数")
  void shouldAcceptLateEventWithinTolerance() {
    LaunchRequest request = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 27),
        TriggerType.EVENT,
        "req-003",
        "trace-003",
        Map.of());
    TriggerRequestEntity triggerRequest = acceptedTriggerRequest(request);
    triggerRequest.setId(102L);
    triggerRequest.setDedupKey("dedup-003");
    triggerRequest.setTriggerType(TriggerType.EVENT.code());

    JobDefinitionEntity jobDefinition = jobDefinition("BIZ_CAL");
    LaunchValidationService.LaunchLoadResult loaded = new LaunchValidationService.LaunchLoadResult(
        triggerRequest, jobDefinition, null, "BIZ_CAL", null);

    BusinessCalendarEntity calendar = new BusinessCalendarEntity(
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
    BatchDayInstanceEntity existing = new BatchDayInstanceEntity(
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
    assertThat(saved.catchupCount()).isZero();
  }

  @Test
  @DisplayName("事件超出容差时改写为补跑类型,累计补跑次数且不记迟到")
  void shouldRouteLateEventOutsideToleranceToCatchUp() {
    LaunchRequest request = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 27),
        TriggerType.EVENT,
        "req-004",
        "trace-004",
        Map.of());
    TriggerRequestEntity triggerRequest = acceptedTriggerRequest(request);
    triggerRequest.setId(103L);
    triggerRequest.setDedupKey("dedup-004");
    triggerRequest.setTriggerType(TriggerType.EVENT.code());

    JobDefinitionEntity jobDefinition = jobDefinition("BIZ_CAL");
    LaunchValidationService.LaunchLoadResult loaded = new LaunchValidationService.LaunchLoadResult(
        triggerRequest, jobDefinition, null, "BIZ_CAL", null);

    BusinessCalendarEntity calendar = new BusinessCalendarEntity(
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
    BatchDayInstanceEntity existing = new BatchDayInstanceEntity(
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
    assertThat(saved.lateCount()).isZero();
  }

  @Test
  @DisplayName("实例已落库后派发出现业务异常时标记为失败,回写拒绝受理并记录告警日志与失败分类")
  void shouldMarkPreparedJobFailedWhenDispatchBusinessErrorOccursAfterT1() {
    LaunchRequest request = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 27),
        TriggerType.API,
        "req-dispatch-reject",
        "trace-dispatch-reject",
        Map.of());
    TriggerRequestEntity triggerRequest = acceptedTriggerRequest(request);
    triggerRequest.setId(104L);
    triggerRequest.setDedupKey("dedup-dispatch-reject");
    JobDefinitionEntity jobDefinition = jobDefinition("BIZ_CAL");
    LaunchValidationService.LaunchLoadResult loaded = new LaunchValidationService.LaunchLoadResult(
        triggerRequest, jobDefinition, null, "BIZ_CAL", null);
    BusinessCalendarEntity calendar = new BusinessCalendarEntity(
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
    doAnswer(invocation -> {
          JobInstanceEntity entity = invocation.getArgument(0);
          entity.setId(501L);
          entity.setVersion(0L);
          return 1;
        })
        .when(jobInstanceMapper)
        .insert(any());
    when(jobInstanceMapper.updateProgress(any())).thenReturn(1);
    doThrow(BizException.of(
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
        .updateAcceptance("t1", "req-dispatch-reject", TriggerRequestStatus.REJECTED.code(), 501L);

    ArgumentCaptor<JobExecutionLogEntity> logCaptor =
        ArgumentCaptor.forClass(JobExecutionLogEntity.class);
    verify(jobExecutionLogMapper, Mockito.atLeastOnce()).insert(logCaptor.capture());
    JobExecutionLogEntity log = logCaptor.getAllValues().stream()
        .filter(item -> "JOB_INSTANCE_DISPATCH_REJECTED".equals(item.getMessage()))
        .findFirst()
        .orElseThrow();
    assertThat(log.getTenantId()).isEqualTo("t1");
    assertThat(log.getJobInstanceId()).isEqualTo(501L);
    assertThat(log.getLogLevel()).isEqualTo("WARN");
    assertThat(log.getMessage()).isEqualTo("JOB_INSTANCE_DISPATCH_REJECTED");
    assertThat(log.getDetailRef()).isEqualTo("job_instance.dispatch_rejected");
    assertThat(log.getExtraJson()).contains("tenant quota exceeded");
    verify(terminalOutbox).writeTerminalEvent("t1", 501L);
  }

  @Test
  @DisplayName("重复投递命中已存在实例时沿用原实例编号与链路标识,不标记重复且不再次派发")
  void shouldNotMarkOriginalRequestDuplicateWhenKafkaRedeliversBetweenT1AndT2() {
    LaunchRequest request = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 27),
        TriggerType.API,
        "req-redelivery",
        "trace-redelivery",
        Map.of());
    TriggerRequestEntity triggerRequest = acceptedTriggerRequest(request);
    triggerRequest.setId(900L);
    triggerRequest.setDedupKey("dedup-redelivery");
    JobInstanceEntity existing = new JobInstanceEntity();
    existing.setId(901L);
    existing.setInstanceNo("instance-redelivery");
    existing.setTraceId("trace-original");
    existing.setTriggerRequestId(900L);
    LaunchValidationService.LaunchLoadResult loaded = new LaunchValidationService.LaunchLoadResult(
        triggerRequest, jobDefinition("BIZ_CAL"), null, existing);
    when(launchValidationService.load(request)).thenReturn(loaded);

    LaunchResponse response = service.launch(request);

    assertThat(response.instanceNo()).isEqualTo("instance-redelivery");
    assertThat(response.traceId()).isEqualTo("trace-original");
    verify(triggerRequestMapper, never())
        .updateAcceptance("t1", "req-redelivery", TriggerRequestStatus.DUPLICATE.code(), 901L);
    verify(partitionDispatchService, never()).dispatch(any());
  }

  @Test
  @DisplayName("模板冻结业务拒绝发生在实例创建前，退出事务后终结请求且不派发")
  void shouldRejectRequestWithoutInstance_whenTemplateCaptureFails() {
    LaunchRequest request = LaunchRequest.builder()
        .tenantId("t1")
        .jobCode("IMPORT_JOB")
        .bizDate(LocalDate.of(2026, 3, 27))
        .requestId("template-rejected")
        .triggerType(TriggerType.API)
        .params(Map.of())
        .build();
    TriggerRequestEntity triggerRequest = acceptedTriggerRequest(request);
    triggerRequest.setDedupKey("template-rejected");
    JobDefinitionEntity definition = jobDefinition("BIZ_CAL");
    LaunchValidationService.LaunchLoadResult loaded = new LaunchValidationService.LaunchLoadResult(
        triggerRequest, definition, null, "BIZ_CAL", null);
    when(launchValidationService.load(request)).thenReturn(loaded);
    BizException failure =
        BizException.of(ResultCode.STATE_CONFLICT, "error.worker.frozen_file_template_invalid");
    when(fileTemplateSnapshots.pin(any(), any(), anyMap())).thenThrow(failure);

    assertThatThrownBy(() -> service.launch(request)).isSameAs(failure);

    verify(triggerRequestMapper).updateAcceptance("t1", "template-rejected", "REJECTED", null);
    verify(jobInstanceMapper, never()).insert(any());
    verify(partitionDispatchService, never()).dispatch(any());
  }

  @Test
  @DisplayName("整 Workflow Replay 的 Job 准备只使用审批绑定的模板快照")
  void shouldPinApprovedWorkflowReplayTemplateBindingDuringJobPrepare() {
    LocalDate bizDate = LocalDate.of(2026, Month.MARCH, 27);
    Map<String, Object> replayParams = Map.of(
        "templateCode",
        "TPL_A",
        "operationType",
        "WORKFLOW_RUN",
        "_parentWorkflowRunId",
        900L,
        "_parentNodeCode",
        "IMPORT_NODE");
    LaunchRequest request = LaunchRequest.builder()
        .tenantId("t1")
        .jobCode("IMPORT_JOB")
        .bizDate(bizDate)
        .triggerType(TriggerType.API)
        .requestId("workflow-replay-template")
        .traceId("workflow-replay-template-trace")
        .params(replayParams)
        .dryRun(true)
        .build();
    TriggerRequestEntity triggerRequest = acceptedTriggerRequest(request);
    triggerRequest.setParentWorkflowRunId(900L);
    triggerRequest.setParentNodeCode("IMPORT_NODE");

    ReplayCapabilityDeclaration declaration = new ReplayCapabilityDeclaration(
        "jdbc_mapped",
        "1",
        List.of("BUSINESS_DATABASE"),
        IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC,
        "tenant+business-key",
        "IMPORT_SQL_V1",
        ReplayCapabilityDeclaration.CURRENT_CONTRACT_VERSION);
    ReplayExecutionGuard executionGuard = new ReplayExecutionGuard(701L, declaration);
    Map<String, String> protectedTemplates = Map.of("TPL_A", "sealed-template-a");
    LaunchValidationService.WorkflowReplayExecutionBinding replayBinding =
        new LaunchValidationService.WorkflowReplayExecutionBinding(
            701L, 900L, "IMPORT_NODE", executionGuard, protectedTemplates, Map.of(), Map.of());
    JobDefinitionEntity definition = JobDefinitionEntity.builder()
        .id(501L)
        .tenantId("t1")
        .jobCode("IMPORT_JOB")
        .jobName("Import Job")
        .jobType(JobType.IMPORT.code())
        .bizType("BIZ")
        .workerGroup("WG")
        .queueCode("QUEUE")
        .paramSchema(Map.of())
        .defaultParams(Map.of())
        .priority(5)
        .version(1)
        .enabled(true)
        .build();
    LaunchValidationService.LaunchLoadResult loaded = new LaunchValidationService.LaunchLoadResult(
        triggerRequest, definition, null, null, null, replayBinding);

    WorkflowRunEntity parentRun = new WorkflowRunEntity();
    parentRun.setId(900L);
    parentRun.setTenantId("t1");
    parentRun.setWorkflowDefinitionId(90L);
    parentRun.setWorkflowVersionSnapshot(7);
    Map<String, Object> declarationSnapshot = Map.of(
        "pluginId",
        "jdbc_mapped",
        "pluginVersion",
        "1",
        "externalEffectCategories",
        List.of("BUSINESS_DATABASE"),
        "idempotencyCapability",
        "IDEMPOTENT_BY_PLUGIN_LOGIC",
        "idempotencyKeyScope",
        "tenant+business-key",
        "recoveryProtocolId",
        "IMPORT_SQL_V1",
        "contractVersion",
        ReplayCapabilityDeclaration.CURRENT_CONTRACT_VERSION);
    Map<String, Object> nodeJobSnapshot = Map.of(
        "jobDefinitionId",
        501,
        "jobCode",
        "IMPORT_JOB",
        "jobType",
        JobType.IMPORT.code(),
        "jobDefinitionVersion",
        1,
        "systemPipelineCode",
        "IMPORT",
        "systemPipelineVersion",
        1,
        "executionDefinition",
        Map.of(
            "id",
            501,
            "tenantId",
            "t1",
            "jobCode",
            "IMPORT_JOB",
            "jobType",
            JobType.IMPORT.code(),
            "version",
            1),
        "replayExecutionGuard",
        Map.of("replayOperationId", 701L, "declaration", declarationSnapshot),
        "frozenFileTemplateSnapshots",
        protectedTemplates);
    Map<String, Object> executionSnapshot = Map.of(
        "schemaVersion",
        2,
        "workflowDefinitionId",
        90,
        "workflowVersion",
        7,
        "workflowCalendarCode",
        "CALENDAR_A",
        "replayOperationId",
        701L,
        "nodes",
        List.of(Map.of(
            "nodeCode",
            "IMPORT_NODE",
            "nodeType",
            "JOB",
            "enabled",
            true,
            "tenantId",
            "t1",
            "workflowDefinitionId",
            90,
            "relatedJobCode",
            "IMPORT_JOB")),
        "edges",
        List.of(),
        "jobDefinitions",
        Map.of("IMPORT_NODE", nodeJobSnapshot));
    String serializedExecutionSnapshot = JsonUtils.toJson(executionSnapshot);
    parentRun.setExecutionSnapshot(serializedExecutionSnapshot);
    when(workflowRunMapper.selectById("t1", 900L)).thenReturn(parentRun);
    when(fileTemplateSnapshots.pinWorkflowReplay(any(), any(), anyMap(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));

    service.prepareJobInstance(request, loaded, replayParams, request.traceId());

    verify(fileTemplateSnapshots)
        .pinWorkflowReplay(request, definition, replayParams, replayBinding);
    verify(fileTemplateSnapshots, never()).pin(request, definition, replayParams);
  }

  private TriggerRequestEntity acceptedTriggerRequest(LaunchRequest request) {
    TriggerRequestEntity original = new TriggerRequestEntity();
    original.setId(100L);
    original.setTenantId(request.tenantId());
    original.setRequestId(request.requestId());
    original.setJobCode(request.jobCode());
    original.setWorkflowDefinitionId(request.workflowDefinitionId());
    original.setBizDate(request.bizDate());
    original.setDryRun(Boolean.TRUE.equals(request.dryRun()));
    original.setRequestStatus(TriggerRequestStatus.ACCEPTED.code());
    // 重复和前置拒绝分支不会进入T1，共同夹具只为实际准备事务提供原请求。
    lenient()
        .when(triggerRequestMapper.selectByTenantAndRequestIdForUpdate(
            request.tenantId(), request.requestId()))
        .thenReturn(original);
    return original;
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
    return LocalDate.of(2026, Month.MARCH, 27)
        .plusDays(1)
        .atTime(LocalTime.of(6, 0))
        .atZone(ZoneId.of("Asia/Shanghai"))
        .toInstant()
        .plusSeconds(120L * 60L);
  }
}
