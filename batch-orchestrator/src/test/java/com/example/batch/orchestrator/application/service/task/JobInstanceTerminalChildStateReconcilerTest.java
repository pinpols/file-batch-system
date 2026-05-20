package com.example.batch.orchestrator.application.service.task;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * 守护实例终态 → 子表(partition/task)收口的映射:
 *
 * <ul>
 *   <li>SUCCESS → 子表 SUCCESS
 *   <li>FAILED / PARTIAL_FAILED → 子表 FAILED(混合失败都收口为 FAILED)
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
  @DisplayName("SUCCESS → 子表收口为 SUCCESS")
  void reconciles_success() {
    reconciler.reconcile("ta", 100L, JobInstanceStatus.SUCCESS.code());

    verify(partitionMapper)
        .closeNonTerminalPartitionsForTerminalInstance("ta", 100L, PartitionStatus.SUCCESS.code());
    verify(taskMapper)
        .closeNonTerminalTasksForTerminalInstance("ta", 100L, TaskStatus.SUCCESS.code());
  }

  @Test
  @DisplayName("FAILED → 子表收口为 FAILED")
  void reconciles_failed() {
    reconciler.reconcile("ta", 100L, JobInstanceStatus.FAILED.code());

    verify(partitionMapper)
        .closeNonTerminalPartitionsForTerminalInstance("ta", 100L, PartitionStatus.FAILED.code());
    verify(taskMapper)
        .closeNonTerminalTasksForTerminalInstance("ta", 100L, TaskStatus.FAILED.code());
  }

  @Test
  @DisplayName("PARTIAL_FAILED → 子表收口为 FAILED(部分失败也收口为 FAILED)")
  void reconciles_partial_failed_as_failed() {
    reconciler.reconcile("ta", 100L, JobInstanceStatus.PARTIAL_FAILED.code());

    verify(partitionMapper)
        .closeNonTerminalPartitionsForTerminalInstance("ta", 100L, PartitionStatus.FAILED.code());
    verify(taskMapper)
        .closeNonTerminalTasksForTerminalInstance("ta", 100L, TaskStatus.FAILED.code());
  }

  @Test
  @DisplayName("CANCELLED → 子表收口为 CANCELLED")
  void reconciles_cancelled() {
    reconciler.reconcile("ta", 100L, JobInstanceStatus.CANCELLED.code());

    verify(partitionMapper)
        .closeNonTerminalPartitionsForTerminalInstance(
            "ta", 100L, PartitionStatus.CANCELLED.code());
    verify(taskMapper)
        .closeNonTerminalTasksForTerminalInstance("ta", 100L, TaskStatus.CANCELLED.code());
  }

  @Test
  @DisplayName("TERMINATED → 子表收口为 TERMINATED")
  void reconciles_terminated() {
    reconciler.reconcile("ta", 100L, JobInstanceStatus.TERMINATED.code());

    verify(partitionMapper)
        .closeNonTerminalPartitionsForTerminalInstance(
            "ta", 100L, PartitionStatus.TERMINATED.code());
    verify(taskMapper)
        .closeNonTerminalTasksForTerminalInstance("ta", 100L, TaskStatus.TERMINATED.code());
  }

  @Test
  @DisplayName("非业务终态 RUNNING → no-op,不写子表(防止节流计数泄漏)")
  void noop_for_non_terminal_running() {
    reconciler.reconcile("ta", 100L, JobInstanceStatus.RUNNING.code());

    verify(partitionMapper, never())
        .closeNonTerminalPartitionsForTerminalInstance(anyString(), anyLong(), anyString());
    verify(taskMapper, never())
        .closeNonTerminalTasksForTerminalInstance(anyString(), anyLong(), anyString());
  }

  @Test
  @DisplayName("非业务终态 WAITING → no-op")
  void noop_for_non_terminal_waiting() {
    reconciler.reconcile("ta", 100L, JobInstanceStatus.WAITING.code());

    verify(partitionMapper, never())
        .closeNonTerminalPartitionsForTerminalInstance(anyString(), anyLong(), anyString());
    verify(taskMapper, never())
        .closeNonTerminalTasksForTerminalInstance(anyString(), anyLong(), anyString());
  }

  @Test
  @DisplayName("未知 code → no-op,不抛错")
  void noop_for_unknown_code() {
    reconciler.reconcile("ta", 100L, "UNKNOWN_GARBAGE");

    verify(partitionMapper, never())
        .closeNonTerminalPartitionsForTerminalInstance(anyString(), anyLong(), anyString());
  }

  @Test
  @DisplayName("null code → no-op,不抛 NPE")
  void noop_for_null_code() {
    reconciler.reconcile("ta", 100L, null);

    verify(partitionMapper, never())
        .closeNonTerminalPartitionsForTerminalInstance(anyString(), anyLong(), anyString());
  }
}
