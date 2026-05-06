package com.example.batch.orchestrator.service.failure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.FailureClass;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.client.ResourceAccessException;

class FailureClassifierTest {

  private final FailureClassifier classifier = new FailureClassifier();

  @Test
  void shouldUseWorkerReportedWhenPresent() {
    assertThat(classifier.classify("DATA_QUALITY", new RuntimeException()))
        .isEqualTo(FailureClass.DATA_QUALITY);
  }

  @Test
  void shouldFallbackToBizExceptionFailureClass() {
    BizException biz =
        BizException.of(ResultCode.INVALID_ARGUMENT, FailureClass.CONFIG, "error.job.bad_config");
    assertThat(classifier.classify(null, biz)).isEqualTo(FailureClass.CONFIG);
  }

  @Test
  void shouldDetectTimeout() {
    assertThat(classifier.classify(null, new TimeoutException())).isEqualTo(FailureClass.TIMEOUT);
    assertThat(classifier.classify(null, new SQLTimeoutException()))
        .isEqualTo(FailureClass.TIMEOUT);
  }

  @Test
  void shouldDetectInfrastructure() {
    assertThat(classifier.classify(null, new OptimisticLockingFailureException("CAS")))
        .isEqualTo(FailureClass.INFRASTRUCTURE);
    assertThat(classifier.classify(null, new ResourceAccessException("network")))
        .isEqualTo(FailureClass.INFRASTRUCTURE);
  }

  @Test
  void shouldClassifyBySqlState() {
    assertThat(classifier.classify(null, new SQLException("constraint", "23505")))
        .isEqualTo(FailureClass.DATA_QUALITY);
    assertThat(classifier.classify(null, new SQLException("syntax", "42601")))
        .isEqualTo(FailureClass.CONFIG);
    assertThat(classifier.classify(null, new SQLException("conn", "08006")))
        .isEqualTo(FailureClass.INFRASTRUCTURE);
    assertThat(classifier.classify(null, new SQLException("deadlock", "40P01")))
        .isEqualTo(FailureClass.INFRASTRUCTURE);
  }

  @Test
  void shouldReturnUnknownWhenNoSignal() {
    assertThat(classifier.classify(null, null)).isEqualTo(FailureClass.UNKNOWN);
    assertThat(classifier.classify(null, new RuntimeException("?")))
        .isEqualTo(FailureClass.UNKNOWN);
    // 未知 worker 上报字符串 → 走兜底（且最终仍是 UNKNOWN）
    assertThat(classifier.classify("WHO_KNOWS", null)).isEqualTo(FailureClass.UNKNOWN);
  }

  @Test
  void shouldRespectWorkerReportedOverBizException() {
    // worker 显式 → 优先（worker 业务侧最知道）
    BizException biz = BizException.of(ResultCode.SYSTEM_ERROR, FailureClass.CONFIG, "msg");
    assertThat(classifier.classify("DATA_QUALITY", biz)).isEqualTo(FailureClass.DATA_QUALITY);
  }
}
