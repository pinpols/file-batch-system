package com.example.batch.orchestrator.application.service.workflow;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import com.example.batch.orchestrator.application.workflow.WorkflowParamResolver;
import com.example.batch.orchestrator.application.workflow.WorkflowRunContext;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;
import com.example.batch.orchestrator.mapper.FileRecordLookupMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * P2-4 god-class-decomposition extract: 把 {@link DefaultWorkflowNodeDispatchService} 内的 task
 * payload 拼装 + 上游产物合并 + WorkflowRun 上下文加载 + JSON 解析助手集中在一处。
 *
 * <p>覆盖原 service ~225 行,主 service 不再夹带"拼 payload"职责:
 *
 * <ul>
 *   <li>{@link #buildTaskPayload} — 4 层优先级合并(sourcePayload + 上游 partition output + node_params +
 *       workflow 元数据)
 *   <li>{@link #mergeNodeParams} — workflow_node.node_params + ADR-009 DSL 引用解析
 *   <li>{@link #loadWorkflowRunContext} — 加载已完成节点 output 给 ADR-009 DSL 解析用
 *   <li>{@link #parsePayloadMap} / {@link #parseOutputJson} — 公共 JSON Map 反序列化
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowNodePayloadBuilder {

  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final WorkflowParamResolver workflowParamResolver;
  private final FileRecordLookupMapper fileRecordLookupMapper;

  /**
   * 组装下游节点的 task payload。分层优先级(后写覆盖前写):
   *
   * <ol>
   *   <li>workflow 实例的 {@code sourcePayload}(整条链路共享的根 params)
   *   <li>上游兄弟分区产出({@code job_partition.output_summary} 里带的 {@code fileId} 等),保证 SETTLE 生成的
   *       file_record id 自动流向 DISPATCH 节点,而不是靠每条 workflow 手工声明
   *   <li>当前节点的 {@code workflow_node.node_params}(节点级静态配置,如 DISPATCH 的 {@code channelCode})
   *   <li>workflow 元数据({@code workflowNodeCode / workflowNodeType / targetJobCode}),供 worker
   *       侧用作上下文日志、幂等键计算
   * </ol>
   */
  @SuppressWarnings("unchecked")
  String buildTaskPayload(
      String sourcePayload,
      WorkflowDagService.DagNodeResolution node,
      String targetJobCode,
      WorkflowNodeEntity workflowNode,
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (sourcePayload != null && !sourcePayload.isBlank()) {
      try {
        Object payloadObject = JsonUtils.fromJson(sourcePayload, Object.class);
        if (payloadObject instanceof Map<?, ?> payloadMap) {
          payload.putAll((Map<String, Object>) payloadMap);
        } else {
          payload.put("upstreamPayload", payloadObject);
        }
      } catch (IllegalArgumentException exception) {
        SwallowedExceptionLogger.info(
            WorkflowNodePayloadBuilder.class, "catch:IllegalArgumentException", exception);

        payload.put("upstreamPayloadRaw", sourcePayload);
      }
    }
    mergeUpstreamPartitionOutputs(payload, jobInstance);
    mergeNodeParams(payload, workflowNode, workflowRun);
    payload.put("workflowNodeCode", node.nodeCode());
    payload.put("workflowNodeType", node.nodeType());
    payload.put("targetJobCode", targetJobCode);
    return JsonUtils.toJson(payload);
  }

  /**
   * 扫描当前 workflow_run 同一 job_instance 下已 SUCCESS 的兄弟分区的 {@code output_summary},把 {@code fileId} /
   * {@code fileCode} 这类跨节点常用字段挑出来塞进 payload。保守做法:只挑已知少量字段(避免把 partition 内部诊断字段污染到 worker
   * payload)。多分区并存时最新成功的胜出。
   */
  private static final List<String> UPSTREAM_OUTPUT_WHITELIST =
      List.of("fileId", "fileCode", "batchNo", "recordCount", "bizDate");

  private void mergeUpstreamPartitionOutputs(
      Map<String, Object> payload, JobInstanceEntity jobInstance) {
    if (jobInstance == null || jobInstance.getId() == null) {
      return;
    }
    List<JobPartitionEntity> siblings =
        jobMappers.jobPartitionMapper.selectByQuery(
            new JobPartitionQuery(jobInstance.getTenantId(), jobInstance.getId(), null, null));
    if (siblings == null || siblings.isEmpty()) {
      return;
    }
    JobPartitionEntity latestSuccess = findLatestSuccessPartition(siblings);
    mergeWhitelistedOutputFields(payload, latestSuccess);
    fallbackFileIdLookup(payload, jobInstance);
  }

  private static JobPartitionEntity findLatestSuccessPartition(List<JobPartitionEntity> siblings) {
    JobPartitionEntity latest = null;
    for (JobPartitionEntity p : siblings) {
      if (!PartitionStatus.SUCCESS.code().equals(p.getPartitionStatus())) {
        continue;
      }
      if (latest == null || isFinishedLater(p, latest)) {
        latest = p;
      }
    }
    return latest;
  }

  private static boolean isFinishedLater(JobPartitionEntity p, JobPartitionEntity reference) {
    return p.getFinishedAt() != null
        && reference.getFinishedAt() != null
        && p.getFinishedAt().isAfter(reference.getFinishedAt());
  }

  @SuppressWarnings("unchecked")
  private static void mergeWhitelistedOutputFields(
      Map<String, Object> payload, JobPartitionEntity latestSuccess) {
    if (latestSuccess == null || latestSuccess.getOutputSummary() == null) {
      return;
    }
    try {
      Object outputObj = JsonUtils.fromJson(latestSuccess.getOutputSummary(), Object.class);
      if (!(outputObj instanceof Map<?, ?> outMap)) {
        return;
      }
      Map<String, Object> out = (Map<String, Object>) outMap;
      // 保守白名单：只把已知的跨节点常用字段挑出来
      for (String key : UPSTREAM_OUTPUT_WHITELIST) {
        Object v = out.get(key);
        if (v != null && !payload.containsKey(key)) {
          payload.put(key, v);
        }
      }
    } catch (IllegalArgumentException ignored) {
      SwallowedExceptionLogger.info(
          WorkflowNodePayloadBuilder.class, "catch:IllegalArgumentException", ignored);

      // 跳过脏 outputSummary
    }
  }

  // 兜底：partition.output_summary 不含 fileId 时，通过 trace_id 或 batchNo 反查 file_record。
  // 两条独立的线索都要查：
  //   (a) trace_id - 本次 run 期间 EXPORT worker 新建的 file_record 会打同一 trace_id
  //   (b) source_ref = batchNo - 文件按 batchNo 幂等复用时，trace_id 不更新但 source_ref 一致
  //       （settlement-2026-04-22 这种业务上每日唯一的文件就是这种场景）
  private void fallbackFileIdLookup(Map<String, Object> payload, JobInstanceEntity jobInstance) {
    if (payload.containsKey("fileId") || jobInstance.getTenantId() == null) {
      return;
    }
    Long fileId = null;
    if (jobInstance.getTraceId() != null && !jobInstance.getTraceId().isBlank()) {
      fileId =
          safeFileIdLookup(
              fileRecordLookupMapper::selectIdByTenantAndTraceId,
              jobInstance.getTenantId(),
              jobInstance.getTraceId(),
              "traceId");
    }
    if (fileId == null) {
      Object batchNo = payload.get("batchNo");
      if (batchNo != null && !String.valueOf(batchNo).isBlank()) {
        fileId =
            safeFileIdLookup(
                fileRecordLookupMapper::selectIdByTenantAndSourceRef,
                jobInstance.getTenantId(),
                String.valueOf(batchNo),
                "sourceRef");
      }
    }
    if (fileId != null) {
      payload.put("fileId", String.valueOf(fileId));
    }
  }

  /** Mapper 调用兜底：查询失败（DB 异常）记 warn 返 null，不让 dispatch 链路因此失败。 */
  private Long safeFileIdLookup(
      BiFunction<String, String, Long> lookup,
      String tenantId,
      String secondArg,
      String secondArgName) {
    try {
      return lookup.apply(tenantId, secondArg);
    } catch (RuntimeException ex) {
      log.warn(
          "file_record lookup failed: tenantId={}, {}={}, error={}",
          tenantId,
          secondArgName,
          secondArg,
          ex.getMessage());
      return null;
    }
  }

  /**
   * 合并当前节点的 {@code workflow_node.node_params}(JSON 对象)到 payload。用户在 workflow 设计器配的 templateCode /
   * channelCode 等静态字段由此流入 worker,无需每次触发时手工重复。
   *
   * <p>ADR-009 Stage 3: node_params 中形如 {@code $.nodes.<X>.output.<key>} / {@code
   * $.workflowRun.<key>} 的引用,会先经 {@link WorkflowParamResolver} 用 {@code workflow_run} 上下文替换为
   * 实际值,再合并到 payload。
   */
  @SuppressWarnings("unchecked")
  public void mergeNodeParams(
      Map<String, Object> payload, WorkflowNodeEntity workflowNode, WorkflowRunEntity workflowRun) {
    if (workflowNode == null || workflowNode.getNodeParams() == null) {
      return;
    }
    String raw = workflowNode.getNodeParams();
    if (raw.isBlank()) {
      return;
    }
    try {
      Object parsed = JsonUtils.fromJson(raw, Object.class);
      if (parsed instanceof Map<?, ?> map) {
        Object resolved = workflowParamResolver.resolve(map, loadWorkflowRunContext(workflowRun));
        if (resolved instanceof Map<?, ?> resolvedMap) {
          ((Map<String, Object>) resolvedMap).forEach(payload::putIfAbsent);
        }
      }
    } catch (IllegalArgumentException ignored) {
      SwallowedExceptionLogger.info(
          WorkflowNodePayloadBuilder.class, "catch:IllegalArgumentException", ignored);

      // node_params 非 Map 或畸形——静默跳过，不让坏数据阻断派发
    }
  }

  /**
   * ADR-009 Stage 3: 加载 workflow_run 内所有已完成节点的 output → 构造 {@link WorkflowRunContext}。 派发前调用,不持久化;
   * 仅为本次派发的 node_params DSL 解析提供 {@code $.nodes.<X>.output.<key>} 数据源。
   *
   * <p>workflowRun 为 null 时返回空 context(老路径不走 workflow,resolver 见到 nodes/workflowRun 引用会 fail-fast,
   * 但实际只有 workflow 派发路径会调到 mergeNodeParams,所以这里 null-safe 仅作防御)。
   */
  private WorkflowRunContext loadWorkflowRunContext(WorkflowRunEntity workflowRun) {
    if (workflowRun == null) {
      return new WorkflowRunContext() {
        @Override
        public boolean hasNode(String nodeCode) {
          return false;
        }

        @Override
        public Map<String, Object> nodeOutput(String nodeCode) {
          return null;
        }

        @Override
        public Map<String, Object> workflowRunFields() {
          return Map.of();
        }
      };
    }
    List<WorkflowNodeRunEntity> nodeRuns =
        workflowMappers.workflowNodeRunMapper.selectByWorkflowRunId(workflowRun.getId());
    Map<String, Map<String, Object>> nodeOutputs = new LinkedHashMap<>();
    for (WorkflowNodeRunEntity run : nodeRuns) {
      String code = run.getNodeCode();
      // 同 nodeCode 多次执行(retry / 循环)取最新一次的 output;selectByWorkflowRunId 按 run_seq asc 返回,
      // 后续覆盖前面 → 自然结果 = 最新 run_seq 的 output。
      Map<String, Object> parsedOutput = parseOutputJson(run.getOutput());
      nodeOutputs.put(code, parsedOutput);
    }
    Map<String, Object> workflowRunFields = new LinkedHashMap<>();
    if (workflowRun.getBizDate() != null) {
      workflowRunFields.put("bizDate", workflowRun.getBizDate().toString());
    }
    if (workflowRun.getTraceId() != null) {
      workflowRunFields.put("traceId", workflowRun.getTraceId());
    }
    return new WorkflowRunContext() {
      @Override
      public boolean hasNode(String nodeCode) {
        return nodeOutputs.containsKey(nodeCode);
      }

      @Override
      public Map<String, Object> nodeOutput(String nodeCode) {
        return nodeOutputs.get(nodeCode);
      }

      @Override
      public Map<String, Object> workflowRunFields() {
        return workflowRunFields;
      }
    };
  }

  /**
   * P1 动态 fan-out:把 {@code itemsExpr}(形如 {@code $.nodes.<上游>.output.<arrayKey>})对当前 workflow_run
   * 上下文解析成元素列表。解析结果必须是 JSON 数组,否则 fail-fast(配置错)。供 {@link DefaultWorkflowNodeDispatchService} 在
   * TASK 节点派发前决定展开几个并行分区。
   */
  public List<Object> resolveFanOutItems(String itemsExpr, WorkflowRunEntity workflowRun) {
    Object resolved = workflowParamResolver.resolve(itemsExpr, loadWorkflowRunContext(workflowRun));
    if (resolved instanceof List<?> list) {
      return new ArrayList<>(list);
    }
    throw BizException.of(
        ResultCode.INVALID_ARGUMENT, "error.workflow.fan_out_items_not_array", itemsExpr);
  }

  // ── 公共 JSON 反序列化助手(主 service + ChildJobLaunchSupport 共用) ────────

  @SuppressWarnings("unchecked")
  public static Map<String, Object> parsePayloadMap(String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return Map.of();
    }
    try {
      Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
      if (payloadObject instanceof Map<?, ?> payloadMap) {
        return new LinkedHashMap<>((Map<String, Object>) payloadMap);
      }
    } catch (IllegalArgumentException exception) {
      SwallowedExceptionLogger.info(
          WorkflowNodePayloadBuilder.class, "catch:IllegalArgumentException", exception);

      return Map.of();
    }
    return Map.of();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseOutputJson(String outputJson) {
    if (outputJson == null || outputJson.isBlank()) {
      return null;
    }
    try {
      Object parsed = JsonUtils.fromJson(outputJson, Object.class);
      if (parsed instanceof Map<?, ?> map) {
        return new LinkedHashMap<>((Map<String, Object>) map);
      }
    } catch (IllegalArgumentException ignored) {
      SwallowedExceptionLogger.info(
          WorkflowNodePayloadBuilder.class, "catch:IllegalArgumentException", ignored);

      // 数据库里 output 列异常,不影响派发,按"无产出"语义返回 null
    }
    return null;
  }
}
