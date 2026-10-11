package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("租户配置引用规范化")
class TenantConfigReferenceResolverTest {

  @Test
  @DisplayName("递归规范化模板和渠道引用但保留普通字符串")
  void shouldNormalizeKnownReferences_whenNestedInJson() {
    String normalized = TenantConfigReferenceResolver.normalizeConfigReferences(
        "{\"templateCode\":\"MY-TEMPLATE\",\"nested\":[{\"output_channel_code\":\"SFTP-OUT\"}],"
            + "\"description\":\"Keep-MixedCase\"}");

    assertThat(normalized)
        .isEqualTo(
            "{\"templateCode\":\"my_template\",\"nested\":[{\"output_channel_code\":\"sftp_out\"}],"
                + "\"description\":\"Keep-MixedCase\"}");
  }

  @Test
  @DisplayName("没有配置引用时不改写 JSON 格式")
  void shouldPreserveJson_whenNoConfigReferenceExists() {
    String json = "{ \"description\" : \"Keep-MixedCase\" }";

    assertThat(TenantConfigReferenceResolver.normalizeConfigReferences(json)).isEqualTo(json);
  }

  @Test
  @DisplayName("非 JSON 的历史参数原样保留")
  void shouldPreserveLegacyText_whenInputIsNotJson() {
    String legacy = "customer_id = :CustomerId";

    assertThat(TenantConfigReferenceResolver.normalizeConfigReferences(legacy)).isEqualTo(legacy);
  }
}
