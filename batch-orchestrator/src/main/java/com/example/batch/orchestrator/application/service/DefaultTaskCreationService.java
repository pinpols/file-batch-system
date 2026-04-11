package com.example.batch.orchestrator.application.service;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;

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
        return StringUtils.hasText(taskType)
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
        if (payloadJson == null
                || payloadJson.isBlank()
                || fieldName == null
                || fieldName.isBlank()) {
            return null;
        }
        try {
            Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
            if (payloadObject instanceof Map<?, ?> payloadMap) {
                Object value = ((Map<String, Object>) payloadMap).get(fieldName);
                return value == null ? null : String.valueOf(value);
            }
        } catch (IllegalArgumentException exception) {
            return null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Long payloadLongValue(String payloadJson, String fieldName) {
        if (payloadJson == null
                || payloadJson.isBlank()
                || fieldName == null
                || fieldName.isBlank()) {
            return null;
        }
        try {
            Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
            if (payloadObject instanceof Map<?, ?> payloadMap) {
                Object value = ((Map<String, Object>) payloadMap).get(fieldName);
                return toPositiveLong(value);
            }
        } catch (IllegalArgumentException exception) {
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
