package io.github.pinpols.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.dto.FrozenFileTemplateSnapshot;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineDefinitionRepository;
import io.github.pinpols.batch.worker.core.support.FrozenFileTemplateResolver;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportPayload;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("导出准备阶段使用已固定模板，禁止重新读取当前配置")
class PrepareStepFrozenTemplateTest {

  private final ObjectMapper objectMapper = JsonUtils.newDefaultMapper();

  @Mock
  private PlatformPipelineDefinitionRepository templates;

  @Mock
  private SecretPayloadProtector secretPayloadProtector;

  @InjectMocks
  private FrozenFileTemplateResolver resolver;

  private PrepareStep step;

  @BeforeEach
  void setUp() {
    step = new PrepareStep(objectMapper, resolver);
  }

  @Test
  @DisplayName("已有模板上下文决定格式和文件名且不读取最新版本")
  void shouldKeepCapturedTemplate_whenRuntimeTemplateHasChanged() {
    ExportJobContext context = new ExportJobContext();
    context.setTenantId("tenant-a");
    context.setJobCode("EXPORT_A");
    context.setBizDate("2026-10-20");
    context.setRawPayload("""
        {"templateCode":"EXPORT_A_TPL","batchNo":"batch-a","bizDate":"2026-10-20"}
        """);
    context
        .getAttributes()
        .put(
            PipelineRuntimeKeys.TEMPLATE_CONFIG,
            Map.of(
                "file_format_type", "DELIMITED",
                "naming_rule", "captured_${bizDate}"));

    var result = step.execute(context);

    verifyNoInteractions(templates);
    assertThat(result.success()).isTrue();
    Object fileName = context.getAttributes().get(PipelineRuntimeKeys.FILE_NAME);
    Object format = context.getAttributes().get(PipelineRuntimeKeys.EXPORT_FILE_FORMAT_TYPE);
    assertThat(fileName).isEqualTo("captured_2026-10-20");
    assertThat(format).isEqualTo("DELIMITED");
  }

  @Test
  @DisplayName("回放预检解析同一冻结模板且不执行导出阶段")
  void resolveTemplateConfig_usesFrozenTemplateWithoutExecutingStages() {
    ExportJobContext context = new ExportJobContext();
    context.setTenantId("tenant-a");
    context.setJobCode("EXPORT_A");
    context.setBizDate("2026-10-20");
    context.setRawPayload("""
        {"templateCode":"EXPORT_A_TPL","batchNo":"batch-a","bizDate":"2026-10-20"}
        """);
    Map<String, Object> frozenTemplate = Map.of(
        "file_format_type", "DELIMITED",
        "export_data_ref", "jdbc_mapped_export");
    context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, frozenTemplate);

    Map<String, Object> resolved = step.resolveTemplateConfig(context);

    assertThat(resolved).isEqualTo(frozenTemplate);
    assertThat(context.getAttributes().get(ExportRuntimeKeys.EXPORT_PAYLOAD))
        .isInstanceOf(ExportPayload.class);
    verifyNoInteractions(templates);
  }

  @Test
  @DisplayName("原始 CLAIM 载荷中的受保护模板决定本次导出，忽略当前配置")
  void shouldUseProtectedClaimTemplate_whenWholeWorkflowReplayExecutes() {
    Map<String, Object> configuration = Map.of(
        "id",
        101,
        "tenant_id",
        "tenant-a",
        "template_code",
        "TPL_A",
        "template_type",
        "EXPORT",
        "version",
        2,
        "file_format_type",
        "DELIMITED",
        "naming_rule",
        "frozen_${bizDate}");
    FrozenFileTemplateSnapshot snapshot = FrozenFileTemplateSnapshot.capture(configuration);
    String plaintext = JsonUtils.toJson(snapshot);
    when(secretPayloadProtector.revealEncrypted("protected-template")).thenReturn(plaintext);
    String rawPayload = JsonUtils.toJson(Map.of(
        "templateCode",
        "TPL_A",
        "bizDate",
        "2026-10-20",
        "batchNo",
        "batch-a",
        "operationType",
        "WORKFLOW_RUN",
        "frozenFileTemplateProtected",
        "protected-template"));
    ExportJobContext context = new ExportJobContext();
    context.setTenantId("tenant-a");
    context.setJobCode("EXPORT_A");
    context.setBizDate("2026-10-20");
    context.setRawPayload(rawPayload);

    var result = step.execute(context);

    assertThat(result.success()).isTrue();
    Object fileName = context.getAttributes().get(PipelineRuntimeKeys.FILE_NAME);
    Object selectedConfig = context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    assertThat(fileName).isEqualTo("frozen_2026-10-20");
    assertThat(selectedConfig).isEqualTo(configuration);
    verifyNoInteractions(templates);
  }

  @Test
  @DisplayName("整图重放缺少冻结模板时不允许回退已有普通上下文")
  void shouldFailPreparation_whenWholeWorkflowReplaySnapshotIsMissing() {
    ExportJobContext context = new ExportJobContext();
    context.setTenantId("tenant-a");
    context.setJobCode("EXPORT_A");
    context.setBizDate("2026-10-20");
    context.setRawPayload("""
        {"templateCode":"TPL_A","operationType":"WORKFLOW_RUN","bizDate":"2026-10-20"}
        """);
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("file_format_type", "JSON"));

    var result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("FROZEN_FILE_TEMPLATE_INVALID");
    assertThat(result.errorKey()).isEqualTo("error.worker.frozen_file_template_invalid");
    verifyNoInteractions(templates, secretPayloadProtector);
  }
}
