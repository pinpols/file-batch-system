package com.example.batch.console.domain.workflow.infrastructure;

import com.example.batch.common.utils.Texts;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowEdgeResponse;
import com.example.batch.console.domain.workflow.web.response.ConsoleWorkflowNodeResponse;
import com.example.batch.console.domain.workflow.web.response.WorkflowDefinitionDetailResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 workflow 定义渲染为 mermaid flowchart 文本。可贴入 GitHub README / PR / 文档站,平台无关。
 *
 * <p>节点形状按 nodeType 选择(START/END=stadium 椭圆,GATEWAY=菱形,FILE_STEP=圆柱,WAIT=子流程, JOB/TASK=矩形);边按
 * edgeType 渲染样式(CONDITION 带表达式 label,FAILURE 虚线)。
 */
public final class WorkflowMermaidRenderer {

  private WorkflowMermaidRenderer() {}

  public static String render(WorkflowDefinitionDetailResponse detail) {
    StringBuilder sb = new StringBuilder(512);
    sb.append("flowchart LR\n");
    if (detail == null) {
      return sb.toString();
    }
    if (Texts.hasText(detail.workflowName()) || Texts.hasText(detail.workflowCode())) {
      sb.append("  %% ")
          .append(detail.workflowName() == null ? "" : detail.workflowName())
          .append(" · ")
          .append(detail.workflowCode() == null ? "" : detail.workflowCode())
          .append(" v")
          .append(detail.version() == null ? "?" : detail.version())
          .append("\n");
    }
    Map<String, String> nodeIds = new HashMap<>();
    List<ConsoleWorkflowNodeResponse> nodes = detail.nodes() == null ? List.of() : detail.nodes();
    for (ConsoleWorkflowNodeResponse n : nodes) {
      if (!Texts.hasText(n.nodeCode())) continue;
      String id = sanitizeId(n.nodeCode());
      nodeIds.put(n.nodeCode(), id);
      sb.append("  ").append(id).append(shapeFor(n)).append("\n");
    }
    List<ConsoleWorkflowEdgeResponse> edges = detail.edges() == null ? List.of() : detail.edges();
    for (ConsoleWorkflowEdgeResponse e : edges) {
      if (!Texts.hasText(e.fromNodeCode()) || !Texts.hasText(e.toNodeCode())) continue;
      String from = nodeIds.getOrDefault(e.fromNodeCode(), sanitizeId(e.fromNodeCode()));
      String to = nodeIds.getOrDefault(e.toNodeCode(), sanitizeId(e.toNodeCode()));
      sb.append("  ").append(from).append(arrowFor(e)).append(to).append("\n");
    }
    return sb.toString();
  }

  private static String shapeFor(ConsoleWorkflowNodeResponse n) {
    String label = escapeLabel(displayLabel(n));
    String type = n.nodeType() == null ? "" : n.nodeType().toUpperCase(Locale.ROOT);
    return switch (type) {
      case "START", "END" -> "([" + label + "])";
      case "GATEWAY" -> "{" + label + "}";
      case "FILE_STEP" -> "[(" + label + ")]";
      case "WAIT" -> "[[" + label + "]]";
      default -> "[" + label + "]";
    };
  }

  private static String displayLabel(ConsoleWorkflowNodeResponse n) {
    if (Texts.hasText(n.nodeName())) {
      return n.nodeName() + " · " + n.nodeCode();
    }
    return n.nodeCode();
  }

  private static String arrowFor(ConsoleWorkflowEdgeResponse e) {
    String type = e.edgeType() == null ? "" : e.edgeType().toUpperCase(Locale.ROOT);
    return switch (type) {
      case "CONDITION" ->
          " -- \""
              + escapeLabel(Texts.hasText(e.conditionExpr()) ? e.conditionExpr() : "?")
              + "\" --> ";
      case "FAILURE" -> " -. failure .-> ";
      case "SUCCESS" -> " -- success --> ";
      case "ALWAYS" -> " --> ";
      default -> " --> ";
    };
  }

  /** Mermaid id 只允许 ASCII 字母数字下划线;首字符必须是 ASCII 字母。中文/标点等一律换成 _。 */
  static String sanitizeId(String raw) {
    StringBuilder sb = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      boolean asciiAlphaNum =
          (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
      if (asciiAlphaNum || c == '_') {
        sb.append(c);
      } else {
        sb.append('_');
      }
    }
    char first = sb.isEmpty() ? '_' : sb.charAt(0);
    boolean firstAsciiLetter = (first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z');
    if (!firstAsciiLetter) {
      sb.insert(0, 'n');
    }
    return sb.toString();
  }

  /** Mermaid label 里不允许裸 " / 换行 / 反引号。 */
  static String escapeLabel(String raw) {
    if (raw == null) return "";
    return raw.replace("\"", "&quot;").replace("\n", " ").replace("`", "'");
  }
}
