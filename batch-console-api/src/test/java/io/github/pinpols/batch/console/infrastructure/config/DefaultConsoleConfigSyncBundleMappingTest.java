package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncSummaryResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.ItemStats;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import io.github.pinpols.batch.console.domain.config.param.ConfigSyncLogInsertParam;
import io.github.pinpols.batch.console.domain.config.param.ConfigSyncLogUpdateParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.mapper.ConfigSyncLogMapper;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
@DisplayName("配置同步包: 所有导出配置类别都传入目标租户初始化并计入汇总")
class DefaultConsoleConfigSyncBundleMappingTest {

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @Mock
  private ConsoleTenantConfigCopyService copyService;

  @Mock
  private ConfigSyncImportExecutor importExecutor;

  @Mock
  private ConsoleRequestMetadataResolver metadataResolver;

  @Mock
  private ConfigSyncLogMapper logMapper;

  @Mock
  private PlatformTransactionManager transactionManager;

  @InjectMocks
  private DefaultConsoleConfigSyncApplicationService service;

  @BeforeEach
  void prepareSuccessfulAuditWrites() {
    // 汇总与初始审计拒绝场景不触达全部审计步骤，仅此两个共享 stub 允许未使用。
    lenient()
        .doAnswer(invocation -> {
          ConfigSyncLogInsertParam params = invocation.getArgument(0);
          params.setId(1L);
          return 1;
        })
        .when(logMapper)
        .insert(any());
    lenient().when(logMapper.updateResult(any())).thenReturn(1);
  }

  @Test
  @DisplayName("导入不丢失资源队列、窗口、业务日历、配额策略及告警路由")
  void shouldImportPassesEveryBundleCategoryToTenantInitialization() {
    ConfigSyncBundlePayload bundle = fullBundle();
    ConfigSyncImportRequest request = new ConfigSyncImportRequest();
    request.setTenantId("target-tenant");
    request.setSourceEnv("source");
    request.setTargetEnv("target");
    request.setTargetTenantIds(List.of("target-tenant"));
    request.setBundle(bundle);
    when(tenantGuard.resolveTenant("target-tenant")).thenReturn("target-tenant");
    when(metadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata(null, null, "target-tenant", "operator", null, null));
    when(importExecutor.execute(any(), any()))
        .thenReturn(new TenantConfigBatchInitResponse("batch-1", 1, 1, 0, false, List.of()));

    var response = service.importBundle(request);

    ArgumentCaptor<TenantConfigBatchInitRequest> initRequest =
        ArgumentCaptor.forClass(TenantConfigBatchInitRequest.class);
    verify(importExecutor).execute(any(), initRequest.capture());
    assertThat(initRequest.getValue().getJobDefinitions()).isSameAs(bundle.getJobDefinitions());
    assertThat(initRequest.getValue().getWorkflowDefinitions())
        .isSameAs(bundle.getWorkflowDefinitions());
    assertThat(initRequest.getValue().getFileChannels()).isSameAs(bundle.getFileChannels());
    assertThat(initRequest.getValue().getFileTemplates()).isSameAs(bundle.getFileTemplates());
    assertThat(initRequest.getValue().getResourceQueues()).isSameAs(bundle.getResourceQueues());
    assertThat(initRequest.getValue().getBatchWindows()).isSameAs(bundle.getBatchWindows());
    assertThat(initRequest.getValue().getBusinessCalendars())
        .isSameAs(bundle.getBusinessCalendars());
    assertThat(initRequest.getValue().getQuotaPolicies()).isSameAs(bundle.getQuotaPolicies());
    assertThat(initRequest.getValue().getAlertRoutings()).isSameAs(bundle.getAlertRoutings());
    assertThat(response.summary().total()).isEqualTo(9);
  }

  @Test
  @DisplayName("配置同步汇总逐类计入九类当前可导入配置")
  void shouldSummaryCountsEveryCurrentBundleCategory() {
    ConfigSyncSummaryResponse summary = ConfigSyncSummaryResponse.from(fullBundle());

    assertThat(summary.jobDefinitions()).isEqualTo(1);
    assertThat(summary.workflowDefinitions()).isEqualTo(1);
    assertThat(summary.fileChannels()).isEqualTo(1);
    assertThat(summary.fileTemplates()).isEqualTo(1);
    assertThat(summary.resourceQueues()).isEqualTo(1);
    assertThat(summary.batchWindows()).isEqualTo(1);
    assertThat(summary.businessCalendars()).isEqualTo(1);
    assertThat(summary.quotaPolicies()).isEqualTo(1);
    assertThat(summary.alertRoutings()).isEqualTo(1);
    assertThat(summary.total()).isEqualTo(9);
  }

