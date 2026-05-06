package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.service.governance.AlertEventService;
import com.example.batch.orchestrator.application.service.workflow.WorkflowGraphValidator;
import com.example.batch.orchestrator.application.service.workflow.WorkflowValidationResult;
import com.example.batch.orchestrator.config.WorkflowValidatorReconcileProperties;
import com.example.batch.orchestrator.controller.request.AlertEmitRequest;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-025 §决策 §定期对账 — daily reconciler 重新跑 validator。
 *
 * <p>已 enable 的 workflow 可能因为引用的 calendar / job_definition 被 disable / 删除而退化为 invalid；reconciler
 * 不修复，只 emit alert event 让 ops 人工处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowValidatorReconciler {

  static final String ALERT_TYPE = "WORKFLOW_DEFINITION_INVALID";

  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final WorkflowGraphValidator validator;
  private final AlertEventService alertEventService;
  private final WorkflowValidatorReconcileProperties properties;
  private final OrchestratorGracefulShutdown gracefulShutdown;

  @Scheduled(fixedDelayString = "${batch.workflow.validator.poll-interval-millis:86400000}")
  @SchedulerLock(
      name = "workflow_validator_reconcile",
      lockAtMostFor = "PT30M",
      lockAtLeastFor = "PT5M")
  public void scheduledReconcile() {
    if (!properties.isEnabled() || gracefulShutdown.isDraining()) {
      return;
    }
    List<WorkflowDefinitionEntity> enabled =
        workflowDefinitionMapper.selectAllEnabled(properties.getBatchSize());
    if (enabled == null || enabled.isEmpty()) {
      return;
    }
    int invalidCount = 0;
    for (WorkflowDefinitionEntity wf : enabled) {
      if (wf == null || wf.id() == null) continue;
      try {
        WorkflowValidationResult result = validator.validate(wf.id());
        if (result.hasErrors()) {
          invalidCount++;
          emitInvalid(wf, result);
        }
      } catch (Exception failure) {
        log.warn(
            "workflow_validator reconcile error: workflowDefId={}, msg={}",
            wf.id(),
            failure.getMessage());
      }
    }
    if (invalidCount > 0) {
      log.warn(
          "workflow_validator reconcile finished: total={}, invalid={}",
          enabled.size(),
          invalidCount);
    }
  }

  private void emitInvalid(WorkflowDefinitionEntity wf, WorkflowValidationResult result) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("workflowDefinitionId", wf.id());
    detail.put("workflowCode", wf.workflowCode());
    detail.put("errors", result.errors());
    detail.put("warnings", result.warnings());
    AlertEmitRequest request =
        AlertEmitRequest.builder()
            .tenantId(wf.tenantId())
            .serviceName("batch-orchestrator")
            .alertType(ALERT_TYPE)
            .severity("ERROR")
            .title("workflow definition invalid: " + wf.workflowCode())
            .detailJson(JsonUtils.toJson(detail))
            .resourceKey(wf.tenantId() + ":" + wf.id())
            .build();
    alertEventService.emit(request);
  }
}
