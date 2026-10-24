package io.github.pinpols.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.FrozenFileTemplateSnapshot;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.ReplayExecutionGuard;
import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.mapper.FileTemplateSnapshotMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchValidationService.WorkflowReplayExecutionBinding;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("启动事务捕获文件模板，历史重放不静默切换当前配置")
class FileTemplateSnapshotServiceTest {

  @Mock
  private FileTemplateSnapshotMapper templates;

  @Mock
  private JobInstanceMapper instances;

  @Mock
  private SecretPayloadProtector protector;

  @InjectMocks
  private FileTemplateSnapshotService service;

  @Test
  @DisplayName("导入固定数据库模板，丢弃请求方自带的快照且不修改原参数")
  void shouldCaptureDatabaseTemplate_whenCallerSuppliesUntrustedSnapshot() {
    stubLatest("TPL_A", "IMPORT");
    Map<String, Object> parameters = Map.of(
        "templateCode",
        "TPL_A",
        "frozenFileTemplateProtected",
        "forged",
        "_frozenFileTemplatesProtected",
        Map.of("OTHER", "forged"));

    Map<String, Object> pinned = service.pin(request(), definition("IMPORT"), parameters);

    assertThat(pinned).containsEntry("frozenFileTemplateProtected", "sealed-TPL_A");
    assertThat(pinned).doesNotContainKey("_frozenFileTemplatesProtected");
    assertThat(parameters).containsEntry("frozenFileTemplateProtected", "forged");
    verifyNoInteractions(instances);
  }

  @Test
  @DisplayName("导出未指定模板时与 Worker 一致使用作业编码加 TPL 后缀")
  void shouldCaptureExportFallback_whenTemplateCodeIsAbsent() {
    stubLatest("FILE_JOB_TPL", "EXPORT");

    Map<String, Object> pinned = service.pin(request(), definition("EXPORT"), Map.of());

    assertThat(pinned).containsEntry("frozenFileTemplateProtected", "sealed-FILE_JOB_TPL");
  }

  @Test
  @DisplayName("整 Workflow 预览捕获模板密文并按同一配置解析导入插件")
  void shouldCaptureReplayTemplateAndResolveImportPluginFromFrozenConfiguration() {
    Map<String, Object> configuration = templateConfiguration("TPL_A", "IMPORT");
    configuration.put("load_target_ref", "tenant_loader");
    String snapshotJson = JsonUtils.toJson(FrozenFileTemplateSnapshot.capture(configuration));
    when(templates.selectLatestConfiguration("tenant-a", "TPL_A", "IMPORT"))
        .thenReturn(JsonUtils.toJson(configuration));
    when(protector.protect(anyString())).thenReturn("sealed-TPL_A");
    when(protector.revealEncrypted("sealed-TPL_A")).thenReturn(snapshotJson);

    FileTemplateSnapshotService.ReplayTemplateSelection selection =
        service.captureForWorkflowReplay(
            "tenant-a",
            definition("IMPORT"),
            Map.of("templateCode", "TPL_A"),
            ConfigVersionPolicy.USE_LATEST_CONFIG,
            null);

    assertThat(selection.protectedSnapshots()).containsEntry("TPL_A", "sealed-TPL_A");
    assertThat(selection.pluginId()).isEqualTo("tenant_loader");
  }

  @Test
  @DisplayName("整 Workflow 导出预览从模板嵌套 schema 解析实际导出插件")
  void shouldResolveExportPluginFromFrozenTemplateSchema() {
    Map<String, Object> configuration = templateConfiguration("TPL_A", "EXPORT");
    configuration.put("query_param_schema", Map.of("export_data_ref", "mapped_export"));
    String snapshotJson = JsonUtils.toJson(FrozenFileTemplateSnapshot.capture(configuration));
    when(templates.selectLatestConfiguration("tenant-a", "TPL_A", "EXPORT"))
        .thenReturn(JsonUtils.toJson(configuration));
    when(protector.protect(anyString())).thenReturn("sealed-TPL_A");
    when(protector.revealEncrypted("sealed-TPL_A")).thenReturn(snapshotJson);

    FileTemplateSnapshotService.ReplayTemplateSelection selection =
        service.captureForWorkflowReplay(
            "tenant-a",
            definition("EXPORT"),
            Map.of("templateCode", "TPL_A"),
            ConfigVersionPolicy.USE_LATEST_CONFIG,
            null);

    assertThat(selection.pluginId()).isEqualTo("mapped_export");
  }

