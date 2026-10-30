# 后端全量规约治理覆盖与历史方法路由

## 适用范围

用户要求“后端全部规约、全工程治理、存量复扫、按历史清单不要遗漏”时读取本页，由 `project-engineering-review` 管理后端工程与规约范围，代码 Bug 使用 `code-review-and-gates` 的 Bug 专项模式单独调查。普通 PR 仍只审实际 diff 和必要调用方，不因本页扩大范围。

当前规则以[工程基线](../../../../docs/agent-baseline.md)、[编码规约](../../../../docs/coding-conventions.md)、领域契约和有效 ADR 为准。当前按用户最新编号 1–24 逐项承接，重复的“3”分别记为 3a、3b，共 25 个工作包；第19–24项独立登记。前19个工作包可追溯[原始治理台账](../../../../docs/plans/development-worktree-governance-2026-10-24.md)，历史完成数量、状态和测试结果不继承。补充项承接规范中其他章节及历史深扫反例。

## 使用方法

1. 固定实际工作树、分支、HEAD、比较基线及 tracked/untracked 源码范围；区分目标分支累计变更、本地改动和明确排除。全量任务不得只看新增行或登记类型；同构入口按模块/调用链覆盖。
2. 对每个工作包记录当前来源、扫描/人工范围、代码证据、合法保留项、未验证项和关闭条件。用“已核对”“不适用及原因”“未验证及缺项”逐项回答，不能留空；修复状态与验证层级分别记录。
3. 从现行守卫和历史报告取得候选，再核对调用方及运行机制。门禁没有命中只证明其输入范围；AST 解析、方法词法不变和历史 PASS 分别保留证据边界。
4. 按用户约束执行必要验证。实体/构造重排检查生成代码、映射和序列化风险，事务/SQL/并发按需真实依赖验证；用户限制只做语义核对时继续整改已确认项并保留运行验证缺口。
5. 历史方法按“触发条件→调查步骤→合法反例→验证方式→当前条款”提炼；不能把一个报告只标为“工程审查”就算其中每个方法已承接，也不能把历史建议升级为现行禁令。

## 当前 25 个工作包（用户编号 1–24）

