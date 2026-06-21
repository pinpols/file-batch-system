# Runbook · 控制总额/笔数对账 —— 开启「拦截」模式

> ADR-041 的笔数(Phase1.1 trailer)+ 金额(Phase1.2 `ControlTotalEvaluator`)对账**都已实现**。本文档讲怎么按需把它从默认的「只告警」开成「不符即拦截交付」。

## 0. 决策存档(2026-06-21「形式化落地」体检维度3)

体检维度3 指出对账「只记不拦」是出账风险。经评估,**保持全局默认 `alert-only`(opt-in 拦截),不翻全局默认**:

- 翻全局默认会让现有 trailer 不规整 / 没配 `amountField` 的租户文件**突然被拒、阻断当天批**,影响面不可控。
- 对账拦截是**逐模板/逐租户**按数据真实情况开启的安全闸,不是全局开关。
- 涉及金额出账的关键模板**应当**显式开启拦截(见 §2),这是推荐姿势;但由配置者按账期数据特征决定,而非平台默认强加。

## 1. 现状:实现了什么,默认什么行为

| 对账 | 实现 | 默认 | 开关 |
|---|---|---|---|
| **笔数**(声明 vs 实际行数) | trailer `declaredRecordCount`(ADR-041 P1.1)+ manifest `recordCount`(ADR-040) | 笔数不符:manifest 路径**无条件拦截**;trailer 路径默认 alert-only | trailer 规则 `blocker` |
| **金额总额**(声明 vs 跨 chunk 累加) | `ControlTotalEvaluator`(ADR-041 P1.2) | 默认 alert-only(`log.warn` + `ValidationIssue` 非阻断) | `controlTotalCheck.blocker` |

- 容差内 → 通过;超差 + `blocker=false` → 仅告警放行;超差 + `blocker=true` → emit 阻断性 `ValidationIssue`(`IMPORT_VALIDATE_CONTROL_TOTAL`)→ 走 ValidateStep 阻断路径,**该批次不交付**。

## 2. 怎么开启拦截

在该 import 模板的 `rule_set`(数据质量规则 JSON)里给 `controlTotalCheck` 配 `blocker: true` + 必要字段:

```json
{
  "controlTotalCheck": {
    "amountField": "amount",          // 累加哪一列(必填,否则该检查不生效)
    "expected": "123456.78",          // 声明总额来源之一;另一来源是 P1.1 trailer 的 declaredControlTotal
    "tolerance": "0.00",              // 容差,默认 0
    "blocker": true                   // ← 关键:超差即阻断交付(默认 false=仅告警)
  }
}
```

笔数拦截(trailer 路径)同理,在 trailer 对应规则上置 `blocker: true`。

**生效范围**:只影响配了这条规则的模板。其它模板不受影响,仍是默认 alert-only。

## 3. 上线姿势(推荐)

1. **先 alert-only 跑一段**:新模板先用默认(不配 `blocker` 或 `blocker:false`),观察告警 / `ValidationIssue` 里 `declared` vs `actual` 是否经常超差——确认声明值来源(trailer/规则 `expected`)和 `amountField` 选对了。
2. **确认数据干净后再翻 `blocker:true`**:把它从「监控」升级为「闸门」。涉及金额出账的模板**建议必开**。
3. **被拦截后的处置**:批次卡在 ValidateStep,`ValidationIssue` 带 `declared`/`actual`/`field`。核对是上游文件错(补正确文件重派)还是声明值配错(改规则);**不要**为了过闸把 `blocker` 关掉再跑——那等于放弃对账保护。

## 4. 边界(ADR-021 对照)

控制总额对账只验**「数对不对」(传输完整性:声明笔数/金额 == 实际)**,**不裁定业务对错**(某笔金额该不该是这个数是业务域的事,ADR-021 红线)。开拦截 = 卡住「传输/解析丢了行或金额对不上」的批,不是卡「业务算错」的批。
