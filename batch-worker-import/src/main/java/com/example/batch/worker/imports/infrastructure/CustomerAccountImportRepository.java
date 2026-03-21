package com.example.batch.worker.imports.infrastructure;

import com.example.batch.worker.imports.domain.CustomerImportPayload;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;

@Repository
public class CustomerAccountImportRepository {

    private final JdbcTemplate businessJdbcTemplate;

    public CustomerAccountImportRepository(@Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate) {
        this.businessJdbcTemplate = businessJdbcTemplate;
    }

    public int upsert(String tenantId, String sourceFileName, String sourceBatchNo, String sourceTraceId, CustomerImportPayload payload) {
        if (!StringUtils.hasText(tenantId) || payload == null || !StringUtils.hasText(payload.customerNo())) {
            return 0;
        }
        String sql = """
                insert into biz.customer_account (
                    tenant_id,
                    customer_no,
                    customer_name,
                    customer_type,
                    certificate_no,
                    mobile_no,
                    email,
                    status,
                    source_file_name,
                    source_batch_no,
                    source_trace_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, customer_no) do update set
                    customer_name = excluded.customer_name,
                    customer_type = excluded.customer_type,
                    certificate_no = excluded.certificate_no,
                    mobile_no = excluded.mobile_no,
                    email = excluded.email,
                    status = excluded.status,
                    source_file_name = excluded.source_file_name,
                    source_batch_no = excluded.source_batch_no,
                    source_trace_id = excluded.source_trace_id,
                    updated_at = current_timestamp
                """;
        return businessJdbcTemplate.update(sql,
                tenantId,
                payload.customerNo(),
                payload.customerName(),
                payload.customerType(),
                payload.certificateNo(),
                payload.mobileNo(),
                payload.email(),
                normalizeStatus(payload.status()),
                sourceFileName,
                sourceBatchNo,
                sourceTraceId
        );
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
}
