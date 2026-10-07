package io.github.pinpols.batch.orchestrator.service.failure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.FailureClass;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.client.ResourceAccessException;

@DisplayName("失败分类器: 依据上报类别, 业务异常与数据库状态信号判定失败归属")
class FailureClassifierTest {

  private final FailureClassifier classifier = new FailureClassifier();

  @Test
  @DisplayName("上游已上报失败类别时直接采用,不再依据异常类型推断")
  void shouldUseWorkerReportedWhenPresent() {
    assertThat(classifier.classify("DATA_QUALITY", new RuntimeException()))
        .isEqualTo(FailureClass.DATA_QUALITY);
  }

  @Test
  @DisplayName("无上报值但异常自带失败类别时采用该类别")
  void shouldFallbackToBizExceptionFailureClass() {
    BizException biz =
        BizException.of(ResultCode.INVALID_ARGUMENT, FailureClass.CONFIG, "error.job.bad_config");
    assertThat(classifier.classify(null, biz)).isEqualTo(FailureClass.CONFIG);
  }

  @Test
  @DisplayName("普通超时与数据库超时异常都归类为超时失败")
  void shouldDetectTimeout() {
    assertThat(classifier.classify(null, new TimeoutException())).isEqualTo(FailureClass.TIMEOUT);
    assertThat(classifier.classify(null, new SQLTimeoutException()))
        .isEqualTo(FailureClass.TIMEOUT);
  }

  @Test
  @DisplayName("乐观锁冲突与网络访问异常归类为基础设施失败")
  void shouldDetectInfrastructure() {
    assertThat(classifier.classify(null, new OptimisticLockingFailureException("CAS")))
        .isEqualTo(FailureClass.INFRASTRUCTURE);
    assertThat(classifier.classify(null, new ResourceAccessException("network")))
        .isEqualTo(FailureClass.INFRASTRUCTURE);
  }

  @Test
  @DisplayName("按数据库状态信号分别归类为数据质量, 配置与基础设施失败")
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
  @DisplayName("无可用信号或上报值无法识别时归类为未知失败")
  void shouldReturnUnknownWhenNoSignal() {
    assertThat(classifier.classify(null, null)).isEqualTo(FailureClass.UNKNOWN);
    assertThat(classifier.classify(null, new RuntimeException("?")))
        .isEqualTo(FailureClass.UNKNOWN);
    // 未知 worker 上报字符串 → 走回退（且最终仍是 UNKNOWN）
    assertThat(classifier.classify("WHO_KNOWS", null)).isEqualTo(FailureClass.UNKNOWN);
  }

  @Test
  @DisplayName("数据完整性冲突归类为数据质量失败而非基础设施失败,包在外层异常里也能沿因果链命中")
  void shouldClassifyDataIntegrityViolationAsDataQuality_notInfrastructure() {
    // 异常数据(唯一键 / FK / not-null 违反)→ DATA_QUALITY(不可重试),
    // 不能被泛 DataAccessException catch-all 误判为 INFRASTRUCTURE 而无限重试。
    assertThat(classifier.classify(null, new DataIntegrityViolationException("duplicate key")))
        .isEqualTo(FailureClass.DATA_QUALITY);
    // 即使被包在更外层的运行时异常里,遍历 cause 链也要命中 DATA_QUALITY。
    assertThat(classifier.classify(
            null, new RuntimeException("wrap", new DataIntegrityViolationException("uk"))))
        .isEqualTo(FailureClass.DATA_QUALITY);
  }

  @Test
  @DisplayName("数据完整性冲突携带唯一键状态信号时仍归类为数据质量失败")
  void shouldClassifyDataIntegrityWithSqlStateByState() {
    // 整合违反携带 23xxx SQLState 时,经 cause 链最终仍归 DATA_QUALITY。
    DataIntegrityViolationException ex =
        new DataIntegrityViolationException("uk", new SQLException("dup", "23505"));
    assertThat(classifier.classify(null, ex)).isEqualTo(FailureClass.DATA_QUALITY);
  }

  @Test
  @DisplayName("无状态信号的不可重试数据访问失败归类为数据质量失败,不再当基础设施重试")
  void shouldClassifyOtherNonTransientDataAccessAsDataQuality() {
    // NonTransient 且无 SQLState 信号(非整合违反)→ DATA_QUALITY,不再被当基础设施重试。
    assertThat(classifier.classify(null, new DataAccessResourceFailureException("not-transient")))
        .isEqualTo(FailureClass.DATA_QUALITY);
  }

  @Test
  @DisplayName("上报类别与异常自带类别同时存在时以上报类别为准")
  void shouldRespectWorkerReportedOverBizException() {
    // worker 显式 → 优先（worker 业务侧最知道）
    BizException biz = BizException.of(ResultCode.SYSTEM_ERROR, FailureClass.CONFIG, "msg");
    assertThat(classifier.classify("DATA_QUALITY", biz)).isEqualTo(FailureClass.DATA_QUALITY);
  }
}
