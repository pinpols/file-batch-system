package com.example.batch.worker.imports.stage;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.preprocess.ImportPreprocessException;
import com.example.batch.worker.imports.preprocess.ImportPreprocessPipeline;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * PREPROCESS（设计说明书 §9.3）：拉取模板、解码正文，执行 {@link ImportPreprocessPipeline}
 *（{@code preprocess_pipeline} 或隐式 {@code compress_type}/{@code encrypt_type}：UNZIP、GUNZIP、AES-GCM、摘要、RSA 验签、字符集转码），
 * 再归一化文本或保留二进制供 EXCEL 等格式在 PARSE 消费。
 */
@Component
public class PreprocessStep implements ImportStageStep {

    private final PlatformFileRuntimeRepository runtimeRepository;
    private final BatchSecurityProperties batchSecurityProperties;

    public PreprocessStep(PlatformFileRuntimeRepository runtimeRepository,
                          BatchSecurityProperties batchSecurityProperties) {
        this.runtimeRepository = runtimeRepository;
        this.batchSecurityProperties = batchSecurityProperties;
    }

    @Override
    public ImportStage stage() {
        return ImportStage.PREPROCESS;
    }

    @Override
    public ImportStageResult execute(ImportJobContext context) {
        if (context == null) {
            return ImportStageResult.failure(stage(), "IMPORT_PREPROCESS_INVALID", "context is null");
        }
        ImportPayload importPayload = context.getAttributes().get("importPayload") instanceof ImportPayload payload ? payload : null;
        if (!StringUtils.hasText(context.getRawPayload()) && (importPayload == null
                || (!StringUtils.hasText(importPayload.content()) && !StringUtils.hasText(importPayload.contentBase64())))) {
            return ImportStageResult.failure(stage(), "IMPORT_PREPROCESS_INVALID", "raw payload is blank");
        }
        try {
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
            Object templateConfigObject = context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
            Map<String, Object> templateConfig = toStringKeyMap(templateConfigObject);

            byte[] rawBytes = resolveRawBytes(context, importPayload, templateConfigObject);
            byte[] processed = ImportPreprocessPipeline.run(rawBytes, importPayload, templateConfig, batchSecurityProperties.isTestingOpen());

            String formatType = resolveFileFormatType(importPayload, templateConfig);
            if (isBinaryImportFormat(formatType)) {
                context.getAttributes().put(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD, processed);
                context.setRawPayload("");
                context.getAttributes().remove("normalizedPayload");
            } else {
                Charset charset = resolveCharset(importPayload, templateConfigObject);
                String normalized = normalizeText(new String(processed, charset));
                context.setRawPayload(normalized);
                context.getAttributes().put("normalizedPayload", normalized);
                context.getAttributes().remove(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD);
            }

            runtimeRepository.updateFileStatus(
                    runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)),
                    "PARSING",
                    Map.of("preprocessed", Boolean.TRUE, "preprocessFormat", formatType == null ? "" : formatType)
            );
            return ImportStageResult.success(stage());
        } catch (ImportPreprocessException ex) {
            return ImportStageResult.failure(stage(), ex.errorCode(), ex.getMessage());
        }
    }

    private static boolean isBinaryImportFormat(String formatType) {
        if (!StringUtils.hasText(formatType)) {
            return false;
        }
        String u = formatType.trim().toUpperCase();
        return "EXCEL".equals(u) || "BINARY".equals(u);
    }

    private static String resolveFileFormatType(ImportPayload importPayload, Map<String, Object> templateConfig) {
        if (importPayload != null && StringUtils.hasText(importPayload.fileFormatType())) {
            return importPayload.fileFormatType();
        }
        Object v = templateConfig.get("file_format_type");
        if (v != null && StringUtils.hasText(String.valueOf(v))) {
            return String.valueOf(v);
        }
        return null;
    }

    private byte[] resolveRawBytes(ImportJobContext context,
                                   ImportPayload importPayload,
                                   Object templateConfigObject) {
        if (importPayload != null && StringUtils.hasText(importPayload.contentBase64())) {
            return Base64.getDecoder().decode(importPayload.contentBase64().trim());
        }
        if (importPayload != null && StringUtils.hasText(importPayload.content())) {
            Charset cs = resolveCharsetForContentBytes(importPayload, templateConfigObject);
            return importPayload.content().getBytes(cs);
        }
        String raw = context.getRawPayload();
        return raw == null ? new byte[0] : raw.getBytes(StandardCharsets.UTF_8);
    }

    private Charset resolveCharsetForContentBytes(ImportPayload importPayload, Object templateConfigObject) {
        if (templateConfigObject instanceof Map<?, ?> templateConfig) {
            Object charset = templateConfig.get("charset");
            if (charset != null && StringUtils.hasText(String.valueOf(charset))) {
                return Charset.forName(String.valueOf(charset));
            }
        }
        if (importPayload != null && StringUtils.hasText(importPayload.charset())) {
            return Charset.forName(importPayload.charset());
        }
        return StandardCharsets.UTF_8;
    }

    private static Map<String, Object> toStringKeyMap(Object templateConfigObject) {
        if (!(templateConfigObject instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private String normalizeText(String source) {
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
            Object charset = templateConfig.get("charset");
            if (charset != null && StringUtils.hasText(String.valueOf(charset))) {
                return Charset.forName(String.valueOf(charset));
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
