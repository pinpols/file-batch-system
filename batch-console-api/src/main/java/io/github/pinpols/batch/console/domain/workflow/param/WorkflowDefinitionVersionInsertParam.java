package io.github.pinpols.batch.console.domain.workflow.param;

import lombok.Data;

/**
 * workflow-dag-designer Polish — fullUpdate 同事务追加版本快照入参。
 *
 * <p>{@code nodesJson} / {@code edgesJson} 由 application service {@code
 * ObjectMapper.writeValueAsString} 序列化为字符串载荷,mapper xml 用 {@code ::jsonb} cast 入库, 与 {@code
 * AtomicTaskConfigMapper} 同一风格。
 */
@Data
public class WorkflowDefinitionVersionInsertParam {

  private Long id;
  private String tenantId;
  private Long workflowDefinitionId;
  private String workflowCode;
  private Integer version;
  private String workflowName;
  private String workflowType;
  private Boolean enabled;
  private String nodesJson;
  private String edgesJson;
  private String savedBy;
  private String summary;
}