  @Test
  @DisplayName("同步日志按各目标租户的配置项结果计数并反映单项依赖失败")
  void shouldRecordItemFailuresAcrossTargetTenants() {
    ConfigSyncBundlePayload bundle = fullBundle();
    ConfigSyncImportRequest request = new ConfigSyncImportRequest();
    request.setTenantId("target-tenant");
    request.setSourceEnv("source");
    request.setTargetEnv("target");
    request.setTargetTenantIds(List.of("tenant-a", "tenant-b"));
    request.setBundle(bundle);
    when(tenantGuard.resolveTenant("target-tenant")).thenReturn("target-tenant");
    when(metadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata(null, null, "target-tenant", "operator", null, null));
    TenantInitResult partiallyApplied = new TenantInitResult(
        "tenant-a",
        true,
        null,
        new ItemStats(0, 0, 0, 1, 0, List.of()),
        created(),
        created(),
        created(),
        created(),
        created(),
        created(),
        created(),
        created());
    TenantInitResult fullyApplied = new TenantInitResult(
        "tenant-b",
        true,
        null,
        created(),
        created(),
        created(),
        created(),
        created(),
        created(),
        created(),
        created(),
        created());
    when(importExecutor.execute(any(), any()))
        .thenReturn(new TenantConfigBatchInitResponse(
            "batch-1", 2, 2, 0, false, List.of(partiallyApplied, fullyApplied)));

    service.importBundle(request);

    ArgumentCaptor<ConfigSyncLogInsertParam> insert = ArgumentCaptor.captor();
    verify(logMapper).insert(insert.capture());
    assertThat(insert.getValue().getTotalItems()).isEqualTo(18);

    ArgumentCaptor<ConfigSyncLogUpdateParam> update = ArgumentCaptor.captor();
    verify(logMapper).updateResult(update.capture());
    assertThat(update.getValue().syncStatus()).isEqualTo("PARTIAL_FAILED");
    assertThat(update.getValue().successItems()).isEqualTo(17);
    assertThat(update.getValue().failedItems()).isEqualTo(1);
    assertThat(update.getValue().skippedItems()).isEqualTo(0);
  }

  @Test
  @DisplayName("整租户导入失败时将该租户的全部配置项记为失败")
  void shouldCountEveryBundleItemAsFailedForFailedTenant() {
    ConfigSyncBundlePayload bundle = fullBundle();
    ConfigSyncImportRequest request = new ConfigSyncImportRequest();
    request.setTenantId("target-tenant");
    request.setSourceEnv("source");
    request.setTargetEnv("target");
    request.setTargetTenantIds(List.of("tenant-a"));
    request.setBundle(bundle);
    when(tenantGuard.resolveTenant("target-tenant")).thenReturn("target-tenant");
    when(metadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata(null, null, "target-tenant", "operator", null, null));
    when(importExecutor.execute(any(), any()))
        .thenReturn(new TenantConfigBatchInitResponse(
            "batch-1",
            1,
            0,
            1,
            false,
            List.of(TenantInitResult.failed("tenant-a", "tenant unavailable"))));

    service.importBundle(request);

    ArgumentCaptor<ConfigSyncLogInsertParam> insert = ArgumentCaptor.captor();
    verify(logMapper).insert(insert.capture());
    assertThat(insert.getValue().getTotalItems()).isEqualTo(9);

    ArgumentCaptor<ConfigSyncLogUpdateParam> update = ArgumentCaptor.captor();
    verify(logMapper).updateResult(update.capture());
    assertThat(update.getValue().syncStatus()).isEqualTo("PARTIAL_FAILED");
    assertThat(update.getValue().successItems()).isEqualTo(0);
    assertThat(update.getValue().failedItems()).isEqualTo(9);
    assertThat(update.getValue().skippedItems()).isEqualTo(0);
  }

