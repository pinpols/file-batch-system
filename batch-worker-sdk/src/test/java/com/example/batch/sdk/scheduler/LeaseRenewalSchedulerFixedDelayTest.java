package com.example.batch.sdk.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.dispatcher.TaskDispatcher;
import com.example.batch.sdk.internal.PlatformHttpClient;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * TOP #6:LeaseRenewalScheduler 必须用 fixed-delay,与 HeartbeatScheduler 对齐。
 *
 * <p>fixed-rate 在 tick 卡顿(HTTP 5xx 重试 ~3s)后会立刻追发下一轮,内存爆 / orchestrator 雪崩。 见
 * docs/analysis/2026-06-02-sdk-code-deep-review.md §3。
 */
class LeaseRenewalSchedulerFixedDelayTest {

  private static BatchPlatformClientConfig cfg() {
    return BatchPlatformClientConfig.builder()
        .baseUrl("http://x")
        .tenantId("tx")
        .workerCode("w-1")
        .kafkaBootstrap("k:9092")
        .kafkaTopicPattern("t.*")
        .kafkaGroupId("g")
        .leaseRenewInterval(Duration.ofSeconds(15))
        .build();
  }

  @Test
  @DisplayName("start() 必须调 scheduleWithFixedDelay,严禁 scheduleAtFixedRate(防 tick 卡顿追赶式积压)")
  void shouldUseFixedDelay_notFixedRate_onStart() {
    // arrange
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
    LeaseRenewalScheduler s = new LeaseRenewalScheduler(cfg(), http, dispatcher, exec);

    // act
    s.start();

    // assert — 调度走的是 fixed-delay,不是 fixed-rate
    ArgumentCaptor<Long> initialDelay = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<Long> period = ArgumentCaptor.forClass(Long.class);
    verify(exec)
        .scheduleWithFixedDelay(
            any(Runnable.class),
            initialDelay.capture(),
            period.capture(),
            eq(TimeUnit.MILLISECONDS));
    verify(exec, never()).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any());
    assertThat(initialDelay.getValue()).isEqualTo(15_000L);
    assertThat(period.getValue()).isEqualTo(15_000L);
  }
}
