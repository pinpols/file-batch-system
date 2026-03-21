package com.example.batch.worker.exports.stage;

import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PrepareStep implements ExportStageStep {

    private final ObjectMapper objectMapper;
    private final PlatformFileRuntimeRepository runtimeRepository;

    public PrepareStep(ObjectMapper objectMapper, PlatformFileRuntimeRepository runtimeRepository) {
        this.objectMapper = objectMapper;
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public ExportStage stage() {
        return ExportStage.PREPARE;
    }

    @Override
    public ExportStageResult execute(ExportJobContext context) {
        if (context == null || !StringUtils.hasText(context.getTenantId()) || !StringUtils.hasText(context.getRawPayload())) {
            return ExportStageResult.failure(stage(), "EXPORT_PREPARE_INVALID", "tenantId or payload is blank");
        }
        try {
            ExportPayload payload = context.getAttributes().get("exportPayload") instanceof ExportPayload exportPayload
                    ? exportPayload
                    : objectMapper.readValue(context.getRawPayload(), ExportPayload.class);
            context.getAttributes().put("exportPayload", payload);
            Map<String, Object> templateConfig = Map.of();
            if (StringUtils.hasText(payload.templateCode())) {
                templateConfig = runtimeRepository.loadLatestTemplateConfig(context.getTenantId(), payload.templateCode(), "EXPORT");
                if (!templateConfig.isEmpty()) {
                    context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig);
                }
            }
            String fileFormatType = resolveText(templateConfig.get("file_format_type"), "JSON");
            String fileName = resolveFileName(context, payload, templateConfig, fileFormatType);
            String finalObjectName = resolveObjectName(context, payload, fileName);
            String tempObjectName = "tmp/" + context.getTenantId() + "/" + resolveBizDate(context, payload) + "/" + fileName + ".part";
            context.getAttributes().put("fileName", fileName);
            context.getAttributes().put("exportFileFormatType", fileFormatType);
            context.getAttributes().put("objectName", finalObjectName);
            context.getAttributes().put("tempObjectName", tempObjectName);
        } catch (Exception ex) {
            return ExportStageResult.failure(stage(), "EXPORT_PREPARE_PARSE_FAILED", ex.getMessage());
        }
        return ExportStageResult.success(stage());
    }

    private String resolveFileName(ExportJobContext context,
                                   ExportPayload payload,
                                   Map<String, Object> templateConfig,
                                   String fileFormatType) {
        if (StringUtils.hasText(payload.fileName())) {
            return payload.fileName();
        }
        String namingRule = templateConfig.get("naming_rule") == null ? null : String.valueOf(templateConfig.get("naming_rule"));
        String bizDate = resolveBizDate(context, payload);
        String bizType = StringUtils.hasText(payload.bizType()) ? payload.bizType() : context.getJobCode();
        String extension = switch (fileFormatType.toUpperCase()) {
            case "DELIMITED" -> ".csv";
            case "EXCEL" -> ".xlsx";
            case "XML" -> ".xml";
            default -> ".json";
        };
        if (StringUtils.hasText(namingRule)) {
            return namingRule
                    .replace("${bizDate}", bizDate)
                    .replace("${tenantId}", context.getTenantId())
                    .replace("${batchNo}", defaultText(payload.batchNo(), "batch"))
                    .replace("${version}", "v1");
        }
        return bizType + "_" + bizDate + "_" + defaultText(payload.batchNo(), "batch") + extension;
    }

    private String resolveObjectName(ExportJobContext context, ExportPayload payload, String fileName) {
        if (StringUtils.hasText(payload.objectName())) {
            return payload.objectName();
        }
        String bizType = StringUtils.hasText(payload.bizType()) ? payload.bizType() : context.getJobCode();
        String bizDate = resolveBizDate(context, payload);
        return "outbound/" + bizType + "/" + bizDate + "/" + defaultText(payload.batchNo(), "batch") + "/v1/" + fileName;
    }

    private String resolveBizDate(ExportJobContext context, ExportPayload payload) {
        if (StringUtils.hasText(payload.bizDate())) {
            return payload.bizDate();
        }
        if (StringUtils.hasText(context.getBizDate())) {
            return context.getBizDate();
        }
        return LocalDate.now().toString();
    }

    private String resolveText(Object value, String fallback) {
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? fallback : String.valueOf(value);
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
