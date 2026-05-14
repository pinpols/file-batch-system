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
 * <p>失败语义（ADR-030 §G）：
 *
 * <ul>
 *   <li>软告警（默认）：失败仅落 attributes.verifierFailures + Micrometer + outbox；task 仍 SUCCESS
 *   <li>硬中止（{@link ContentVerifier#fatal()} 返回 true）：失败将通过返回值告知 adapter，由 adapter 决定走
 *       markPipelineFailed 而非 markPipelineSuccess（task 翻为 FAILED + 错误码 VERIFIER_FATAL）
 * </ul>
 *
 * <p>hook 本身永不抛异常——异常路径透传到 pipeline 主链路会破坏 SLA 监控。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PipelineVerifierHook {

  private final ObjectProvider<ContentVerifierRegistry> registryProvider;

  /**
   * Hook 执行结果。{@code fatalFailure=true} 表示至少一个 {@link ContentVerifier#fatal()} 的 verifier 失败，
   * 调用方（adapter）应据此走 markPipelineFailed。{@code firstFatalCode/Message} 用于失败 response。
   */
  public record VerifierHookResult(
      boolean fatalFailure, String firstFatalCode, String firstFatalMessage) {
    public static final VerifierHookResult NO_FATAL = new VerifierHookResult(false, null, null);
  }

  /**
   * 在 pipeline 成功路径调用；失败结果落到 attributes，并返回是否含 fatal 失败。
   *
   * @param tenantId tenant ID
   * @param pipelineType worker 上报的 pipeline 类型字符串（{@code "IMPORT"/"EXPORT"/...}），与 {@link
   *     JobType#code()} 对齐
   * @param jobInstanceId 当前 job_instance.id（null 即 verifier context jobInstanceId=null）
   * @param taskId 当前 job_task.id（同上）
   * @param stageCode 当前 stage 业务码（最后一个成功 stage），用于按 stage 精确路由
   * @param attributes pipeline runtime attributes（同时作为 payload 来源 + 失败结果落地点）
   * @return 是否含 fatal 失败（adapter 据此决定走 success / failure 路径）
   */
  public VerifierHookResult runVerifiers(
      String tenantId,
      String pipelineType,
      Long jobInstanceId,
      Long taskId,
      String stageCode,
      Map<String, Object> attributes) {
    if (attributes == null) {
      return VerifierHookResult.NO_FATAL;
    }
    ContentVerifierRegistry registry = registryProvider.getIfAvailable();
    if (registry == null) {
      return VerifierHookResult.NO_FATAL;
    }
    JobType jobType = resolveJobType(pipelineType);
    if (jobType == null) {
      // pipelineType 不在 JobType 字典里（如自定义 WORKER 类型）—— 跳过，不报错
      return VerifierHookResult.NO_FATAL;
    }
    try {
      List<ContentVerifier> applicable = registry.verifiersFor(jobType, stageCode);
      if (applicable.isEmpty()) {
        return VerifierHookResult.NO_FATAL;
      }
      // payload = 顶层 attributes ∪ NODE_OUTPUTS。NODE_OUTPUTS 是 worker
      // buildSuccessResponse 写的"对外契约"键集（recordCount / fileId / receiptCode /
      // publishedCount 等），verifier 应该按这个 schema 写；展平合并避免每个 verifier 自己
      // dig 进嵌套 map。同名键 NODE_OUTPUTS 覆盖顶层（前者是规范输出，后者可能是中间态）。
      Map<String, Object> payload = new LinkedHashMap<>(attributes);
      Object outputs = attributes.get(PipelineRuntimeKeys.NODE_OUTPUTS);
      if (outputs instanceof Map<?, ?> outputsMap) {
        outputsMap.forEach(
            (k, v) -> {
              if (k != null) {
                payload.put(k.toString(), v);
              }
            });
      }
      VerifyContext context =
          VerifyContext.builder()
              .tenantId(tenantId)
              .jobType(jobType)
              .jobInstanceId(jobInstanceId)
              .taskId(taskId)
              .stageCode(stageCode)
              .payload(payload)
              .build();
      List<Map<String, Object>> failures = new ArrayList<>();
      String firstFatalCode = null;
      String firstFatalMessage = null;
      for (ContentVerifier verifier : applicable) {
        VerifyResult result = registry.run(verifier, context);
        if (!result.passed()) {
          Map<String, Object> entry = new LinkedHashMap<>();
          entry.put("code", result.code());
          entry.put("message", result.message());
          entry.put("evidence", result.evidence());
          entry.put("fatal", verifier.fatal());
          failures.add(entry);
          if (verifier.fatal() && firstFatalCode == null) {
            firstFatalCode = result.code();
            firstFatalMessage = result.message();
          }
          log.warn(
              "ContentVerifier failed: code={}, reason={}, fatal={}, tenantId={}, taskId={},"
                  + " evidence={}",
              verifier.code(),
              result.code(),
              verifier.fatal(),
              tenantId,
              taskId,
              result.evidence());
        }
      }
      if (!failures.isEmpty()) {
        attributes.put(PipelineRuntimeKeys.VERIFIER_FAILURES, failures);
      }
      return firstFatalCode == null
          ? VerifierHookResult.NO_FATAL
          : new VerifierHookResult(true, firstFatalCode, firstFatalMessage);
    } catch (RuntimeException ex) {
      // Hook 不允许把 verifier 路径异常透传到 pipeline 主链路
      SwallowedExceptionLogger.warn(PipelineVerifierHook.class, "catch:RuntimeException", ex);
      return VerifierHookResult.NO_FATAL;
    }
  }

  private static JobType resolveJobType(String pipelineType) {
    if (pipelineType == null || pipelineType.isBlank()) {
      return null;
    }
    return DictEnum.fromCode(JobType.class, pipelineType.trim().toUpperCase());
  }
}
