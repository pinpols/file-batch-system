package com.example.batch.orchestrator.application.workflow;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import io.micrometer.observation.annotation.Observed;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * ADR-009 Stage 2: workflow 节点间参数串联 DSL 解析器.
 *
 * <p>受限 JSONPath 子集,只支持纯路径引用,不支持通配符 / 过滤 / 表达式:
 *
 * <ul>
 *   <li>{@code $.nodes.<nodeCode>.output.<key>} — 引用同 workflow_run 内某节点 output 的某字段
 *   <li>{@code $.workflowRun.<key>} — 引用 workflow_run 级共享字段(如 bizDate / batchNo)
 * </ul>
 *
 * <p><b>fail-mode</b>:
 *
 * <ul>
 *   <li>路径语法非法(根不是 $ / 子段缺失) → 立即抛 {@link BizException}(WORKFLOW_PARAM_REF_INVALID)
 *   <li>引用未知 node code → 立即抛 BizException
 *   <li>引用已知 node 但 output 字段缺失 / output 整体为 null → 返回 null(让业务侧 null 检查回退)
 * </ul>
 *
 * <p>不会修改入参:递归遍历 Map / List 时返回新结构。
 */
@Component
public class WorkflowParamResolver {

  /** 引用语法形如 "$.nodes.SETTLE.output.fileId" / "$.workflowRun.bizDate"。 */
  private static final Pattern REF_PATTERN =
      Pattern.compile("^\\$\\.([a-zA-Z_][a-zA-Z0-9_]*)(\\..+)?$");

  private static final String NODES_ROOT = "nodes";
  private static final String WORKFLOW_RUN_ROOT = "workflowRun";
  private static final String OUTPUT_SEGMENT = "output";

  /**
   * 把 {@code params} 中所有 String 值形如 {@code $.xxx} 的引用替换为 {@link WorkflowRunContext} 中实际值。 非引用字符串 /
   * 数字 / Boolean 原样返回。
   *
   * <p>语义:递归遍历嵌套 Map / List。对 Map value:String 引用 → resolve;Map → 递归;List → 元素递归。
   *
   * @param params workflow_node.node_params 反序列化后的对象(通常是 Map);允许 null,返回 null
   * @param context 当前 workflow_run 上下文(含已完成节点 output 与 workflow 级字段)
   * @return 替换后的对象;原始结构不变
   */
  @Observed(name = "orch.workflow.param-resolve", contextualName = "orch.workflow.param-resolve")
  public Object resolve(Object params, WorkflowRunContext context) {
    if (params == null) {
      return null;
    }
    if (params instanceof String text) {
      return resolveString(text, context);
    }
    if (params instanceof Map<?, ?> map) {
      return resolveMap(map, context);
    }
    if (params instanceof List<?> list) {
      return resolveList(list, context);
    }
    return params;
  }

  private Map<String, Object> resolveMap(Map<?, ?> source, WorkflowRunContext context) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      result.put(String.valueOf(entry.getKey()), resolve(entry.getValue(), context));
    }
    return result;
  }

  private List<Object> resolveList(List<?> source, WorkflowRunContext context) {
    List<Object> result = new ArrayList<>(source.size());
    for (Object item : source) {
      result.add(resolve(item, context));
    }
    return result;
  }

  private Object resolveString(String text, WorkflowRunContext context) {
    if (text == null || !text.startsWith("$.")) {
      // 非引用字符串原样返回
      return text;
    }
    Matcher matcher = REF_PATTERN.matcher(text);
    if (!matcher.matches()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.workflow.param_ref_invalid", text);
    }
    String root = matcher.group(1);
    String tail = matcher.group(2);
    return switch (root) {
      case NODES_ROOT -> resolveNodeRef(text, tail, context);
      case WORKFLOW_RUN_ROOT -> resolveWorkflowRunRef(text, tail, context);
      default ->
          throw BizException.of(
              ResultCode.INVALID_ARGUMENT, "error.workflow.param_ref_invalid", text);
    };
  }

  private Object resolveNodeRef(String fullRef, String tail, WorkflowRunContext context) {
    // tail 形如 ".SETTLE.output.fileId"
    if (tail == null || tail.length() < 2) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.workflow.param_ref_invalid", fullRef);
    }
    String[] segments = tail.substring(1).split("\\.", 3);
    if (segments.length != 3 || !OUTPUT_SEGMENT.equals(segments[1])) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.workflow.param_ref_invalid", fullRef);
    }
    String nodeCode = segments[0];
    String outputKey = segments[2];
    if (!context.hasNode(nodeCode)) {
      // 引用未知 node code 视为 fail-fast(可能是 typo / 配置错)
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.workflow.param_ref_invalid", fullRef);
    }
    Map<String, Object> nodeOutput = context.nodeOutput(nodeCode);
    if (nodeOutput == null) {
      // 节点存在但 output 整体未上报(老 worker 或 worker 没填) → null fallback
      return null;
    }
    return resolveDottedPath(nodeOutput, outputKey);
  }

  private Object resolveWorkflowRunRef(String fullRef, String tail, WorkflowRunContext context) {
    if (tail == null || tail.length() < 2) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.workflow.param_ref_invalid", fullRef);
    }
    String key = tail.substring(1);
    Map<String, Object> shared = context.workflowRunFields();
    if (shared == null) {
      return null;
    }
    // P2-11：移除 containsKey 二次分割校验（与 resolveDottedPath 行为不一致）。
    // 整段路径统一由 resolveDottedPath 决定：缺失 key / 中间非 Map 节点 → null（业务侧 null 回退）。
    return resolveDottedPath(shared, key);
  }

  /** 支持 a.b.c 形式的嵌套 key 在 Map 中下钻;遇到非 Map 中间节点或缺失 key 返回 null。 */
  private Object resolveDottedPath(Map<String, Object> root, String dottedPath) {
    String[] segments = dottedPath.split("\\.");
    Object current = root;
    for (String segment : segments) {
      if (!(current instanceof Map<?, ?> currentMap)) {
        return null;
      }
      current = currentMap.get(segment);
      if (current == null) {
        return null;
      }
    }
    return current;
  }
}
