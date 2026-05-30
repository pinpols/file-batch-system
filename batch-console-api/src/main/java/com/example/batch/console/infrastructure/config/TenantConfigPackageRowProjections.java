package com.example.batch.console.infrastructure.config;

import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_BIZ_TYPE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CALENDAR_CODE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_CONDITION_EXPR;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DEFAULT_PARAMS;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_DESCRIPTION;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_EDGE_TYPE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_ENABLED;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_EXECUTION_HANDLER;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_FROM_NODE_CODE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_CODE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_NAME;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_JOB_TYPE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_CODE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_NAME;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_ORDER;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_PARAMS;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_NODE_TYPE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_PARAM_SCHEMA;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_QUEUE_CODE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RELATED_JOB_CODE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RELATED_PIPELINE_CODE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RETRY_MAX_COUNT;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_RETRY_POLICY;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SCHEDULE_EXPR;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SCHEDULE_TYPE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_SHARD_STRATEGY;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_TENANT_ID;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_TIMEOUT_SECONDS;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_TO_NODE_CODE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_VERSION;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WINDOW_CODE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKER_GROUP;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKFLOW_CODE;
import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.COL_WORKFLOW_VERSION;

import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.workflow.mapper.PipelineStepDefinitionMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import com.example.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import com.example.batch.console.domain.workflow.query.WorkflowNodeQuery;
import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.job.entity.JobDefinitionEntity;
import com.example.batch.console.domain.query.WorkflowEdgeQuery;
import com.example.batch.console.domain.query.WorkflowNodeQuery;
import com.example.batch.console.mapper.PipelineStepDefinitionMapper;
import com.example.batch.console.mapper.WorkflowEdgeMapper;
import com.example.batch.console.mapper.WorkflowNodeMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * P2-3 god-class-decomposition extract: 把租户配置包导出时各 entity → Map row 的投影逻辑集中在一处。
 *
 * <p>覆盖原 service ~125 行 toJobRows / toWfDefRows / collectPipelineSteps / collectWorkflowNodes /
 * collectWorkflowEdges。投影后的 Map 直接喂给 {@link ConfigPackageExcelWorkbookWriter} 写盘。
 */
@Component
@RequiredArgsConstructor
public class TenantConfigPackageRowProjections {

  private static final String KEY_ID = "id";

  private final PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;

