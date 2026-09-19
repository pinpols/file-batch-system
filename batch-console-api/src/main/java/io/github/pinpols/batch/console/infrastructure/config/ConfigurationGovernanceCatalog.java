package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.web.response.config.ConfigGovernanceItemResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/** 从 CI 生成的唯一登记源加载 Console 配置治理目录。 */
@Component
public class ConfigurationGovernanceCatalog {

  private static final String RESOURCE = "/config-governance-registry.json";

  private final List<ConfigGovernanceItemResponse> items;

  public ConfigurationGovernanceCatalog() {
    try (InputStream input = ConfigurationGovernanceCatalog.class.getResourceAsStream(RESOURCE)) {
      if (EmptyChecks.isNull(input)) {
        throw new IllegalStateException("Missing configuration governance registry: " + RESOURCE);
      }
      String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      RegistryDocument document = JsonUtils.fromJson(json, RegistryDocument.class);
      items = document.configurationProperties().stream()
          .map(item -> new ConfigGovernanceItemResponse(
              item.id(),
              item.className(),
              item.prefix(),
              item.source(),
              item.activation(),
              item.sensitivity(),
              "RESTART_REQUIRED".equals(item.activation())))
          .toList();
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Failed to load configuration governance registry", exception);
    }
  }

  public List<ConfigGovernanceItemResponse> items() {
    return items;
  }

  private record RegistryDocument(List<RegistryItem> configurationProperties) {}

  private record RegistryItem(
      String id,
      String className,
      String prefix,
      String source,
      String activation,
      String sensitivity) {}
}
