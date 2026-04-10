package com.example.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.batch.console.repository.ConsoleWebhookDeliveryLogRepository;
import java.lang.reflect.Method;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class WebhookDispatcherTest {

    private ConsoleWebhookService webhookService;
    private ConsoleWebhookDeliveryLogRepository deliveryLogRepository;
    private RestClient.Builder restClientBuilder;
    private WebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        webhookService = mock(ConsoleWebhookService.class);
        deliveryLogRepository = mock(ConsoleWebhookDeliveryLogRepository.class);
        restClientBuilder = mock(RestClient.Builder.class);
        dispatcher = new WebhookDispatcher(webhookService, deliveryLogRepository, restClientBuilder);
    }

    @Test
    void shouldNotDispatchWhenNoSubscriptions() {
        when(webhookService.findEnabledSubscriptions("tenant-a")).thenReturn(Collections.emptyList());

        dispatcher.dispatchAsync("tenant-a", "JOB_SUCCESS", "stream-1", "cursor-1", "data", null);

        // Give the async executor a moment, then verify no delivery log was written
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        verifyNoInteractions(deliveryLogRepository);
    }

    @Test
    void shouldMatchWildcardEventType() throws Exception {
        Method matches = WebhookDispatcher.class.getDeclaredMethod("matches", String.class, String.class);
        matches.setAccessible(true);

        boolean result = (boolean) matches.invoke(dispatcher, "*", "JOB_SUCCESS");

        assertThat(result).isTrue();
    }

    @Test
    void shouldMatchSpecificEventType() throws Exception {
        Method matches = WebhookDispatcher.class.getDeclaredMethod("matches", String.class, String.class);
        matches.setAccessible(true);

        boolean result = (boolean) matches.invoke(dispatcher, "JOB_SUCCESS,JOB_FAILED", "JOB_SUCCESS");

        assertThat(result).isTrue();
    }

    @Test
    void shouldNotMatchUnrelatedEventType() throws Exception {
        Method matches = WebhookDispatcher.class.getDeclaredMethod("matches", String.class, String.class);
        matches.setAccessible(true);

        boolean result = (boolean) matches.invoke(dispatcher, "JOB_SUCCESS", "WORKFLOW_FAILED");

        assertThat(result).isFalse();
    }

    @Test
    void shouldNormalizeEventTypeToUpperCase() throws Exception {
        Method normalizeEventType = WebhookDispatcher.class.getDeclaredMethod("normalizeEventType", String.class);
        normalizeEventType.setAccessible(true);

        String result = (String) normalizeEventType.invoke(dispatcher, "job_success");

        assertThat(result).isEqualTo("JOB_SUCCESS");
    }

    @Test
    void shouldNormalizeNullEventTypeToUnknown() throws Exception {
        Method normalizeEventType = WebhookDispatcher.class.getDeclaredMethod("normalizeEventType", String.class);
        normalizeEventType.setAccessible(true);

        String result = (String) normalizeEventType.invoke(dispatcher, (String) null);

        assertThat(result).isEqualTo("UNKNOWN");
    }

    @Test
    void shouldSignPayloadWithHmacSha256() throws Exception {
        Method sign = WebhookDispatcher.class.getDeclaredMethod("sign", String.class, String.class);
        sign.setAccessible(true);

        String result = (String) sign.invoke(dispatcher, "{\"event\":\"test\"}", "my-secret");

        assertThat(result).startsWith("sha256=");
        assertThat(result).hasSize("sha256=".length() + 64); // sha256 hex = 64 chars
    }
}
