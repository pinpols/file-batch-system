package com.example.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.batch.console.mapper.ConsoleWebhookDeliveryLogMapper;
import java.lang.reflect.Method;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class WebhookDispatcherTest {

  private ConsoleWebhookService webhookService;
  private ConsoleWebhookDeliveryLogMapper deliveryLogRepository;
  private RestClient.Builder restClientBuilder;
  private WebhookDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    webhookService = mock(ConsoleWebhookService.class);
    deliveryLogRepository = mock(ConsoleWebhookDeliveryLogMapper.class);
    restClientBuilder = mock(RestClient.Builder.class);
    dispatcher = new WebhookDispatcher(webhookService, deliveryLogRepository, restClientBuilder);
  }

  @Test
  void shouldNotDispatchWhenNoSubscriptions() throws InterruptedException {
    // R3-P1-11：原 Thread.sleep(200) flaky；CI 低 CPU 时异步任务可能还没运行就被 verifyNoInteractions
    // 假阳通过。改用 CountDownLatch — stub findEnabledSubscriptions 在被调用时 countDown，
    // 主线程 await 后才执行 verify，保证 async 任务确实进入了 dispatchOne 入口（且因空列表早 return）。
    java.util.concurrent.CountDownLatch findCalled = new java.util.concurrent.CountDownLatch(1);
    when(webhookService.findEnabledSubscriptions("tenant-a"))
        .thenAnswer(
            inv -> {
              findCalled.countDown();
              return Collections.emptyList();
            });

    dispatcher.dispatchAsync("tenant-a", "JOB_SUCCESS", "stream-1", "cursor-1", "data", null);

    assertThat(findCalled.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    // findEnabledSubscriptions 已返回空列表 → 同一 task 内立刻早返回，
    // 后续 deliveryLogRepository 调用必然不会发生
    verifyNoInteractions(deliveryLogRepository);
  }

  @Test
  void shouldMatchWildcardEventType() throws Exception {
    Method matches =
        WebhookDispatcher.class.getDeclaredMethod("matches", String.class, String.class);
    matches.setAccessible(true);

    boolean result = (boolean) matches.invoke(dispatcher, "*", "JOB_SUCCESS");

    assertThat(result).isTrue();
  }

  @Test
  void shouldMatchSpecificEventType() throws Exception {
    Method matches =
        WebhookDispatcher.class.getDeclaredMethod("matches", String.class, String.class);
    matches.setAccessible(true);

    boolean result = (boolean) matches.invoke(dispatcher, "JOB_SUCCESS,JOB_FAILED", "JOB_SUCCESS");

    assertThat(result).isTrue();
  }

  @Test
  void shouldNotMatchUnrelatedEventType() throws Exception {
    Method matches =
        WebhookDispatcher.class.getDeclaredMethod("matches", String.class, String.class);
    matches.setAccessible(true);

    boolean result = (boolean) matches.invoke(dispatcher, "JOB_SUCCESS", "WORKFLOW_FAILED");

    assertThat(result).isFalse();
  }

  @Test
  void shouldNormalizeEventTypeToUpperCase() throws Exception {
    Method normalizeEventType =
        WebhookDispatcher.class.getDeclaredMethod("normalizeEventType", String.class);
    normalizeEventType.setAccessible(true);

    String result = (String) normalizeEventType.invoke(dispatcher, "job_success");

    assertThat(result).isEqualTo("JOB_SUCCESS");
  }

  @Test
  void shouldNormalizeNullEventTypeToUnknown() throws Exception {
    Method normalizeEventType =
        WebhookDispatcher.class.getDeclaredMethod("normalizeEventType", String.class);
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
