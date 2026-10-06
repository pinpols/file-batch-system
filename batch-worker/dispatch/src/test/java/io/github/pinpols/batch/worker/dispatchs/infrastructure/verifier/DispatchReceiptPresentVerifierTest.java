package io.github.pinpols.batch.worker.dispatchs.infrastructure.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.verifier.VerifyContext;
import io.github.pinpols.batch.common.verifier.VerifyResult;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DispatchReceiptPresentVerifierTest {

  private final DispatchReceiptPresentVerifier verifier = new DispatchReceiptPresentVerifier();

  @Test
  void passesWhenReceiptCodePresent() {
    assertThat(verifier
            .verify(contextWith(Map.of(DispatchRuntimeKeys.RECEIPT_CODE, "RCP-001")))
            .passed())
        .isTrue();
  }

  @Test
  void passesWhenOnlyExternalRequestIdPresent() {
    assertThat(verifier
            .verify(contextWith(Map.of(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID, "ext-xyz")))
            .passed())
        .isTrue();
  }

  @Test
  void failsWhenBothMissing() {
    Map<String, Object> payload = new HashMap<>();
    payload.put(DispatchRuntimeKeys.CHANNEL_CODE, "NAS");
    payload.put(PipelineRuntimeKeys.FILE_ID, 42L);
    VerifyResult result = verifier.verify(contextWith(payload));
    assertThat(result.passed()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_RECEIPT_MISSING");
    assertThat(result.evidence())
        .containsEntry(DispatchRuntimeKeys.CHANNEL_CODE, "NAS")
        .containsEntry(PipelineRuntimeKeys.FILE_ID, 42L);
  }

  @Test
  void failsWhenReceiptCodeBlank() {
    Map<String, Object> payload = new HashMap<>();
    payload.put(DispatchRuntimeKeys.RECEIPT_CODE, "  ");
    payload.put(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID, "");
    assertThat(verifier.verify(contextWith(payload)).passed()).isFalse();
  }

  private static VerifyContext contextWith(Map<String, Object> payload) {
    return VerifyContext.builder()
        .tenantId("t1")
        .jobType(JobType.DISPATCH)
        .jobInstanceId(1L)
        .taskId(2L)
        .stageCode("DISPATCH_COMPLETE")
        .payload(payload)
        .build();
  }
}
