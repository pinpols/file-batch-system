package com.example.batch.worker.imports.infrastructure;

import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.example.batch.worker.imports.mapper.business.CustomerAccountImportMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class CustomerAccountImportRepository {

    private final CustomerAccountImportMapper customerAccountImportMapper;

    public int upsert(String tenantId, String sourceFileName, String sourceBatchNo, String sourceTraceId, CustomerImportPayload payload) {
        if (!StringUtils.hasText(tenantId) || payload == null || !StringUtils.hasText(payload.customerNo())) {
            return 0;
        }
        return customerAccountImportMapper.upsertCustomerAccount(params(
                "tenantId", tenantId,
                "customerNo", payload.customerNo(),
                "customerName", payload.customerName(),
                "customerType", payload.customerType(),
                "certificateNo", payload.certificateNo(),
                "mobileNo", payload.mobileNo(),
                "email", payload.email(),
                "status", normalizeStatus(payload.status()),
                "sourceFileName", sourceFileName,
                "sourceBatchNo", sourceBatchNo,
                "sourceTraceId", sourceTraceId
        ));
    }

    public int upsertBatch(String tenantId,
                           String sourceFileName,
                           String sourceBatchNo,
                           String sourceTraceId,
                           List<CustomerImportPayload> payloads) {
        if (!StringUtils.hasText(tenantId) || payloads == null || payloads.isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (CustomerImportPayload payload : payloads) {
            updated += upsert(tenantId, sourceFileName, sourceBatchNo, sourceTraceId, payload);
        }
        return updated;
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status : "ACTIVE";
    }

    private Map<String, Object> params(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return values;
    }
}
