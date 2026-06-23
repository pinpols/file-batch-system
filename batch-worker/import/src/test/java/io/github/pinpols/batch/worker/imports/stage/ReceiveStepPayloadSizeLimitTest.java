package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReceiveStepPayloadSizeLimitTest {

  @Mock private PlatformFileRuntimeRepository runtimeRepository;

  @Mock private BatchSecurityProperties batchSecurityProperties;

  private ReceiveStep receiveStep;

  @BeforeEach
  void setUp() {
    io.github.pinpols.batch.worker.imports.config.WorkerImportPayloadProperties payloadProps =
        new io.github.pinpols.batch.worker.imports.config.WorkerImportPayloadProperties();
    payloadProps.setMaxPayloadSizeMb(1);
    receiveStep =
        new ReceiveStep(
            runtimeRepository, batchSecurityProperties, new ObjectMapper(), payloadProps);
    // 强制 1 MB（绕开 heap-ratio 计算结果可能更小的情况）
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
