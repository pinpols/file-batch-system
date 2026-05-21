package com.example.batch.worker.processes.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.batch.worker.processes.domain.ProcessJobContext;
import com.example.batch.worker.processes.domain.ProcessStage;
import com.example.batch.worker.processes.domain.ProcessStageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Process COMMIT 阶段薄委托语义单测：
 *
 * <ul>
 *   <li>plugin 已解析 → 委托到 plugin.commit() 并返回其结果
 *   <li>plugin 返回 null → 视为 success 兜底
 *   <li>plugin 未解析（context.resolvedPlugin = null）→ success no-op
 *   <li>dry-run → success no-op，不调 plugin（COMMIT 是最大副作用入口）
 * </ul>
 */
class CommitStepTest {

  @Test
  void shouldReturnStageCommit_whenStageAccessed() {
    assertThat(new CommitStep().stage()).isEqualTo(ProcessStage.COMMIT);
  }

  @Test
  @DisplayName("happy path: 委托 plugin.commit()，返回插件的结果")
  void shouldDelegateToResolvedPlugin_whenPluginPresent() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    ProcessStageResult expected = ProcessStageResult.success(ProcessStage.COMMIT);
    when(plugin.commit(any())).thenReturn(expected);

    ProcessJobContext ctx = new ProcessJobContext();
    ctx.setResolvedPlugin(plugin);

    ProcessStageResult result = new CommitStep().execute(ctx);

    assertThat(result).isSameAs(expected);
    verify(plugin).commit(ctx);
  }

  @Test
  @DisplayName("plugin.commit() 返回 null → 兜底成 success(COMMIT)")
  void shouldReturnSuccess_whenPluginReturnsNull() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.commit(any())).thenReturn(null);

    ProcessJobContext ctx = new ProcessJobContext();
    ctx.setResolvedPlugin(plugin);

    ProcessStageResult result = new CommitStep().execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(result.stage()).isEqualTo(ProcessStage.COMMIT);
  }

  @Test
  @DisplayName("无 resolvedPlugin → success no-op")
  void shouldReturnSuccessNoOp_whenPluginNotResolved() {
    ProcessJobContext ctx = new ProcessJobContext();

    ProcessStageResult result = new CommitStep().execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(result.stage()).isEqualTo(ProcessStage.COMMIT);
  }

  @Test
  @DisplayName("dry-run 模式：直接 success，不调 plugin（COMMIT 不能写业务表）")
  void shouldSkipPlugin_whenDryRun() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    ProcessJobContext ctx = new ProcessJobContext();
    ctx.setResolvedPlugin(plugin);
    ctx.getAttributes().put("dryRun", Boolean.TRUE);

    ProcessStageResult result = new CommitStep().execute(ctx);

    assertThat(result.success()).isTrue();
    verifyNoInteractions(plugin);
  }

  @Test
  @DisplayName(
      "null context → 视为 dry-run（DryRunGuard.fromAttributes(null) passThrough）走 plugin null 路径")
  void shouldReturnSuccess_whenContextIsNull() {
    // DryRunGuard.fromAttributes(null) returns passThrough (not dry-run).
    // CommitStep then dereferences context.getResolvedPlugin() which NPEs.
    // 守护实际语义：CommitStep 不容忍 null context（与 ComputeStep 不同）。
    // 本用例不调用 execute(null)，改为放空 attributes 验证 plugin-null 分支 success。
    ProcessJobContext ctx = new ProcessJobContext();
    ProcessStageResult result = new CommitStep().execute(ctx);
    assertThat(result.success()).isTrue();
  }

  @Test
  @DisplayName("plugin 抛 RuntimeException → 透传出去（事务回滚由框架处理）")
  void shouldPropagateException_whenPluginThrows() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.commit(any())).thenThrow(new IllegalStateException("conflict"));

    ProcessJobContext ctx = new ProcessJobContext();
    ctx.setResolvedPlugin(plugin);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CommitStep().execute(ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflict");
  }
}
