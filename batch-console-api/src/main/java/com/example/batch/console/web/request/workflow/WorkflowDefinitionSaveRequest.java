package com.example.batch.console.web.request.workflow;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class WorkflowDefinitionSaveRequest {
  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(max = 128)
  private String workflowCode;

  @Size(max = 256)
  private String workflowName;

  private String workflowType;
  private Boolean enabled;
  private List<NodeItem> nodes;
  private List<EdgeItem> edges;

  @Data
  public static class NodeItem {
    @NotBlank private String nodeCode;
    private String nodeName;
    @NotBlank private String nodeType;
    private String relatedJobCode;
    private String relatedPipelineCode;
    private String workerGroup;
    private String windowCode;
    private Integer nodeOrder;
    private String retryPolicy;
    private Integer retryMaxCount;
    private Integer timeoutSeconds;
    private String nodeParams;
    private Boolean enabled;
  }

  @Data
  public static class EdgeItem {
    @NotBlank private String fromNodeCode;
    @NotBlank private String toNodeCode;
    private String edgeType;
    private String conditionExpr;
    private Boolean enabled;
  }
}
