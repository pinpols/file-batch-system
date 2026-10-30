package io.github.pinpols.batch.trigger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.persistence.entity.TriggerOutboxEventEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import io.github.pinpols.batch.trigger.domain.param.TriggerOutboxEventMarkFailedParam;
import io.github.pinpols.batch.trigger.domain.param.TriggerOutboxEventMarkPublishingBatchParam;
import io.github.pinpols.batch.trigger.mapper.TriggerOutboxEventMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** S3 Stage 2: TriggerOutboxRelay 单测,覆盖空批 / 成功 / 失败 / 反序列化错误 / 已被抢占 5 类路径 + 退避函数。 */
// LENIENT 保留:setUp() 预置了 resetStalePublishing / countByStatuses / countStalePublishing /
// executeWithLock 的共享 stub,但 backoffSeconds 等纯静态用例完全不会触达这些 mock,
// 严格模式会误报 UnnecessaryStubbing。
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trigger outbox 中继轮询:批领取预算、投递成败回写、停机跳过与退避重试语义")
class TriggerOutboxRelayTest {

  @Mock
  private TriggerOutboxEventMapper mapper;

  @Mock
  private TriggerEventPublisher publisher;

  @Mock
  private LockingTaskExecutor lockingTaskExecutor;

  @Mock
  private TriggerLaunchLagMonitor lagMonitor;

  private TriggerOutboxRelay relay;
  private TriggerOutboxRelayProperties relayProperties;
  private ThreadPoolTaskScheduler scheduler;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() throws Throwable {
    relayProperties = new TriggerOutboxRelayProperties();
    scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("trigger-outbox-relay-test-");
    scheduler.initialize();
    meterRegistry = new SimpleMeterRegistry();
    relay = new TriggerOutboxRelay(
        mapper,
        publisher,
        lockingTaskExecutor,
        meterRegistry,
        relayProperties,
        lagMonitor,
        scheduler);
    when(lagMonitor.current()).thenReturn(new TriggerLaunchLagMonitor.LagSnapshot(0L, 1L));
    when(mapper.resetStalePublishing(anyString(), anyString(), anyString(), anyLong()))
        .thenReturn(0);
    when(mapper.countByStatuses(any())).thenReturn(0L);
    when(mapper.countStalePublishing(anyString(), anyLong())).thenReturn(0L);
    when(mapper.markPublishingBatch(any(TriggerOutboxEventMarkPublishingBatchParam.class)))
        .thenAnswer(invocation -> {
          TriggerOutboxEventMarkPublishingBatchParam claim = invocation.getArgument(0);
          return claim.ids();
        });
    when(mapper.markPublishedBatch(anyList(), anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
    when(mapper.markFailed(ArgumentMatchers.argThat(parameters -> parameters != null
            && parameters.status() != null
            && parameters.lastError() != null
            && parameters.publishingStatus() != null
            && parameters.claimToken() != null)))
        .thenReturn(1);
    // executeWithLock(Task,LockConfiguration) 返回 void → 必须用 doAnswer 而非 when
    doAnswer(inv -> {
          Runnable task = inv.getArgument(0);
          task.run();
          return null;
        })
        .when(lockingTaskExecutor)
        .executeWithLock(any(Runnable.class), any());
  }

  @AfterEach
  void tearDown() {
    if (scheduler != null) {
      scheduler.shutdown();
    }
  }

  @Test
  @DisplayName("本轮无待发事件时只重置超时 PUBLISHING,不发起投递或抢占")
  void poll_emptyBatch_doesNothing() throws Throwable {
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString())).thenReturn(List.of());

    relay.poll();

    verify(mapper).resetStalePublishing(anyString(), anyString(), anyString(), anyLong());
    verify(publisher, never()).publishAsync(any(), any(), any(), any());
    verify(mapper, never())
        .markPublishingBatch(any(TriggerOutboxEventMarkPublishingBatchParam.class));
  }

