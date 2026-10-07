package io.github.pinpols.batch.trigger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Outbox 自适应发布限速器:按 Kafka lag 样本以 AIMD 策略调节单进程每秒发布上限")
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
  @DisplayName("自适应发布关闭时有效速率恒为配置上限 40,不随 lag 变化参与调频")
  void disabled_returnsConfiguredLimitWithoutReadingLag() {
    properties.setAdaptiveReleaseEnabled(false);

    assertThat(governor.effectiveLimit()).isEqualTo(40);
  }

  @Test
  @DisplayName("lag 样本未知时保守降速到配置的最小发布速率 5,避免在观测缺失时加速")
  void unknownLag_fallsBackToMinimumReleaseRate() {
    properties.setAdaptiveReleaseEnabled(true);
    sample(TriggerLaunchLagMonitor.UNKNOWN_LAG, 1L);

    assertThat(governor.effectiveLimit()).isEqualTo(5);
  }

  @Test
  @DisplayName("启动期因未知 lag 降速后,首个健康样本立即恢复到配置上限而非缓慢爬升")
  void firstHealthySample_restoresConfiguredLimitAfterStartupUnknownSample() {
    properties.setAdaptiveReleaseEnabled(true);
    sample(TriggerLaunchLagMonitor.UNKNOWN_LAG, 1L);
    assertThat(governor.effectiveLimit()).isEqualTo(5);

    sample(0L, 2L);
    assertThat(governor.effectiveLimit()).isEqualTo(40);
  }

  @Test
  @DisplayName("lag 触及硬阈值时发布速率减半,同一序号样本重复读取不会再次减半")
  void hardLag_halvesOncePerNewSample() {
    properties.setAdaptiveReleaseEnabled(true);
    sample(500L, 1L);

    assertThat(governor.effectiveLimit()).isEqualTo(20);
    assertThat(governor.effectiveLimit()).isEqualTo(20);

    sample(700L, 2L);
    assertThat(governor.effectiveLimit()).isEqualTo(10);
  }

  @Test
  @DisplayName("lag 触及软阈值时发布速率按四分之一比例下调,由 40 降到 30")
  void softLag_decreasesByOneQuarter() {
    properties.setAdaptiveReleaseEnabled(true);
    sample(100L, 1L);

    assertThat(governor.effectiveLimit()).isEqualTo(30);
  }

  @Test
  @DisplayName("lag 回落后按固定步长 2 逐样本爬升,不会一次性跳回配置上限")
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
  @DisplayName("运行期出现未知 lag 降速后再次健康时,从最小值 5 起按步长逐步恢复")
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
