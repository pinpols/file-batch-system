package com.example.batch.worker.exports.stage;

import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.infrastructure.SettlementExportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GenerateStep implements ExportStageStep {

    private final SettlementExportRepository settlementExportRepository;
    private final ObjectMapper objectMapper;

    public GenerateStep(SettlementExportRepository settlementExportRepository, ObjectMapper objectMapper) {
        this.settlementExportRepository = settlementExportRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExportStage stage() {
        return ExportStage.GENERATE;
    }

    @Override
    public ExportStageResult execute(ExportJobContext context) {
        Object payload = context == null ? null : context.getAttributes().get("exportPayload");
        if (!(payload instanceof ExportPayload exportPayload) || !StringUtils.hasText(exportPayload.batchNo())) {
            return ExportStageResult.failure(stage(), "EXPORT_GENERATE_NO_PAYLOAD", "export payload missing");
        }
        Map<String, Object> batch = settlementExportRepository.loadBatch(context.getTenantId(), exportPayload.batchNo());
        if (batch.isEmpty()) {
            return ExportStageResult.failure(stage(), "EXPORT_BATCH_NOT_FOUND", "settlement batch not found");
        }
        Object batchId = batch.get("id");
        List<Map<String, Object>> details = settlementExportRepository.loadDetailsByBatchId(
                context.getTenantId(),
                batchId == null ? null : Long.valueOf(String.valueOf(batchId))
        );
        String fileFormatType = String.valueOf(context.getAttributes().getOrDefault("exportFileFormatType", "JSON"));
        String generatedContent = generateContent(fileFormatType, batch, details);
        context.getAttributes().put("exportBatch", batch);
        context.getAttributes().put("exportDetails", details);
        context.getAttributes().put("generatedContent", generatedContent);
        context.getAttributes().put("recordCount", details.size());
        context.getAttributes().put("totalAmount", batch.getOrDefault("total_amount", BigDecimal.ZERO));
        context.getAttributes().put("fileSizeBytes", generatedContent.getBytes(StandardCharsets.UTF_8).length);
        return ExportStageResult.success(stage());
    }

    private String generateContent(String fileFormatType,
                                   Map<String, Object> batch,
                                   List<Map<String, Object>> details) {
        try {
            if ("DELIMITED".equalsIgnoreCase(fileFormatType)) {
                return generateDelimited(batch, details);
            }
            return objectMapper.writeValueAsString(Map.of(
                    "batch", batch,
                    "details", details
            ));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to generate export content", ex);
        }
    }

    private String generateDelimited(Map<String, Object> batch, List<Map<String, Object>> details) {
        StringBuilder builder = new StringBuilder();
        builder.append("batchNo,bizDate,settlementNo,customerNo,grossAmount,feeAmount,netAmount,currency,status").append('\n');
        String batchNo = String.valueOf(batch.getOrDefault("batch_no", ""));
        String bizDate = String.valueOf(batch.getOrDefault("biz_date", ""));
        for (Map<String, Object> detail : details) {
            StringJoiner joiner = new StringJoiner(",");
            joiner.add(csv(batchNo));
            joiner.add(csv(bizDate));
            joiner.add(csv(detail.get("settlement_no")));
            joiner.add(csv(detail.get("customer_no")));
            joiner.add(csv(detail.get("gross_amount")));
            joiner.add(csv(detail.get("fee_amount")));
            joiner.add(csv(detail.get("net_amount")));
            joiner.add(csv(detail.get("currency")));
            joiner.add(csv(detail.get("settlement_status")));
            builder.append(joiner).append('\n');
        }
        return builder.toString();
    }

    private String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
