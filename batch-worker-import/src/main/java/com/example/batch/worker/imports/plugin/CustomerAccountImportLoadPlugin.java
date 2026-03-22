package com.example.batch.worker.imports.plugin;

import com.example.batch.common.plugin.ImportLoadContext;
import com.example.batch.common.plugin.ImportLoadPlugin;
import com.example.batch.common.plugin.WorkerPluginIds;
import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.example.batch.worker.imports.infrastructure.CustomerAccountImportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Default LOAD plugin: persists to {@code biz.customer_account} via existing mapper.
 */
@Component
@RequiredArgsConstructor
public class CustomerAccountImportLoadPlugin implements ImportLoadPlugin {

    private final CustomerAccountImportRepository customerAccountImportRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String id() {
        return WorkerPluginIds.IMPORT_LOAD_CUSTOMER_ACCOUNT;
    }

    @Override
    public int loadChunk(ImportLoadContext context, List<Map<String, Object>> records) throws Exception {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        List<CustomerImportPayload> payloads = new ArrayList<>(records.size());
        for (Map<String, Object> row : records) {
            payloads.add(objectMapper.convertValue(row, CustomerImportPayload.class));
        }
        return customerAccountImportRepository.upsertBatch(
                context.tenantId(),
                context.sourceFileName(),
                context.batchNo(),
                context.traceId(),
                payloads
        );
    }
}
