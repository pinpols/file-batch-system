package com.example.batch.console.domain.workflow.infrastructure.excel;

import java.util.Objects;

/**
 * P2-3 god-class-decomposition extract: 工作流 Excel 内的 (workflow / node / edge) 唯一键定义集中地。
 *
 * <p>原 service 内三套 record + equals/hashCode 模板共 ~90 行,集中后 validator 与 service 都从这里取用。
 *
 * <p>三个 record 都显式覆写 equals/hashCode — 与 record 默认生成逻辑一致,仅为绕过 Alibaba 插件对 record 的识别盲区(原代码注释)。
 */
public final class WorkflowExcelKeys {

  private WorkflowExcelKeys() {}

  public record WorkflowKey(String tenantId, String workflowCode, Integer version) {
    public static WorkflowKey of(String tenantId, String workflowCode, Integer version) {
      return new WorkflowKey(tenantId, workflowCode, version);
    }

    public String display() {
      return workflowCode + "#" + version;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof WorkflowKey that)) {
        return false;
      }
      return Objects.equals(tenantId, that.tenantId)
          && Objects.equals(workflowCode, that.workflowCode)
          && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
      return Objects.hash(tenantId, workflowCode, version);
    }
  }

  public record NodeKey(WorkflowKey workflowKey, String nodeCode) {
    public static NodeKey of(WorkflowKey workflowKey, String nodeCode) {
      return new NodeKey(workflowKey, nodeCode);
    }

    public String display() {
      return workflowKey.display() + "/" + nodeCode;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof NodeKey that)) {
        return false;
      }
      return Objects.equals(workflowKey, that.workflowKey)
          && Objects.equals(nodeCode, that.nodeCode);
    }

    @Override
    public int hashCode() {
      return Objects.hash(workflowKey, nodeCode);
    }
  }

  public record EdgeKey(
      WorkflowKey workflowKey, String fromNodeCode, String toNodeCode, String edgeType) {
    public static EdgeKey of(
        WorkflowKey workflowKey, String fromNodeCode, String toNodeCode, String edgeType) {
      return new EdgeKey(workflowKey, fromNodeCode, toNodeCode, edgeType);
    }

    public String display() {
      return workflowKey.display() + "/" + fromNodeCode + "->" + toNodeCode + "(" + edgeType + ")";
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof EdgeKey that)) {
        return false;
      }
      return Objects.equals(workflowKey, that.workflowKey)
          && Objects.equals(fromNodeCode, that.fromNodeCode)
          && Objects.equals(toNodeCode, that.toNodeCode)
          && Objects.equals(edgeType, that.edgeType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(workflowKey, fromNodeCode, toNodeCode, edgeType);
    }
  }
}
