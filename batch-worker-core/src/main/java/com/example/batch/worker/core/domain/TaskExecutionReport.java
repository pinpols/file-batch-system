package com.example.batch.worker.core.domain;

import com.example.batch.common.i18n.AbstractLocalizedErrorEntity;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class TaskExecutionReport extends AbstractLocalizedErrorEntity {

  private Long taskId;
  private String tenantId;
  private String workerId;

  /** Worker 侧的 traceId，用于让 orchestrator 同一条任务实例的日志/审计能够串起来。 */
  private String traceId;

  private boolean success;
  private String code;
  private String message;
  private String resultSummary;
  private String errorCode;

  /**
   * i18n message key (来自 BizException.of)。Worker 侧 BizException 命中 i18n key 时填入, orchestrator 持久化到
   * job_task.error_key,console 读路径按当前 Locale 重渲染。null 表示老 literal / 第三方异常,errorMessage 是唯一展示来源。
   */

  /**
   * 增量执行模式(ExecutionMode.INCREMENTAL)下 worker 上报的新水位高点。业务逻辑通过 {@code
   * ExecutionContext.getAttributes().put(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, ...)}
   * 写入;框架不自动推进。null 表示本次执行无水位变化,orchestrator 不更新 job_instance.high_water_mark_out。
   */
  private String highWaterMarkOut;

  /** ADR-014: optional; sent with report when worker holds current partition invocation id。 */
  private String partitionInvocationId;

  /**
   * ADR-009 Stage 1.2: 节点产出 Map(供下游 workflow 节点 $.nodes.&lt;X&gt;.output.&lt;key&gt; 引用)。Worker 侧仅放
   * JSON 原生类型(String/Number/Boolean/List/Map);orchestrator 在 SUCCESS 路径写入 workflow_node_run.output
   * JSONB。null/empty 等价"无产出",DSL 引用按 null fallback。
   */
  private Map<String, Object> outputs;
}
