package com.example.batch.orchestrator.application.service.workflow;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1 动态 fan-out 的纯函数工具(无状态,可单测)。把"解析 fanOut 配置 / 展开 N 分区 / 给分区注入 item"这三段纯逻辑 从 {@link
 * DefaultWorkflowNodeDispatchService} 抽出,便于覆盖。items 的解析(要 workflow_run 上下文)留在 {@link
 * WorkflowNodePayloadBuilder#resolveFanOutItems}。
 */
public final class WorkflowFanOutSupport {

  /** node_params.fanOut 默认上限。 */
  public static final int DEFAULT_MAX_FAN_OUT = 200;

  private static final String DEFAULT_ITEM_PARAM = "fanOutItem";

  private WorkflowFanOutSupport() {}

  /** 动态 fan-out 配置(workflow_node.node_params.fanOut)。 */
  public record FanOutSpec(String itemsExpr, String itemParam, int maxFanOut) {}

  /**
   * 解析节点的 fan-out 配置。约定 node_params JSON 含 {@code fanOut: {itemsExpr, itemParam?, maxFanOut?}}。 无
   * fanOut / itemsExpr 空 → 返回 null(普通节点)。
   */
  @SuppressWarnings("unchecked")
  public static FanOutSpec parseSpec(WorkflowNodeEntity workflowNode) {
    if (workflowNode == null || workflowNode.getNodeParams() == null) {
      return null;
    }
    Map<String, Object> params =
        WorkflowNodePayloadBuilder.parsePayloadMap(workflowNode.getNodeParams());
    Object fanOutObj = params.get("fanOut");
    if (!(fanOutObj instanceof Map<?, ?> fanOutMap)) {
      return null;
    }
    Map<String, Object> fan = (Map<String, Object>) fanOutMap;
    Object itemsExpr = fan.get("itemsExpr");
    if (!(itemsExpr instanceof String expr) || expr.isBlank()) {
      return null;
    }
    Object itemParamObj = fan.get("itemParam");
    String itemParam = itemParamObj instanceof String s && !s.isBlank() ? s : DEFAULT_ITEM_PARAM;
    Object maxObj = fan.get("maxFanOut");
    int maxFanOut =
        maxObj instanceof Number n && n.intValue() > 0 ? n.intValue() : DEFAULT_MAX_FAN_OUT;
    return new FanOutSpec(expr.trim(), itemParam, maxFanOut);
  }

  /** 把 plan 的分区列表替换为 N 份(以原首个分区为模板克隆 worker route / status)。 */
  public static List<SchedulePlan.PartitionPlan> expandPartitions(SchedulePlan plan, int count) {
    SchedulePlan.PartitionPlan template =
        plan.getPartitions() == null || plan.getPartitions().isEmpty()
            ? new SchedulePlan.PartitionPlan()
            : plan.getPartitions().get(0);
    List<SchedulePlan.PartitionPlan> expanded = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      SchedulePlan.PartitionPlan p = new SchedulePlan.PartitionPlan();
      p.setPartitionKey(template.getPartitionKey() == null ? "fanout" : template.getPartitionKey());
      p.setBusinessKey(template.getBusinessKey());
      p.setWorkerRoute(template.getWorkerRoute());
      p.setPartitionStatus(template.getPartitionStatus());
      expanded.add(p);
    }
    return expanded;
  }

  /** 给某个 fan-out 分区的 task payload 注入它负责的 item + 索引信息。 */
  public static String injectItem(
      String basePayload, String itemParam, Object item, int index, int total) {
    Map<String, Object> merged =
        new LinkedHashMap<>(WorkflowNodePayloadBuilder.parsePayloadMap(basePayload));
    merged.put(itemParam, item);
    merged.put("fanOutIndex", index);
    merged.put("fanOutTotal", total);
    return JsonUtils.toJson(merged);
  }
}
