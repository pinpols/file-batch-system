package com.example.batch.orchestrator.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.CompensationCommandStatus;
import com.example.batch.orchestrator.infrastructure.tenant.ActiveTenantProvider;
import com.example.batch.orchestrator.mapper.CompensationCommandMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StaleCompensationCommandReconcilerTest {

  @Mock private CompensationCommandMapper mapper;

  @Mock private ActiveTenantProvider activeTenantProvider;

  @InjectMocks private StaleCompensationCommandReconciler reconciler;

  @Test
  void reconcileMarksTimedOutRunningCommandsFailed() {
    ReflectionTestUtils.setField(reconciler, "timeoutSeconds", 300L);
    ReflectionTestUtils.setField(reconciler, "batchSize", 25);
    when(activeTenantProvider.activeTenantIds()).thenReturn(List.of("tenant-a"));
    when(mapper.markStaleRunningFailed(any(), any(), any(), any(), any(), any(), anyInt()))
        .thenReturn(2);

    reconciler.reconcile();

    verify(mapper)
        .markStaleRunningFailed(
            eq("tenant-a"),
            eq(CompensationCommandStatus.RUNNING.code()),
            eq(CompensationCommandStatus.FAILED.code()),
            any(Instant.class),
            eq(StaleCompensationCommandReconciler.ERROR_CODE),
            eq("compensation command stayed RUNNING beyond timeout; marked failed by reconciler"),
            eq(25));
  }

  @Test
  void reconcile_singleTenantException_continuesOtherTenants() {
    ReflectionTestUtils.setField(reconciler, "timeoutSeconds", 300L);
    ReflectionTestUtils.setField(reconciler, "batchSize", 25);
    when(activeTenantProvider.activeTenantIds()).thenReturn(List.of("tenant-fail", "tenant-ok"));
    when(mapper.markStaleRunningFailed(
            eq("tenant-fail"), any(), any(), any(), any(), any(), anyInt()))
        .thenThrow(new RuntimeException("simulated db error"));
    when(mapper.markStaleRunningFailed(
            eq("tenant-ok"), any(), any(), any(), any(), any(), anyInt()))
        .thenReturn(1);

    // should not throw
    reconciler.reconcile();

    verify(mapper)
        .markStaleRunningFailed(
            eq("tenant-ok"), any(), any(), any(Instant.class), any(), any(), anyInt());
  }
}
