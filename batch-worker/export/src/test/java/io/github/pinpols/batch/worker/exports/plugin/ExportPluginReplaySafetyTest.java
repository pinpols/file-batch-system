package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.worker.exports.config.ExportWorkerConfiguration;
import io.github.pinpols.batch.worker.exports.config.JdbcMappedExportSecurityProperties;
import io.github.pinpols.batch.worker.exports.config.SqlTemplateExportSecurityProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("内置 Export 插件的注册回调重放能力")
class ExportPluginReplaySafetyTest {

  @Test
  @DisplayName("JDBC 映射导出目录声明覆盖只读查询和空注册回调")
  void shouldPublishReadOnlyJdbcReplayCapability() {
    GenericJdbcMappedExportDataPlugin plugin = new GenericJdbcMappedExportDataPlugin(
        new DriverManagerDataSource(),
        new ObjectMapper(),
        new JdbcMappedExportSecurityProperties());
    ReplayCapabilityDeclaration declaration = plugin.replayCapabilityDeclaration();
    ExportDataPluginRegistry registry = new ExportDataPluginRegistry(List.of(plugin));

    assertThat(declaration.isFullyDeclared()).isTrue();
    assertThat(declaration.pluginId()).isEqualTo(plugin.id());
    assertThat(declaration.pluginVersion()).isEqualTo("1");
    assertThat(declaration.externalEffectCategories()).containsExactly("BUSINESS_DATABASE_READ");
    assertThat(declaration.idempotencyCapability())
        .isEqualTo(IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC);
    assertThat(declaration.idempotencyKeyScope())
        .isEqualTo("tenantId:batchNo:templateCode:partitionNo:partitionCount");
    assertThat(declaration.recoveryProtocolId()).isEqualTo("read-only-jdbc-query-v1");
    assertThat(plugin.replayCapability(Map.of())).isEqualTo(declaration);
    assertThat(plugin.replayCapability(Map.of("jdbc_mapped_export", Map.of("batchTable", "batch"))))
        .isEqualTo(declaration);
    assertThat(registry.replayCapabilityCatalog()).containsExactly(declaration);
  }

  @Test
  @DisplayName("内置空注册回调显式声明可安全重放")
  void shouldDeclareReplaySafeForBuiltInNoOpRegistrationCallbacks() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    ObjectMapper objectMapper = new ObjectMapper();
    GenericJdbcMappedExportDataPlugin jdbcPlugin = new GenericJdbcMappedExportDataPlugin(
        dataSource, objectMapper, new JdbcMappedExportSecurityProperties());
    SqlTemplateExportDataPlugin sqlPlugin = new SqlTemplateExportDataPlugin(
        dataSource,
        objectMapper,
        new SqlTemplateExportSecurityProperties(),
        new ExportWorkerConfiguration(null, null, null, null, null, null, null, 0, null));

    assertThat(jdbcPlugin.registrationCallbackIdempotencyCapability())
        .isEqualTo(IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC);
    assertThat(sqlPlugin.registrationCallbackIdempotencyCapability())
        .isEqualTo(IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC);
    assertThat(sqlPlugin.replayCapabilityDeclaration())
        .isEqualTo(ReplayCapabilityDeclaration.unknown(sqlPlugin.id()));

    jdbcPlugin.onRegistered(null, null, 1, null);
    sqlPlugin.onRegistered(null, null, 1, null);
  }
}
