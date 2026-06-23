package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

/** ADR-021 数据对账规则定义。priority-scope §ADR-021 红线：只做"修业务数据"层面的对账，**不**做主数据治理 / 财务核算。 */
@Data
public class DataQualityRuleEntity {

  private Long id;
  private String tenantId;
  private String ruleCode;
  private String ruleName;

  /** ROW_LEVEL / TABLE_LEVEL / CROSS_TABLE / CROSS_DAY。 */
  private String ruleType;

  /** 关联 result_version.business_key（前缀匹配，如 {@code job:DAILY_PNL:}）。 */
  private String scopeBusinessKey;

  /** 规则表达式（SQL / JSONLogic / SPI ref）。 */
  private String expression;

  /** {maxFailRows / failRatio / deltaTolerance...} JSON。 */
  private String thresholdJson;

  /** BLOCKER / WARN / INFO。 */
  private String severity;

  private Boolean enabled;
  private String description;
  private Instant createdAt;
  private Instant updatedAt;
}
