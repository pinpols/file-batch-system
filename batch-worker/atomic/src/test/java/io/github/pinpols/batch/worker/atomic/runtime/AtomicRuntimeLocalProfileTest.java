package io.github.pinpols.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

@DisplayName("Atomic本地配置覆盖后仍提供实际运行状态的只读端点")
class AtomicRuntimeLocalProfileTest {
  @Test
  @DisplayName("本地覆盖保留运行状态端点且不扩大到日志写配置端点")
  void shouldExposeRuntimeEndpointAfterLocalProfileOverridesBase() {
    YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
    loader.setResources(
        new ClassPathResource("application.yml"), new ClassPathResource("application-local.yml"));
    Properties properties = loader.getObject();
    assertThat(properties).isNotNull();
    String include = properties.getProperty("management.endpoints.web.exposure.include");
    assertThat(Arrays.asList(include.split(",")))
        .contains("health", "info", "prometheus", "atomicruntime")
        .doesNotContain("loggers");
  }
}
