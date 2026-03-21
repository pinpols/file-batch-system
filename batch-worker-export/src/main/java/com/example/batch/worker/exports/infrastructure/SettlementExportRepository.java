package com.example.batch.worker.exports.infrastructure;

import com.example.batch.worker.exports.config.BusinessDataSourceProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class SettlementExportRepository {

    private final JdbcTemplate businessJdbcTemplate;

    public SettlementExportRepository(@Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate) {
        this.businessJdbcTemplate = businessJdbcTemplate;
    }

    public Map<String, Object> loadBatch(String tenantId, String batchNo) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(batchNo)) {
            return Map.of();
        }
        List<Map<String, Object>> rows = businessJdbcTemplate.queryForList(
                "select * from biz.settlement_batch where tenant_id = ? and batch_no = ?",
                tenantId, batchNo
        );
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public List<Map<String, Object>> loadDetailsByBatchId(String tenantId, Long batchId) {
        if (!StringUtils.hasText(tenantId) || batchId == null) {
            return List.of();
        }
        return businessJdbcTemplate.queryForList(
                "select * from biz.settlement_detail where tenant_id = ? and batch_id = ? order by id asc",
                tenantId, batchId
        );
    }

    public int markBatchExported(String tenantId, Long batchId) {
        if (!StringUtils.hasText(tenantId) || batchId == null) {
            return 0;
        }
        return businessJdbcTemplate.update("""
                update biz.settlement_batch
                set batch_status = 'EXPORTED',
                    updated_at = current_timestamp
                where tenant_id = ? and id = ?
                """,
                tenantId,
                batchId
        );
    }

    public int markDetailsExported(String tenantId, Long batchId, int exportVersion, String traceId) {
        if (!StringUtils.hasText(tenantId) || batchId == null) {
            return 0;
        }
        return businessJdbcTemplate.update("""
                update biz.settlement_detail
                set exported_version = greatest(exported_version, ?),
                    settlement_status = case
                        when settlement_status in ('READY', 'SETTLED') then 'EXPORTED'
                        else settlement_status
                    end,
                    source_trace_id = coalesce(?, source_trace_id),
                    updated_at = current_timestamp
                where tenant_id = ? and batch_id = ?
                """,
                exportVersion,
                traceId,
                tenantId,
                batchId
        );
    }
}
