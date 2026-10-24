package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.dto.FrozenFileTemplateSnapshot;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileTemplateRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import io.github.pinpols.batch.worker.core.support.FrozenFileTemplateResolver;
import io.github.pinpols.batch.worker.imports.config.WorkerImportPayloadProperties;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("导入接收阶段在登记文件前验证并使用冻结模板")
class ReceiveStepFrozenTemplateTest {

  @Mock
  private PlatformFileRecordRepository files;

  @Mock
  private PlatformFileTemplateRepository templates;

  @Mock
  private PlatformPipelineRunRepository pipelines;

  @Mock
  private SecretPayloadProtector protector;

  private ReceiveStep step;

  @BeforeEach
  void setUp() {
    FrozenFileTemplateResolver resolver = new FrozenFileTemplateResolver(templates, protector);
    WorkerImportPayloadProperties properties = new WorkerImportPayloadProperties();
    properties.setMaxPayloadSizeMb(1);
    ObjectMapper mapper = JsonUtils.newDefaultMapper();
    BatchSecurityProperties security = new BatchSecurityProperties();
    step = new ReceiveStep(files, resolver, pipelines, security, mapper, properties);
    step.init();
  }

  @Test
  @DisplayName("导入消费 CLAIM 冻结配置并传递给后续阶段，不查询最新模板")
  void shouldBindProtectedTemplate_whenWholeWorkflowReplayReceivesFile() {
    Map<String, Object> configuration = Map.of(
        "id",
        101,
        "tenant_id",
        "tenant-a",
        "template_code",
        "TPL_A",
        "template_type",
        "IMPORT",
        "version",
        2,
        "file_format_type",
        "DELIMITED");
    FrozenFileTemplateSnapshot snapshot = FrozenFileTemplateSnapshot.capture(configuration);
    String plaintext = JsonUtils.toJson(snapshot);
    when(protector.revealEncrypted("protected-template")).thenReturn(plaintext);
    String payload = JsonUtils.toJson(Map.of(
        "templateCode",
        "TPL_A",
        "content",
        "name\nAlice",
        "operationType",
        "WORKFLOW_RUN",
        "frozenFileTemplateProtected",
        "protected-template"));
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("tenant-a");
    context.setRawPayload(payload);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 12L);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, Map.of());

    var result = step.execute(context);

    assertThat(result.success()).isTrue();
    Object selectedConfiguration = context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    assertThat(selectedConfiguration).isEqualTo(configuration);
    verifyNoInteractions(files, templates, pipelines);
  }

  @Test
  @DisplayName("能力预检只解密冻结导入模板，不创建文件记录或读取最新模板")
  void resolveTemplateConfig_usesFrozenSnapshotWithoutLiveTemplateLookup() {
    Map<String, Object> configuration = Map.of(
        "id",
        101,
        "tenant_id",
        "tenant-a",
        "template_code",
        "TPL_A",
        "template_type",
        "IMPORT",
        "version",
        2,
        "load_target_ref",
        "custom-jdbc",
        "file_format_type",
        "DELIMITED");
    FrozenFileTemplateSnapshot snapshot = FrozenFileTemplateSnapshot.capture(configuration);
    String plaintext = JsonUtils.toJson(snapshot);
    when(protector.revealEncrypted("protected-template")).thenReturn(plaintext);
    String payload = JsonUtils.toJson(Map.of(
        "templateCode",
        "TPL_A",
        "operationType",
        "WORKFLOW_RUN",
        "frozenFileTemplateProtected",
        "protected-template"));
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("tenant-a");
    context.setRawPayload(payload);

    Map<String, Object> actual = step.resolveTemplateConfig(context);

    assertThat(actual).isEqualTo(configuration);
    verifyNoInteractions(files, templates, pipelines);
  }

  @Test
  @DisplayName("整图重放缺少配置快照时在登记文件或绑定流水线前拒绝")
  void shouldRejectMissingSnapshot_beforeCreatingAnyFileRecord() {
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("tenant-a");
    context.setRawPayload("""
        {"templateCode":"TPL_A","operationType":"WORKFLOW_RUN","content":"name"}
        """);

    var result = step.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("FROZEN_FILE_TEMPLATE_INVALID");
    assertThat(result.errorKey()).isEqualTo("error.worker.frozen_file_template_invalid");

    verifyNoInteractions(files, templates, pipelines, protector);
  }
}
