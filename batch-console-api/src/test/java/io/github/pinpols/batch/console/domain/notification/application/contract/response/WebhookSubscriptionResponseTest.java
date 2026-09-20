package io.github.pinpols.batch.console.domain.notification.application.contract.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import org.junit.jupiter.api.Test;

class WebhookSubscriptionResponseTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void doesNotExposeCallbackSecret() throws Exception {
    WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity();
    entity.setName("billing-events");
    entity.setSecret("do-not-return");

    String json = mapper.writeValueAsString(WebhookSubscriptionResponse.from(entity));

    assertThat(json).contains("billing-events").doesNotContain("do-not-return", "secret");
  }
}
