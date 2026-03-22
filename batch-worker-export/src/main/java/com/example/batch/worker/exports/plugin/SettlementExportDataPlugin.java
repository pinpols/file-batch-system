package com.example.batch.worker.exports.plugin;

import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.common.plugin.WorkerPluginIds;
import com.example.batch.worker.exports.infrastructure.SettlementExportRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Default export data plugin: {@code biz.settlement_batch} / {@code biz.settlement_detail}.
 */
@Component
@RequiredArgsConstructor
public class SettlementExportDataPlugin implements ExportDataPlugin {

    private final SettlementExportRepository settlementExportRepository;

    @Override
    public String id() {
        return WorkerPluginIds.EXPORT_DATA_SETTLEMENT;
    }

    @Override
    public Map<String, Object> loadBatch(ExportDataContext context) {
        return settlementExportRepository.loadBatch(context.tenantId(), context.batchNo());
    }

    @Override
    public DetailPage loadDetailPage(ExportDataContext context, Long batchId, int pageSize, Object cursor) {
        Long afterId = cursor instanceof Number number ? number.longValue() : null;
        List<Map<String, Object>> rows = settlementExportRepository.loadDetailsByBatchIdAfterId(context.tenantId(), batchId, afterId, pageSize);
        if (rows.isEmpty()) {
            return DetailPage.empty();
        }
        Object nextCursor = rows.get(rows.size() - 1).get("id");
        return new DetailPage(rows, nextCursor);
    }

    @Override
    public List<DelimitedColumn> describeDelimitedColumns(ExportDataContext context, Map<String, Object> batch) {
        return List.of(
                new DelimitedColumn("batchNo", "batch.batch_no"),
                new DelimitedColumn("bizDate", "batch.biz_date"),
                new DelimitedColumn("settlementNo", "detail.settlement_no"),
                new DelimitedColumn("customerNo", "detail.customer_no"),
                new DelimitedColumn("grossAmount", "detail.gross_amount"),
                new DelimitedColumn("feeAmount", "detail.fee_amount"),
                new DelimitedColumn("netAmount", "detail.net_amount"),
                new DelimitedColumn("currency", "detail.currency"),
                new DelimitedColumn("status", "detail.settlement_status")
        );
    }
}
