package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.orchestrator.domain.param.StaleCompensationFailureParam;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.CompensationCommandStatus;
import io.github.pinpols.batch.orchestrator.mapper.CompensationCommandMapper;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("过期补偿命令对账器,验证超时仍处于运行中状态的命令被批量标记为失败")
class StaleCompensationCommandReconcilerTest {

  @Test
  @DisplayName("补偿命令超过超时阈值仍为运行中时,对账应按配置的批量大小将其标记为失败并记录错误原因")
  void shouldMarkTimedOutRunningCommandsFailed_whenReconciling() {
    CompensationCommandMapper mapper = mock(CompensationCommandMapper.class);
    StaleCompensationReconcilerProperties properties = new StaleCompensationReconcilerProperties();
    properties.setTimeoutSeconds(300L);
    properties.setBatchSize(25);
    StaleCompensationCommandReconciler reconciler =
        new StaleCompensationCommandReconciler(mapper, properties);
    when(mapper.markStaleRunningFailed(org.mockito.ArgumentMatchers.any(StaleCompensationFailureParam.class)))
        .thenReturn(2);

    reconciler.reconcile();

    verify(mapper)
        .markStaleRunningFailed(org.mockito.ArgumentMatchers.argThat(param -> param != null && java.util.Objects.equals(param.runningStatus(), CompensationCommandStatus.RUNNING.code()) && java.util.Objects.equals(param.failedStatus(), CompensationCommandStatus.FAILED.code()) && param.cutoff() != null && java.util.Objects.equals(param.errorCode(), StaleCompensationCommandReconciler.ERROR_CODE) && java.util.Objects.equals(param.errorMessage(), "compensation command stayed RUNNING beyond timeout; marked failed by reconciler") && java.util.Objects.equals(param.limit(), 25)));
  }
}
