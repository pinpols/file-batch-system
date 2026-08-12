package io.github.pinpols.batch.orchestrator.infrastructure.sensor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class SensorProbeTransactionExecutorTest {

  @Test
  void databaseLockAndPerNodeProbeUseRequiresNewTransactions() throws Exception {
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
