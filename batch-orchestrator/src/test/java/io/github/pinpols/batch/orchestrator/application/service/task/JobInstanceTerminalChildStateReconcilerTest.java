package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * 守护实例终态 → 子表(partition/task)收敛的映射:
 *
 * <ul>
 *   <li>SUCCESS → 子表 SUCCESS
 *   <li>FAILED / PARTIAL_FAILED → 子表 FAILED(混合失败都收敛为 FAILED)
 *   <li>CANCELLED → 子表 CANCELLED
 *   <li>TERMINATED → 子表 TERMINATED
 *   <li>非业务终态(RUNNING/WAITING/READY/CREATED) → no-op,不写 DB
 *   <li>未知 code → no-op
 * </ul>
 */
class JobInstanceTerminalChildStateReconcilerTest {

  @Mock private JobPartitionMapper partitionMapper;
  @Mock private JobTaskMapper taskMapper;

  private JobInstanceTerminalChildStateReconciler reconciler;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    reconciler = new JobInstanceTerminalChildStateReconciler(partitionMapper, taskMapper);
    when(partitionMapper.closeNonTerminalPartitionsForTerminalInstance(
            anyString(), anyLong(), anyString()))
        .thenReturn(0);
    when(taskMapper.closeNonTerminalTasksForTerminalInstance(anyString(), anyLong(), anyString()))
        .thenReturn(0);
  }

  @Test
  @DisplayName("SUCCESS → 子表收敛为 SUCCESS")
  void reconcilesSuccess() {
    reconciler.reconcile("ta", 100L, JobInstanceStatus.SUCCESS.code());

    verify(partitionMapper)
        .closeNonTerminalPartitionsForTerminalInstance("ta", 100L, PartitionStatus.SUCCESS.code());
    verify(taskMapper)
        .closeNonTerminalTasksForTerminalInstance("ta", 100L, TaskStatus.SUCCESS.code());
  }

  @Test
  @DisplayName("FAILED → 子表收敛为 FAILED")
  void reconcilesFailed() {
    reconciler.reconcile("ta", 100L, JobInstanceStatus.FAILED.code());

    verify(partitionMapper)
        .closeNonTerminalPartitionsForTerminalInstance("ta", 100L, PartitionStatus.FAILED.code());
    verify(taskMapper)
        .closeNonTerminalTasksForTerminalInstance("ta", 100L, TaskStatus.FAILED.code());
  }

  @Test
  @DisplayName("PARTIAL_FAILED → 子表收敛为 FAILED(部分失败也收敛为 FAILED)")
  void reconcilesPartialFailedAsFailed() {
    reconciler.reconcile("ta", 100L, JobInstanceStatus.PARTIAL_FAILED.code());

    verify(partitionMapper)
        .closeNonTerminalPartitionsForTerminalInstance("ta", 100L, PartitionStatus.FAILED.code());
    verify(taskMapper)
        .closeNonTerminalTasksForTerminalInstance("ta", 100L, TaskStatus.FAILED.code());
  }

  @Test
  @DisplayName("CANCELLED → 子表收敛为 CANCELLED")
  void reconcilesCancelled() {
    reconciler.reconcile("ta", 100L, JobInstanceStatus.CANCELLED.code());

    verify(partitionMapper)
        .closeNonTerminalPartitionsForTerminalInstance(
            "ta", 100L, PartitionStatus.CANCELLED.code());
    verify(taskMapper)
        .closeNonTerminalTasksForTerminalInstance("ta", 100L, TaskStatus.CANCELLED.code());
  }

  @Test
  @DisplayName("TERMINATED → 子表收敛为 TERMINATED")
  void reconcilesTerminated() {
    reconciler.reconcile("ta", 100L, JobInstanceStatus.TERMINATED.code());

    verify(partitionMapper)
        .closeNonTerminalPartitionsForTerminalInstance(
            "ta", 100L, PartitionStatus.TERMINATED.code());
    verify(taskMapper)
        .closeNonTerminalTasksForTerminalInstance("ta", 100L, TaskStatus.TERMINATED.code());
  }

  @Test
  @DisplayName("非业务终态 RUNNING → no-op,不写子表(防止节流计数泄漏)")
  void noopForNonTerminalRunning() {
    reconciler.reconcile("ta", 100L, JobInstanceStatus.RUNNING.code());

    verify(partitionMapper, never())
        .closeNonTerminalPartitionsForTerminalInstance(anyString(), anyLong(), anyString());
    verify(taskMapper, never())
        .closeNonTerminalTasksForTerminalInstance(anyString(), anyLong(), anyString());
  }

  @Test
  @DisplayName("非业务终态 WAITING → no-op")
  void noopForNonTerminalWaiting() {
    reconciler.reconcile("ta", 100L, JobInstanceStatus.WAITING.code());

    verify(partitionMapper, never())
        .closeNonTerminalPartitionsForTerminalInstance(anyString(), anyLong(), anyString());
    verify(taskMapper, never())
        .closeNonTerminalTasksForTerminalInstance(anyString(), anyLong(), anyString());
  }

  @Test
  @DisplayName("未知 code → no-op,不抛错")
  void noopForUnknownCode() {
    reconciler.reconcile("ta", 100L, "UNKNOWN_GARBAGE");

    verify(partitionMapper, never())
        .closeNonTerminalPartitionsForTerminalInstance(anyString(), anyLong(), anyString());
  }

  @Test
  @DisplayName("null code → no-op,不抛 NPE")
  void noopForNullCode() {
    reconciler.reconcile("ta", 100L, null);

    verify(partitionMapper, never())
        .closeNonTerminalPartitionsForTerminalInstance(anyString(), anyLong(), anyString());
  }
}
