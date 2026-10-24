package io.github.pinpols.batch.orchestrator.application.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.constants.NodeOutputKeys;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunExecutionSnapshot;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 依据冻结的依赖图和持久化节点输出，审计显式声明的计数连续性。
 * 上报的范围只能证明数据可比；此校验不能证明实际读取来源，也
 * 不会改变执行状态。缺少证据时记录为 UNVERIFIED。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CountContinuityOutboxService {
  private static final String MISMATCH_EVENT = "workflow.node.count.continuity.v1";
  private static final String CHECKED_EVENT = "workflow.node.count.continuity.checked.v1";
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final WorkflowRunMapper workflowRunMapper;
  private final WorkflowNodeRunMapper workflowNodeRunMapper;
  private final DomainEventPublisher domainEventPublisher;
  private final ObjectMapper objectMapper;

  /** 必须在节点输出写入后、同一事务中执行。 */
  @Transactional(propagation = Propagation.MANDATORY)
  public void checkContinuity(Long workflowRunId, String nodeCode, String currentOutputJson) {
    if (EmptyChecks.isNull(workflowRunId) || EmptyChecks.isBlank(nodeCode)) {
      return;
    }
    WorkflowRunEntity run = workflowRunMapper.selectByIdAnyTenant(workflowRunId);
    if (EmptyChecks.isNull(run) || EmptyChecks.isBlank(run.getTenantId())) {
      return;
    }
    WorkflowNodeRunEntity current =
        workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, nodeCode);
    Inspection inspection = new Inspection(run, nodeCode, current, null, null);
    WorkflowRunExecutionSnapshot snapshot;
    try {
      snapshot = WorkflowRunExecutionSnapshot.from(run);
    } catch (RuntimeException invalidSnapshot) {
      writeResult(inspection, Decision.unverified("SNAPSHOT_INVALID"));
      return;
    }
    if (EmptyChecks.isNull(snapshot)) {
      writeResult(inspection, Decision.unverified("SNAPSHOT_UNAVAILABLE"));
      return;
    }
    WorkflowNodeEntity definition = snapshot.node(nodeCode);
    if (EmptyChecks.isNull(definition)) {
      writeResult(inspection, Decision.unverified("NODE_DEFINITION_MISSING"));
      return;
    }
    Map<String, Object> params;
    try {
      params = readObject(definition.getNodeParams());
    } catch (JsonProcessingException invalidParams) {
      writeResult(inspection, Decision.unverified("DECLARATION_INVALID"));
      return;
    }
    Object raw = params.get("countContinuity");
    if (EmptyChecks.isNull(raw)) {
      return;
    }
    if (raw instanceof Map<?, ?> declaration && Boolean.FALSE.equals(declaration.get("enabled"))) {
      return;
    }
    Declaration declaration = readDeclaration(raw);
    if (EmptyChecks.isNull(declaration) || nodeCode.equals(declaration.upstreamNodeCode())) {
      writeResult(inspection, Decision.unverified("DECLARATION_INVALID"));
      return;
    }
    inspection = new Inspection(run, nodeCode, current, null, declaration);
    WorkflowNodeEntity upstreamDefinition = snapshot.node(declaration.upstreamNodeCode());
    boolean upstreamDefinitionIsUnavailableOrDisconnected =
        !Boolean.TRUE.equals(definition.getEnabled())
        || EmptyChecks.isNull(upstreamDefinition)
        || !Boolean.TRUE.equals(upstreamDefinition.getEnabled())
        || snapshot.incoming(nodeCode).stream()
            .noneMatch(edge -> declaration.upstreamNodeCode().equals(edge.getFromNodeCode()));
    if (upstreamDefinitionIsUnavailableOrDisconnected) {
      writeResult(inspection, Decision.unverified("DATA_EDGE_MISSING"));
      return;
    }
    WorkflowNodeRunEntity upstream = workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
        workflowRunId, declaration.upstreamNodeCode());
    inspection = new Inspection(run, nodeCode, current, upstream, declaration);
    if (!successfulExecution(current, workflowRunId, nodeCode)) {
      writeResult(inspection, Decision.unverified("CURRENT_NOT_SUCCESS"));
      return;
    }
    if (!successfulExecution(upstream, workflowRunId, declaration.upstreamNodeCode())) {
      writeResult(inspection, Decision.unverified("UPSTREAM_NOT_SUCCESS"));
      return;
    }
    Decision decision;
    try {
      Map<String, Object> currentOutput = readObject(current.getOutput());
      Map<String, Object> expectedCurrentOutput = readObject(currentOutputJson);
      if (!currentOutput.equals(expectedCurrentOutput)) {
        decision = Decision.unverified("CURRENT_OUTPUT_CHANGED");
      } else {
        Map<String, Object> upstreamOutput = readObject(upstream.getOutput());
        decision = compare(upstreamOutput, currentOutput, declaration);
      }
    } catch (JsonProcessingException invalidOutput) {
      decision = Decision.unverified("OUTPUT_INVALID");
    }
    writeResult(inspection, decision);
  }

  private boolean successfulExecution(WorkflowNodeRunEntity node, Long runId, String nodeCode) {
    return EmptyChecks.isNotNull(node)
        && EmptyChecks.isNotNull(node.getId())
        && Objects.equals(runId, node.getWorkflowRunId())
        && Objects.equals(nodeCode, node.getNodeCode())
        && WorkflowNodeRunStatus.SUCCESS.code().equals(node.getNodeStatus());
  }

  private Declaration readDeclaration(Object raw) {
    if (!(raw instanceof Map<?, ?> value) || !Boolean.TRUE.equals(value.get("enabled"))) {
      return null;
    }
    String upstream = nonblankString(value.get("upstreamNodeCode"));
    Long delta = 0L;
    if (value.containsKey("expectedDelta")) {
      delta = nonnegativeLong(value.get("expectedDelta"));
    }
    String reason = nonblankString(value.get("deltaReason"));
    if (EmptyChecks.isNull(upstream)
        || EmptyChecks.isNull(delta)
        || (delta > 0 && EmptyChecks.isNull(reason))) {
      return null;
    }
    return new Declaration(upstream, delta, reason);
  }

  private Decision compare(
      Map<String, Object> upstream, Map<String, Object> current, Declaration declaration) {
    Long outputCount = nonnegativeLong(upstream.get(NodeOutputKeys.OUTPUT_COUNT));
    Long inputCount = nonnegativeLong(current.get(NodeOutputKeys.INPUT_COUNT));
    CountContext outputContext = readContext(upstream.get(NodeOutputKeys.OUTPUT_COUNT_CONTEXT));
    CountContext inputContext = readContext(current.get(NodeOutputKeys.INPUT_COUNT_CONTEXT));
    if (EmptyChecks.isNull(outputCount) || EmptyChecks.isNull(inputCount)) {
      return new Decision(
          Status.UNVERIFIED, "COUNT_INVALID", outputCount, inputCount, outputContext, inputContext);
    }
    if (EmptyChecks.isNull(outputContext) || EmptyChecks.isNull(inputContext)) {
      return new Decision(
          Status.UNVERIFIED,
          "COUNT_CONTEXT_MISSING",
          outputCount,
          inputCount,
          outputContext,
          inputContext);
    }
    if (!outputContext.scopeId().equals(inputContext.scopeId())) {
      return new Decision(
          Status.UNVERIFIED,
          "SCOPE_MISMATCH",
          outputCount,
          inputCount,
          outputContext,
          inputContext);
    }
    if (!outputContext.unit().equals(inputContext.unit())) {
      return new Decision(
          Status.UNVERIFIED, "UNIT_MISMATCH", outputCount, inputCount, outputContext, inputContext);
    }
    boolean hasMissingDeltaEvidence =
        declaration.expectedDelta() > 0
            && (EmptyChecks.isNull(inputContext.excludedCount())
                || EmptyChecks.isBlank(inputContext.exclusionEvidenceRef()));
    if (hasMissingDeltaEvidence) {
      return new Decision(
          Status.UNVERIFIED,
          "DELTA_EVIDENCE_MISSING",
          outputCount,
          inputCount,
          outputContext,
          inputContext);
    }
    boolean excludedCountDoesNotMatchExpectedDelta =
        EmptyChecks.isNotNull(inputContext.excludedCount())
            && inputContext.excludedCount() != declaration.expectedDelta();
    if (excludedCountDoesNotMatchExpectedDelta) {
      return new Decision(
          Status.UNVERIFIED,
          "DELTA_EVIDENCE_MISMATCH",
          outputCount,
          inputCount,
          outputContext,
          inputContext);
    }
    // 两个计数都是非负 long，因此相减不会溢出有符号 long。
    boolean matched = outputCount - inputCount == declaration.expectedDelta();
    return new Decision(
        matched ? Status.MATCHED : Status.MISMATCH,
        matched ? "COUNTS_ACCOUNTED" : "COUNT_DIFFERENCE",
        outputCount,
        inputCount,
        outputContext,
        inputContext);
  }

  private void writeResult(Inspection inspection, Decision decision) {
    WorkflowRunEntity run = inspection.run();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", "v1");
    payload.put("tenantId", run.getTenantId());
    payload.put("workflowRunId", run.getId());
    payload.put("nodeCode", inspection.nodeCode());
    payload.put("nodeRunId", nodeRunId(inspection.current()));
    payload.put("upstreamNodeRunId", nodeRunId(inspection.upstream()));
    payload.put("status", decision.status().name());
    payload.put("reason", decision.reason());
    payload.put("upstreamOutputCount", decision.upstreamOutput());
    payload.put("currentInputCount", decision.currentInput());
    payload.put("upstreamCountContext", decision.upstreamContext());
    payload.put("currentCountContext", decision.currentContext());
    Declaration declaration = inspection.declaration();
    payload.put(
        "upstreamNodeCode",
        EmptyChecks.isNull(declaration) ? null : declaration.upstreamNodeCode());
    payload.put(
        "expectedDelta", EmptyChecks.isNull(declaration) ? null : declaration.expectedDelta());
    payload.put("deltaReason", EmptyChecks.isNull(declaration) ? null : declaration.deltaReason());
    String eventKey =
        run.getTenantId() + ":continuity:" + run.getId() + ":" + nodeRunId(inspection.current())
            + ":" + nodeRunId(inspection.upstream()) + ":" + inspection.nodeCode();
    boolean mismatch = decision.status() == Status.MISMATCH;
    DomainEvent event = DomainEvent.builder(run.getTenantId())
        .aggregate("WORKFLOW_RUN", run.getId())
        .type(mismatch ? MISMATCH_EVENT : CHECKED_EVENT)
        .key(eventKey)
        .payload(payload)
        .build();
    Long eventId = domainEventPublisher.publish(event);
    if (EmptyChecks.isNull(eventId) || decision.status() == Status.MATCHED) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          logDecision(inspection, decision);
        }
      });
    } else {
      logDecision(inspection, decision);
    }
  }

  private void logDecision(Inspection inspection, Decision decision) {
    WorkflowRunEntity run = inspection.run();
    if (decision.status() == Status.MISMATCH) {
      log.warn(
          "count continuity mismatch (alert-only): workflowRunId={}, nodeCode={}, upstreamOutput={}, currentInput={}, expectedDelta={}",
          run.getId(),
          inspection.nodeCode(),
          decision.upstreamOutput(),
          decision.currentInput(),
          inspection.declaration().expectedDelta());
    } else if (decision.status() == Status.UNVERIFIED) {
      log.warn(
          "count continuity unverified: workflowRunId={}, nodeCode={}, reason={}",
          run.getId(),
          inspection.nodeCode(),
          decision.reason());
    }
  }

  private Long nodeRunId(WorkflowNodeRunEntity node) {
    return EmptyChecks.isNull(node) ? null : node.getId();
  }

  private CountContext readContext(Object raw) {
    if (!(raw instanceof Map<?, ?> value)) return null;
    String scope = nonblankString(value.get("scopeId"));
    String unit = nonblankString(value.get("unit"));
    Long excluded = null;
    if (value.containsKey("excludedCount")) {
      Object excludedCountValue = value.get("excludedCount");
      excluded = nonnegativeLong(excludedCountValue);
      if (EmptyChecks.isNull(excluded)) return null;
    }
    String evidenceRef = nonblankString(value.get("exclusionEvidenceRef"));
    return EmptyChecks.isNull(scope) || EmptyChecks.isNull(unit)
        ? null
        : new CountContext(scope, unit, excluded, evidenceRef);
  }

  private String nonblankString(Object value) {
    return value instanceof String text && EmptyChecks.isNotBlank(text) ? text : null;
  }

  private Long nonnegativeLong(Object value) {
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      long count = ((Number) value).longValue();
      return count >= 0 ? count : null;
    }
    if (value instanceof BigInteger integer) {
      try {
        long count = integer.longValueExact();
        return count >= 0 ? count : null;
      } catch (ArithmeticException outOfRange) {
        return null;
      }
    }
    return null;
  }

  private Map<String, Object> readObject(String json) throws JsonProcessingException {
    if (EmptyChecks.isBlank(json)) return Map.of();
    Map<String, Object> result = objectMapper.readValue(json, MAP_TYPE);
    return EmptyChecks.isNull(result) ? Map.of() : result;
  }

  private record Declaration(String upstreamNodeCode, long expectedDelta, String deltaReason) {}

  private record CountContext(
      String scopeId, String unit, Long excludedCount, String exclusionEvidenceRef) {}

  private record Inspection(
      WorkflowRunEntity run,
      String nodeCode,
      WorkflowNodeRunEntity current,
      WorkflowNodeRunEntity upstream,
      Declaration declaration) {}

  private enum Status {
    MATCHED,
    MISMATCH,
    UNVERIFIED
  }

  private record Decision(
      Status status,
      String reason,
      Long upstreamOutput,
      Long currentInput,
      CountContext upstreamContext,
      CountContext currentContext) {
    static Decision unverified(String reason) {
      return new Decision(Status.UNVERIFIED, reason, null, null, null, null);
    }
  }
}