| 编号与范围 | 必查动作及容易误判的边界 | 来源与专项路由 |
|---|---|---|
| 1 数据库表与 Flyway | 从表职责、写入者和真实消费者核对必要性、键/约束/RLS、索引、分区、归档镜像和注释；迁移顺序、checksum、空库与升级路径分别判断。删表、合并表和索引优化不能只凭引用搜索。 | [数据库逐表审查](../../../../docs/audit/database-table-necessity-review-2026-10-24.md)、[数据库与类注释](../../../../docs/audit/database-and-class-comment-audit-2026-08-21.md)；`database-migration-safety`、`sql-query-performance` |
| 2 实体、Lombok、record、Builder | 按表行/请求/命令/投影/值对象选择形态；检查重复访问器、equals/hashCode、无参/兼容/canonical 构造、Builder 默认值、防御性复制及 Jackson/MyBatis 映射。派生/接口桥接访问器有行为，不能机械删；record Entity 或可变 Entity 不因形式统一互换。 | 编码规约 §8–9、基线 §11.2；[实体与构造历史](../../../../docs/audit/entity-and-constructor-governance-2026-10-24.md)；`code-review-and-gates` |
| 3a Mapper 参数与 SQL | Mapper 公共方法达到5个参数须封装单一具名 Query/Param/Entity，具体形态按职责选择；核对重复/nullable 条件、参数对象和 XML 绑定；区分状态写入、共享生命周期分类和专项筛选。检查默认值所有者、CAS、租户谓词、返回映射及真实查询代价；不能为去字面量合并不同口径，也不能把原子 SQL 改成 Java 先读后写。 | 编码规约 §1、§9.3；[SQL/Java 审计](../../../../docs/audit/sql-java-responsibility-audit-2026-09-08.md)、[SQL 方法](../../sql-query-performance/references/query-semantics.md)；`sql-query-performance`、`backend-testing-governance` |
| 3b Service 与设计模式 | 逐个用例看校验/编排/持久化职责、常量所有权、重复策略和薄转发价值；按变化原因评估 Strategy/Template/Facade，不用类行数决定拆分。保留有事务、协议或兼容职责的边界，识别没有语义价值的包装。 | 编码规约 §4、§22、基线 §11；[模式审查](../../../../docs/audit/design-pattern-review-2026-10-21.md)；`project-engineering-review`、`code-review-and-gates` |
| 4 Controller、REST 与 Service 边界 | 沿 Controller→用例→持久化检查 DTO、校验/权限/租户、HTTP/错误码、固定响应、软/物理删除及默认过滤；内部协议也核对兼容性，不机械套 Console envelope，不泄露 Entity。 | 编码规约 §6–8、§11；`frontend-backend-contract`、`module-config-boundaries` |
| 5 职责边界、事务与资源 | 从模块/领域/数据写入者与资源拥有者核对跨层责任、委托及依赖方向，画出实际代理调用、事务/提交点、RLS/MDC/路由上下文和外部副作用；核对锁 owner/fence、释放顺序、池总量、队列边界及失败/取消/停机关闭。只读查询不一律加事务，daemon 不等于任务安全退出。 | 编码规约 §10、§11.4；[资源审计](../../../../docs/audit/resource-lock-pool-cache-transaction-audit-2026-10-01.md)、[事务方法](../../worker-pipeline-review/references/historical-methods.md)；`worker-pipeline-review`、`performance-validation` |
| 6 注解、配置类与日志异常形式 | 核对构造器注入、Lombok 重复方法、配置类 proxy 设置和类型安全配置绑定；按位置判断 `@Autowired`/`@Value`，不能文本命中即违规。按日志架构判定 `@Slf4j`、具名 logger、堆栈与安全摘要例外。 | 编码规约 §3、§12–13；`configuration-governance`、`observability-governance` |
| 7 测试固定值、SQL、Shell/E2E | 区分生产事实源、场景专属数据与独立契约预期；按资产所有权分离 SQL 与 Shell。E2E 数据准备/回读用真实测试库 JdbcTemplate，主动作走业务入口；不靠直接改状态伪造成功。 | 编码规约 §4.5、§14.8；[测试审查](../../../../docs/audit/test-code-conventions-review-2026-10-25.md)；`backend-testing-governance`、`script-sql-governance` |
| 8 多行文本、宽构造、多 null | 固定结构文本按 text block/序列化器治理，保留字节、末尾换行、转义与签名语义；宽构造逐字段映射，null 分清可选数据和业务模式。短链、短工厂、协议兼容和 fixture 依其例外保留，Builder 不能开放非法状态。 | 编码规约 §0.2、§1.1、§1.6、§8；[构造/Map 后续](../../../../docs/audit/model-map-and-constructor-followup-2026-10-24.md)；`code-review-and-gates` |
| 9 Shell 兼容与配置分离 | 检查支持的 Shell/OS、运行时依赖、引用/转义、管道退出码、信号清理和幂等；公共地址/凭据/default 来源与场景参数分开，动态 SQL 文件引用及 fallback 追真实实现。 | 编码规约 §4.5、§19.5；`script-sql-governance`、`configuration-governance` |
| 10 公共测试能力 | 复核 Mock 初始化/strictness、AssertJ、中文 DisplayName、fixture、共享 profile/test scope、并行隔离和失败清理；只有稳定且真实复用的责任才抽公共能力，不建立万能基类。 | 编码规约 §14；`backend-testing-governance`、`ci-governance` |
| 11 专业中文注释 | Java/Shell/SQL 说明使用基线格式，重点说明 WHY、不变量和恢复边界；核对业务根表及关键字段语义注释。保留标识、机器指令、许可证和可复制代码，不把全部英文文本都当说明注释。 | 编码规约 §19；[数据库与类注释历史](../../../../docs/audit/database-and-class-comment-audit-2026-08-21.md)；`documentation-governance`、`script-sql-governance` |
| 12 全部受影响文档 | 从实际变化映射架构、模型/字典、API/SDK、配置、Runbook、计划/TODO、测试/脚本入口和 Changelog；维护原权威资产，历史原文保留日期/基线。不能用新增汇总报告替代实际受影响文档。 | [文档路由](../../../../docs/standards/document-routing-guide.md)；`documentation-governance` |
| 13 固定对象与 Map | 从消费者核实键集合是否固定；固定契约/投影用职责明确的 DTO/record 和显式映射，动态扩展/聚合/协议原文保留 Map。迁移时复核 null/缺失/未知键、数字类型、顺序与序列化，不能丢掉原严格对账语义。 | 编码规约 §1.2、§8、§9.3；[实体与审计证据历史](../../../../docs/audit/entity-and-constructor-governance-2026-10-24.md)；`code-review-and-gates`、`frontend-backend-contract` |
| 14 运行时导入配置 | 沿 preview→apply/审批→发布→缓存失效→消费者核对多实例 revision、任务冻结快照及密钥轮换/撤销；区分新任务与在途任务。HTTP 成功和目标 receipt 不能证明全部任务切换。 | [导入影响审计](../../../../docs/audit/runtime-config-import-impact-2026-10-24.md)、[配置生命周期](../../configuration-governance/references/lifecycle-methods.md)；`configuration-governance` |
| 15 日常警告与抑制 | 区分预期重试/关闭与真实故障，核对频次、限流、异常摘要、降级和恢复观测；suppression 要有范围/理由/退出条件。不用吞异常、调低级别或扩大白名单替代根因修复。 | 编码规约 §12；[抑制项登记](../../../../docs/standards/java-suppression-registry.md)；`observability-governance`、`ci-governance` |
| 16 复合判断 | 按业务准入/状态分类判断是否需要具名 boolean/谓词；保留短路、模式变量作用域、类型收窄、求值次数和异常时点。简单 guard 不为数量归零拆分。 | 编码规约 §1.5；[复合判断历史](../../../../docs/audit/compound-condition-readability-2026-10-24.md)；`code-review-and-gates` |
| 17 嵌套方法与构造 | 检查所有方法/构造实参、返回表达式和测试断言，提取真实有阅读收益的中间值；保留简单 getter、清晰链式/声明式写法。查询不得移出轮询、事务、异常或惰性回调。 | 编码规约 §1.1、§1.4、§14.8；[嵌套治理](../../../../docs/audit/nested-callsite-closure-2026-10-24.md)、[语义方法](../../project-engineering-review/references/historical-methods.md)；`code-review-and-gates`、`backend-testing-governance` |
| 18 stdout、异常与 UUID | 生产输出按日志入口治理，CLI/benchmark/测试诊断保留例外；业务错误码、双语 key/args、持久化三元组和读时渲染沿链核对。区分主键、请求幂等键、租约身份与测试唯一值；同一请求重试不能重新随机生成业务身份，也不按 UUID 文本命中统一替换。 | 编码规约 §5、§12；[i18n](../../../../docs/design/i18n.md)、[SDK 身份方法](../../sdk-contract-governance/references/historical-methods.md)；`observability-governance`、`sdk-contract-governance` |

