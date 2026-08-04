package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务创建服务的默认实现，负责持久化 {@link JobTaskEntity} 并同步创建对应的 {@link JobStepInstanceEntity}。
 *
 * <p>创建时会自动将任务版本号初始化为 0，并通过解析 {@code taskPayload} JSON 提取 {@code workflowNodeCode}、{@code
 * workflowNodeType} 和文件关联 ID 等字段， 以确保步骤实例能够正确反映工作流节点语义。若同一任务的步骤实例已存在则幂等跳过， 保证在重复调用场景下不产生重复记录。
 *
 * <p>所有写操作在同一事务内完成，保证任务与步骤实例的一致性。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultTaskCreationService implements TaskCreationService {

  private final JobTaskMapper jobTaskMapper;
  private final JobStepInstanceMapper jobStepInstanceMapper;

  @Override
  @Transactional
  public JobTaskEntity createTask(JobTaskEntity task) {
    validateForCreate(task);
    jobTaskMapper.insert(task);
    createStepInstance(task);
    return task;
  }

  /**
   * PERF(5.1): 批量创建 —— task 一次多行 INSERT（生成 id 按序回填）+ step 镜像一次多行 INSERT。
   *
   * <p>与逐条 {@link #createTask} 的语义差异仅一处：step 镜像不再做 selectByJobTaskId 幂等预检 —— 批量路径的 task id
   * 是本事务内刚生成的，不可能已存在 step 镜像；单条路径（重复调用场景可达）保留预检。
   */
  @Override
  @Transactional
  public List<JobTaskEntity> createTasks(List<JobTaskEntity> tasks) {
    if (EmptyChecks.isEmpty(tasks)) {
      return EmptyChecks.isNull(tasks) ? List.of() : tasks;
    }
    // 先整批校验再写：任何一项非法（task_type 缺失）整批不落库，避免半批状态。
    for (JobTaskEntity task : tasks) {
      validateForCreate(task);
    }
    // R2:按固定 chunk 切批,防单条多行 INSERT 的绑定参数越 PG 65535 上限整批回滚。
    // useGeneratedKeys 通过 subList 视图回填,id 落回原 tasks 对应位置(顺序正确)。
    BatchInsertChunks.insertInChunks(
        tasks, BatchInsertChunks.DEFAULT_CHUNK_SIZE, jobTaskMapper::insertBatch);
    List<JobStepInstanceEntity> stepInstances = new ArrayList<>(tasks.size());
    for (JobTaskEntity task : tasks) {
      if (EmptyChecks.isNull(task.getId())) {
        continue;
      }
      stepInstances.add(buildStepInstance(task));
    }
    if (EmptyChecks.isNotEmpty(stepInstances)) {
      BatchInsertChunks.insertInChunks(
          stepInstances, BatchInsertChunks.DEFAULT_CHUNK_SIZE, jobStepInstanceMapper::insertBatch);
    }
    return tasks;
  }

  private void validateForCreate(JobTaskEntity task) {
    if (EmptyChecks.isNotNull(task) && EmptyChecks.isNull(task.getVersion())) {
      task.setVersion(0L);
    }
    // R7 log-audit defensive：job_task.task_type 列 NOT NULL，但历史发现 workflow
    // dispatch 路径在 targetJobCode 解析不到 jobDefinition 时会传 null，撞 PSQLException
    // 导致 TriggerLaunchConsumer 无限循环。上游 DefaultWorkflowNodeDispatchService 已经
    // fail-fast，这里再加一层回退，把 DB 抛错前置成业务异常，错误信息更可读。
    if (EmptyChecks.isNotNull(task) && EmptyChecks.isBlank(task.getTaskType())) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.job_task.task_type_required",
          task.getJobInstanceId());
    }
  }

  private void createStepInstance(JobTaskEntity task) {
    if (EmptyChecks.isNull(task) || EmptyChecks.isNull(task.getId())) {
      return;
    }
    JobStepInstanceEntity existing =
        jobStepInstanceMapper.selectByJobTaskId(task.getTenantId(), task.getId());
    if (EmptyChecks.isNotNull(existing)) {
      return;
    }
    jobStepInstanceMapper.insert(buildStepInstance(task));
  }

  private JobStepInstanceEntity buildStepInstance(JobTaskEntity task) {
    JobStepInstanceEntity stepInstance = new JobStepInstanceEntity();
    stepInstance.setTenantId(task.getTenantId());
    stepInstance.setJobInstanceId(task.getJobInstanceId());
    stepInstance.setJobPartitionId(task.getJobPartitionId());
    stepInstance.setJobTaskId(task.getId());
    stepInstance.setStepCode(resolveStepCode(task));
    stepInstance.setStepType(resolveStepType(task));
    stepInstance.setStepStatus(task.getTaskStatus());
    stepInstance.setRetryCount(0);
    stepInstance.setRelatedFileId(resolveRelatedFileId(task));
    stepInstance.setVersion(0L);
    return stepInstance;
  }

  private String resolveStepCode(JobTaskEntity task) {
    String workflowNodeCode = payloadStringValue(
        EmptyChecks.isNull(task) ? null : task.getTaskPayload(), "workflowNodeCode");
    if (EmptyChecks.isNotBlank(workflowNodeCode)) {
      return workflowNodeCode;
    }
    String taskType = EmptyChecks.isNull(task) ? null : task.getTaskType();
    Integer taskSeq = EmptyChecks.isNull(task) ? null : task.getTaskSeq();
    return EmptyChecks.isNotBlank(taskType)
        ? taskType + ":" + (EmptyChecks.isNull(taskSeq) ? 1 : taskSeq)
        : "EXECUTION:" + (EmptyChecks.isNull(taskSeq) ? 1 : taskSeq);
  }

  private String resolveStepType(JobTaskEntity task) {
    String workflowNodeType = payloadStringValue(
        EmptyChecks.isNull(task) ? null : task.getTaskPayload(), "workflowNodeType");
    if (EmptyChecks.isNotBlank(workflowNodeType)) {
      return workflowNodeType;
    }
    return EmptyChecks.isNull(task) ? "EXECUTION" : task.getTaskType();
  }

  private Long resolveRelatedFileId(JobTaskEntity task) {
    return firstPositiveLong(
        payloadLongValue(EmptyChecks.isNull(task) ? null : task.getTaskPayload(), "relatedFileId"),
        payloadLongValue(EmptyChecks.isNull(task) ? null : task.getTaskPayload(), "fileId"),
        payloadLongValue(EmptyChecks.isNull(task) ? null : task.getTaskPayload(), "sourceFileId"));
  }

  @SuppressWarnings("unchecked")
  private String payloadStringValue(String payloadJson, String fieldName) {
    if (EmptyChecks.isBlank(payloadJson) || EmptyChecks.isBlank(fieldName)) {
      return null;
    }
    try {
      Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
      if (payloadObject instanceof Map<?, ?> payloadMap) {
        Object value = ((Map<String, Object>) payloadMap).get(fieldName);
        return EmptyChecks.isNull(value) ? null : String.valueOf(value);
      }
    } catch (IllegalArgumentException exception) {
      SwallowedExceptionLogger.info(
          DefaultTaskCreationService.class, "catch:IllegalArgumentException", exception);

      return null;
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private Long payloadLongValue(String payloadJson, String fieldName) {
    if (EmptyChecks.isBlank(payloadJson) || EmptyChecks.isBlank(fieldName)) {
      return null;
    }
    try {
      Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
      if (payloadObject instanceof Map<?, ?> payloadMap) {
        Object value = ((Map<String, Object>) payloadMap).get(fieldName);
        return toPositiveLong(value);
      }
    } catch (IllegalArgumentException exception) {
      SwallowedExceptionLogger.info(
          DefaultTaskCreationService.class, "catch:IllegalArgumentException", exception);

      return null;
    }
    return null;
  }

  private Long toPositiveLong(Object candidate) {
    if (candidate instanceof Number number) {
      long value = number.longValue();
      return value > 0 ? value : null;
    }
    if (EmptyChecks.isNull(candidate)) {
      return null;
    }
    String text = String.valueOf(candidate).trim();
    if (EmptyChecks.isEmpty(text)) {
      return null;
    }
    try {
      long value = Long.parseLong(text);
      return value > 0 ? value : null;
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(
          DefaultTaskCreationService.class, "catch:NumberFormatException", ignored);

      return null;
    }
  }

  private Long firstPositiveLong(Long... candidates) {
    for (Long candidate : candidates) {
      if (EmptyChecks.isNotNull(candidate) && candidate > 0) {
        return candidate;
      }
    }
    return null;
  }
}
