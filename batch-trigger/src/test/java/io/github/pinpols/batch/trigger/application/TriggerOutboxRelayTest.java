package io.github.pinpols.batch.trigger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.persistence.entity.TriggerOutboxEventEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import io.github.pinpols.batch.trigger.mapper.TriggerOutboxEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** S3 Stage 2: TriggerOutboxRelay 单测,覆盖空批 / 成功 / 失败 / 反序列化错误 / 已被抢占 5 类路径 + 退避函数。 */
// LENIENT 保留:setUp() 预置了 resetStalePublishing / countByStatuses / countStalePublishing /
// executeWithLock 的共享 stub,但 backoffSeconds 等纯静态用例完全不会触达这些 mock,
// 严格模式会误报 UnnecessaryStubbing。
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TriggerOutboxRelayTest {

  @Mock private TriggerOutboxEventMapper mapper;
  @Mock private TriggerEventPublisher publisher;
  @Mock private LockingTaskExecutor lockingTaskExecutor;

  private TriggerOutboxRelay relay;
  private TriggerOutboxRelayProperties relayProperties;
  private ThreadPoolTaskScheduler scheduler;

  @BeforeEach
  void setUp() throws Throwable {
    relayProperties = new TriggerOutboxRelayProperties();
    scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("trigger-outbox-relay-test-");
    scheduler.initialize();
    relay =
        new TriggerOutboxRelay(
            mapper,
            publisher,
            lockingTaskExecutor,
            new SimpleMeterRegistry(),
            relayProperties,
            scheduler);
    when(mapper.resetStalePublishing(anyString(), anyString(), anyString(), anyLong()))
        .thenReturn(0);
    when(mapper.countByStatuses(any())).thenReturn(0L);
    when(mapper.countStalePublishing(anyString(), anyLong())).thenReturn(0L);
    // executeWithLock(Task,LockConfiguration) 返回 void → 必须用 doAnswer 而非 when
    doAnswer(
            inv -> {
              LockingTaskExecutor.Task task = inv.getArgument(0);
              task.call();
              return null;
            })
        .when(lockingTaskExecutor)
        .executeWithLock(any(LockingTaskExecutor.Task.class), any());
  }

  @AfterEach
  void tearDown() {
    if (scheduler != null) {
      scheduler.shutdown();
    }
  }

  @Test
  void poll_emptyBatch_doesNothing() throws Throwable {
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString())).thenReturn(List.of());

    relay.poll();

    verify(mapper).resetStalePublishing(anyString(), anyString(), anyString(), anyLong());
    verify(publisher, never()).publish(any(), any(), any(), any());
    verify(mapper, never()).markPublishing(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  void poll_afterContextClosed_skipsLockAndMapper() throws Throwable {
    relay.stopOnContextClosed(new ContextClosedEvent(new StaticApplicationContext()));

    relay.poll();

    verify(lockingTaskExecutor, never())
        .executeWithLock(any(LockingTaskExecutor.Task.class), any());
    verify(mapper, never()).resetStalePublishing(anyString(), anyString(), anyString(), anyLong());
  }

  @Test
  void poll_redisStoppingDuringShutdown_isNotBusinessError() throws Throwable {
    doAnswer(
            inv -> {
              relay.stopOnContextClosed(new ContextClosedEvent(new StaticApplicationContext()));
              throw new IllegalStateException("LettuceConnectionFactory is STOPPING");
            })
        .when(lockingTaskExecutor)
        .executeWithLock(any(LockingTaskExecutor.Task.class), any());

    relay.poll();

    verify(mapper, never()).resetStalePublishing(anyString(), anyString(), anyString(), anyLong());
  }

  @Test
  void poll_resetsStalePublishingBeforeSelectingPending() {
    when(mapper.resetStalePublishing(anyString(), anyString(), anyString(), anyLong()))
        .thenReturn(2);
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString())).thenReturn(List.of());

    relay.poll();

    verify(mapper)
        .resetStalePublishing(
            eq(OutboxPublishStatus.PUBLISHING.code()),
            eq(OutboxPublishStatus.FAILED.code()),
            eq("stale PUBLISHING reset by TriggerOutboxRelay"),
            anyLong());
  }

  @Test
  void poll_successPath_marksPublished() {
    TriggerOutboxEventEntity event = buildPendingEvent(101L, validEnvelopePayload());
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));
    when(mapper.markPublishing(eq(101L), anyString(), anyString(), anyString())).thenReturn(1);
    when(publisher.publish(any(), any(), any(), any()))
        .thenReturn(TriggerEventPublisher.PublishResult.ok());

    relay.poll();

    verify(publisher)
        .publish(
            eq("batch.trigger.launch.v1"),
            eq("tenant-a:req-1"),
            any(LaunchEnvelope.class),
            eq("trace-1"));
    verify(mapper).markPublished(101L, OutboxPublishStatus.PUBLISHED.code());
    verify(mapper, never()).markFailed(anyLong(), anyString(), anyString(), any());
  }

  @Test
  void poll_publisherFailure_marksFailedWithBackoff() {
    TriggerOutboxEventEntity event = buildPendingEvent(102L, validEnvelopePayload());
    event.setPublishAttempt(2);
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));
    when(mapper.markPublishing(eq(102L), anyString(), anyString(), anyString())).thenReturn(1);
    when(publisher.publish(any(), any(), any(), any()))
        .thenReturn(TriggerEventPublisher.PublishResult.fail("kafka broker not reachable"));

    relay.poll();

    verify(mapper)
        .markFailed(
            eq(102L),
            eq(OutboxPublishStatus.FAILED.code()),
            eq("kafka broker not reachable"),
            any(Instant.class));
    verify(mapper, never()).markPublished(anyLong(), anyString());
  }

  @Test
  void poll_publisherFailureAtMaxAttempts_marksGiveUp() throws Exception {
    relayProperties.setMaxPublishAttempts(3);
    TriggerOutboxEventEntity event = buildPendingEvent(107L, validEnvelopePayload());
    event.setPublishAttempt(2);
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));
    when(mapper.markPublishing(eq(107L), anyString(), anyString(), anyString())).thenReturn(1);
    when(publisher.publish(any(), any(), any(), any()))
        .thenReturn(TriggerEventPublisher.PublishResult.fail("kafka still down"));

    relay.poll();

    verify(mapper)
        .markFailed(
            eq(107L),
            eq(OutboxPublishStatus.GIVE_UP.code()),
            eq("kafka still down"),
            any(Instant.class));
    verify(mapper, never()).markPublished(anyLong(), anyString());
  }

  @Test
  void poll_payloadDeserializeError_marksGiveUp() {
    TriggerOutboxEventEntity event = buildPendingEvent(103L, "{not-json}");
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));
    when(mapper.markPublishing(eq(103L), anyString(), anyString(), anyString())).thenReturn(1);

    relay.poll();

    verify(mapper)
        .markFailed(
            eq(103L),
            eq(OutboxPublishStatus.GIVE_UP.code()),
            contains("payload deserialize"),
            any(Instant.class));
    verify(publisher, never()).publish(any(), any(), any(), any());
  }

  @Test
  void poll_alreadyClaimedByOtherInstance_skipsSilently() {
    TriggerOutboxEventEntity event = buildPendingEvent(104L, validEnvelopePayload());
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));
    when(mapper.markPublishing(eq(104L), anyString(), anyString(), anyString())).thenReturn(0);

    relay.poll();

    verify(publisher, never()).publish(any(), any(), any(), any());
    verify(mapper, never()).markPublished(anyLong(), anyString());
    verify(mapper, never()).markFailed(anyLong(), anyString(), anyString(), any());
  }

  @Test
  void poll_singleItemException_doesNotBlockRestOfBatch() {
    TriggerOutboxEventEntity bad = buildPendingEvent(105L, validEnvelopePayload());
    TriggerOutboxEventEntity good = buildPendingEvent(106L, validEnvelopePayload());
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(bad, good));
    // bad: markPublishing 抛异常模拟 DB 偶发问题
    when(mapper.markPublishing(eq(105L), anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("db transient error"));
    when(mapper.markPublishing(eq(106L), anyString(), anyString(), anyString())).thenReturn(1);
    when(publisher.publish(any(), any(), any(), any()))
        .thenReturn(TriggerEventPublisher.PublishResult.ok());

    relay.poll();

    verify(mapper).markPublished(106L, OutboxPublishStatus.PUBLISHED.code());
    verify(publisher, times(1)).publish(any(), any(), any(), any());
  }

  @Test
  void backoffSeconds_followsExponentialWithCap() {
    assertThat(TriggerOutboxRelay.backoffSeconds(0)).isEqualTo(1);
    assertThat(TriggerOutboxRelay.backoffSeconds(1)).isEqualTo(2);
    assertThat(TriggerOutboxRelay.backoffSeconds(2)).isEqualTo(4);
    assertThat(TriggerOutboxRelay.backoffSeconds(3)).isEqualTo(8);
    assertThat(TriggerOutboxRelay.backoffSeconds(4)).isEqualTo(16);
    assertThat(TriggerOutboxRelay.backoffSeconds(5)).isEqualTo(32);
    // 2^6 = 64, capped to MAX 60
    assertThat(TriggerOutboxRelay.backoffSeconds(6)).isEqualTo(60);
    assertThat(TriggerOutboxRelay.backoffSeconds(10)).isEqualTo(60);
  }

  // ---------- helpers ----------

  private static TriggerOutboxEventEntity buildPendingEvent(Long id, String payload) {
    TriggerOutboxEventEntity event = new TriggerOutboxEventEntity();
    event.setId(id);
    event.setTenantId("tenant-a");
    event.setRequestId("req-1");
    event.setTopic("batch.trigger.launch.v1");
    event.setPayload(payload);
    event.setPublishStatus(OutboxPublishStatus.NEW.code());
    event.setPublishAttempt(0);
    event.setTraceId("trace-1");
    event.setNextPublishAt(BatchDateTimeSupport.utcNow());
    event.setCreatedAt(BatchDateTimeSupport.utcNow());
    event.setUpdatedAt(BatchDateTimeSupport.utcNow());
    return event;
  }

  private static String validEnvelopePayload() {
    LaunchRequest request =
        new LaunchRequest(
            "tenant-a",
            "test-job",
            LocalDate.parse("2026-04-30"),
            TriggerType.MANUAL,
            "req-1",
            "trace-1",
            Map.of());
    return JsonUtils.toJson(
        LaunchEnvelope.of(request, "tenant-a:req-1", BatchDateTimeSupport.utcNow()));
  }
}
