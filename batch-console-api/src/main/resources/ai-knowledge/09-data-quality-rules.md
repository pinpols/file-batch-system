# 数据质量(对账)规则草稿:字段规范 → ruleType → expression/threshold 写法 → 示例

当用户要为某张表 / 某业务生成数据质量(对账)规则时,依据本规范输出**规则草稿建议**(JSON 或文本)。
边界(ADR-021 红线):对账只做「修业务数据」层面的核对,**不**做主数据治理 / 财务核算。
**AI 只输出草稿,不写库**:草稿需人工复核后,在控制台 / orchestrator 的规则管理入口保存生效。AI 没有也不会调用任何创建 / 更新 / 删除规则的能力。规则存储在 orchestrator 侧 `data_quality_rule` 表,console 无直接读接口,故本能力基于本规范生成草稿,不跨模块读取现有规则。

## 规则字段(DataQualityRuleEntity)
草稿应给出这些字段:
- `ruleCode` — 规则唯一编码(租户内唯一,建议大写下划线,如 `DAILY_PNL_ROWCOUNT`)。
- `ruleName` — 人类可读名称。
- `ruleType` — 规则类型,见下枚举。
- `scopeBusinessKey` — 关联 `result_version.business_key` 的**前缀**(前缀匹配),如 `job:DAILY_PNL:`。
- `expression` — 规则表达式(TABLE_LEVEL 为受限 SQL;其它类型为 SPI ref / 说明)。
- `thresholdJson` — 阈值 JSON,见下写法。
- `severity` — `BLOCKER` / `WARN` / `INFO`(注意:与告警 severity 枚举不同)。
  - `BLOCKER` — 命中即阻断后续 / 判失败。
  - `WARN` / `INFO` — 仅记录,不阻断。
- `enabled` — true/false。
- `description` — 说明。

## ruleType 枚举(四类)
- `TABLE_LEVEL` — 表级:一条受限 SQL 返回**单个数值 metric**,按 `thresholdJson` 判定 PASS/FAIL。**这是引擎目前真正执行的类型**。
- `ROW_LEVEL` — 行级:逐行校验。当前为占位,业务方经 SPI sink 直接写 `data_quality_check`,引擎读取汇总。
- `CROSS_TABLE` — 跨表核对(如两表金额/笔数一致)。当前同为占位 + SPI sink 模式。
- `CROSS_DAY` — 跨日核对(如今日 vs 昨日笔数偏差)。同为占位 + SPI sink 模式。
给草稿时:能用一条聚合 SQL 表达的,优先建 `TABLE_LEVEL`;需要逐行/跨源比对的,标注为 `ROW_LEVEL/CROSS_TABLE/CROSS_DAY` 并说明需业务方 SPI sink 写入结果。

## TABLE_LEVEL 的 expression(受限 SQL)规范
SQL 由 `SensorSqlValidator` 校验 + `NamedParameterJdbcTemplate` 绑定参数执行,草稿必须遵守:
- 必须是 `SELECT` 或 `WITH`(只读),**禁止** `SELECT *`(必须显式列 / 聚合)。
- 只能访问白名单 schema:`batch`、`archive`(如 `batch.xxx` / `archive.xxx`)。
- 结果应为**单行单值数值**(如 `COUNT(*)`、`SUM(...)`、比率)。
- 可用命名参数(引擎自动绑定,勿手工拼接):`:tenantId`、`:bizDate`、`:jobInstanceId`。
- 参数用绑定,不要字符串拼接(防注入)。

## thresholdJson 写法
引擎 `matchThreshold` 支持的键:
- `{"expected": N}` — 实际值必须等于 N,否则 FAIL。
- `{"min": A}` / `{"max": B}` / `{"min": A, "max": B}` — 实际值须落在 [A,B](给哪个判哪个)。
- 省略 threshold 时,回退语义为 `actual > 0` 即视为命中(通常表示「存在坏数据即失败」)。
概念性阈值键(如 `maxFailRows` / `failRatio` / `deltaTolerance`)可在 ROW_LEVEL/CROSS_* 的 SPI 结果里体现;
草稿里若用这些,要说明由业务 sink 侧解释,而非 TABLE_LEVEL 引擎直接判。

## 常见规则草稿示例

空值率 / 关键字段非空(TABLE_LEVEL):
```json
{
  "ruleCode": "ORDER_NULL_AMOUNT",
  "ruleName": "订单金额非空",
  "ruleType": "TABLE_LEVEL",
  "scopeBusinessKey": "job:DAILY_ORDER:",
  "expression": "SELECT COUNT(*) FROM batch.order_fact WHERE tenant_id = :tenantId AND biz_date = :bizDate AND amount IS NULL",
  "thresholdJson": "{\"max\": 0}",
  "severity": "BLOCKER"
}
```

唯一性 / 无重复主键(TABLE_LEVEL):
```json
{
  "ruleCode": "ORDER_DUP_ID",
  "ruleName": "订单号唯一",
  "ruleType": "TABLE_LEVEL",
  "expression": "SELECT COUNT(*) FROM (SELECT order_id FROM batch.order_fact WHERE tenant_id = :tenantId AND biz_date = :bizDate GROUP BY order_id HAVING COUNT(*) > 1) d",
  "thresholdJson": "{\"max\": 0}",
  "severity": "BLOCKER"
}
```

范围 / 值域(TABLE_LEVEL):
```json
{
  "ruleCode": "ORDER_ROWCOUNT_RANGE",
  "ruleName": "订单笔数在合理区间",
  "ruleType": "TABLE_LEVEL",
  "expression": "SELECT COUNT(*) FROM batch.order_fact WHERE tenant_id = :tenantId AND biz_date = :bizDate",
  "thresholdJson": "{\"min\": 1000, \"max\": 2000000}",
  "severity": "WARN"
}
```

枚举 / 合法取值(TABLE_LEVEL,统计非法值行数):
```json
{
  "ruleCode": "ORDER_BAD_STATUS",
  "ruleName": "订单状态取值合法",
  "ruleType": "TABLE_LEVEL",
  "expression": "SELECT COUNT(*) FROM batch.order_fact WHERE tenant_id = :tenantId AND biz_date = :bizDate AND status NOT IN ('NEW','PAID','CANCELLED')",
  "thresholdJson": "{\"max\": 0}",
  "severity": "BLOCKER"
}
```
(注:DQ severity 只有 BLOCKER/WARN/INFO 三档,不要误用告警的 ERROR/CRITICAL。)

跨日笔数偏差(CROSS_DAY,占位 + SPI):
```json
{
  "ruleCode": "ORDER_DOD_DELTA",
  "ruleName": "订单笔数日环比偏差",
  "ruleType": "CROSS_DAY",
  "expression": "SPI:orderDodDeltaCheck",
  "thresholdJson": "{\"deltaTolerance\": 0.2}",
  "severity": "WARN",
  "description": "由业务 SPI sink 计算今日 vs 昨日笔数偏差,写 data_quality_check;引擎读取汇总。"
}
```

输出草稿后,始终提醒:以上为草稿建议,请人工复核字段 / SQL / 阈值,确认无误后在控制台保存生效。
