package com.example.batch.worker.imports.stage;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.infrastructure.ImportDataQualityService;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class ValidateStep implements ImportStageStep {

    private final PlatformFileRuntimeRepository runtimeRepository;
    private final ImportRecordGovernanceService recordGovernanceService;
    private final ImportDataQualityService dataQualityService;

    @Override
    public ImportStage stage() {
        return ImportStage.VALIDATE;
    }

    @Override
    public ImportStageResult execute(ImportJobContext context) {
        Object payload = context == null ? null : context.getAttributes().get("customerPayloads");
        if (!(payload instanceof List<?> payloads) || payloads.isEmpty()) {
            if (numberValue(context.getAttributes().get("skippedCount")) > 0) {
                runtimeRepository.updateFileStatus(
                        runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
                        "VALIDATED",
                        Map.of(
                                "validatedCount", 0,
                                "skippedCount", numberValue(context.getAttributes().get("skippedCount")),
                                "badRecordCount", badRecordCount(context)
                        )
                );
                return ImportStageResult.success(stage());
            }
            return ImportStageResult.failure(stage(), "IMPORT_VALIDATE_NO_PAYLOAD", "customer payload missing");
        }
        @SuppressWarnings("unchecked")
        List<CustomerImportPayload> customerPayloads = (List<CustomerImportPayload>) payloads;
        try {
            ImportDataQualityService.ValidationOutcome validationOutcome = dataQualityService.validate(context, customerPayloads);
            for (ImportDataQualityService.ValidationIssue issue : validationOutcome.datasetIssues()) {
                recordValidationError(context, issue.recordNo(), issue.errorCode(), issue.errorMessage(), issue.rawRecord());
                if (!recordGovernanceService.withinThreshold(context)) {
                    return ImportStageResult.failure(stage(), "IMPORT_SKIP_THRESHOLD_EXCEEDED", "skip threshold exceeded");
                }
            }
            List<CustomerImportPayload> validPayloads = new ArrayList<>();
            int validCount = 0;
            for (int index = 0; index < payloads.size(); index++) {
                Object item = payloads.get(index);
                if (!(item instanceof CustomerImportPayload customerPayload)) {
                    recordValidationError(context, index + 1L, "IMPORT_VALIDATE_TYPE", "customer payload type invalid", item);
                    if (!recordGovernanceService.withinThreshold(context)) {
                        return ImportStageResult.failure(stage(), "IMPORT_SKIP_THRESHOLD_EXCEEDED", "skip threshold exceeded");
                    }
                    continue;
                }
                ImportDataQualityService.ValidationIssue issue = validationOutcome.recordIssues().get(index + 1L);
                if (issue != null) {
                    recordValidationError(context, issue.recordNo(), issue.errorCode(), issue.errorMessage(), issue.rawRecord());
                    if (!recordGovernanceService.withinThreshold(context)) {
                        return ImportStageResult.failure(stage(), "IMPORT_SKIP_THRESHOLD_EXCEEDED", "skip threshold exceeded");
                    }
                    continue;
                }
                validPayloads.add(customerPayload);
                validCount++;
            }
            context.getAttributes().put("customerPayloads", validPayloads);
            context.getAttributes().put("validatedCount", validCount);
            context.getAttributes().put("qualityChecks", validationOutcome.appliedChecks());
            if (!recordGovernanceService.withinThreshold(context)) {
                return ImportStageResult.failure(stage(), "IMPORT_SKIP_THRESHOLD_EXCEEDED", "skip threshold exceeded");
            }
            runtimeRepository.updateFileStatus(
                    runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
                    "VALIDATED",
                    Map.of(
                            "validatedCount", validCount,
                            "skippedCount", numberValue(context.getAttributes().get("skippedCount")),
                            "badRecordCount", badRecordCount(context),
                            "manualReviewRequired", Boolean.TRUE.equals(context.getAttributes().get("manualReviewRequired")),
                            "qualityChecks", validationOutcome.appliedChecks()
                    )
            );
            return ImportStageResult.success(stage());
        } catch (IllegalStateException exception) {
            String errorCode = StringUtils.hasText(stringValue(context.getAttributes().get("lastErrorCode")))
                    ? stringValue(context.getAttributes().get("lastErrorCode"))
                    : "IMPORT_VALIDATE_FAILED";
            String errorMessage = StringUtils.hasText(stringValue(context.getAttributes().get("lastErrorMessage")))
                    ? stringValue(context.getAttributes().get("lastErrorMessage"))
                    : exception.getMessage();
            return ImportStageResult.failure(stage(), errorCode, errorMessage);
        }
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

    private String resolveValidationMessage(String errorCode, CustomerImportPayload payload) {
        return switch (errorCode) {
            case "IMPORT_VALIDATE_REQUIRED" -> "customerNo/customerName required";
            case "IMPORT_VALIDATE_TYPE_INVALID" -> "invalid customerType: " + payload.customerType();
            case "IMPORT_VALIDATE_STATUS_INVALID" -> "invalid status: " + payload.status();
            default -> errorCode;
        };
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
