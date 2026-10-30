package io.github.pinpols.batch.console.domain.file.application.contract.response;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件配置响应投影: 具名视图行转换为稳定响应")
class ConsoleFileProjectionMapperTest {

  @Test
  @DisplayName("具名视图行的时间戳字段原样进入响应")
  void shouldPassThroughTimestampWhenProjectingChannelView() {
    ConsoleFileChannelResponse response =
        ConsoleFileProjectionMapper.channelView(FileChannelConfigQueryView.builder()
            .id(1L)
            .createdAt(Instant.parse("2026-08-13T04:48:33.838547Z"))
            .build());

    assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-08-13T04:48:33.838547Z"));
  }

  @Test
  @DisplayName("通道配置响应递归脱敏嵌套凭据")
  void shouldRedactNestedChannelCredentials() {
    ConsoleFileChannelResponse response =
        ConsoleFileProjectionMapper.channelView(FileChannelConfigQueryView.builder()
            .configJson("{\"host\":\"sftp.example\",\"auth\":{\"password\":\"plain-pass\","
                + "\"apiKey\":\"plain-key\"}}")
            .build());

    assertThat(response.configJson())
        .isEqualTo("{\"host\":\"sftp.example\",\"auth\":{\"password\":\"****\","
            + "\"apiKey\":\"****\"}}");
  }

  @Test
  @DisplayName("无法解析的历史通道配置不通过响应原样泄露")
  void shouldOmitMalformedLegacyChannelConfig() {
    ConsoleFileChannelResponse response =
        ConsoleFileProjectionMapper.channelView(FileChannelConfigQueryView.builder()
            .configJson("legacy-secret=plain-value")
            .build());

    assertThat(response.configJson()).isNull();
  }

  @Test
  @DisplayName("通道端点仅脱敏 URI 凭据并保留普通目录路径")
  void shouldRedactCredentialsFromEndpointUri() {
    ConsoleFileChannelResponse response =
        ConsoleFileProjectionMapper.channelView(FileChannelConfigQueryView.builder()
            .targetEndpoint("sftp://user:pass@example.test:22/inbox?token=secret&mode=passive")
            .build());
    ConsoleFileChannelResponse localPath = ConsoleFileProjectionMapper.channelView(
        FileChannelConfigQueryView.builder().targetEndpoint("/srv/batch/outbox").build());

    assertThat(response.targetEndpoint())
        .isEqualTo("sftp://****@example.test:22/inbox?token=****&mode=passive");
    assertThat(localPath.targetEndpoint()).isEqualTo("/srv/batch/outbox");
  }

  @Test
  @DisplayName("模板具名视图保留身份、JSON文本和时间，空视图返回空")
  void shouldProjectTemplateViewFields_whenValuesArePresent() {
    Instant createdAt = Instant.parse("2026-08-13T04:48:33.838547Z");
    FileTemplateConfigQueryView view = FileTemplateConfigQueryView.builder()
        .id(17L)
        .tenantId("tenant-a")
        .templateCode("orders")
        .headerTemplate(new StringBuilder("{\"header\":true}"))
        .fieldMappings("[{\"name\":\"id\"}]")
        .enabled(true)
        .createdAt(createdAt)
        .build();

    ConsoleFileTemplateResponse response = ConsoleFileProjectionMapper.templateView(view);

    assertThat(response.id()).isEqualTo(17L);
    assertThat(response.tenantId()).isEqualTo("tenant-a");
    assertThat(response.templateCode()).isEqualTo("orders");
    assertThat(response.headerTemplate()).isEqualTo("{\"header\":true}");
    assertThat(response.fieldMappings()).isEqualTo("[{\"name\":\"id\"}]");
    assertThat(response.enabled()).isTrue();
    assertThat(response.createdAt()).isEqualTo(createdAt);
    assertThat(ConsoleFileProjectionMapper.templateView(null)).isNull();
    assertThat(ConsoleFileProjectionMapper.channelView(null)).isNull();
  }
}
