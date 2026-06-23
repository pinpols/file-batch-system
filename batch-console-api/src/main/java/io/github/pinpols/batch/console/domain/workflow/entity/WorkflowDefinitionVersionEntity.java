package io.github.pinpols.batch.console.domain.workflow.entity;

import java.time.Instant;
import lombok.Data;

/**
 * workflow-dag-designer Polish — {@code batch.workflow_definition_version}(V167)。
 *
 * <p>每次 {@code DefaultConsoleWorkflowDefinitionApplicationService#fullUpdate} 成功(主表 version+1)后,
 * 同事务追加一行快照,承载历史 nodes / edges 全文(ObjectMapper.writeValueAsString)。
 *
 * <p>{@code nodesJson} / {@code edgesJson} 为 JSONB 全文,mapper 端 {@code ::text} 读出为字符串, 反序列化由
 * application service 负责。同租户同 {@code (workflow_definition_id, version)} 唯一(DB UNIQUE 约束)。
 */
@Data
public class WorkflowDefinitionVersionEntity {

  private Long id;
  private String tenantId;
  private Long workflowDefinitionId;
  private String workflowCode;
  private Integer version;
  private String workflowName;
  private String workflowType;
  private Boolean enabled;

  /** nodes JSONB 全文(::text 读出),反序列化由调用方负责。 */
  private String nodesJson;

  /** edges JSONB 全文(::text 读出),反序列化由调用方负责。 */
  private String edgesJson;

  private String savedBy;
  private Instant savedAt;
  private String summary;
}