  private static ItemStats created() {
    return new ItemStats(1, 0, 0, 0, 0, List.of());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("初始化结果未知时不伪造全部失败且审计不暴露数据库细节")
  void shouldKeepUnknownApplyOutcomeUnresolved(boolean databaseFailure) {
    ConfigSyncImportRequest request = importRequest();
    RuntimeException failure = databaseFailure
        ? new DataIntegrityViolationException("SQL /private/Mapper.xml")
        : new IllegalStateException("Internal state /private/Mapper.xml");
    when(importExecutor.execute(any(), any())).thenThrow(failure);

    assertThatThrownBy(() -> service.importBundle(request))
        .isInstanceOfSatisfying(BizException.class, error -> {
          assertThat(error.getCode()).isEqualTo(ResultCode.SYSTEM_ERROR);
          assertThat(error.getCause()).isSameAs(failure);
        });

    ArgumentCaptor<ConfigSyncLogUpdateParam> update = ArgumentCaptor.captor();
    verify(logMapper).updateResult(update.capture());
    assertThat(update.getValue().syncStatus()).isEqualTo("RUNNING");
    assertThat(update.getValue().successItems()).isEqualTo(0);
    assertThat(update.getValue().failedItems()).isEqualTo(0);
    assertThat(update.getValue().skippedItems()).isEqualTo(0);
    assertThat(update.getValue().mergeDetail()).isEqualTo(true);
    assertThat(update.getValue().detailJson())
        .contains("error.common.system_error", "outcomeKnown")
        .doesNotContain("SQL ", "Mapper.xml");
  }

  @Test
  @DisplayName("初始审计未插入时拒绝开始业务导入")
  void shouldRejectImportWhenInitialAuditWasNotWritten() {
    ConfigSyncImportRequest request = importRequest();
    doReturn(0).when(logMapper).insert(any());

    assertThatThrownBy(() -> service.importBundle(request))
        .isInstanceOf(IllegalStateException.class);

    verify(importExecutor, never()).execute(any(), any());
  }

  @Test
  @DisplayName("插入成功但没有生成审计主键时拒绝开始业务导入")
  void shouldRejectImportWhenAuditIdWasNotGenerated() {
    ConfigSyncImportRequest request = importRequest();
    doReturn(1).when(logMapper).insert(any());

    assertThatThrownBy(() -> service.importBundle(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not return an id");

    verify(importExecutor, never()).execute(any(), any());
  }

  @Test
  @DisplayName("最终日志更新零行不得报告导入和审计均成功")
  void shouldRejectZeroRowFinalAuditUpdate() {
    ConfigSyncImportRequest request = importRequest();
    when(importExecutor.execute(any(), any()))
        .thenReturn(new TenantConfigBatchInitResponse("operation", 1, 1, 0, false, List.of()));
    when(logMapper.updateResult(any())).thenReturn(0);

    assertThatThrownBy(() -> service.importBundle(request))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("初始审计保存原目标租户集合以支持崩溃后的准确对账")
  void shouldPersistOriginalTargets_whenImportStarts() {
    ConfigSyncImportRequest request = importRequest();
    when(importExecutor.execute(any(), any()))
        .thenReturn(new TenantConfigBatchInitResponse("operation", 1, 1, 0, false, List.of()));

    service.importBundle(request);

    ArgumentCaptor<ConfigSyncLogInsertParam> inserted = ArgumentCaptor.captor();
    verify(logMapper).insert(inserted.capture());
    String detailJson = inserted.getValue().getDetailJson();
    assertThat(detailJson).contains("targetTenantIds", "tenant-a");
  }

  @Test
  @DisplayName("最终审计合并结果并保留最初的批次与目标身份")
  void shouldPreserveInitialIdentity_whenAuditCompletes() {
    ConfigSyncImportRequest request = importRequest();
    when(importExecutor.execute(any(), any()))
        .thenReturn(new TenantConfigBatchInitResponse("operation", 1, 1, 0, false, List.of()));

    service.importBundle(request);

    ArgumentCaptor<ConfigSyncLogUpdateParam> update = ArgumentCaptor.captor();
    verify(logMapper).updateResult(update.capture());
    assertThat(update.getValue().mergeDetail()).isEqualTo(true);
  }

  private ConfigSyncImportRequest importRequest() {
    ConfigSyncImportRequest request = new ConfigSyncImportRequest();
    request.setTenantId("target-tenant");
    request.setSourceEnv("source");
    request.setTargetEnv("target");
    request.setTargetTenantIds(List.of("tenant-a"));
    request.setBundle(fullBundle());
    when(tenantGuard.resolveTenant("target-tenant")).thenReturn("target-tenant");
    when(metadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata(null, null, "target-tenant", "operator", null, null));
    return request;
  }

  private static ConfigSyncBundlePayload fullBundle() {
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    bundle.setJobDefinitions(List.of(new TenantConfigBatchInitRequest.JobDefinitionSpec()));
    bundle.setWorkflowDefinitions(
        List.of(new TenantConfigBatchInitRequest.WorkflowDefinitionSpec()));
    bundle.setFileChannels(List.of(new TenantConfigBatchInitRequest.FileChannelSpec()));
    bundle.setFileTemplates(List.of(new TenantConfigBatchInitRequest.FileTemplateSpec()));
    bundle.setResourceQueues(List.of(new TenantConfigBatchInitRequest.ResourceQueueSpec()));
    bundle.setBatchWindows(List.of(new TenantConfigBatchInitRequest.BatchWindowSpec()));
    bundle.setBusinessCalendars(List.of(new TenantConfigBatchInitRequest.BusinessCalendarSpec()));
    bundle.setQuotaPolicies(List.of(new TenantConfigBatchInitRequest.TenantQuotaPolicySpec()));
    bundle.setAlertRoutings(List.of(new TenantConfigBatchInitRequest.AlertRoutingSpec()));
    return bundle;
  }
}
