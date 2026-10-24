package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.worker.exports.config.ExportWorkerConfiguration;
import io.github.pinpols.batch.worker.exports.config.JdbcMappedExportSecurityProperties;
import io.github.pinpols.batch.worker.exports.config.SqlTemplateExportSecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("内置 Export 插件的注册回调重放能力")
class ExportPluginReplaySafetyTest {

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

    jdbcPlugin.onRegistered(null, null, 1, null);
    sqlPlugin.onRegistered(null, null, 1, null);
  }
}
