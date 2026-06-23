package io.github.pinpols.batch.worker.processes.stage;

import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import io.github.pinpols.batch.worker.processes.metrics.ProcessMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PROCESS FEEDBACK 阶段:推进水位、清理 staging、写审计 / 指标,委托到 plugin.feedback()。
 *
 * <p>FEEDBACK 在主链路成功后跑,异常应仅 log warn 不抛(避免一个失败的清理把整个 task 标失败,业务结果其实已写入 target);同时 增 {@code
 * process_feedback_swallowed_total} 指标暴露 swallow 频率,告警 / 排障入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackStep implements ProcessStageStep {

  private final ProcessMetrics metrics;

  @Override
  public ProcessStage stage() {
    return ProcessStage.FEEDBACK;
  }

  @Override
  public ProcessStageResult execute(ProcessJobContext context) {
    // ADR-026: 演练模式 plugin.feedback() 通常推水位 / 清 staging / 写审计 — 全部跳过。
    if (DryRunGuard.fromAttributes(context == null ? null : context.getAttributes()).isDryRun()) {
      return ProcessStageResult.success(stage());
    }
    ProcessComputePlugin plugin = context.getResolvedPlugin();
    if (plugin == null) {
      return ProcessStageResult.success(stage());
    }
    try {
      ProcessStageResult result = plugin.feedback(context);
      return result == null ? ProcessStageResult.success(stage()) : result;
    } catch (RuntimeException ex) {
      metrics.incrementFeedbackSwallowed(context.getTenantId());
      log.warn(
          "process feedback step swallowed exception (target row already published): tenantId={},"
              + " jobCode={}, batchKey={}",
          context.getTenantId(),
          context.getJobCode(),
          context.getBatchKey(),
          ex);
      return ProcessStageResult.success(stage());
    }
  }
}
