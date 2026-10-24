package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.pinpols.batch.common.config.BatchObjectCryptoAutoConfiguration;
import io.github.pinpols.batch.common.dto.FrozenFileTemplateSnapshot;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineDefinitionRepository;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@ExtendWith(MockitoExtension.class)
@DisplayName("冻结模板解析器与平台认证加密组件实际组装")
class FrozenFileTemplateResolverWiringTest {

  @Mock
  private PlatformPipelineDefinitionRepository definitions;

  @Test
  @DisplayName("平台保护的配置经原始任务载荷传给 Worker 后仍读取同一版本")
  void shouldResolvePlatformProtectedSnapshot_whenAutowiredWithCommonCryptoConfiguration() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    String encodedKey = Base64.getEncoder().encodeToString(key);
    ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
        .withConfiguration(AutoConfigurations.of(BatchObjectCryptoAutoConfiguration.class))
        .withPropertyValues(
            "batch.security.kms.default-key-ref=replay-wiring",
            "batch.security.kms.keys.replay-wiring=" + encodedKey)
        .withBean(PlatformPipelineDefinitionRepository.class, () -> definitions)
        .withBean(FrozenFileTemplateResolver.class);

    runner.run(context -> {
      assertThat(context).hasNotFailed().hasSingleBean(FrozenFileTemplateResolver.class);
      SecretPayloadProtector protector = context.getBean(SecretPayloadProtector.class);
      FrozenFileTemplateResolver resolver = context.getBean(FrozenFileTemplateResolver.class);
      Map<String, Object> original = Map.of(
          "id",
          101,
          "tenant_id",
          "tenant-a",
          "template_code",
          "TPL_A",
          "template_type",
          "EXPORT",
          "version",
          2,
          "naming_rule",
          "captured-name");
      FrozenFileTemplateSnapshot snapshot = FrozenFileTemplateSnapshot.capture(original);
      String plaintext = JsonUtils.toJson(snapshot);
      String protectedSnapshot = protector.protect(plaintext);
      String payload = JsonUtils.toJson(Map.of(
          "operationType", "WORKFLOW_RUN", "frozenFileTemplateProtected", protectedSnapshot));
      FileTemplateConfigRequest request =
          new FileTemplateConfigRequest("tenant-a", "TPL_A", "EXPORT", payload, Map.of());

      Map<String, Object> selected = resolver.resolve(request);

      assertThat(selected).isEqualTo(original);
      verifyNoInteractions(definitions);
    });
  }
}
