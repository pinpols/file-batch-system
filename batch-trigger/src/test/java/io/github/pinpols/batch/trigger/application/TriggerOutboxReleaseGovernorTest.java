package io.github.pinpols.batch.trigger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TriggerOutboxReleaseGovernorTest {

  @Mock
  private TriggerLaunchLagMonitor lagMonitor;

  private TriggerOutboxRelayProperties properties;
  private TriggerOutboxReleaseGovernor governor;

  @BeforeEach
  void setUp() {
    properties = new TriggerOutboxRelayProperties();
    properties.setMaxPublishEventsPerSecond(40);
    properties.setMinPublishEventsPerSecond(5);
    properties.setLagSoftThreshold(100);
    properties.setLagHardThreshold(500);
    properties.setAdaptiveIncreaseStep(2);
    governor = new TriggerOutboxReleaseGovernor(properties, lagMonitor);
  }

  @Test
  void disabled_returnsConfiguredLimitWithoutReadingLag() {
    properties.setAdaptiveReleaseEnabled(false);

    assertThat(governor.effectiveLimit()).isEqualTo(40);
  }

  @Test
  void unknownLag_fallsBackToMinimumReleaseRate() {
    properties.setAdaptiveReleaseEnabled(true);
    sample(TriggerLaunchLagMonitor.UNKNOWN_LAG, 1L);

    assertThat(governor.effectiveLimit()).isEqualTo(5);
  }

  @Test
  void firstHealthySample_restoresConfiguredLimitAfterStartupUnknownSample() {
    properties.setAdaptiveReleaseEnabled(true);
    sample(TriggerLaunchLagMonitor.UNKNOWN_LAG, 1L);
    assertThat(governor.effectiveLimit()).isEqualTo(5);

    sample(0L, 2L);
    assertThat(governor.effectiveLimit()).isEqualTo(40);
  }

  @Test
  void hardLag_halvesOncePerNewSample() {
    properties.setAdaptiveReleaseEnabled(true);
    sample(500L, 1L);

    assertThat(governor.effectiveLimit()).isEqualTo(20);
    assertThat(governor.effectiveLimit()).isEqualTo(20);

    sample(700L, 2L);
    assertThat(governor.effectiveLimit()).isEqualTo(10);
  }

  @Test
  void softLag_decreasesByOneQuarter() {
    properties.setAdaptiveReleaseEnabled(true);
    sample(100L, 1L);

    assertThat(governor.effectiveLimit()).isEqualTo(30);
  }

  @Test
  void lowLag_recoversGraduallyToConfiguredLimit() {
    properties.setAdaptiveReleaseEnabled(true);
    sample(500L, 1L);
    assertThat(governor.effectiveLimit()).isEqualTo(20);

    sample(0L, 2L);
    assertThat(governor.effectiveLimit()).isEqualTo(22);

    sample(0L, 3L);
    assertThat(governor.effectiveLimit()).isEqualTo(24);
  }

  @Test
  void healthySampleAfterRuntimeUnknown_recoversGradually() {
    properties.setAdaptiveReleaseEnabled(true);
    sample(500L, 1L);
    assertThat(governor.effectiveLimit()).isEqualTo(20);

    sample(TriggerLaunchLagMonitor.UNKNOWN_LAG, 2L);
    assertThat(governor.effectiveLimit()).isEqualTo(5);

    sample(0L, 3L);
    assertThat(governor.effectiveLimit()).isEqualTo(7);
  }

  private void sample(long lag, long sequence) {
    when(lagMonitor.current()).thenReturn(new TriggerLaunchLagMonitor.LagSnapshot(lag, sequence));
  }
}
