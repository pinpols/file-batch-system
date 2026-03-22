package com.example.batch.worker.exports.infrastructure;

import com.example.batch.worker.exports.mapper.business.SettlementExportMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class SettlementExportRepository {

    private final SettlementExportMapper settlementExportMapper;

    public Map<String, Object> loadBatch(String tenantId, String batchNo) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(batchNo)) {
            return Map.of();
        }
        Map<String, Object> batch = settlementExportMapper.selectBatch(params("tenantId", tenantId, "batchNo", batchNo));
        return batch == null ? Map.of() : batch;
    }

    public List<Map<String, Object>> loadDetailsByBatchIdAfterId(String tenantId, Long batchId, Long afterId, int pageSize) {
        if (!StringUtils.hasText(tenantId) || batchId == null || pageSize <= 0) {
            return List.of();
        }
        return settlementExportMapper.selectDetailsByBatchIdAfterId(params(
                "tenantId", tenantId,
                "batchId", batchId,
                "afterId", afterId,
                "pageSize", pageSize
        ));
    }

    public int markBatchExported(String tenantId, Long batchId) {
        if (!StringUtils.hasText(tenantId) || batchId == null) {
            return 0;
        }
        return settlementExportMapper.updateBatchExported(params("tenantId", tenantId, "batchId", batchId));
    }

    public int markDetailsExported(String tenantId, Long batchId, int exportVersion, String traceId) {
        if (!StringUtils.hasText(tenantId) || batchId == null) {
            return 0;
        }
        return settlementExportMapper.updateDetailsExported(params(
                "tenantId", tenantId,
                "batchId", batchId,
                "exportVersion", exportVersion,
                "traceId", traceId
        ));
    }

    private Map<String, Object> params(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return values;
    }
}
