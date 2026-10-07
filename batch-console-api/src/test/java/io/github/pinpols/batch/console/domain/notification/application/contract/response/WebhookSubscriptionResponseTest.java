package io.github.pinpols.batch.console.domain.notification.application.contract.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("订阅响应输出: 敏感回调密钥不得随序列化结果外泄")
class WebhookSubscriptionResponseTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @DisplayName("订阅信息序列化后包含订阅名称, 但不包含回调密钥")
  void shouldNotExposeCallbackSecret_whenSubscriptionSerialized() throws Exception {
    WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity();
    entity.setName("billing-events");
    entity.setSecret("do-not-return");

    String json = mapper.writeValueAsString(WebhookSubscriptionResponse.from(entity));

    assertThat(json).contains("billing-events").doesNotContain("do-not-return", "secret");
  }
}
