package com.example.batch.orchestrator.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.CompensationCommandStatus;
import com.example.batch.orchestrator.mapper.CompensationCommandMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class StaleCompensationCommandReconcilerTest {

  @Test
  void reconcileMarksTimedOutRunningCommandsFailed() {
    CompensationCommandMapper mapper = mock(CompensationCommandMapper.class);
    StaleCompensationCommandReconciler reconciler = new StaleCompensationCommandReconciler(mapper);
    ReflectionTestUtils.setField(reconciler, "timeoutSeconds", 300L);
    ReflectionTestUtils.setField(reconciler, "batchSize", 25);
    when(mapper.markStaleRunningFailed(any(), any(), any(), any(), any(), anyInt())).thenReturn(2);

    reconciler.reconcile();

    verify(mapper)
        .markStaleRunningFailed(
            eq(CompensationCommandStatus.RUNNING.code()),
            eq(CompensationCommandStatus.FAILED.code()),
            any(Instant.class),
            eq(StaleCompensationCommandReconciler.ERROR_CODE),
            eq("compensation command stayed RUNNING beyond timeout; marked failed by reconciler"),
            eq(25));
  }
}
