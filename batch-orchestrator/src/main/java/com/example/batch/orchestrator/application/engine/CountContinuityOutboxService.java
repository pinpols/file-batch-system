package com.example.batch.orchestrator.application.engine;

import com.example.batch.common.event.DomainEvent;
import com.example.batch.common.event.DomainEventPublisher;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.domain.entity.WorkflowEdgeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.mapper.WorkflowEdgeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-041 Phase1.3b:跨阶段 count 连续性核对(inline-on-report,仅告警)。
 *
 * <p>当一个 workflow 节点上报成功、其归一化产出(P1.3a 的 {@code inputCount}/{@code outputCount})落 {@code
 * workflow_node_run.output} 后,比对本节点 {@code inputCount} vs 上游节点 {@code outputCount}。不一致即
 * 阶段间「静默丢行」,同事务写一条 {@code outbox_event(event_type=workflow.node.count.continuity.v1)} 告警,
 * <b>不</b>翻转任何状态(只对账「数对不对/全不全」,不裁定业务对错,ADR-021 边界)。
 *
 * <p>调用方须已在 {@code @Transactional} 内({@link Propagation#MANDATORY}),保证告警与节点产出同事务原子。 幂等靠 {@code
 * outbox_event(tenant_id,event_key)} 唯一约束:同一 run 同一 upstream→node 边只发一条。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CountContinuityOutboxService {

  private static final String EVENT_TYPE = "workflow.node.count.continuity.v1";
  private static final String AGGREGATE_TYPE = "WORKFLOW_RUN";
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final WorkflowRunMapper workflowRunMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;
  private final WorkflowNodeRunMapper workflowNodeRunMapper;
  private final DomainEventPublisher domainEventPublisher;
  private final ObjectMapper objectMapper;

  /** 调用方持有当前事务;本方法 MANDATORY,无事务直接抛。 */
  @Transactional(propagation = Propagation.MANDATORY)
  public void checkContinuity(Long workflowRunId, String nodeCode, String currentOutputJson) {
    if (workflowRunId == null || !Texts.hasText(nodeCode) || !Texts.hasText(currentOutputJson)) {
      return;
    }
    Long inputCount = readLong(parse(currentOutputJson), "inputCount");
    if (inputCount == null) {
      // 本节点未上报 inputCount(非文件链路节点 / 未启用信封)→ 不参与
      return;
    }
    WorkflowRunEntity run = workflowRunMapper.selectByIdAnyTenant(workflowRunId);
    if (run == null || run.getWorkflowDefinitionId() == null) {
      return;
    }
    List<WorkflowEdgeEntity> incoming =
        workflowEdgeMapper.selectIncomingEdges(run.getWorkflowDefinitionId(), nodeCode);
    if (incoming.isEmpty()) {
      return;
    }
    List<String> upstreamCodes =
        incoming.stream()
            .map(WorkflowEdgeEntity::getFromNodeCode)
            .filter(Texts::hasText)
            .distinct()
            .toList();
    if (upstreamCodes.isEmpty()) {
      return;
    }
    List<WorkflowNodeRunEntity> upstreamRuns =
        workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCodesIn(
            workflowRunId, upstreamCodes);
    for (WorkflowNodeRunEntity upstream : upstreamRuns) {
      Long upstreamOutput = readLong(parse(upstream.getOutput()), "outputCount");
      if (upstreamOutput == null || upstreamOutput.equals(inputCount)) {
        continue;
      }
      writeMismatch(run, nodeCode, upstream.getNodeCode(), upstreamOutput, inputCount);
    }
  }

  private void writeMismatch(
      WorkflowRunEntity run,
      String nodeCode,
      String upstreamCode,
      long upstreamOutput,
      long currentInput) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", "v1");
    payload.put("tenantId", run.getTenantId());
    payload.put("workflowRunId", run.getId());
    payload.put("nodeCode", nodeCode);
    payload.put("upstreamNodeCode", upstreamCode);
    payload.put("upstreamOutputCount", upstreamOutput);
    payload.put("currentInputCount", currentInput);
    String eventKey =
        run.getTenantId() + ":continuity:" + run.getId() + ":" + upstreamCode + "->" + nodeCode;
    domainEventPublisher.publish(
        DomainEvent.builder(run.getTenantId())
            .aggregate(AGGREGATE_TYPE, run.getId())
            .type(EVENT_TYPE)
            .key(eventKey)
            .payload(payload)
            .build());
    log.warn(
        "count continuity mismatch (alert-only): workflowRunId={}, {}.outputCount={} !="
            + " {}.inputCount={}",
        run.getId(),
        upstreamCode,
        upstreamOutput,
        nodeCode,
        currentInput);
  }

  private Map<String, Object> parse(String json) {
    if (!Texts.hasText(json)) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (Exception ignored) {
      SwallowedExceptionLogger.warn(CountContinuityOutboxService.class, "catch:Exception", ignored);
      return Map.of();
    }
  }

  private Long readLong(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return null;
    }
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }
}
