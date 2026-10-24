package io.github.pinpols.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.application.service.governance.RelatedFileReferenceValidator;
import io.github.pinpols.batch.orchestrator.application.service.task.DefaultTaskCreationService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@DisplayName("任务创建服务: 版本初始化, 步骤实例生成与批量分片口径")
class DefaultTaskCreationServiceTest {

  @Mock
  private JobTaskMapper jobTaskMapper;

  @Mock
  private JobStepInstanceMapper jobStepInstanceMapper;

  @Mock
  private RelatedFileReferenceValidator relatedFileReferenceValidator;

  private DefaultTaskCreationService service;

  @BeforeEach
  void setUp() {
    service = new DefaultTaskCreationService(
        jobTaskMapper, jobStepInstanceMapper, relatedFileReferenceValidator);
  }

  @Test
  @DisplayName("任务版本为空时初始化为零")
  void createTask_setsVersionToZeroWhenNull() {
    JobTaskEntity task = buildTask(null, null, "IMPORT", null);
    service.createTask(task);
    assertThat(task.getVersion()).isZero();
  }

  @Test
  @DisplayName("任务已有版本时保持原值不变")
  void createTask_doesNotOverrideExistingVersion() {
    JobTaskEntity task = buildTask(null, null, "IMPORT", null);
    task.setVersion(3L);
    service.createTask(task);
    assertThat(task.getVersion()).isEqualTo(3L);
  }

