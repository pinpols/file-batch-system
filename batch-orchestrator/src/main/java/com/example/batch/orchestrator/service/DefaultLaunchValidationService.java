package com.example.batch.orchestrator.service;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.utils.Guard;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Launch 前置校验与配置加载：把 trigger_request / job_definition / workflow_definition / 现有 instance 一次性读齐供
 * {@link DefaultLaunchService} 使用；校验失败会把 {@code trigger_request} 打成 REJECTED 再抛异常， 避免请求挂在 ACCEPTED
 * 永远没落地。
 *
 * <p>job_definition / workflow_definition 走 Redis 缓存（{@code OrchestratorConfigCacheService}），
 * launch 热路径上不直连 DB 读配置。WORKFLOW 类型强制有 workflow_definition，其他类型（IMPORT/EXPORT/DISPATCH/GENERAL）
 * 不要求。
 */
@Service
@RequiredArgsConstructor
public class DefaultLaunchValidationService implements LaunchValidationService {

  private final TriggerRequestMapper triggerRequestMapper;
  private final OrchestratorConfigCacheService configCacheService;
  private final JobInstanceMapper jobInstanceMapper;

  /**
   * 注意副作用：job/workflow 定义缺失时，本方法会把 trigger_request 状态更新为 REJECTED 后再抛 {@code
   * NOT_FOUND}——保证请求有终态而非停留在 ACCEPTED。调用方不需要也不应重复打 REJECTED。
   */
  @Override
  public LaunchLoadResult load(LaunchRequest request) {
    validate(request);

    TriggerRequestEntity triggerRequest =
        triggerRequestMapper.selectByTenantAndRequestId(request.tenantId(), request.requestId());
    if (triggerRequest == null) {
      throw BizException.of(
          ResultCode.NOT_FOUND, "error.trigger.request_not_found", request.requestId());
    }

    JobDefinitionEntity jobDefinition =
        configCacheService.findEnabledJobDefinition(request.tenantId(), request.jobCode());
    if (jobDefinition == null) {
      triggerRequestMapper.updateAcceptance(
          request.tenantId(), request.requestId(), BatchStatusConstants.REJECTED, null);
      throw BizException.of(ResultCode.NOT_FOUND, "error.job.definition_not_found");
    }

    // workflow definition 仅对 WORKFLOW 类型 job 必须存在；IMPORT/EXPORT/DISPATCH/GENERAL 无需关联 workflow
    // definition
    WorkflowDefinitionEntity workflowDefinition = null;
    if (JobType.WORKFLOW.code().equals(jobDefinition.jobType())) {
      workflowDefinition =
          configCacheService.findEnabledWorkflowDefinition(request.tenantId(), request.jobCode());
      if (workflowDefinition == null) {
        triggerRequestMapper.updateAcceptance(
            request.tenantId(), request.requestId(), BatchStatusConstants.REJECTED, null);
        throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.definition_not_found_for_job");
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
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    if (request.jobCode() == null || request.jobCode().isBlank()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.job.code_required");
    }
    if (request.bizDate() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.biz_date_required");
    }
    if (request.triggerType() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.trigger.type_required");
    }
  }
}
