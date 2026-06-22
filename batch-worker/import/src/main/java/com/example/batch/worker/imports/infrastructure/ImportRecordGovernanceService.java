package com.example.batch.worker.imports.infrastructure;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.ErrorSinkType;
import com.example.batch.common.enums.SkipAction;
import com.example.batch.common.enums.SkipThresholdMode;
import com.example.batch.common.utils.ContentMaskingUtils;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.FileAuditParam;
import com.example.batch.worker.core.infrastructure.FileErrorRecordParam;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.config.ImportSkipProperties;
import com.example.batch.worker.imports.domain.ImportBadRecordEntity;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 导入坏记录治理服务：集中管理跳过策略、阈值判断、坏记录持久化和错误输出汇总。
 *
 * <p><b>跳过策略</b>（{@link SkipAction}）：{@code CONTINUE}（记录后继续）、 {@code FAIL_BATCH}（触发跳过即批次失败）、{@code
 * MANUAL_REVIEW}（标记人工审核）。 允许跳过的错误码由 {@code ImportSkipProperties#skipErrorCodes} 逗号分隔配置；空列表表示全部可跳过。
 *
 * <p><b>阈值</b>（{@link SkipThresholdMode}）：{@code ABSOLUTE}（最大跳过条数）或 {@code PERCENTAGE}（最大跳过率），超阈值通过
 * {@link #recordThresholdViolation} 写入坏记录 并在上下文中标记 {@code skipThresholdExceeded=true}。
 *
 * <p><b>坏记录写入数据库</b>：每条坏记录同步写入 {@code file_error_record}， 并按 {@link ErrorSinkType} 决定是否额外写入 MinIO
 * 错误文件（{@link ImportErrorOutputStorage}）。 含 {@code error_line_masking_enabled} 配置时对错误信息和原始记录脱敏。
 *
 * <p>{@link #finalizeErrorOutput} 在 pipeline 结束时汇总统计并更新 {@code file_record} 元数据 + 审计。
 */
@Service
@RequiredArgsConstructor
public class ImportRecordGovernanceService {

  private static final String BAD_RECORDS_KEY = "badRecords";

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_SKIPPED_COUNT = PipelineRuntimeKeys.IMPORT_SKIPPED_COUNT;
  // C-2.15：按 stage 细分的 skipped / failed 计数，独立于 KEY_SKIPPED_COUNT 回退语义；
  // 运维可单独看 parse vs validation 哪一步在丢数据，上层后续可引入双阈值配置
  private static final String KEY_PARSE_SKIPPED_COUNT = "parseSkippedCount";
  private static final String KEY_VALIDATE_SKIPPED_COUNT = "validateSkippedCount";
  private static final String KEY_PARSE_FAILED_COUNT = "parseFailedCount";
  private static final String KEY_VALIDATE_FAILED_COUNT = "validateFailedCount";

  private final ImportSkipProperties skipProperties;
  private final PlatformFileRuntimeRepository runtimeRepository;
  private final ImportErrorOutputStorage errorOutputStorage;
  private final BatchSecurityProperties batchSecurityProperties;

  public boolean isSkipEnabled() {
    return skipProperties != null && skipProperties.enabled();
  }

  public boolean isSkippable(String errorCode) {
    if (!isSkipEnabled() || !Texts.hasText(errorCode)) {
      return false;
    }
    Set<String> allowedErrorCodes = resolveAllowedErrorCodes();
    return allowedErrorCodes.isEmpty() || allowedErrorCodes.contains(errorCode);
  }

  public boolean shouldFailOnSkip(String errorCode) {
    SkipAction action = resolveSkipAction();
    return action == SkipAction.FAIL_BATCH && isSkippable(errorCode);
  }

  public boolean shouldManualReview() {
    return resolveSkipAction() == SkipAction.MANUAL_REVIEW;
  }

  public void recordSkippedRecord(
      ImportJobContext context,
      ImportStage stage,
      Long recordNo,
      String errorCode,
      String errorMessage,
      Object rawRecord) {
    recordSkippedRecord(
        context, BadRecordCommand.of(stage, recordNo, errorCode, errorMessage, rawRecord, null));
  }

  /** 带 Excel 物理定位({@link SourceLocator})的跳过记录;locator 为空时退化为不带定位的行为。 */
  public void recordSkippedRecord(ImportJobContext context, BadRecordCommand command) {
    recordBadRecord(toBadRecordContext(context, command, true));
  }

  public void recordFailedRecord(
      ImportJobContext context,
      ImportStage stage,
      Long recordNo,
      String errorCode,
      String errorMessage,
      Object rawRecord) {
    recordFailedRecord(
        context, BadRecordCommand.of(stage, recordNo, errorCode, errorMessage, rawRecord, null));
  }

  /** 带 Excel 物理定位({@link SourceLocator})的失败记录;locator 为空时退化为不带定位的行为。 */
  public void recordFailedRecord(ImportJobContext context, BadRecordCommand command) {
    recordBadRecord(toBadRecordContext(context, command, false));
  }

  private BadRecordContext toBadRecordContext(
      ImportJobContext context, BadRecordCommand command, boolean skipped) {
    return BadRecordContext.builder()
        .context(context)
        .stage(command.stage())
        .recordNo(command.recordNo())
        .errorCode(command.errorCode())
        .errorMessage(command.errorMessage())
        .rawRecord(command.rawRecord())
        .skipped(skipped)
        .locator(command.locator())
        .build();
  }

  /** 坏行登记命令(把 ≥7 参的坏行入参封装成参数对象,满足参数 ≤6 约束)。 {@code locator} 为 Excel 物理定位(可空)。 */
  public record BadRecordCommand(
      ImportStage stage,
      Long recordNo,
      String errorCode,
      String errorMessage,
      Object rawRecord,
      SourceLocator locator) {
    public static BadRecordCommand of(
        ImportStage stage,
        Long recordNo,
        String errorCode,
        String errorMessage,
        Object rawRecord,
        SourceLocator locator) {
      return new BadRecordCommand(stage, recordNo, errorCode, errorMessage, rawRecord, locator);
    }
  }

  /**
   * Excel 物理定位:{@code rowNum} 为 1-based 物理行号(SAX 的 0-based rowNum +1), {@code column}
   * 为出错列的表头名。两者均可空,缺失时坏行不带定位写入数据库。
   */
  public record SourceLocator(Long rowNum, String column) {}

  public void recordThresholdViolation(
      ImportJobContext context, ImportStage stage, String errorCode, String message) {
    BadRecordContext brc =
        BadRecordContext.builder()
            .context(context)
            .stage(stage)
            .errorCode(errorCode)
            .errorMessage(message)
            .build();
    recordBadRecord(brc);
    context.getAttributes().put(PipelineRuntimeKeys.IMPORT_SKIP_THRESHOLD_EXCEEDED, true);
  }

  public boolean withinThreshold(ImportJobContext context) {
    if (!isSkipEnabled()) {
      return true;
    }
    long skippedCount = numberValue(context.getAttributes().get(KEY_SKIPPED_COUNT));
    long totalCount =
        numberValue(context.getAttributes().get(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT));
    SkipThresholdMode mode = resolveThresholdMode();
    if (mode == SkipThresholdMode.PERCENTAGE) {
      double rate = totalCount <= 0 ? 0D : (double) skippedCount / (double) totalCount;
      return rate <= Math.max(0D, skipProperties.maxSkipRate());
    }
    return skippedCount <= Math.max(0, skipProperties.maxSkipCount());
  }

  public void markThresholdExceeded(ImportJobContext context) {
    if (context != null) {
      context.getAttributes().put(PipelineRuntimeKeys.IMPORT_SKIP_THRESHOLD_EXCEEDED, true);
    }
  }

  public void finalizeErrorOutput(ImportJobContext context) {
    List<ImportBadRecordEntity> badRecords = badRecords(context);
    if (badRecords.isEmpty()) {
      return;
    }
    Map<String, Object> attrs = context.getAttributes();
    Long fileId = runtimeRepository.toLong(attrs.get(PipelineRuntimeKeys.FILE_ID));
    if (fileId == null || !Texts.hasText(context.getTenantId())) {
      return;
    }
    String errorOutputPath = null;
    ErrorSinkType sinkType = resolveErrorSinkType();
    if (sinkType == ErrorSinkType.ERROR_FILE || sinkType == ErrorSinkType.BOTH) {
      errorOutputPath =
          errorOutputStorage.writeErrorOutput(
              context.getTenantId(), String.valueOf(fileId), badRecords);
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("badRecordCount", badRecords.size());
    metadata.put("successCount", numberValue(attrs.get("successCount")));
    metadata.put(KEY_SKIPPED_COUNT, numberValue(attrs.get(KEY_SKIPPED_COUNT)));
    metadata.put("failedCount", numberValue(attrs.get("failedCount")));
    metadata.put(
        PipelineRuntimeKeys.IMPORT_TOTAL_COUNT,
        numberValue(attrs.get(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT)));
    // C-2.15：把 parse / validate 两阶段的细分计数一并登记，便于问题定位
    metadata.put(KEY_PARSE_SKIPPED_COUNT, numberValue(attrs.get(KEY_PARSE_SKIPPED_COUNT)));
    metadata.put(KEY_VALIDATE_SKIPPED_COUNT, numberValue(attrs.get(KEY_VALIDATE_SKIPPED_COUNT)));
    metadata.put(KEY_PARSE_FAILED_COUNT, numberValue(attrs.get(KEY_PARSE_FAILED_COUNT)));
    metadata.put(KEY_VALIDATE_FAILED_COUNT, numberValue(attrs.get(KEY_VALIDATE_FAILED_COUNT)));
    metadata.put(
        PipelineRuntimeKeys.IMPORT_SKIP_THRESHOLD_EXCEEDED,
        Boolean.TRUE.equals(attrs.get(PipelineRuntimeKeys.IMPORT_SKIP_THRESHOLD_EXCEEDED)));
    metadata.put(
        "manualReviewRequired",
        Boolean.TRUE.equals(attrs.get("manualReviewRequired")) || shouldManualReview());
    if (Texts.hasText(errorOutputPath)) {
      metadata.put("errorOutputPath", errorOutputPath);
    }
    runtimeRepository.updateFileMetadata(fileId, metadata);
    runtimeRepository.appendAudit(
        FileAuditParam.builder()
            .fileId(fileId)
            .tenantId(context.getTenantId())
            .operationType("BAD_RECORD_GOVERNANCE")
            .operationResult("SUCCESS")
            .operatorType("SYSTEM")
            .operatorId(context.getWorkerId())
            .traceId(stringValue(attrs.get(PipelineRuntimeKeys.TRACE_ID)))
            .evidenceRef("import-error-output")
            .detailSummary(metadata)
            .build());
  }

  /**
   * C-2.15：把 stage → stage-scoped counter key 的映射放这里。 PARSE 对应
   * parseSkippedCount/parseFailedCount；VALIDATE 对应 validateSkippedCount/
   * validateFailedCount；其他阶段（LOAD / REPORT 等）暂不细分返回 null（不写 stage-scoped counter， KEY_SKIPPED_COUNT
   * 仍正常累加保持总阈值语义）。
   */
  private String resolveStageScopedKey(ImportStage stage, boolean skipped) {
    if (stage == null) {
      return null;
    }
    return switch (stage) {
      case PARSE -> skipped ? KEY_PARSE_SKIPPED_COUNT : KEY_PARSE_FAILED_COUNT;
      case VALIDATE -> skipped ? KEY_VALIDATE_SKIPPED_COUNT : KEY_VALIDATE_FAILED_COUNT;
      default -> null;
    };
  }

  @Builder
  private record BadRecordContext(
      ImportJobContext context,
      ImportStage stage,
      Long recordNo,
      String errorCode,
      String errorMessage,
      Object rawRecord,
      boolean skipped,
      SourceLocator locator) {}

  private void recordBadRecord(BadRecordContext brc) {
    ImportJobContext context = brc.context();
    ImportStage stage = brc.stage();
    Long recordNo = brc.recordNo();
    String errorCode = brc.errorCode();
    String errorMessage = brc.errorMessage();
    Object rawRecord = brc.rawRecord();
    boolean skipped = brc.skipped();
    SourceLocator locator = brc.locator();
    Long sourceRowNum = locator == null ? null : locator.rowNum();
    String sourceColumn = locator == null ? null : locator.column();
    Map<String, Object> attrs = context.getAttributes();
    ImportBadRecordEntity badRecord =
        new ImportBadRecordEntity(
            recordNo,
            stage == null ? null : stage.name(),
            errorCode,
            errorMessage,
            rawRecord,
            skipped,
            resolveSkipAction().code(),
            null,
            sourceRowNum,
            sourceColumn);
    badRecords(context).add(badRecord);

    if (skipped) {
      increment(context, KEY_SKIPPED_COUNT);
      // C-2.15：按 stage 分桶计数，便于 parse 和 validate 的阈值 / 指标分开观察
      String stageScoped = resolveStageScopedKey(stage, true);
      if (stageScoped != null) {
        increment(context, stageScoped);
      }
    } else {
      increment(context, "failedCount");
      String stageScoped = resolveStageScopedKey(stage, false);
      if (stageScoped != null) {
        increment(context, stageScoped);
      }
    }

    Long fileId = runtimeRepository.toLong(attrs.get(PipelineRuntimeKeys.FILE_ID));
    Long pipelineInstanceId =
        runtimeRepository.toLong(attrs.get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
    Long pipelineStepRunId =
        runtimeRepository.toLong(attrs.get(PipelineRuntimeKeys.PIPELINE_STEP_RUN_ID));
    Object templateConfig = attrs.get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    boolean errorLineMask = false;
    String maskingRuleSet = null;
    if (templateConfig instanceof Map<?, ?> templateMap) {
      Object flag = templateMap.get("error_line_masking_enabled");
      errorLineMask = Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
      Object rule = templateMap.get("masking_rule_set");
      maskingRuleSet = rule == null ? null : String.valueOf(rule);
    }
    if (batchSecurityProperties.isBypassMode()) {
      errorLineMask = false;
    }
    String safeMessage =
        errorLineMask
            ? ContentMaskingUtils.maskPlainText(errorMessage, maskingRuleSet)
            : errorMessage;
    Object payloadForStore = rawRecord == null ? JsonUtils.toJson(badRecord) : rawRecord;
    Object safePayload =
        errorLineMask ? maskErrorPayload(payloadForStore, maskingRuleSet) : payloadForStore;
    runtimeRepository.insertFileErrorRecord(
        FileErrorRecordParam.builder()
            .tenantId(context.getTenantId())
            .fileId(fileId)
            .pipelineInstanceId(pipelineInstanceId)
            .pipelineStepRunId(pipelineStepRunId)
            .recordNo(recordNo)
            .errorCode(errorCode)
            .errorMessage(safeMessage)
            .errorStage(stage == null ? null : stage.name())
            .skipped(skipped)
            .skipAction(resolveSkipAction().code())
            .rawRecord(safePayload)
            .sourceRowNum(sourceRowNum)
            .sourceColumn(sourceColumn)
            .build());

    if (skipped && resolveSkipAction() == SkipAction.MANUAL_REVIEW) {
      attrs.put("manualReviewRequired", true);
    }
    if (!skipped) {
      attrs.put("lastBadRecord", badRecord);
    }
    attrs.put("lastProcessedRecordNo", recordNo);
    attrs.put("lastErrorCode", errorCode);
    attrs.put("lastErrorMessage", errorMessage);
  }

  @SuppressWarnings("unchecked")
  private List<ImportBadRecordEntity> badRecords(ImportJobContext context) {
    Object existing = context.getAttributes().get(BAD_RECORDS_KEY);
    if (existing instanceof List<?> list) {
      // 校验所有元素都是 ImportBadRecordEntity;若是则返回原 list 引用(可变),
      // 以便后续 recordBadRecord 的 add 可见。此处若返回防御性拷贝,会静默丢掉
      // 第 2 条及之后的坏记录。
      boolean allMatch = true;
      for (Object item : list) {
        if (!(item instanceof ImportBadRecordEntity)) {
          allMatch = false;
          break;
        }
      }
      if (allMatch) {
        return (List<ImportBadRecordEntity>) existing;
      }
    }
    List<ImportBadRecordEntity> created = new ArrayList<>();
    context.getAttributes().put(BAD_RECORDS_KEY, created);
    return created;
  }

  private Object maskErrorPayload(Object payload, String maskingRuleSet) {
    if (payload == null) {
      return null;
    }
    if (payload instanceof String text) {
      return ContentMaskingUtils.maskPlainText(text, maskingRuleSet);
    }
    return ContentMaskingUtils.maskPlainText(JsonUtils.toJson(payload), maskingRuleSet);
  }

  private long increment(ImportJobContext context, String key) {
    long value = numberValue(context.getAttributes().get(key)) + 1;
    context.getAttributes().put(key, value);
    return value;
  }

  private long numberValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return 0L;
    }
    String text = String.valueOf(value);
    if (!Texts.hasText(text)) {
      return 0L;
    }
    return Long.parseLong(text);
  }

  private Set<String> resolveAllowedErrorCodes() {
    if (!Texts.hasText(skipProperties.skipErrorCodes())) {
      return Set.of();
    }
    Set<String> codes = new HashSet<>();
    for (String item : skipProperties.skipErrorCodes().split(",")) {
      String code = item.trim();
      if (Texts.hasText(code)) {
        codes.add(code);
      }
    }
    return codes;
  }

  private SkipThresholdMode resolveThresholdMode() {
    SkipThresholdMode mode =
        DictEnum.fromCode(SkipThresholdMode.class, skipProperties.thresholdMode());
    return mode == null ? SkipThresholdMode.ABSOLUTE : mode;
  }

  private SkipAction resolveSkipAction() {
    SkipAction action = DictEnum.fromCode(SkipAction.class, skipProperties.skipAction());
    return action == null ? SkipAction.CONTINUE : action;
  }

  private ErrorSinkType resolveErrorSinkType() {
    ErrorSinkType sinkType = DictEnum.fromCode(ErrorSinkType.class, skipProperties.errorSinkType());
    return sinkType == null ? ErrorSinkType.BOTH : sinkType;
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
