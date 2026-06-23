package io.github.pinpols.batch.console.domain.workflow.web.request;

import io.github.pinpols.batch.common.validation.ValidResourceCode;
import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class WorkflowDefinitionSaveRequest {
  @ValidTenantId private String tenantId;

  @ValidResourceCode private String workflowCode;

  @Size(max = 256)
  private String workflowName;

  private String workflowType;
  private Boolean enabled;
  @Valid private List<NodeItem> nodes;
  @Valid private List<EdgeItem> edges;

  @Data
  public static class NodeItem {
    @ValidResourceCode private String nodeCode;
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
    @ValidResourceCode private String fromNodeCode;
    @ValidResourceCode private String toNodeCode;
    private String edgeType;
    private String conditionExpr;
    private Boolean enabled;
  }
}
