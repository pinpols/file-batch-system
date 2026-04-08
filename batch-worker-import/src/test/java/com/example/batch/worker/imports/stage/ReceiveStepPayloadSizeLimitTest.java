package com.example.batch.worker.imports.stage;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ReceiveStepPayloadSizeLimitTest {

    @Mock
    private PlatformFileRuntimeRepository runtimeRepository;

    @Mock
    private BatchSecurityProperties batchSecurityProperties;

    private ReceiveStep receiveStep;

    @BeforeEach
    void setUp() {
        receiveStep = new ReceiveStep(runtimeRepository, batchSecurityProperties, new ObjectMapper());
        // 设置 1 MB 限制
        ReflectionTestUtils.setField(receiveStep, "maxPayloadSizeMb", 1);
        ReflectionTestUtils.setField(receiveStep, "maxPayloadSizeBytes", 1L * 1024 * 1024);
    }

    @Test
    void execute_payloadWithinLimit_doesNotFailOnSizeCheck() {
        String payload = "{\"templateCode\":\"T1\",\"content\":\"small\"}";
        ImportJobContext ctx = buildContext("t1", payload);

        // 仅检查不因 size 报错（可能因为其他原因失败，但不是 TOO_LARGE）
        ImportStageResult result = receiveStep.execute(ctx);
        assertThat(result.code()).isNotEqualTo("IMPORT_RECEIVE_TOO_LARGE");
    }

    @Test
    void execute_payloadExceedsLimit_returnsTooLargeFailure() {
        // 2 MB payload
        String payload = "x".repeat(2 * 1024 * 1024);
        ImportJobContext ctx = buildContext("t1", payload);

        ImportStageResult result = receiveStep.execute(ctx);
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("IMPORT_RECEIVE_TOO_LARGE");
        assertThat(result.message()).contains("exceeds limit");
    }

    @Test
    void execute_nullContext_returnsInvalidFailure() {
        ImportStageResult result = receiveStep.execute(null);
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("IMPORT_RECEIVE_INVALID");
    }

    @Test
    void execute_blankTenantId_returnsInvalidFailure() {
        ImportJobContext ctx = buildContext("", "{\"content\":\"x\"}");
        ImportStageResult result = receiveStep.execute(ctx);
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("IMPORT_RECEIVE_INVALID");
    }

    @Test
    void execute_blankPayload_returnsInvalidFailure() {
        ImportJobContext ctx = buildContext("t1", "");
        ImportStageResult result = receiveStep.execute(ctx);
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("IMPORT_RECEIVE_INVALID");
    }

    private ImportJobContext buildContext(String tenantId, String payload) {
        ImportJobContext ctx = new ImportJobContext();
        ctx.setTenantId(tenantId);
        ctx.setRawPayload(payload);
        ctx.setAttributes(new HashMap<>());
        return ctx;
    }
}
