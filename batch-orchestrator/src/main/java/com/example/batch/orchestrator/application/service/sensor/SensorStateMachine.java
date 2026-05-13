package com.example.batch.orchestrator.application.service.sensor;

import com.example.batch.common.enums.SensorTimeoutAction;
import com.example.batch.common.enums.SensorType;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.service.task.TaskOutcomeService;
import com.example.batch.orchestrator.application.service.task.TaskOutcomeService.NodeRunFinishCommand;
import com.example.batch.orchestrator.application.service.task.TaskOutcomeService.NodeRunKey;
import com.example.batch.orchestrator.application.service.task.TaskOutcomeService.NodeRunOutcome;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ADR-028 Sensor 状态机：把 {@link SensorPolicy#probe} 的返回值翻译为 workflow_node_run 状态推进 + 探测状态更新。
 *
 * <ul>
 *   <li>MATCHED → recordNodeRunFinish(success=true, output)
 *   <li>NOT_YET → updateSensorProbeState(nextProbeAt=now+pollInterval)
 *   <li>elapsed &gt;= timeout_seconds → on_timeout=FAIL → recordNodeRunFinish(success=false)；
 *       SKIP_DOWNSTREAM 同样标 success=false（FAILED），后续 DAG 走失败分支由 WorkflowDagService 决定
 *   <li>ERROR：连续 3 次后视同 FAIL；否则视同 NOT_YET 重排下次探测
 * </ul>
 *
 * <p>DAG 下游派发不在本类范围：WAIT 节点 SUCCESS 后由 {@code WorkflowOrchestrationReconciler} 或后续 worker
 * 任务回报联动推进（见 S3.1 follow-up 跟踪）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensorStateMachine {

  /** 连续 ERROR 多少次后视为失败终态。 */
  static final int MAX_CONSECUTIVE_ERRORS = 3;

  private final SensorPolicyRegistry registry;
  private final WorkflowNodeRunMapper nodeRunMapper;
  private final WorkflowNodeMapper nodeMapper;
  private final WorkflowRunMapper workflowRunMapper;
  private final TaskOutcomeService taskOutcomeService;
  private final ObjectMapper objectMapper;

  /** scheduler 入口：对单个到期 WAIT 节点跑一次探测并推进状态。 */
  public void probeAndAdvance(WorkflowNodeRunEntity nodeRun, Instant now) {
    WorkflowRunEntity wfRun = workflowRunMapper.selectByIdAnyTenant(nodeRun.getWorkflowRunId());
    if (wfRun == null) {
      log.warn("WAIT probe skipped: workflow_run id={} not found", nodeRun.getWorkflowRunId());
      return;
    }
    WorkflowNodeEntity node =
        nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(
            wfRun.getWorkflowDefinitionId(), nodeRun.getNodeCode());
    if (node == null || !"WAIT".equals(node.getNodeType())) {
      log.warn(
          "WAIT probe skipped: node definition missing or wrong type wfDefId={} code={}",
          wfRun.getWorkflowDefinitionId(),
          nodeRun.getNodeCode());
      return;
    }

    Map<String, Object> params = parseJson(node.getNodeParams());
    SensorConfig cfg = SensorConfig.from(params);
    if (cfg == null) {
      finishFailure(
          wfRun,
          nodeRun,
          now,
          "error.workflow.sensor_spec_invalid",
          List.of(String.valueOf(params.get("sensor_type")), "node_params not valid"));
      return;
    }

    Instant startedAt = nodeRun.getStartedAt() != null ? nodeRun.getStartedAt() : now;
    Duration elapsed = Duration.between(startedAt, now);
    if (elapsed.getSeconds() >= cfg.timeoutSeconds) {
      log.info(
          "WAIT timeout nodeRunId={} elapsed={}s timeout={}s onTimeout={}",
          nodeRun.getId(),
          elapsed.getSeconds(),
          cfg.timeoutSeconds,
          cfg.onTimeout);
      finishFailure(
          wfRun,
          nodeRun,
          now,
          "error.workflow.sensor_timeout",
          List.of(String.valueOf(cfg.timeoutSeconds), cfg.onTimeout.code()));
      return;
    }

    SensorPolicy policy = registry.resolve(cfg.sensorType);
    if (policy == null) {
      finishFailure(
          wfRun,
          nodeRun,
          now,
          "error.workflow.sensor_spec_invalid",
          List.of(cfg.sensorType.code(), "no policy registered"));
      return;
    }

    Duration remaining = Duration.ofSeconds(cfg.timeoutSeconds).minus(elapsed);
    SensorContext ctx =
        new SensorContext(
            wfRun.getTenantId(),
            nodeRun.getId(),
            cfg.sensorSpec,
            buildWorkflowRunVars(wfRun),
            remaining);
    SensorProbeResult result;
    try {
      result = policy.probe(ctx);
    } catch (Exception e) {
      log.warn(
          "Sensor policy threw nodeRunId={} type={} err={}",
          nodeRun.getId(),
          cfg.sensorType,
          e.toString());
      result =
          SensorProbeResult.error(
              "error.workflow.sensor_probe_failed", List.of(cfg.sensorType.code(), e.getMessage()));
    }

    apply(wfRun, nodeRun, cfg, result, now);
  }

  private void apply(
      WorkflowRunEntity wfRun,
      WorkflowNodeRunEntity nodeRun,
      SensorConfig cfg,
      SensorProbeResult result,
      Instant now) {
    int probeCount = safe(nodeRun.getSensorProbeCount()) + 1;
    int errorCount = safe(nodeRun.getSensorErrorCount());
    Instant next = now.plusSeconds(cfg.pollIntervalSeconds);

    switch (result.status()) {
      case MATCHED -> {
        log.info(
            "WAIT matched nodeRunId={} type={} probes={}",
            nodeRun.getId(),
            cfg.sensorType,
            probeCount);
        finishSuccess(wfRun, nodeRun, now, result.output());
      }
      case NOT_YET -> {
        nodeRunMapper.updateSensorProbeState(nodeRun.getId(), next, now, probeCount, 0);
      }
      case ERROR -> {
        int newErrors = errorCount + 1;
        if (newErrors >= MAX_CONSECUTIVE_ERRORS) {
          log.warn(
              "WAIT ERROR threshold reached nodeRunId={} errors={} key={}",
              nodeRun.getId(),
              newErrors,
              result.errorKey());
          finishFailure(wfRun, nodeRun, now, result.errorKey(), result.errorArgs());
        } else {
          // 指数退避：每次 error 翻倍 poll_interval（上限 5x）
          long backoffMul = Math.min(1L << Math.min(newErrors, 3), 5L);
          Instant retryAt = now.plusSeconds(cfg.pollIntervalSeconds * backoffMul);
          nodeRunMapper.updateSensorProbeState(
              nodeRun.getId(), retryAt, now, probeCount, newErrors);
        }
      }
    }
  }

  private void finishSuccess(
      WorkflowRunEntity wfRun,
      WorkflowNodeRunEntity nodeRun,
      Instant now,
      Map<String, Object> output) {
    NodeRunOutcome outcome =
        NodeRunOutcome.builder()
            .success(true)
            .startedAt(nodeRun.getStartedAt())
            .finishedAt(now)
            .outputJson(serializeOutput(output))
            .build();
    NodeRunKey key = new NodeRunKey(wfRun.getId(), nodeRun.getNodeCode(), "WAIT");
    taskOutcomeService.recordNodeRunFinish(NodeRunFinishCommand.of(key, outcome));
    // 探测状态归零（之后归档/审计读到的快照不带遗留 next_probe_at）
    nodeRunMapper.updateSensorProbeState(
        nodeRun.getId(), null, now, safe(nodeRun.getSensorProbeCount()) + 1, 0);
  }

  private void finishFailure(
      WorkflowRunEntity wfRun,
      WorkflowNodeRunEntity nodeRun,
      Instant now,
      String errorKey,
      List<String> errorArgs) {
    NodeRunOutcome outcome =
        NodeRunOutcome.builder()
            .success(false)
            .errorCode("SENSOR_FAIL")
            .errorKey(errorKey)
            .errorArgs(serializeArgs(errorArgs))
            .startedAt(nodeRun.getStartedAt())
            .finishedAt(now)
            .build();
    NodeRunKey key = new NodeRunKey(wfRun.getId(), nodeRun.getNodeCode(), "WAIT");
    taskOutcomeService.recordNodeRunFinish(NodeRunFinishCommand.of(key, outcome));
    nodeRunMapper.updateSensorProbeState(
        nodeRun.getId(), null, now, safe(nodeRun.getSensorProbeCount()) + 1, 0);
  }

  private Map<String, Object> parseJson(String raw) {
    if (!Texts.hasText(raw)) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      log.warn("Failed to parse node_params JSON: {}", e.getMessage());
      return Map.of();
    }
  }

  private Map<String, Object> buildWorkflowRunVars(WorkflowRunEntity wfRun) {
    Map<String, Object> vars = new LinkedHashMap<>();
    if (wfRun.getBizDate() != null) {
      vars.put("bizDate", wfRun.getBizDate().toString());
    }
    if (wfRun.getTraceId() != null) {
      vars.put("traceId", wfRun.getTraceId());
    }
    return vars;
  }

  private String serializeOutput(Map<String, Object> output) {
    if (output == null || output.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(output);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private String serializeArgs(List<String> args) {
    if (args == null || args.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(args);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static int safe(Integer v) {
    return v == null ? 0 : v;
  }

  /** sensor 配置 record：构造时即完成参数校验，缺关键字段返 null。 */
  record SensorConfig(
      SensorType sensorType,
      Map<String, Object> sensorSpec,
      long timeoutSeconds,
      long pollIntervalSeconds,
      SensorTimeoutAction onTimeout) {

    @SuppressWarnings("unchecked")
    static SensorConfig from(Map<String, Object> params) {
      if (params == null) {
        return null;
      }
      String typeCode = stringOf(params.get("sensor_type"));
      SensorType type = typeCode == null ? null : tryParseType(typeCode);
      Object spec = params.get("sensor_spec");
      Long timeout = longOf(params.get("timeout_seconds"));
      Long interval = longOf(params.get("poll_interval_seconds"));
      String onTimeoutCode = stringOf(params.get("on_timeout"));
      SensorTimeoutAction onTimeout = onTimeoutCode == null ? null : tryParseAction(onTimeoutCode);
      if (type == null
          || !(spec instanceof Map)
          || timeout == null
          || timeout <= 0
          || interval == null
          || interval <= 0
          || onTimeout == null) {
        return null;
      }
      return new SensorConfig(type, (Map<String, Object>) spec, timeout, interval, onTimeout);
    }

    private static SensorType tryParseType(String code) {
      try {
        return SensorType.valueOf(code);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    private static SensorTimeoutAction tryParseAction(String code) {
      try {
        return SensorTimeoutAction.valueOf(code);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    private static String stringOf(Object v) {
      return v == null ? null : v.toString();
    }

    private static Long longOf(Object v) {
      if (v == null) {
        return null;
      }
      if (v instanceof Number n) {
        return n.longValue();
      }
      try {
        return Long.parseLong(v.toString().trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
  }
}
