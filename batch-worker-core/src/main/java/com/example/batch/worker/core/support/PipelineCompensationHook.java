package com.example.batch.worker.core.support;

import com.example.batch.worker.core.infrastructure.FileAuditParam;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 安全增量补偿(opt-in)统一钩子 —— 照 {@link PipelineVerifierHook} 模式,由 {@link
 * AbstractPipelineStepExecutionAdapter} 在 pipeline **每个失败落地点**调用。
 *
 * <p><b>核心契约(安全第一,会删数据)</b>:
 *
 * <ol>
 *   <li><b>默认 off 逐字节不变</b>:仅当模板 {@code template_config.compensate_on_failure == true} 且该 pipeline
 *       类型注册了 {@link PipelineCompensator} 时才补偿;否则 {@link #runCompensation} 直接返回(adapter 走原
 *       markPipelineFailed 路径),行为与未引入本特性时完全一致。
 *   <li><b>COMPENSATING 是中间态</b>:触发补偿时先 {@code markPipelineCompensating},补偿跑完由 adapter 继续 {@code
 *       markPipelineFailed} 落 FAILED 终态(不停在 COMPENSATING)。
 *   <li><b>补偿不掩盖原始失败</b>:compensator 是 best-effort,内部异常 / FAILED 结果只记审计 + 日志, 本钩子<b>永不抛异常</b>到
 *       pipeline 主链路。
 *   <li><b>每条反向动作落审计</b>:删了什么 / 影响行数 / SKIPPED / FAILED 原因,写 file_audit_log(fileId 存在时) + 结构化 info
 *       日志。
 * </ol>
 *
 * <p>worker-core 通过 {@code ObjectProvider} 注入 compensator,不硬依赖任何具体 worker;无实现 bean 场景整体跳过。
 */
@Component
@Slf4j
public class PipelineCompensationHook {

  /** 模板顶层布尔开关键;默认 false。 */
  static final String COMPENSATE_ON_FAILURE_KEY = "compensate_on_failure";

  private static final String AUDIT_OPERATION_TYPE = "PIPELINE_COMPENSATE";

  private final PlatformFileRuntimeRepository runtimeRepository;
  private final ObjectProvider<PipelineCompensator> compensatorProvider;

  public PipelineCompensationHook(
      PlatformFileRuntimeRepository runtimeRepository,
      ObjectProvider<PipelineCompensator> compensatorProvider) {
    this.runtimeRepository = runtimeRepository;
    this.compensatorProvider = compensatorProvider;
  }

  /**
   * 在 pipeline 失败落地点调用。返回 true 表示已进入补偿(adapter 仍须随后 markPipelineFailed 落终态);返回 false 表示未触发补偿,
   * adapter 走原路径。<b>永不抛异常。</b>
   *
   * @param tenantId 租户 ID
   * @param pipelineType worker 上报的 pipeline 类型("IMPORT"/"EXPORT"/"DISPATCH")
   * @param pipelineInstanceId 当前 pipeline 实例 ID
   * @param attributes pipeline runtime attributes(含 templateConfig / fileId / traceId 等)
   * @return 是否触发了补偿(仅用于 adapter 决定是否走 COMPENSATING→FAILED 两段写;无论返回值如何 adapter 都要落 FAILED)
   */
  public boolean runCompensation(
      String tenantId,
      String pipelineType,
      Long pipelineInstanceId,
      Map<String, Object> attributes) {
    try {
      if (attributes == null || !compensateEnabled(attributes)) {
        // 默认 off:行为逐字节不变,不触碰任何 compensator。
        return false;
      }
      PipelineCompensator compensator = resolveCompensator(pipelineType);
      if (compensator == null) {
        // 开了开关但该 pipeline 类型没注册 compensator:无反向动作可做,走原路径。
        return false;
      }
      Long fileId = runtimeRepository.toLong(attributes.get(PipelineRuntimeKeys.FILE_ID));
      // ① 进入 COMPENSATING 中间态(adapter 随后落 FAILED 终态)。
      runtimeRepository.markPipelineCompensating(pipelineInstanceId);
      // ② 执行反向动作(compensator 内部已吞咽异常,但此处再兜一层防御)。
      CompensationResult result;
      try {
        result = compensator.compensate(tenantId, pipelineInstanceId, fileId, attributes);
      } catch (RuntimeException ex) {
        // 防御:compensator 理论上不抛,但若抛了也绝不掩盖原始失败。
        log.warn(
            "pipeline compensation threw unexpectedly (best-effort, not masking original"
                + " failure): tenantId={}, pipelineType={}, pipelineInstanceId={}",
            tenantId,
            pipelineType,
            pipelineInstanceId,
            ex);
        result = CompensationResult.failed("compensator threw: " + ex.getMessage());
      }
      // ③ 审计每条反向动作。
      audit(tenantId, pipelineType, pipelineInstanceId, fileId, attributes, result);
      return true;
    } catch (RuntimeException ex) {
      // 钩子自身永不把异常透传到 pipeline 主链路(否则掩盖原始失败 / 破坏 SLA)。
      log.warn(
          "pipeline compensation hook failed (swallowed, original failure preserved):"
              + " tenantId={}, pipelineType={}, pipelineInstanceId={}",
          tenantId,
          pipelineType,
          pipelineInstanceId,
          ex);
      return false;
    }
  }