| 19 if 内可独立命名的子判断 | 除整体长条件外，逐个识别具有独立业务含义、可复用或来源复杂的子条件，提取具名 boolean/谓词；与第16项分别记录。保留短路与模式变量作用域，不把原来只在右侧执行的查询/校验提前到所有分支。 | 编码规约 §1.5；[条件语义历史](../../../../docs/audit/compound-condition-readability-2026-10-24.md)；`code-review-and-gates` |
| 20 参数与引用对象的显式传递 | 调用前承接复杂返回值，优先传入已经明确的值或职责内聚的对象引用；减少同一调用中的多阶段取值、转换及构造。核对对象身份、可变性、快照、防御性复制和生命周期；不能把多个原独立调用擅自缓存为一次，也不为拆调用增加共享字段或薄包装方法。简单 getter 与清晰链式调用沿原例外保留。 | 编码规约 §1.1、§1.4、§1.6；[语义核对方法](../../project-engineering-review/references/historical-methods.md)；`code-review-and-gates` |
| 21 测试健壮性与统一抽取 | 核对时间/ID/随机数据、排序、异步等待、共享状态、并行隔离及失败清理；断言外部行为和独立预期。统一抽取稳定的准备/回读/清理/请求能力，保留场景含义和关键断言；不靠固定 sleep、无限重试或万能基类掩盖不稳定。 | 编码规约 §14；`backend-testing-governance` |
| 22 国际化 key 与环境切换 | 普通业务测试优先核对稳定 ResultCode/errorKey/args，翻译专项独立验证中英文、占位符与 fallback；不能调用同一 resolver 生成全部预期。测试和脚本核对 profile、Locale、时区、动态端口、服务地址、数据库与凭据来源，覆盖本地/容器/CI 适用入口并恢复上下文。 | [i18n 规范](../../../../docs/design/i18n.md)、编码规约 §14.8；`backend-testing-governance`、`script-sql-governance`、`configuration-governance` |
| 23 配置、变量、key、枚举与常量 | 从重复语义和所有权确定提取位置；固定结构用对象、有限域用既有枚举、稳定重复键用所属常量，动态边界保留 Map。key 的名称/大小写/命名空间/序列化形式一致，兼容别名与已有持久化/协议消费者先核实；独立契约预期保留，不为统一字符串建全局常量袋。 | 编码规约 §1.2、§9.3、§13、§18；[有限域与常量计划](../../../../docs/plans/typed-contract-enum-constant-governance-plan-2026-10-07.md)；`configuration-governance`、`code-review-and-gates` |
| 24 数据结构、策略与工厂 | 按顺序/重复/成员查找/键索引/队列/有限枚举/并发需求选 List、Set、Map、Deque 等结构，核对 null、equals/hashCode、可变性和遍历顺序。真实可替换规则再用策略，合法构造/默认值集中再用工厂，稳定流程变化步骤再用模板；复用既有能力，不按类名或分支数量机械加接口。 | 编码规约 §4、§22、基线 §11；[设计模式历史](../../../../docs/audit/design-pattern-review-2026-10-21.md)；`code-review-and-gates`、`project-engineering-review` |

