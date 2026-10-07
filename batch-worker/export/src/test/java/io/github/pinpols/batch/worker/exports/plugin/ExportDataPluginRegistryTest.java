package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("导出数据插件注册中心单测:标识解析,大小写归一与重复注册的失败语义")
class ExportDataPluginRegistryTest {

  @Test
  @DisplayName("按标识精确解析到已注册的插件实例")
  void shouldResolvePluginById() {
    ExportDataPlugin plugin = stubPlugin("settlement");
    ExportDataPluginRegistry registry = new ExportDataPluginRegistry(List.of(plugin));

    assertThat(registry.require("settlement")).isSameAs(plugin);
  }

  @Test
  @DisplayName("标识大小写不敏感,大写输入同样命中同一插件")
  void shouldNormalizeIdToLowerCase() {
    ExportDataPlugin plugin = stubPlugin("settlement");
    ExportDataPluginRegistry registry = new ExportDataPluginRegistry(List.of(plugin));

    assertThat(registry.require("SETTLEMENT")).isSameAs(plugin);
  }

  @Test
  @DisplayName("未提供导出数据引用时快速失败,并提示该引用为必填")
  void shouldThrowWhenIdIsNull() {
    ExportDataPluginRegistry registry = new ExportDataPluginRegistry(List.of());

    assertThatThrownBy(() -> registry.require(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("export_data_ref is required");
  }

  @Test
  @DisplayName("导出数据引用为空白字符串时快速失败,并提示必填")
  void shouldThrowWhenIdIsBlank() {
    ExportDataPluginRegistry registry = new ExportDataPluginRegistry(List.of());

    assertThatThrownBy(() -> registry.require("  "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("export_data_ref is required");
  }

  @Test
  @DisplayName("引用未注册时快速失败,并在消息中回显该标识")
  void shouldThrowWhenPluginNotFound() {
    ExportDataPluginRegistry registry = new ExportDataPluginRegistry(List.of());

    assertThatThrownBy(() -> registry.require("no_such_plugin"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no_such_plugin");
  }

  @Test
  @DisplayName("同一标识注册两个插件时,注册阶段即失败并提示重复")
  void shouldThrowOnDuplicatePluginId() {
    ExportDataPlugin p1 = stubPlugin("settlement");
    ExportDataPlugin p2 = stubPlugin("settlement");

    assertThatThrownBy(() -> new ExportDataPluginRegistry(List.of(p1, p2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  @DisplayName("多个不同标识的插件可同时注册,并各自解析到对应实例")
  void shouldSupportMultipleDistinctPlugins() {
    ExportDataPlugin p1 = stubPlugin("settlement");
    ExportDataPlugin p2 = stubPlugin("jdbc_mapped_export");
    ExportDataPluginRegistry registry = new ExportDataPluginRegistry(List.of(p1, p2));

    assertThat(registry.require("settlement")).isSameAs(p1);
    assertThat(registry.require("jdbc_mapped_export")).isSameAs(p2);
  }

  // --- helpers ---

  private static ExportDataPlugin stubPlugin(String id) {
    ExportDataPlugin plugin = mock(ExportDataPlugin.class);
    when(plugin.id()).thenReturn(id);
    return plugin;
  }
}
