# 固定契约、有限域与常量治理计划（2026-10-07）

> 状态：LocalImplemented（本地实现与定向验收完成；已获前后端 PR 提交授权，CI 验收待完成）
>
> 目标：治理固定结构使用 `Map` / `Object`、有限状态使用裸字符串、同一语义字面量重复散落三类问题，并建立低误报的增量门禁。
>
> 边界：不改变业务状态机、事务、锁、幂等、重试和错误语义；不强制 DTO 化动态 JSON、插件参数或用户 metadata；不以消除全部 Sonar `S1192` 为目标。
>
> 实施证据：[本地治理验收记录](../audit/typed-contract-governance-verification-2026-10-07.md)。本地通过不等于 Full Gate、sim 或生产验收通过。

## 1. 背景与结论

现有编码规约已经明确：固定 API/Application 契约使用 DTO，核心有限域优先使用公共枚举，重复且承载同一语义的字面量应收敛为具名常量。当前仓库仍有少量存量实现没有完成闭环，且现有 `RepositoryMapReturnConventionTest` 只保护 Repository 返回值，不能防止 Controller 和 Application Service 回退为固定 `Map` / `Object`。

本轮采用四批治理：

1. 固定契约类型化：先处理公开 API，再处理内部 API 和 Application Service。
2. 有限域枚举化：只替换已经有权威枚举、且语义完全一致的状态和策略值。
3. 重复常量收敛：优先复用已有常量，再提取稳定协议键；拒绝无意义常量化。
4. 增量门禁：精准阻断新增回退，存量通过明确基线逐步归零，不建立高误报的全局字符串禁令。

这是首轮确认清单，不是“全仓所有 Map 必须消失”的计划。持续普查入口为[当前 Java 可读性清单](../analysis/java-readability-inventory-2026-08-12.md)：本地治理后仍有 54 个 public Map 词法候选，含 JSON 工具、插件参数、动态聚合及固定基础设施投影，不能把该数量等同于 54 个待修缺陷。

后续逐项判断固定基础设施投影、SDK 传输组装和请求载荷；确认字段、消费点和所有者后再进入下一批。通用 JSON/配置转换、开放行数据及插件扩展保留 Map；固定投影应在所属模块类型化，不借本轮增加公共模块依赖或统一万能 DTO。

## 2. 治理判定

### 2.1 必须治理

| 类型 | 判定条件 | 目标形态 |
|---|---|---|
| 固定响应 `Map` | 字段集合固定、调用方按固定 key 读取、需要进入 OpenAPI 或跨模块传输 | 独立 `record` / Response DTO |
| 固定请求 `Map` | 字段集合固定且有校验、授权或持久化语义 | Request / Command DTO |
| `Object` 契约 | 同一端点按分支返回不同固定类型，调用方必须运行时判断 | 固定 DTO；已有多形态 wire 使用封闭类型联合与 OpenAPI `oneOf`，不强行改变 JSON 形态 |
| 有限域裸字符串 | 已有公共枚举，字符串与枚举 code 的领域语义一致 | 枚举常量或 `.code()` |
| 重复协议键 | 同一类或同一协议内重复，拼写漂移会改变行为 | 复用已有常量或提取具名常量 |
| 重复错误码/状态码 | 被日志、响应、告警或测试作为稳定标识消费 | 公共错误码、枚举或局部常量 |

### 2.2 明确保留

以下内容不因本轮治理机械改写：

- 插件参数、用户自定义 metadata、JSONB 原始载荷、外部扩展字段。
- 状态名、租户自定义字段或业务维度作为 key 的动态聚合。
- 管理端动态参数字典、测试清理结果 `table -> count` 等真实动态 Map。
- SQL 片段、Excel 声明式标题/说明、日志模板、只出现一次且没有协议语义的文本。
- 测试 fixture 中用于直接表达业务场景的 Map 和 inline 构造。
- 外部系统兼容转换入口；允许保留 `from(Map)`，但生产核心链路不得继续传播原始 Map。

### 2.3 禁止的过度治理

- 不为每一个字符串建立常量。
- 不因字符串形似枚举 code 就强制替换；必须先证明领域相同。
- 不把开放扩展数据包装成含 `Map<String, Object>` 字段的空壳 DTO，以此伪装类型化。
- 不把内部结构重构与 wire 字段、状态机、事务边界变更混入同一批次。
- 不将 Sonar `java:S1192` 的全部告警直接升级为阻断门禁。

