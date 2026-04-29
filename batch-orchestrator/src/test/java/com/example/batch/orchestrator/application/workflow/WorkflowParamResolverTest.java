package com.example.batch.orchestrator.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.exception.BizException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADR-009 Stage 2 unit tests:覆盖 4 类引用语法 + 3 类 fail-fast。
 *
 * <p>4 类引用:
 *
 * <ol>
 *   <li>$.nodes.X.output.key 单层
 *   <li>$.nodes.X.output.nested.deep 嵌套下钻
 *   <li>$.workflowRun.bizDate workflow 级共享字段
 *   <li>非引用 String 原样返回
 * </ol>
 *
 * <p>3 类 fail-fast:
 *
 * <ol>
 *   <li>语法非法($.foo / $$.x)→ BizException
 *   <li>未知 node code → BizException
 *   <li>nodes 引用 但 output 缺字段 → 返回 null(非 fail-fast,业务侧兜底)
 * </ol>
 */
class WorkflowParamResolverTest {

  private final WorkflowParamResolver resolver = new WorkflowParamResolver();

  // ── 4 类引用 ────────────────────────────────────────────────────────────────

  @Test
  void resolve_nodeOutputSimple_returnsValue() {
    WorkflowRunContext ctx =
        ctx(Map.of("SETTLE", Map.of("fileId", 12345L, "recordCount", 100)), Map.of());
    assertThat(resolver.resolve("$.nodes.SETTLE.output.fileId", ctx)).isEqualTo(12345L);
    assertThat(resolver.resolve("$.nodes.SETTLE.output.recordCount", ctx)).isEqualTo(100);
  }

  @Test
  void resolve_nodeOutputNested_dotPath() {
    WorkflowRunContext ctx =
        ctx(Map.of("PROCESS", Map.of("metrics", Map.of("validated", 88, "skipped", 12))), Map.of());
    assertThat(resolver.resolve("$.nodes.PROCESS.output.metrics.validated", ctx)).isEqualTo(88);
    assertThat(resolver.resolve("$.nodes.PROCESS.output.metrics.skipped", ctx)).isEqualTo(12);
  }

  @Test
  void resolve_workflowRunField_returnsValue() {
    WorkflowRunContext ctx = ctx(Map.of(), Map.of("bizDate", "2026-04-29", "batchNo", "B001"));
    assertThat(resolver.resolve("$.workflowRun.bizDate", ctx)).isEqualTo("2026-04-29");
    assertThat(resolver.resolve("$.workflowRun.batchNo", ctx)).isEqualTo("B001");
  }

  @Test
  void resolve_nonReferenceString_passThrough() {
    WorkflowRunContext ctx = ctx(Map.of(), Map.of());
    assertThat(resolver.resolve("plain text", ctx)).isEqualTo("plain text");
    assertThat(resolver.resolve("ftp://target/path", ctx)).isEqualTo("ftp://target/path");
    // null 也应原样返回
    assertThat(resolver.resolve(null, ctx)).isNull();
  }

  // ── 3 类 fail-fast / fallback ──────────────────────────────────────────────

  @Test
  void resolve_invalidSyntax_throwsBiz() {
    WorkflowRunContext ctx = ctx(Map.of(), Map.of());
    // 缺少 root segment
    assertThatThrownBy(() -> resolver.resolve("$.", ctx)).isInstanceOf(BizException.class);
    // 错误的 root(非 nodes / workflowRun)
    assertThatThrownBy(() -> resolver.resolve("$.foo.bar", ctx)).isInstanceOf(BizException.class);
    // nodes 路径缺少 output 段
    assertThatThrownBy(() -> resolver.resolve("$.nodes.SETTLE.fileId", ctx))
        .isInstanceOf(BizException.class);
    // nodes 路径段不全
    assertThatThrownBy(() -> resolver.resolve("$.nodes.SETTLE", ctx))
        .isInstanceOf(BizException.class);
  }

  @Test
  void resolve_unknownNodeCode_throwsBiz() {
    WorkflowRunContext ctx = ctx(Map.of("SETTLE", Map.of("fileId", 1L)), Map.of());
    assertThatThrownBy(() -> resolver.resolve("$.nodes.WRONG.output.fileId", ctx))
        .isInstanceOf(BizException.class);
  }

  @Test
  void resolve_knownNodeButMissingOutputField_returnsNull() {
    // 节点存在,output 上报但没有该 key → null fallback(让业务侧 null check 兜底)
    WorkflowRunContext ctx = ctx(Map.of("SETTLE", Map.of("fileId", 1L)), Map.of());
    assertThat(resolver.resolve("$.nodes.SETTLE.output.unknownKey", ctx)).isNull();
  }

  @Test
  void resolve_knownNodeButOutputNull_returnsNull() {
    // 节点存在但整个 output 没上报(老 worker)→ null fallback
    Map<String, Map<String, Object>> outputs = new HashMap<>();
    outputs.put("SETTLE", null);
    WorkflowRunContext ctx = ctxNullable(outputs, Map.of());
    assertThat(resolver.resolve("$.nodes.SETTLE.output.fileId", ctx)).isNull();
  }

  // ── 嵌套结构递归 ───────────────────────────────────────────────────────────

  @Test
  void resolve_nestedMap_recursivelyReplaces() {
    WorkflowRunContext ctx =
        ctx(Map.of("SETTLE", Map.of("fileId", 999L)), Map.of("bizDate", "2026-04-29"));
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileId", "$.nodes.SETTLE.output.fileId");
    params.put("channelCode", "ftp_outbound"); // 字面量原样
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("date", "$.workflowRun.bizDate");
    params.put("_meta", nested);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) resolver.resolve(params, ctx);
    assertThat(result.get("fileId")).isEqualTo(999L);
    assertThat(result.get("channelCode")).isEqualTo("ftp_outbound");
    @SuppressWarnings("unchecked")
    Map<String, Object> resultNested = (Map<String, Object>) result.get("_meta");
    assertThat(resultNested.get("date")).isEqualTo("2026-04-29");
  }

  @Test
  void resolve_listElements_recursivelyReplaces() {
    WorkflowRunContext ctx = ctx(Map.of("EXPORT", Map.of("fileId", 7L)), Map.of());
    List<Object> params = List.of("$.nodes.EXPORT.output.fileId", "literal", 42);
    @SuppressWarnings("unchecked")
    List<Object> result = (List<Object>) resolver.resolve(params, ctx);
    assertThat(result).containsExactly(7L, "literal", 42);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private WorkflowRunContext ctx(
      Map<String, Map<String, Object>> nodeOutputs, Map<String, Object> workflowRunFields) {
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

  /** 节点存在但 output 可能为 null(老 worker 上报场景)。 */
  private WorkflowRunContext ctxNullable(
      Map<String, Map<String, Object>> nodeOutputs, Map<String, Object> workflowRunFields) {
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
}
