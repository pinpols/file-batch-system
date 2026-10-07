package io.github.pinpols.batch.worker.processes.infrastructure.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.verifier.VerifyContext;
import io.github.pinpols.batch.common.verifier.VerifyResult;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("处理发布数量校验:发布数为零且确有处理行时判失败,缺字段或空输入时放行")
class ProcessPublishedCountVerifierTest {

  private final ProcessPublishedCountVerifier verifier = new ProcessPublishedCountVerifier();

  @Test
  @DisplayName("发布数量为正数时校验通过")
  void shouldPass_whenPublishedCountPositive() {
    assertThat(verifier.verify(contextWith(Map.of("publishedCount", 10L))).passed())
        .isTrue();
  }

  @Test
  @DisplayName("载荷未上报发布数量时不归本校验判定,直接通过")
  void shouldPass_whenPublishedCountMissing() {
    // worker 未上报该字段 → 不归本 verifier 判
    assertThat(verifier.verify(contextWith(Map.of())).passed()).isTrue();
  }

  @Test
  @DisplayName("发布数为零但处理行数为正数时校验失败,并以批次号作为证据回带")
  void shouldFail_whenPublishedCountZero() {
    Map<String, Object> payload = new HashMap<>();
    payload.put("publishedCount", 0);
    payload.put("processedCount", 100);
    payload.put("batchKey", "BATCH-001");
    VerifyResult result = verifier.verify(contextWith(payload));
    assertThat(result.passed()).isFalse();
    assertThat(result.code()).isEqualTo("PROCESS_PUBLISHED_ZERO");
    assertThat(result.evidence()).containsEntry("batchKey", "BATCH-001");
  }

  @Test
  @DisplayName("发布数为零且处理行数也为零时视为空输入,校验通过")
  void shouldPass_whenNoRowsWereProcessed() {
    assertThat(verifier
            .verify(contextWith(Map.of("publishedCount", 0, "processedCount", 0)))
            .passed())
        .isTrue();
  }

  @Test
  @DisplayName("处理行数不是数字时按非空输入处理,校验失败")
  void shouldFail_whenProcessedCountNotANumber() {
    Map<String, Object> payload = new HashMap<>();
    payload.put("publishedCount", 0);
    payload.put("processedCount", "unknown");
    assertThat(verifier.verify(contextWith(payload)).passed()).isFalse();
  }

  @Test
  @DisplayName("发布数量以数字字符串给出时解析后判定通过")
  void shouldPass_whenPublishedCountIsNumericString() {
    assertThat(verifier.verify(contextWith(Map.of("publishedCount", "5"))).passed())
        .isTrue();
  }

  private static VerifyContext contextWith(Map<String, Object> payload) {
    return VerifyContext.builder()
        .tenantId("t1")
        .jobType(JobType.PROCESS)
        .jobInstanceId(1L)
        .taskId(2L)
        .stageCode("PROCESS_PUBLISH")
        .payload(payload)
        .build();
  }
}
