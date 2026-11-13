# 其余 Mermaid 图审查与修改计划（2026-11-13）

## 结论

- 基线 `origin/main` `250142c30`（含 #1365）。范围是除归档外、架构导航 17 张核心图之外的其余 **110 张** Mermaid 图，分布在 50 个文件：flowchart 89、sequenceDiagram 8、stateDiagram-v2 6、classDiagram 4、erDiagram 3。
- 110 张在 Mermaid 11.17.2 的 neutral 与 dark 主题下全部解析、渲染成功；没有图写死颜色，也没有图按语义区分角色。
- 主要问题不是语法，而是**风格与导航图不一致**、少数图**布局失控或过宽**、两份状态图**口径并存**。按优先级：P1 4 张、P2 23 张、P3 83 张。
- 本文是审查与计划，只读；没有修改任何图。实施按下文批次另行授权。

## 与既有工作的关系

- [2026-10-25 现行文档图整理](../audit/document-diagram-refresh-2026-10-25.md)完成了图义修正、ASCII 转换和拆图，并刻意去掉了无语义的多色样式。本计划不重复其语义工作。
- [#1365](https://github.com/pinpols/file-batch-system/pull/1365) 在[架构导航](../architecture/README.md#图例)为 17 张核心图引入按角色着色的图例。本计划把同一图例推广到其余图；颜色只表达图例中定义的角色或结果语义，与 10-25 记录及[文档治理](../standards/document-governance.md) §9「颜色只用于明确的语义」一致。

## 方法与证据

- 从现行文档抽取图块，用与文档站相同的 Mermaid 11.17.2（`batch-console/node_modules`）在本地 Chrome 中按 neutral、dark 渲染，记录解析结果与 SVG 尺寸，并保存亮色截图。
- 人工抽看问题最明显的图：K8s 目标架构、README 总图、排障决策树、端到端时序、job_instance 状态机、ADR-046 恢复流程。
- 代码抽查：`system-flow-overview` §2.1、§2.2 的阶段名与 `ImportStage`（RECEIVE…FEEDBACK）、`ExportStage`（PREPARE…COMPLETE）一致。job_instance 没有集中迁移表，迁移分散在服务代码中，状态图需逐条核对。
- 尺寸只用于筛查，不作为验收标准；渲染成功不等于内容正确。

## 主要发现

| 优先级 | 发现 | 位置 |
|---|---|---|
| P1 | 首页总图把执行面放在最上、箭头向上回流，与导航「运行主链」重复且读向相反 | `README.md`、`README.en.md` |
| P1 | job_instance 状态机用 flowchart 画，状态集合比核心模型状态路径多 WAITING/READY/PARTIAL_FAILED/CANCELLED；两处并存但未说明主从 | `design/status-state-machines.md` §3.1 |
| P1 | K8s + GitOps 目标架构 21 条连线大面积交叉，基本不可读 | `runbook/compose-cd-roadmap.md` §12 |
| P2 | 端到端时序 6 个参与方、宽约 1450–1544px，文档站正文约 690px，缩放后字过小 | `system-flow-overview` §4.1–4.3、`workflow-dependency-guide` §3.1 |
| P2 | 细高图：排障决策树 1567px、时间语义 1110/1406px、回路图 354×1127、ER 480×1210 | 见附录 |
| P2 | §4.4 三张极小对比图结构重复 | `system-flow-overview` §4.4 |
| P2 | 文件链路统一视图、执行架构与 `system-flow-overview` §2 有内容重叠，存在口径漂移风险 | `design/file-pipeline-design.md` |
| P3 | 其余图只需按图例统一角色颜色、形状（存储圆柱、Kafka 六边形、外部虚线框）、结果着色 | 见附录 |
| 观察 | ADR-046 恢复流程高 1901px，超过 10-25 记录的 1700px 筛查线（ADR 为决策记录，默认不改） | `adr/ADR-046` |

## 统一约定

- 沿用[架构导航图例](../architecture/README.md#图例)：入口、控制面、消息、执行面、持久化、外部/可选、共享库；状态与决策图用成功/失败/等待/跳过四色；半透明填充以适配明暗主题。
- 子图表达分层或信任边界；外部节点连到子图本身，避免子图方向失效和标题遮挡。
- 时序图参与方按平台/执行/外部分组（`box`），步骤多时加 `autonumber`。
- 不改变节点、连线和图示内容；状态与阶段图在改样式前先对照代码。ADR 与阶段计划保留原貌。

## 批次计划

| 批次 | 内容 | 图数（优先级） | 关键动作 | 关闭条件 |
|---|---|---|---|---|
| B0 | 图例入规范 | — | 将架构导航图例要点写入文档治理 §9，作为其余图的着色依据 | 规范与图例一致，文档检查通过 |
| B1 | 入口总图 | 2（P1 2） | 按导航主链图重绘中英两份，结构一致 | 中英一致、与导航主链口径相同 |
| B2 | 状态与阶段图核对 | 6（P1 1、P2 3、P3 2） | 先对照代码核对迁移，再把 flowchart 状态机改为 stateDiagram 并结果着色；明确与核心模型状态路径的主从并互链 | 每条迁移附代码出处，状态集合与枚举一致 |
| B3 | 部署拓扑 | 6（P1 1、P2 1、P3 4） | §12 拆图或用子图收敛连线；其余按图例着色，保留「当前/目标/规划」标识 | 无大面积交叉，目标/当前标识保留 |
| B4 | 时序图 | 8（P2 4、P3 4） | 参与方分组、autonumber；评估 §4.1–4.3 共用骨架只保留差异 | 正文宽度下文字可读 |
| B5 | 决策树 | 9（P2 1、P3 8） | 结论节点着色；最高的排障树评估横向或拆段 | 结论可一眼区分 |
| B6 | 系统流程其余图 | 22（P2 8、P3 14） | §4.4 合并或改表；细高图改横向；总览图分层并与导航图互链 | 无重复图、无细高图 |
| B7 | 设计文档图 | 23（P2 5、P3 18） | 细高图改横向；与 system-flow 重叠处改引用；其余着色 | 重复处改为引用 |
| B8 | ER 与类图 | 7（P2 1、P3 6） | ER/类图不着色，只核对关系与类名仍存在；细高 ER 评估 LR | 关系与迁移脚本一致 |
| B9 | runbook 其余图 | 11（P3 11） | 按图例着色，细高图评估横向 | 风格一致 |
| B10 | ADR 与阶段计划 | 16（P3 16） | 默认不改；仅对仍为现行入口的 ADR 可选着色 | 不改变决策记录内容 |

建议顺序：B0 → B1、B2、B3 的 P1 → B4、B5、B6 的 P2 → B7、B9 → B8 → B10（可选）。每批独立 PR，只改对应文件。

## 每批验收

- 用文档站同版本 Mermaid 在 neutral、dark 下全部解析、渲染并截图人工复核：交叉线、标题遮挡、宽图、暗色对比度。
- 状态与阶段图逐条列出代码出处；其余图核对节点与连线语义未变。
- 运行 `check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py`、`git diff --check`；至少一批在文档站实际构建预览（注意 `docs:size` 体积门禁已有两份超限报告，与本计划无关）。
- `docs/changelog.md` 记录每批变化；渲染通过不写成业务或部署验收。

## 风险与不做的事

- 不改归档、ADR 决策内容和阶段计划原貌；不借重绘改变当前/目标/规划的属性。
- 着色依赖图例语义；若后续图例调整，需同步全部已着色图。
- 每张图增加约 7 行 `classDef`，页面体积略增；对 `docs:size` 门禁的影响在实施批次中实测。

## 附录：逐图台账

| # | 位置 | 类型 | 渲染尺寸 | 批次 | 优先级 | 问题 | 建议动作 |
|---|---|---|---|---|---|---|---|
| 000 | [`README.en.md`](../../README.en.md) 第 1 张（L54） | flowchart | 1037×702 | B1 | P1 | 首页总图把执行面放在最上、箭头向上回流，与导航主链图口径重复 | 按导航主链图重绘中英文两份，保持中英结构一致 |
| 001 | [`README.md`](../../README.md) 第 1 张（L54） | flowchart | 962×652 | B1 | P1 | 首页总图把执行面放在最上、箭头向上回流，与导航主链图口径重复 | 按导航主链图重绘中英文两份，保持中英结构一致 |
| 071 | [`design/status-state-machines.md`](../design/status-state-machines.md) 第 1 张（L67） | flowchart | 1017×550 | B2 | P1 | 用 flowchart 表达状态机；比核心模型状态图多 WAITING/READY/PARTIAL_FAILED/CANCELLED，两处口径并存 | 对照代码迁移点核对后改 stateDiagram；明确与核心模型「典型路径」的主从关系并互链 |
| 039 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 21 张（L659） | stateDiagram-v2 | 611×754 | B2 | P2 | 17 条迁移的总状态图，较拥挤 | 核对代码后按实体拆分或分组，结果着色 |
| 072 | [`design/status-state-machines.md`](../design/status-state-machines.md) 第 2 张（L84） | flowchart | 686×262 | B2 | P2 | 用 flowchart 表达状态机 | 核对代码迁移后改 stateDiagram，结果着色 |
| 073 | [`design/status-state-machines.md`](../design/status-state-machines.md) 第 3 张（L98） | flowchart | 625×472 | B2 | P2 | 用 flowchart 表达状态机 | 核对代码迁移后改 stateDiagram，结果着色 |
| 025 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 7 张（L257） | stateDiagram-v2 | 417×550 | B2 | P3 | 状态图未按结果着色 | 核对迁移后按图例着色 |
| 041 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 23 张（L741） | stateDiagram-v2 | 310×526 | B2 | P3 | 状态图未按结果着色 | 核对迁移后按图例着色 |
| 096 | [`runbook/compose-cd-roadmap.md`](../runbook/compose-cd-roadmap.md) 第 2 张（L252） | flowchart | 1457×1378 | B3 | P1 | K8s 目标架构 21 条连线大量交叉，难以阅读 | 拆为「应用层」「数据与基础设施层」两图或按 subgraph 指向收敛连线；保留「目标/规划」标识 |
| 095 | [`runbook/compose-cd-roadmap.md`](../runbook/compose-cd-roadmap.md) 第 1 张（L197） | flowchart | 1283×1078 | B3 | P2 | 两节点 Compose 生产架构 17 条连线，较密 | 按节点分区、连线指向子图收敛；保留「当前推荐」标识 |
| 097 | [`runbook/compose-cd-roadmap.md`](../runbook/compose-cd-roadmap.md) 第 4 张（L366） | flowchart | 1340×142 | B3 | P3 | 演进路线横条 | 按图例着色并标阶段 |
| 101 | [`runbook/gitlab-self-managed-migration.md`](../runbook/gitlab-self-managed-migration.md) 第 1 张（L21） | flowchart | 1453×438 | B3 | P3 | 部署/接入拓扑未区分角色 | 按图例着色、外部系统虚线框 |
| 102 | [`runbook/mobile-frontend-tunnel.md`](../runbook/mobile-frontend-tunnel.md) 第 1 张（L7） | flowchart | 420×676 | B3 | P3 | 部署/接入拓扑未区分角色 | 按图例着色、外部系统虚线框 |
| 103 | [`runbook/observability-stack.md`](../runbook/observability-stack.md) 第 1 张（L8） | flowchart | 955×295 | B3 | P3 | 部署/接入拓扑未区分角色 | 按图例着色、外部系统虚线框 |
| 033 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 15 张（L495） | sequenceDiagram | 1447×1075 | B4 | P2 | 6 个参与方的端到端时序，宽约 1450–1500px，文档站缩放后字过小 | 参与方分组（box）、加 autonumber；评估三图共用骨架只保留差异段 |
| 034 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 16 张（L535） | sequenceDiagram | 1495×1075 | B4 | P2 | 6 个参与方的端到端时序，宽约 1450–1500px，文档站缩放后字过小 | 参与方分组（box）、加 autonumber；评估三图共用骨架只保留差异段 |
| 035 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 17 张（L578） | sequenceDiagram | 1495×1075 | B4 | P2 | 6 个参与方的端到端时序，宽约 1450–1500px，文档站缩放后字过小 | 参与方分组（box）、加 autonumber；评估三图共用骨架只保留差异段 |
| 047 | [`architecture/workflow-dependency-guide.md`](../architecture/workflow-dependency-guide.md) 第 3 张（L108） | sequenceDiagram | 1544×1087 | B4 | P2 | Workflow 端到端时序宽 1544px、高 1087px | 参与方分组、autonumber，必要时拆为派发与回报两段 |
| 022 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 2 张（L77） | sequenceDiagram | 1368×571 | B4 | P3 | 时序图未分组 | 参与方按平台/执行/外部分组，加 autonumber |
| 040 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 22 张（L716） | sequenceDiagram | 980×675 | B4 | P3 | 时序图未分组 | 参与方按平台/执行/外部分组，加 autonumber |
| 064 | [`design/file-pipeline-design.md`](../design/file-pipeline-design.md) 第 6 张（L905） | sequenceDiagram | 986×655 | B4 | P3 | 时序图未分组 | 参与方按平台/执行/外部分组，加 autonumber |
| 098 | [`runbook/console-login-encryption.md`](../runbook/console-login-encryption.md) 第 1 张（L28） | sequenceDiagram | 650×350 | B4 | P3 | 时序图未分组 | 参与方按平台/执行/外部分组，加 autonumber |
| 105 | [`runbook/troubleshooting-decision-tree.md`](../runbook/troubleshooting-decision-tree.md) 第 1 张（L25） | flowchart | 1158×1567 | B5 | P2 | 排障决策树纵向 1567px，阶梯式斜排 | 结论节点按症状着色；评估改横向或拆为两段 |
| 007 | [`architecture/adr/ADR-019-cross-domain-rate-limit.md`](../architecture/adr/ADR-019-cross-domain-rate-limit.md) 第 1 张（L78） | flowchart | 492×754 | B5 | P3 | 决策树未区分结论 | 结论/推荐项着色；082 高 1172px 可评估横向 |
| 053 | [`coding-conventions.md`](../coding-conventions.md) 第 3 张（L486） | flowchart | 429×538 | B5 | P3 | 决策树未区分结论 | 结论/推荐项着色；082 高 1172px 可评估横向 |
| 058 | [`design/delete-strategy.md`](../design/delete-strategy.md) 第 1 张（L20） | flowchart | 687×693 | B5 | P3 | 决策树未区分结论 | 结论/推荐项着色；082 高 1172px 可评估横向 |
| 082 | [`design/worker-deployment-models.md`](../design/worker-deployment-models.md) 第 4 张（L501） | flowchart | 557×1172 | B5 | P3 | 决策树未区分结论 | 结论/推荐项着色；082 高 1172px 可评估横向 |
| 099 | [`runbook/distributed-locking-checklist.md`](../runbook/distributed-locking-checklist.md) 第 1 张（L22） | flowchart | 1346×570 | B5 | P3 | 决策树未区分结论 | 结论/推荐项着色；082 高 1172px 可评估横向 |
| 106 | [`runbook/troubleshooting-decision-tree.md`](../runbook/troubleshooting-decision-tree.md) 第 2 张（L85） | flowchart | 695×1005 | B5 | P3 | 排障决策树 | 结论节点着色，判断节点保持菱形 |
| 107 | [`runbook/troubleshooting-decision-tree.md`](../runbook/troubleshooting-decision-tree.md) 第 3 张（L144） | flowchart | 703×1178 | B5 | P3 | 排障决策树 | 结论节点着色，判断节点保持菱形 |
| 108 | [`runbook/troubleshooting-decision-tree.md`](../runbook/troubleshooting-decision-tree.md) 第 5 张（L240） | flowchart | 1236×592 | B5 | P3 | 排障决策树 | 结论节点着色，判断节点保持菱形 |
| 020 | [`architecture/runtime-module-communication.md`](../architecture/runtime-module-communication.md) 第 1 张（L16） | flowchart | 1034×896 | B6 | P2 | 连线 12–17 条的总览图，未分层 | 按控制/执行/存储分组并着色；020 为模块通信总览，需与导航模块图互链避免口径漂移 |
| 023 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 3 张（L109） | flowchart | 879×591 | B6 | P2 | 连线 12–17 条的总览图，未分层 | 按控制/执行/存储分组并着色；020 为模块通信总览，需与导航模块图互链避免口径漂移 |
| 024 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 6 张（L238） | flowchart | 928×550 | B6 | P2 | 连线 12–17 条的总览图，未分层 | 按控制/执行/存储分组并着色；020 为模块通信总览，需与导航模块图互链避免口径漂移 |
| 036 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 18 张（L617） | flowchart | 600×229 | B6 | P2 | 4.4 节三张极小对比图（高约 230px）重复结构 | 合并为一张对比图或改表格 |
| 037 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 19 张（L627） | flowchart | 617×239 | B6 | P2 | 4.4 节三张极小对比图（高约 230px）重复结构 | 合并为一张对比图或改表格 |
| 038 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 20 张（L637） | flowchart | 660×225 | B6 | P2 | 4.4 节三张极小对比图（高约 230px）重复结构 | 合并为一张对比图或改表格 |
| 042 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 24 张（L821） | flowchart | 354×1127 | B6 | P2 | 7.8.2 回路图细高（354×1127） | 改横向或分组，减少纵向空白 |
| 043 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 25 张（L897） | flowchart | 683×950 | B6 | P2 | 连线 12–17 条的总览图，未分层 | 按控制/执行/存储分组并着色；020 为模块通信总览，需与导航模块图互链避免口径漂移 |
| 017 | [`architecture/core-model.md`](../architecture/core-model.md) 第 2 张（L172） | flowchart | 513×826 | B6 | P3 | 流程/阶段图未区分角色 | 按图例着色；阶段名已抽查与 ImportStage/ExportStage 一致，其余阶段改动前核对代码 |
| 019 | [`architecture/pipeline-vs-workflow-boundary.md`](../architecture/pipeline-vs-workflow-boundary.md) 第 1 张（L11） | flowchart | 620×670 | B6 | P3 | 流程/阶段图未区分角色 | 按图例着色；阶段名已抽查与 ImportStage/ExportStage 一致，其余阶段改动前核对代码 |
| 021 | [`architecture/runtime-module-communication.md`](../architecture/runtime-module-communication.md) 第 2 张（L37） | flowchart | 717×755 | B6 | P3 | 流程/阶段图未区分角色 | 按图例着色；阶段名已抽查与 ImportStage/ExportStage 一致，其余阶段改动前核对代码 |
| 026 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 8 张（L290） | flowchart | 991×696 | B6 | P3 | 流程/阶段图未区分角色 | 按图例着色；阶段名已抽查与 ImportStage/ExportStage 一致，其余阶段改动前核对代码 |
| 027 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 9 张（L339） | flowchart | 982×70 | B6 | P3 | 流程/阶段图未区分角色 | 按图例着色；阶段名已抽查与 ImportStage/ExportStage 一致，其余阶段改动前核对代码 |
| 028 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 10 张（L356） | flowchart | 830×70 | B6 | P3 | 流程/阶段图未区分角色 | 按图例着色；阶段名已抽查与 ImportStage/ExportStage 一致，其余阶段改动前核对代码 |
| 029 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 11 张（L372） | flowchart | 578×574 | B6 | P3 | 流程/阶段图未区分角色 | 按图例着色；阶段名已抽查与 ImportStage/ExportStage 一致，其余阶段改动前核对代码 |
| 030 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 12 张（L404） | flowchart | 1068×265 | B6 | P3 | 流程/阶段图未区分角色 | 按图例着色；阶段名已抽查与 ImportStage/ExportStage 一致，其余阶段改动前核对代码 |
| 031 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 13 张（L432） | flowchart | 269×814 | B6 | P3 | 流程/阶段图未区分角色 | 按图例着色；阶段名已抽查与 ImportStage/ExportStage 一致，其余阶段改动前核对代码 |
| 032 | [`architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) 第 14 张（L456） | flowchart | 743×448 | B6 | P3 | 流程/阶段图未区分角色 | 按图例着色；阶段名已抽查与 ImportStage/ExportStage 一致，其余阶段改动前核对代码 |
| 044 | [`architecture/worker-plugins.md`](../architecture/worker-plugins.md) 第 1 张（L122） | flowchart | 1229×503 | B6 | P3 | 流程/阶段图未区分角色 | 按图例着色；阶段名已抽查与 ImportStage/ExportStage 一致，其余阶段改动前核对代码 |
| 045 | [`architecture/workflow-dependency-guide.md`](../architecture/workflow-dependency-guide.md) 第 1 张（L16） | flowchart | 1583×294 | B6 | P3 | 流程/阶段图未区分角色 | 按图例着色；阶段名已抽查与 ImportStage/ExportStage 一致，其余阶段改动前核对代码 |
| 049 | [`architecture/workflow-dependency-guide.md`](../architecture/workflow-dependency-guide.md) 第 5 张（L388） | flowchart | 916×70 | B6 | P3 | 流程/阶段图未区分角色 | 按图例着色；阶段名已抽查与 ImportStage/ExportStage 一致，其余阶段改动前核对代码 |
| 050 | [`architecture/workflow-dependency-guide.md`](../architecture/workflow-dependency-guide.md) 第 6 张（L398） | flowchart | 901×162 | B6 | P3 | 流程/阶段图未区分角色 | 按图例着色；阶段名已抽查与 ImportStage/ExportStage 一致，其余阶段改动前核对代码 |
| 059 | [`design/file-pipeline-design.md`](../design/file-pipeline-design.md) 第 1 张（L22） | flowchart | 989×681 | B7 | P2 | 文件链路统一视图/执行架构/运行闭环，与系统流程章节有重叠 | 核对与 system-flow §2 的分工，重复处改为引用；其余按图例着色 |
| 063 | [`design/file-pipeline-design.md`](../design/file-pipeline-design.md) 第 5 张（L815） | flowchart | 819×754 | B7 | P2 | 文件链路统一视图/执行架构/运行闭环，与系统流程章节有重叠 | 核对与 system-flow §2 的分工，重复处改为引用；其余按图例着色 |
| 066 | [`design/file-pipeline-design.md`](../design/file-pipeline-design.md) 第 8 张（L1069） | flowchart | 665×956 | B7 | P2 | 文件链路统一视图/执行架构/运行闭环，与系统流程章节有重叠 | 核对与 system-flow §2 的分工，重复处改为引用；其余按图例着色 |
| 075 | [`design/time-date-semantics.md`](../design/time-date-semantics.md) 第 1 张（L31） | flowchart | 610×1110 | B7 | P2 | 时间语义图细高（1110、1406px） | 改横向或拆段，保留时区优先级语义 |
| 077 | [`design/time-date-semantics.md`](../design/time-date-semantics.md) 第 3 张（L76） | flowchart | 702×1406 | B7 | P2 | 时间语义图细高（1110、1406px） | 改横向或拆段，保留时区优先级语义 |
| 054 | [`design/batch-day-design.md`](../design/batch-day-design.md) 第 1 张（L98） | flowchart | 707×646 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 055 | [`design/database-schema-guide.md`](../design/database-schema-guide.md) 第 1 张（L18） | flowchart | 641×516 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 056 | [`design/database-schema-guide.md`](../design/database-schema-guide.md) 第 2 张（L50） | flowchart | 782×772 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 057 | [`design/database-schema-guide.md`](../design/database-schema-guide.md) 第 3 张（L73） | flowchart | 747×882 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 060 | [`design/file-pipeline-design.md`](../design/file-pipeline-design.md) 第 2 张（L149） | flowchart | 1286×156 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 061 | [`design/file-pipeline-design.md`](../design/file-pipeline-design.md) 第 3 张（L487） | flowchart | 830×70 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 062 | [`design/file-pipeline-design.md`](../design/file-pipeline-design.md) 第 4 张（L759） | flowchart | 1268×287 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 065 | [`design/file-pipeline-design.md`](../design/file-pipeline-design.md) 第 7 张（L1007） | flowchart | 674×322 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 067 | [`design/logging-architecture.md`](../design/logging-architecture.md) 第 1 张（L11） | flowchart | 919×802 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 068 | [`design/logging-architecture.md`](../design/logging-architecture.md) 第 2 张（L94） | flowchart | 879×168 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 069 | [`design/logging-architecture.md`](../design/logging-architecture.md) 第 3 张（L186） | flowchart | 532×706 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 070 | [`design/partition-idempotency-decision.md`](../design/partition-idempotency-decision.md) 第 1 张（L5） | flowchart | 1311×294 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 074 | [`design/task-spi-design.md`](../design/task-spi-design.md) 第 1 张（L43） | flowchart | 656×574 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 076 | [`design/time-date-semantics.md`](../design/time-date-semantics.md) 第 2 张（L54） | flowchart | 1262×239 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 078 | [`design/time-date-semantics.md`](../design/time-date-semantics.md) 第 4 张（L99） | flowchart | 697×242 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 079 | [`design/worker-deployment-models.md`](../design/worker-deployment-models.md) 第 1 张（L66） | flowchart | 1257×180 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 080 | [`design/worker-deployment-models.md`](../design/worker-deployment-models.md) 第 2 张（L131） | flowchart | 367×754 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 081 | [`design/worker-deployment-models.md`](../design/worker-deployment-models.md) 第 3 张（L166） | flowchart | 847×853 | B7 | P3 | 设计图未区分角色 | 按图例着色；数据库/schema 图存储节点用圆柱 |
| 048 | [`architecture/workflow-dependency-guide.md`](../architecture/workflow-dependency-guide.md) 第 4 张（L141） | erDiagram | 480×1210 | B8 | P2 | 运行态对象关系 ER 图细高（480×1210） | 评估 LR 方向或拆分；关系核对迁移脚本 |
| 013 | [`architecture/adr/ADR-036-sdk-task-handler-templates.md`](../architecture/adr/ADR-036-sdk-task-handler-templates.md) 第 1 张（L46） | classDiagram | 1344×392 | B8 | P3 | 类图 | 保持，核对类名仍存在；不着色 |
| 014 | [`architecture/adr/ADR-037-sdk-checkpoint-resume-and-reliable-commit.md`](../architecture/adr/ADR-037-sdk-checkpoint-resume-and-reliable-commit.md) 第 1 张（L155） | classDiagram | 740×770 | B8 | P3 | 类图 | 保持，核对类名仍存在；不着色 |
| 018 | [`architecture/outbox-architecture.md`](../architecture/outbox-architecture.md) 第 1 张（L13） | erDiagram | 1249×801 | B8 | P3 | ER 图 | ER 图不着色；仅检查字段/关系与迁移一致 |
| 046 | [`architecture/workflow-dependency-guide.md`](../architecture/workflow-dependency-guide.md) 第 2 张（L53） | erDiagram | 1288×988 | B8 | P3 | ER 图 | ER 图不着色；仅检查字段/关系与迁移一致 |
| 051 | [`coding-conventions.md`](../coding-conventions.md) 第 1 张（L288） | classDiagram | 381×258 | B8 | P3 | 类图 | 保持，核对类名仍存在；不着色 |
| 052 | [`coding-conventions.md`](../coding-conventions.md) 第 2 张（L378） | classDiagram | 733×234 | B8 | P3 | 类图 | 保持，核对类名仍存在；不着色 |
| 087 | [`runbook/autoscaling-strategy.md`](../runbook/autoscaling-strategy.md) 第 1 张（L23） | flowchart | 797×94 | B9 | P3 | runbook 流程图未区分角色 | 按图例着色；104/093 细高可评估横向 |
| 088 | [`runbook/autoscaling-strategy.md`](../runbook/autoscaling-strategy.md) 第 2 张（L33） | flowchart | 1171×70 | B9 | P3 | runbook 流程图未区分角色 | 按图例着色；104/093 细高可评估横向 |
| 089 | [`runbook/autoscaling-strategy.md`](../runbook/autoscaling-strategy.md) 第 3 张（L48） | flowchart | 447×376 | B9 | P3 | runbook 流程图未区分角色 | 按图例着色；104/093 细高可评估横向 |
| 090 | [`runbook/autoscaling-strategy.md`](../runbook/autoscaling-strategy.md) 第 4 张（L73） | flowchart | 463×448 | B9 | P3 | runbook 流程图未区分角色 | 按图例着色；104/093 细高可评估横向 |
| 091 | [`runbook/autoscaling-strategy.md`](../runbook/autoscaling-strategy.md) 第 5 张（L105） | flowchart | 1166×156 | B9 | P3 | runbook 流程图未区分角色 | 按图例着色；104/093 细高可评估横向 |
| 092 | [`runbook/batch-day-gate-howto.md`](../runbook/batch-day-gate-howto.md) 第 1 张（L60） | flowchart | 719×826 | B9 | P3 | runbook 流程图未区分角色 | 按图例着色；104/093 细高可评估横向 |
| 093 | [`runbook/biz-tenant-routing.md`](../runbook/biz-tenant-routing.md) 第 1 张（L38） | flowchart | 583×827 | B9 | P3 | runbook 流程图未区分角色 | 按图例着色；104/093 细高可评估横向 |
| 094 | [`runbook/biz-tenant-routing.md`](../runbook/biz-tenant-routing.md) 第 2 张（L145） | flowchart | 1033×94 | B9 | P3 | runbook 流程图未区分角色 | 按图例着色；104/093 细高可评估横向 |
| 100 | [`runbook/first-tenant-config-quickstart.md`](../runbook/first-tenant-config-quickstart.md) 第 1 张（L9） | flowchart | 500×472 | B9 | P3 | runbook 流程图未区分角色 | 按图例着色；104/093 细高可评估横向 |
| 104 | [`runbook/sdk-release.md`](../runbook/sdk-release.md) 第 1 张（L18） | flowchart | 454×798 | B9 | P3 | runbook 流程图未区分角色 | 按图例着色；104/093 细高可评估横向 |
| 109 | [`sdk/wire-protocol.md`](../sdk/wire-protocol.md) 第 1 张（L12） | flowchart | 1247×416 | B9 | P3 | runbook 流程图未区分角色 | 按图例着色；104/093 细高可评估横向 |
| 002 | [`architecture/adr/ADR-002-transactional-outbox.md`](../architecture/adr/ADR-002-transactional-outbox.md) 第 1 张（L84） | stateDiagram-v2 | 310×550 | B10 | P3 | ADR 状态图 | ADR 为决策记录，仅可选按图例着色，不改迁移内容 |
| 003 | [`architecture/adr/ADR-011-idempotency-boundary-alignment.md`](../architecture/adr/ADR-011-idempotency-boundary-alignment.md) 第 1 张（L69） | flowchart | 622×622 | B10 | P3 | ADR 流程图 | 决策记录，默认不改；仅在 ADR 仍为现行入口时可选着色 |
| 004 | [`architecture/adr/ADR-017-result-version-model.md`](../architecture/adr/ADR-017-result-version-model.md) 第 1 张（L63） | flowchart | 881×156 | B10 | P3 | ADR 用 flowchart 画版本状态 | 仅可选着色；如需改 stateDiagram 先确认 ADR 仍为现行口径 |
| 005 | [`architecture/adr/ADR-018-cross-batch-day-dag-dependency.md`](../architecture/adr/ADR-018-cross-batch-day-dag-dependency.md) 第 1 张（L71） | flowchart | 590×628 | B10 | P3 | ADR 流程图 | 决策记录，默认不改；仅在 ADR 仍为现行入口时可选着色 |
| 006 | [`architecture/adr/ADR-018-cross-batch-day-dag-dependency.md`](../architecture/adr/ADR-018-cross-batch-day-dag-dependency.md) 第 2 张（L103） | stateDiagram-v2 | 407×260 | B10 | P3 | ADR 状态图 | ADR 为决策记录，仅可选按图例着色，不改迁移内容 |
| 008 | [`architecture/adr/ADR-020-batch-day-replay.md`](../architecture/adr/ADR-020-batch-day-replay.md) 第 1 张（L113） | stateDiagram-v2 | 454×284 | B10 | P3 | ADR 状态图 | ADR 为决策记录，仅可选按图例着色，不改迁移内容 |
| 009 | [`architecture/adr/ADR-021-data-quality-reconciliation.md`](../architecture/adr/ADR-021-data-quality-reconciliation.md) 第 1 张（L87） | flowchart | 466×676 | B10 | P3 | ADR 流程图 | 决策记录，默认不改；仅在 ADR 仍为现行入口时可选着色 |
| 010 | [`architecture/adr/ADR-024-archive-tiering.md`](../architecture/adr/ADR-024-archive-tiering.md) 第 1 张（L83） | flowchart | 276×730 | B10 | P3 | ADR 流程图 | 决策记录，默认不改；仅在 ADR 仍为现行入口时可选着色 |
| 011 | [`architecture/adr/ADR-025-workflow-static-validator.md`](../architecture/adr/ADR-025-workflow-static-validator.md) 第 1 张（L63） | flowchart | 426×496 | B10 | P3 | ADR 流程图 | 决策记录，默认不改；仅在 ADR 仍为现行入口时可选着色 |
| 012 | [`architecture/adr/ADR-027-resource-affinity.md`](../architecture/adr/ADR-027-resource-affinity.md) 第 1 张（L145） | flowchart | 276×706 | B10 | P3 | ADR 流程图 | 决策记录，默认不改；仅在 ADR 仍为现行入口时可选着色 |
| 015 | [`architecture/adr/ADR-046-file-bundle-aggregation.md`](../architecture/adr/ADR-046-file-bundle-aggregation.md) 第 1 张（L76） | flowchart | 943×521 | B10 | P3 | ADR 流程图 | 决策记录，默认不改；仅在 ADR 仍为现行入口时可选着色 |
| 016 | [`architecture/adr/ADR-046-file-bundle-aggregation.md`](../architecture/adr/ADR-046-file-bundle-aggregation.md) 第 2 张（L258） | flowchart | 776×1901 | B10 | P3 | ADR-046 REPORT 微批恢复图高 1901px | 决策记录默认不改；若该流程已沉淀到现行 runbook，应在 runbook 重画而非改 ADR |
| 083 | [`plans/ai-assistant-contextual-experience-and-cost-governance-2026-09-29.md`](../plans/ai-assistant-contextual-experience-and-cost-governance-2026-09-29.md) 第 1 张（L69） | flowchart | 325×1046 | B10 | P3 | 阶段计划内的图 | 阶段方案保留基线原貌，不改 |
| 084 | [`plans/console-usage-statistics-plan-2026-09-29.md`](../plans/console-usage-statistics-plan-2026-09-29.md) 第 1 张（L48） | flowchart | 1593×156 | B10 | P3 | 阶段计划内的图 | 阶段方案保留基线原貌，不改 |
| 085 | [`plans/ha-deployment-plan-2026-06-13.md`](../plans/ha-deployment-plan-2026-06-13.md) 第 1 张（L36） | flowchart | 1584×670 | B10 | P3 | 阶段计划内的图 | 阶段方案保留基线原貌，不改 |
| 086 | [`plans/workflow-unified-execution-and-system-pipeline-plan-2026-10-14.md`](../plans/workflow-unified-execution-and-system-pipeline-plan-2026-10-14.md) 第 1 张（L24） | flowchart | 703×740 | B10 | P3 | 阶段计划内的图 | 阶段方案保留基线原貌，不改 |
