package io.github.pinpols.batch.worker.dispatchs.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

@DisplayName("分发渠道健康探针的运行配置")
class DispatchChannelHealthConfigurationTest {

  @Test
  @DisplayName("应用 YAML 保留六类恢复探针，API 故障不会永久阻塞派发")
  void shouldPreserveRecoveryProbeTypes_whenApplicationYamlIsLoaded() throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    new YamlPropertySourceLoader()
        .load("dispatch-application", new ClassPathResource("application.yml"))
        .forEach(source -> environment.getPropertySources().addFirst(source));
    assertThat(environment.getProperty("spring.application.name"))
        .isEqualTo("batch-worker-dispatch");
    DispatchChannelHealthProperties effective = Binder.get(environment)
        .bind("batch.worker.dispatch.health", DispatchChannelHealthProperties.class)
        .orElseThrow(() -> new AssertionError("dispatch health configuration is missing"));
    assertThat(effective.isEnabled()).isTrue();
    assertThat(effective.getProbeChannelTypes())
        .containsExactly("NAS", "OSS", "SFTP", "EMAIL", "API", "API_PUSH");
  }
}
