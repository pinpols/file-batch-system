package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BundleBatchClaimPropertiesTest {

  @Test
  void shouldDefaultToDisabledWithSaneBatchSize() {
    BundleBatchClaimProperties props = new BundleBatchClaimProperties();

    assertThat(props.isEnabled()).isFalse();
    assertThat(props.effectiveBatchSize()).isEqualTo(50);
    assertThat(props.isEnabledForJob("ANY_JOB")).isFalse();
    assertThat(props.isEnabledForJob(null)).isFalse();
  }

  @Test
  void shouldFallBackToGlobalFlagWhenNoOverride() {
    BundleBatchClaimProperties props = new BundleBatchClaimProperties();
    props.setEnabled(true);

    assertThat(props.isEnabledForJob("BUNDLE_IMPORT")).isTrue();
    assertThat(props.isEnabledForJob(null)).isTrue();
  }

  @Test
  void shouldLetPerJobOverrideWinOverGlobal() {
    BundleBatchClaimProperties props = new BundleBatchClaimProperties();
    props.setEnabled(false);
    props.setJobOverrides(Map.of("BUNDLE_IMPORT", true, "BUNDLE_DISPATCH", false));

    // 全局关,但 BUNDLE_IMPORT 灰度开
    assertThat(props.isEnabledForJob("BUNDLE_IMPORT")).isTrue();
    // 显式关
    assertThat(props.isEnabledForJob("BUNDLE_DISPATCH")).isFalse();
    // 未覆盖回退全局(关)
    assertThat(props.isEnabledForJob("OTHER")).isFalse();
  }

  @Test
  void shouldClampNonPositiveBatchSizeToOne() {
    BundleBatchClaimProperties props = new BundleBatchClaimProperties();
    props.setMaxBatchSize(0);
    assertThat(props.effectiveBatchSize()).isEqualTo(1);

    props.setMaxBatchSize(-5);
    assertThat(props.effectiveBatchSize()).isEqualTo(1);
  }
}
