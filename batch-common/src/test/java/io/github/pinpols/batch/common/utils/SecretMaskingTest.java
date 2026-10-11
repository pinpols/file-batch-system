package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JSON 凭据脱敏与掩码回传保留")
class SecretMaskingTest {

  @Test
  @DisplayName("递归脱敏对象和数组中的敏感字段")
  void shouldMaskSensitiveKeysRecursively() {
    String masked = SecretMasking.maskSensitiveJson(
        "{\"host\":\"sftp.example\",\"auth\":{\"apiKey\":\"key-1\"},"
            + "\"accounts\":[{\"password\":\"pass-1\"}]}");

    assertThat(masked)
        .isEqualTo("{\"host\":\"sftp.example\",\"auth\":{\"apiKey\":\"****\"},"
            + "\"accounts\":[{\"password\":\"****\"}]}");
  }

  @Test
  @DisplayName("更新回传掩码时保留原凭据且允许删除字段")
  void shouldRestoreMaskedSecretsFromStoredJson() {
    String restored = SecretMasking.preserveMaskedJson(
        "{\"host\":\"new.example\",\"auth\":{\"apiKey\":\"****\","
            + "\"password\":\"\"},\"obsoleteToken\":null}",
        "{\"host\":\"old.example\",\"auth\":{\"apiKey\":\"key-1\","
            + "\"password\":\"pass-1\"},\"obsoleteToken\":\"old-token\"}");

    assertThat(restored)
        .isEqualTo("{\"host\":\"new.example\",\"auth\":{\"apiKey\":\"key-1\","
            + "\"password\":\"\"},\"obsoleteToken\":null}");
    assertThat(SecretMasking.containsMaskedSensitiveValue(restored)).isFalse();
  }

  @Test
  @DisplayName("新配置中的未恢复凭据掩码可被识别")
  void shouldDetectUnrestoredSensitiveMasks() {
    assertThat(SecretMasking.containsMaskedSensitiveValue(
            "{\"nested\":[{\"clientSecret\":\"****\"}]}"))
        .isTrue();
    assertThat(SecretMasking.containsMaskedSensitiveValue("{\"label\":\"****\"}"))
        .isFalse();
  }

  @Test
  @DisplayName("跨租户复制时递归移除凭据字段")
  void shouldRemoveSensitiveKeysFromCopiedJson() {
    assertThat(SecretMasking.removeSensitiveJsonFields(
            "{\"host\":\"target\",\"auth\":{\"apiKey\":\"source-key\","
                + "\"user\":\"service\"},\"items\":[{\"password\":\"source-pass\","
                + "\"name\":\"x\"}]}"))
        .isEqualTo("{\"host\":\"target\",\"auth\":{\"user\":\"service\"},"
            + "\"items\":[{\"name\":\"x\"}]}");
  }

  @Test
  @DisplayName("端点响应隐藏 userinfo 和敏感查询参数")
  void shouldMaskCredentialsInEndpointUri() {
    assertThat(SecretMasking.maskEndpoint(
            "sftp://user:pass@example.test:22/inbox?token=secret&mode=passive"))
        .isEqualTo("sftp://****@example.test:22/inbox?token=****&mode=passive");
  }

  @Test
  @DisplayName("更新端点回传掩码时恢复原 userinfo 和查询凭据")
  void shouldRestoreCredentialsInMaskedEndpointUri() {
    assertThat(SecretMasking.preserveMaskedEndpoint(
            "sftp://****@example.test:22/outbox?token=****&mode=active",
            "sftp://user:pass@example.test:22/inbox?token=secret&mode=passive"))
        .isEqualTo("sftp://user:pass@example.test:22/outbox?token=secret&mode=active");
  }

  @Test
  @DisplayName("额外敏感键可用于特定配置契约")
  void shouldMaskAdditionalSensitiveJsonKeys() {
    String masked = SecretMasking.maskSensitiveJson(
        "{\"url\":\"https://hook.example/secret\",\"label\":\"ops\"}", java.util.Set.of("url"));

    assertThat(masked).isEqualTo("{\"url\":\"****\",\"label\":\"ops\"}");
  }
}
