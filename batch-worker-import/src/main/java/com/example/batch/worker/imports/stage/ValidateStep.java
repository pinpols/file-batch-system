package com.example.batch.worker.imports.stage;

import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ValidateStep implements ImportStageStep {

    private final PlatformFileRuntimeRepository runtimeRepository;

    public ValidateStep(PlatformFileRuntimeRepository runtimeRepository) {
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public ImportStage stage() {
        return ImportStage.VALIDATE;
    }

    @Override
    public ImportStageResult execute(ImportJobContext context) {
        Object payload = context == null ? null : context.getAttributes().get("customerPayloads");
        if (!(payload instanceof List<?> payloads) || payloads.isEmpty()) {
            return ImportStageResult.failure(stage(), "IMPORT_VALIDATE_NO_PAYLOAD", "customer payload missing");
        }
        Set<String> customerNos = new HashSet<>();
        int validCount = 0;
        for (Object item : payloads) {
            if (!(item instanceof CustomerImportPayload customerPayload)) {
                return ImportStageResult.failure(stage(), "IMPORT_VALIDATE_TYPE", "customer payload type invalid");
            }
            if (!StringUtils.hasText(customerPayload.customerNo()) || !StringUtils.hasText(customerPayload.customerName())) {
                return ImportStageResult.failure(stage(), "IMPORT_VALIDATE_REQUIRED", "customerNo/customerName required");
            }
            if (!customerNos.add(customerPayload.customerNo())) {
                return ImportStageResult.failure(stage(), "IMPORT_VALIDATE_DUPLICATE", "duplicate customerNo: " + customerPayload.customerNo());
            }
            if (StringUtils.hasText(customerPayload.customerType())
                    && !"PERSONAL".equalsIgnoreCase(customerPayload.customerType())
                    && !"ENTERPRISE".equalsIgnoreCase(customerPayload.customerType())) {
                return ImportStageResult.failure(stage(), "IMPORT_VALIDATE_TYPE_INVALID", "invalid customerType: " + customerPayload.customerType());
            }
            if (StringUtils.hasText(customerPayload.status())
                    && !"ACTIVE".equalsIgnoreCase(customerPayload.status())
                    && !"INACTIVE".equalsIgnoreCase(customerPayload.status())
                    && !"FROZEN".equalsIgnoreCase(customerPayload.status())) {
                return ImportStageResult.failure(stage(), "IMPORT_VALIDATE_STATUS_INVALID", "invalid status: " + customerPayload.status());
            }
            validCount++;
        }
        context.getAttributes().put("validatedCount", validCount);
        runtimeRepository.updateFileStatus(
                runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
                "VALIDATED",
                Map.of("validatedCount", validCount)
        );
        return ImportStageResult.success(stage());
    }
}
