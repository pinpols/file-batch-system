package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.FrozenFileTemplateSnapshot;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineDefinitionRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Worker 文件模板解析优先验证 CLAIM 中的冻结配置")
class FrozenFileTemplateResolverTest {

  @Mock
  private PlatformPipelineDefinitionRepository definitions;

  @Mock
  private SecretPayloadProtector protector;

  @InjectMocks
  private FrozenFileTemplateResolver resolver;

  @Test
  @DisplayName("载荷冻结模板优先于上下文旧模板且不读取当前数据库版本")
  void shouldUseClaimSnapshot_whenLiveAndContextConfigurationsHaveChanged() {
    String plaintext = snapshot();
    when(protector.revealEncrypted("protected-template")).thenReturn(plaintext);
    String payload = payload("protected-template");
    Map<String, Object> attributes = Map.of(
        PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("version", 999, "file_format_type", "JSON"));
    FileTemplateConfigRequest request =
        new FileTemplateConfigRequest("tenant-a", "TPL_A", "EXPORT", payload, attributes);

    Map<String, Object> selected = resolver.resolve(request);

    assertThat(selected).containsEntry("version", 2).containsEntry("file_format_type", "DELIMITED");
    verifyNoInteractions(definitions);
  }

  @ParameterizedTest
  @CsvSource({"tenant-b,TPL_A,EXPORT", "tenant-a,TPL_OTHER,EXPORT", "tenant-a,TPL_A,IMPORT"})
  @DisplayName("租户、模板编码或类型不匹配时拒绝且不回退当前配置")
  void shouldRejectFrozenTemplate_whenExpectedIdentityDoesNotMatch(
      String tenantId, String templateCode, String templateType) {
    String plaintext = snapshot();
    when(protector.revealEncrypted("protected-template")).thenReturn(plaintext);
    String payload = payload("protected-template");
    FileTemplateConfigRequest request =
        new FileTemplateConfigRequest(tenantId, templateCode, templateType, payload, Map.of());

    assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(BizException.class);

    verifyNoInteractions(definitions);
  }

  @ParameterizedTest
  @MethodSource("invalidProtectedValues")
  @DisplayName("快照字段存在但为空或类型错误时不能静默回退")
  void shouldRejectFrozenTemplate_whenProtectedFieldIsInvalid(Object invalidValue) {
    String payload = payload(invalidValue);
    FileTemplateConfigRequest request =
        new FileTemplateConfigRequest("tenant-a", "TPL_A", "EXPORT", payload, Map.of());

    assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(BizException.class);

    verifyNoInteractions(definitions, protector);
  }

  @Test
  @DisplayName("整 Workflow 重放缺失快照时不复用未认证的上下文模板")
  void shouldRejectMissingSnapshot_whenWholeWorkflowReplayIsRequested() {
    String payload = "{\"operationType\":\"WORKFLOW_RUN\"}";
    Map<String, Object> attributes =
        Map.of(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("file_format_type", "JSON"));
    FileTemplateConfigRequest request =
        new FileTemplateConfigRequest("tenant-a", "TPL_A", "EXPORT", payload, attributes);

    assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(BizException.class);

    verifyNoInteractions(definitions, protector);
  }

  @Test
  @DisplayName("普通执行可复用已加载模板而不再次读取最新配置")
  void shouldReuseExistingConfiguration_whenTaskIsNotWholeWorkflowReplay() {
    Map<String, Object> attributes =
        Map.of(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("file_format_type", "DELIMITED"));
    FileTemplateConfigRequest request =
        new FileTemplateConfigRequest("tenant-a", "TPL_A", "EXPORT", "{}", attributes);

    Map<String, Object> selected = resolver.resolve(request);

    assertThat(selected).containsEntry("file_format_type", "DELIMITED");
    verifyNoInteractions(definitions, protector);
  }

  @Test
  @DisplayName("普通首次执行沿既有租户、模板编码和类型读取配置")
  void shouldLoadOrdinaryTemplate_whenNoSnapshotOrContextExists() {
    Map<String, Object> latest = Map.of("file_format_type", "JSON");
    when(definitions.loadLatestTemplateConfig("tenant-a", "TPL_A", "EXPORT")).thenReturn(latest);
    FileTemplateConfigRequest request =
        new FileTemplateConfigRequest("tenant-a", "TPL_A", "EXPORT", "{}", Map.of());

    Map<String, Object> selected = resolver.resolve(request);

    assertThat(selected).isEqualTo(latest);
    verifyNoInteractions(protector);
  }

