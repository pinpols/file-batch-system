# ADR-041 · 控制总额贯穿闸 —— 文件传输完整性的端到端对账

- **Status**: Proposed
- **Date**: 2026-06-20
- **Related**: `docs/analysis/system-wide-capability-gap-analysis-2026-06-20.md`(全系统缺口分析,本 ADR 的源)、`docs/plans/settlement-gap-remediation-roadmap-2026-06-20.md`(Phase 1)、ADR-021(数据质量对账,**边界对照**)、ADR-038(checkpoint)、`DispatchManifestSupport`(出站 sidecar)
- **Plan**: 见 §实施分阶段(= roadmap Phase 1.1–1.5)。本 PR 仅设计文档,评审定方向后逐 PR 落地。

## 范围边界

「**控制总额(笔数 + 金额)从入站声明 → 逐跳重核 → 出站内嵌 → 落地回读 的端到端文件传输完整性闸**」√

- ✅ 入站:校验上游 trailer 声明的**记录数 / 控制金额** vs 实际解析结果(`DatasetRuleEvaluator` 新增 `controlRecordCheck` / `controlTotalCheck`)
- ✅ 跨阶段:`import→process→export→dispatch` 每跳在 REPORT 信封带 `inputCount/outputCount`(+ 可选 `controlTotal`),orchestrator 核**连续性**(上一跳出 == 下一跳入)
- ✅ 出站:export 按 `header_template`/`trailer_template` 把**笔数/金额写进输出文件本身**(下游可带内对账)
- ✅ 落地:dispatch 投递后**回读目的端 size/checksum** 与 manifest 比对

「不做的部分」(明确不做 / 留边界):

- ❌ **不裁定业务对错**(ADR-021 边界):本 ADR 只问「**数对不对、文件全不全、传输有没有丢/损**」,**不问**「这笔金额业务上该不该是这个值」。`controlTotalCheck` 是「汇总金额 == 上游声明总额」(传输完整性),**不是**「金额计算是否正确」(业务对账,ADR-021)。
- ❌ **不做记录级血缘**(逐行 input→output 追踪)——那是独立的 lineage 议题,本 ADR 只到「计数/金额连续性」粒度。
- ❌ **不替代** per-file `.chk`(ADR-040/sidecar manifest):manifest 管「单文件 size/checksum 对不对」,本 ADR 管「**笔数/金额 + 跨阶段连续性 + 落地回读**」,正交叠加。

## 背景

全系统缺口分析(2026-06-20)指出:系统在编排/依赖/复跑/幂等/到达组装/投递机制上生产级偏强,但**结算级对账的承重墙缺失**,且这是单 worker 视角看不到、贯穿四个 worker 的闭环命门:

1. 入站 `header_template`/`trailer_template` 列**存在但无校验逻辑**——「上游声明 1000 笔,实际 999 笔」零告警(行业公认第一道对账闸)。
2. `DatasetRuleEvaluator` 只有 `row_count_check`/`checksum_check`/schema,**无金额汇总比对**——结算文件丢钱不报警。
3. **无端到端 count 连续性**——各阶段各记各的,process 静默丢 10% 仍标 SUCCESS。
4. 出站 export 把 recordCount/totalAmount **只落 DB metadata,未写进文件本身**——下游只能靠可丢的 sidecar。
5. dispatch 投递后**不回读**目的端——传输损坏/半写不发现。

这五条是「一个东西的五个面」:一条贯穿的**控制总额闸**。本 ADR 统一其协议与时序。

## 决策

### 1. 控制记录契约（trailer/header）

`file_template_config.trailer_template` / `header_template`(JSONB,已存在)定义控制记录如何解析/生成:

```json
{
  "trailer": {
    "present": true,
    "match": "LAST_LINE",                 // LAST_LINE / PREFIX:"T" / REGEX:...
    "recordCountField": { "type": "DELIMITED", "index": 1 },
    "controlTotalField": { "type": "DELIMITED", "index": 2, "scale": 2 },
    "excludeFromData": true                // trailer 行不计入数据行
  }
}
```

- **入站**(P1.1/1.2):parse 后按此从 trailer 抽 `declaredRecordCount` / `declaredControlTotal`,与实际 `actualRecordCount` / `SUM(amountColumn)` 比对,不符 → `IMPORT_VALIDATE_CONTROL_RECORD` / `IMPORT_VALIDATE_CONTROL_TOTAL`(走既有 skip/fail 策略)。
- **出站**(P1.4):export 生成时按 `trailer_template` 把 `recordCount`/`totalAmount`(GenerateStep 已算,attrs `recordCount`/`totalAmount`)**写进输出文件头/尾**。

### 2. 跨阶段 count 信封（端到端连续性）

REPORT 信封(`TaskExecutionReport`)增可选 `controlCounts`:`{ inputCount, outputCount, controlTotal }`。

- 每跳 worker 上报本阶段入/出计数(import: 文件行数→入库数;process: 入→出;export: 入→生成行数;dispatch: 文件数)。
- orchestrator 在阶段衔接处核**连续性**:`上一跳.outputCount == 下一跳.inputCount`(允许声明的合法过滤/拒绝差额,需显式记账)。不符 → 告警 + 可配 BLOCKER(默认告警,不阻断,渐进上线)。
- **复用既有字段**:import 已有 loaded_count、export 已有 recordCount、process 已有 publishedCount;本 ADR 把它们**串成连续性契约**,而非新造计数。

### 3. 投递后回读校验（P1.5）

dispatch 投递成功后,对支持回读的渠道(NAS/OSS/SFTP)**readback 目的端 size**(+ 可选 checksum)与 `DispatchManifestSupport` 写的 manifest 比对;不符 → 投递判失败、触发重投(复用 `RetryDispatchStep`)。HTTP 渠道无回读,保持 ACK 语义。

### 4. 与 ADR-021 的边界

| | ADR-041(本) | ADR-021 |
|---|---|---|
| 问题 | 数对不对 / 文件全不全 / 传输丢没丢 | 业务数据该不该是这个值 |
| 例 | 「声明 1000 笔实到 999」「汇总 ¥50M 实 ¥49.99M」 | 「这笔利息算错了」 |
| 性质 | 文件传输完整性 | 业务正确性裁定 |

本 ADR 全程在 ADR-041 列;`controlTotalCheck` 是**传输完整性**(汇总 == 声明),不碰 ADR-021。

## 实施分阶段（= roadmap Phase 1）

| PR | 内容 | 模块 |
|---|---|---|
| **1.1** | 入站 trailer **笔数校验**(`controlRecordCheck` + trailer 解析) | import |
| **1.2** | 入站 **控制金额对账**(`controlTotalCheck`,汇总金额列 vs 声明) | import |
| **1.3** | **跨阶段 count 信封**(REPORT `controlCounts` + orchestrator 连续性核) | core + orchestrator |
| **1.4** | 出站 **内嵌控制记录**(export 按 trailer_template 写头尾) | export |
| **1.5** | **投递后回读校验**(dispatch readback) | dispatch |

每 PR:默认开关关(向后兼容)、复用既有规则框架/信封/manifest、不改热表 schema(模板字段已在 JSONB)、不动 run 模型。

## Consequences

- **正面**:补齐结算级对账承重墙;五个面统一协议,避免散点;全程不越 ADR-021 边界;渐进上线(默认告警非阻断)。
- **代价**:每跳计数有微小开销(汇总金额需扫一遍金额列——可与既有 row_count 同遍完成);trailer 解析需模板配置(上游协议对齐)。
- **风险**:跨阶段连续性的「合法差额」(去重/拒绝行)需显式记账,否则误报——P1.3 需设计差额账。
