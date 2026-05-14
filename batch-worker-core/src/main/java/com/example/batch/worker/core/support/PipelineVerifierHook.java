package com.example.batch.worker.core.support;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.verifier.ContentVerifier;
import com.example.batch.common.verifier.ContentVerifierRegistry;
import com.example.batch.common.verifier.VerifyContext;
import com.example.batch.common.verifier.VerifyResult;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * ADR-030 §C：worker pipeline 成功路径运行 ContentVerifier 的统一钩子。
 *
 * <p>由 {@link AbstractPipelineStepExecutionAdapter#doExecute} 在 pipeline 全部 stage SUCCESS 后调用。
 * 钩子负责：
 *
 * <ol>
 *   <li>把 worker 自由格式的 {@code pipelineType()} 字符串 映射成 {@link JobType}（fallback：日志 + skip）
 *   <li>构造 {@link VerifyContext}，payload = pipeline {@code attributes}（含 fileId/recordCount/output
 *       等）
 *   <li>取所有适用 verifier，逐个跑（registry 内部已包计时与异常吞咽）
 *   <li>把失败结果写入 {@code attributes[PipelineRuntimeKeys.VERIFIER_FAILURES]} —— 后续 {@code
 *       DefaultTaskExecutionWrapper.buildReport} 透传给 orchestrator
 * </ol>
 *
 * <p>本钩子绝不抛出异常 / 翻转 success 标志；它是"软告警"基线。"硬中止" 策略由后续 PR 通过 attributes 标记叠加。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PipelineVerifierHook {

  private final ObjectProvider<ContentVerifierRegistry> registryProvider;

  /**
   * 在 pipeline 成功路径调用；失败结果落到 attributes，不抛异常。
   *
   * @param tenantId tenant ID
   * @param pipelineType worker 上报的 pipeline 类型字符串（{@code "IMPORT"/"EXPORT"/...}），与 {@link
   *     JobType#code()} 对齐
   * @param jobInstanceId 当前 job_instance.id（null 即 verifier context jobInstanceId=null）
   * @param taskId 当前 job_task.id（同上）
   * @param stageCode 当前 stage 业务码（最后一个成功 stage），用于按 stage 精确路由
   * @param attributes pipeline runtime attributes（同时作为 payload 来源 + 失败结果落地点）
   */
  public void runVerifiers(
      String tenantId,
      String pipelineType,
      Long jobInstanceId,
      Long taskId,
      String stageCode,
      Map<String, Object> attributes) {
    if (attributes == null) {
      return;
    }
    ContentVerifierRegistry registry = registryProvider.getIfAvailable();
    if (registry == null) {
      return;
    }
    JobType jobType = resolveJobType(pipelineType);
    if (jobType == null) {
      // pipelineType 不在 JobType 字典里（如自定义 WORKER 类型）—— 跳过，不报错
      return;
    }
    try {
      List<ContentVerifier> applicable = registry.verifiersFor(jobType, stageCode);
      if (applicable.isEmpty()) {
        return;
      }
      VerifyContext context =
          VerifyContext.builder()
              .tenantId(tenantId)
              .jobType(jobType)
              .jobInstanceId(jobInstanceId)
              .taskId(taskId)
              .stageCode(stageCode)
              .payload(attributes)
              .build();
      List<Map<String, Object>> failures = new ArrayList<>();
      for (ContentVerifier verifier : applicable) {
        VerifyResult result = registry.run(verifier, context);
        if (!result.passed()) {
          Map<String, Object> entry = new LinkedHashMap<>();
          entry.put("code", result.code());
          entry.put("message", result.message());
          entry.put("evidence", result.evidence());
          failures.add(entry);
          log.warn(
              "ContentVerifier failed: code={}, reason={}, tenantId={}, taskId={}, evidence={}",
              verifier.code(),
              result.code(),
              tenantId,
              taskId,
              result.evidence());
        }
      }
      if (!failures.isEmpty()) {
        attributes.put(PipelineRuntimeKeys.VERIFIER_FAILURES, failures);
      }
    } catch (RuntimeException ex) {
      // Hook 不允许把 verifier 路径异常透传到 pipeline 主链路
      SwallowedExceptionLogger.warn(PipelineVerifierHook.class, "catch:RuntimeException", ex);
    }
  }

  private static JobType resolveJobType(String pipelineType) {
    if (pipelineType == null || pipelineType.isBlank()) {
      return null;
    }
    return DictEnum.fromCode(JobType.class, pipelineType.trim().toUpperCase());
  }
}
