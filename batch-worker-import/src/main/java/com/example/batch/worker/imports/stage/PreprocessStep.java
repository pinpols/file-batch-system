package com.example.batch.worker.imports.stage;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.ImportPayload;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PreprocessStep implements ImportStageStep {

    private final PlatformFileRuntimeRepository runtimeRepository;

    public PreprocessStep(PlatformFileRuntimeRepository runtimeRepository) {
        this.runtimeRepository = runtimeRepository;
    }

    @Override
    public ImportStage stage() {
        return ImportStage.PREPROCESS;
    }

    @Override
    public ImportStageResult execute(ImportJobContext context) {
        if (context == null || !StringUtils.hasText(context.getRawPayload())) {
            return ImportStageResult.failure(stage(), "IMPORT_PREPROCESS_INVALID", "raw payload is blank");
        }
        ImportPayload importPayload = context.getAttributes().get("importPayload") instanceof ImportPayload payload
                ? payload
                : null;
        if (importPayload != null && StringUtils.hasText(importPayload.templateCode())) {
            Map<String, Object> templateConfig = runtimeRepository.loadLatestTemplateConfig(
                    context.getTenantId(),
                    importPayload.templateCode(),
                    "IMPORT"
            );
            if (!templateConfig.isEmpty()) {
                context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig);
            }
        }
        String normalizedPayload = normalizePayload(context.getRawPayload(), importPayload, context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG));
        context.setRawPayload(normalizedPayload);
        context.getAttributes().put("normalizedPayload", normalizedPayload);
        runtimeRepository.updateFileStatus(
                runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
                "PARSING",
                Map.of("preprocessed", Boolean.TRUE)
        );
        return ImportStageResult.success(stage());
    }

    private String normalizePayload(String rawPayload, ImportPayload importPayload, Object templateConfigObject) {
        String source = rawPayload;
        if (importPayload != null) {
            if (StringUtils.hasText(importPayload.contentBase64())) {
                Charset charset = resolveCharset(importPayload, templateConfigObject);
                source = new String(Base64.getDecoder().decode(importPayload.contentBase64()), charset);
            } else if (StringUtils.hasText(importPayload.content())) {
                source = importPayload.content();
            }
        }
        if (source == null) {
            return "";
        }
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.isEmpty() && normalized.charAt(0) == '\uFEFF') {
            normalized = normalized.substring(1);
        }
        return normalized.trim();
    }

    private Charset resolveCharset(ImportPayload importPayload, Object templateConfigObject) {
        if (templateConfigObject instanceof Map<?, ?> templateConfig) {
            Object targetCharset = templateConfig.get("target_charset");
            if (targetCharset != null && StringUtils.hasText(String.valueOf(targetCharset))) {
                return Charset.forName(String.valueOf(targetCharset));
            }
        }
        if (importPayload != null && StringUtils.hasText(importPayload.targetCharset())) {
            return Charset.forName(importPayload.targetCharset());
        }
        if (importPayload != null && StringUtils.hasText(importPayload.charset())) {
            return Charset.forName(importPayload.charset());
        }
        return StandardCharsets.UTF_8;
    }
}
