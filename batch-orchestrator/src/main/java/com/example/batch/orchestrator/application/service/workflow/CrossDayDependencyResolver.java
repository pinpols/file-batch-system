package com.example.batch.orchestrator.application.service.workflow;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.service.version.ResultVersionQueryService;
import com.example.batch.orchestrator.domain.entity.ResultVersionEntity;
import com.example.batch.orchestrator.domain.workflow.CrossDayDependencySpec;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ADR-018 §决策 §解析时机 §DSL 扩展 — 跨批量日依赖运行期解析。
 *
 * <p>读路径：{@link com.example.batch.orchestrator.domain.workflow.CrossDayDependencySpec} 数组 + {@link
 * BizDateArithmetic} 算 bizDate + {@link ResultVersionQueryService} 找 EFFECTIVE 版本。
 *
 * <p>三种产出：
 *
 * <ul>
 *   <li><b>RESOLVED</b>：所有 REQUIRED 依赖命中（OPTIONAL 命中或缺失都算成功）；resolved map 注入 node payload， 下游通过
 *       {@code $.crossDay.<alias>.output.<key>} 引用；
 *   <li><b>WAITING</b>：至少 1 个 REQUIRED 依赖缺失 → 节点进入 WAITING_DEPENDENCY；
 *   <li><b>FAILED</b>：解析期错误（如非法 spec / 不识别的 strategy） → 节点 FAIL（fail-fast，杜绝静默错配）。
 * </ul>
 *
 * <p>本 Stage 4 提供独立 service；状态机注入、reconciler、超时治理在 Stage 5+。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrossDayDependencyResolver {

  private static final TypeReference<List<CrossDayDependencySpec>> SPEC_LIST_TYPE =
      new TypeReference<>() {};

  private final BizDateArithmetic bizDateArithmetic;
  private final ResultVersionQueryService resultVersionQueryService;

  /**
   * 解析入口。
   *
   * @param tenantId 租户
   * @param workflowBizDate workflow_run.biz_date（pipe 模型基准日）
   * @param crossDayDependenciesJson workflow_node.cross_day_dependencies 原始 JSONB 字符串；NULL/空跳过
   */
  public ResolutionResult resolve(
      String tenantId, LocalDate workflowBizDate, String crossDayDependenciesJson) {
    if (!Texts.hasText(tenantId)
        || workflowBizDate == null
        || !Texts.hasText(crossDayDependenciesJson)) {
      return ResolutionResult.resolved(Map.of());
    }
    List<CrossDayDependencySpec> specs;
    try {
      specs = JsonUtils.fromJson(crossDayDependenciesJson, SPEC_LIST_TYPE);
    } catch (Exception parseFailure) {
      log.warn(
          "cross_day_dependencies JSON parse failed: tenantId={}, json={}, msg={}",
          tenantId,
          crossDayDependenciesJson,
          parseFailure.getMessage());
      return ResolutionResult.failed("CROSS_DAY_DEPS_PARSE_FAILED");
    }
    if (specs == null || specs.isEmpty()) {
      return ResolutionResult.resolved(Map.of());
    }

    Map<String, Object> resolved = new LinkedHashMap<>();
    List<String> waitingReasons = new ArrayList<>();

    for (CrossDayDependencySpec spec : specs) {
      if (spec == null || !Texts.hasText(spec.jobCode())) {
        return ResolutionResult.failed("CROSS_DAY_DEP_INVALID_SPEC");
      }
      String alias = Texts.hasText(spec.alias()) ? spec.alias() : defaultAlias(spec);

      try {
        if (spec.isRangeMode()) {
          List<Map<String, Object>> rangeOutputs = resolveRange(tenantId, workflowBizDate, spec);
          if (rangeOutputs == null) {
            // 解析期错误（spec 既非 offset 也非合法 range）
            return ResolutionResult.failed("CROSS_DAY_DEP_INVALID_RANGE");
          }
          // OPTIONAL：空列表也允许；REQUIRED：空列表视为缺失
          if (rangeOutputs.isEmpty() && spec.isRequired()) {
            waitingReasons.add(missingReason(alias, spec, "RANGE_EMPTY"));
            continue;
          }
          resolved.put(alias, Map.of("outputs", rangeOutputs));
        } else {
          LocalDate target = computeOffsetDate(workflowBizDate, spec);
          if (target == null) {
            return ResolutionResult.failed("CROSS_DAY_DEP_INVALID_OFFSET");
          }
          Optional<ResultVersionEntity> hit = lookup(tenantId, spec, target);
          if (hit.isEmpty()) {
            if (spec.isRequired()) {
              waitingReasons.add(missingReason(alias, spec, target.toString()));
            }
            // OPTIONAL 缺失：alias 不写入 resolved，下游 DSL 取到 null 回退
            continue;
          }
          resolved.put(alias, toEntry(hit.get()));
        }
      } catch (Exception spec_failure) {
        log.warn(
            "cross_day_dep resolve error: tenantId={}, alias={}, jobCode={}, msg={}",
            tenantId,
            alias,
            spec.jobCode(),
            spec_failure.getMessage());
        return ResolutionResult.failed("CROSS_DAY_DEP_RESOLVE_ERROR");
      }
    }

    if (!waitingReasons.isEmpty()) {
      return ResolutionResult.waiting(waitingReasons, resolved);
    }
    return ResolutionResult.resolved(resolved);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private LocalDate computeOffsetDate(LocalDate bizDate, CrossDayDependencySpec spec) {
    if (spec.isOffsetMode()) {
      return bizDateArithmetic.resolveOffset(bizDate, spec.bizDateOffset());
    }
    if (Texts.hasText(spec.bizDateRange())) {
      return bizDateArithmetic.resolveNamedOffset(bizDate, spec.bizDateRange());
    }
    return null;
  }

  /**
   * range 模式聚合：每个范围内 LocalDate 找 EFFECTIVE 版本；缺失的日期：REQUIRED 整体缺失就 wait（caller 决定），OPTIONAL 跳过该日。
   */
  private List<Map<String, Object>> resolveRange(
      String tenantId, LocalDate bizDate, CrossDayDependencySpec spec) {
    List<LocalDate> dates = bizDateArithmetic.resolveRange(bizDate, spec.bizDateRange());
    if (dates == null) {
      return null;
    }
    List<Map<String, Object>> results = new ArrayList<>();
    for (LocalDate date : dates) {
      Optional<ResultVersionEntity> hit = lookup(tenantId, spec, date);
      hit.ifPresent(row -> results.add(toEntry(row)));
    }
    return results;
  }

  private Optional<ResultVersionEntity> lookup(
      String tenantId, CrossDayDependencySpec spec, LocalDate bizDate) {
    String strategy = spec.resolvedStrategy();
    return switch (strategy) {
      case CrossDayDependencySpec.STRATEGY_EFFECTIVE_ONLY ->
          resultVersionQueryService.findEffectiveByJob(tenantId, spec.jobCode(), bizDate);
      case CrossDayDependencySpec.STRATEGY_LATEST_INCLUDING_PENDING ->
          latestIncludingPending(tenantId, spec.jobCode(), bizDate);
      case CrossDayDependencySpec.STRATEGY_SPECIFIC_VERSION -> {
        if (spec.specificVersionNo() == null) {
          yield Optional.empty();
        }
        yield findSpecificVersion(tenantId, spec.jobCode(), bizDate, spec.specificVersionNo());
      }
      default -> Optional.empty();
    };
  }

  private Optional<ResultVersionEntity> latestIncludingPending(
      String tenantId, String jobCode, LocalDate bizDate) {
    String businessKey = "job:" + jobCode + ":" + bizDate;
    List<ResultVersionEntity> versions =
        resultVersionQueryService.listVersions(tenantId, businessKey, 1);
    return versions.isEmpty() ? Optional.empty() : Optional.of(versions.get(0));
  }

  private Optional<ResultVersionEntity> findSpecificVersion(
      String tenantId, String jobCode, LocalDate bizDate, int versionNo) {
    String businessKey = "job:" + jobCode + ":" + bizDate;
    List<ResultVersionEntity> versions =
        resultVersionQueryService.listVersions(tenantId, businessKey, 50);
    return versions.stream()
        .filter(v -> v.versionNo() != null && v.versionNo() == versionNo)
        .findFirst();
  }

  private Map<String, Object> toEntry(ResultVersionEntity row) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("versionNo", row.versionNo());
    entry.put("status", row.status());
    entry.put("payloadStorage", row.payloadStorage());
    entry.put("payloadJson", row.payloadJson());
    entry.put("payloadRef", row.payloadRef());
    entry.put("jobInstanceId", row.jobInstanceId());
    entry.put("businessKey", row.businessKey());
    return entry;
  }

  private String defaultAlias(CrossDayDependencySpec spec) {
    if (spec.isOffsetMode()) {
      int n = spec.bizDateOffset();
      String sign = n < 0 ? "minus_" : "plus_";
      return "t_" + sign + Math.abs(n);
    }
    if (Texts.hasText(spec.bizDateRange())) {
      return spec.bizDateRange().toLowerCase();
    }
    return spec.jobCode().toLowerCase();
  }

  private String missingReason(String alias, CrossDayDependencySpec spec, String detail) {
    return "MISSING:alias=" + alias + ":job=" + spec.jobCode() + ":target=" + detail;
  }

  // ── result types ────────────────────────────────────────────────────────

  public enum ResolutionStatus {
    RESOLVED,
    WAITING,
    FAILED
  }

  /**
   * 解析结果聚合：
   *
   * <ul>
   *   <li>{@link ResolutionStatus#RESOLVED}：node 可启动，{@link #resolved()} 注入 payload；
   *   <li>{@link ResolutionStatus#WAITING}：至少一个 REQUIRED 缺失，{@link #waitingReasons()} 给出原因；
   *   <li>{@link ResolutionStatus#FAILED}：解析期硬错误，节点 FAIL，{@link #failureCode()} 标识错误码。
   * </ul>
   */
  @Value
  @Builder
  public static class ResolutionResult {
    ResolutionStatus status;
    Map<String, Object> resolved;
    List<String> waitingReasons;
    String failureCode;

    public boolean isResolved() {
      return status == ResolutionStatus.RESOLVED;
    }

    public boolean isWaiting() {
      return status == ResolutionStatus.WAITING;
    }

    public boolean isFailed() {
      return status == ResolutionStatus.FAILED;
    }

    static ResolutionResult resolved(Map<String, Object> resolved) {
      return ResolutionResult.builder()
          .status(ResolutionStatus.RESOLVED)
          .resolved(resolved)
          .waitingReasons(Collections.emptyList())
          .build();
    }

    static ResolutionResult waiting(List<String> waitingReasons, Map<String, Object> resolved) {
      return ResolutionResult.builder()
          .status(ResolutionStatus.WAITING)
          .resolved(resolved)
          .waitingReasons(List.copyOf(waitingReasons))
          .build();
    }

    static ResolutionResult failed(String failureCode) {
      return ResolutionResult.builder()
          .status(ResolutionStatus.FAILED)
          .resolved(Map.of())
          .waitingReasons(Collections.emptyList())
          .failureCode(failureCode)
          .build();
    }
  }
}
