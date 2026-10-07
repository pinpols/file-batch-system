package io.github.pinpols.batch.orchestrator.infrastructure.sensor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("探测事务执行器的事务传播行为,核查取到期任务与单节点探测都要求独立新事务")
class SensorProbeTransactionExecutorTest {

  @Test
  @DisplayName("获取到期探测任务与执行单节点探测时均开启独立新事务,避免复用调用方既有事务")
  void shouldRequireNewTransaction_whenDatabaseLockAndSingleNodeProbe() throws Exception {
    Method fetch = SensorProbeTransactionExecutor.class.getDeclaredMethod(
        "fetchDue", Instant.class, int.class);
    Method probe = SensorProbeTransactionExecutor.class.getDeclaredMethod(
        "probeOne", WorkflowNodeRunEntity.class, Instant.class);

    assertThat(fetch.getAnnotation(Transactional.class).propagation())
        .isEqualTo(Propagation.REQUIRES_NEW);
    assertThat(probe.getAnnotation(Transactional.class).propagation())
        .isEqualTo(Propagation.REQUIRES_NEW);
  }
}
