# Runbook · 控制总额/笔数对账 —— 开启「拦截」模式

> ADR-041 的笔数(Phase1.1 trailer)+ 金额(Phase1.2 `ControlTotalEvaluator`)对账**都已实现**。规则保持显式启用；启用后缺少核对依据或数值不符即阻断交付。

## 0. 当前策略(2026-10-11)

对账仍为 opt-in：未配置 `controlRecordCheck` / `controlTotalCheck` 的作业不受影响。规则启用后采用严格阻断：

- 金额容差默认 `0`；trailer、manifest 和启用的期望行数检查必须精确相等。
- 启用检查但缺少金额字段、声明总额或声明笔数时，按配置/输入不完整阻断，不静默跳过。
- 任何差异超过容差都阻断；历史 `blocker:false` 不再提供告警放行通道。
- 未启用检查的旧作业保持原行为，不会因平台默认值变更而自动增加对账门禁。

## 1. 现状:实现了什么,默认什么行为

| 对账 | 实现 | 默认 | 开关 |
|---|---|---|---|
| **笔数**(声明 vs 实际行数) | trailer `declaredRecordCount`(ADR-041 P1.1)+ manifest `recordCount`(ADR-040) | 检查启用后缺声明或笔数不符即阻断 | `controlRecordCheck.enabled` |
| **金额总额**(声明 vs 跨 chunk 累加) | `ControlTotalEvaluator`(ADR-041 P1.2) | 检查启用后缺字段/声明或差异超容差即阻断；容差缺省为 0 | `controlTotalCheck.enabled`，`tolerance` 可选 |

- 金额差异 `abs(实际 - 声明) <= tolerance` → 通过；否则 emit 阻断性 `ValidationIssue`(`IMPORT_VALIDATE_CONTROL_TOTAL`)→ 走 ValidateStep 阻断路径，**该批次不交付**。`IMPORT_VALIDATE_CONTROL_RECORD`、manifest/`rowCountCheck` 的 `IMPORT_VALIDATE_ROW_COUNT` 同样按数据集级硬门禁处理，不进入坏记录跳过策略。

## 2. 怎么开启拦截

在该 import 模板的 `rule_set`(数据质量规则 JSON)里显式启用 `controlTotalCheck` 并提供必要字段:

```json
{
  "controlTotalCheck": {
    "amountField": "amount",          // 累加哪一列(必填,否则该检查不生效)
    "expected": "123456.78",          // 声明总额来源之一;另一来源是 P1.1 trailer 的 declaredControlTotal
    "tolerance": "0.00"               // 可选容差;省略时严格零容差
  }
}
```

笔数校验通过 `controlRecordCheck.enabled=true` 显式启用，启用后缺少 trailer 声明笔数或数值不一致均阻断。`blocker` 字段不再控制是否阻断；旧值 `false` 不会让差异放行。

**生效范围**：只影响显式启用了规则的模板；完全未配置规则的模板不受影响。启用前必须确认 trailer/manifest 声明字段和 `amountField` 完整、可解析。

## 3. 上线姿势(推荐)

1. 配置前核对声明值来源(trailer/规则 `expected`)、声明笔数和 `amountField`，并使用同一份业务样本做预演。
2. 打开规则后，零容差为默认；任何差异都阻断。只有获批的作业级 `tolerance` 可放宽金额差异，不能用 `blocker:false` 绕过。
3. 被拦截后，`ValidationIssue` 提供 `declared`/`actual`/`field`。核对上游文件和配置；不得为放行而关闭阻断语义。

## 4. 边界(ADR-021 对照)

控制总额对账只验**「数对不对」(传输完整性:声明笔数/金额 == 实际)**,**不裁定业务对错**(某笔金额该不该是这个数是业务域的事,ADR-021 红线)。开拦截 = 卡住「传输/解析丢了行或金额对不上」的批,不是卡「业务算错」的批。
