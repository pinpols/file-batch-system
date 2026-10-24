package io.github.pinpols.batch.orchestrator.application.service.replay;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunExecutionSnapshot;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the immutable source inputs required by an ADR-049 whole-Workflow replay. */
@Service
public class WorkflowRunReplaySourceResolver {

  private static final String INVALID_SOURCE_MESSAGE = "error.workflow.replay_source_snapshot_invalid";

  private final WorkflowRunMapper workflowRunMapper;
  private final WorkflowNodeRunMapper workflowNodeRunMapper;
  private final SecretPayloadProtector secretPayloadProtector;

  public WorkflowRunReplaySourceResolver(
      WorkflowRunMapper workflowRunMapper,
      WorkflowNodeRunMapper workflowNodeRunMapper,
      SecretPayloadProtector secretPayloadProtector) {
    this.workflowRunMapper = workflowRunMapper;
    this.workflowNodeRunMapper = workflowNodeRunMapper;
    this.secretPayloadProtector = secretPayloadProtector;
  }

  @Transactional(readOnly = true)
  public Source resolve(String tenantId, Long workflowRunId) {
    boolean replaySourceIdentityIsInvalid =
        tenantId == null || tenantId.isBlank() || workflowRunId == null || workflowRunId <= 0;
    if (replaySourceIdentityIsInvalid) {
      throw invalidSource();
    }
    WorkflowRunEntity sourceRun = workflowRunMapper.selectById(tenantId, workflowRunId);
    boolean sourceRunIsNotEligibleForReplay = sourceRun == null
        || !tenantId.equals(sourceRun.getTenantId())
        || !WorkflowRunStatus.terminalCodes().contains(sourceRun.getRunStatus());
    if (sourceRunIsNotEligibleForReplay) {
      throw invalidSource();
    }
    WorkflowRunExecutionSnapshot executionSnapshot = WorkflowRunExecutionSnapshot.from(sourceRun);
    if (executionSnapshot == null) {
      throw invalidSource();
    }
    WorkflowNodeRunEntity startNode = workflowNodeRunMapper
        .selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, WorkflowNodeType.START.code());
    boolean startNodeIsNotValidReplaySource = startNode == null
        || !workflowRunId.equals(startNode.getWorkflowRunId())
        || !WorkflowNodeType.START.code().equals(startNode.getNodeType())
        || !WorkflowNodeRunStatus.SUCCESS.code().equals(startNode.getNodeStatus())
        || startNode.getSourcePayloadProtected() == null
        || startNode.getSourcePayloadProtected().isBlank();
    if (startNodeIsNotValidReplaySource) {
      throw invalidSource();
    }
    Map<String, Object> rootParams = parseRootParams(startNode.getSourcePayloadProtected());
    return new Source(sourceRun, executionSnapshot, startNode.getSourcePayloadProtected(), rootParams);
  }

  private Map<String, Object> parseRootParams(String protectedPayload) {
    try {
      String json = secretPayloadProtector.revealEncrypted(protectedPayload);
      Object value = JsonUtils.fromJson(json, Object.class);
      if (!(value instanceof Map<?, ?> params)) {
        throw invalidSource();
      }
      Map<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : params.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw invalidSource();
        }
        result.put(key, entry.getValue());
      }
      return Collections.unmodifiableMap(result);
    } catch (BizException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalidSource();
    }
  }

  private static BizException invalidSource() {
    return BizException.of(ResultCode.STATE_CONFLICT, INVALID_SOURCE_MESSAGE);
  }

  /** Root params are recovered only from their encrypted START node payload. */
  public record Source(
      WorkflowRunEntity run,
      WorkflowRunExecutionSnapshot executionSnapshot,
      String protectedRootParams,
      Map<String, Object> rootParams) {

    @Override
    public String toString() {
      return "Source[workflowRunId=" + run.getId()
          + ", workflowDefinitionId=" + executionSnapshot.workflowDefinitionId()
          + ", workflowVersion=" + executionSnapshot.workflowVersion()
          + ", rootParams=<redacted>]";
    }
  }
}
