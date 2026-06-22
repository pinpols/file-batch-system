package com.example.batch.console.domain.job.web.query;

import com.example.batch.console.web.query.PageQueryRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class JobInstanceQueryRequest extends PageQueryRequest {

  private String tenantId;
  private String jobCode;
  private String instanceNo;
  private String instanceStatus;
  private String bizDate;
  private String traceId;
  private String startDate;
  private String endDate;

  /** 排序方式：id（默认）、duration（按运行时长降序，用于慢任务诊断）。 */
  private String sortBy;

  /** 最小运行时长过滤（秒）：仅返回运行时长 ≥ 该值的实例，用于慢任务诊断。 */
  private Integer minDurationSeconds;

  /**
   * SLA 违约过滤：true 时仅返回 deadline_at &lt; now
   * 且仍在活跃态(CREATED/WAITING/READY/RUNNING/PARTIAL_FAILED)的实例。 与 OpsSummary 的 slaBreaches
   * 指标使用相同判定,供移动端「SLA 违约」入口跳转复用。
   */
  private Boolean slaBreached;

  /**
   * 多状态过滤(CSV,如 "FAILED,PARTIAL_FAILED"):
   *
   * <p>OpsSummary 的「失败任务」卡片同时计入 FAILED + PARTIAL_FAILED,跳转列表需要多值过滤。 instanceStatus 单值字段保留兼容,
   * instanceStatuses 不为空时优先生效(IN 查询)。
   */
  private String instanceStatuses;
}