  List<Map<String, Object>> toJobRows(List<JobDefinitionEntity> entities) {
    return entities.stream()
        .map(
            e -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put(COL_TENANT_ID, e.getTenantId());
              m.put(COL_JOB_CODE, e.getJobCode());
              m.put(COL_JOB_NAME, e.getJobName());
              m.put(COL_JOB_TYPE, e.getJobType());
              m.put(COL_BIZ_TYPE, e.getBizType());
              m.put(COL_QUEUE_CODE, e.getQueueCode());
              m.put(COL_WORKER_GROUP, e.getWorkerGroup());
              m.put(COL_SCHEDULE_TYPE, e.getScheduleType());
              m.put(COL_SCHEDULE_EXPR, e.getScheduleExpr());
              m.put(COL_CALENDAR_CODE, e.getCalendarCode());
              m.put(COL_WINDOW_CODE, e.getWindowCode());
              m.put(COL_RETRY_POLICY, e.getRetryPolicy());
              m.put(COL_RETRY_MAX_COUNT, e.getRetryMaxCount());
              m.put(COL_TIMEOUT_SECONDS, e.getTimeoutSeconds());
              m.put(COL_SHARD_STRATEGY, e.getShardStrategy());
              m.put(COL_EXECUTION_HANDLER, e.getExecutionHandler());
              m.put(COL_PARAM_SCHEMA, e.getParamSchema());
              m.put(COL_DEFAULT_PARAMS, e.getDefaultParams());
              m.put(COL_ENABLED, e.getEnabled());
              m.put(COL_DESCRIPTION, e.getDescription());
              return m;
            })
        .collect(Collectors.toList());
  }

  List<Map<String, Object>> collectPipelineSteps(List<Map<String, Object>> pipelines) {
    List<Map<String, Object>> allSteps = new ArrayList<>();
    for (Map<String, Object> pipeline : pipelines) {
      Long pipelineId = ((Number) pipeline.get(KEY_ID)).longValue();
      String jobCode = String.valueOf(pipeline.get(COL_JOB_CODE));
      String version = String.valueOf(pipeline.get(COL_VERSION));
      for (Map<String, Object> step :
          pipelineStepDefinitionMapper.selectByPipelineDefinitionId(pipelineId)) {
        Map<String, Object> enriched = new LinkedHashMap<>(step);
        enriched.put(COL_JOB_CODE, jobCode);
        enriched.put(COL_VERSION, version);
        allSteps.add(enriched);
      }
    }
    return allSteps;
  }

  List<Map<String, Object>> toWfDefRows(List<WorkflowDefinitionEntity> entities) {
    return entities.stream()
        .map(
            e -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put(COL_TENANT_ID, e.getTenantId());
              m.put(COL_WORKFLOW_CODE, e.getWorkflowCode());
              m.put("workflow_name", e.getWorkflowName());
              m.put("workflow_type", e.getWorkflowType());
              m.put(COL_VERSION, e.getVersion());
              m.put(COL_ENABLED, e.getEnabled());
              m.put(COL_DESCRIPTION, e.getDescription());
              return m;
            })
        .collect(Collectors.toList());
  }

  List<Map<String, Object>> collectWorkflowNodes(
      String tenantId, List<WorkflowDefinitionEntity> defs) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (WorkflowDefinitionEntity def : defs) {
      WorkflowNodeQuery nodeQuery =
          WorkflowNodeQuery.builder()
              .tenantId(tenantId)
              .workflowDefinitionId(def.getId())
              .workflowCode(def.getWorkflowCode())
              .build();
      List<WorkflowNodeEntity> nodes = workflowNodeMapper.selectByQuery(nodeQuery);
      for (WorkflowNodeEntity node : nodes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(COL_TENANT_ID, tenantId);
        m.put(COL_WORKFLOW_CODE, def.getWorkflowCode());
        m.put(COL_WORKFLOW_VERSION, def.getVersion());
        m.put(COL_NODE_CODE, node.getNodeCode());
        m.put(COL_NODE_NAME, node.getNodeName());
        m.put(COL_NODE_TYPE, node.getNodeType());
        m.put(COL_RELATED_JOB_CODE, node.getRelatedJobCode());
        m.put(COL_RELATED_PIPELINE_CODE, node.getRelatedPipelineCode());
        m.put(COL_WORKER_GROUP, node.getWorkerGroup());
        m.put(COL_WINDOW_CODE, node.getWindowCode());
        m.put(COL_NODE_ORDER, node.getNodeOrder());
        m.put(COL_RETRY_POLICY, node.getRetryPolicy());
        m.put(COL_RETRY_MAX_COUNT, node.getRetryMaxCount());
        m.put(COL_TIMEOUT_SECONDS, node.getTimeoutSeconds());
        m.put(COL_NODE_PARAMS, node.getNodeParams());
        m.put(COL_ENABLED, node.getEnabled());
        result.add(m);
      }
    }
    return result;
  }

  List<Map<String, Object>> collectWorkflowEdges(
      String tenantId, List<WorkflowDefinitionEntity> defs) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (WorkflowDefinitionEntity def : defs) {
      WorkflowEdgeQuery edgeQuery =
          WorkflowEdgeQuery.builder()
              .tenantId(tenantId)
              .workflowDefinitionId(def.getId())
              .workflowCode(def.getWorkflowCode())
              .build();
      List<WorkflowEdgeEntity> edges = workflowEdgeMapper.selectByQuery(edgeQuery);
      for (WorkflowEdgeEntity edge : edges) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(COL_TENANT_ID, tenantId);
        m.put(COL_WORKFLOW_CODE, def.getWorkflowCode());
        m.put(COL_WORKFLOW_VERSION, def.getVersion());
        m.put(COL_FROM_NODE_CODE, edge.getFromNodeCode());
        m.put(COL_TO_NODE_CODE, edge.getToNodeCode());
        m.put(COL_EDGE_TYPE, edge.getEdgeType());
        m.put(COL_CONDITION_EXPR, edge.getConditionExpr());
        m.put(COL_ENABLED, edge.getEnabled());
        result.add(m);
      }
    }
    return result;
  }
}
