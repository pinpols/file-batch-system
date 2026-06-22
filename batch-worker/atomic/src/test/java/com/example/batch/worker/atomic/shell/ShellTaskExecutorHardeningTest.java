package com.example.batch.worker.atomic.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 单测覆盖 SPI hardening 两点(无 docker / 不 spawn 进程,只测可测的纯静态钩子):
 *
 * <ul>
 *   <li>SH-1:{@code ..} 父目录引用检测
 *   <li>SH-2:per-invocation reader map key 唯一性
 * </ul>
 */
class ShellTaskExecutorHardeningTest {

  // ─── SH-1: parent-dir reference detection ─────────────────────────────────

  @Test
  void rejectsPathTraversalArgs() {
    assertThat(ShellTaskExecutor.hasParentDirRef("../../etc/passwd")).isTrue();
    assertThat(ShellTaskExecutor.hasParentDirRef("a/../b")).isTrue();
  }

  @Test
  void allowsLegitArgsThatMerelyContainDots() {
    assertThat(ShellTaskExecutor.hasParentDirRef("foo..bar")).isFalse();
    assertThat(ShellTaskExecutor.hasParentDirRef("normalarg")).isFalse();
  }

  @Test
  void detectsParentDirRefAtBoundaries() {
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
  void twoInvocationsProduceDistinctKeys() {
    String id1 = ShellTaskExecutor.nextInvocationId();
    String id2 = ShellTaskExecutor.nextInvocationId();
    assertThat(id1).isNotEqualTo(id2);
    // 派生的 stdout/stderr key 也必须各自唯一
    assertThat("stdout-" + id1).isNotEqualTo("stdout-" + id2);
    assertThat("stderr-" + id1).isNotEqualTo("stderr-" + id2);
  }
}
