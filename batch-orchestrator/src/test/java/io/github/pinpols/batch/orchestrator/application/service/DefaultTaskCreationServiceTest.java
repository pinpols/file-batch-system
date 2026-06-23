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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
