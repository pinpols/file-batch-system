package io.github.pinpols.batch.orchestrator.application.service.replay;

import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyValues;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunExecutionSnapshot;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 为整 Workflow 重放预览解析单个 JOB 节点的不可变定义。 */
@Component
@RequiredArgsConstructor
public class WorkflowReplayJobDefinitionResolver {

    private final WorkflowRunMapper workflowRunMapper;

    public JobDefinitionView resolve(WorkflowReplayJobDefinitionRequest request) {
        validateRequest(request);
        JobDefinitionView sourceDefinition =
                request.sourceSnapshot().jobDefinitions().get(request.nodeCode());
        validateDefinition(sourceDefinition, request.tenantId(), request.jobCode());
        return switch (request.policy()) {
            case USE_ORIGINAL_CONFIG -> sourceDefinition;
            case USE_LATEST_CONFIG ->
                validateAndReturn(
                        request.latestDefinition(), request.tenantId(), request.jobCode(), sourceDefinition.id());
            case USE_SPECIFIED_VERSION ->
                resolveSpecified(request.tenantId(), request.jobCode(), sourceDefinition, request.specifiedVersion());
        };
    }

    private JobDefinitionView resolveSpecified(
            String tenantId, String jobCode, JobDefinitionView sourceDefinition, Integer version) {
        if (version == null || version <= 0) {
            throw invalidPolicy();
        }
        WorkflowRunEntity historicalRun =
                workflowRunMapper.selectLatestJobDefinitionSnapshot(tenantId, sourceDefinition.id(), jobCode, version);
        WorkflowRunExecutionSnapshot historicalSnapshot = WorkflowRunExecutionSnapshot.from(historicalRun);
        if (historicalSnapshot == null || !tenantId.equals(historicalRun.getTenantId())) {
            throw unavailableVersion();
        }
        List<JobDefinitionView> matches = historicalSnapshot.jobDefinitions().values().stream()
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
        boolean requestHasRequiredFields = request != null
                && EmptyValues.isNotBlank(request.tenantId())
                && EmptyValues.isNotBlank(request.nodeCode())
                && EmptyValues.isNotBlank(request.jobCode())
                && request.sourceSnapshot() != null
                && request.policy() != null;
        boolean requestPolicyIsValid = requestHasRequiredFields
                && (request.policy() == ConfigVersionPolicy.USE_SPECIFIED_VERSION
                        ? request.specifiedVersion() != null && request.specifiedVersion() > 0
                        : request.specifiedVersion() == null);
        if (!requestPolicyIsValid) {
            throw invalidPolicy();
        }
    }

    private JobDefinitionView validateAndReturn(
            JobDefinitionView definition, String tenantId, String jobCode, Long sourceDefinitionId) {
        validateDefinition(definition, tenantId, jobCode);
        if (!sourceDefinitionId.equals(definition.id())) {
            throw unavailableVersion();
        }
        return definition;
    }

    private void validateDefinition(JobDefinitionView definition, String tenantId, String jobCode) {
        boolean definitionMatchesReplayRequest = definition != null
                && tenantId.equals(definition.tenantId())
                && jobCode.equals(definition.jobCode())
                && definition.id() != null
                && definition.version() != null;
        if (!definitionMatchesReplayRequest) {
            throw unavailableVersion();
        }
    }

    private BizException invalidPolicy() {
        return BizException.of(ResultCode.INVALID_ARGUMENT, "error.workflow.replay_invalid_policy");
    }

    private BizException unavailableVersion() {
        return BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.replay_config_version_unavailable");
    }
}
