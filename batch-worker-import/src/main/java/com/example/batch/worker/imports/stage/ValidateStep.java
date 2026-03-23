package com.example.batch.worker.imports.stage;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.infrastructure.ImportDataQualityService;
import com.example.batch.worker.imports.infrastructure.ImportDataQualityService.ValidationIssue;
import com.example.batch.worker.imports.infrastructure.ImportDataQualityService.ValidationSession;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
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
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class ValidateStep implements ImportStageStep {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final PlatformFileRuntimeRepository runtimeRepository;
    private final ImportRecordGovernanceService recordGovernanceService;
    private final ImportDataQualityService dataQualityService;
    private final ImportWorkerConfiguration workerConfiguration;
    private final ObjectMapper objectMapper;

    @Override
    public ImportStage stage() {
        return ImportStage.VALIDATE;
    }

    @Override
    public ImportStageResult execute(ImportJobContext context) {
        String parsedRecordsPath = stringValue(context.getAttributes().get(PipelineRuntimeKeys.PARSED_RECORDS_PATH));
        if (!StringUtils.hasText(parsedRecordsPath)) {
            return ImportStageResult.failure(stage(), "IMPORT_VALIDATE_NO_STREAM", "parsed records path missing");
        }
        return executeStreaming(context, Path.of(parsedRecordsPath));
    }

    private ImportStageResult executeStreaming(ImportJobContext context, Path parsedRecordsPath) {
        if (!Files.exists(parsedRecordsPath)) {
            return ImportStageResult.failure(stage(), "IMPORT_VALIDATE_NO_STREAM", "parsed records file missing");
        }
        Path validatedRecordsPath = null;
        try {
            List<String> schemaFields = stringList(context.getAttributes().get("schemaFields"));
            long totalCount = numberValue(context.getAttributes().get("totalCount"));
            ValidationSession session = dataQualityService.beginValidation(context, totalCount, schemaFields);
            dataQualityService.validateDataset(session);
            for (ValidationIssue issue : session.datasetIssues()) {
                recordValidationError(context, issue.recordNo() == null ? 0L : issue.recordNo(), issue.errorCode(), issue.errorMessage(), issue.rawRecord());
                if (!recordGovernanceService.withinThreshold(context)) {
                    return failStreaming(context, validatedRecordsPath, "IMPORT_SKIP_THRESHOLD_EXCEEDED", "skip threshold exceeded");
                }
            }
            validatedRecordsPath = createValidatedFile(context);
            int chunkSize = resolveChunkSize(context);
            long recordNo = 0L;
            long validatedCount = 0L;
            long loadedCandidateCount = 0L;
            try (BufferedReader reader = Files.newBufferedReader(parsedRecordsPath, StandardCharsets.UTF_8);
                 BufferedWriter writer = Files.newBufferedWriter(validatedRecordsPath, StandardCharsets.UTF_8,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                List<Map<String, Object>> chunk = new ArrayList<>(chunkSize);
                long chunkStartRecordNo = 1L;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!StringUtils.hasText(line)) {
                        continue;
                    }
                    recordNo++;
                    Map<String, Object> row;
                    try {
                        row = objectMapper.readValue(line, MAP_TYPE);
                    } catch (Exception exception) {
                        recordValidationError(context, recordNo, "IMPORT_VALIDATE_TYPE_INVALID", exception.getMessage(), line);
                        if (!recordGovernanceService.withinThreshold(context)) {
                            return failStreaming(context, validatedRecordsPath, "IMPORT_SKIP_THRESHOLD_EXCEEDED", "skip threshold exceeded");
                        }
                        continue;
                    }
                    if (chunk.isEmpty()) {
                        chunkStartRecordNo = recordNo;
                    }
                    chunk.add(row);
                    if (chunk.size() >= chunkSize) {
                        ChunkProcessResult result = processChunk(context, session, chunk, chunkStartRecordNo, writer);
                        validatedCount += result.validCount();
                        loadedCandidateCount += result.validCount();
                        if (!result.success()) {
                            return failStreaming(context, validatedRecordsPath, result.errorCode(), result.errorMessage());
                        }
                        chunk.clear();
                    }
                }
                if (!chunk.isEmpty()) {
                    ChunkProcessResult result = processChunk(context, session, chunk, chunkStartRecordNo, writer);
                    validatedCount += result.validCount();
                    loadedCandidateCount += result.validCount();
                    if (!result.success()) {
                        return failStreaming(context, validatedRecordsPath, result.errorCode(), result.errorMessage());
                    }
                }
            }
            context.getAttributes().put(PipelineRuntimeKeys.VALIDATED_RECORDS_PATH, validatedRecordsPath.toString());
            context.getAttributes().put("validatedCount", validatedCount);
            context.getAttributes().put("qualityChecks", session.appliedChecks());
            context.getAttributes().put("customerPayloadCount", loadedCandidateCount);
            if (!recordGovernanceService.withinThreshold(context)) {
                return failStreaming(context, validatedRecordsPath, "IMPORT_SKIP_THRESHOLD_EXCEEDED", "skip threshold exceeded");
            }
            runtimeRepository.updateFileStatus(
                    runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
                    "VALIDATED",
                    Map.of(
                            "validatedCount", validatedCount,
                            "skippedCount", numberValue(context.getAttributes().get("skippedCount")),
                            "badRecordCount", badRecordCount(context),
                            "manualReviewRequired", Boolean.TRUE.equals(context.getAttributes().get("manualReviewRequired")),
                            "qualityChecks", session.appliedChecks(),
                            "validatedRecordsPath", validatedRecordsPath.toString()
                    )
            );
            return ImportStageResult.success(stage());
        } catch (Exception exception) {
            if (validatedRecordsPath != null) {
                deleteQuietly(validatedRecordsPath);
            }
            log.error("validate stage failed: tenantId={}, fileId={}, message={}",
                    context.getTenantId(),
                    context.getAttributes().get(PipelineRuntimeKeys.FILE_ID),
                    exception.getMessage(),
                    exception);
            return ImportStageResult.failure(stage(), "IMPORT_VALIDATE_FAILED", exception.getMessage());
        }
    }

    private ChunkProcessResult processChunk(ImportJobContext context,
                                            ValidationSession session,
                                            List<Map<String, Object>> chunk,
                                            long chunkStartRecordNo,
                                            BufferedWriter writer) throws Exception {
        Map<Long, ValidationIssue> issues = dataQualityService.validateChunkRows(session, chunk, chunkStartRecordNo);
        long validCount = 0L;
        for (int index = 0; index < chunk.size(); index++) {
            long recordNo = chunkStartRecordNo + index;
            ValidationIssue issue = issues.get(recordNo);
            if (issue != null) {
                recordValidationError(context, recordNo, issue.errorCode(), issue.errorMessage(), issue.rawRecord());
                if (!recordGovernanceService.withinThreshold(context)) {
                    return ChunkProcessResult.failure("IMPORT_SKIP_THRESHOLD_EXCEEDED", "skip threshold exceeded", validCount);
                }
                continue;
            }
            writer.write(objectMapper.writeValueAsString(chunk.get(index)));
            writer.newLine();
            validCount++;
        }
        return ChunkProcessResult.success(validCount);
    }

    private ImportStageResult failStreaming(ImportJobContext context, Path validatedRecordsPath, String errorCode, String errorMessage) {
        deleteQuietly(validatedRecordsPath);
        if (StringUtils.hasText(errorCode) && StringUtils.hasText(errorMessage)) {
            return ImportStageResult.failure(stage(), errorCode, errorMessage);
        }
        return ImportStageResult.failure(stage(), "IMPORT_VALIDATE_FAILED", errorMessage);
    }

    private void recordValidationError(ImportJobContext context, long recordNo, String errorCode, String errorMessage, Object rawRecord) {
        if (!recordGovernanceService.isSkippable(errorCode)) {
            recordGovernanceService.recordFailedRecord(context, stage(), recordNo, errorCode, errorMessage, rawRecord);
            throw new IllegalStateException(errorMessage);
        }
        recordGovernanceService.recordSkippedRecord(context, stage(), recordNo, errorCode, errorMessage, rawRecord);
        if (recordGovernanceService.shouldFailOnSkip(errorCode)) {
            throw new IllegalStateException("skip action FAIL_BATCH");
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
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        String text = String.valueOf(value);
        if (!StringUtils.hasText(text)) {
            return 0L;
        }
        return Long.parseLong(text);
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return List.of();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) && !"null".equalsIgnoreCase(text) ? text : null;
    }

    private int resolveChunkSize(ImportJobContext context) {
        int fallback = workerConfiguration == null ? 500 : workerConfiguration.chunkSize();
        Object templateConfigObject = context == null ? null : context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
        if (templateConfigObject instanceof Map<?, ?> templateConfig) {
            Object value = templateConfig.get("chunk_size");
            if (value instanceof Number number) {
                return Math.max(1, number.intValue());
            }
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return Math.max(1, Integer.parseInt(String.valueOf(value)));
            }
        }
        return Math.max(1, fallback);
    }

    private Path createValidatedFile(ImportJobContext context) throws Exception {
        String fileId = context == null ? "unknown" : String.valueOf(context.getFileId());
        String workerId = context == null ? "worker" : String.valueOf(context.getWorkerId());
        return Files.createTempFile(BatchFileConstants.validatedStagePrefix(fileId, workerId), BatchFileConstants.NDJSON_SUFFIX);
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ex) {
            log.warn("Failed to delete temp file: {}", path, ex);
        }
    }

    private record ChunkProcessResult(boolean success, String errorCode, String errorMessage, long validCount) {
        static ChunkProcessResult success(long validCount) {
            return new ChunkProcessResult(true, null, null, validCount);
        }

        static ChunkProcessResult failure(String errorCode, String errorMessage, long validCount) {
            return new ChunkProcessResult(false, errorCode, errorMessage, validCount);
        }
    }
}
