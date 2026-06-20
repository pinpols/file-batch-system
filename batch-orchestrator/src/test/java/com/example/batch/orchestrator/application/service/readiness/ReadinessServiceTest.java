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
  @DisplayName("上游该批次日最新 attempt 为 SUCCESS → ready")
  void shouldBeReady_whenLatestAttemptSuccess() {
    // arrange
    when(jobInstanceMapper.selectLatestStatusByBizDate("t1", "UP_JOB", BIZ_DATE))
        .thenReturn("SUCCESS");

    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", BIZ_DATE);

    // assert
    assertThat(result.ready()).isTrue();
    assertThat(result.reason()).isNull();
  }

  @Test
  @DisplayName("先成功后 rerun 失败:最新 attempt=FAILED → not ready(不被过期成功误放行)")
  void shouldNotBeReady_whenLatestAttemptFailedAfterEarlierSuccess() {
    // arrange —— selectLatestStatusByBizDate 已按 run_attempt desc 取最新,返回 rerun 的 FAILED
    when(jobInstanceMapper.selectLatestStatusByBizDate("t1", "UP_JOB", BIZ_DATE))
        .thenReturn("FAILED");

    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", BIZ_DATE);

    // assert
    assertThat(result.ready()).isFalse();
    assertThat(result.reason()).isEqualTo("upstream-job-not-success");
  }

  @Test
  @DisplayName("rerun 正在跑:最新 attempt=RUNNING → not ready")
  void shouldNotBeReady_whenLatestAttemptRunning() {
    // arrange
    when(jobInstanceMapper.selectLatestStatusByBizDate("t1", "UP_JOB", BIZ_DATE))
        .thenReturn("RUNNING");

    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", BIZ_DATE);

    // assert
    assertThat(result.ready()).isFalse();
    assertThat(result.reason()).isEqualTo("upstream-job-not-success");
  }

  @Test
  @DisplayName("该批次日无任何实例:返回 null → not ready")
  void shouldNotBeReady_whenNoInstance() {
    // arrange
    when(jobInstanceMapper.selectLatestStatusByBizDate("t1", "UP_JOB", BIZ_DATE)).thenReturn(null);

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
    verify(jobInstanceMapper, never()).selectLatestStatusByBizDate(any(), any(), any());
  }

  @Test
  @DisplayName("null bizDate → 短路 not ready")
  void shouldShortCircuit_whenBizDateNull() {
    // act
    ReadinessResult result = readinessService.checkJobReady("t1", "UP_JOB", null);

    // assert
    assertThat(result.ready()).isFalse();
    verify(jobInstanceMapper, never()).selectLatestStatusByBizDate(eq("t1"), eq("UP_JOB"), any());
  }
}
