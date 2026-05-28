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
import com.example.batch.worker.processes.metrics.ProcessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Process FEEDBACK 阶段语义守护：
 *
 * <ul>
 *   <li>plugin.feedback() 异常被 swallow + metric 计数（业务已成功提交，不应让 cleanup 失败拖垮 task）
 *   <li>plugin 缺失 / null 返回 / dry-run → 直接 success
 * </ul>
 */
class FeedbackStepTest {

  private static ProcessMetrics noopMetrics() {
    return ProcessMetrics.noop();
  }

  private static ProcessMetrics realMetrics(SimpleMeterRegistry registry) {
    ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
        new ObjectProvider<>() {
          @Override
          public io.micrometer.core.instrument.MeterRegistry getObject(Object... args) {
            return registry;
          }

          @Override
          public io.micrometer.core.instrument.MeterRegistry getObject() {
            return registry;
          }

          @Override
          public io.micrometer.core.instrument.MeterRegistry getIfAvailable() {
            return registry;
          }

          @Override
          public io.micrometer.core.instrument.MeterRegistry getIfUnique() {
            return registry;
          }
        };
    return new ProcessMetrics(provider);
  }

  @Test
  void shouldReturnStageFeedback_whenStageAccessed() {
    assertThat(new FeedbackStep(noopMetrics()).stage()).isEqualTo(ProcessStage.FEEDBACK);
  }

  @Test
  @DisplayName("happy path: 委托 plugin.feedback()，返回插件结果")
  void shouldDelegateToPlugin_whenPluginPresent() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    ProcessStageResult expected = ProcessStageResult.success(ProcessStage.FEEDBACK);
    when(plugin.feedback(any())).thenReturn(expected);

    ProcessJobContext ctx = new ProcessJobContext();
    ctx.setResolvedPlugin(plugin);

    assertThat(new FeedbackStep(noopMetrics()).execute(ctx)).isSameAs(expected);
    verify(plugin).feedback(ctx);
  }

  @Test
  @DisplayName("plugin.feedback() 返回 null → 兜底 success")
  void shouldReturnSuccess_whenPluginReturnsNull() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.feedback(any())).thenReturn(null);
    ProcessJobContext ctx = new ProcessJobContext();
    ctx.setResolvedPlugin(plugin);

    ProcessStageResult result = new FeedbackStep(noopMetrics()).execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(result.stage()).isEqualTo(ProcessStage.FEEDBACK);
  }

  @Test
  @DisplayName("无 resolvedPlugin → success no-op")
  void shouldReturnSuccess_whenNoPlugin() {
    ProcessJobContext ctx = new ProcessJobContext();
    assertThat(new FeedbackStep(noopMetrics()).execute(ctx).success()).isTrue();
  }

  @Test
  @DisplayName("dry-run 模式：跳过 plugin（不清 staging / 不写审计）")
  void shouldSkipPlugin_whenDryRun() {
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    ProcessJobContext ctx = new ProcessJobContext();
    ctx.setResolvedPlugin(plugin);
    ctx.getAttributes().put("dryRun", Boolean.TRUE);

    assertThat(new FeedbackStep(noopMetrics()).execute(ctx).success()).isTrue();
    verifyNoInteractions(plugin);
  }

  @Test
  @DisplayName("plugin 抛 RuntimeException → swallow，metric +1，仍返回 success")
  void shouldSwallowException_andIncrementMetric() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ProcessMetrics metrics = realMetrics(registry);
    ProcessComputePlugin plugin = mock(ProcessComputePlugin.class);
    when(plugin.feedback(any())).thenThrow(new IllegalStateException("staging cleanup failed"));
    ProcessJobContext ctx = new ProcessJobContext();
    ctx.setTenantId("tenant-A");
    ctx.setJobCode("JOB_X");
    ctx.setResolvedPlugin(plugin);

    ProcessStageResult result = new FeedbackStep(metrics).execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(result.stage()).isEqualTo(ProcessStage.FEEDBACK);
    // metric: process_feedback_swallowed_total = 1
    // tenantId 不再作为 tag(高基数 → Prometheus 内存爆),改单全局 counter
    assertThat(registry.find("process_feedback_swallowed_total").counter()).isNotNull();
    assertThat(registry.find("process_feedback_swallowed_total").counter().count()).isEqualTo(1.0);
  }
}
