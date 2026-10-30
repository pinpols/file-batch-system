package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.application.readiness.TriggerInternalProperties;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** 从实际应用 YAML 绑定恢复地址，覆盖统一地址和本地端口覆盖值。 */
@DisplayName("readiness 恢复地址与统一 Trigger 配置对齐")
class TriggerReadinessRecoveryAddressTest {

  @Test
  @DisplayName("集群或自定义 Trigger 地址通过现有环境变量传入恢复客户端")
  void shouldUseSharedTriggerBaseUrl() throws IOException {
    TriggerInternalProperties properties =
        bind(Map.of("BATCH_TRIGGER_BASE_URL", "http://trigger-service:19181"));
    assertThat(properties.getBaseUrl()).isEqualTo("http://trigger-service:19181");
  }

  @Test
  @DisplayName("未指定完整地址时使用本轮环境端口，默认端口与 Trigger 一致")
  void shouldResolveLocalTriggerPort() throws IOException {
    TriggerInternalProperties overridden = bind(Map.of("BATCH_TRIGGER_PORT", "29181"));
    TriggerInternalProperties defaults = bind(Map.of());
    assertThat(overridden.getBaseUrl()).isEqualTo("http://localhost:29181");
    assertThat(defaults.getBaseUrl()).isEqualTo("http://localhost:18081");
  }

  private TriggerInternalProperties bind(Map<String, Object> overrides) throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().addFirst(new MapPropertySource("scenario", overrides));
    var loader = new YamlPropertySourceLoader();
    var application = new ClassPathResource("application.yml");
    for (var source : loader.load("application", application)) {
      environment.getPropertySources().addLast(source);
    }
    return Binder.get(environment)
        .bind("batch.trigger.internal", TriggerInternalProperties.class)
        .get();
  }
}
