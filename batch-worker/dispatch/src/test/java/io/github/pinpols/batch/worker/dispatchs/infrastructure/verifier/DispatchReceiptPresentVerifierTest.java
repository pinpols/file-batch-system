package io.github.pinpols.batch.worker.dispatchs.infrastructure.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.verifier.VerifyContext;
import io.github.pinpols.batch.common.verifier.VerifyResult;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("分发回执存在性校验:回执码与外部请求号的非空判定及缺失时的证据回带")
class DispatchReceiptPresentVerifierTest {

  private final DispatchReceiptPresentVerifier verifier = new DispatchReceiptPresentVerifier();

  @Test
  @DisplayName("回执码非空时校验通过")
  void shouldPass_whenReceiptCodePresent() {
    assertThat(verifier
            .verify(contextWith(Map.of(DispatchRuntimeKeys.RECEIPT_CODE, "RCP-001")))
            .passed())
        .isTrue();
  }

  @Test
  @DisplayName("只有外部请求号而没有回执码时,校验同样通过")
  void shouldPass_whenOnlyExternalRequestIdPresent() {
    assertThat(verifier
            .verify(contextWith(Map.of(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID, "ext-xyz")))
            .passed())
        .isTrue();
  }

  @Test
  @DisplayName("回执码与外部请求号都缺失时校验失败,并以渠道号与文件号作为证据回带")
  void shouldFail_whenReceiptCodeAndExternalRequestIdBothMissing() {
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
  @DisplayName("回执码与外部请求号都只有空白字符时,校验失败")
  void shouldFail_whenReceiptCodeAndExternalRequestIdBlank() {
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
