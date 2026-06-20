package com.example.batch.orchestrator.application.service.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("上游就绪查询服务")
class ReadinessServiceTest {

  @Mock private JobInstanceMapper jobInstanceMapper;

  @InjectMocks private ReadinessService readinessService;

  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 6, 20);

  @Test
  @DisplayName("上游该批次日存在 SUCCESS 实例 → ready")
  void shouldBeReady_whenUpstreamSuccessExists() {
    // arrange
    when(jobInstanceMapper.countSuccessByBizDate("t1", "UP_JOB", BIZ_DATE)).thenReturn(1L);

    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", BIZ_DATE);

    // assert
    assertThat(result.ready()).isTrue();
    assertThat(result.reason()).isNull();
  }

  @Test
  @DisplayName("上游该批次日无 SUCCESS → not ready,带原因码")
  void shouldNotBeReady_whenNoUpstreamSuccess() {
    // arrange
    when(jobInstanceMapper.countSuccessByBizDate("t1", "UP_JOB", BIZ_DATE)).thenReturn(0L);

    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", BIZ_DATE);

    // assert
    assertThat(result.ready()).isFalse();
    assertThat(result.reason()).isEqualTo("upstream-job-not-success");
  }

  @Test
  @DisplayName("入参非法 → 短路 not ready,不查库")
  void shouldShortCircuit_whenArgsInvalid() {
    // act
    ReadinessResult result = readinessService.checkJobReady("t1", " ", BIZ_DATE);

    // assert
    assertThat(result.ready()).isFalse();
    assertThat(result.reason()).isEqualTo("invalid-readiness-query");
    verify(jobInstanceMapper, never()).countSuccessByBizDate(any(), any(), any());
  }

  @Test
  @DisplayName("null bizDate → 短路 not ready")
  void shouldShortCircuit_whenBizDateNull() {
    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", null);

    // assert
    assertThat(result.ready()).isFalse();
    verify(jobInstanceMapper, never()).countSuccessByBizDate(eq("t1"), eq("UP_JOB"), any());
  }
}
