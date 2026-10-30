package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.CompensationCommandStatus;
import io.github.pinpols.batch.orchestrator.domain.param.StaleCompensationFailureParam;
import io.github.pinpols.batch.orchestrator.mapper.CompensationCommandMapper;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

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
    when(mapper.markStaleRunningFailed(ArgumentMatchers.any(StaleCompensationFailureParam.class)))
        .thenReturn(2);

    reconciler.reconcile();

    verify(mapper)
        .markStaleRunningFailed(ArgumentMatchers.argThat(param -> param != null
            && Objects.equals(param.runningStatus(), CompensationCommandStatus.RUNNING.code())
            && Objects.equals(param.failedStatus(), CompensationCommandStatus.FAILED.code())
            && param.cutoff() != null
            && Objects.equals(param.errorCode(), StaleCompensationCommandReconciler.ERROR_CODE)
            && Objects.equals(
                param.errorMessage(),
                "compensation command stayed RUNNING beyond timeout; marked failed by reconciler")
            && Objects.equals(param.limit(), 25)));
  }
}
