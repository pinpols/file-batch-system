package com.example.batch.console.web.query;

import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通用控制台用户操作审计查询参数。所有字段可空,空 = 不过滤。
 *
 * <p>4 个核心维度:
 *
 * <ul>
 *   <li>租户(管理员可查任意租户;普通用户被 TenantGuard 限定到自己租户)
 *   <li>操作维度(action / aggregateType / aggregateId)
 *   <li>主体维度(operatorId)
 *   <li>时间窗 + result + traceId(排障 / 合规反查)
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OperationAuditQueryRequest extends PageQueryRequest {
  private String tenantId;
  private String aggregateType;
  private String aggregateId;
  private String action;
  private String operatorId;

  /** "SUCCESS" 或 "FAILED";空 = 全部 */
  private String result;

  private String traceId;

  /** 起始时间(包含),ISO-8601 字符串由 Spring 自动绑定 */
  private Instant startTime;

  /** 结束时间(包含) */
  private Instant endTime;
}
