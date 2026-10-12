# 前后端代码可读性治理审计（2026-10-13）

## 范围与前提

- 审查范围：`batch-console` 与 `file-batch-system` 同名分支相对各自 `main` 的累计差异、前端未提交改动，以及后端生产 Java 的多个 `null` 实参候选。
- 首要约束：只做结构等价改造；保持求值顺序、调用次数、短路、异常时机、副作用、事务边界、SQL 和 HTTP 契约不变。
- 候选不等于缺陷。括号数量、链长度、`null` 数量只用于定位，声明式 UI、短工厂、兼容 DTO、数据库 nullable 行与测试 fixture 不机械整改。

## 原审查发现闭环

| # | 原发现 | 处置 | 状态 |
|---:|---|---|---|
| 1 | `UserRole.vue` 英文环境仍显示中文页面名 | 页面标题统一经 `pageTitleKey()` 派生并由 i18n 解析；补标题 key 和权限树英文断言 | 已治理 |
| 2 | `BatchDayReplayEntryMapper.updateStatus` 8 个位置参数及连续 `null` | 改为 `UpdateBatchDayReplayEntryStatusParam`；必填字段构造期校验；调用侧使用 `running`、`succeededWithResult`、`failedWithResult`、`failedOnSubmission`、`completedRerun` 具名入口 | 已治理 |
| 3 | `settle(reservation, null, null, null)` 隐式表达预留结算模式 | 拆为 `settleMeasured` 与 `settleReserved`；用 `MeasuredUsage` 表达完整用量，删除公共 nullable 模式组合 | 已治理 |
| 4 | 导航分组顺序遗漏只能运行时发现 | 顺序元组派生联合 key，注册表以 `satisfies` 做完整性校验，并补唯一性测试 | 已治理 |
| 5 | 权限树用 `—` 哨兵并重复扁平化/分组，节点类型缺字段 | 改为 `group` / `item` 判别联合，原始权限保留数组/缺失状态，展示层格式化；可见分组直接由导航项派生 | 已治理 |
| 6 | baseline 当前分支与 base 分支解析逻辑漂移 | 提取 `parse_baseline_items(content, path, source)`，两条路径复用同一校验；补 base 非法布尔值回归测试 | 已治理 |

## 补充审查发现闭环

| # | 原发现 | 处置 | 状态 |
|---:|---|---|---|
| 1 | AI `settle` 公共接口仍允许 provider/token 非法空值组合 | 公共入口仅保留 `settleMeasured(Reservation, MeasuredUsage)` 与 `settleReserved(Reservation)`；`MeasuredUsage` 构造期校验 provider 和非负基本类型 token，两个入口分别计算后进入持久化核心 | 已治理 |
| 2 | 重放状态参数 Builder 仍可遗漏必填字段或组合非法状态 | 删除通用 Builder 入口；私有构造器对必填字段 fail-fast，生产调用只能走具名状态工厂；会话期望状态使用不可变非空 `List`，并补公开入口负向测试和工厂字段形态测试 | 已治理 |
| 3 | 权限树是“全部字段可选”的数据袋 | 改为 `kind: 'group' | 'item'` 判别联合，模板按 `kind` 收窄；分组/叶子各自必填字段由 TypeScript 守护 | 已治理 |
| 4 | `UserRole.vue` import 与初始化交错 | 所有 import 集中在 `<script setup>` 顶部，之后按类型、composable/store、computed/watch/方法组织；未引入低精度全仓自动修复 | 已治理 |
| 5 | 页面标题 key 有多套推导 | `pageMeta.ts` 提供 `pageTitleKey(path, explicitPathKey?)`，统一 query/hash 处理；Command Palette、Tabs、移动标题、Header、PageHeader 与导航共同复用 | 已治理 |
| 6 | 导航完整性条件类型晦涩且不防重复 | 顺序 tuple 成为 key union 权威源，注册表 `satisfies` 保证完整性，唯一性测试阻止重复；代码注释说明两层职责 | 已治理 |
| 7 | baseline 解析用诊断标签后缀选择格式 | `parse_baseline_items(content, path, source)` 由 path 判别格式、source 仅用于诊断；当前/base 内容走同一解析与校验，补 TXT/JSON/base 反例 | 已治理 |
| 8 | AI 应用服务测试的全局 `lenient()` 扩张 | 用真实 `ConsoleAiCostService` spy 替代结算默认 lenient stub；成功/失败路径验证 `settleMeasured` / `settleReserved` 的真实分派 | 已治理 |

