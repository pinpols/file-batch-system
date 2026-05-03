package com.example.batch.common.dto;

import com.example.batch.common.enums.TriggerType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.Builder;

/**
 * Launch 请求模型. 触发侧 (CRON / FIXED_RATE / API / MANUAL) 计算 bizDate / 参数后封装提交给 orchestrator.
 *
 * <p><b>data_interval (V94)</b>: 半开区间 {@code [dataIntervalStart, dataIntervalEnd)},
 * 表达"本次跑应该处理哪段时间的数据". 由触发侧计算:
 *
 * <ul>
 *   <li>CRON / FIXED_RATE: {@code [thisFireAt, nextFireAt)} — IMPORT/EXPORT 业务可拼 SQL {@code WHERE
 *       update_time >= :start AND update_time < :end} 真实现分钟级切片
 *   <li>API / MANUAL: 调用方提供, 否则 null
 *   <li>fallback: 退化为 {@code [bizDate.atStartOfDay(zone), bizDate+1.atStartOfDay(zone))} 单点日级
 * </ul>
 *
 * <p>两个字段允许为 null (向后兼容: V94 之前的 trigger 不算 interval, instance 落 null, worker 业务用 bizDate 兜底).
 */
@Builder
public record LaunchRequest(
    String tenantId,
    String jobCode,
    LocalDate bizDate,
    TriggerType triggerType,
    String requestId,
    String traceId,
    Map<String, Object> params,
    Instant dataIntervalStart,
    Instant dataIntervalEnd) {

  /** 简洁兜底构造器: 不带 interval 的旧调用方 (RERUN / 历史路径) 直接传 7 参. */
  public LaunchRequest(
      String tenantId,
      String jobCode,
      LocalDate bizDate,
      TriggerType triggerType,
      String requestId,
      String traceId,
      Map<String, Object> params) {
    this(tenantId, jobCode, bizDate, triggerType, requestId, traceId, params, null, null);
  }
}