  private boolean compensateEnabled(Map<String, Object> attributes) {
    Object templateConfig = attributes.get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (!(templateConfig instanceof Map<?, ?> map)) {
      // 模板配置不在 attributes(如失败发生在加载模板之前):此时尚未写入任何业务行 / 对象,无可补偿,安全跳过。
      return false;
    }
    return truthy(map.get(COMPENSATE_ON_FAILURE_KEY));
  }

  private static boolean truthy(Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
  }

  private PipelineCompensator resolveCompensator(String pipelineType) {
    if (pipelineType == null || pipelineType.isBlank()) {
      return null;
    }
    String wanted = pipelineType.trim().toUpperCase(Locale.ROOT);
    for (PipelineCompensator candidate : compensatorProvider) {
      if (candidate.pipelineType() != null
          && wanted.equals(candidate.pipelineType().trim().toUpperCase(Locale.ROOT))) {
        return candidate;
      }
    }
    return null;
  }

  private void audit(
      String tenantId,
      String pipelineType,
      Long pipelineInstanceId,
      Long fileId,
      Map<String, Object> attributes,
      CompensationResult result) {
    String traceId =
        attributes.get(PipelineRuntimeKeys.TRACE_ID) == null
            ? null
            : String.valueOf(attributes.get(PipelineRuntimeKeys.TRACE_ID));
    // 结构化 info 日志(始终落,即便 fileId 为 null):删了什么 / 影响行数 / SKIPPED / FAILED 原因。
    log.info(
        "pipeline compensation done: tenantId={}, pipelineType={}, pipelineInstanceId={},"
            + " fileId={}, outcome={}, reversedCount={}, detail={}",
        tenantId,
        pipelineType,
        pipelineInstanceId,
        fileId,
        result.outcome(),
        result.reversedCount(),
        result.detail());
    if (fileId == null) {
      return;
    }
    // 持久化审计(file_audit_log)——fileId 存在时。
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("pipelineType", pipelineType);
    detail.put("pipelineInstanceId", pipelineInstanceId);
    detail.put("outcome", result.outcome().name());
    detail.put("reversedCount", result.reversedCount());
    detail.put("detail", result.detail());
    try {
      runtimeRepository.appendAudit(
          FileAuditParam.builder()
              .tenantId(tenantId)
              .fileId(fileId)
              .operationType(AUDIT_OPERATION_TYPE)
              .operationResult(result.outcome().name())
              .operatorType("SYSTEM")
              .traceId(traceId)
              .detailSummary(detail)
              .build());
    } catch (RuntimeException ex) {
      // 审计写入数据库失败不影响 pipeline 落终态;info 日志已是补充审计。
      log.warn(
          "pipeline compensation audit persist failed (info log already recorded): fileId={}",
          fileId,
          ex);
    }
  }
}
