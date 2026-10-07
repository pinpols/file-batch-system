package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("批量认领配置项:默认取值与批量上限收敛,以及全局开关和作业级灰度覆盖的判定优先级")
class BundleBatchClaimPropertiesTest {

  @Test
  @DisplayName("未做任何配置时默认关闭,批量大小取默认上限,任意作业标识与空标识都不启用")
  void shouldDefaultToDisabledWithSaneBatchSize() {
    BundleBatchClaimProperties props = new BundleBatchClaimProperties();

    assertThat(props.isEnabled()).isFalse();
    assertThat(props.effectiveBatchSize()).isEqualTo(50);
    assertThat(props.isEnabledForJob("ANY_JOB")).isFalse();
    assertThat(props.isEnabledForJob(null)).isFalse();
  }

  @Test
  @DisplayName("开启全局开关且无作业级覆盖时,任一作业标识与空标识都按全局开关放行")
  void shouldFallBackToGlobalFlagWhenNoOverride() {
    BundleBatchClaimProperties props = new BundleBatchClaimProperties();
    props.setEnabled(true);

    assertThat(props.isEnabledForJob("BUNDLE_IMPORT")).isTrue();
    assertThat(props.isEnabledForJob(null)).isTrue();
  }

  @Test
  @DisplayName("作业级覆盖优先于全局开关:显式开启的作业放行,显式关闭的作业拦截,未覆盖的作业回退全局判定")
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
  @DisplayName("批量上限配置为零或负数时收敛为一,避免产生非法批量")
  void shouldClampNonPositiveBatchSizeToOne() {
    BundleBatchClaimProperties props = new BundleBatchClaimProperties();
    props.setMaxBatchSize(0);
    assertThat(props.effectiveBatchSize()).isEqualTo(1);

    props.setMaxBatchSize(-5);
    assertThat(props.effectiveBatchSize()).isEqualTo(1);
  }
}