  @Test
  @DisplayName("创建任务时落库任务并生成对应步骤实例")
  void createTask_insertsTaskAndCreatesStepInstance() {
    JobTaskEntity task = buildTask(100L, "t1", "IMPORT", 1);
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 100L)).thenReturn(null);

    service.createTask(task);

    verify(jobTaskMapper).insert(task);
    verify(jobStepInstanceMapper).insert(any(JobStepInstanceEntity.class));
  }

  @Test
  @DisplayName("步骤实例已存在时不重复生成")
  void createTask_skipsStepInstanceWhenAlreadyExists() {
    JobTaskEntity task = buildTask(100L, "t1", "IMPORT", 1);
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 100L))
        .thenReturn(new JobStepInstanceEntity());

    service.createTask(task);

    verify(jobStepInstanceMapper, never()).insert(any());
  }

  @Test
  @DisplayName("任务标识为空时既不查询也不生成步骤实例")
  void createTask_skipsStepInstanceWhenTaskIdIsNull() {
    JobTaskEntity task = buildTask(null, "t1", "IMPORT", 1);

    service.createTask(task);

    verify(jobStepInstanceMapper, never()).selectByJobTaskId(anyString(), anyLong());
    verify(jobStepInstanceMapper, never()).insert(any());
  }

  @Test
  @DisplayName("任务缺少所属实例业务日时在写库前拒绝")
  void shouldRejectTaskWithoutInstanceBusinessDate() {
    JobTaskEntity task = buildTask(100L, "t1", "IMPORT", 1);
    task.setJobInstanceBizDate(null);

    assertThatThrownBy(() -> service.createTask(task))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT);
          assertThat(failure.getMessage()).isEqualTo("error.job_task.instance_biz_date_required");
        });

    verifyNoInteractions(jobTaskMapper, jobStepInstanceMapper);
  }

  @Test
  @DisplayName("载荷带有流程节点编码时按该编码解析步骤并生成实例")
  void createTask_resolvesStepCodeFromWorkflowNodeCodeInPayload() {
    JobTaskEntity task = buildTask(200L, "t1", "EXPORT", 1);
    task.setTaskPayload("{\"workflowNodeCode\":\"NODE-A\",\"workflowNodeType\":\"EXPORT_NODE\"}");
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 200L)).thenReturn(null);

    service.createTask(task);

    verify(jobStepInstanceMapper).insert(any(JobStepInstanceEntity.class));
  }

  @Test
  @DisplayName("载荷没有流程节点编码时回退使用任务类型作为步骤编码")
  void createTask_fallsBackToTaskTypeStepCodeWhenNoWorkflowNodeCode() {
    JobTaskEntity task = buildTask(200L, "t1", "EXPORT", 2);
    task.setTaskPayload("{}");
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 200L)).thenReturn(null);

    service.createTask(task);

    verify(jobStepInstanceMapper).insert(any(JobStepInstanceEntity.class));
  }

  @Test
  @DisplayName("载荷带有相关文件标识时解析后生成步骤实例")
  void createTask_resolvesRelatedFileIdFromPayload() {
    JobTaskEntity task = buildTask(300L, "t1", "IMPORT", 1);
    task.setTaskPayload("{\"relatedFileId\":42}");
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 300L)).thenReturn(null);

    service.createTask(task);

    // just verify insert is called, relatedFileId resolution is an internal detail
    verify(jobStepInstanceMapper).insert(any(JobStepInstanceEntity.class));
    verify(relatedFileReferenceValidator).validate("t1", 42L);
  }

  @Test
  @DisplayName("跨租户文件引用在任务及步骤写入前被拒绝")
  void createTask_rejectsForeignTenantFileReferenceBeforeInsert() {
    JobTaskEntity task = buildTask(301L, "t1", "IMPORT", 1);
    task.setTaskPayload("{\"relatedFileId\":99}");
    Mockito.doThrow(new BizException(ResultCode.NOT_FOUND, "error.file.record_not_found"))
        .when(relatedFileReferenceValidator)
        .validate("t1", 99L);

    Assertions.assertThatThrownBy(() -> service.createTask(task)).isInstanceOf(BizException.class);

    verifyNoInteractions(jobTaskMapper, jobStepInstanceMapper);
  }

  @Test
  @DisplayName("载荷为空时正常完成创建, 不抛异常")
  void createTask_handlesNullPayloadGracefully() {
    JobTaskEntity task = buildTask(400L, "t1", "IMPORT", 1);
    task.setTaskPayload(null);
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 400L)).thenReturn(null);

    service.createTask(task);

    verify(jobStepInstanceMapper).insert(any(JobStepInstanceEntity.class));
  }

  @Test
  @DisplayName("载荷格式非法时正常完成创建, 不抛异常")
  void createTask_handlesInvalidJsonPayloadGracefully() {
    JobTaskEntity task = buildTask(500L, "t1", "IMPORT", 1);
    task.setTaskPayload("not-valid-json");
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 500L)).thenReturn(null);

    service.createTask(task);

    // should not throw — resolveStepCode catches the IllegalArgumentException
    verify(jobStepInstanceMapper).insert(any(JobStepInstanceEntity.class));
  }

  @Test
  @DisplayName("创建任务返回传入的同一个任务对象")
  void createTask_returnsTheSameTaskObject() {
    JobTaskEntity task = buildTask(100L, "t1", "IMPORT", 1);
    when(jobStepInstanceMapper.selectByJobTaskId("t1", 100L)).thenReturn(null);

    JobTaskEntity result = service.createTask(task);

    assertThat(result).isSameAs(task);
  }

  // ===== PERF(5.1) createTasks 批量 =====

  @Test
  @DisplayName("批量创建时任务与步骤各批量落库一次, 不做逐条预检")
  @SuppressWarnings("unchecked")
  void createTasks_batchInsertsTasksAndStepsOnceEach() {
    JobTaskEntity t1 = buildTask(null, "t1", "IMPORT", 1);
    JobTaskEntity t2 = buildTask(null, "t1", "EXPORT", 1);
    t2.setJobInstanceId(2L);
    t2.setJobPartitionId(null);
    when(jobTaskMapper.insertBatch(any())).thenAnswer(inv -> {
      List<JobTaskEntity> list = inv.getArgument(0);
      long id = 100L;
      for (JobTaskEntity t : list) {
        t.setId(id++);
      }
      return list.size();
    });

    List<JobTaskEntity> result = service.createTasks(List.of(t1, t2));

    assertThat(result).containsExactly(t1, t2);
    assertThat(t1.getVersion()).isZero();
    verify(jobTaskMapper).insertBatch(List.of(t1, t2));
    verify(jobTaskMapper, never()).insert(any());
    ArgumentCaptor<List<JobStepInstanceEntity>> cap = ArgumentCaptor.forClass(List.class);
    verify(jobStepInstanceMapper).insertBatch(cap.capture());
    assertThat(cap.getValue()).hasSize(2);
    assertThat(cap.getValue().get(0).getJobTaskId()).isEqualTo(100L);
    assertThat(cap.getValue().get(1).getJobTaskId()).isEqualTo(101L);
    for (int i = 0; i < result.size(); i++) {
      assertThat(cap.getValue().get(i).getTenantId()).isEqualTo(result.get(i).getTenantId());
      assertThat(cap.getValue().get(i).getJobInstanceId())
          .isEqualTo(result.get(i).getJobInstanceId());
      assertThat(cap.getValue().get(i).getJobPartitionId())
          .isEqualTo(result.get(i).getJobPartitionId());
    }
    // 批量路径 task id 为 fresh 生成:无幂等预检、无逐条 step insert
    verify(jobStepInstanceMapper, never()).selectByJobTaskId(anyString(), anyLong());
    verify(jobStepInstanceMapper, never()).insert(any());
  }

  @Test
  @DisplayName("批次中存在缺少任务类型的任务时整批快速失败, 不落库")
  void createTasks_failsFastWholeBatchWhenTaskTypeMissing() {
    JobTaskEntity ok = buildTask(null, "t1", "IMPORT", 1);
    JobTaskEntity bad = buildTask(null, "t1", null, 1);

    Assertions.assertThatThrownBy(() -> service.createTasks(List.of(ok, bad)))
        .isInstanceOf(BizException.class);
    verify(jobTaskMapper, never()).insertBatch(any());
    verify(jobTaskMapper, never()).insert(any());
    verify(jobStepInstanceMapper, never()).insertBatch(any());
  }

  @Test
  @DisplayName("超大批次按上限切分落库, 并保持标识回填顺序与步骤归属一致")
  @SuppressWarnings("unchecked")
  void createTasks_chunksLargeBatch_andPreservesIdBackfillOrder() {
    // 1200 > chunk 500 → task/step 各分 3 批(500,500,200);id 回填拼接回原顺序正确。
    int total = 1200;
    List<JobTaskEntity> tasks = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      tasks.add(buildTask(null, "t1", "IMPORT", i));
    }
    AtomicLong nextId = new AtomicLong(1L);
    when(jobTaskMapper.insertBatch(any())).thenAnswer(inv -> {
      List<JobTaskEntity> chunk = inv.getArgument(0);
      for (JobTaskEntity t : chunk) {
        t.setId(nextId.getAndIncrement());
      }
      return chunk.size();
    });

    service.createTasks(tasks);

    // task 与 step 各切 3 批
    ArgumentCaptor<List<JobTaskEntity>> taskCap = ArgumentCaptor.forClass(List.class);
    verify(jobTaskMapper, times(3)).insertBatch(taskCap.capture());
    assertThat(taskCap.getAllValues()).extracting(List::size).containsExactly(500, 500, 200);
    ArgumentCaptor<List<JobStepInstanceEntity>> stepCap = ArgumentCaptor.forClass(List.class);
    verify(jobStepInstanceMapper, times(3)).insertBatch(stepCap.capture());
    assertThat(stepCap.getAllValues()).extracting(List::size).containsExactly(500, 500, 200);

    // 回填顺序:原 tasks 第 i 个拿到第 i 个生成 id;step 镜像的 jobTaskId 与之一致。
    for (int i = 0; i < total; i++) {
      assertThat(tasks.get(i).getId()).as("task %d id", i).isEqualTo((long) (i + 1));
    }
    List<JobStepInstanceEntity> allSteps = new ArrayList<>();
    stepCap.getAllValues().forEach(allSteps::addAll);
    assertThat(allSteps).hasSize(total);
    for (int i = 0; i < total; i++) {
      assertThat(allSteps.get(i).getJobTaskId()).as("step %d jobTaskId", i).isEqualTo((long)
          (i + 1));
    }
  }

  @Test
  @DisplayName("入参为空或空列表时返回空且不执行任何写库")
  void createTasks_emptyOrNullInputNoSql() {
    assertThat(service.createTasks(List.of())).isEmpty();
    assertThat(service.createTasks(null)).isEmpty();
    verify(jobTaskMapper, never()).insertBatch(any());
  }

  @Test
  @DisplayName("任务父分区归属冲突返回稳定业务错误, 不继续写步骤")
  void shouldReturnConflict_whenTaskParentReferenceChanges() {
    JobTaskEntity task = buildTask(700L, "t1", "IMPORT", 1);
    doThrow(ownershipFailure("23503", "fk_job_task_partition_tenant_instance"))
        .when(jobTaskMapper)
        .insert(task);

    assertThatThrownBy(() -> service.createTask(task))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.CONFLICT);
          assertThat(failure.getMessage()).isEqualTo("error.job_task.reference_changed");
        });
    verifyNoInteractions(jobStepInstanceMapper);
  }

  @Test
  @DisplayName("JobInstance 复合外键竞态返回稳定业务错误")
  void shouldReturnConflict_whenTaskParentInstanceReferenceChanges() {
    JobTaskEntity task = buildTask(701L, "t1", "IMPORT", 1);
    doThrow(ownershipFailure("23503", "fk_job_task_instance_tenant_date"))
        .when(jobTaskMapper)
        .insert(task);

    assertThatThrownBy(() -> service.createTask(task))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.CONFLICT);
          assertThat(failure.getMessage()).isEqualTo("error.job_task.reference_changed");
        });
    verifyNoInteractions(jobStepInstanceMapper);
  }

  @Test
  @DisplayName("批量步骤归属冲突返回稳定业务错误")
  @SuppressWarnings("unchecked")
  void shouldReturnConflict_whenBatchStepOwnershipChanges() {
    JobTaskEntity task = buildTask(null, "t1", "IMPORT", 1);
    when(jobTaskMapper.insertBatch(any())).thenAnswer(invocation -> {
      List<JobTaskEntity> tasks = invocation.getArgument(0);
      tasks.get(0).setId(800L);
      return tasks.size();
    });
    doThrow(ownershipFailure("23503", "fk_job_step_task_ownership"))
        .when(jobStepInstanceMapper)
        .insertBatch(any());

    assertThatThrownBy(() -> service.createTasks(List.of(task)))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.CONFLICT);
          assertThat(failure.getMessage()).isEqualTo("error.job_task.reference_changed");
        });
  }

  @ParameterizedTest
  @CsvSource({
    "23503,other_constraint",
    "23505,fk_job_step_task_ownership",
    "23503,fk_job_step_task_ownership_extra"
  })
  @DisplayName("非目标完整性错误原样上抛, 不按相似名称误判归属冲突")
  void shouldPreserveDatabaseFailure_whenConstraintOrSqlStateDiffers(
      String state, String constraint) {
    JobTaskEntity task = buildTask(900L, "t1", "IMPORT", 1);
    DataIntegrityViolationException original = ownershipFailure(state, constraint);
    doThrow(original).when(jobTaskMapper).insert(task);

    assertThatThrownBy(() -> service.createTask(task)).isSameAs(original);
    verifyNoInteractions(jobStepInstanceMapper);
  }

  @Test
  @DisplayName("驱动缺少约束元数据时保留原数据库错误, 不产生空指针")
  void shouldPreserveDatabaseFailure_whenConstraintMetadataIsMissing() {
    JobTaskEntity task = buildTask(901L, "t1", "IMPORT", 1);
    PSQLException sql =
        new PSQLException(new ServerErrorMessage("SERROR\0C23503\0Munknown foreign key\0\0"));
    DataIntegrityViolationException original =
        new DataIntegrityViolationException("missing metadata", sql);
    doThrow(original).when(jobTaskMapper).insert(task);

    assertThatThrownBy(() -> service.createTask(task)).isSameAs(original);
    verifyNoInteractions(jobStepInstanceMapper);
  }

  private DataIntegrityViolationException ownershipFailure(String state, String constraint) {
    ServerErrorMessage error = new ServerErrorMessage(
        "SERROR\0C" + state + "\0Mowned reference changed\0n" + constraint + "\0\0");
    return new DataIntegrityViolationException("ownership failure", new PSQLException(error));
  }

  private JobTaskEntity buildTask(Long id, String tenantId, String taskType, Integer taskSeq) {
    JobTaskEntity task = new JobTaskEntity();
    task.setId(id);
    task.setTenantId(tenantId);
    task.setTaskType(taskType);
    task.setTaskSeq(taskSeq);
    task.setTaskStatus("PENDING");
    task.setJobInstanceId(1L);
    task.setJobInstanceBizDate(LocalDate.of(2026, 3, 22));
    task.setJobPartitionId(1L);
    return task;
  }
}
