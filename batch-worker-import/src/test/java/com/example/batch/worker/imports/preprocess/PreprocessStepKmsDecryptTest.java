package com.example.batch.worker.imports.preprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchKmsProperties;
import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.stage.PreprocessStep;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 单元测试：PreprocessStep 与 KMS 加解密闭环。
 * 校验 BATCHENC 载荷（由 {@link BatchObjectCryptoService} 产生，例如 StoreStep）在 ImportPreprocessPipeline
 * 运行前被正确解密，贯通导出加密与导入解密链路。
 */
class PreprocessStepKmsDecryptTest {

    private static final String KEY_REF = "TEST_KMS_2026";
    private static final String KEY_B64 = Base64.getEncoder().encodeToString(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345".getBytes(StandardCharsets.US_ASCII));
    private static final String PLAINTEXT = "{\"customerNo\":\"C001\",\"customerName\":\"Alice\"}";

    private BatchObjectCryptoService cryptoService;
    private PlatformFileRuntimeRepository runtimeRepo;
    private PreprocessStep preprocessStep;

    @BeforeEach
    void setUp() {
        BatchSecurityProperties security = new BatchSecurityProperties();
        security.setTestingOpen(false);

        BatchKmsProperties kms = new BatchKmsProperties();
        kms.setDefaultKeyRef(KEY_REF);
        kms.setKeys(Map.of(KEY_REF, KEY_B64));

        cryptoService = new BatchObjectCryptoService(security, kms);

        runtimeRepo = mock(PlatformFileRuntimeRepository.class);
        when(runtimeRepo.toLong(any())).thenReturn(1L);
        when(runtimeRepo.loadLatestTemplateConfig(any(), any(), any())).thenReturn(Map.of());
        // updateFileStatus returns void — Mockito mocks do nothing by default

        preprocessStep = new PreprocessStep(runtimeRepo, security, cryptoService);
    }

    @Test
    void shouldDecryptBatchEncPayload_beforePipelineRuns() {
        // Simulate what StoreStep does: encrypt plaintext with BatchObjectCryptoService
        byte[] encryptedBytes = cryptoService.encrypt(
                PLAINTEXT.getBytes(StandardCharsets.UTF_8), KEY_REF);
        String encryptedBase64 = Base64.getEncoder().encodeToString(encryptedBytes);

        // Build ImportPayload carrying the BATCHENC-encrypted content as base64
        // Fields: fileCode, fileName, originalFileName, bizType, fileFormatType, charset,
        //   targetCharset, checksumType, checksumValue, sourceType, sourceRef, storageType,
        //   storagePath, storageBucket, templateCode, batchNo, content, contentBase64,
        //   delimiter, headerRows, footerRows, withHeader, metadata
        ImportPayload payload = new ImportPayload(
                null, null, null, null, "JSON", null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, encryptedBase64, // contentBase64 (field 18)
                null, null, null, null,
                Map.of()
        );

        ImportJobContext context = buildContext(payload);
        context.getAttributes().put("importPayload", payload);

        ImportStageResult result = preprocessStep.execute(context);

        assertThat(result.success()).isTrue();
        // After decryption, normalizedPayload should equal the original JSON
        Object normalized = context.getAttributes().get("normalizedPayload");
        assertThat(normalized).isNotNull();
        assertThat(normalized.toString()).contains("customerNo");
        assertThat(normalized.toString()).contains("C001");
    }

    @Test
    void shouldPassThrough_nonEncryptedPayload() {
        String rawJson = "{\"records\":[]}";
        ImportPayload payload = new ImportPayload(
                null, null, null, null, "JSON", null, null, null,
                null, null, null, null,
                null, null, null, null,
                rawJson, null, // content (field 17)
                null, null, null, null,
                Map.of()
        );

        ImportJobContext context = buildContext(payload);
        context.getAttributes().put("importPayload", payload);

        ImportStageResult result = preprocessStep.execute(context);

        assertThat(result.success()).isTrue();
        Object normalized = context.getAttributes().get("normalizedPayload");
        assertThat(normalized).isNotNull();
        assertThat(normalized.toString()).contains("records");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private ImportJobContext buildContext(ImportPayload payload) {
        ImportJobContext context = new ImportJobContext();
        context.setTenantId("tenant-kms-test");
        context.setJobCode("KMS_IMPORT");
        context.setWorkerId("worker-kms-1");
        context.setRawPayload("{\"placeholder\":true}");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(PipelineRuntimeKeys.FILE_ID, 1L);
        attrs.put(PipelineRuntimeKeys.TASK_ID, 101L);
        context.setAttributes(attrs);
        return context;
    }
}
