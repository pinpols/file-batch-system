package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.domain.file.mapper.FileChannelConfigMapper;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineStepDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.InitMode;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import io.github.pinpols.batch.console.web.response.config.TenantConfigBatchInitResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Unit tests for DefaultConsoleTenantConfigInitApplicationService.
 *
 * <p>Covers: multi-tenant fan-out, SKIP_EXISTING vs UPSERT modes, per-item create/skip/update
 * counts, partial tenant failure isolation.
 */
@ExtendWith(MockitoExtension.class)
class DefaultConsoleTenantConfigInitApplicationServiceTest {

  @Mock private JobDefinitionMapper jobDefinitionMapper;
  @Mock private WorkflowDefinitionMapper workflowDefinitionMapper;
  @Mock private WorkflowNodeMapper workflowNodeMapper;
  @Mock private WorkflowEdgeMapper workflowEdgeMapper;
  @Mock private PipelineDefinitionMapper pipelineDefinitionMapper;
  @Mock private PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
  @Mock private FileChannelConfigMapper fileChannelConfigMapper;
  @Mock private FileTemplateConfigMapper fileTemplateConfigMapper;
  @Mock private ResourceQueueMapper resourceQueueMapper;
  @Mock private BatchWindowMapper batchWindowMapper;
  @Mock private BusinessCalendarMapper businessCalendarMapper;
  @Mock private CalendarHolidayMapper calendarHolidayMapper;
  @Mock private TenantQuotaPolicyMapper tenantQuotaPolicyMapper;
  @Mock private AlertRoutingConfigMapper alertRoutingConfigMapper;

  private DefaultConsoleTenantConfigInitApplicationService service;