## 规范与历史深扫的补充覆盖

| 补充项 | 必查动作与边界 | 来源与专项路由 |
|---|---|---|
| 补1 FQN 与符号绑定 | 类型及注解用 import；同名类/包、静态重载和类型推断核对真实绑定，解析成功不等于编译成功。 | 编码规约 §2；[历史语义方法](../../project-engineering-review/references/historical-methods.md#read-1-机械整理的语义复核) |
| 补2 命名、日期时间、字符集、数值 | 核对职责包/后缀、Clock/时区/DST/业务日、默认编码、外部编码归一、大数精度及窄化；不能为了统一格式改变协议字节或时间基准。 | 编码规约 §15、§20；[数值精度历史](../../../../docs/audit/backend-deep-scan-2026-06-19.md) |
| 补3 状态与模块所有权 | 按 Trigger/Orchestrator/Worker、定义态/运行态确认写入者、依赖方向、字典与状态分类；Job/Workflow、dry-run、批量 claim/report 的逐项语义分别核对。 | 编码规约 §16–18、基线 §6–10；`batch-scheduling-engineering`、`module-config-boundaries` |
| 补4 安全、输入与 API/SDK | 检查数值/分页/载荷边界、tenant/ownership/RLS、参数化/动态 SQL、凭据与文件/网络边界；五语言 SDK 的 wire、版本、transport/重试和关闭分别验证。 | 编码规约 §11；`frontend-backend-contract`、`security-scan-governance`、`sdk-contract-governance` |
| 补5 指标、事件与可用性 | 核对指标名称/标签基数、日志与业务审计、触发/持续/恢复/最终收件，以及监控系统自身不可用；不能以 Counter 窗口过期证明业务恢复。 | 编码规约 §11.5–11.6；`observability-governance` |
| 补6 治理控制有效性 | 从规则到文件枚举/新增模块、workflow 路由、命令、真实报告与汇总逐段核对；分别记录机器阻断、候选报告和人工语义。SKIP、旧基线、历史 PASS、关键词路由与格式验证都不代表全覆盖。 | [持续守护总账](../../../../docs/standards/convention-drift-guard-index.md)、[资料提炼边界](../../../../docs/audit/document-script-skill-review-2026-10-25.md)；`ci-governance` |

## 历史来源与验证边界

上述链接用于找到具体决策、反例和验证方法；其他历史来源仍通过[审计索引](../../../../docs/audit/README.md)、[逐篇映射](../../../../docs/audit/archive-experience-skill-extraction-2026-10-25.csv)及[逐文件盘点](../../../../docs/audit/document-script-skill-coverage-2026-10-25.csv)发现。映射数量不是方法已逐条承接的证明。

例如旧报告曾把 Entity 的 Builder、所有查询缺 readOnly、Outbox 重发直接列为风险；当前应按实体构造契约、实际连接/代理边界及至少一次投递的不变量重新判定。历史原文保留，撤回/合法反例一并读取，不继承旧禁令、风险等级或通过状态。
