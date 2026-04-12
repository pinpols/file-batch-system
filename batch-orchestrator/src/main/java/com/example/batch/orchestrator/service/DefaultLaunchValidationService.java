package com.example.batch.orchestrator.service;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.utils.Guard;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionRecord;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultLaunchValidationService implements LaunchValidationService {

  private final TriggerRequestMapper triggerRequestMapper;
  private final OrchestratorConfigCacheService configCacheService;
  private final JobInstanceMapper jobInstanceMapper;

  @Override
  public LaunchLoadResult load(LaunchRequest request) {
    validate(request);

    TriggerRequestEntity triggerRequest =
        Guard.requireFound(
            triggerRequestMapper.selectByTenantAndRequestId(
                request.tenantId(), request.requestId()),
            "trigger request not found");

    JobDefinitionRecord jobDefinition =
        configCacheService.findEnabledJobDefinition(request.tenantId(), request.jobCode());
    if (jobDefinition == null) {
      triggerRequestMapper.updateAcceptance(
          request.tenantId(), request.requestId(), BatchStatusConstants.REJECTED, null);
      throw new BizException(ResultCode.NOT_FOUND, "job definition not found");
    }

    // workflow definition 仅对 WORKFLOW 类型 job 必须存在；IMPORT/EXPORT/DISPATCH/GENERAL 无需关联 workflow
    // definition
    WorkflowDefinitionRecord workflowDefinition = null;
    if (JobType.WORKFLOW.code().equals(jobDefinition.jobType())) {
      workflowDefinition =
          configCacheService.findEnabledWorkflowDefinition(request.tenantId(), request.jobCode());
      if (workflowDefinition == null) {
        triggerRequestMapper.updateAcceptance(
            request.tenantId(), request.requestId(), BatchStatusConstants.REJECTED, null);
        throw new BizException(ResultCode.NOT_FOUND, "workflow definition not found for job code");
      }
    }

    JobInstanceEntity existingInstance =
        jobInstanceMapper.selectByTenantAndDedupKey(
            request.tenantId(), triggerRequest.getDedupKey());

    return new LaunchLoadResult(
        triggerRequest, jobDefinition, workflowDefinition, existingInstance);
  }

  private void validate(LaunchRequest request) {
    Guard.require(request != null, "launch request is required");
    if (request.tenantId() == null || request.tenantId().isBlank()) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "tenantId is required");
    }
    if (request.jobCode() == null || request.jobCode().isBlank()) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "jobCode is required");
    }
    if (request.bizDate() == null) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "bizDate is required");
    }
    if (request.triggerType() == null) {
      throw new BizException(ResultCode.INVALID_ARGUMENT, "triggerType is required");
    }
  }
}
