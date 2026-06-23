package io.github.pinpols.batch.common.spi.task;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * K4:{@link PipelineWorkerAtomicClasspathCheck} 单测。
 *
 * <p>覆盖两条路径:① canary 缺席 → 放行(batch-common 单测 classpath 默认不会有 atomic 模块 class);② canary 命中 →
 * fail-fast(用 自定义 ClassLoader 假装命中)。
 */
class PipelineWorkerAtomicClasspathCheckTest {

  @Test
  void shouldPass_whenCanaryClassAbsent() {
    // batch-common 测试 classpath 默认不含 batch-worker-atomic 类,canary 应找不到 → 放行
    PipelineWorkerAtomicClasspathCheck check = new PipelineWorkerAtomicClasspathCheck();
    assertThatCode(check::verifyClasspathIsolation).doesNotThrowAnyException();
  }

  @Test
  void shouldFailFast_whenCanaryPresent() {
    // 通过子类 override isCanaryPresent() 模拟「atomic 类在 pipeline worker classpath 上」
    PipelineWorkerAtomicClasspathCheck check =
        new PipelineWorkerAtomicClasspathCheck() {
          @Override
          protected boolean isCanaryPresent() {
            return true;
          }
        };
    assertThatThrownBy(check::verifyClasspathIsolation)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ADR-029 violation")
        .hasMessageContaining(PipelineWorkerAtomicClasspathCheck.CANARY_CLASS);
  }

  @Test
  void shouldPass_whenCanaryAbsent_viaOverride() {
    PipelineWorkerAtomicClasspathCheck check =
        new PipelineWorkerAtomicClasspathCheck() {
          @Override
          protected boolean isCanaryPresent() {
            return false;
          }
        };
    assertThatCode(check::verifyClasspathIsolation).doesNotThrowAnyException();
  }
}
