package com.example.batch.worker.processes.infrastructure.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.JobType;
import com.example.batch.common.verifier.VerifyContext;
import com.example.batch.common.verifier.VerifyResult;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessPublishedCountVerifierTest {

  private final ProcessPublishedCountVerifier verifier = new ProcessPublishedCountVerifier();

  @Test
  void passesWhenPublishedCountPositive() {
    assertThat(verifier.verify(contextWith(Map.of("publishedCount", 10L))).passed()).isTrue();
  }

  @Test
  void passesWhenPublishedCountMissing() {
    // worker 未上报该字段 → 不归本 verifier 判
    assertThat(verifier.verify(contextWith(Map.of())).passed()).isTrue();
  }

  @Test
  void failsWhenPublishedCountZero() {
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
  void parsesStringNumber() {
    assertThat(verifier.verify(contextWith(Map.of("publishedCount", "5"))).passed()).isTrue();
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
