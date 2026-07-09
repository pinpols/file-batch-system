package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NotificationSender 注册表按 channelType 解析")
class NotificationSenderRegistryTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("WECOM 渠道解析到 WeComNotificationSender（规范渠道值,大小写不敏感）")
  void shouldResolveWecomChannelToWeComSender() {
    // arrange
    WeComNotificationSender weComSender = new WeComNotificationSender(objectMapper);
    NotificationSenderRegistry registry = new NotificationSenderRegistry(List.of(weComSender));

    // act / assert：WECOM 是 DDL CHECK/白名单/DictEnum 的规范值，必须能解析到企微 sender。
    assertThat(registry.resolve("WECOM")).isSameAs(weComSender);
    assertThat(registry.resolve("wecom")).isSameAs(weComSender);
  }

  @Test
  @DisplayName("历史误值 WECHAT 不再解析到任何 sender")
  void shouldNotResolveLegacyWechatValue() {
    // arrange
    NotificationSenderRegistry registry =
        new NotificationSenderRegistry(List.of(new WeComNotificationSender(objectMapper)));

    // act / assert
    assertThat(registry.resolve("WECHAT")).isNull();
    assertThat(registry.resolve(null)).isNull();
    assertThat(registry.resolve(" ")).isNull();
  }
}