## 本轮扩展治理

### 方法调用与括号层级

- 前端将重复的选项清洗/去重/排序收敛为 `uniqueDisplayFieldValues`，调用侧先命名业务列表再渲染。
- 日历点击、报表日期键、登录主题恢复与文本附件解码提取语义中间值；有副作用的调用保持原次数和执行先后，仅复用已确认纯函数的日期 key 结果。
- 后端将无明细 DQ 写入、成功审计、无详细错误的 pipeline 失败处理改为具名私有入口；具名入口仍逐值委托原实现。
- 工作流拓扑查询用 builder/具名 query 工厂替代长构造器；每个原有租户解析调用仍在原控制流位置执行，没有合并或缓存潜在有副作用的调用。

### 多个 `null` 实参与合法状态

- `ImportPayload.empty()` 与 `EffectiveTaskConfig.empty()` 隐藏兼容占位对象的整组空字段。
- `JobTaskQuery.ofPartition`、`JobPartitionQuery.ofJobInstance`、`WorkflowRunQuery.ofDefinition`、`WorkflowNodeRunQuery.ofWorkflowRun`、`FileArrivalGroupQuery.ofState` 隐藏无关筛选字段。
- `PositionalArgsConventionTest` 新增生产调用侧约束，阻止已治理 Query 再以两个及以上字面量 `null` 直接位置构造；Query 自身具名工厂内部不误报。
- 保留的多 `null` 仅限静态工厂/重载内部、协议/数据库 nullable 数据对象、JDK/SDK 构造器或明确的兼容边界；调用业务模式不得继续以多个空值选择。

### 字符串与设计模式

- 本次新增/修改内容没有适合改成 Java `"""` 或前端多行模板字符串的固定多行结构；结构化字符串门禁继续负责防止新增拼接退化。
- 标题 key、导航分组 key、状态更新模式和选项清洗已收敛为单一事实源。
- 未为单次局部表达式新增策略/接口；只在存在真实合法状态或重复边界时使用工厂、参数对象、判别联合与纯函数。

### 设计模式与数据结构复核

| 审查面 | 结论与处置 |
|---|---|
| 合法状态 | 前端权限树使用判别联合；AI 实测用量使用值对象并拒绝空白 provider/负 token；回放条目与会话参数仅暴露具名工厂，删除可绕过约束的通用 Builder。 |
| 注册表与顺序 | 导航顺序 tuple 是 key union 的权威来源，`Record` 形态注册表负责完整性，单测负责唯一性；没有再叠加第三方类型工具。 |
| 集合选择 | 权限 path 与选项去重使用 `Set`；渠道适配器等既有按 key 查找继续使用 `Map`；需要稳定顺序的导航和状态 CAS 条件继续使用 `List`，参数对象用 `List.copyOf` 防止构造后被外部修改。 |
| 参数对象 | 只为同一状态更新事务引入参数对象，Mapper XML 仍绑定原字段；没有把无关职责塞入“万能 Context”。Query 的空筛选组合由窄工厂命名。 |
| 工厂/策略 | 使用工厂表达 `running/succeeded/failed/reserved/measured/empty` 等真实模式；单次分支不新增 Strategy、Factory 接口或抽象基类，避免模式套壳。 |
| 数据变换 | 权限树从导航源一次映射，展示哨兵不进入模型；页面标题与查询选项清洗各有单一纯函数，避免多套派生逻辑。 |

