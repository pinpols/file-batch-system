package io.github.pinpols.batch.orchestrator.application.service.replay;

import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunExecutionSnapshot;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Resolves one JOB node's immutable definition for a whole-Workflow replay preview. */
@Component
@RequiredArgsConstructor
public class WorkflowReplayJobDefinitionResolver {

  private final WorkflowRunMapper workflowRunMapper;

  public JobDefinitionEntity resolve(WorkflowReplayJobDefinitionRequest request) {
    validateRequest(request);
    JobDefinitionEntity sourceDefinition =
        request.sourceSnapshot().jobDefinitions().get(request.nodeCode());
    validateDefinition(sourceDefinition, request.tenantId(), request.jobCode());
    return switch (request.policy()) {
      case USE_ORIGINAL_CONFIG -> sourceDefinition;
      case USE_LATEST_CONFIG ->
        validateAndReturn(
            request.latestDefinition(),
            request.tenantId(),
            request.jobCode(),
            sourceDefinition.id());
      case USE_SPECIFIED_VERSION ->
        resolveSpecified(
            request.tenantId(), request.jobCode(), sourceDefinition, request.specifiedVersion());
    };
  }

  private JobDefinitionEntity resolveSpecified(
      String tenantId, String jobCode, JobDefinitionEntity sourceDefinition, Integer version) {
    if (EmptyChecks.isNull(version) || version <= 0) {
      throw invalidPolicy();
    }
    WorkflowRunEntity historicalRun = workflowRunMapper.selectLatestJobDefinitionSnapshot(
        tenantId, sourceDefinition.id(), jobCode, version);
    WorkflowRunExecutionSnapshot historicalSnapshot =
        WorkflowRunExecutionSnapshot.from(historicalRun);
    if (EmptyChecks.isNull(historicalSnapshot) || !tenantId.equals(historicalRun.getTenantId())) {
      throw unavailableVersion();
    }
    List<JobDefinitionEntity> matches = historicalSnapshot.jobDefinitions().values().stream()
        .filter(definition -> sourceDefinition.id().equals(definition.id()))
        .filter(definition -> jobCode.equals(definition.jobCode()))
        .filter(definition -> version.equals(definition.version()))
        .distinct()
        .toList();
    if (matches.size() != 1) {
      throw unavailableVersion();
    }
    return matches.getFirst();
  }

  private void validateRequest(WorkflowReplayJobDefinitionRequest request) {
    boolean requestHasRequiredFields =
        EmptyChecks.isNotNull(request)
            && EmptyChecks.isNotBlank(request.tenantId())
            && EmptyChecks.isNotBlank(request.nodeCode())
            && EmptyChecks.isNotBlank(request.jobCode())
            && EmptyChecks.isNotNull(request.sourceSnapshot())
            && EmptyChecks.isNotNull(request.policy());
    boolean requestPolicyIsValid =
        requestHasRequiredFields
            && (request.policy() == ConfigVersionPolicy.USE_SPECIFIED_VERSION
                ? EmptyChecks.isNotNull(request.specifiedVersion())
                    && request.specifiedVersion() > 0
                : EmptyChecks.isNull(request.specifiedVersion()));
    if (!requestPolicyIsValid) {
      throw invalidPolicy();
    }
  }

  private JobDefinitionEntity validateAndReturn(
      JobDefinitionEntity definition, String tenantId, String jobCode, Long sourceDefinitionId) {
    validateDefinition(definition, tenantId, jobCode);
    if (!sourceDefinitionId.equals(definition.id())) {
      throw unavailableVersion();
    }
    return definition;
  }

  private void validateDefinition(JobDefinitionEntity definition, String tenantId, String jobCode) {
    boolean definitionMatchesReplayRequest =
        EmptyChecks.isNotNull(definition)
            && tenantId.equals(definition.tenantId())
            && jobCode.equals(definition.jobCode())
            && EmptyChecks.isNotNull(definition.id())
            && EmptyChecks.isNotNull(definition.version());
    if (!definitionMatchesReplayRequest) {
      throw unavailableVersion();
    }
  }

  private BizException invalidPolicy() {
    return BizException.of(ResultCode.INVALID_ARGUMENT, "error.workflow.replay_invalid_policy");
  }

  private BizException unavailableVersion() {
    return BizException.of(
        ResultCode.STATE_CONFLICT, "error.workflow.replay_config_version_unavailable");
  }
}
