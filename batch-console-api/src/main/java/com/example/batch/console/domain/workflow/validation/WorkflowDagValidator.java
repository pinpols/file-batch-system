package com.example.batch.console.domain.workflow.validation;

import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.job.entity.JobDefinitionEntity;
import com.example.batch.console.domain.job.mapper.JobDefinitionMapper;
import com.example.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import com.example.batch.console.domain.workflow.query.WorkflowDefinitionQuery;
import com.example.batch.console.domain.workflow.query.WorkflowNodeQuery;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest.EdgeItem;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest.NodeItem;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Workflow DAG designer 全量替换的 BE 兜底拓扑校验(MVP)。
 *
 * <p>FE 已做 client-side validators,但 BE 是数据真相源:防绕过 / 防恶意输入 / 防工具脚本写脏。校验在持久化前同步执行,任何 违反规则直接抛 {@link
 * BizException} (VALIDATION_ERROR),不会进入 nodeMapper / edgeMapper。
 *
 * <p>范围边界:仅做 <b>拓扑 + 引用完整性</b>,不做业务对错(ADR-021)/ 性能优化(N+1,MVP 节点 ≤ 200 量级)/ 高级图分析(关键路径 / 复杂度评分)。
 *
 * <p>规则清单(详见 docs/design/workflow-dag-designer.md):
 *
 * <ol>
 *   <li>节点数 ≤ 200(防恶意大输入,可调)
 *   <li>nodeCode 唯一(同一 workflow 内)
 *   <li>恰好 1 个 START 节点
 *   <li>至少 1 个 END 节点
 *   <li>边 from/to 必须是已知节点
 *   <li>无环(Kahn 拓扑排序)
 *   <li>无孤立节点(每个非 START 节点必须能从 START DFS 到达)
 *   <li>JOB.related_job_code 非空
 *   <li>FILE_STEP.related_pipeline_code 非空 + 必须在 pipeline_definition 表存在(同租户)
 *   <li>GATEWAY 出度 ≥ 2
 *   <li>GATEWAY.gateway_strategy 非空(承载在 node_params,MVP 阶段 string 非空即可)
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class WorkflowDagValidator {

  /** 节点数硬上限,防恶意大输入耗尽 BFS 遍历资源。MVP 量级,后续可调。 */
  public static final int MAX_NODES = 200;

  private final PipelineDefinitionMapper pipelineDefinitionMapper;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final WorkflowNodeMapper workflowNodeMapper;

  /**
   * 校验全量替换请求的 DAG 拓扑 + 引用完整性。任何规则违反 → 抛 {@link BizException}。
   *
   * @param tenantId 已解析的租户 id(由 caller 走 tenantGuard 拿到)
   * @param request 完整的 (definition + nodes + edges)
   */
  public void validate(String tenantId, WorkflowDefinitionSaveRequest request) {
    List<NodeItem> nodes = request.getNodes() == null ? List.of() : request.getNodes();
    List<EdgeItem> edges = request.getEdges() == null ? List.of() : request.getEdges();

    // 1. 节点数上限
    if (nodes.size() > MAX_NODES) {
      throw BizException.of(
          ResultCode.VALIDATION_ERROR,
          "error.workflow.dag.too_many_nodes",
          nodes.size(),
          MAX_NODES);
    }

    // 2. nodeCode 唯一 + 收集节点信息
    Set<String> nodeCodes = new HashSet<>();
    List<String> startNodes = new ArrayList<>();
    List<String> endNodes = new ArrayList<>();
    Map<String, NodeItem> byCode = new HashMap<>();
    for (NodeItem n : nodes) {
      String code = n.getNodeCode();
      if (!nodeCodes.add(code)) {
        throw BizException.of(
            ResultCode.VALIDATION_ERROR, "error.workflow.dag.duplicate_node_code", code);
      }
      byCode.put(code, n);
      String type = n.getNodeType();
      if (WorkflowNodeType.START.code().equalsIgnoreCase(type)) {
        startNodes.add(code);
      } else if (WorkflowNodeType.END.code().equalsIgnoreCase(type)) {
        endNodes.add(code);
      }
    }

    // 3/4. START / END 数量
    if (startNodes.size() != 1) {
      throw BizException.of(
          ResultCode.VALIDATION_ERROR, "error.workflow.dag.start_count_invalid", startNodes.size());
    }
    if (endNodes.isEmpty()) {
      throw BizException.of(ResultCode.VALIDATION_ERROR, "error.workflow.dag.missing_end");
    }

    // 5. 边端点必须存在
    for (EdgeItem e : edges) {
      if (!nodeCodes.contains(e.getFromNodeCode())) {
        throw BizException.of(
            ResultCode.VALIDATION_ERROR,
            "error.workflow.dag.edge_from_unknown",
            e.getFromNodeCode(),
            e.getToNodeCode());
      }
      if (!nodeCodes.contains(e.getToNodeCode())) {
        throw BizException.of(
            ResultCode.VALIDATION_ERROR,
            "error.workflow.dag.edge_to_unknown",
            e.getFromNodeCode(),
            e.getToNodeCode());
      }
    }

    // 构建邻接表 + 出度统计
    Map<String, List<String>> adj = new HashMap<>();
    Map<String, Integer> inDegree = new HashMap<>();
    Map<String, Integer> outDegree = new HashMap<>();
    for (String code : nodeCodes) {
      adj.put(code, new ArrayList<>());
      inDegree.put(code, 0);
      outDegree.put(code, 0);
    }
    for (EdgeItem e : edges) {
      adj.get(e.getFromNodeCode()).add(e.getToNodeCode());
      inDegree.merge(e.getToNodeCode(), 1, Integer::sum);
      outDegree.merge(e.getFromNodeCode(), 1, Integer::sum);
    }

    // 6. 无环 — Kahn 拓扑排序;visited < total 即存在环
    detectCycle(nodeCodes, adj, inDegree);

    // 7. 无孤立节点 — 从 START BFS,每个非 START 节点必须可达
    detectUnreachable(startNodes.get(0), nodeCodes, adj);

    // 8/9/10/11. 节点字段引用完整性
    validateNodeReferences(tenantId, nodes, outDegree);
  }

  /**
   * 12. 跨 workflow 嵌套环检测(配置期)。
   *
   * <p>JOB 节点的 {@code related_job_code} 可指向 {@code job_type=WORKFLOW} 的 job(workflow 套
   * workflow)。{@link #validate} 里的 Kahn 环检测只看单 workflow 内的边,发现不了 A→B→A / 自引用 这类跨定义引用环 —— 运行期 {@code
   * ChildJobLaunchSupport} 虽有兜底,但配置期就拦住能给设计者更早反馈。
   *
   * <p>以 {@code rootWorkflowCode} 为起点,沿「JOB 节点 → WORKFLOW 类型 job」展开 workflow 图做 DFS 着色: root
   * 的边取自本次请求(可能尚未持久化),其余 workflow 的边读 DB 的 live 定义;遇到回到「访问中」节点即判环。引用的 job 不是 WORKFLOW 类型、或目标
   * workflow 定义尚不存在时,不构成跨 workflow 边(存在性/类型校验另有其责)。
   *
   * <p>该方法独立于 {@link #validate}(后者签名只有 request、拿不到不可变的 rootCode),由 save 路径在 {@code validate}
   * 之后显式调用。
   *
   * @param tenantId 已解析租户 id
   * @param rootWorkflowCode 正在保存的 workflow 的 code(fullUpdate 下取持久化 def 的 code,因 code 不可变)
   * @param request 本次保存的完整 (nodes + edges)
   */
  public void validateNoCrossWorkflowCycle(
      String tenantId, String rootWorkflowCode, WorkflowDefinitionSaveRequest request) {
    if (rootWorkflowCode == null || rootWorkflowCode.isBlank()) {
      return;
    }
    List<NodeItem> rootNodes = request.getNodes() == null ? List.of() : request.getNodes();
    Set<String> rootRefs = workflowRefsFromRequestNodes(tenantId, rootNodes);
    Set<String> visiting = new LinkedHashSet<>();
    Set<String> done = new HashSet<>();
    dfsCrossWorkflow(tenantId, rootWorkflowCode, rootRefs, visiting, done);
  }

  /** DFS 着色:visiting=灰(当前路径),done=黑(已完成子树);命中灰节点 → 环。 */
  private void dfsCrossWorkflow(
      String tenantId, String code, Set<String> rootRefs, Set<String> visiting, Set<String> done) {
    visiting.add(code);
    // root(visiting 第一个节点)的下游引用来自本次请求;其余 workflow 读 DB live 定义。
    Set<String> targets = visiting.size() == 1 ? rootRefs : workflowRefsFromDb(tenantId, code);
    for (String target : targets) {
      if (visiting.contains(target)) {
        List<String> cyclePath = new ArrayList<>(visiting);
        cyclePath.add(target);
        throw BizException.of(
            ResultCode.VALIDATION_ERROR,
            "error.workflow.dag.cross_workflow_cycle_detected",
            target,
            String.join(" -> ", cyclePath));
      }
      if (!done.contains(target)) {
        dfsCrossWorkflow(tenantId, target, rootRefs, visiting, done);
      }
    }
    visiting.remove(code);
    done.add(code);
  }

  /** 从本次请求的节点里挑出指向 WORKFLOW 类型 job 的 JOB 节点引用(= 跨 workflow 出边)。 */
  private Set<String> workflowRefsFromRequestNodes(String tenantId, List<NodeItem> nodes) {
    Set<String> refs = new LinkedHashSet<>();
    for (NodeItem n : nodes) {
      if (WorkflowNodeType.JOB.code().equalsIgnoreCase(n.getNodeType())
          && !isBlank(n.getRelatedJobCode())
          && isWorkflowTypeJob(tenantId, n.getRelatedJobCode())) {
        refs.add(n.getRelatedJobCode());
      }
    }
    return refs;
  }

  /** 读 DB live 定义,挑出该 workflow 指向 WORKFLOW 类型 job 的 JOB 节点引用(= 跨 workflow 出边)。 */
  private Set<String> workflowRefsFromDb(String tenantId, String workflowCode) {
    WorkflowDefinitionEntity def = findLiveDefinitionByCode(tenantId, workflowCode);
    if (def == null || def.getId() == null) {
      return Set.of();
    }
    List<WorkflowNodeEntity> nodes =
        workflowNodeMapper.selectByQuery(
            WorkflowNodeQuery.ofDefinition(tenantId, def.getId(), null));
    Set<String> refs = new LinkedHashSet<>();
    for (WorkflowNodeEntity n : nodes) {
      if (WorkflowNodeType.JOB.code().equalsIgnoreCase(n.getNodeType())
          && !isBlank(n.getRelatedJobCode())
          && isWorkflowTypeJob(tenantId, n.getRelatedJobCode())) {
        refs.add(n.getRelatedJobCode());
      }
    }
    return refs;
  }

  private boolean isWorkflowTypeJob(String tenantId, String jobCode) {
    JobDefinitionEntity job = jobDefinitionMapper.selectByUniqueKey(tenantId, jobCode);
    return job != null && JobType.WORKFLOW.code().equalsIgnoreCase(job.getJobType());
  }

  /** selectByQuery 用 LIKE 模糊匹配 code,这里取精确相等那条(已按 version desc,id desc 排序 → live 行在前)。 */
  private WorkflowDefinitionEntity findLiveDefinitionByCode(String tenantId, String workflowCode) {
    List<WorkflowDefinitionEntity> matches =
        workflowDefinitionMapper.selectByQuery(
            WorkflowDefinitionQuery.builder()
                .tenantId(tenantId)
                .workflowCode(workflowCode)
                .build());
    for (WorkflowDefinitionEntity d : matches) {
      if (workflowCode.equals(d.getWorkflowCode())) {
        return d;
      }
    }
    return null;
  }

  /** 8/9/10/11:JOB / FILE_STEP / GATEWAY 节点字段引用完整性 + gateway 出度下限。 */
  private void validateNodeReferences(
      String tenantId, List<NodeItem> nodes, Map<String, Integer> outDegree) {
    for (NodeItem n : nodes) {
      String type = n.getNodeType();
      if (WorkflowNodeType.JOB.code().equalsIgnoreCase(type)) {
        if (isBlank(n.getRelatedJobCode())) {
          throw BizException.of(
              ResultCode.VALIDATION_ERROR,
              "error.workflow.dag.job_related_code_missing",
              n.getNodeCode());
        }
      } else if (WorkflowNodeType.FILE_STEP.code().equalsIgnoreCase(type)) {
        String pipelineCode = n.getRelatedPipelineCode();
        if (isBlank(pipelineCode)) {
          throw BizException.of(
              ResultCode.VALIDATION_ERROR,
              "error.workflow.dag.file_step_related_pipeline_missing",
              n.getNodeCode());
        }
        long cnt = pipelineDefinitionMapper.countByJobCode(tenantId, pipelineCode);
        if (cnt == 0) {
          throw BizException.of(
              ResultCode.VALIDATION_ERROR,
              "error.workflow.dag.file_step_pipeline_not_found",
              n.getNodeCode(),
              pipelineCode);
        }
      } else if (WorkflowNodeType.GATEWAY.code().equalsIgnoreCase(type)) {
        // gateway_strategy 承载在 node_params (JSON);MVP 阶段非空即可,具体 strategy 取值不校验
        if (isBlank(n.getNodeParams())) {
          throw BizException.of(
              ResultCode.VALIDATION_ERROR,
              "error.workflow.dag.gateway_strategy_missing",
              n.getNodeCode());
        }
        if (outDegree.getOrDefault(n.getNodeCode(), 0) < 2) {
          throw BizException.of(
              ResultCode.VALIDATION_ERROR,
              "error.workflow.dag.gateway_out_degree_too_small",
              n.getNodeCode(),
              outDegree.getOrDefault(n.getNodeCode(), 0));
        }
      }
    }
  }

  private static void detectCycle(
      Set<String> nodeCodes, Map<String, List<String>> adj, Map<String, Integer> inDegree) {
    Deque<String> queue = new ArrayDeque<>();
    Map<String, Integer> deg = new HashMap<>(inDegree);
    for (Map.Entry<String, Integer> e : deg.entrySet()) {
      if (e.getValue() == 0) {
        queue.add(e.getKey());
      }
    }
    int visited = 0;
    while (!queue.isEmpty()) {
      String cur = queue.poll();
      visited++;
      for (String next : adj.get(cur)) {
        int v = deg.get(next) - 1;
        deg.put(next, v);
        if (v == 0) {
          queue.add(next);
        }
      }
    }
    if (visited < nodeCodes.size()) {
      String stuck =
          deg.entrySet().stream()
              .filter(e -> e.getValue() > 0)
              .map(Map.Entry::getKey)
              .findFirst()
              .orElse("?");
      throw BizException.of(
          ResultCode.VALIDATION_ERROR, "error.workflow.dag.cycle_detected", stuck);
    }
  }

  private static void detectUnreachable(
      String startCode, Set<String> nodeCodes, Map<String, List<String>> adj) {
    Set<String> reachable = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>();
    queue.add(startCode);
    reachable.add(startCode);
    while (!queue.isEmpty()) {
      String cur = queue.poll();
      for (String next : adj.get(cur)) {
        if (reachable.add(next)) {
          queue.add(next);
        }
      }
    }
    for (String code : nodeCodes) {
      if (!reachable.contains(code)) {
        throw BizException.of(
            ResultCode.VALIDATION_ERROR, "error.workflow.dag.node_unreachable", code);
      }
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