## 3. 已确认治理清单

### 3.1 P0：公开 Console 契约

| 位置 | 当前问题 | 计划 |
|---|---|---|
| `ConsoleJobController.trigger` | `CommonResponse<Object>`，普通触发返回实例号，dry-run 返回另一对象 | 使用 sealed `JobTriggerResult`，普通分支以 `@JsonValue` 保留字符串，dry-run 保留对象；同步 OpenAPI `oneOf`、生成类型与契约测试 |
| `PipelineDefinitionService` | Application Service 返回 `PageResponse<Map<String, Object>>`，Controller 再转换 | Service 直接返回固定列表项 DTO，移除固定 Map 在应用层传播 |
| Console Trigger proxy/list | `List<Object>` + `instanceof Map` 做租户过滤 | 定义 Console 内部传输 DTO，使用类型字段完成过滤和响应组装 |

公开契约必须保持路径、鉴权、业务含义和错误码不变。若 JSON wire 必须调整，必须作为显式契约变化记录，并在同一批完成配对前端适配。

### 3.2 P1：内部固定契约

| 位置 | 当前问题 | 计划 |
|---|---|---|
| Trigger status/management Controller | 固定 `status/tenantId/jobCode` 使用 Map | 增加内部 Response record，Console proxy 使用对应 typed response |
| `LineageEvidenceService` | 固定顶层、coverage 和数据行使用嵌套 Map | 按既有 Console lineage wire 建立 typed projection；只保留证据扩展字段为动态 Map |
| `ImportEventArrivalController` | 固定 `{triggered, reason}` 使用 Map | 增加 `ImportEventArrivalResponse` record |

### 3.3 P1：已有枚举未复用

优先处理以下已确认的同域替换：

- 实例与分区管理：`JobInstanceStatus`、`PartitionStatus`。
- workflow 管理与校验：`WorkflowRunStatus`、`WorkflowNodeRunStatus`、`WorkflowJoinMode`、`SensorType`、`SensorTimeoutAction`。
- outbox 查询与健康检查：`OutboxPublishStatus`。
- Console 租户统计与策略：`PipelineRunStatus`、`AlertSeverity`、`NotificationChannelType`。
- dispatch：`FileChannelType`、`FileReceiptPolicy`。
- import/export：`FileTemplateFormat`。

实施时逐处核对：数据库值、JSON code、展示 label 和内部枚举名不能混用。只允许以 `DictEnum.code()` 参与持久化和 wire 比较，不使用 `name()` 代替稳定 code。

### 3.4 P2：已有常量未复用与稳定键

首批明确候选：

- Java SDK `TaskDispatcher` 的 MDC `tenantId` / `taskId`。
- 租户配置复制服务的 `enabled`。
- 文件治理查询的 `runningStatus`。
- Atomic Shell 执行器的 `command`。
- AI 会话失败状态 `FAILED`。
- Trigger management 的 `tenantId`。

其余候选以当前代码上的 Sonar `java:S1192` 扫描为输入，逐条分成：

1. 复用已有常量。
2. 新增局部/领域常量。
3. 改用现有枚举。
4. 合理保留。

扫描数量只作为候选规模，不作为必须清零的质量指标。

## 4. 分批实施

### 阶段 A：冻结基线与测试保护

**工作项**

- 对上述类建立准确候选清单和合理例外清单。
- 为公开 JSON 响应补充序列化/MockMvc/OpenAPI 契约测试。
- 为内部 Trigger、Lineage、Import event 响应补充字段级测试。
- 记录当前前端调用方和生成类型，禁止仅后端改完即宣称闭环。

**验收**

- 现有 wire 字段、nullability、错误码和权限行为有测试快照。
- 动态 Map 例外均有业务理由，不使用文件级宽泛豁免。

### 阶段 B：固定契约类型化

建议按以下独立提交顺序：

1. Pipeline Definition Application Service 内部类型化。
2. Import event 内部响应类型化。
3. Trigger 内部 API + Console proxy 联动类型化。
4. Lineage evidence 内部投影类型化。
5. Console Job trigger 公开契约 + OpenAPI + 配对前端。