结论：本次累计差异中未发现还需要引入 Strategy、State、Visitor 等重量模式的场景；现有问题适合用值对象、具名工厂、判别联合、注册表和集合语义解决。

### 后端生产代码多 `null` 全量候选复核

扫描所有 `src/main/java` 的直接实参后，逐项按调用语义分类，而不是以命中数机械整改：

- 业务模式选择：AI 结算、回放状态、空导入载荷、空有效配置、查询模式、实时全量订阅、全部配额、资源分配保护、无运行窗口等均已改为具名入口或值对象。
- 工厂/重载内部：允许在类内部集中构造 nullable 数据，但业务调用侧必须使用具名入口；回放参数对象的通用 Builder 已删除。
- 数据/协议边界：响应 DTO、事件/消息兼容构造器、数据库 nullable 行、Mapper 可选筛选条件和 JDK/SDK 构造器保留；这里的空值表示协议字段缺失，不选择业务分支。
- 测试 fixture 不纳入生产门禁；已治理 Query 由 `PositionalArgsConventionTest` 阻止业务代码重新出现两个以上字面量 `null` 的直接构造。

因此，“仍能搜到 `null`”不等于漏治；剩余项都有明确边界归类。后续若把协议空值改为领域状态，必须先修改契约、迁移和消费者，不能在可读性治理中顺手改变语义。

## 为什么原门禁没有拦住

原有自动检查覆盖格式、类型、i18n key、Java `var`/配置类、文本块格式、结构化字符串拼接、宽参数白名单和可读性快照，但不能可靠判断：

1. 嵌套调用是否遮住业务含义；
2. 一个 `null` 是数据库缺失、兼容协议，还是在选择业务模式；
3. 提取局部变量是否改变调用次数、求值顺序或异常时机；
4. 工厂/策略/接口是在表达合法状态，还是无价值套壳。

这类判断若按括号或 `null` 数量全量阻断，会误报 builder、Vue render tree、query 工厂、JDK/SDK API 与 fixture。治理采用“高精度自动门禁 + 全量候选清单 + 语义评审”：机械事实自动阻断，语义候选必须在评审清单逐项定性，不以扫描数量直接判失败。

`JAVA_READABILITY` 的稳定检查范围实际是显式局部类型与 Spring `@Configuration(proxyBeanMethods = false)`。脚本输出已增加 `scope=explicit-type,configuration-proxy`，避免把绿色结果误读为完成全仓语义可读性审查；嵌套调用、合法状态和抽象适度性仍按本审计清单人工/AI 复核。

## 持续约束

- 后端权威规则：[`docs/coding-conventions.md` §1.4–1.6](../coding-conventions.md#14-命令与上下文的调用现场) 与 §8.1 Query 工厂。
- 前端权威规则：[`batch-console/docs/engineering/code-readability-governance.md`](../../../batch-console/docs/engineering/code-readability-governance.md)。
- PR 必审：复杂实参/返回表达式、连续空值、可选字段袋、字符串事实源、重复转换、状态组合与新增抽象。
- 自动检查只对可稳定解析且低误报的事实 fail-closed；候选报告不自动改写源码。

## 验证口径

本轮本地验证结果：

- 前端：`vue-tsc`、ESLint、i18n、maintainability、docs 检查通过；导航、页面标题、权限 store、查询选项 4 个 Vitest 文件共 19 项通过。
- 后端：Spotless 通过；Java readability、text-block style、structured-string-concat 三项门禁通过；baseline 解析 6 项 Python 测试通过。
- 后端定向测试：orchestrator 82 项、console 64 项通过；受控构造/位置参数守护 4 项单独复跑通过。
- 后端增量 Sonar：相对 `origin/main` 扫描 54 个 Java 变更文件，变更行 0 个 open issue、0 个待审 hotspot；首次发现的两个私有长构造器问题已通过内部不可变状态明细收敛后复扫归零。
- 两仓工作区及相对各自 `main` 的累计 `git diff --check` 均通过；浏览器 E2E、真实后端验收和远端 CI 未运行，不得描述为通过。
