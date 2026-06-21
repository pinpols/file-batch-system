# 结算级缺口补全路线图（2026-06-20）

> 配套 `docs/analysis/system-wide-capability-gap-analysis-2026-06-20.md`(全系统能力缺口分析)。把缺口排成可执行的分阶段 PR 计划。
>
> **两类缺口,处置不同**:
> - **配置/校验增强类**(不动架构、不越 ADR 边界)→ 直接 PR。
> - **架构级**(改分层 / 跨模块语义 / 动 run 模型 / 碰 ADR-021 边界)→ **先 ADR + 决策者拍板**,不擅自越界。下表 ⚠️ 标注。

## 总览

| 阶段 | 主题 | PR 数 | 性质 |
|---|---|---|---|
| Phase 0 | `ADR-041 控制总额贯穿闸`(设计) | 1(docs) | 立 ADR |
| Phase 1 | 对账完整性闭环 | ~5 | 全部不动架构/不越界 |
| Phase 2 | 可靠性 / 长跑 / 峰值流量 | ~3 | 含 1 个需小 ADR |
| Phase 3 | 结算治理 / 合规 | ~4 | 含 1 个需 ADR-021 对齐 |
| Phase 4 | 调度 / 到达 / 准实时 | ~4 | 多数需 ADR |
| **合计** | | **≈ 1–2 ADR + 16 实现 PR(约 18 PR)** | 多周程序 |

## Phase 0 — 立 ADR（最高优先,先行）

**`ADR-041 控制总额贯穿闸`**:统一定义贯穿 `import→process→export→dispatch` 的控制总额(笔数 + 金额)契约——trailer schema、跨阶段 count 信封字段、投递回读契约、与 ADR-021 的边界(本 ADR 只管「文件传输完整性:数对不对、全不全」,**不裁定业务对错**)、分阶段。流程同 ADR-040:先设计 review,OK 后逐 PR 落地 Phase 1。

## Phase 1 — 对账完整性闭环（最高价值,~5 PR,全部不动架构/不越界）

| PR | 内容 | 模块 | 备注 |
|---|---|---|---|
| 1.1 | 入站 **trailer 笔数校验**(`DatasetRuleEvaluator` 读 `trailer_template` 声明记录数 vs 实际行数) | import | `trailer_template` 列已存在 |
| 1.2 | **控制金额对账**(汇总金额列 vs trailer 声明总额,新增 `controlTotalCheck` 规则) | import | 复用规则框架 |
| 1.3 | **端到端 count 信封**(REPORT 带 `inputCount/outputCount`,orchestrator 跨阶段核 `import→process→export` 连续性) | core + orchestrator | process 静默丢行可被抓 |
| 1.4 | **出站内嵌控制记录**(export `GenerateStep` 按 `header_template`/`trailer_template` 写头尾笔数/金额进输出文件) | export | 下游可在带内对账 |
| 1.5 | **投递后目的端回读校验**(dispatch 落地后 readback size/checksum 比对 manifest) | dispatch | 传输损坏/半写可被抓 |

> 这 5 条是「一个东西的五个面」,建议在 ADR-041 下统一设计、分 PR 落地。完成后系统「结算级对账」短板补上大半。

## Phase 2 — 可靠性 / 长跑 / 峰值流量（~3 PR）

| PR | 内容 | 模块 | 备注 |
|---|---|---|---|
| 2.1 | **长任务 task 级心跳**(atomic 执行中续 task 心跳,防长 shell/sql 被误判 worker 死) | worker-atomic + core | 直接 |
| 2.2 | **ADR-038 checkpoint/resume 接入** `LoadStep`/`GenerateStep`(表+store 已就绪,接 advance 调用) | worker-core/import/export | 已有 ADR-038 |
| 2.3 | **准入控制 / 过载软节流**(硬拒改软节流/排队,峰值流量不误拒正常请求) | orchestrator | ⚠️ 需小 ADR 决策 |

## Phase 3 — 结算治理 / 合规（~4 PR）

| PR | 内容 | 模块 | 备注 |
|---|---|---|---|
| 3.1 | **双控 maker-checker 强制**(高危操作要第二复核人) | console-api | 结算合规刚需 |
| 3.2 | **dual-use 命令持久审计表**(shell/sql 命令落可查询审计表,非仅日志) | worker-atomic | 取证/合规 |
| 3.3 | **告警升级阶梯**(ack 超时→升级→呼叫 on-call) | console-api | |
| 3.4 | **人工数据修正受控 API**(带护栏 + 操作人可溯) | console-api | ⚠️ 需 ADR-021 边界对齐先拍板 |

## Phase 4 — 调度 / 到达 / 准实时（~4 PR,多数需 ADR）

| PR | 内容 | 模块 | 备注 |
|---|---|---|---|
| 4.1 | **事件驱动到达**(S3/对象存储通知 → 直达 launch,准实时,绕 30s 轮询) | import/trigger | |
| 4.2 | **依赖感知 fire**(上游没就绪就不 fire) | trigger | ⚠️ 架构级,改触发/编排分层,需 ADR |
| 4.3 | **实例 pause/resume** + 批次日严格串行依赖 | orchestrator | ⚠️ 需 ADR |
| 4.4 | 事件/文件到达**触发类型**落地(`TriggerType.EVENT` 补消费者) | trigger | |

## 现实约束（诚实记录）

1. **本地编译受限**:开发环境 JDK 21、项目 `maven.compiler.release=25`,**无法本地编译/跑测**,每个 PR 靠 CI(temurin 25)验证;偶有 spotless/PMD 折行返工(小)。
2. **架构级需先决策**:Phase 2.3 / 3.4 / 4.2 / 4.3 先出 ADR、决策者拍板边界,不擅自越界(尤其 ADR-021「不裁定业务对错」、ADR-027「不挑机器」等范围红线)。
3. **多周程序**:非一次性落地,按阶段稳推;每 PR 小而可评审、可 CI 验证、向后兼容(默认开关关)。

## 建议节奏

**先 Phase 0(ADR-041)+ Phase 1(对账闭环)**——价值最高、5 个 PR 全部不动架构/不越界、是结算系统真正核心门槛。这一簇闭完,系统「结算级」短板补上大半。其余阶段按优先级排。
