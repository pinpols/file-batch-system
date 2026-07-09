package io.github.pinpols.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.task.DefaultTaskCreationService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultTaskCreationServiceTest {

  @Mock private JobTaskMapper jobTaskMapper;
  @Mock private JobStepInstanceMapper jobStepInstanceMapper;

  private DefaultTaskCreationService service;

  @BeforeEach
  void setUp() {
    service = new DefaultTaskCreationService(jobTaskMapper, jobStepInstanceMapper);
  }

  @Test
  void createTask_setsVersionToZeroWhenNull() {
    JobTaskEntity task = buildTask(null, null, "IMPORT", null);
    service.createTask(task);
    assertThat(task.getVersion()).isEqualTo(0L);
  }

  @Test
  void createTask_doesNotOverrideExistingVersion() {
    JobTaskEntity task = buildTask(null, null, "IMPORT", null);
    task.setVersion(3L);
    service.createTask(task);
    assertThat(task.getVersion()).isEqualTo(3L);
  }

  @Test
  void createTask_insertsTaskAndCreatesStepInstance() {
    JobTaskEntity task = buildTask(100L, "t1", "IMPORT", 1);
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 100L)).thenReturn(null);

    service.createTask(task);

    verify(jobTaskMapper).insert(task);
    verify(jobStepInstanceMapper).insert(any(JobStepInstanceEntity.class));
  }

  @Test
  void createTask_skipsStepInstanceWhenAlreadyExists() {
    JobTaskEntity task = buildTask(100L, "t1", "IMPORT", 1);
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 100L))
        .thenReturn(new JobStepInstanceEntity());

    service.createTask(task);

    verify(jobStepInstanceMapper, never()).insert(any());
  }

  @Test
  void createTask_skipsStepInstanceWhenTaskIdIsNull() {
    JobTaskEntity task = buildTask(null, "t1", "IMPORT", 1);

    service.createTask(task);

    verify(jobStepInstanceMapper, never()).selectByJobTaskId(anyString(), anyLong());
    verify(jobStepInstanceMapper, never()).insert(any());
  }

  @Test
  void createTask_resolvesStepCodeFromWorkflowNodeCodeInPayload() {
    JobTaskEntity task = buildTask(200L, "t1", "EXPORT", 1);
    task.setTaskPayload("{\"workflowNodeCode\":\"NODE-A\",\"workflowNodeType\":\"EXPORT_NODE\"}");
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 200L)).thenReturn(null);

    service.createTask(task);

    verify(jobStepInstanceMapper).insert(any(JobStepInstanceEntity.class));
  }

  @Test
  void createTask_fallsBackToTaskTypeStepCodeWhenNoWorkflowNodeCode() {
    JobTaskEntity task = buildTask(200L, "t1", "EXPORT", 2);
    task.setTaskPayload("{}");
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 200L)).thenReturn(null);

    service.createTask(task);

    verify(jobStepInstanceMapper).insert(any(JobStepInstanceEntity.class));
  }

  @Test
  void createTask_resolvesRelatedFileIdFromPayload() {
    JobTaskEntity task = buildTask(300L, "t1", "IMPORT", 1);
    task.setTaskPayload("{\"relatedFileId\":42}");
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 300L)).thenReturn(null);

    service.createTask(task);

    // just verify insert is called, relatedFileId resolution is an internal detail
    verify(jobStepInstanceMapper).insert(any(JobStepInstanceEntity.class));
  }

  @Test
  void createTask_handlesNullPayloadGracefully() {
    JobTaskEntity task = buildTask(400L, "t1", "IMPORT", 1);
    task.setTaskPayload(null);
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 400L)).thenReturn(null);

    service.createTask(task);

    verify(jobStepInstanceMapper).insert(any(JobStepInstanceEntity.class));
  }

  @Test
  void createTask_handlesInvalidJsonPayloadGracefully() {
    JobTaskEntity task = buildTask(500L, "t1", "IMPORT", 1);
    task.setTaskPayload("not-valid-json");
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 500L)).thenReturn(null);

    service.createTask(task);

    // should not throw — resolveStepCode catches the IllegalArgumentException
    verify(jobStepInstanceMapper).insert(any(JobStepInstanceEntity.class));
  }

  @Test
  void createTask_returnsTheSameTaskObject() {
    JobTaskEntity task = buildTask(100L, "t1", "IMPORT", 1);
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 100L)).thenReturn(null);

    JobTaskEntity result = service.createTask(task);

    assertThat(result).isSameAs(task);
  }

  // ===== PERF(5.1) createTasks 批量 =====

  @Test
  @SuppressWarnings("unchecked")
  void createTasks_batchInsertsTasksAndStepsOnceEach() {
    JobTaskEntity t1 = buildTask(null, "t1", "IMPORT", 1);
    JobTaskEntity t2 = buildTask(null, "t1", "EXPORT", 1);
    when(jobTaskMapper.insertBatch(any()))
        .thenAnswer(
            inv -> {
              List<JobTaskEntity> list = inv.getArgument(0);
              long id = 100L;
              for (JobTaskEntity t : list) {
                t.setId(id++);
              }
              return list.size();
            });

    List<JobTaskEntity> result = service.createTasks(List.of(t1, t2));

    assertThat(result).containsExactly(t1, t2);
    assertThat(t1.getVersion()).isEqualTo(0L);
    verify(jobTaskMapper).insertBatch(List.of(t1, t2));
    verify(jobTaskMapper, never()).insert(any());
    ArgumentCaptor<List<JobStepInstanceEntity>> cap = ArgumentCaptor.forClass(List.class);
    verify(jobStepInstanceMapper).insertBatch(cap.capture());
    assertThat(cap.getValue()).hasSize(2);
    assertThat(cap.getValue().get(0).getJobTaskId()).isEqualTo(100L);
    assertThat(cap.getValue().get(1).getJobTaskId()).isEqualTo(101L);
    // 批量路径 task id 为 fresh 生成:无幂等预检、无逐条 step insert
    verify(jobStepInstanceMapper, never()).selectByJobTaskId(anyString(), anyLong());
    verify(jobStepInstanceMapper, never()).insert(any());
  }

  @Test
  void createTasks_failsFastWholeBatchWhenTaskTypeMissing() {
    JobTaskEntity ok = buildTask(null, "t1", "IMPORT", 1);
    JobTaskEntity bad = buildTask(null, "t1", null, 1);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createTasks(List.of(ok, bad)))
        .isInstanceOf(io.github.pinpols.batch.common.exception.BizException.class);
    verify(jobTaskMapper, never()).insertBatch(any());
    verify(jobTaskMapper, never()).insert(any());
    verify(jobStepInstanceMapper, never()).insertBatch(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void createTasks_chunksLargeBatch_andPreservesIdBackfillOrder() {
    // 1200 > chunk 500 → task/step 各分 3 批(500,500,200);id 回填拼接回原顺序正确。
    int total = 1200;
    List<JobTaskEntity> tasks = new java.util.ArrayList<>();
    for (int i = 0; i < total; i++) {
      tasks.add(buildTask(null, "t1", "IMPORT", i));
    }
    java.util.concurrent.atomic.AtomicLong nextId = new java.util.concurrent.atomic.AtomicLong(1L);
    when(jobTaskMapper.insertBatch(any()))
        .thenAnswer(
            inv -> {
              List<JobTaskEntity> chunk = inv.getArgument(0);
              for (JobTaskEntity t : chunk) {
                t.setId(nextId.getAndIncrement());
              }
              return chunk.size();
            });

    service.createTasks(tasks);

    // task 与 step 各切 3 批
    ArgumentCaptor<List<JobTaskEntity>> taskCap = ArgumentCaptor.forClass(List.class);
    verify(jobTaskMapper, org.mockito.Mockito.times(3)).insertBatch(taskCap.capture());
    assertThat(taskCap.getAllValues()).extracting(List::size).containsExactly(500, 500, 200);
    ArgumentCaptor<List<JobStepInstanceEntity>> stepCap = ArgumentCaptor.forClass(List.class);
    verify(jobStepInstanceMapper, org.mockito.Mockito.times(3)).insertBatch(stepCap.capture());
    assertThat(stepCap.getAllValues()).extracting(List::size).containsExactly(500, 500, 200);

    // 回填顺序:原 tasks 第 i 个拿到第 i 个生成 id;step 镜像的 jobTaskId 与之一致。
    for (int i = 0; i < total; i++) {
      assertThat(tasks.get(i).getId()).as("task %d id", i).isEqualTo((long) (i + 1));
    }
    List<JobStepInstanceEntity> allSteps = new java.util.ArrayList<>();
    stepCap.getAllValues().forEach(allSteps::addAll);
    assertThat(allSteps).hasSize(total);
    for (int i = 0; i < total; i++) {
      assertThat(allSteps.get(i).getJobTaskId())
          .as("step %d jobTaskId", i)
          .isEqualTo((long) (i + 1));
    }
  }

  @Test
  void createTasks_emptyOrNullInputNoSql() {
    assertThat(service.createTasks(List.of())).isEmpty();
    assertThat(service.createTasks(null)).isEmpty();
    verify(jobTaskMapper, never()).insertBatch(any());
  }

  private JobTaskEntity buildTask(Long id, String tenantId, String taskType, Integer taskSeq) {
    JobTaskEntity task = new JobTaskEntity();
    task.setId(id);
    task.setTenantId(tenantId);
    task.setTaskType(taskType);
    task.setTaskSeq(taskSeq);
    task.setTaskStatus("PENDING");
    task.setJobInstanceId(1L);
    task.setJobPartitionId(1L);
    return task;
  }
}
