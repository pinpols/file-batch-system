package io.github.pinpols.batch.console.domain.file.application.contract.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件通道响应投影: 数据库时间戳的时区归一")
class ConsoleFileProjectionMapperTest {

  @Test
  @DisplayName("无时区数据库时间戳按协调世界时解析")
  void shouldTreatOffsetlessTimestampAsUtcWhenProjectingChannel() {
    ConsoleFileChannelResponse response = ConsoleFileProjectionMapper.channel(
        Map.of("id", 1L, "created_at", "2026-08-13 04:48:33.838547"));

    assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-08-13T04:48:33.838547Z"));
  }

  @Test
  @DisplayName("通道配置响应递归脱敏嵌套凭据")
  void shouldRedactNestedChannelCredentials() {
    ConsoleFileChannelResponse response = ConsoleFileProjectionMapper.channel(Map.of(
        "config_json",
        "{\"host\":\"sftp.example\",\"auth\":{\"password\":\"plain-pass\","
            + "\"apiKey\":\"plain-key\"}}"));

    assertThat(response.configJson())
        .isEqualTo("{\"host\":\"sftp.example\",\"auth\":{\"password\":\"****\","
            + "\"apiKey\":\"****\"}}");
  }

  @Test
  @DisplayName("无法解析的历史通道配置不通过响应原样泄露")
  void shouldOmitMalformedLegacyChannelConfig() {
    ConsoleFileChannelResponse response =
        ConsoleFileProjectionMapper.channel(Map.of("config_json", "legacy-secret=plain-value"));

    assertThat(response.configJson()).isNull();
  }

  @Test
  @DisplayName("通道端点仅脱敏 URI 凭据并保留普通目录路径")
  void shouldRedactCredentialsFromEndpointUri() {
    ConsoleFileChannelResponse response = ConsoleFileProjectionMapper.channel(Map.of(
        "target_endpoint", "sftp://user:pass@example.test:22/inbox?token=secret&mode=passive"));
    ConsoleFileChannelResponse localPath =
        ConsoleFileProjectionMapper.channel(Map.of("target_endpoint", "/srv/batch/outbox"));

    assertThat(response.targetEndpoint())
        .isEqualTo("sftp://****@example.test:22/inbox?token=****&mode=passive");
    assertThat(localPath.targetEndpoint()).isEqualTo("/srv/batch/outbox");
  }
}
