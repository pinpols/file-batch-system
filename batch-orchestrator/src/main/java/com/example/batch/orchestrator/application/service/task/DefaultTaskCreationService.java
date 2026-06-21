package com.example.batch.orchestrator.application.service.task;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
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
    if (task != null && task.getVersion() == null) {
      task.setVersion(0L);
    }
    // R7 log-audit defensive：job_task.task_type 列 NOT NULL，但历史发现 workflow
    // dispatch 路径在 targetJobCode 解析不到 jobDefinition 时会传 null，撞 PSQLException
    // 导致 TriggerLaunchConsumer 无限循环。上游 DefaultWorkflowNodeDispatchService 已经
    // fail-fast，这里再加一层回退，把 DB 抛错前置成业务异常，错误信息更可读。
    if (task != null && (task.getTaskType() == null || task.getTaskType().isBlank())) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.job_task.task_type_required",
          task.getJobInstanceId());
    }
    jobTaskMapper.insert(task);
    createStepInstance(task);
    return task;
  }

  private void createStepInstance(JobTaskEntity task) {
    if (task == null || task.getId() == null) {
      return;
    }
    JobStepInstanceEntity existing =
        jobStepInstanceMapper.selectByJobTaskId(task.getTenantId(), task.getId());
    if (existing != null) {
      return;
    }
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
    jobStepInstanceMapper.insert(stepInstance);
  }

  private String resolveStepCode(JobTaskEntity task) {
    String workflowNodeCode =
        payloadStringValue(task == null ? null : task.getTaskPayload(), "workflowNodeCode");
    if (workflowNodeCode != null && !workflowNodeCode.isBlank()) {
      return workflowNodeCode;
    }
    String taskType = task == null ? null : task.getTaskType();
    Integer taskSeq = task == null ? null : task.getTaskSeq();
    return Texts.hasText(taskType)
        ? taskType + ":" + (taskSeq == null ? 1 : taskSeq)
        : "EXECUTION:" + (taskSeq == null ? 1 : taskSeq);
  }

  private String resolveStepType(JobTaskEntity task) {
    String workflowNodeType =
        payloadStringValue(task == null ? null : task.getTaskPayload(), "workflowNodeType");
    if (workflowNodeType != null && !workflowNodeType.isBlank()) {
      return workflowNodeType;
    }
    return task == null ? "EXECUTION" : task.getTaskType();
  }

  private Long resolveRelatedFileId(JobTaskEntity task) {
    return firstPositiveLong(
        payloadLongValue(task == null ? null : task.getTaskPayload(), "relatedFileId"),
        payloadLongValue(task == null ? null : task.getTaskPayload(), "fileId"),
        payloadLongValue(task == null ? null : task.getTaskPayload(), "sourceFileId"));
  }

  @SuppressWarnings("unchecked")
  private String payloadStringValue(String payloadJson, String fieldName) {
    if (payloadJson == null || payloadJson.isBlank() || fieldName == null || fieldName.isBlank()) {
      return null;
    }
    try {
      Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
      if (payloadObject instanceof Map<?, ?> payloadMap) {
        Object value = ((Map<String, Object>) payloadMap).get(fieldName);
        return value == null ? null : String.valueOf(value);
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
    if (payloadJson == null || payloadJson.isBlank() || fieldName == null || fieldName.isBlank()) {
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
    if (candidate == null) {
      return null;
    }
    String text = String.valueOf(candidate).trim();
    if (text.isEmpty()) {
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
      if (candidate != null && candidate > 0) {
        return candidate;
      }
    }
    return null;
  }
}
