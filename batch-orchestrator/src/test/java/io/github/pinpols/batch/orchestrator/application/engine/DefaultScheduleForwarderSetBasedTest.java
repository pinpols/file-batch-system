package io.github.pinpols.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.orchestrator.config.OutboxProperties;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.EventOutboxRetryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.OutboxEventEntity;
import io.github.pinpols.batch.orchestrator.mapper.EventOutboxRetryMapper;
import io.github.pinpols.batch.orchestrator.mapper.OutboxEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * PERF(5.4) 守护 outbox forwarder 的 set-based 三阶段:
 *
 * <ul>
 *   <li>阶段一:按租户分组批量 markPublishingBatch(抢占 CAS RETURNING 胜出集),只发胜出事件;
 *   <li>阶段三:成功/失败/GIVE_UP 各组一条批量 UPDATE,全部保留 publish_status='PUBLISHING' 守卫;
 *   <li>逐条 markPublishing / markPublished / markFailed / markGiveUp 不再被调用。
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultScheduleForwarderSetBasedTest {

  @Mock private OutboxEventMapper outboxEventMapper;
  @Mock private EventOutboxRetryMapper eventOutboxRetryMapper;
  @Mock private OutboxPublisher outboxPublisher;
  @Mock private BatchOrchestratorGovernanceProperties governance;

  private DefaultScheduleForwarder forwarder;
  private final OutboxProperties outboxProperties = new OutboxProperties();

  @BeforeEach
  void setUp() {
    when(governance.outbox()).thenReturn(outboxProperties);
    forwarder =
        new DefaultScheduleForwarder(
            outboxEventMapper,
            eventOutboxRetryMapper,
            outboxPublisher,
            governance,
            new SimpleMeterRegistry());
    forwarder.initMetrics();
  }

  @Test
  @DisplayName("三阶段 set-based:抢占/成功/失败/GIVE_UP 各走批量 SQL,单条 mark* 零调用")
  void advanceUsesSetBasedStatementsPerOutcomeGroup() {
    OutboxEventEntity ok = event("ta", 1L, 0);
    OutboxEventEntity fail = event("ta", 2L, 0);
    // publish_attempt=4 → 本次为第 5 次(=maxRetryAttempts 默认 5)→ GIVE_UP
    OutboxEventEntity exhausted = event("ta", 3L, 4);
    when(outboxEventMapper.selectPending(any())).thenReturn(List.of(ok, fail, exhausted));
    when(outboxEventMapper.markPublishingBatch(
            eq("ta"),
            eq(List.of(1L, 2L, 3L)),
            eq(OutboxPublishStatus.PUBLISHING.code()),
            eq(OutboxPublishStatus.NEW.code()),
            eq(OutboxPublishStatus.FAILED.code())))
        .thenReturn(List.of(1L, 2L, 3L));
    when(outboxPublisher.publish(ok)).thenReturn(CompletableFuture.completedFuture(true));
    when(outboxPublisher.publish(fail)).thenReturn(CompletableFuture.completedFuture(false));
    when(outboxPublisher.publish(exhausted)).thenReturn(CompletableFuture.completedFuture(false));
    when(outboxEventMapper.markPublishedBatch(anyString(), anyList(), anyString(), anyString()))
        .thenReturn(1);
    when(outboxEventMapper.markFailedBatch(anyString(), anyList(), anyString(), any(), anyString()))
        .thenReturn(1);
    when(outboxEventMapper.markGiveUpBatch(anyString(), anyList(), anyString(), anyString()))
        .thenReturn(1);

    ScheduleForwarderResult result = forwarder.advance(null);

    assertThat(result.attemptedEvents()).isEqualTo(3);
    assertThat(result.publishSucceeded()).isEqualTo(1);
    assertThat(result.publishFailed()).isEqualTo(2);
    // 阶段三守卫:批量版必须保留 publish_status='PUBLISHING'(R3-P0-6)
    verify(outboxEventMapper)
        .markPublishedBatch(
            eq("ta"),
            eq(List.of(1L)),
            eq(OutboxPublishStatus.PUBLISHED.code()),
            eq(OutboxPublishStatus.PUBLISHING.code()));
    verify(outboxEventMapper)
        .markFailedBatch(
            eq("ta"),
            eq(List.of(2L)),
            eq(OutboxPublishStatus.FAILED.code()),
            any(),
            eq(OutboxPublishStatus.PUBLISHING.code()));
    verify(outboxEventMapper)
        .markGiveUpBatch(
            eq("ta"),
            eq(List.of(3L)),
            eq(OutboxPublishStatus.GIVE_UP.code()),
            eq(OutboxPublishStatus.PUBLISHING.code()));
    // 单条路径彻底退役
    verify(outboxEventMapper, never())
        .markPublishing(anyString(), anyLong(), anyString(), anyString(), anyString());
    verify(outboxEventMapper, never())
        .markPublished(anyString(), anyLong(), anyString(), anyString());
    verify(outboxEventMapper, never())
        .markFailed(anyString(), anyLong(), anyString(), any(), anyString());
    verify(outboxEventMapper, never()).markGiveUp(anyString(), anyLong(), anyString(), anyString());
    // 失败 + GIVE_UP 各一条 event_outbox_retry 审计
    ArgumentCaptor<EventOutboxRetryEntity> retryCap =
        ArgumentCaptor.forClass(EventOutboxRetryEntity.class);
    verify(eventOutboxRetryMapper, org.mockito.Mockito.times(2)).insert(retryCap.capture());
    assertThat(retryCap.getAllValues())
        .extracting(EventOutboxRetryEntity::getOutboxEventId)
        .containsExactlyInAnyOrder(2L, 3L);
  }

  @Test
  @DisplayName("抢占胜出集是发送边界:未胜出(被并发 forwarder 抢走)的事件不发 Kafka")
  void advanceOnlyPublishesWinnersOfBatchCas() {
    OutboxEventEntity won = event("ta", 1L, 0);
    OutboxEventEntity lost = event("ta", 2L, 0);
    when(outboxEventMapper.selectPending(any())).thenReturn(List.of(won, lost));
    when(outboxEventMapper.markPublishingBatch(
            anyString(), anyList(), anyString(), anyString(), anyString()))
        .thenReturn(List.of(1L));
    when(outboxPublisher.publish(won)).thenReturn(CompletableFuture.completedFuture(true));
    when(outboxEventMapper.markPublishedBatch(anyString(), anyList(), anyString(), anyString()))
        .thenReturn(1);

    ScheduleForwarderResult result = forwarder.advance(null);

    assertThat(result.attemptedEvents()).isEqualTo(1);
    verify(outboxPublisher).publish(won);
    verify(outboxPublisher, never()).publish(lost);
  }

  @Test
  @DisplayName("跨租户批:阶段一按租户分组各一条批量抢占(保留复合分布键裁剪)")
  void advanceGroupsPhaseOneByTenant() {
    OutboxEventEntity ta = event("ta", 1L, 0);
    OutboxEventEntity tb = event("tb", 2L, 0);
    when(outboxEventMapper.selectPending(any())).thenReturn(List.of(ta, tb));
    when(outboxEventMapper.markPublishingBatch(
            anyString(), anyList(), anyString(), anyString(), anyString()))
        .thenReturn(List.of());

    forwarder.advance(null);

    verify(outboxEventMapper)
        .markPublishingBatch(eq("ta"), eq(List.of(1L)), anyString(), anyString(), anyString());
    verify(outboxEventMapper)
        .markPublishingBatch(eq("tb"), eq(List.of(2L)), anyString(), anyString(), anyString());
  }

  private OutboxEventEntity event(String tenantId, Long id, int publishAttempt) {
    OutboxEventEntity event = new OutboxEventEntity();
    event.setTenantId(tenantId);
    event.setId(id);
    event.setPublishAttempt(publishAttempt);
    event.setEventKey(tenantId + ":" + id);
    event.setTraceId("trace-" + id);
    return event;
  }
}