  @Test
  @DisplayName("审批后的文件 Job 只使用父 Workflow 节点绑定的模板密文，不查询 latest")
  void shouldApplyOnlyApprovedReplayTemplateBoundToParentNode() {
    String protectedSnapshot = "sealed-TPL_A";
    when(protector.revealEncrypted(protectedSnapshot)).thenReturn(snapshot("TPL_A", "IMPORT"));
    WorkflowReplayExecutionBinding binding = replayBinding(
        "IMPORT_NODE", Map.of("TPL_A", protectedSnapshot));

    Map<String, Object> pinned = service.pinWorkflowReplay(
        workflowReplayRequest("IMPORT_NODE", Map.of("templateCode", "TPL_A")),
        definition("IMPORT"),
        replayParams("IMPORT_NODE", Map.of("templateCode", "TPL_A")),
        binding);

    assertThat(pinned)
        .containsEntry(FrozenFileTemplateSnapshot.PAYLOAD_KEY, protectedSnapshot)
        .containsEntry("operationType", "WORKFLOW_RUN");
    verifyNoInteractions(templates, instances);
  }

  @Test
  @DisplayName("整 Workflow 子 Job 不接受其他节点的模板密文绑定")
  void shouldRejectReplayTemplateBoundToDifferentNode() {
    WorkflowReplayExecutionBinding binding = replayBinding(
        "OTHER_NODE", Map.of("TPL_A", "sealed-TPL_A"));

    assertThatThrownBy(() -> service.pinWorkflowReplay(
            workflowReplayRequest("IMPORT_NODE", Map.of("templateCode", "TPL_A")),
            definition("IMPORT"),
            replayParams("IMPORT_NODE", Map.of("templateCode", "TPL_A")),
            binding))
        .isInstanceOf(BizException.class);

    verifyNoInteractions(templates, instances, protector);
  }

  @Test
  @DisplayName("文件束按模板去重捕获，重复文件不重复读取模板")
  void shouldCaptureEachTemplateOnce_whenBundleRepeatsTemplate() {
    stubLatest("TPL_A", "IMPORT");
    Map<String, Object> parameters = Map.of(
        "bundleFiles",
        List.of(
            Map.of("sourceFileId", 11, "templateCode", "TPL_A"),
            Map.of("sourceFileId", 12, "templateCode", "TPL_A")));

    Map<String, Object> pinned = service.pin(request(), definition("BUNDLE_IMPORT"), parameters);

    assertThat(pinned)
        .containsEntry("_frozenFileTemplatesProtected", Map.of("TPL_A", "sealed-TPL_A"));
    assertThat(pinned).doesNotContainKey("frozenFileTemplateProtected");
    verify(templates, times(1)).selectLatestConfiguration("tenant-a", "TPL_A", "IMPORT");
  }

  @Test
  @DisplayName("普通无模板载荷保留既有执行语义，不生成虚假的模板快照")
  void shouldKeepPayloadOnlyExecution_whenNoMatchingTemplateExists() {
    Map<String, Object> parameters = Map.of("templateCode", "MISSING");

    Map<String, Object> pinned = service.pin(request(), definition("IMPORT"), parameters);

    assertThat(pinned).isEqualTo(parameters);
    verifyNoInteractions(protector, instances);
  }