  @Test
  @DisplayName("认证解密失败直接拒绝，不重查当前数据库配置")
  void shouldRejectSnapshot_whenAuthenticationFails() {
    when(protector.revealEncrypted("tampered-template"))
        .thenThrow(new IllegalStateException("authentication failed"));
    String payload = payload("tampered-template");
    FileTemplateConfigRequest request =
        new FileTemplateConfigRequest("tenant-a", "TPL_A", "EXPORT", payload, Map.of());

    assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(BizException.class);

    verifyNoInteractions(definitions);
  }

  @Test
  @DisplayName("解密内容无效时错误不携带配置正文")
  void shouldHideDecryptedValues_whenSnapshotJsonIsInvalid() {
    when(protector.revealEncrypted("protected-template"))
        .thenReturn("{\"query\":\"private-query-marker\"");
    String payload = payload("protected-template");
    FileTemplateConfigRequest request =
        new FileTemplateConfigRequest("tenant-a", "TPL_A", "EXPORT", payload, Map.of());

    assertThatThrownBy(() -> resolver.resolve(request))
        .isInstanceOf(BizException.class)
        .hasMessageNotContaining("private-query-marker");

    verifyNoInteractions(definitions);
  }

  @Test
  @DisplayName("跳过大段内联文件及嵌套同名字段，只选择根级受保护模板")
  void shouldSelectRootSnapshot_whenUnrelatedInlineContentIsLarge() {
    String plaintext = snapshot();
    when(protector.revealEncrypted("protected-template")).thenReturn(plaintext);
    String content = "x".repeat(1024 * 1024);
    Map<String, Object> wirePayload = Map.of(
        "content",
        content,
        "metadata",
        Map.of("frozenFileTemplateProtected", "nested-value"),
        "operationType",
        "WORKFLOW_RUN",
        "frozenFileTemplateProtected",
        "protected-template");
    String payload = JsonUtils.toJson(wirePayload);
    FileTemplateConfigRequest request =
        new FileTemplateConfigRequest("tenant-a", "TPL_A", "EXPORT", payload, Map.of());

    Map<String, Object> selected = resolver.resolve(request);

    assertThat(selected).containsEntry("version", 2);
    verifyNoInteractions(definitions);
  }

  @ParameterizedTest
  @MethodSource("ambiguousPayloads")
  @DisplayName("重复根级快照、重复操作类型或截断的重放载荷拒绝回退")
  void shouldRejectAmbiguousPayload_whenReplayFieldsAreDuplicatedOrTruncated(String payload) {
    FileTemplateConfigRequest request =
        new FileTemplateConfigRequest("tenant-a", "TPL_A", "EXPORT", payload, Map.of());

    assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(BizException.class);

    verifyNoInteractions(definitions, protector);
  }

  private String snapshot() {
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
        "DELIMITED");
    FrozenFileTemplateSnapshot snapshot = FrozenFileTemplateSnapshot.capture(configuration);
    return JsonUtils.toJson(snapshot);
  }

  private String payload(Object protectedValue) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("operationType", "WORKFLOW_RUN");
    payload.put("frozenFileTemplateProtected", protectedValue);
    return JsonUtils.toJson(payload);
  }

  private static Stream<Object> invalidProtectedValues() {
    return Stream.of(null, "", 123, Map.of("ciphertext", "invalid"));
  }

  private static Stream<String> ambiguousPayloads() {
    return Stream.of(
        "{\"frozenFileTemplateProtected\":null,\"frozenFileTemplateProtected\":\"other\"}",
        "{\"operationType\":\"WORKFLOW_RUN\",\"operationType\":\"OTHER\"}",
        "{\"operationType\":null}",
        "{\"operationType\":{\"type\":\"WORKFLOW_RUN\"}}",
        "{\"operationType\":\"WORKFLOW_RUN\",\"content\":",
        "{\"frozenFileTemplateProtected\":\"protected-template\"} {}");
  }
}
