package io.github.pinpols.batch.trigger.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TriggerOutboxReleaseBudgetTest {

  @Test
  void reserve_capsCurrentSecondAndResetsInNextSecond() {
    TriggerOutboxRelayProperties properties = new TriggerOutboxRelayProperties();
    properties.setMaxPublishEventsPerSecond(40);
    AtomicLong epochSecond = new AtomicLong(100L);
    TriggerOutboxReleaseBudget budget =
        new TriggerOutboxReleaseBudget(properties, epochSecond::get);

    assertThat(budget.reserve(256)).isEqualTo(40);
    assertThat(budget.reserve(1)).isZero();
    assertThat(budget.reservedInCurrentWindow()).isEqualTo(40);

    epochSecond.incrementAndGet();

    assertThat(budget.reserve(8)).isEqualTo(8);
    assertThat(budget.reservedInCurrentWindow()).isEqualTo(8);
  }

  @Test
  void reserve_isUnlimitedWhenLimitIsDisabled() {
    TriggerOutboxRelayProperties properties = new TriggerOutboxRelayProperties();
    properties.setMaxPublishEventsPerSecond(0);
    TriggerOutboxReleaseBudget budget = new TriggerOutboxReleaseBudget(properties, () -> 100L);

    assertThat(budget.reserve(256)).isEqualTo(256);
    assertThat(budget.reserve(256)).isEqualTo(256);
  }

  @Test
  void reserveAtPace_compensatesForPollsSlowerThanConfiguredInterval() {
    TriggerOutboxRelayProperties properties = new TriggerOutboxRelayProperties();
    properties.setMaxPublishEventsPerSecond(40);
    AtomicLong epochSecond = new AtomicLong(100L);
    AtomicLong monotonicNanos = new AtomicLong(0L);
    TriggerOutboxReleaseBudget budget =
        new TriggerOutboxReleaseBudget(properties, epochSecond::get, monotonicNanos::get);

    assertThat(budget.reserveAtPace(256, 200)).isEqualTo(8);

    // fixed-delay 下上一轮工作多花 50ms；下一轮应领取 10 条而非固定的 8 条。
    monotonicNanos.addAndGet(250_000_000L);
    assertThat(budget.reserveAtPace(256, 200)).isEqualTo(10);
    assertThat(budget.reservedInCurrentWindow()).isEqualTo(18);
  }

  @Test
  void reserveAtPace_capsCatchUpBurstToOneSecondOfTokens() {
    TriggerOutboxRelayProperties properties = new TriggerOutboxRelayProperties();
    properties.setMaxPublishEventsPerSecond(40);
    AtomicLong epochSecond = new AtomicLong(100L);
    AtomicLong monotonicNanos = new AtomicLong(0L);
    TriggerOutboxReleaseBudget budget =
        new TriggerOutboxReleaseBudget(properties, epochSecond::get, monotonicNanos::get);

    assertThat(budget.reserveAtPace(256, 200)).isEqualTo(8);
    monotonicNanos.addAndGet(2_000_000_000L);

    // 长暂停后允许追赶，但单次最多补满一个完整秒的令牌。
    assertThat(budget.reserveAtPace(256, 200)).isEqualTo(40);
  }
}
