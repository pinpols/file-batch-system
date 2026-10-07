package io.github.pinpols.batch.worker.atomic.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 单测覆盖 SPI hardening 两点(无 docker / 不 spawn 进程,只测可测的纯静态钩子):
 *
 * <ul>
 *   <li>SH-1:{@code ..} 父目录引用检测
 *   <li>SH-2:per-invocation reader map key 唯一性
 * </ul>
 */
@DisplayName("命令执行器加固: 相对路径穿越检测与调用标识唯一性")
class ShellTaskExecutorHardeningTest {

  // ─── SH-1: parent-dir reference detection ─────────────────────────────────

  @Test
  @DisplayName("参数包含上级目录引用时应判定为路径穿越并拒绝")
  void shouldDetectParentDirRef_whenArgContainsTraversal() {
    assertThat(ShellTaskExecutor.hasParentDirRef("../../etc/passwd")).isTrue();
    assertThat(ShellTaskExecutor.hasParentDirRef("a/../b")).isTrue();
  }

  @Test
  @DisplayName("参数只含点号但不构成上级引用时应放行, 避免误伤正常取值")
  void shouldAllow_whenArgOnlyContainsDots() {
    assertThat(ShellTaskExecutor.hasParentDirRef("foo..bar")).isFalse();
    assertThat(ShellTaskExecutor.hasParentDirRef("normalarg")).isFalse();
  }

  @Test
  @DisplayName("上级目录引用位于首尾或使用反斜杠分隔时同样应被识别")
  void shouldDetectParentDirRef_whenAtBoundaries() {
    assertThat(ShellTaskExecutor.hasParentDirRef("..")).isTrue();
    assertThat(ShellTaskExecutor.hasParentDirRef("../foo")).isTrue();
    assertThat(ShellTaskExecutor.hasParentDirRef("foo/..")).isTrue();
    assertThat(ShellTaskExecutor.hasParentDirRef("foo\\..\\bar")).isTrue();
    // 合法子串不应误伤
    assertThat(ShellTaskExecutor.hasParentDirRef("a..b/c")).isFalse();
    assertThat(ShellTaskExecutor.hasParentDirRef("")).isFalse();
  }

  // ─── SH-2: reader map key uniqueness per invocation ───────────────────────

  @Test
  @DisplayName("连续两次调用应生成不同标识, 派生的输出流键也随之唯一")
  void shouldProduceDistinctKeys_whenInvocationsRepeat() {
    String id1 = ShellTaskExecutor.nextInvocationId();
    String id2 = ShellTaskExecutor.nextInvocationId();
    assertThat(id1).isNotEqualTo(id2);
    // 派生的 stdout/stderr key 也必须各自唯一
    assertThat("stdout-" + id1).isNotEqualTo("stdout-" + id2);
    assertThat("stderr-" + id1).isNotEqualTo("stderr-" + id2);
  }
}