每批必须保持可独立回退。涉及 `/api/console/**` 的批次必须同时修改 `docs/api/console-api.openapi.yaml`、协议文档、前端生成类型、API 调用方和相关测试。

### 阶段 C：有限域枚举化

按领域拆为 instance/workflow、outbox、notification/asset、file/import/export 四批。替换前先核对：

- 数据库 CHECK 和 mapper 返回值。
- `DictEnum.code()` 与历史 wire 值。
- SQL 中状态集合的具体业务口径。
- 是否属于共享生命周期分类，或属于 SLA/失败筛选等专项策略。

MyBatis 状态集合不得为消除字面量而统一参数化。共享生命周期集合应增加 Mapper 测试，对照相应 enum 派生 code；专项业务策略继续保留明确集合和专项测试。

### 阶段 D：重复常量收敛

- 先修“已有常量却重复写字面量”的确定问题。
- 再处理同一协议、同一类内出现多次且有稳定语义的 key/code。
- 默认使用 `private static final`；只有跨模块/跨协议共享且已有明确所有者时才上提公共常量。
- 禁止创建 `Constants` 万能类；常量归属协议、领域对象或使用类。

### 阶段 E：门禁与文档

新增精准增量检查，不直接全仓硬阻断：

1. **固定边界检查**：禁止新增 Controller/Application Service 的固定 `CommonResponse<Object>`、`CommonResponse<Map<String, Object>>`、`CommonResponse<List<Object>>`、`PageResponse<Map<String, Object>>`。
2. **例外注册**：真实动态契约使用精确到类/方法/签名的 allowlist，并写明理由；禁止目录级通配。
3. **枚举字面量检查**：只覆盖已确认的状态字段和既有枚举 code，不做全局字符串猜测。
4. **常量复用检查**：只阻断“同类已有常量仍新增同值字面量”的高置信场景；Sonar `S1192` 继续作为报告和人工复扫输入。
5. **运行位置**：本地/PR 使用 changed-files 增量检查，权威枚举变化时扩展到登记消费者；规则或注册表变化时全量复扫。Full Gate 使用全量基线模式，不要求全仓字符串归零。

新增门禁必须同步：

- `scripts/ci/README.md`
- `docs/audit/convention-drift-guard-index.md`
- 对应 workflow / PR gate
- 脚本自测
- `docs/changelog.md`

## 5. 验证矩阵

| 变更类型 | 最低验证 | 补充验证 |
|---|---|---|
| Application Service 内部 DTO | 编译、受影响模块单测、序列化测试 | 相关 Mapper/Service IT |
| Trigger/Orchestrator 内部 API | 两端模块单测、内部 OpenAPI/契约测试 | Console proxy 集成测试 |
| Console 公开 API | 后端 MockMvc、OpenAPI drift/breaking gate | 前端 codegen check、typecheck、相关单测/E2E |
| Trigger 运维消费点 | 租户角色只消费自身列表；全局角色校验列表行 tenantId 与操作请求 tenantId 一致 | ta/tb 同 jobCode、管理员切换租户、缺失 tenantId、请求失败与降级状态的页面联测 |
| 枚举化 | 受影响单测、Mapper XML 状态集合测试 | 关键状态机 IT/sim，视改动风险选择 |
| 常量收敛 | 编译、单测、Sonar/PMD/Spotless | 无行为变化时不要求全量 E2E |
| CI 门禁 | 脚本正向/负向自测 | PR changed-files 与 Full Gate 基线模式各跑一次 |

全批次最终验收：

- 固定契约候选归零，动态 Map 例外可追踪。
- 已确认的有限域裸字符串归零，且没有改变 SQL 业务口径。
- 已有常量重复字面量归零，高置信新增常量候选闭环。
- OpenAPI 与配对前端生成类型一致。
- Spotless、PMD、相关单元/集成测试及新增门禁通过。
- Full Gate 与真实链路验证结果分开报告，不用静态检查替代运行证据。

## 6. 风险与回退