  @BeforeEach
  void setUp() {
    TenantConfigInitApplyHandlers handlers =
        new TenantConfigInitApplyHandlers(
            jobDefinitionMapper,
            workflowDefinitionMapper,
            workflowNodeMapper,
            workflowEdgeMapper,
            pipelineDefinitionMapper,
            pipelineStepDefinitionMapper,
            fileChannelConfigMapper,
            fileTemplateConfigMapper,
            resourceQueueMapper,
            batchWindowMapper,
            businessCalendarMapper,
            calendarHolidayMapper,
            tenantQuotaPolicyMapper,
            alertRoutingConfigMapper,
            new PlatformTransactionManager() {
              @Override
              public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
              }

              @Override
              public void commit(TransactionStatus status) {}

              @Override
              public void rollback(TransactionStatus status) {}
            });
    ReflectionTestUtils.setField(handlers, "self", handlers);
    service = new DefaultConsoleTenantConfigInitApplicationService(handlers);
    ReflectionTestUtils.setField(service, "self", service);
  }

  // ------------------------------------------------------------------ job definitions

  @Test
  void batchInit_createsJobDefinitionWhenNotExists() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.getJobDefinitions().get(0).setDependsOnJobCode("upstream-job");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.successTenants()).isEqualTo(1);
    assertThat(response.results().get(0).jobDefinitions().created()).isEqualTo(1);
    assertThat(response.results().get(0).jobDefinitions().skipped()).isEqualTo(0);
    verify(jobDefinitionMapper)
        .insert(
            argThat(
                entity ->
                    "upstream-job".equals(entity.getDependsOnJobCode())
                        && "t1".equals(entity.getTenantId())));
  }

  @Test
  void batchInit_passesThroughExecutionModeAndWatermarkField() {
    // 回归:bundle/init 的 JobDefinitionSpec 曾漏 executionMode/watermarkField,
    // 致向导填 INCREMENTAL + 水位字段被静默丢弃、落库退化为 FULL。
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.getJobDefinitions().get(0).setExecutionMode("INCREMENTAL");
    request.getJobDefinitions().get(0).setWatermarkField("updated_at");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);

    service.batchInit(request, "admin", "batch-test-inc");

    verify(jobDefinitionMapper)
        .insert(
            argThat(
                entity ->
                    "INCREMENTAL".equals(entity.getExecutionMode())
                        && "updated_at".equals(entity.getWatermarkField())));
  }

  @Test
  void batchInit_defaultsExecutionModeToFullWhenSpecOmitsIt() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);

    service.batchInit(request, "admin", "batch-test-full");

    verify(jobDefinitionMapper).insert(argThat(entity -> "FULL".equals(entity.getExecutionMode())));
  }

  @Test
  void batchInit_skipsJobDefinitionWhenExistsInSkipMode() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.setMode(InitMode.SKIP_EXISTING);
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1"))
        .thenReturn(new JobDefinitionEntity());

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).jobDefinitions().skipped()).isEqualTo(1);
    assertThat(response.results().get(0).jobDefinitions().created()).isEqualTo(0);
    verify(jobDefinitionMapper, never()).insert(any());
  }

  @Test
  void batchInit_updatesJobDefinitionWhenExistsInUpsertMode() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.getJobDefinitions().get(0).setDependsOnJobCode("next-upstream");
    request.setMode(InitMode.UPSERT);
    JobDefinitionEntity existing = new JobDefinitionEntity();
    existing.setJobCode("job-1");
    existing.setTenantId("t1");
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(existing);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).jobDefinitions().updated()).isEqualTo(1);
    verify(jobDefinitionMapper)
        .updateJobDefinitionMaintenance(
            argThat(param -> "next-upstream".equals(param.getDependsOnJobCode())));
  }

  // ------------------------------------------------------------------ file channels

  @Test
  void batchInit_createsFileChannelWhenNotExists() {
    TenantConfigBatchInitRequest request = requestWithChannel("ch-1", List.of("t1"));
    when(fileChannelConfigMapper.selectByUniqueKey("t1", "ch-1")).thenReturn(null);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).fileChannels().created()).isEqualTo(1);
    verify(fileChannelConfigMapper).upsertFileChannelConfig(any());
  }

  @Test
  void batchInit_skipsFileChannelWhenExistsInSkipMode() {
    TenantConfigBatchInitRequest request = requestWithChannel("ch-1", List.of("t1"));
    when(fileChannelConfigMapper.selectByUniqueKey("t1", "ch-1")).thenReturn(Map.of("id", 1));

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).fileChannels().skipped()).isEqualTo(1);
    verify(fileChannelConfigMapper, never()).upsertFileChannelConfig(any());
  }

  @Test
  void batchInit_upsertsFileChannelInUpsertMode() {
    TenantConfigBatchInitRequest request = requestWithChannel("ch-1", List.of("t1"));
    request.setMode(InitMode.UPSERT);
    when(fileChannelConfigMapper.selectByUniqueKey("t1", "ch-1")).thenReturn(Map.of("id", 1));

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).fileChannels().updated()).isEqualTo(1);
    verify(fileChannelConfigMapper).upsertFileChannelConfig(any());
  }

  // ------------------------------------------------------------------ file templates

  @Test
  void batchInit_createsFileTemplateWhenNotExists() {
    TenantConfigBatchInitRequest request = requestWithTemplate("tpl-1", List.of("t1"));
    when(fileTemplateConfigMapper.selectByUniqueKey("t1", "tpl-1", 1)).thenReturn(null);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).fileTemplates().created()).isEqualTo(1);
    verify(fileTemplateConfigMapper).upsertFileTemplateConfig(any());
  }

  @Test
  void batchInit_skipsFileTemplateWhenExistsInSkipMode() {
    TenantConfigBatchInitRequest request = requestWithTemplate("tpl-1", List.of("t1"));
    when(fileTemplateConfigMapper.selectByUniqueKey("t1", "tpl-1", 1)).thenReturn(Map.of("id", 1));

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).fileTemplates().skipped()).isEqualTo(1);
    verify(fileTemplateConfigMapper, never()).upsertFileTemplateConfig(any());
  }

  // ------------------------------------------------------------------ multi-tenant fan-out

  @Test
  void batchInit_appliesConfigToAllTargetTenants() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1", "t2", "t3"));
    when(jobDefinitionMapper.selectByUniqueKey(anyString(), eq("job-1"))).thenReturn(null);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.totalTenants()).isEqualTo(3);
    assertThat(response.successTenants()).isEqualTo(3);
    verify(jobDefinitionMapper, times(3)).insert(any(JobDefinitionEntity.class));
  }

  @Test
  void batchInit_itemFailureTrackedInStatsBothTenantsStillSuccess() {
    // Per-item exceptions are caught at item level → tracked as failed count, tenant = success
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1", "t2"));
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);
    when(jobDefinitionMapper.selectByUniqueKey("t2", "job-1")).thenReturn(null);
    when(jobDefinitionMapper.insert(any()))
        .thenAnswer(
            inv -> {
              JobDefinitionEntity e = inv.getArgument(0);
              if ("t2".equals(e.getTenantId())) {
                throw new RuntimeException("DB error for t2");
              }
              return 1;
            });

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    // t1 succeeds with 1 created; t2 has 1 failed item but tenant-level success=true
    assertThat(response.totalTenants()).isEqualTo(2);
    TenantConfigBatchInitResponse.TenantInitResult t1Result =
        response.results().stream()
            .filter(r -> "t1".equals(r.tenantId()))
            .findFirst()
            .orElseThrow();
    assertThat(t1Result.success()).isTrue();
    assertThat(t1Result.jobDefinitions().created()).isEqualTo(1);

    TenantConfigBatchInitResponse.TenantInitResult t2Result =
        response.results().stream()
            .filter(r -> "t2".equals(r.tenantId()))
            .findFirst()
            .orElseThrow();
    assertThat(t2Result.success()).isTrue();
    assertThat(t2Result.jobDefinitions().failed()).isEqualTo(1);
  }

  @Test
  void batchInit_strictModeRollsBackOnItemFailure() {
    // strict=true (Job Bundle 路径): 任一 spec failed 即整体回滚,tenant 结果标 failed
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.setStrict(true);
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);
    when(jobDefinitionMapper.insert(any(JobDefinitionEntity.class)))
        .thenThrow(new RuntimeException("simulated DB error"));

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.totalTenants()).isEqualTo(1);
    assertThat(response.failureTenants()).isEqualTo(1);
    TenantConfigBatchInitResponse.TenantInitResult t1Result = response.results().get(0);
    assertThat(t1Result.success()).isFalse();
    assertThat(t1Result.errorMessage()).contains("strict bundle aborted");
  }

  @Test
  void batchInit_handlesEmptyConfigLists() {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of("t1", "t2"));

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.successTenants()).isEqualTo(2);
    assertThat(response.results()).hasSize(2);
    response
        .results()
        .forEach(
            r -> {
              assertThat(r.success()).isTrue();
              assertThat(r.jobDefinitions().created()).isEqualTo(0);
              assertThat(r.fileChannels().created()).isEqualTo(0);
            });
  }

  // ------------------------------------------------------------------ workflow definitions

  @Test
  void batchInit_createsWorkflowWhenNotExists() {
    TenantConfigBatchInitRequest request = requestWithWorkflow("wf-1", List.of("t1"));
    when(workflowDefinitionMapper.selectByUniqueKey("t1", "wf-1", 1)).thenReturn(null);
    // After upsert, simulate saved entity
    WorkflowDefinitionEntity saved = new WorkflowDefinitionEntity();
    saved.setId(10L);
    saved.setWorkflowCode("wf-1");
    when(workflowDefinitionMapper.upsertWorkflowDefinition(any())).thenReturn(1);
    when(workflowDefinitionMapper.selectByUniqueKey("t1", "wf-1", 1))
        .thenReturn(null) // first call (check existence) = not found
        .thenReturn(saved); // second call (after upsert) = found

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).workflowDefinitions().created()).isEqualTo(1);
    verify(workflowDefinitionMapper).upsertWorkflowDefinition(any());
  }

  @Test
  void batchInit_skipsWorkflowWhenExistsInSkipMode() {
    TenantConfigBatchInitRequest request = requestWithWorkflow("wf-1", List.of("t1"));
    WorkflowDefinitionEntity existing = new WorkflowDefinitionEntity();
    existing.setId(1L);
    when(workflowDefinitionMapper.selectByUniqueKey("t1", "wf-1", 1)).thenReturn(existing);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-test-001");

    assertThat(response.results().get(0).workflowDefinitions().skipped()).isEqualTo(1);
    verify(workflowDefinitionMapper, never()).upsertWorkflowDefinition(any());
  }

  // ------------------------------------------------------------------ dry-run & batchOperationId

  @Test
  void batchInit_dryRunSkipsAllWrites() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    request.setDryRun(true);
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-dry-001");

    assertThat(response.dryRun()).isTrue();
    assertThat(response.batchOperationId()).isEqualTo("batch-dry-001");
    assertThat(response.results().get(0).jobDefinitions().created()).isEqualTo(1);
    verify(jobDefinitionMapper, never()).insert(any());
  }

  @Test
  void batchInit_returnsBatchOperationId() {
    TenantConfigBatchInitRequest request = requestWithJobDef("job-1", List.of("t1"));
    when(jobDefinitionMapper.selectByUniqueKey("t1", "job-1")).thenReturn(null);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", "batch-abc-123");

    assertThat(response.batchOperationId()).isEqualTo("batch-abc-123");
    assertThat(response.dryRun()).isFalse();
  }

  // ------------------------------------------------------------------ helpers

  private TenantConfigBatchInitRequest requestWithJobDef(String jobCode, List<String> tenants) {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(tenants);
    JobDefinitionSpec spec = new JobDefinitionSpec();
    spec.setJobCode(jobCode);
    spec.setJobType("BATCH");
    spec.setScheduleType("CRON");
    spec.setEnabled(true);
    request.setJobDefinitions(List.of(spec));
    return request;
  }

  private TenantConfigBatchInitRequest requestWithChannel(
      String channelCode, List<String> tenants) {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(tenants);
    FileChannelSpec spec = new FileChannelSpec();
    spec.setChannelCode(channelCode);
    spec.setChannelType("SFTP");
    spec.setEnabled(true);
    request.setFileChannels(List.of(spec));
    return request;
  }

  private TenantConfigBatchInitRequest requestWithTemplate(
      String templateCode, List<String> tenants) {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(tenants);
    FileTemplateSpec spec = new FileTemplateSpec();
    spec.setTemplateCode(templateCode);
    spec.setTemplateType("IMPORT");
    spec.setVersion(1);
    spec.setEnabled(true);
    request.setFileTemplates(List.of(spec));
    return request;
  }

  private TenantConfigBatchInitRequest requestWithWorkflow(
      String workflowCode, List<String> tenants) {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(tenants);
    WorkflowDefinitionSpec spec = new WorkflowDefinitionSpec();
    spec.setWorkflowCode(workflowCode);
    spec.setEnabled(true);
    request.setWorkflowDefinitions(List.of(spec));
    return request;
  }
}
