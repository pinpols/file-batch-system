package com.example.batch.orchestrator.controller.request;

import com.example.batch.common.i18n.AbstractLocalizedErrorEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;

// 防滚动升级期间 worker / orchestrator 版本不一致时未识别字段 fail。
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@EqualsAndHashCode(callSuper = false)
public class TaskExecutionReportDto extends AbstractLocalizedErrorEntity {

  private Long taskId;
  private String tenantId;
  private String workerId;

  /** Worker 侧 traceId，用于在 orchestrator 侧把状态推进/重试/补偿日志串起来。 */
  private String traceId;

  private boolean success;
  private String code;
  private String message;
  private String resultSummary;
  private String errorCode;

  /** 增量执行模式下 worker 上报的新水位高点。仅在成功路径回写 {@code job_instance.high_water_mark_out}; null 表示无变化。 */
  private String highWaterMarkOut;

  /**
   * ADR-009 Stage 1.2: 节点产出 Map。orchestrator 在 success 路径序列化为 JSON 写到
   * workflow_node_run.output,供下游节点 $.nodes.&lt;X&gt;.output.&lt;key&gt; 引用。旧 worker 不上报时为
   * null,workflow_node_run.output 保持 null。
   */
  private Map<String, Object> outputs;

  /** ADR-014: optional; mismatched invocation → orchestrator rejects report with CONFLICT。 */
  private String partitionInvocationId;

  /**
   * ADR-012 worker 上报的失败分类（V111）。仅 {@code success=false} 路径有意义；为空时由 orchestrator 端
   * FailureClassifier 兜底推断。允许的取值见 {@link com.example.batch.common.enums.FailureClass}。
   */
  private String failureClass;
}