| 风险 | 控制措施 | 回退方式 |
|---|---|---|
| DTO 导致 JSON 字段/null 行为变化 | 序列化快照 + OpenAPI + 前端 codegen | 单批回退 DTO 提交 |
| enum `name()` 与 code 混淆 | 强制 `.code()`，补 mapper/wire 测试 | 回退对应领域批次 |
| MyBatis record 映射依赖列序 | 显式 `resultMap` + constructor arg | 恢复原 mapper 并保留边界转换 |
| 全局 Trigger 列表与选定租户动作混用 | 行 tenantId、当前租户和操作目标必须在页面联测中核对；类型检查不能证明运维目标正确 | 保留显式租户字段，单独修正消费点，不改变服务端权限范围 |
| 门禁误报动态 Map | 精确 allowlist + 脚本自测 + 增量先行 | 门禁降级为 report-only，不扩大豁免 |
| 一次改动范围过大 | 每批一个契约簇或领域簇，结构和行为分离 | 独立提交逐批回退 |

## 7. 完成定义

本计划只有在以下条件全部满足时才能标记为 Implemented：

1. 清单中的固定契约、枚举和高置信常量候选全部完成或有明确保留理由。
2. 后端契约、OpenAPI、配对前端和测试同步完成。
3. 新门禁具备正向、负向和 allowlist 测试，并进入规定的 CI 路径。
4. 文档索引、守卫总账和 changelog 已更新。
5. 相关 CI 实际通过；未运行的 IT、E2E、sim 或 Full Gate 必须如实标注，不能以“应当通过”代替证据。

## 8. 本地实施状态

| 阶段 | 状态 | 交付 |
|---|---|---|
| A：基线与契约保护 | 已完成 | 8 个精确动态例外、空违规基线；真实 Jackson HTTP 栈、MockMvc、非空列表转换和 PG 归档投影测试 |
| B：固定类型 | 已完成 | Pipeline、Import event、Trigger/Console proxy、Lineage 和 Job trigger 全部类型化；配对前端生成类型和消费点同步 |
| C：有限域 | 已完成 | 31 个确认类范围登记权威枚举；code 值、查询策略和 SQL 状态集合不变 |
| D：高置信常量 | 已完成首批范围 | 已有同类 KEY/PARAM/MDC 值复用；另收敛 stepParams、timezone、message、requiredFileSet、downloadRequiresApproval、errorCode、workerId 等 8 个类内稳定协议键。固定路由常量复用；Sonar S1192 全量仍作为后续人工候选，不是全局阻断清单 |
| E：精准守卫 | 已完成本地 | JCON-1/2/3；19 个正反例；PR/local 增量与 Full Gate 全量入口、规则变化路由、自测、总账和 runbook 已接入 |
| CI / Full Gate / sim | 待 PR 验收 | 按主题提交前后端 PR；取得真实 CI 结果后更新，不据本地通过标记 Implemented |

### 已确认保留项

- `ALL_OF`、`N_OF_` 是现有 workflow DSL，不等同于 `WorkflowJoinMode` 的 `ALL/ANY/N_OF` code；不替换。
- 租户暂停的 `PARTIAL_FAILED` 阻断口径不等于通用 active 状态集合；保留原集合。
- 通知当前允许渠道子集不含 `SLACK`；复用 enum 不扩大产品支持范围。
- `CSV/TSV/FIXEDWIDTH` 是现有格式别名；保持原兼容入口和归一化。
- `CANCEL_REQUESTED` 是操作动作，不伪装成实例生命周期状态。
- 动态治理字典、清理表计数、枚举元数据、插件 effectiveParams 只登记精确例外；测试 fixture 和声明式规格不机械常量化。
- Lineage 只有文件 `metadata_json` 的 JDBC 封装被规范化为动态 JSON 对象；顶层字段、证据行字段、排序、租户条件和热冷回退不变。
- 全仓 Sonar S1192 的普通文案、SQL 片段、样例、指标标签和其他未确认同域候选继续人工判断；本轮不声称全部告警清零。

### 门禁适用限制

JCON 是高置信候选守卫，不是完整 Java 语义分析器：固定边界通过类名/接口和返回类型识别，枚举规则在登记类中匹配权威 code，常量规则只检查规定的 key 消费形式。请求参数、嵌套 DTO 内的固定 Map、未登记的有限域、跨类常量和复杂别名仍需要代码评审及 Sonar 复扫。新增登记范围前先以 report 模式核对候选，不能将零候选表述为全仓所有字符串和 Map 都已治理。
