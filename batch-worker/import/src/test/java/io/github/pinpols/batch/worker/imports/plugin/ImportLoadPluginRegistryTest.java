package io.github.pinpols.batch.worker.imports.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ImportLoadPlugin;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("导入加载插件注册中心单测:标识解析,缺省兜底与重复注册的失败语义")
class ImportLoadPluginRegistryTest {

  @Test
  @DisplayName("按标识精确解析到已注册的插件实例")
  void shouldResolvePluginById() {
    ImportLoadPlugin plugin = stubPlugin("jdbc_mapped");
    ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of(plugin));

    assertThat(registry.require("jdbc_mapped")).isSameAs(plugin);
  }

  @Test
  @DisplayName("标识大小写不敏感,大写输入命中同一插件")
  void shouldNormalizeIdToLowerCase() {
    ImportLoadPlugin plugin = stubPlugin("jdbc_mapped");
    ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of(plugin));

    assertThat(registry.require("JDBC_MAPPED")).isSameAs(plugin);
  }

  @Test
  @DisplayName("未提供标识时回退到默认插件")
  void shouldUseDefaultPluginWhenIdIsNull() {
    ImportLoadPlugin defaultPlugin = stubPlugin(WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED);
    ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of(defaultPlugin));

    assertThat(registry.require(null)).isSameAs(defaultPlugin);
  }

  @Test
  @DisplayName("标识为空白字符串时回退到默认插件")
  void shouldUseDefaultPluginWhenIdIsBlank() {
    ImportLoadPlugin defaultPlugin = stubPlugin(WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED);
    ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of(defaultPlugin));

    assertThat(registry.require("  ")).isSameAs(defaultPlugin);
  }

  @Test
  @DisplayName("标识未注册时快速失败,并在消息中回显该标识")
  void shouldThrowWhenPluginNotFound() {
    ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of());

    assertThatThrownBy(() -> registry.require("unknown_plugin"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unknown_plugin");
  }

  @Test
  @DisplayName("同一标识注册两个插件时,注册阶段即失败并提示重复")
  void shouldThrowOnDuplicatePluginId() {
    ImportLoadPlugin p1 = stubPlugin("jdbc_mapped");
    ImportLoadPlugin p2 = stubPlugin("jdbc_mapped");

    assertThatThrownBy(() -> new ImportLoadPluginRegistry(List.of(p1, p2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  @DisplayName("多个不同标识的插件可同时注册,并各自解析到对应实例")
  void shouldSupportMultipleDistinctPlugins() {
    ImportLoadPlugin p1 = stubPlugin("jdbc_mapped");
    ImportLoadPlugin p2 = stubPlugin(WorkerPluginIds.EXPORT_DATA_SQL_TEMPLATE);
    ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of(p1, p2));

    assertThat(registry.require("jdbc_mapped")).isSameAs(p1);
    assertThat(registry.require(WorkerPluginIds.EXPORT_DATA_SQL_TEMPLATE)).isSameAs(p2);
  }

  @Test
  @DisplayName("重放能力按实际解析的插件和冻结模板配置计算")
  void shouldResolveReplayCapabilityFromSelectedPluginAndFrozenConfig() {
    Map<String, Object> frozenConfig = Map.of("loadStrategy", "BATCH_UPSERT");
    ImportLoadPlugin plugin = stubPlugin("jdbc_mapped");
    ReplayCapabilityDeclaration declaredCapability = new ReplayCapabilityDeclaration(
        "jdbc_mapped",
        "1.2.3",
        List.of("DATABASE_WRITE"),
        IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT,
        "tenant+business-key",
        "database-unique-constraint",
        ReplayCapabilityDeclaration.CURRENT_CONTRACT_VERSION);
    when(plugin.replayCapability(frozenConfig)).thenReturn(declaredCapability);
    ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of(plugin));

    ReplayCapabilityDeclaration actualCapability =
        registry.replayCapability("JDBC_MAPPED", frozenConfig);

    assertThat(actualCapability).isEqualTo(declaredCapability);
  }

  @Test
  @DisplayName("注册目录不以空配置推断安全能力")
  void shouldUseConservativeStaticDeclarationForCatalog() {
    ImportLoadPlugin plugin = mock(ImportLoadPlugin.class, CALLS_REAL_METHODS);
    when(plugin.id()).thenReturn("jdbc_mapped");
    ReplayCapabilityDeclaration configSpecificDeclaration = new ReplayCapabilityDeclaration(
        "jdbc_mapped",
        "1.2.3",
        List.of("DATABASE_WRITE"),
        IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT,
        "tenant+business-key",
        "database-unique-constraint",
        ReplayCapabilityDeclaration.CURRENT_CONTRACT_VERSION);
    when(plugin.replayCapability(Map.of())).thenReturn(configSpecificDeclaration);
    ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of(plugin));

    ReplayCapabilityDeclaration catalogDeclaration =
        registry.replayCapabilityCatalog().getFirst();

    assertThat(catalogDeclaration).isEqualTo(ReplayCapabilityDeclaration.unknown("jdbc_mapped"));
  }

  // --- helpers ---

  private static ImportLoadPlugin stubPlugin(String id) {
    ImportLoadPlugin plugin = mock(ImportLoadPlugin.class);
    when(plugin.id()).thenReturn(id);
    return plugin;
  }
}
