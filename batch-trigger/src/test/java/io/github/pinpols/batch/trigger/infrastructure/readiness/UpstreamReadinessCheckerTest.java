package io.github.pinpols.batch.trigger.infrastructure.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("上游就绪查询客户端")
class UpstreamReadinessCheckerTest {

  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 6, 20);

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private RestClient orchestratorRestClient;

  @SuppressWarnings("unchecked")
  private void stubBody(ReadinessResponse response) {
    lenient()
        .when(
            orchestratorRestClient
                .get()
                .uri(any(Function.class))
                .retrieve()
                .body(eq(ReadinessResponse.class)))
        .thenReturn(response);
  }

  @Test
  @DisplayName("上游 ready=true → 放行")
  void shouldAllow_whenUpstreamReady() {
    stubBody(new ReadinessResponse(true, null));
    UpstreamReadinessChecker checker = new UpstreamReadinessChecker(orchestratorRestClient, true);

    assertThat(checker.isReady("t1", "UP_JOB", BIZ_DATE)).isTrue();
  }

  @Test
  @DisplayName("上游 ready=false → 拦截")
  void shouldBlock_whenUpstreamNotReady() {
    stubBody(new ReadinessResponse(false, "upstream-job-not-success"));
    UpstreamReadinessChecker checker = new UpstreamReadinessChecker(orchestratorRestClient, true);

    assertThat(checker.isReady("t1", "UP_JOB", BIZ_DATE)).isFalse();
  }

  @Test
  @DisplayName("查询抛异常 → fail-closed 拦截")
  void shouldFailClosed_whenQueryThrows() {
    when(orchestratorRestClient.get()).thenThrow(new RuntimeException("orchestrator down"));
    UpstreamReadinessChecker checker = new UpstreamReadinessChecker(orchestratorRestClient, true);

    assertThat(checker.isReady("t1", "UP_JOB", BIZ_DATE)).isFalse();
  }

  @Test
  @DisplayName("总开关关闭 → 恒放行,不查 orchestrator")
  void shouldAlwaysAllow_whenGateDisabled() {
    RestClient unused = mock(RestClient.class);
    UpstreamReadinessChecker checker = new UpstreamReadinessChecker(unused, false);

    assertThat(checker.isReady("t1", "UP_JOB", BIZ_DATE)).isTrue();
    verifyNoInteractions(unused);
  }
}