  @Test
  @DisplayName("非文件作业不读取模板且不能透传调用方伪造的内部快照集合")
  void shouldStripReservedFields_whenJobDoesNotUseFileTemplates() {
    Map<String, Object> parameters = Map.of("frozenFileTemplateProtected", "forged");

    Map<String, Object> pinned = service.pin(request(), definition("PROCESS"), parameters);

    assertThat(pinned).isEmpty();
    verifyNoInteractions(templates, instances, protector);
  }

  @Test
  @DisplayName("原配置重放从同租户同作业同业务日来源实例回读真实密文，不读取 latest")
  void shouldReusePersistedSourceTemplate_whenOriginalPolicySelected() {
    JobInstanceEntity source = source(Map.of("frozenFileTemplateProtected", "source-template"));
    when(instances.selectById("tenant-a", 91L)).thenReturn(source);
    String plaintext = snapshot("TPL_A", "IMPORT");
    when(protector.revealEncrypted("source-template")).thenReturn(plaintext);
    Map<String, Object> parameters = Map.of(
        "templateCode",
        "TPL_A",
        "operationType",
        "JOB_RERUN",
        "parentInstanceId",
        91,
        "frozenFileTemplateProtected",
        "forged");

    Map<String, Object> pinned = service.pin(request(), definition("IMPORT"), parameters);

    assertThat(pinned).containsEntry("frozenFileTemplateProtected", "source-template");
    verifyNoInteractions(templates);
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant", "job", "date"})
  @DisplayName("来源实例租户、作业或业务日不同则不能复用冻结模板")
  void shouldRejectSourceIdentityMismatch_whenOriginalPolicySelected(String mismatch) {
    JobInstanceEntity source = source(Map.of("frozenFileTemplateProtected", "source-template"));
    switch (mismatch) {
      case "tenant" -> source.setTenantId("other");
      case "job" -> source.setJobCode("other");
      case "date" -> source.setBizDate(LocalDate.of(2026, 10, 19));
      default -> throw new AssertionError(mismatch);
    }
    when(instances.selectById("tenant-a", 91L)).thenReturn(source);
    Map<String, Object> parameters =
        Map.of("templateCode", "TPL_A", "operationType", "JOB_RERUN", "parentInstanceId", 91);

    assertThatThrownBy(() -> service.pin(request(), definition("IMPORT"), parameters))
        .isInstanceOf(BizException.class);

    verifyNoInteractions(templates, protector);
  }

  @Test
  @DisplayName("来源实例没有实际模板快照时失败关闭，不猜测历史模板版本")
  void shouldRejectMissingOriginalTemplate_whenOriginalPolicySelected() {
    JobInstanceEntity source = source(Map.of());
    when(instances.selectById("tenant-a", 91L)).thenReturn(source);
    Map<String, Object> parameters =
        Map.of("templateCode", "TPL_A", "operationType", "JOB_RERUN", "parentInstanceId", 91);

    assertThatThrownBy(() -> service.pin(request(), definition("IMPORT"), parameters))
        .isInstanceOf(BizException.class);

    verifyNoInteractions(templates, protector);
  }

  @Test
  @DisplayName("指定 Job 版本不能被当作文件模板版本，缺少独立版本证据时拒绝")
  void shouldRejectImplicitTemplateVersion_whenSpecifiedJobVersionSelected() {
    Map<String, Object> parameters =
        Map.of("templateCode", "TPL_A", "_rerunConfigVersionPolicy", "USE_SPECIFIED_VERSION");

    assertThatThrownBy(() -> service.pin(request(), definition("IMPORT"), parameters))
        .isInstanceOf(BizException.class)
        .hasMessage("error.rerun.file_template_version_unavailable");

    verifyNoInteractions(templates, instances, protector);
  }

  @Test
  @DisplayName("整 Workflow 的普通启动路径不能绕过尚未接通的审批快照绑定")
  void shouldRejectUnboundWholeReplay_whenCallerSuppliesProtectedTemplate() {
    Map<String, Object> parameters = Map.of(
        "templateCode",
        "TPL_A",
        "operationType",
        "WORKFLOW_RUN",
        "frozenFileTemplateProtected",
        "caller-template");

    assertThatThrownBy(() -> service.pin(request(), definition("IMPORT"), parameters))
        .isInstanceOf(BizException.class);

    verifyNoInteractions(templates, instances, protector);
  }

  private void stubLatest(String code, String type) {
    String snapshotJson = snapshot(code, type);
    FrozenFileTemplateSnapshot snapshot =
        JsonUtils.fromJsonStrict(snapshotJson, FrozenFileTemplateSnapshot.class);
    String configurationJson = JsonUtils.toJson(snapshot.configuration());
    when(templates.selectLatestConfiguration("tenant-a", code, type)).thenReturn(configurationJson);
    when(protector.protect(anyString())).thenReturn("sealed-" + code);
    when(protector.revealEncrypted("sealed-" + code)).thenReturn(snapshotJson);
  }

  private String snapshot(String code, String type) {
    Map<String, Object> configuration = templateConfiguration(code, type);
    FrozenFileTemplateSnapshot snapshot = FrozenFileTemplateSnapshot.capture(configuration);
    return JsonUtils.toJson(snapshot);
  }

  private Map<String, Object> templateConfiguration(String code, String type) {
    Map<String, Object> configuration = new java.util.LinkedHashMap<>(Map.of(
        "id",
        101,
        "tenant_id",
        "tenant-a",
        "template_code",
        code,
        "template_type",
        type,
        "version",
        2,
        "file_format_type",
        "DELIMITED"));
    return configuration;
  }

  private JobInstanceEntity source(Map<String, Object> effectiveParams) {
    JobInstanceEntity source = new JobInstanceEntity();
    source.setTenantId("tenant-a");
    source.setJobCode("FILE_JOB");
    source.setJobDefinitionId(7L);
    source.setBizDate(LocalDate.of(2026, 10, 20));
    String snapshot = JsonUtils.toJson(Map.of("effectiveParams", effectiveParams));
    source.setParamsSnapshot(snapshot);
    return source;
  }

  private JobDefinitionEntity definition(String type) {
    return JobDefinitionEntity.builder()
        .id(7L)
        .tenantId("tenant-a")
        .jobCode("FILE_JOB")
        .jobType(type)
        .build();
  }

  private LaunchRequest request() {
    return request(Map.of());
  }

  private LaunchRequest request(Map<String, Object> params) {
    return LaunchRequest.builder()
        .tenantId("tenant-a")
        .jobCode("FILE_JOB")
        .bizDate(LocalDate.of(2026, 10, 20))
        .params(params)
        .build();
  }

  private LaunchRequest workflowReplayRequest(String nodeCode, Map<String, Object> params) {
    return request(replayParams(nodeCode, params));
  }

  private Map<String, Object> replayParams(String nodeCode, Map<String, Object> params) {
    Map<String, Object> replayParams = new java.util.LinkedHashMap<>(params);
    replayParams.put("operationType", "WORKFLOW_RUN");
    replayParams.put("_parentWorkflowRunId", 51L);
    replayParams.put("_parentNodeCode", nodeCode);
    return replayParams;
  }

  private WorkflowReplayExecutionBinding replayBinding(
      String nodeCode, Map<String, String> protectedTemplates) {
    ReplayCapabilityDeclaration declaration = new ReplayCapabilityDeclaration(
        "jdbc_mapped",
        "1",
        List.of("BUSINESS_DATABASE"),
        IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC,
        "tenant+business-key",
        "IMPORT_SQL_V1",
        1);
    ReplayExecutionGuard guard = new ReplayExecutionGuard(701L, declaration);
    return new WorkflowReplayExecutionBinding(
        701L, 51L, nodeCode, guard, protectedTemplates, Map.of(), Map.of());
  }
}
