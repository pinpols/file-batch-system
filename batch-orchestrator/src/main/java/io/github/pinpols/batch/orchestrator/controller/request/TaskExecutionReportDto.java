package io.github.pinpols.batch.orchestrator.controller.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.pinpols.batch.common.i18n.AbstractLocalizedErrorEntity;
import jakarta.validation.constraints.Size;
import java.util.List;
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

  /**
   * P1-12: 8KB 上界守护,防止 worker 异常或恶意 SDK 发送巨型 message 触发 Jackson + PG JSONB OOM。 旧版 worker 用 message
   * 字段、新版用父类 errorMessage,两者守护一致。
   */
  @Size(max = 8 * 1024, message = "{validation.task_report.error_message_too_long}")
  private String message;

  /** P1-12: 4KB 上界守护,防止 worker 异常或恶意 SDK 发送巨型 resultSummary 触发 Jackson + PG JSONB OOM。 */
  @Size(max = 4 * 1024, message = "{validation.task_report.result_summary_too_long}")
  private String resultSummary;

  private String errorCode;

  /**
   * P1-12: override 父类 {@code errorMessage} getter,在 controller 入口加 8KB 守护; 父类字段本身不动(避免影响持久化路径的其它
   * entity 子类)。
   */
  @Override
  @Size(max = 8 * 1024, message = "{validation.task_report.error_message_too_long}")
  public String getErrorMessage() {
    return super.getErrorMessage();
  }

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
   * FailureClassifier 回退推断。允许的取值见 {@link io.github.pinpols.batch.common.enums.FailureClass}。
   */
  private String failureClass;

  /**
   * ADR-030 §C/F: worker 端 ContentVerifier 检测到的产物级失败列表（仅成功路径携带）。 每个元素 {@code {code, message,
   * evidence}}。orchestrator 在 task SUCCESS 事务里每个失败写一条 {@code
   * outbox_event(event_type='verifier.failure.v1')}；null/empty 等价"全部通过"。
   */
  private List<Map<String, Object>> verifierFailures;
}
