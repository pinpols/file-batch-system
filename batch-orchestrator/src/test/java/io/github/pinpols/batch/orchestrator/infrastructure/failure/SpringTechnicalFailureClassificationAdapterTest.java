package io.github.pinpols.batch.orchestrator.infrastructure.failure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.FailureClass;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.client.ResourceAccessException;

@DisplayName("技术异常分类适配器: 隔离 JDBC, Spring DAO 与 HTTP 客户端异常")
class SpringTechnicalFailureClassificationAdapterTest {

  private final SpringTechnicalFailureClassificationAdapter adapter =
      new SpringTechnicalFailureClassificationAdapter();

  @Test
  @DisplayName("超时,网络与乐观锁冲突分别归一为超时或基础设施失败")
  void shouldClassifyTimeoutAndInfrastructureFailures() {
    assertThat(adapter.classify(new TimeoutException())).isEqualTo(FailureClass.TIMEOUT);
    assertThat(adapter.classify(new SQLTimeoutException())).isEqualTo(FailureClass.TIMEOUT);
    assertThat(adapter.classify(new ResourceAccessException("network")))
        .isEqualTo(FailureClass.INFRASTRUCTURE);
    assertThat(adapter.classify(new OptimisticLockingFailureException("CAS")))
        .isEqualTo(FailureClass.INFRASTRUCTURE);
  }

  @Test
  @DisplayName("数据库状态信号按基础设施,数据质量与配置失败分类")
  void shouldClassifySqlState() {
    assertThat(adapter.classify(new SQLException("constraint", "23505")))
        .isEqualTo(FailureClass.DATA_QUALITY);
    assertThat(adapter.classify(new SQLException("syntax", "42601")))
        .isEqualTo(FailureClass.CONFIG);
    assertThat(adapter.classify(new SQLException("connection", "08006")))
        .isEqualTo(FailureClass.INFRASTRUCTURE);
    assertThat(adapter.classify(new SQLException("deadlock", "40P01")))
        .isEqualTo(FailureClass.INFRASTRUCTURE);
  }
}
