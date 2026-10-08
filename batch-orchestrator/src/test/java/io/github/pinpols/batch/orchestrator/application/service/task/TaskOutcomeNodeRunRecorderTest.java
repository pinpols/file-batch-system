package io.github.pinpols.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DuplicateKeyException;

/**
 * 守护 workflow_node_run 写入的并发幂等语义:
 *
 * <ul>
 *   <li>节点进入 READY/RUNNING 时遇唯一约束冲突不抛错,而是返回已有记录(防止并发重复插入)
 *   <li>runSeq 递增: 第二次 record 同节点应拿到 +1 的 runSeq
 * </ul>
 *
 * <p>task outcome 主流程的状态推进由 Testcontainers 集成测试覆盖。
 */
@DisplayName("节点运行记录器: 并发幂等、序号推进与字段持久化")
class TaskOutcomeNodeRunRecorderTest {

  @Test
  @DisplayName("active nodes:忽略已完成的 FORK/END，并保留最新一轮仍等待的节点")
  void shouldResolveActiveNodeCodes_whenUsingLatestRunState() {
    WorkflowNodeRunEntity fork = nodeRun("FORK", 1, WorkflowNodeRunStatus.SUCCESS.code());
    WorkflowNodeRunEntity end = nodeRun("END", 1, WorkflowNodeRunStatus.SUCCESS.code());
    WorkflowNodeRunEntity oldBranch = nodeRun("BRANCH", 1, WorkflowNodeRunStatus.RUNNING.code());
    WorkflowNodeRunEntity finishedBranch =
        nodeRun("BRANCH", 2, WorkflowNodeRunStatus.SUCCESS.code());
    WorkflowNodeRunEntity waiting =
        nodeRun("WAITING", 1, WorkflowNodeRunStatus.WAITING_DEPENDENCY.code());

    assertThat(TaskOutcomeStatePolicy.resolveActiveNodeCodes(
            java.util.List.of(fork, end, oldBranch, finishedBranch, waiting)))
        .containsExactly("WAITING");
  }

  @Mock
  private WorkflowNodeRunMapper workflowNodeRunMapper;

  private TaskOutcomeNodeRunRecorder recorder;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    recorder = new TaskOutcomeNodeRunRecorder(
        new OrchestratorWorkflowMappers(null, null, workflowNodeRunMapper));
  }

  // ===== READY 状态记录 =====

  @Test
  @DisplayName("首次写入 READY 记录时从 runSeq=1 开始")
  void shouldSetRunSeqToOne_whenFirstReadyInsert() {
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "n1"))
        .thenReturn(null);

    WorkflowNodeRunEntity result = recorder.recordReady(10L, "n1", "TASK");

    assertThat(result.getRunSeq()).isEqualTo(1);
    assertThat(result.getNodeStatus()).isEqualTo(WorkflowNodeRunStatus.READY.code());
    assertThat(result.getRetryCount()).isZero();
    verify(workflowNodeRunMapper).insert(any());
  }

  @Test
  @DisplayName("已有节点记录时 READY 记录序号递增")
  void shouldIncrementRunSeq_whenReadyInsertRepeats() {
    WorkflowNodeRunEntity existing = new WorkflowNodeRunEntity();
    existing.setRunSeq(2);
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "n1"))
        .thenReturn(existing);

    WorkflowNodeRunEntity result = recorder.recordReady(10L, "n1", "TASK");

    assertThat(result.getRunSeq()).isEqualTo(3);
  }

  @Test
  @DisplayName("READY 记录并发冲突时返回已存在记录")
  void shouldReturnExistingRecord_whenReadyInsertConflicts() {
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "n1"))
        .thenReturn(null) // nextRunSeq 读
        .thenReturn(existingRunSeq(5)); // catch block 重读
    doThrow(new DuplicateKeyException("uk_conflict"))
        .when(workflowNodeRunMapper)
        .insert(any());

    WorkflowNodeRunEntity result = recorder.recordReady(10L, "n1", "TASK");

    assertThat(result.getRunSeq()).isEqualTo(5);
    verify(workflowNodeRunMapper, times(1)).insert(any());
  }

  // ===== RUNNING 状态记录 =====

  @Test
  @DisplayName("首次写入 RUNNING 记录时保留开始时间")
  void shouldStartRunning_whenFirstStartInsert() {
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "n1"))
        .thenReturn(null);

    Instant t = Instant.parse("2026-05-20T10:00:00Z");
    WorkflowNodeRunEntity result = recorder.recordStart(10L, "n1", "TASK", t);

    assertThat(result.getRunSeq()).isEqualTo(1);
    assertThat(result.getNodeStatus()).isEqualTo(WorkflowNodeRunStatus.RUNNING.code());
    assertThat(result.getStartedAt()).isEqualTo(t);
  }

  @Test
  @DisplayName("RUNNING 记录并发冲突时返回已存在记录")
  void shouldReturnExistingRecord_whenStartInsertConflicts() {
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(10L, "n1"))
        .thenReturn(null)
        .thenReturn(existingRunSeq(2));
    doThrow(new DuplicateKeyException("uk_conflict"))
        .when(workflowNodeRunMapper)
        .insert(any());

    WorkflowNodeRunEntity result = recorder.recordStart(10L, "n1", "TASK", Instant.now());

    assertThat(result.getRunSeq()).isEqualTo(2);
  }

  @Test
  @DisplayName("RUNNING 记录持久化节点标识、类型和序号")
  void shouldPersistFullEntity_whenStartingNodeRun() {
    when(workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(anyLong(), any()))
        .thenReturn(null);

    recorder.recordStart(10L, "n1", "GATEWAY", Instant.now());

    ArgumentCaptor<WorkflowNodeRunEntity> cap =
        ArgumentCaptor.forClass(WorkflowNodeRunEntity.class);
    verify(workflowNodeRunMapper).insert(cap.capture());
    WorkflowNodeRunEntity inserted = cap.getValue();
    assertThat(inserted.getWorkflowRunId()).isEqualTo(10L);
    assertThat(inserted.getNodeCode()).isEqualTo("n1");
    assertThat(inserted.getNodeType()).isEqualTo("GATEWAY");
    assertThat(inserted.getRunSeq()).isEqualTo(1);
    assertThat(inserted.getNodeStatus()).isEqualTo(WorkflowNodeRunStatus.RUNNING.code());
  }

  private WorkflowNodeRunEntity existingRunSeq(int seq) {
    WorkflowNodeRunEntity e = new WorkflowNodeRunEntity();
    e.setRunSeq(seq);
    e.setNodeCode("n1");
    return e;
  }

  private static WorkflowNodeRunEntity nodeRun(String code, int runSeq, String status) {
    WorkflowNodeRunEntity nodeRun = new WorkflowNodeRunEntity();
    nodeRun.setNodeCode(code);
    nodeRun.setRunSeq(runSeq);
    nodeRun.setNodeStatus(status);
    return nodeRun;
  }
}
