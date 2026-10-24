package io.github.pinpols.batch.orchestrator.application.service.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.domain.workflow.CrossDayDependencySpec;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ADR-018 §决策 §解析时机 §DSL 扩展 — 跨批量日依赖运行期解析。
 *
 * <p>读路径：{@link io.github.pinpols.batch.orchestrator.domain.workflow.CrossDayDependencySpec} 数组 +
 * {@link BizDateArithmetic} 算 bizDate + {@link ResultVersionQueryService} 找 EFFECTIVE 版本。
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

  private static final String PAYLOAD_STORAGE_INLINE_JSON = "INLINE_JSON";
  private static final String FAILURE_UNSUPPORTED_PAYLOAD = "CROSS_DAY_DEP_PAYLOAD_UNSUPPORTED";

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
      String tenantId, LocalDate workflowBizDate, String crossDayDependenciesJson, boolean dryRun) {
    return resolveInternal(tenantId, workflowBizDate, crossDayDependenciesJson, dryRun, null);
  }

  /** 解析整 Workflow 重放操作中冻结的精确结果版本。 */
  public ResolutionResult resolvePinned(
      String tenantId,
      LocalDate workflowBizDate,
      String crossDayDependenciesJson,
      boolean dryRun,
      Map<String, List<Long>> resultVersionIdsByAlias) {
    if (EmptyChecks.isNull(resultVersionIdsByAlias)) {
      return ResolutionResult.failed("CROSS_DAY_DEP_SNAPSHOT_INVALID");
    }
    return resolveInternal(
        tenantId, workflowBizDate, crossDayDependenciesJson, dryRun, resultVersionIdsByAlias);
  }

  private ResolutionResult resolveInternal(
      String tenantId,
      LocalDate workflowBizDate,
      String crossDayDependenciesJson,
      boolean dryRun,
      Map<String, List<Long>> pinnedResultIdsByAlias) {
    if (!Texts.hasText(tenantId)
        || workflowBizDate == null
        || !Texts.hasText(crossDayDependenciesJson)) {
      return ResolutionResult.resolvedPayload(Map.of(), Map.of());
    }
    List<CrossDayDependencySpec> specs;
    try {
      specs = JsonUtils.fromJson(crossDayDependenciesJson, SPEC_LIST_TYPE);
    } catch (Exception parseFailure) {
      log.warn(
          "cross_day_dependencies JSON parse failed: tenantId={}, json={}, msg={}",
          tenantId,
          crossDayDependenciesJson,
          SwallowedExceptionLogger.summary(parseFailure));
      return ResolutionResult.failed("CROSS_DAY_DEPS_PARSE_FAILED");
    }
    if (specs == null || specs.isEmpty()) {
      return ResolutionResult.resolvedPayload(Map.of(), Map.of());
    }

    Map<String, Object> resolved = new LinkedHashMap<>();
    Map<String, List<Long>> resultVersionIdsByAlias = new LinkedHashMap<>();
    Set<String> aliases = new LinkedHashSet<>();
    List<String> waitingReasons = new ArrayList<>();

    DependencyResolutionContext context = DependencyResolutionContext.builder()
        .tenantId(tenantId)
        .workflowBizDate(workflowBizDate)
        .dryRun(dryRun)
        .pinnedResultIdsByAlias(pinnedResultIdsByAlias)
        .resolved(resolved)
        .resultVersionIdsByAlias(resultVersionIdsByAlias)
        .waitingReasons(waitingReasons)
        .build();

    for (CrossDayDependencySpec spec : specs) {
      boolean specIsMissingOrHasNoJobCode = EmptyChecks.isNull(spec) || !Texts.hasText(spec.jobCode());
      if (specIsMissingOrHasNoJobCode) {
        return ResolutionResult.failed("CROSS_DAY_DEP_INVALID_SPEC");
      }
      if (!spec.hasSupportedStrategy() || !spec.hasValidVersionSelector()) {
        return ResolutionResult.failed("CROSS_DAY_DEP_INVALID_STRATEGY");
      }
      boolean dependencyScopeIsUnsupported = EmptyChecks.isNotNull(spec.scope())
          && !CrossDayDependencySpec.SCOPE_REQUIRED.equalsIgnoreCase(spec.scope())
          && !CrossDayDependencySpec.SCOPE_OPTIONAL.equalsIgnoreCase(spec.scope());
      if (dependencyScopeIsUnsupported) {
        return ResolutionResult.failed("CROSS_DAY_DEP_INVALID_SCOPE");
      }
      String alias = Texts.hasText(spec.alias()) ? spec.alias() : defaultAlias(spec);
      if (!aliases.add(alias)) {
        return ResolutionResult.failed("CROSS_DAY_DEP_DUPLICATE_ALIAS");
      }
      if (EmptyChecks.isNotNull(pinnedResultIdsByAlias) && !pinnedResultIdsByAlias.containsKey(alias)) {
        return ResolutionResult.failed("CROSS_DAY_DEP_SNAPSHOT_INVALID");
      }

      ResolutionResult failure = resolveDependencySpec(spec, alias, context);
      if (EmptyChecks.isNotNull(failure)) {
        return failure;
      }
    }

    if (EmptyChecks.isNotNull(pinnedResultIdsByAlias) && !aliases.equals(pinnedResultIdsByAlias.keySet())) {
      return ResolutionResult.failed("CROSS_DAY_DEP_SNAPSHOT_INVALID");
    }

    if (EmptyChecks.isNotEmpty(waitingReasons)) {
      return ResolutionResult.waiting(waitingReasons, resolved, resultVersionIdsByAlias);
    }
    return ResolutionResult.resolvedPayload(resolved, resultVersionIdsByAlias);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  /** 同一节点依赖解析的输入与累积结果，保持逐条失败关闭和版本钉住。 */
  @Builder
  private record DependencyResolutionContext(
      String tenantId,
      LocalDate workflowBizDate,
      boolean dryRun,
      Map<String, List<Long>> pinnedResultIdsByAlias,
      Map<String, Object> resolved,
      Map<String, List<Long>> resultVersionIdsByAlias,
      List<String> waitingReasons) {}

  private ResolutionResult resolveDependencySpec(
      CrossDayDependencySpec spec, String alias, DependencyResolutionContext context) {
    try {
      if (spec.isRangeMode()) {
        List<Map<String, Object>> rangeOutputs;
        if (EmptyChecks.isNull(context.pinnedResultIdsByAlias())) {
          RangeResolution rangeResolution =
              resolveRange(context.tenantId(), context.workflowBizDate(), spec, context.dryRun());
          if (EmptyChecks.isNull(rangeResolution)) {
            return ResolutionResult.failed("CROSS_DAY_DEP_INVALID_RANGE");
          }
          rangeOutputs = rangeResolution.outputs();
          if (spec.isRequired() && !EmptyChecks.isEmpty(Objects.requireNonNull(rangeResolution.missingDates()))) {
            String missingDateList = String.join(",", rangeResolution.missingDates());
            String missingRangeReason = "RANGE_MISSING:" + missingDateList;
            context.waitingReasons().add(missingReason(alias, spec, missingRangeReason));
          }
        } else {
          rangeOutputs = resolvePinnedRange(
              context.tenantId(),
              context.workflowBizDate(),
              spec,
              context.dryRun(),
              context.pinnedResultIdsByAlias().get(alias));
          if (EmptyChecks.isNull(rangeOutputs)) {
            return ResolutionResult.failed("CROSS_DAY_DEP_SNAPSHOT_INVALID");
          }
        }
        // OPTIONAL：缺失日期允许；REQUIRED：任一日期缺失时等待或在 pinned replay 中失败关闭。
        context.resolved().put(alias, Map.of("outputs", rangeOutputs));
        context.resultVersionIdsByAlias().put(alias, resultIds(rangeOutputs));
      } else {
        LocalDate target = computeOffsetDate(context.workflowBizDate(), spec);
        if (EmptyChecks.isNull(target)) {
          return ResolutionResult.failed("CROSS_DAY_DEP_INVALID_OFFSET");
        }
        Optional<ResultVersionEntity> hit;
        if (EmptyChecks.isNull(context.pinnedResultIdsByAlias())) {
          hit = lookup(context.tenantId(), spec, target, context.dryRun());
        } else {
          hit = resolvePinnedSingle(
              context.tenantId(), spec, target, context.dryRun(), context.pinnedResultIdsByAlias().get(alias));
        }
        if (!hit.isPresent()) {
          context.resultVersionIdsByAlias().put(alias, List.of());
          if (EmptyChecks.isNotNull(context.pinnedResultIdsByAlias()) && spec.isRequired()) {
            return ResolutionResult.failed("CROSS_DAY_DEP_SNAPSHOT_INVALID");
          }
          if (spec.isRequired()) {
            String missingDate = target.toString();
            context.waitingReasons().add(missingReason(alias, spec, missingDate));
          }
          // OPTIONAL 缺失：alias 不写入 resolved，下游 DSL 取到 null 回退
          return null;
        }
        ResultVersionEntity resultVersion = hit.get();
        Map<String, Object> resolvedEntry = toEntry(resultVersion);
        context.resolved().put(alias, resolvedEntry);
        context.resultVersionIdsByAlias().put(alias, List.of(resultVersion.id()));
      }
    } catch (UnsupportedCrossDayPayloadException unsupportedPayload) {
      return ResolutionResult.failed(FAILURE_UNSUPPORTED_PAYLOAD);
    } catch (Exception specFailure) {
      log.warn(
          "cross_day_dep resolve error: tenantId={}, alias={}, jobCode={}, msg={}",
          context.tenantId(),
          alias,
          spec.jobCode(),
          SwallowedExceptionLogger.summary(specFailure));
      return ResolutionResult.failed("CROSS_DAY_DEP_RESOLVE_ERROR");
    }
    return null;
  }

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
  private RangeResolution resolveRange(
      String tenantId, LocalDate bizDate, CrossDayDependencySpec spec, boolean dryRun) {
    List<LocalDate> dates = bizDateArithmetic.resolveRange(bizDate, spec.bizDateRange());
    if (dates == null) {
      return null;
    }
    List<Map<String, Object>> results = new ArrayList<>();
    List<String> missingDates = new ArrayList<>();
    for (LocalDate date : dates) {
      Optional<ResultVersionEntity> hit = lookup(tenantId, spec, date, dryRun);
      if (hit.isPresent()) {
        ResultVersionEntity resultVersion = hit.get();
        Map<String, Object> resultEntry = toEntry(resultVersion);
        results.add(resultEntry);
      } else {
        missingDates.add(date.toString());
      }
    }
    return new RangeResolution(results, missingDates);
  }

  private List<Map<String, Object>> resolvePinnedRange(
      String tenantId,
      LocalDate bizDate,
      CrossDayDependencySpec spec,
      boolean dryRun,
      List<Long> pinnedResultIds) {
    List<LocalDate> dates = bizDateArithmetic.resolveRange(bizDate, spec.bizDateRange());
    if (EmptyChecks.isNull(dates) || EmptyChecks.isNull(pinnedResultIds)) {
      return null;
    }
    Map<String, ResultVersionEntity> selectedByBusinessKey = new LinkedHashMap<>();
    for (Long resultVersionId : pinnedResultIds) {
      if (EmptyChecks.isNull(resultVersionId) || resultVersionId <= 0) {
        return null;
      }
      ResultVersionEntity selected = resultVersionQueryService
          .findById(tenantId, resultVersionId)
          .orElse(null);
      if (!isPinnedVersionUsable(selected, dryRun)) {
        return null;
      }
      String selectedBusinessKey = selected.businessKey();
      boolean selectedBusinessKeyIsOutsideRange =
          dates.stream()
              .noneMatch(date -> businessKey(spec.jobCode(), date).equals(selectedBusinessKey));
      if (selectedBusinessKeyIsOutsideRange) {
        return null;
      }
      boolean resultVersionWasAlreadySelected =
          EmptyChecks.isNotNull(selectedByBusinessKey.putIfAbsent(selectedBusinessKey, selected));
      if (resultVersionWasAlreadySelected) {
        return null;
      }
    }
    List<Map<String, Object>> results = new ArrayList<>();
    for (LocalDate date : dates) {
      String dateBusinessKey = businessKey(spec.jobCode(), date);
      ResultVersionEntity selected = selectedByBusinessKey.get(dateBusinessKey);
      if (EmptyChecks.isNull(selected)) {
        if (spec.isRequired()) {
          return null;
        }
        continue;
      }
      Map<String, Object> resultEntry = toEntry(selected);
      results.add(resultEntry);
    }
    return results;
  }

  private Optional<ResultVersionEntity> resolvePinnedSingle(
      String tenantId,
      CrossDayDependencySpec spec,
      LocalDate targetDate,
      boolean dryRun,
      List<Long> pinnedResultIds) {
    if (EmptyChecks.isNull(pinnedResultIds) || pinnedResultIds.size() > 1) {
      return Optional.empty();
    }
    if (EmptyChecks.isEmpty(Objects.requireNonNull(pinnedResultIds))) {
      return Optional.empty();
    }
    Long resultVersionId = pinnedResultIds.getFirst();
    if (EmptyChecks.isNull(resultVersionId) || resultVersionId <= 0) {
      return Optional.empty();
    }
    ResultVersionEntity selected = resultVersionQueryService
        .findById(tenantId, resultVersionId)
        .orElse(null);
    if (!isPinnedVersionUsable(selected, dryRun)
        || !businessKey(spec.jobCode(), targetDate).equals(selected.businessKey())) {
      return Optional.empty();
    }
    return Optional.of(selected);
  }

  private boolean isPinnedVersionUsable(ResultVersionEntity selected, boolean dryRun) {
    return EmptyChecks.isNotNull(selected)
        && EmptyChecks.isNotNull(selected.id())
        && EmptyChecks.isNotNull(selected.jobInstanceId())
        && "INLINE_JSON".equals(selected.payloadStorage())
        && (dryRun == "DRY_RUN".equals(selected.status()));
  }

  private String businessKey(String jobCode, LocalDate bizDate) {
    return "job:" + jobCode + ":" + bizDate;
  }

  private List<Long> resultIds(List<Map<String, Object>> outputs) {
    List<Long> resultVersionIds = new ArrayList<>();
    for (Map<String, Object> output : outputs) {
      Object id = output.get("resultVersionId");
      if (id instanceof Number number && number.longValue() > 0) {
        resultVersionIds.add(number.longValue());
      } else {
        throw new IllegalStateException("resolved dependency result has no stable resultVersionId");
      }
    }
    return List.copyOf(resultVersionIds);
  }

  private Optional<ResultVersionEntity> lookup(
      String tenantId, CrossDayDependencySpec spec, LocalDate bizDate, boolean dryRun) {
    String strategy = spec.resolvedStrategy();
    return switch (strategy) {
      case CrossDayDependencySpec.STRATEGY_EFFECTIVE_ONLY -> {
        Optional<ResultVersionEntity> latest =
            findLatestModeVersion(tenantId, spec, bizDate, dryRun);
        yield latest.filter(
            row -> dryRun ? "DRY_RUN".equals(row.status()) : "EFFECTIVE".equals(row.status()));
      }
      case CrossDayDependencySpec.STRATEGY_LATEST_INCLUDING_PENDING ->
        findLatestModeVersion(tenantId, spec, bizDate, dryRun);
      case CrossDayDependencySpec.STRATEGY_SPECIFIC_VERSION -> {
        if (spec.specificVersionNo() == null) {
          yield Optional.empty();
        }
        yield findSpecificVersion(
            tenantId, spec.jobCode(), bizDate, spec.specificVersionNo(), dryRun);
      }
      default -> Optional.empty();
    };
  }

  private Optional<ResultVersionEntity> findLatestModeVersion(
      String tenantId, CrossDayDependencySpec spec, LocalDate bizDate, boolean dryRun) {
    return resultVersionQueryService.findLatestCrossDayDependencyVersion(
        tenantId, spec.jobCode(), bizDate, dryRun);
  }

  private Optional<ResultVersionEntity> findSpecificVersion(
      String tenantId, String jobCode, LocalDate bizDate, int versionNo, boolean dryRun) {
    return resultVersionQueryService.findCrossDayDependencyVersion(
        tenantId, jobCode, bizDate, versionNo, dryRun);
  }

  private Map<String, Object> toEntry(ResultVersionEntity row) {
    if (!PAYLOAD_STORAGE_INLINE_JSON.equals(row.payloadStorage())
        || !Texts.hasText(row.payloadJson())) {
      throw new UnsupportedCrossDayPayloadException();
    }
    Object output;
    try {
      output = JsonUtils.fromJson(row.payloadJson(), Object.class);
    } catch (IllegalArgumentException malformedPayload) {
      throw new UnsupportedCrossDayPayloadException();
    }
    if (!(output instanceof Map<?, ?>)) {
      throw new UnsupportedCrossDayPayloadException();
    }
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("resultVersionId", row.id());
    entry.put("versionNo", row.versionNo());
    entry.put("status", row.status());
    entry.put("payloadStorage", row.payloadStorage());
    entry.put("output", output);
    entry.put("jobInstanceId", row.jobInstanceId());
    entry.put("businessKey", row.businessKey());
    return entry;
  }

  private static final class UnsupportedCrossDayPayloadException extends RuntimeException {}

  private record RangeResolution(List<Map<String, Object>> outputs, List<String> missingDates) {}

  private String defaultAlias(CrossDayDependencySpec spec) {
    if (spec.isOffsetMode()) {
      int n = spec.bizDateOffset();
      String sign = n < 0 ? "minus_" : "plus_";
      return "t_" + sign + Math.abs(n);
    }
    if (Texts.hasText(spec.bizDateRange())) {
      return spec.bizDateRange().toLowerCase(Locale.ROOT);
    }
    return spec.jobCode().toLowerCase(Locale.ROOT);
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
    Map<String, List<Long>> resultVersionIdsByAlias;

    public boolean isResolved() {
      return status == ResolutionStatus.RESOLVED;
    }

    public boolean isWaiting() {
      return status == ResolutionStatus.WAITING;
    }

    public boolean isFailed() {
      return status == ResolutionStatus.FAILED;
    }

    static ResolutionResult resolvedPayload(
        Map<String, Object> resolved, Map<String, List<Long>> resultVersionIdsByAlias) {
      return ResolutionResult.builder()
          .status(ResolutionStatus.RESOLVED)
          .resolved(resolved)
          .waitingReasons(Collections.emptyList())
          .resultVersionIdsByAlias(immutableResultVersionIds(resultVersionIdsByAlias))
          .build();
    }

    static ResolutionResult waiting(
        List<String> waitingReasons,
        Map<String, Object> resolved,
        Map<String, List<Long>> resultVersionIdsByAlias) {
      return ResolutionResult.builder()
          .status(ResolutionStatus.WAITING)
          .resolved(resolved)
          .waitingReasons(List.copyOf(waitingReasons))
          .resultVersionIdsByAlias(immutableResultVersionIds(resultVersionIdsByAlias))
          .build();
    }

    static ResolutionResult failed(String failureCode) {
      return ResolutionResult.builder()
          .status(ResolutionStatus.FAILED)
          .resolved(Map.of())
          .waitingReasons(Collections.emptyList())
          .resultVersionIdsByAlias(Map.of())
          .failureCode(failureCode)
          .build();
    }

    private static Map<String, List<Long>> immutableResultVersionIds(
        Map<String, List<Long>> resultVersionIdsByAlias) {
      Map<String, List<Long>> immutableIdsByAlias = new LinkedHashMap<>();
      resultVersionIdsByAlias.forEach(
          (alias, resultVersionIds) -> immutableIdsByAlias.put(alias, List.copyOf(resultVersionIds)));
      return Collections.unmodifiableMap(immutableIdsByAlias);
    }
  }
}
