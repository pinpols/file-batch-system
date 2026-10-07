package io.github.pinpols.batch.common.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.RunMode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("运行模式归一化:遗留别名改写与缺省模式回退")
class RunModeSupportTest {

  @Test
  @DisplayName("遗留运行模式别名被改写成规范值,原别名键随之移除")
  void shouldCanonicalizeLegacyAliasWhenCopying() {
    Map<String, Object> normalized = RunModeSupport.copyWithDefault(
        Map.of(RunModeSupport.LEGACY_RUN_MODE, "retry"), RunMode.NORMAL);

    assertThat(normalized)
        .containsEntry(RunModeSupport.RUN_MODE, RunMode.RETRY.code())
        .doesNotContainKey(RunModeSupport.LEGACY_RUN_MODE);
  }

  @Test
  @DisplayName("未显式指定运行模式时回退到调用方给出的默认模式")
  void shouldFallBackToDefaultWhenRunModeIsMissing() {
    Map<String, Object> normalized =
        RunModeSupport.copyWithDefault(Map.of("jobCode", "IMPORT_JOB"), RunMode.RERUN);

    assertThat(normalized).containsEntry(RunModeSupport.RUN_MODE, RunMode.RERUN.code());
  }
}
