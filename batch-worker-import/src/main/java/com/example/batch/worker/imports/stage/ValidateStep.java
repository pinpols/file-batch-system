package com.example.batch.worker.imports.stage;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.infrastructure.ImportDataQualityService;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import com.example.batch.worker.imports.infrastructure.quality.ControlTotalEvaluator;
import com.example.batch.worker.imports.infrastructure.quality.ValidationIssue;
import com.example.batch.worker.imports.infrastructure.quality.ValidationSession;
import com.example.batch.worker.imports.stage.support.ImportStageSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Import pipeline 的 VALIDATE 阶段：流式读取 {@code PARSED_RECORDS_PATH} 暂存文件， 对每条记录执行数据质量校验，将通过校验的记录写入
 * {@code VALIDATED_RECORDS_PATH} 暂存文件。
 *
 * <p><b>校验流程</b>：
 *
 * <ol>
 *   <li>数据集级别校验（行数、checksum、schema 字段）——任意失败且不可跳过则立即返回。
 *   <li>逐行按 {@code chunk_size}（默认取 {@link ImportWorkerConfiguration#chunkSize()}）分块校验，
 *       通过的记录写入输出文件，失败记录交 {@link ImportRecordGovernanceService} 处理。
 *   <li>超过跳过阈值时删除输出文件并返回 {@code IMPORT_SKIP_THRESHOLD_EXCEEDED}。
 * </ol>
 *
 * <p>校验完成后更新 {@code file_record} 状态为 {@code VALIDATED} 并写入统计元数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValidateStep implements ImportStageStep {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String ERR_SKIP_THRESHOLD_EXCEEDED = "IMPORT_SKIP_THRESHOLD_EXCEEDED";
  private static final String MSG_SKIP_THRESHOLD_EXCEEDED = "skip threshold exceeded";
  private static final String I18N_SKIP_THRESHOLD_EXCEEDED = "error.import.skip_threshold_exceeded";

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final PlatformFileRuntimeRepository runtimeRepository;
  private final ImportRecordGovernanceService recordGovernanceService;
  private final ImportDataQualityService dataQualityService;
  private final ImportWorkerConfiguration workerConfiguration;
  private final ControlTotalEvaluator controlTotalEvaluator;
  private final ObjectMapper objectMapper;

  @Override
  public ImportStage stage() {
    return ImportStage.VALIDATE;
  }

  @Override
  public ImportStageResult execute(ImportJobContext context) {
    String parsedRecordsPath =
        stringValue(context.getAttributes().get(PipelineRuntimeKeys.PARSED_RECORDS_PATH));
    if (!Texts.hasText(parsedRecordsPath)) {
      return ImportStageResult.failure(
          stage(),
          "IMPORT_VALIDATE_NO_STREAM",
          "error.import.validate.no_stream",
          new Object[] {"parsed records path missing"},
          "parsed records path missing",
          objectMapper);
    }
    return executeStreaming(context, Path.of(parsedRecordsPath));
  }

  private ImportStageResult executeStreaming(ImportJobContext context, Path parsedRecordsPath) {
    if (!Files.exists(parsedRecordsPath)) {
      return ImportStageResult.failure(
          stage(),
          "IMPORT_VALIDATE_NO_STREAM",
          "error.import.validate.no_stream",
          new Object[] {"parsed records file missing"},
          "parsed records file missing",
          objectMapper);
    }
    Path validatedRecordsPath = null;
    ImportStageResult result;
    try {
      ValidationSession session = openValidationSession(context);
      // R-4.3: processDatasetIssues 不需要文件，单独处理不传 path
      ImportStageResult datasetResult = processDatasetIssues(context, session);
      if (datasetResult != null) {
        return datasetResult;
      }
      validatedRecordsPath = createValidatedFile(context);
      // R-4.3: processValidationBatch 内部的 writer 在 try-with-resources 里；
      // 失败标记只记到 StreamingValidationResult，不在 writer 还开着时 delete 文件
      // （旧实现 failStreaming 直接 delete 会导致 writer 还在写的 fd 被 unlink，
      //  Linux 下数据静默丢失、Windows 下直接报错）。
      StreamingValidationResult streamResult =
          processValidationBatch(context, session, parsedRecordsPath, validatedRecordsPath);
      if (streamResult.failure() != null) {
        // writer 此时已随 processValidationBatch 的 try-with-resources 关闭，安全 delete
        deleteQuietly(validatedRecordsPath);
        return streamResult.failure();
      }
      // ADR-041 Phase1.2:全量记录累加完毕,控制金额对账(声明总额 vs 实际累加和)。
      ImportStageResult controlTotalFailure =
          processControlTotal(context, session, validatedRecordsPath);
      if (controlTotalFailure != null) {
        return controlTotalFailure;
      }
      writeValidationResult(
          context,
          session,
          validatedRecordsPath,
          streamResult.validatedCount(),
          streamResult.loadedCandidateCount());
      if (!recordGovernanceService.withinThreshold(context)) {
        recordGovernanceService.markThresholdExceeded(context);
        deleteQuietly(validatedRecordsPath);
        return ImportStageResult.failure(
            stage(),
            ERR_SKIP_THRESHOLD_EXCEEDED,
            I18N_SKIP_THRESHOLD_EXCEEDED,
            new Object[0],
            MSG_SKIP_THRESHOLD_EXCEEDED,
            objectMapper);
      }
      result = ImportStageResult.success(stage());
      return result;
    } catch (Exception exception) {
      if (validatedRecordsPath != null) {
        deleteQuietly(validatedRecordsPath);
      }
      log.error(
          "validate stage failed: tenantId={}, fileId={}, message={}",
          context.getTenantId(),
          context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
          exception.getMessage(),
          exception);
      return ImportStageResult.failure(
          stage(),
          "IMPORT_VALIDATE_FAILED",
          "error.import.validate.failed",
          new Object[] {exception.getMessage()},
          exception.getMessage(),
          objectMapper);
    }
  }

  private ValidationSession openValidationSession(ImportJobContext context) {
    List<String> schemaFields = stringList(context.getAttributes().get("schemaFields"));
    long totalCount = numberValue(context.getAttributes().get("totalCount"));
    ValidationSession session =
        dataQualityService.beginValidation(context, totalCount, schemaFields);
    dataQualityService.validateDataset(session);
    return session;
  }

  private ImportStageResult processDatasetIssues(
      ImportJobContext context, ValidationSession session) {
    // R-4.3: 数据集级校验阶段 validatedRecordsPath 尚未创建，
    // 超阈值直接返回失败结果即可，不需要 delete（之前代码传 null 进来也是 no-op）。
    for (ValidationIssue issue : session.datasetIssues()) {
      ValidationErrorOutcome outcome =
          recordValidationError(
              context,
              issue.recordNo() == null ? 0L : issue.recordNo(),
              issue.errorCode(),
              issue.errorMessage(),
              issue.rawRecord());
      if (outcome.stop()) {
        return ImportStageResult.failure(
            stage(),
            outcome.errorCode(),
            "error.import.validate.row_invalid",
            new Object[] {outcome.errorMessage()},
            outcome.errorMessage(),
            objectMapper);
      }
      if (!recordGovernanceService.withinThreshold(context)) {
        recordGovernanceService.markThresholdExceeded(context);
        return ImportStageResult.failure(
            stage(),
            ERR_SKIP_THRESHOLD_EXCEEDED,
            I18N_SKIP_THRESHOLD_EXCEEDED,
            new Object[0],
            MSG_SKIP_THRESHOLD_EXCEEDED,
            objectMapper);
      }
    }
    return null;
  }

  /**
   * ADR-041 Phase1.2:控制金额对账。声明总额与累加和不符且 blocker=true 时,记失败记录并以 IMPORT_VALIDATE_CONTROL_TOTAL
   * 失败整个阶段;默认告警(evaluator 内部 log.warn)则返回 null 放行。
   */
  private ImportStageResult processControlTotal(
      ImportJobContext context, ValidationSession session, Path validatedRecordsPath) {
    ValidationIssue issue = controlTotalEvaluator.finalizeCheck(session);
    if (issue == null) {
      return null;
    }
    ValidationErrorOutcome outcome =
        recordValidationError(context, 0L, issue.errorCode(), issue.errorMessage(), null);
    if (outcome.stop()) {
      deleteQuietly(validatedRecordsPath);
      return ImportStageResult.failure(
          stage(),
          outcome.errorCode(),
          "error.import.validate.row_invalid",
          new Object[] {outcome.errorMessage()},
          outcome.errorMessage(),
          objectMapper);
    }
    return null;
  }

  private StreamingValidationResult processValidationBatch(
      ImportJobContext context,
      ValidationSession session,
      Path parsedRecordsPath,
      Path validatedRecordsPath)
      throws Exception {
    int chunkSize = resolveChunkSize(context);
    long recordNo = 0L;
    long validatedCount = 0L;
    long loadedCandidateCount = 0L;
    try (BufferedReader reader =
            Files.newBufferedReader(parsedRecordsPath, StandardCharsets.UTF_8);
        BufferedWriter writer =
            Files.newBufferedWriter(
                validatedRecordsPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
      List<Map<String, Object>> chunk = new ArrayList<>(chunkSize);
      long chunkStartRecordNo = 1L;
      String line;
      while ((line = reader.readLine()) != null) {
        if (!Texts.hasText(line)) {
          continue;
        }
        recordNo++;
        Map<String, Object> row;
        try {
          row = objectMapper.readValue(line, MAP_TYPE);
        } catch (Exception exception) {
          SwallowedExceptionLogger.warn(ValidateStep.class, "catch:Exception", exception);

          ValidationErrorOutcome outcome =
              recordValidationError(
                  context, recordNo, "IMPORT_VALIDATE_TYPE_INVALID", exception.getMessage(), line);
          if (outcome.stop()) {
            return new StreamingValidationResult(
                validatedCount,
                loadedCandidateCount,
                ImportStageResult.failure(
                    stage(),
                    outcome.errorCode(),
                    "error.import.validate.row_invalid",
                    new Object[] {outcome.errorMessage()},
                    outcome.errorMessage(),
                    objectMapper));
          }
          if (!recordGovernanceService.withinThreshold(context)) {
            recordGovernanceService.markThresholdExceeded(context);
            return new StreamingValidationResult(
                validatedCount,
                loadedCandidateCount,
                ImportStageResult.failure(
                    stage(), ERR_SKIP_THRESHOLD_EXCEEDED, MSG_SKIP_THRESHOLD_EXCEEDED));
          }
          continue;
        }
        if (chunk.isEmpty()) {
          chunkStartRecordNo = recordNo;
        }
        chunk.add(row);
        if (chunk.size() >= chunkSize) {
          ChunkProcessResult result =
              processChunk(context, session, chunk, chunkStartRecordNo, writer);
          validatedCount += result.validCount();
          loadedCandidateCount += result.validCount();
          if (!result.success()) {
            return new StreamingValidationResult(
                validatedCount,
                loadedCandidateCount,
                ImportStageResult.failure(
                    stage(),
                    result.errorCode(),
                    "error.import.validate.row_invalid",
                    new Object[] {result.errorMessage()},
                    result.errorMessage(),
                    objectMapper));
          }
          chunk.clear();
        }
      }
      if (!chunk.isEmpty()) {
        ChunkProcessResult result =
            processChunk(context, session, chunk, chunkStartRecordNo, writer);
        validatedCount += result.validCount();
        loadedCandidateCount += result.validCount();
        if (!result.success()) {
          return new StreamingValidationResult(
              validatedCount,
              loadedCandidateCount,
              ImportStageResult.failure(
                  stage(),
                  result.errorCode(),
                  "error.import.validate.row_invalid",
                  new Object[] {result.errorMessage()},
                  result.errorMessage(),
                  objectMapper));
        }
      }
    }
    return new StreamingValidationResult(validatedCount, loadedCandidateCount, null);
  }

  private void writeValidationResult(
      ImportJobContext context,
      ValidationSession session,
      Path validatedRecordsPath,
      long validatedCount,
      long loadedCandidateCount) {
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.VALIDATED_RECORDS_PATH, validatedRecordsPath.toString());
    context.getAttributes().put("validatedCount", validatedCount);
    context.getAttributes().put("qualityChecks", session.appliedChecks());
    context.getAttributes().put("customerPayloadCount", loadedCandidateCount);
    ImportStageSupport.updateFileStatusRecoverAware(
        runtimeRepository,
        context,
        "VALIDATED",
        Map.of(
            "validatedCount", validatedCount,
            "skippedCount", numberValue(context.getAttributes().get("skippedCount")),
            "badRecordCount", badRecordCount(context),
            "manualReviewRequired",
                Boolean.TRUE.equals(context.getAttributes().get("manualReviewRequired")),
            "qualityChecks", session.appliedChecks(),
            "validatedRecordsPath", validatedRecordsPath.toString()));
  }

  private record StreamingValidationResult(
      long validatedCount, long loadedCandidateCount, ImportStageResult failure) {}

  private ChunkProcessResult processChunk(
      ImportJobContext context,
      ValidationSession session,
      List<Map<String, Object>> chunk,
      long chunkStartRecordNo,
      BufferedWriter writer)
      throws Exception {
    // ADR-041 Phase1.2:控制金额对账按到达的全量记录累加(含将被判无效的行——trailer 声明的是上游发出的总额,
    // 对账「数对不对/全不全」,在 finalize 比对前于每 chunk 累加)。
    controlTotalEvaluator.accumulate(session, chunk);
    Map<Long, ValidationIssue> issues =
        dataQualityService.validateChunkRows(session, chunk, chunkStartRecordNo);
    long validCount = 0L;
    for (int index = 0; index < chunk.size(); index++) {
      long recordNo = chunkStartRecordNo + index;
      ValidationIssue issue = issues.get(recordNo);
      if (issue != null) {
        ValidationErrorOutcome outcome =
            recordValidationError(
                context, recordNo, issue.errorCode(), issue.errorMessage(), issue.rawRecord());
        if (outcome.stop()) {
          return ChunkProcessResult.failure(
              outcome.errorCode(), outcome.errorMessage(), validCount);
        }
        if (!recordGovernanceService.withinThreshold(context)) {
          recordGovernanceService.markThresholdExceeded(context);
          return ChunkProcessResult.failure(
              ERR_SKIP_THRESHOLD_EXCEEDED, MSG_SKIP_THRESHOLD_EXCEEDED, validCount);
        }
        continue;
      }
      writer.write(objectMapper.writeValueAsString(chunk.get(index)));
      writer.newLine();
      validCount++;
    }
    return ChunkProcessResult.success(validCount);
  }

  /**
   * P1: 改为返回 {@link ValidationErrorOutcome},不再用 IllegalStateException 控流。 调用方根据 shouldStop()
   * 决定是否提前返回 ChunkProcessResult.failure(...)。
   *
   * <p>旧实现 throw → processValidationBatch 外层 catch(Exception) → 抹成 IMPORT_VALIDATE_FAILED 失败码,丢失了
   * "skip threshold exceeded" / "FAIL_BATCH" 语义。
   */
  private ValidationErrorOutcome recordValidationError(
      ImportJobContext context,
      long recordNo,
      String errorCode,
      String errorMessage,
      Object rawRecord) {
    if (!recordGovernanceService.isSkippable(errorCode)) {
      recordGovernanceService.recordFailedRecord(
          context, stage(), recordNo, errorCode, errorMessage, rawRecord);
      return ValidationErrorOutcome.stop(errorCode, errorMessage);
    }
    recordGovernanceService.recordSkippedRecord(
        context, stage(), recordNo, errorCode, errorMessage, rawRecord);
    if (recordGovernanceService.shouldFailOnSkip(errorCode)) {
      return ValidationErrorOutcome.stop(errorCode, "skip action FAIL_BATCH");
    }
    return ValidationErrorOutcome.CONTINUE;
  }

  /** P1: 校验错误处置结果,替代原 IllegalStateException 控流。 */
  private record ValidationErrorOutcome(boolean stop, String errorCode, String errorMessage) {
    static final ValidationErrorOutcome CONTINUE = new ValidationErrorOutcome(false, null, null);

    static ValidationErrorOutcome stop(String errorCode, String errorMessage) {
      return new ValidationErrorOutcome(true, errorCode, errorMessage);
    }
  }

  private long badRecordCount(ImportJobContext context) {
    Object value = context.getAttributes().get("badRecords");
    if (value instanceof List<?> list) {
      return list.size();
    }
    return 0L;
  }

  private long numberValue(Object value) {
    return ImportStageSupport.numberValue(value);
  }

  private List<String> stringList(Object value) {
    if (value instanceof List<?> list) {
      List<String> result = new ArrayList<>();
      for (Object item : list) {
        if (item != null && Texts.hasText(String.valueOf(item))) {
          result.add(String.valueOf(item));
        }
      }
      return result;
    }
    return List.of();
  }

  private String stringValue(Object value) {
    return ImportStageSupport.stringValue(value);
  }

  private int resolveChunkSize(ImportJobContext context) {
    return ImportStageSupport.resolveChunkSize(context, workerConfiguration);
  }

  private Path createValidatedFile(ImportJobContext context) throws Exception {
    return ImportStageSupport.createValidatedFile(context);
  }

  private void deleteQuietly(Path path) {
    ImportStageSupport.deleteQuietly(path);
  }

  private record ChunkProcessResult(
      boolean success, String errorCode, String errorMessage, long validCount) {
    static ChunkProcessResult success(long validCount) {
      return new ChunkProcessResult(true, null, null, validCount);
    }

    static ChunkProcessResult failure(String errorCode, String errorMessage, long validCount) {
      return new ChunkProcessResult(false, errorCode, errorMessage, validCount);
    }
  }
}
