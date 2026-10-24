package io.github.pinpols.batch.common.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("冻结文件模板快照的身份、版本与不可变边界")
class FrozenFileTemplateSnapshotTest {

  @Test
  @DisplayName("冻结后修改原配置或嵌套配置不改变快照且输出日志不包含配置")
  void shouldRetainImmutableConfiguration_whenCallerMutatesOriginalValues() {
    Map<String, Object> configuration = configuration();
    List<String> columns = new ArrayList<>(List.of("original-column"));
    configuration.put("columns", columns);
    configuration.put("default_query_sql", "private-query-marker");
    configuration.put("nullable_setting", null);

    FrozenFileTemplateSnapshot snapshot = FrozenFileTemplateSnapshot.capture(configuration);
    columns.add("new-column");
    configuration.put("version", 9);

    assertThat(snapshot.templateVersion()).isEqualTo(2);
    Object capturedColumns = snapshot.configuration().get("columns");
    assertThat(capturedColumns).isEqualTo(List.of("original-column"));
    assertThat(snapshot.configuration()).containsEntry("nullable_setting", null);
    assertThat(snapshot.toString()).doesNotContain("private-query-marker", "default_query_sql");
    assertThatThrownBy(() -> snapshot.configuration().put("version", 10))
        .isInstanceOf(UnsupportedOperationException.class);
    List<?> frozenColumns = (List<?>) capturedColumns;
    assertThatThrownBy(frozenColumns::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("信封身份与配置正文不一致时拒绝")
  void shouldRejectSnapshot_whenEnvelopeIdentityDiffersFromConfiguration() {
    Map<String, Object> configuration = configuration();

    assertThatThrownBy(() -> new FrozenFileTemplateSnapshot(
            1, "tenant-a", "TPL_A", "EXPORT", 999L, 2, configuration))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("不支持的版本与分数形式的配置版本均拒绝")
  void shouldRejectSnapshot_whenSchemaOrDefinitionVersionIsInvalid() {
    Map<String, Object> configuration = configuration();
    assertThatThrownBy(() -> new FrozenFileTemplateSnapshot(
            2, "tenant-a", "TPL_A", "EXPORT", 101L, 2, configuration))
        .isInstanceOf(IllegalArgumentException.class);
    configuration.put("version", "2.5");

    assertThatThrownBy(() -> FrozenFileTemplateSnapshot.capture(configuration))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("原始驱动对象不能被静默序列化为不同配置形状")
  void shouldRejectNonJsonValue_whenDriverObjectWasNotNormalized() {
    Map<String, Object> configuration = configuration();
    configuration.put("query_param_schema", new Object());

    assertThatThrownBy(() -> FrozenFileTemplateSnapshot.capture(configuration))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Map<String, Object> configuration() {
    return new LinkedHashMap<>(Map.of(
        "id",
        101L,
        "tenant_id",
        "tenant-a",
        "template_code",
        "TPL_A",
        "template_type",
        "EXPORT",
        "version",
        2));
  }
}
