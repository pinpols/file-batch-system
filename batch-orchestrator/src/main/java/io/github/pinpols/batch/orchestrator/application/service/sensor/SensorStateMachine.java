package io.github.pinpols.batch.orchestrator.application.service.sensor;

import io.github.pinpols.batch.orchestrator.domain.param.WorkflowNodeRunUpdateSensorProbeStateParam;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.SensorTimeoutAction;
import io.github.pinpols.batch.common.enums.SensorType;
import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService.NodeRunFinishCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService.NodeRunKey;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService.NodeRunOutcome;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService.DagNodeResolution;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeTerminalFinalizer;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunExecutionSnapshot;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * ADR-028 Sensor 状态机：把 {@link SensorPolicy#probe} 的返回值翻译为 workflow_node_run 状态推进 + 探测状态更新。
 *
 * <ul>
 *   <li>MATCHED → recordNodeRunFinish(success=true, output)
 *   <li>NOT_YET → updateSensorProbeState(nextProbeAt=now+pollInterval)
 *   <li>elapsed &gt;= timeout_seconds → on_timeout=FAIL 时记录 FAILED；SKIP_DOWNSTREAM 时记录
 *       SKIPPED 并级联跳过不再可触发的下游节点
 *   <li>ERROR：连续 3 次后视同 FAIL；否则视同 NOT_YET 重排下次探测
 * </ul>
 *
 * <p>S3.1：WAIT 节点进入终态后，本状态机复用 {@link WorkflowDagService#resolveNextNodes} + {@link
 * WorkflowNodeDispatchService#dispatchNode} 直接推进下游；END 节点无 worker，由本类内联完成。
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
  private final OrchestratorJobMappers jobMappers;
  private final WorkflowDagService workflowDagService;
  private final ObjectProvider<WorkflowNodeDispatchService> dispatchServiceProvider;
  private final WorkflowNodeTerminalFinalizer workflowNodeTerminalFinalizer;
  private final SecretPayloadProtector secretPayloadProtector;

  /** scheduler 入口：对单个到期 WAIT 节点跑一次探测并推进状态。 */
  public void probeAndAdvance(WorkflowNodeRunEntity nodeRun, Instant now) {
    WorkflowRunEntity wfRun = workflowRunMapper.selectByIdAnyTenant(nodeRun.getWorkflowRunId());
    if (wfRun == null) {
      log.warn("WAIT probe skipped: workflow_run id={} not found", nodeRun.getWorkflowRunId());
      return;
    }
    if (WorkflowRunStatus.terminalCodes().contains(wfRun.getRunStatus())) {
      finishSkippedForTerminalWorkflow(nodeRun, now);
      return;
    }
    WorkflowRunExecutionSnapshot snapshot = WorkflowRunExecutionSnapshot.from(wfRun);
    WorkflowNodeEntity node = EmptyChecks.isNull(snapshot)
        ? nodeMapper.selectByWorkflowDefinitionIdAndNodeCode(
            wfRun.getWorkflowDefinitionId(), nodeRun.getNodeCode())
        : snapshot.node(nodeRun.getNodeCode());
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
      Object configuredSensorType = params.get("sensor_type");
      String sensorType = String.valueOf(configuredSensorType);
      List<String> errorArguments = List.of(sensorType, "node_params not valid");
      finishFailure(
          wfRun,
          nodeRun,
          now,
          "error.workflow.sensor_spec_invalid",
          errorArguments);
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
      if (cfg.onTimeout == SensorTimeoutAction.SKIP_DOWNSTREAM) {
        finishSkipped(wfRun, nodeRun, now, cfg.timeoutSeconds);
      } else {
        finishFailure(
            wfRun,
            nodeRun,
            now,
            "error.workflow.sensor_timeout",
            List.of(String.valueOf(cfg.timeoutSeconds), cfg.onTimeout.code()));
      }
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
    SensorContext ctx = new SensorContext(
        wfRun.getTenantId(),
        nodeRun.getId(),
        cfg.sensorSpec,
        buildWorkflowRunVars(wfRun),
        remaining,
        wfRun.getWorkflowDefinitionId());
    SensorProbeResult result;
    try {
      result = policy.probe(ctx);
    } catch (Exception e) {
      log.warn(
          "Sensor policy threw nodeRunId={} type={} err={}",
          nodeRun.getId(),
          cfg.sensorType,
          SwallowedExceptionLogger.summary(e));
      String sensorTypeCode = cfg.sensorType.code();
      String exceptionMessage = e.getMessage();
      String exceptionClassName = e.getClass().getSimpleName();
      String failureMessage = Objects.requireNonNullElse(exceptionMessage, exceptionClassName);
      result = SensorProbeResult.error(
          "error.workflow.sensor_probe_failed",
          List.of(sensorTypeCode, failureMessage));
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
      case NOT_YET ->
        {
          WorkflowNodeRunUpdateSensorProbeStateParam workflowNodeRunUpdateSensorProbeStateParam = WorkflowNodeRunUpdateSensorProbeStateParam.builder()
              .id(nodeRun.getId())
              .nextProbeAt(next)
              .lastProbeAt(now)
              .probeCount(probeCount)
              .errorCount(0)
              .build();
          nodeRunMapper.updateSensorProbeState(workflowNodeRunUpdateSensorProbeStateParam);
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
          int boundedBackoffExponent = Math.min(newErrors, 3);
          long cappedBackoffMultiplier = Math.min(1L << boundedBackoffExponent, 5L);
          Instant retryAt = now.plusSeconds(cfg.pollIntervalSeconds * cappedBackoffMultiplier);
          WorkflowNodeRunUpdateSensorProbeStateParam workflowNodeRunUpdateSensorProbeStateParam2 = WorkflowNodeRunUpdateSensorProbeStateParam.builder()
              .id(nodeRun.getId())
              .nextProbeAt(retryAt)
              .lastProbeAt(now)
              .probeCount(probeCount)
              .errorCount(newErrors)
              .build();
          nodeRunMapper.updateSensorProbeState(
              workflowNodeRunUpdateSensorProbeStateParam2);
        }
      }
    }
  }

  private void finishSuccess(
      WorkflowRunEntity wfRun,
      WorkflowNodeRunEntity nodeRun,
      Instant now,
      Map<String, Object> output) {
    NodeRunOutcome.NodeRunOutcomeBuilder outcomeBuilder = NodeRunOutcome.builder()
        .success(true)
        .startedAt(nodeRun.getStartedAt())
        .finishedAt(now);
    String outputJson = serializeOutput(output);
    NodeRunOutcome outcome = outcomeBuilder.outputJson(outputJson).build();
    NodeRunKey key = new NodeRunKey(wfRun.getId(), nodeRun.getNodeCode(), "WAIT");
    taskOutcomeService.recordNodeRunFinish(NodeRunFinishCommand.of(key, outcome));
    // 探测状态归零（之后归档/审计读到的快照不带遗留 next_probe_at）
    int nextProbeCount = safe(nodeRun.getSensorProbeCount()) + 1;
    WorkflowNodeRunUpdateSensorProbeStateParam workflowNodeRunUpdateSensorProbeStateParam3 = WorkflowNodeRunUpdateSensorProbeStateParam.builder()
        .id(nodeRun.getId())
        .nextProbeAt(null)
        .lastProbeAt(now)
        .probeCount(nextProbeCount)
        .errorCount(0)
        .build();
    nodeRunMapper.updateSensorProbeState(
        workflowNodeRunUpdateSensorProbeStateParam3);
    advanceDownstream(wfRun, nodeRun.getNodeCode(), true, now);
  }

  private void finishFailure(
      WorkflowRunEntity wfRun,
      WorkflowNodeRunEntity nodeRun,
      Instant now,
      String errorKey,
      List<String> errorArgs) {
    NodeRunOutcome.NodeRunOutcomeBuilder outcomeBuilder = NodeRunOutcome.builder()
        .success(false)
        .errorCode("SENSOR_FAIL")
        .errorKey(errorKey);
    String serializedErrorArgs = serializeArgs(errorArgs);
    NodeRunOutcome outcome = outcomeBuilder
        .errorArgs(serializedErrorArgs)
        .startedAt(nodeRun.getStartedAt())
        .finishedAt(now)
        .build();
    NodeRunKey key = new NodeRunKey(wfRun.getId(), nodeRun.getNodeCode(), "WAIT");
    taskOutcomeService.recordNodeRunFinish(NodeRunFinishCommand.of(key, outcome));
    int nextProbeCount = safe(nodeRun.getSensorProbeCount()) + 1;
    WorkflowNodeRunUpdateSensorProbeStateParam workflowNodeRunUpdateSensorProbeStateParam4 = WorkflowNodeRunUpdateSensorProbeStateParam.builder()
        .id(nodeRun.getId())
        .nextProbeAt(null)
        .lastProbeAt(now)
        .probeCount(nextProbeCount)
        .errorCount(0)
        .build();
    nodeRunMapper.updateSensorProbeState(
        workflowNodeRunUpdateSensorProbeStateParam4);
    advanceDownstream(wfRun, nodeRun.getNodeCode(), false, now);
  }

  private void finishSkipped(
      WorkflowRunEntity wfRun, WorkflowNodeRunEntity nodeRun, Instant now, long timeoutSeconds) {
    UpdateNodeRunStatusParam.UpdateNodeRunStatusParamBuilder updateParamBuilder =
        UpdateNodeRunStatusParam.builder()
        .id(nodeRun.getId())
        .nodeStatus(WorkflowNodeRunStatus.SKIPPED.code())
        .errorCode(null)
        .errorMessage(null)
        .errorKey(null)
        .errorArgs(null)
        .skipReasonCode("SENSOR_TIMEOUT_SKIP_DOWNSTREAM")
        .skipReason("Sensor timed out after " + timeoutSeconds + " seconds; downstream skipped");
    long durationMs = resolveDuration(nodeRun.getStartedAt(), now);
    UpdateNodeRunStatusParam updateParam = updateParamBuilder
        .durationMs(durationMs)
        .finishedAt(now)
        .output(null)
        .build();
    int updated = nodeRunMapper.updateStatus(updateParam);
    if (updated <= 0) {
      return;
    }
    int nextProbeCount = safe(nodeRun.getSensorProbeCount()) + 1;
    WorkflowNodeRunUpdateSensorProbeStateParam workflowNodeRunUpdateSensorProbeStateParam5 = WorkflowNodeRunUpdateSensorProbeStateParam.builder()
        .id(nodeRun.getId())
        .nextProbeAt(null)
        .lastProbeAt(now)
        .probeCount(nextProbeCount)
        .errorCount(0)
        .build();
    nodeRunMapper.updateSensorProbeState(
        workflowNodeRunUpdateSensorProbeStateParam5);
    String sourcePayload = resolveSourcePayloadForDownstream(wfRun, nodeRun.getNodeCode());
    workflowDagService.cascadeSkipDownstreamForRun(wfRun, nodeRun.getNodeCode(), sourcePayload);
    workflowNodeTerminalFinalizer.finalizeIfAllNodesTerminal(wfRun);
  }

  private void finishSkippedForTerminalWorkflow(WorkflowNodeRunEntity nodeRun, Instant now) {
    UpdateNodeRunStatusParam.UpdateNodeRunStatusParamBuilder updateParamBuilder =
        UpdateNodeRunStatusParam.builder()
        .id(nodeRun.getId())
        .nodeStatus(WorkflowNodeRunStatus.SKIPPED.code())
        .skipReasonCode("PARENT_WORKFLOW_TERMINAL")
        .skipReason("Parent WorkflowRun is already terminal; sensor probing stopped");
    long durationMs = resolveDuration(nodeRun.getStartedAt(), now);
    UpdateNodeRunStatusParam updateParam = updateParamBuilder
        .durationMs(durationMs)
        .finishedAt(now)
        .build();
    int updated = nodeRunMapper.updateStatus(updateParam);
    if (updated > 0) {
      int probeCount = safe(nodeRun.getSensorProbeCount());
      int errorCount = safe(nodeRun.getSensorErrorCount());
      WorkflowNodeRunUpdateSensorProbeStateParam workflowNodeRunUpdateSensorProbeStateParam6 = WorkflowNodeRunUpdateSensorProbeStateParam.builder()
          .id(nodeRun.getId())
          .nextProbeAt(null)
          .lastProbeAt(now)
          .probeCount(probeCount)
          .errorCount(errorCount)
          .build();
      nodeRunMapper.updateSensorProbeState(
          workflowNodeRunUpdateSensorProbeStateParam6);
    }
  }

  private long resolveDuration(Instant startedAt, Instant finishedAt) {
    return EmptyChecks.isNull(startedAt) || EmptyChecks.isNull(finishedAt)
        ? 0L
        : Duration.between(startedAt, finishedAt).toMillis();
  }

  /**
   * WAIT 节点终态后推进下游（镜像 DefaultTaskOutcomeService.advanceDagNodes 简化版）。
   *
   * <ul>
   *   <li>resolveNextNodes(success=true|false) 按 SUCCESS/FAILURE 边走分支
   *   <li>END 节点：recordNodeRunFinish 内联（END 无 worker 派发）
   *   <li>非 END：dispatchService.dispatchNode 进 CLAIM → EXECUTE → REPORT 主链路
   * </ul>
   *
   * <p>失败路径 + cascadeSkipDownstream 不在本类范围（避免重复 ADR-018 skip 级联逻辑），由 WorkflowDagService 在下次 worker
   * 回报时自然推进。
   */
  private void advanceDownstream(
      WorkflowRunEntity wfRun, String currentNodeCode, boolean success, Instant now) {
    JobInstanceEntity jobInstance = loadJobInstanceForDispatch(wfRun);
    if (EmptyChecks.isNull(jobInstance) && EmptyChecks.isNotNull(wfRun.getRelatedJobInstanceId())) {
      log.warn(
          "advanceDownstream skipped: single-job workflow_run={} has no related job_instance",
          wfRun.getId());
      return;
    }
    String sourcePayload = resolveSourcePayloadForDownstream(wfRun, currentNodeCode);
    List<DagNodeResolution> nextNodes =
        workflowDagService.resolveNextNodesForRun(wfRun, currentNodeCode, success, sourcePayload);
    if (nextNodes == null || nextNodes.isEmpty()) {
      workflowNodeTerminalFinalizer.finalizeIfAllNodesTerminal(wfRun);
      return;
    }
    WorkflowNodeDispatchService dispatchService = dispatchServiceProvider.getObject();
    for (DagNodeResolution next : nextNodes) {
      if (next == null) {
        continue;
      }
      if (WorkflowNodeCode.END.code().equals(next.nodeCode())) {
        dispatchEndNode(wfRun, next, success, now);
      } else {
        // 让 probeOne 事务包含下游激活。异常必须继续向外传播，使
        // WAIT 终态转换回滚，并让持久化传感器扫描可以重试节点。
        dispatchService.dispatchNode(jobInstance, wfRun, next, sourcePayload, wfRun.getTraceId());
      }
    }
    workflowNodeTerminalFinalizer.finalizeIfAllNodesTerminal(wfRun);
  }

  private String resolveSourcePayloadForDownstream(WorkflowRunEntity wfRun, String nodeCode) {
    WorkflowNodeRunEntity nodeRun =
        nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(wfRun.getId(), nodeCode);
    if (EmptyChecks.isNull(nodeRun) || !Texts.hasText(nodeRun.getSourcePayloadProtected())) {
      return null;
    }
    String protectedSourcePayload = nodeRun.getSourcePayloadProtected();
    return secretPayloadProtector.reveal(protectedSourcePayload);
  }

  private void dispatchEndNode(
      WorkflowRunEntity wfRun, DagNodeResolution endNode, boolean success, Instant now) {
    if (!workflowDagService.isNodeReadyForRunDispatch(wfRun, endNode.nodeCode(), null)) {
      return;
    }
    NodeRunOutcome endOutcome =
        NodeRunOutcome.builder().success(success).startedAt(now).finishedAt(now).build();
    NodeRunKey endKey = new NodeRunKey(wfRun.getId(), endNode.nodeCode(), endNode.nodeType());
    taskOutcomeService.recordNodeRunFinish(NodeRunFinishCommand.of(endKey, endOutcome));
  }

  private JobInstanceEntity loadJobInstanceForDispatch(WorkflowRunEntity wfRun) {
    if (wfRun.getRelatedJobInstanceId() == null) {
      return null;
    }
    return jobMappers.jobInstanceMapper.selectById(
        wfRun.getTenantId(), wfRun.getRelatedJobInstanceId());
  }

  private Map<String, Object> parseJson(String raw) {
    if (!Texts.hasText(raw)) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      log.warn("Failed to parse node_params JSON: {}", SwallowedExceptionLogger.summary(e));
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
      boolean sensorConfigIsInvalid =
          EmptyChecks.isNull(type)
          || !(spec instanceof Map)
          || timeout == null
          || timeout <= 0
          || interval == null
          || interval <= 0
          || EmptyChecks.isNull(onTimeout);
      if (sensorConfigIsInvalid) {
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
        String trimmedValue = v.toString().trim();
        return Long.parseLong(trimmedValue);
      } catch (NumberFormatException e) {
        return null;
      }
    }
  }
}