  @Test
  @DisplayName("每秒发布上限按轮询间隔折算为单轮批量:40 条/秒配 200ms 应只领取 8 条")
  void poll_spreadsPerSecondReleaseBudgetAcrossPollingIntervals() {
    relayProperties.setMaxPublishEventsPerSecond(40);
    relayProperties.setPollIntervalMillis(200);
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString())).thenReturn(List.of());

    relay.poll();

    verify(mapper).selectPending(any(), eq(8), anyString(), anyString());
  }

  @Test
  @DisplayName("分布式锁持有时长须覆盖发布超时并留 10 秒缓冲:120 秒超时对应 130 秒")
  void poll_lockAtMostCoversPublishingTimeoutPlusBuffer() throws Throwable {
    relayProperties.setPublishingTimeoutSeconds(120);
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString())).thenReturn(List.of());
    ArgumentCaptor<LockConfiguration> lockConfig = ArgumentCaptor.forClass(LockConfiguration.class);

    relay.poll();

    verify(lockingTaskExecutor).executeWithLock(any(Runnable.class), lockConfig.capture());
    assertThat(lockConfig.getValue().getLockAtMostFor()).isEqualTo(Duration.ofSeconds(130));
  }

  @Test
  @DisplayName("轮询间隔超过锁上限时,锁最短持有时间收敛到锁上限,避免相邻两轮互斥")
  void poll_lockAtLeastIsClampedWhenPollIntervalExceedsLockAtMost() throws Throwable {
    relayProperties.setPublishingTimeoutSeconds(1);
    relayProperties.setPollIntervalMillis(30_000);
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString())).thenReturn(List.of());
    ArgumentCaptor<LockConfiguration> lockConfig = ArgumentCaptor.forClass(LockConfiguration.class);

    relay.poll();

    verify(lockingTaskExecutor).executeWithLock(any(Runnable.class), lockConfig.capture());
    assertThat(lockConfig.getValue().getLockAtMostFor()).isEqualTo(Duration.ofSeconds(11));
    assertThat(lockConfig.getValue().getLockAtLeastFor()).isEqualTo(Duration.ofSeconds(11));
  }

  @Test
  @DisplayName("收到容器关闭事件后不再取锁与访问 mapper,避免停机中继续打库")
  void poll_afterContextClosed_skipsLockAndMapper() throws Throwable {
    relay.stopOnContextClosed(new ContextClosedEvent(new StaticApplicationContext()));

    relay.poll();

    verify(lockingTaskExecutor, never()).executeWithLock(any(Runnable.class), any());
    verify(mapper, never()).resetStalePublishing(anyString(), anyString(), anyString(), anyLong());
  }

  @Test
  @DisplayName("停机期间 Redis 连接工厂 STOPPING 的异常按跳过处理,不重试也不触碰 mapper")
  void poll_redisStoppingDuringShutdown_isNotBusinessError() throws Throwable {
    doAnswer(inv -> {
          relay.stopOnContextClosed(new ContextClosedEvent(new StaticApplicationContext()));
          throw new IllegalStateException("LettuceConnectionFactory is STOPPING");
        })
        .when(lockingTaskExecutor)
        .executeWithLock(any(Runnable.class), any());

    relay.poll();

    verify(mapper, never()).resetStalePublishing(anyString(), anyString(), anyString(), anyLong());
  }

  @Test
  @DisplayName("取待发事件前先把超时未确认的 PUBLISHING 行重置为 FAILED 并写入原因")
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
  @DisplayName("投递成功的事件按主题与幂等键发出,并批量从 PUBLISHING 置为 PUBLISHED")
  void poll_successPath_marksPublished() {
    TriggerOutboxEventEntity event = buildPendingEvent(101L, validEnvelopePayload());
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));
    when(publisher.publishAsync(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(TriggerEventPublisher.PublishResult.ok()));

    relay.poll();

    verify(publisher)
        .publishAsync(
            eq(BatchTopics.TRIGGER_LAUNCH_V1),
            eq("tenant-a:req-1"),
            any(LaunchEnvelope.class),
            eq("trace-1"));
    ArgumentCaptor<TriggerOutboxEventMarkPublishingBatchParam> claim =
        ArgumentCaptor.forClass(TriggerOutboxEventMarkPublishingBatchParam.class);
    verify(mapper).markPublishingBatch(claim.capture());
    assertThat(claim.getValue().ids()).containsExactly(101L);
    assertThat(claim.getValue().publishingStatus())
        .isEqualTo(OutboxPublishStatus.PUBLISHING.code());
    verify(mapper)
        .markPublishedBatch(
            eq(List.of(101L)),
            eq(OutboxPublishStatus.PUBLISHED.code()),
            eq(OutboxPublishStatus.PUBLISHING.code()),
            eq(claim.getValue().claimToken()));
    verify(mapper, never())
        .markFailed(ArgumentMatchers.argThat(parameters -> parameters != null
            && parameters.status() != null
            && parameters.lastError() != null
            && parameters.publishingStatus() != null));
  }

  @Test
  @DisplayName("broker 明确返回失败时按退避时间写入 FAILED,且不得误标为已发布")
  void poll_publisherFailure_marksFailedWithBackoff() {
    TriggerOutboxEventEntity event = buildPendingEvent(102L, validEnvelopePayload());
    event.setPublishAttempt(2);
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));
    when(publisher.publishAsync(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(
            TriggerEventPublisher.PublishResult.fail("kafka broker not reachable")));

    relay.poll();

    verify(mapper)
        .markFailed(ArgumentMatchers.argThat(parameters -> parameters != null
            && Objects.equals(parameters.id(), 102L)
            && Objects.equals(parameters.status(), OutboxPublishStatus.FAILED.code())
            && Objects.equals(parameters.lastError(), "kafka broker not reachable")
            && parameters.nextPublishAt() != null
            && Objects.equals(
                parameters.publishingStatus(), OutboxPublishStatus.PUBLISHING.code())));
    verify(mapper, never()).markPublishedBatch(anyList(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("投递 future 异常完成时同样回写 FAILED,不让事件滞留在 PUBLISHING")
  void poll_exceptionalPublishFuture_marksFailedWithoutLeavingBatchPublishing() {
    TriggerOutboxEventEntity event = buildPendingEvent(108L, validEnvelopePayload());
    CompletableFuture<TriggerEventPublisher.PublishResult> failed = new CompletableFuture<>();
    failed.completeExceptionally(new IllegalStateException("broker connection reset"));
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));
    when(publisher.publishAsync(any(), any(), any(), any())).thenReturn(failed);

    relay.poll();

    verify(mapper)
        .markFailed(ArgumentMatchers.argThat(parameters -> parameters != null
            && Objects.equals(parameters.id(), 108L)
            && Objects.equals(parameters.status(), OutboxPublishStatus.FAILED.code())
            && (parameters.lastError() != null && parameters.lastError().contains("future failed"))
            && parameters.nextPublishAt() != null
            && Objects.equals(
                parameters.publishingStatus(), OutboxPublishStatus.PUBLISHING.code())));
    verify(mapper, never()).markPublishedBatch(anyList(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("投递 future 长期无响应时按发布超时兜底回写 FAILED,避免批次被永久占用")
  void poll_hungPublishFuture_isBoundedByPublishingTimeout() {
    relayProperties.setPublishingTimeoutSeconds(1);
    TriggerOutboxEventEntity event = buildPendingEvent(109L, validEnvelopePayload());
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));
    when(publisher.publishAsync(any(), any(), any(), any())).thenReturn(new CompletableFuture<>());

    relay.poll();

    verify(mapper)
        .markFailed(ArgumentMatchers.argThat(parameters -> parameters != null
            && Objects.equals(parameters.id(), 109L)
            && Objects.equals(parameters.status(), OutboxPublishStatus.FAILED.code())
            && (parameters.lastError() != null && parameters.lastError().contains("future failed"))
            && parameters.nextPublishAt() != null
            && Objects.equals(
                parameters.publishingStatus(), OutboxPublishStatus.PUBLISHING.code())));
    verify(mapper, never()).markPublishedBatch(anyList(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("重试次数达到上限后失败事件置为 GIVE_UP,不再无限重投")
  void poll_publisherFailureAtMaxAttempts_marksGiveUp() {
    relayProperties.setMaxPublishAttempts(3);
    TriggerOutboxEventEntity event = buildPendingEvent(107L, validEnvelopePayload());
    event.setPublishAttempt(2);
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));
    when(publisher.publishAsync(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(
            TriggerEventPublisher.PublishResult.fail("kafka still down")));

    relay.poll();

    verify(mapper)
        .markFailed(ArgumentMatchers.argThat(parameters -> parameters != null
            && Objects.equals(parameters.id(), 107L)
            && Objects.equals(parameters.status(), OutboxPublishStatus.GIVE_UP.code())
            && Objects.equals(parameters.lastError(), "kafka still down")
            && parameters.nextPublishAt() != null
            && Objects.equals(
                parameters.publishingStatus(), OutboxPublishStatus.PUBLISHING.code())));
    verify(mapper, never()).markPublishedBatch(anyList(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("payload 反序列化失败属不可重试错误,直接置 GIVE_UP 且不发起投递")
  void poll_payloadDeserializeError_marksGiveUp() {
    TriggerOutboxEventEntity event = buildPendingEvent(103L, "{not-json}");
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));

    relay.poll();

    verify(mapper)
        .markFailed(ArgumentMatchers.argThat(parameters -> parameters != null
            && Objects.equals(parameters.id(), 103L)
            && Objects.equals(parameters.status(), OutboxPublishStatus.GIVE_UP.code())
            && (parameters.lastError() != null
                && parameters.lastError().contains("payload deserialize"))
            && parameters.nextPublishAt() != null
            && Objects.equals(
                parameters.publishingStatus(), OutboxPublishStatus.PUBLISHING.code())));
    verify(publisher, never()).publishAsync(any(), any(), any(), any());
  }

  @Test
  @DisplayName("事件已被其它实例抢占时本轮静默跳过,不重复投递也不回写状态")
  void poll_alreadyClaimedByOtherInstance_skipsSilently() {
    TriggerOutboxEventEntity event = buildPendingEvent(104L, validEnvelopePayload());
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));
    when(mapper.markPublishingBatch(any(TriggerOutboxEventMarkPublishingBatchParam.class)))
        .thenReturn(List.of());

    relay.poll();

    verify(publisher, never()).publishAsync(any(), any(), any(), any());
    verify(mapper, never()).markPublishedBatch(anyList(), anyString(), anyString(), anyString());
    verify(mapper, never())
        .markFailed(ArgumentMatchers.argThat(parameters -> parameters != null
            && parameters.status() != null
            && parameters.lastError() != null
            && parameters.publishingStatus() != null));
  }

  @Test
  @DisplayName("单条事件发起投递即抛异常时,同批其它事件仍应正常发布")
  void poll_singleItemException_doesNotBlockRestOfBatch() {
    TriggerOutboxEventEntity bad = buildPendingEvent(105L, validEnvelopePayload());
    TriggerOutboxEventEntity good = buildPendingEvent(106L, validEnvelopePayload());
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(bad, good));
    good.setRequestId("req-2");
    // bad: Kafka send 发起阶段抛异常，不能阻断同批其它事件。
    when(publisher.publishAsync(
            eq(BatchTopics.TRIGGER_LAUNCH_V1), eq("tenant-a:req-1"), any(), any()))
        .thenThrow(new RuntimeException("kafka transient error"));
    when(publisher.publishAsync(
            eq(BatchTopics.TRIGGER_LAUNCH_V1), eq("tenant-a:req-2"), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(TriggerEventPublisher.PublishResult.ok()));

    relay.poll();

    verify(mapper)
        .markPublishedBatch(
            eq(List.of(106L)),
            eq(OutboxPublishStatus.PUBLISHED.code()),
            eq(OutboxPublishStatus.PUBLISHING.code()),
            anyString());
    verify(publisher, times(2)).publishAsync(any(), any(), any(), any());
  }

  @Test
  @DisplayName("投递初始化异常中的凭据不进入日志和 Outbox 失败原因")
  void shouldMaskPublisherInitializationFailureInLogAndLastError() {
    relayProperties.setMaxPublishAttempts(1);
    TriggerOutboxEventEntity event = buildPendingEvent(110L, validEnvelopePayload());
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));
    when(publisher.publishAsync(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("token=probe-secret"));
    Logger logger = (Logger) LoggerFactory.getLogger(TriggerOutboxRelay.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      relay.poll();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    ArgumentCaptor<TriggerOutboxEventMarkFailedParam> failed =
        ArgumentCaptor.forClass(TriggerOutboxEventMarkFailedParam.class);
    verify(mapper).markFailed(failed.capture());
    assertThat(failed.getValue().status()).isEqualTo(OutboxPublishStatus.GIVE_UP.code());
    assertThat(failed.getValue().lastError()).contains("token=****").doesNotContain("probe-secret");
    assertThat(appender.list).isNotEmpty().allSatisfy(logEvent -> {
      assertThat(logEvent.getFormattedMessage()).doesNotContain("probe-secret");
      assertThat(logEvent.getThrowableProxy()).isNull();
    });
  }

  @Test
  @DisplayName("异步投递失败中的凭据不进入重试记录")
  void shouldMaskAsyncPublisherFailureInRetryLastError() {
    relayProperties.setMaxPublishAttempts(2);
    TriggerOutboxEventEntity event = buildPendingEvent(111L, validEnvelopePayload());
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));
    TriggerEventPublisher.PublishResult failedResult =
        TriggerEventPublisher.PublishResult.fail("password=probe-secret");
    when(publisher.publishAsync(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(failedResult));

    relay.poll();

    ArgumentCaptor<TriggerOutboxEventMarkFailedParam> failed =
        ArgumentCaptor.forClass(TriggerOutboxEventMarkFailedParam.class);
    verify(mapper).markFailed(failed.capture());
    assertThat(failed.getValue().status()).isEqualTo(OutboxPublishStatus.FAILED.code());
    assertThat(failed.getValue().lastError()).isEqualTo("password=****");
    assertThat(failed.getValue().nextPublishAt()).isNotNull();
  }

  @Test
  @DisplayName("GIVE_UP 计数只统计成功更新的事件")
  void shouldCountOnlyPersistedGiveUpTransitions() {
    startRelayWithoutScheduledPoll();
    TriggerOutboxEventEntity staleClaim = buildPendingEvent(112L, "{invalid-json");
    TriggerOutboxEventEntity currentClaim = buildPendingEvent(113L, "{invalid-json");
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(staleClaim, currentClaim));
    when(mapper.markFailed(any(TriggerOutboxEventMarkFailedParam.class))).thenReturn(0, 1);

    relay.poll();

    assertThat(meterRegistry.get("batch.trigger.outbox.give_up.total").counter().count())
        .isEqualTo(1.0);
    verify(mapper, times(2)).markFailed(any(TriggerOutboxEventMarkFailedParam.class));
  }

  @Test
  @DisplayName("并发接管导致放弃回写未生效时不记录已放弃错误")
  void shouldNotLogGiveUpWhenClaimWasTakenOver() {
    startRelayWithoutScheduledPoll();
    relayProperties.setMaxPublishAttempts(1);
    TriggerOutboxEventEntity event = buildPendingEvent(114L, validEnvelopePayload());
    when(mapper.selectPending(any(), anyInt(), anyString(), anyString()))
        .thenReturn(List.of(event));
    TriggerEventPublisher.PublishResult failedResult =
        TriggerEventPublisher.PublishResult.fail("broker unavailable");
    when(publisher.publishAsync(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(failedResult));
    when(mapper.markFailed(any(TriggerOutboxEventMarkFailedParam.class))).thenReturn(0);
    Logger logger = (Logger) LoggerFactory.getLogger(TriggerOutboxRelay.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      relay.poll();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    assertThat(meterRegistry.get("batch.trigger.outbox.give_up.total").counter().count())
        .isZero();
    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(message -> message.contains("GIVE_UP after"));
    verify(mapper).markFailed(any(TriggerOutboxEventMarkFailedParam.class));
  }

  @Test
  @DisplayName("失败退避按 2 的幂递增并以 60 秒封顶,避免重试风暴")
  void backoffSeconds_followsExponentialWithCap() {
    assertThat(TriggerOutboxRelay.backoffSeconds(0)).isEqualTo(1);
    assertThat(TriggerOutboxRelay.backoffSeconds(1)).isEqualTo(2);
    assertThat(TriggerOutboxRelay.backoffSeconds(2)).isEqualTo(4);
    assertThat(TriggerOutboxRelay.backoffSeconds(3)).isEqualTo(8);
    assertThat(TriggerOutboxRelay.backoffSeconds(4)).isEqualTo(16);
    assertThat(TriggerOutboxRelay.backoffSeconds(5)).isEqualTo(32);
    // 2 的 6 次方等于 64，计数上限为 MAX 60。
    assertThat(TriggerOutboxRelay.backoffSeconds(6)).isEqualTo(60);
    assertThat(TriggerOutboxRelay.backoffSeconds(10)).isEqualTo(60);
  }

  @Test
  @DisplayName("单调时钟异常返回负时长时跳过延迟样本并递增异常计数")
  void recordLatencySample_negativeDuration_skipsTimerAndCountsAnomaly() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Timer timer = registry.timer("batch.trigger.outbox.publish.latency", "result", "ok");
    Counter anomalies = registry.counter("batch.trigger.outbox.publish.latency.negative_duration");

    TriggerOutboxRelay.recordLatencySample(timer, anomalies, 100L, 37L);

    assertThat(timer.count()).isZero();
    assertThat(anomalies.count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("非负发布时长正常记录且不增加异常计数")
  void recordLatencySample_nonNegativeDuration_recordsTimer() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Timer timer = registry.timer("batch.trigger.outbox.publish.latency", "result", "ok");
    Counter anomalies = registry.counter("batch.trigger.outbox.publish.latency.negative_duration");

    TriggerOutboxRelay.recordLatencySample(timer, anomalies, 100L, 150L);

    assertThat(timer.count()).isEqualTo(1L);
    assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isEqualTo(50.0);
    assertThat(anomalies.count()).isZero();
  }

  // ---------- helpers ----------

  private void startRelayWithoutScheduledPoll() {
    ThreadPoolTaskScheduler controlledScheduler = mock(ThreadPoolTaskScheduler.class);
    relay = new TriggerOutboxRelay(
        mapper,
        publisher,
        lockingTaskExecutor,
        meterRegistry,
        relayProperties,
        lagMonitor,
        controlledScheduler);
    relay.start();
  }

  private static TriggerOutboxEventEntity buildPendingEvent(Long id, String payload) {
    TriggerOutboxEventEntity event = new TriggerOutboxEventEntity();
    event.setId(id);
    event.setTenantId("tenant-a");
    event.setRequestId("req-1");
    event.setTopic(BatchTopics.TRIGGER_LAUNCH_V1);
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
    LaunchRequest request = new LaunchRequest(
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
