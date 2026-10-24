package io.github.pinpols.batch.trigger.infrastructure.scheduler;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.mapper.TriggerMisfirePendingMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Misfire 待审对账器")
class TriggerMisfirePendingReconcilerTest {

  @Mock
  private TriggerMisfirePendingMapper pendingMapper;

  @Mock
  private TriggerGracefulShutdown gracefulShutdown;

  @InjectMocks
  private TriggerMisfirePendingReconciler reconciler;

  @Test
  @DisplayName("正常运行时同时收敛统一审批拒绝和超期记录")
  void reconcile_runsBothConvergencePaths() {
    when(gracefulShutdown.isDraining()).thenReturn(false);

    reconciler.reconcile();

    verify(pendingMapper).approveCentrallyApproved();
    verify(pendingMapper).rejectAndCloseCentrallyRejected();
    verify(pendingMapper).expireAndCloseUnlaunched(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("优雅排空期间不处理审批或过期记录")
  void draining_skipsReconciliation() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    reconciler.reconcile();

    verify(pendingMapper, never()).approveCentrallyApproved();
    verify(pendingMapper, never()).rejectAndCloseCentrallyRejected();
    verify(pendingMapper, never()).expireAndCloseUnlaunched(org.mockito.ArgumentMatchers.any());
  }
}
