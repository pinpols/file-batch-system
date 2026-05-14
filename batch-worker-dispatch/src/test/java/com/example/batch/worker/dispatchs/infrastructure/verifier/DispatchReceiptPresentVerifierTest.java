package com.example.batch.worker.dispatchs.infrastructure.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.JobType;
import com.example.batch.common.verifier.VerifyContext;
import com.example.batch.common.verifier.VerifyResult;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DispatchReceiptPresentVerifierTest {

  private final DispatchReceiptPresentVerifier verifier = new DispatchReceiptPresentVerifier();

  @Test
  void passesWhenReceiptCodePresent() {
    assertThat(verifier.verify(contextWith(Map.of("receiptCode", "RCP-001"))).passed()).isTrue();
  }

  @Test
  void passesWhenOnlyExternalRequestIdPresent() {
    assertThat(verifier.verify(contextWith(Map.of("externalRequestId", "ext-xyz"))).passed())
        .isTrue();
  }

  @Test
  void failsWhenBothMissing() {
    Map<String, Object> payload = new HashMap<>();
    payload.put("channelCode", "NAS");
    payload.put("fileId", 42L);
    VerifyResult result = verifier.verify(contextWith(payload));
    assertThat(result.passed()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_RECEIPT_MISSING");
    assertThat(result.evidence()).containsEntry("channelCode", "NAS").containsEntry("fileId", 42L);
  }

  @Test
  void failsWhenReceiptCodeBlank() {
    Map<String, Object> payload = new HashMap<>();
    payload.put("receiptCode", "  ");
    payload.put("externalRequestId", "");
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
