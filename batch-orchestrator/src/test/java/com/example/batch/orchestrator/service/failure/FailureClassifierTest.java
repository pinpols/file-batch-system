package com.example.batch.orchestrator.service.failure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.FailureClass;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
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
    // 未知 worker 上报字符串 → 走回退（且最终仍是 UNKNOWN）
    assertThat(classifier.classify("WHO_KNOWS", null)).isEqualTo(FailureClass.UNKNOWN);
  }

  @Test
  void shouldClassifyDataIntegrityViolationAsDataQuality_notInfrastructure() {
    // 异常数据(唯一键 / FK / not-null 违反)→ DATA_QUALITY(不可重试),
    // 不能被泛 DataAccessException catch-all 误判为 INFRASTRUCTURE 而无限重试。
    assertThat(classifier.classify(null, new DataIntegrityViolationException("duplicate key")))
        .isEqualTo(FailureClass.DATA_QUALITY);
    // 即使被包在更外层的运行时异常里,遍历 cause 链也要命中 DATA_QUALITY。
    assertThat(
            classifier.classify(
                null, new RuntimeException("wrap", new DataIntegrityViolationException("uk"))))
        .isEqualTo(FailureClass.DATA_QUALITY);
  }

  @Test
  void shouldClassifyDataIntegrityWithSqlStateByState() {
    // 整合违反携带 23xxx SQLState 时,经 cause 链最终仍归 DATA_QUALITY。
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException("uk", new SQLException("dup", "23505"));
    assertThat(classifier.classify(null, ex)).isEqualTo(FailureClass.DATA_QUALITY);
  }

  @Test
  void shouldClassifyOtherNonTransientDataAccessAsDataQuality() {
    // NonTransient 且无 SQLState 信号(非整合违反)→ DATA_QUALITY,不再被当基础设施重试。
    assertThat(classifier.classify(null, new DataAccessResourceFailureException("not-transient")))
        .isEqualTo(FailureClass.DATA_QUALITY);
  }

  @Test
  void shouldRespectWorkerReportedOverBizException() {
    // worker 显式 → 优先（worker 业务侧最知道）
    BizException biz = BizException.of(ResultCode.SYSTEM_ERROR, FailureClass.CONFIG, "msg");
    assertThat(classifier.classify("DATA_QUALITY", biz)).isEqualTo(FailureClass.DATA_QUALITY);
  }
}
