package com.example.batch.console.domain.job.web.query;

import com.example.batch.console.web.query.PageQueryRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** {@code batch.job_execution_log} 查询请求。jobInstanceId 必填——日志查看锚定单个实例。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class JobExecutionLogQueryRequest extends PageQueryRequest {

  private String tenantId;

  @NotNull private Long jobInstanceId;

  /** 可选:只看某个分区的日志。 */
  private Long jobPartitionId;

  /** 可选:DEBUG / INFO / WARN / ERROR 单值过滤。 */
  private String logLevel;

  /** 可选:SYSTEM / BUSINESS / RETRY / ALARM / AUDIT 单值过滤。 */
  private String logType;

  /** 可选:trace snapshot 内部聚合使用；外部日志查看仍要求 jobInstanceId。 */
  private String traceId;

  /** 可选:message 模糊匹配。 */
  private String keyword;
}
