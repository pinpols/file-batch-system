package io.github.pinpols.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.service.task.DefaultTaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskAssignmentService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskCreationService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that DefaultTaskExecutionService correctly delegates every method to its sub-service
 * collaborators without adding logic of its own.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("任务执行服务: 创建, 派工, 心跳, 日志与状态更新的委托口径")
class DefaultTaskExecutionServiceTest {

  @Mock
  private TaskCreationService taskCreationService;

  @Mock
  private TaskAssignmentService taskAssignmentService;

  @Mock
  private TaskOutcomeService taskOutcomeService;

  private DefaultTaskExecutionService service;

  @BeforeEach
  void setUp() {
    service = new DefaultTaskExecutionService(
        taskCreationService, taskAssignmentService, taskOutcomeService);
  }

  @Test
  @DisplayName("创建任务委托给任务创建服务并原样返回结果")
  void createTask_delegatesToCreationService() {
    JobTaskEntity task = new JobTaskEntity();
    when(taskCreationService.createTask(task)).thenReturn(task);

    JobTaskEntity result = service.createTask(task);

    assertThat(result).isSameAs(task);
    verify(taskCreationService).createTask(task);
  }

  @Test
  @DisplayName("派工委托给任务分派服务并原样返回任务")
  void assignWorker_delegatesToAssignmentService() {
    JobTaskEntity task = new JobTaskEntity();
    when(taskAssignmentService.assignWorker("t1", 1L, "w1")).thenReturn(task);

    JobTaskEntity result = service.assignWorker("t1", 1L, "w1");

    assertThat(result).isSameAs(task);
    verify(taskAssignmentService).assignWorker("t1", 1L, "w1");
  }

  @Test
  @DisplayName("续租任务委托给分派服务并透传其返回值")
  void renewTaskLease_delegatesToAssignmentService() {
    when(taskAssignmentService.renewTaskLease("t1", 1L, "w1", null)).thenReturn(true);

    boolean result = service.renewTaskLease("t1", 1L, "w1", null);

    assertThat(result).isTrue();
    verify(taskAssignmentService).renewTaskLease("t1", 1L, "w1", null);
  }

  @Test
  @DisplayName("记录心跳委托给分派服务并原样返回结果")
  void recordHeartbeat_delegatesToAssignmentService() {
    var expected = new TaskAssignmentService.TaskHeartbeatResult(true, true);
    when(taskAssignmentService.recordHeartbeat("t1", 1L, "w1", "inv-1", "{}")).thenReturn(expected);

    var result = service.recordHeartbeat("t1", 1L, "w1", "inv-1", "{}");

    assertThat(result).isSameAs(expected);
    verify(taskAssignmentService).recordHeartbeat("t1", 1L, "w1", "inv-1", "{}");
  }

  @Test
  @DisplayName("请求取消委托给分派服务并透传其返回值")
  void requestCancel_delegatesToAssignmentService() {
    when(taskAssignmentService.requestCancel("t1", 1L)).thenReturn(true);

    boolean result = service.requestCancel("t1", 1L);

    assertThat(result).isTrue();
    verify(taskAssignmentService).requestCancel("t1", 1L);
  }

  @Test
  @DisplayName("更新任务状态委托给分派服务并原样返回任务")
  void updateTaskStatus_delegatesToAssignmentService() {
    JobTaskEntity task = new JobTaskEntity();
    when(taskAssignmentService.updateTaskStatus("t1", 1L, "RUNNING", null, null))
        .thenReturn(task);

    JobTaskEntity result = service.updateTaskStatus("t1", 1L, "RUNNING", null, null);

    assertThat(result).isSameAs(task);
    verify(taskAssignmentService).updateTaskStatus("t1", 1L, "RUNNING", null, null);
  }

  @Test
  @DisplayName("追加执行日志委托给分派服务并原样返回日志")
  void appendLog_delegatesToAssignmentService() {
    JobExecutionLogEntity log = new JobExecutionLogEntity();
    when(taskAssignmentService.appendLog(log)).thenReturn(log);

    JobExecutionLogEntity result = service.appendLog(log);

    assertThat(result).isSameAs(log);
    verify(taskAssignmentService).appendLog(log);
  }

  @Test
  @DisplayName("查询执行日志委托给分派服务并返回日志列表")
  void listLogs_delegatesToAssignmentService() {
    List<JobExecutionLogEntity> logs = List.of(new JobExecutionLogEntity());
    when(taskAssignmentService.listLogs("t1", 1L, 1L)).thenReturn(logs);

    List<JobExecutionLogEntity> result = service.listLogs("t1", 1L, 1L);

    assertThat(result).isEqualTo(logs);
    verify(taskAssignmentService).listLogs("t1", 1L, 1L);
  }

  @Test
  @DisplayName("标记任务运行中委托给分派服务并原样返回任务")
  void markRunning_delegatesToAssignmentService() {
    Instant now = BatchDateTimeSupport.utcNow();
    JobTaskEntity task = new JobTaskEntity();
    when(taskAssignmentService.markRunning("t1", 1L, now)).thenReturn(task);

    JobTaskEntity result = service.markRunning("t1", 1L, now);

    assertThat(result).isSameAs(task);
    verify(taskAssignmentService).markRunning("t1", 1L, now);
  }

  @Test
  @DisplayName("应用任务结果委托给结果服务并原样返回任务")
  void applyTaskOutcome_delegatesToOutcomeService() {
    TaskOutcomeCommand command = new TaskOutcomeCommand(
        "t1", 1L, null, true, null, null, null, null, null, null, null, null, null, null);
    JobTaskEntity task = new JobTaskEntity();
    when(taskOutcomeService.applyTaskOutcome(command)).thenReturn(task);

    JobTaskEntity result = service.applyTaskOutcome(command);

    assertThat(result).isSameAs(task);
    verify(taskOutcomeService).applyTaskOutcome(command);
  }

  @Test
  @DisplayName("登记节点运行就绪委托给结果服务并原样返回记录")
  void recordNodeRunReady_delegatesToOutcomeService() {
    WorkflowNodeRunEntity nodeRun = new WorkflowNodeRunEntity();
    when(taskOutcomeService.recordNodeRunReady(1L, "N1", "EXPORT")).thenReturn(nodeRun);

    WorkflowNodeRunEntity result = service.recordNodeRunReady(1L, "N1", "EXPORT");

    assertThat(result).isSameAs(nodeRun);
    verify(taskOutcomeService).recordNodeRunReady(1L, "N1", "EXPORT");
  }
}
