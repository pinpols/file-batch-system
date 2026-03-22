package com.example.batch.worker.imports.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.plugin.ImportLoadPlugin;
import com.example.batch.common.plugin.WorkerPluginIds;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportLoadPluginRegistryTest {

    @Test
    void shouldResolvePluginById() {
        ImportLoadPlugin plugin = stubPlugin("customer_account");
        ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of(plugin));

        assertThat(registry.require("customer_account")).isSameAs(plugin);
    }

    @Test
    void shouldNormalizeIdToLowerCase() {
        ImportLoadPlugin plugin = stubPlugin("customer_account");
        ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of(plugin));

        assertThat(registry.require("CUSTOMER_ACCOUNT")).isSameAs(plugin);
    }

    @Test
    void shouldUseDefaultPluginWhenIdIsNull() {
        ImportLoadPlugin defaultPlugin = stubPlugin(WorkerPluginIds.IMPORT_LOAD_CUSTOMER_ACCOUNT);
        ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of(defaultPlugin));

        assertThat(registry.require(null)).isSameAs(defaultPlugin);
    }

    @Test
    void shouldUseDefaultPluginWhenIdIsBlank() {
        ImportLoadPlugin defaultPlugin = stubPlugin(WorkerPluginIds.IMPORT_LOAD_CUSTOMER_ACCOUNT);
        ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of(defaultPlugin));

        assertThat(registry.require("  ")).isSameAs(defaultPlugin);
    }

    @Test
    void shouldThrowWhenPluginNotFound() {
        ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of());

        assertThatThrownBy(() -> registry.require("unknown_plugin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown_plugin");
    }

    @Test
    void shouldThrowOnDuplicatePluginId() {
        ImportLoadPlugin p1 = stubPlugin("customer_account");
        ImportLoadPlugin p2 = stubPlugin("customer_account");

        assertThatThrownBy(() -> new ImportLoadPluginRegistry(List.of(p1, p2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void shouldSupportMultipleDistinctPlugins() {
        ImportLoadPlugin p1 = stubPlugin("customer_account");
        ImportLoadPlugin p2 = stubPlugin("jdbc_mapped");
        ImportLoadPluginRegistry registry = new ImportLoadPluginRegistry(List.of(p1, p2));

        assertThat(registry.require("customer_account")).isSameAs(p1);
        assertThat(registry.require("jdbc_mapped")).isSameAs(p2);
    }

    // --- helpers ---

    private static ImportLoadPlugin stubPlugin(String id) {
        ImportLoadPlugin plugin = mock(ImportLoadPlugin.class);
        when(plugin.id()).thenReturn(id);
        return plugin;
    }
}
