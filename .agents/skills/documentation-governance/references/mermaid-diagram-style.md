# Mermaid 制图检查表

在审查、重画或批量美化文档 Mermaid 图时加载；本参考只提供执行方法，不构成实施授权，也不另建颜色或语义规则。权威入口：

- 颜色、形状和图例：[架构导航图例](../../../../docs/architecture/README.md#图例)
- 颜色只表达语义的规范：[文档治理](../../../../docs/standards/document-governance.md) §9
- 批次、逐图台账和验收：[图审查与修改计划](../../../../docs/plans/diagram-review-remediation-plan-2026-11-13.md)
- 已完成的代表性图：`README.md` 整体架构、`docs/design/status-state-machines.md` §3.1、`docs/architecture/system-flow-overview.md` §4.1–4.3、`docs/design/time-date-semantics.md`、`docs/runbook/compose-cd-roadmap.md` §11–12

## 改图前

1. 确认图的职责和读者；一张图只保留一个主题，细节放相邻正文。
2. 对照源码或权威文档核对节点、连线、阶段和状态。状态图、阶段图不先核对代码就不改样式；发现语义漂移时单独登记，不借美化顺手改业务语义。
3. 从架构导航图例复制 `classDef` 块，保持颜色、透明度和描边一致；不在图里临时自造颜色。每张图保持自包含，不依赖全局 CSS。
4. 沿用现有图头：
   `%%{init: {"flowchart":{"curve":"linear","nodeSpacing":32,"rankSpacing":48},"sequence":{"mirrorActors":false}}}%%`
5. 只改目标图；同一批次只动计划列出的文件和图示范围。

## 四类图的固定做法

- **flowchart**：节点角色用图例 class；PostgreSQL/Valkey/S3 用圆柱，Kafka 用六边形，外部/可选系统用虚线框；子图只表达分层或信任边界。
- **stateDiagram-v2**：先核对状态集合和迁移来源，再改用 `stateDiagram-v2`；成功、失败、等待、跳过按图例结果类着色，并注明写入方或代码出处。
- **sequenceDiagram**：参与方用 `box` 按入口/控制面/消息/执行面分组，步骤多时加 `autonumber`；避免为了完整而堆成超宽时序。
- **ER / classDiagram**：不着色，只核对字段、关系、类和枚举仍存在；关系以迁移或源码为准。

## 版式规则

- 以文档站正文约 690px 的阅读宽度为目标；普通图避免不必要的超宽或超细高。
- 细高图优先 `TB → LR`、拆分主题或改为 `subgraph` 分组；同一主题的重复小图合并，能和系统流程重叠的改为引用。
- 外部系统连到子图边界，不穿过子图；回连用图例约定的双向虚线，避免成环后读向反转。
- 不为“看起来更丰富”增加图例未定义的颜色、渐变或装饰。

## 批次验收

- 检查 `check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py` 和 `git diff --check`。
- 按图审查计划使用文档站同版本 Mermaid，在 neutral 与 dark 下渲染并人工检查交叉线、标题遮挡、宽图、暗色对比度；必要时实际构建文档站。
- 逐图确认节点与连线语义未变；状态/阶段图保留代码出处。
- 每批更新 `docs/changelog.md`；渲染或静态检查通过不等于业务、部署或目标环境验收。

## 明确不做

- 不给 ER/类图加装饰色；默认不改 ADR 和阶段计划的原貌。
- 不改归档文档，不借重绘改变“当前/目标/规划”属性。
- 不为了消除交叉线改变调用方向、状态迁移、租户/信任边界或业务结果。
- 不复制别处已经过期的色值；图例变化时同步已着色图，而不是在单图里保留旧色。
