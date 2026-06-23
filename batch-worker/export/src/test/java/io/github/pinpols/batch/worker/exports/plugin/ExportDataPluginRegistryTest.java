package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExportDataPluginRegistryTest {

  @Test
  void shouldResolvePluginById() {
    ExportDataPlugin plugin = stubPlugin("settlement");
    ExportDataPluginRegistry registry = new ExportDataPluginRegistry(List.of(plugin));

    assertThat(registry.require("settlement")).isSameAs(plugin);
  }

  @Test
  void shouldNormalizeIdToLowerCase() {
    ExportDataPlugin plugin = stubPlugin("settlement");
    ExportDataPluginRegistry registry = new ExportDataPluginRegistry(List.of(plugin));

    assertThat(registry.require("SETTLEMENT")).isSameAs(plugin);
  }

  @Test
  void shouldThrowWhenIdIsNull() {
    ExportDataPluginRegistry registry = new ExportDataPluginRegistry(List.of());

    assertThatThrownBy(() -> registry.require(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("export_data_ref is required");
  }

  @Test
  void shouldThrowWhenIdIsBlank() {
    ExportDataPluginRegistry registry = new ExportDataPluginRegistry(List.of());

    assertThatThrownBy(() -> registry.require("  "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("export_data_ref is required");
  }

  @Test
  void shouldThrowWhenPluginNotFound() {
    ExportDataPluginRegistry registry = new ExportDataPluginRegistry(List.of());

    assertThatThrownBy(() -> registry.require("no_such_plugin"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no_such_plugin");
  }

  @Test
  void shouldThrowOnDuplicatePluginId() {
    ExportDataPlugin p1 = stubPlugin("settlement");
    ExportDataPlugin p2 = stubPlugin("settlement");

    assertThatThrownBy(() -> new ExportDataPluginRegistry(List.of(p1, p2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
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
