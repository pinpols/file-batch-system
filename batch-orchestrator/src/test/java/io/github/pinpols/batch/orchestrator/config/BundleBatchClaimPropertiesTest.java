package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("批量认领配置项:默认批量上限与非正数上限收敛")
class BundleBatchClaimPropertiesTest {

  @Test
  @DisplayName("未做任何配置时批量大小取默认上限")
  void shouldUseDefaultBatchSize_whenNotConfigured() {
    BundleBatchClaimProperties props = new BundleBatchClaimProperties();

    assertThat(props.effectiveBatchSize()).isEqualTo(50);
  }

  @Test
  @DisplayName("批量上限配置为零或负数时收敛为一,避免产生非法批量")
  void shouldClampNonPositiveBatchSizeToOne() {
    BundleBatchClaimProperties props = new BundleBatchClaimProperties();
    props.setMaxBatchSize(0);
    assertThat(props.effectiveBatchSize()).isEqualTo(1);

    props.setMaxBatchSize(-5);
    assertThat(props.effectiveBatchSize()).isEqualTo(1);
  }
}
