package io.github.pinpols.batch.console.domain.observability.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleOpsApplicationService;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleOpsSummaryResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// LENIENT:setUp 共享 stub(tenantGuard / cursorFactory / realtimeEventHub.subscribe)
// 被部分用例不触发,符合 CLAUDE.md §测试约定豁免场景。
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsoleOpsSummaryRealtimeStreamTest {

  @Mock private ConsoleOpsApplicationService opsApplicationService;
  @Mock private ConsoleRealtimeEventHub realtimeEventHub;
  @Mock private ConsoleRealtimeRedisPublisher redisPublisher;
  @Mock private ConsoleRealtimeCursorFactory cursorFactory;
  @Mock private ConsoleTenantGuard tenantGuard;
  private ConsoleOpsSummaryRealtimeStream stream;

  @BeforeEach
  void setUp() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("console-realtime-test-");
    scheduler.initialize();
    stream =
        new ConsoleOpsSummaryRealtimeStream(
            opsApplicationService,
            realtimeEventHub,
            redisPublisher,
            cursorFactory,
            tenantGuard,
            dateTimeSupport(),
            scheduler);
    when(tenantGuard.resolveTenant(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(cursorFactory.nextCursor()).thenReturn("cursor-1");
    when(realtimeEventHub.subscribe(anyString(), anyString(), isNull(), isNull(), isNull()))
        .thenReturn(new SseEmitter());
  }

  private static BatchDateTimeSupport dateTimeSupport() {
    return new BatchDateTimeSupport(
        Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
  }

  @Test
  void shouldSkipInitialSnapshotWhenDisabled() {
    SseEmitter emitter = stream.subscribe("t1", null, false);

    assertThat(emitter).isNotNull();
    verify(realtimeEventHub).subscribe("t1", "ops-summary", null, null, null);
    verify(realtimeEventHub, never()).publish(any());
    verify(opsApplicationService, never()).summary(anyString());
  }

  @Test
  void shouldThrottleRepeatedRefreshRequests() {
    when(opsApplicationService.summary("t1"))
        .thenReturn(new ConsoleOpsSummaryResponse("t1", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11));

    stream.publishRefresh("t1");
    stream.publishRefresh("t1");
    try {
      Thread.sleep(500L);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }

    verify(opsApplicationService).summary("t1");
    verify(realtimeEventHub).publish(any());
    verify(redisPublisher).publish(any());
  }

  @Test
  void shouldForceInitialSnapshotWhenSubscribed() {
    when(opsApplicationService.summary("t1"))
        .thenReturn(new ConsoleOpsSummaryResponse("t1", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11));

    stream.subscribe("t1", null, true);

    verify(opsApplicationService).summary("t1");
    verify(realtimeEventHub).publish(any());
    verify(redisPublisher).publish(any());
  }
}
