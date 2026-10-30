---
name: backend-testing-governance
description: 新增、重构或审查 file-batch-system 的 Java 测试类、共享测试能力、fixture 或测试分层时使用。按项目测试规范选择能证明业务行为的最小测试层级；不代替验收执行或 CI 门禁治理。
---

# 后端测试设计与治理

## 职责边界

- 本技能负责 Java 测试的设计、编写、结构整理、共享 fixture 和测试真实性。
- 测试类规范以 `docs/coding-conventions.md` §14 为准；模块测试入口、真实环境和验收证据见 `docs/testing/README.md`。
- 本技能不负责执行整套本地/真实环境验收；使用 `acceptance-validation`。测试 workflow、门禁分层和 required checks 使用 `ci-governance`。单次 PR 的完整差异审查仍使用 `code-review-and-gates`。

## 设计与分层

1. 先把需求转成外部可观察行为、关键失败条件和独立预期，再选择最小有效层级：纯逻辑用单测；Spring 组装、事务、Mapper SQL、并发和租户隔离按风险用真实依赖集成测试；消息、调度和跨服务行为按风险用集成或 E2E。
2. 复用现有模块模式和 `batch-test-support`。不要为了减少几行重复建立万能基类、Fixture 或工厂；只有多个测试共享稳定准备/清理责任时才抽取，并保持测试数据的场景含义可见。
3. 测试类/方法名称、中文 `@DisplayName`、Mockito strictness、AssertJ、Testcontainers 和请求 DTO 构造遵守 `docs/coding-conventions.md` §14。不要在新测试中复制已经禁止的存量写法。
4. 契约测试保留独立预期值；不能让被测实现和断言引用同一常量来掩盖漂移。Mapper SQL 的共享生命周期分类要对照领域枚举；SLA、失败筛选等专属业务口径单独测试。

## Fixture 与真实副作用

- 平台已有配置、枚举、持久化键或服务地址优先使用权威事实源；跨模块共享默认值放在 `batch-test-support`，场景特有输入保留在测试。
- SQL fixture 与 Shell 编排职责分开：可复用的初始化/校验 SQL 放入 根 `test-data/sim/` 等权威 fixture 目录，由脚本引用；不要把长 SQL 内嵌进 Shell，也不要让 fixture 直接替代被测业务流程。
- 集成测试复用 `AbstractIntegrationTest` 和共享 Testcontainers 生命周期；不在每个类重复启动基础设施。
- E2E 需要准备或回读数据库状态时，用 `JdbcTemplate` 访问真实测试数据库；核心动作通过被测 API、调度器或 Worker 执行，不用直接 SQL 改业务状态来伪造成功。Mapper SQL 用真实数据库 Mapper 集成测试验证。
- 事务、重试、乱序、并发、恢复和租户隔离要验证关键失败路径与最终持久状态。清理只删除测试拥有的数据，并确认失败时也能安全收尾。
- Mock、fixture、容器集成和真实环境证明范围不同；测试报告必须准确标明。

## SQL、断言与求值位置

- 按编码规约 §14.8，JdbcTemplate 的 SQL 先用有业务含义的局部变量承接；多行固定 SQL 使用 text block，参数继续绑定。场景专属 SQL 可保留在测试方法，共享 SQL 依原资产目录外置；不为形式统一把每条 SQL 新建文件或抽成万能工厂。
- 查询结果和影响行数先用准确类型的变量承接，再进入断言的实际值、期望值或诊断参数。轮询、事务与异常回调中的操作留在原回调，不提前查询、缓存结果或改变失败时点；专用会话/锁探针保留自己的连接生命周期。
- Mock 初始化、strictness、AssertJ 的具体例外按 §14.2–14.6 及邻近测试核对；不能因命名/DisplayName 门禁通过就认定整个 §14 合规。LENIENT 的理由须与共享 stub 的真实使用一致，不复制模板理由。
- SQL 文本整理核对运行值、末尾换行、绑定顺序、租户条件和执行次数；不顺带改变 SQL、cleanup 断言或数据所有权。可参考[测试规约审查](../../../docs/audit/test-code-conventions-review-2026-10-25.md)和[SQL/JDBC 整理](../../../docs/audit/test-sql-and-assertion-governance-2026-10-24.md)中的反例，不继承其历史剩余数量。

## 测试健壮性、国际化与环境切换

- 核对 Clock/时间、唯一标识、随机数据、结果排序及异步等待；按真实条件有界等待，失败时保留诊断。共享状态、数据库数据、Locale/时区、线程与连接必须按所属生命周期恢复，独跑和套件/并行隔离分别判断。
- 公共能力统一承接稳定的准备、业务请求、SQL 回读及清理责任；场景输入、独立预期和关键断言保留可读。先复用现有 fixture，不以万能基类或一组默认值隐藏场景差异。
- 普通业务测试优先断言 ResultCode、errorKey 和结构化 args，避免偶然依赖当前语言文案。i18n 专项保留独立中英文预期并检查 key parity、占位符、Locale 协商和 fallback；不得用被测 resolver 生成全部期望而掩盖翻译错误。
- 测试及其脚本从共享 profile/环境入口取得服务地址、动态端口、数据库、凭据和时区，核对本地、容器与 CI 的适用差异；明确覆盖和恢复环境/语言状态，不把某台机器的固定端口、目录或默认 Locale 当契约。
- 测试健壮性整改保持原验收强度；超时、锁竞争、迁移或故障探针可保留独立资源和精确断言，不以增加 sleep/重试、放宽断言或改为同源期望销项。

## 验证与交付

- 变更测试类时先检查邻近测试、共享 fixture、`docs/coding-conventions.md` §14 和该模块的测试 profile。
- 按改动风险运行定向测试；共享配置、fixture 或通用基类变化时扩大到所有受影响调用方。记录真实命令、退出码和运行依赖。
- 新增共享测试能力时核对 test scope、模块依赖方向、并行隔离、清理行为和配置唯一来源；同步测试文档或入口索引中实际变化的部分。
- 报告区分测试设计覆盖、实际执行结果、未运行项及 mock/容器/真实环境边界。不能将测试通过写成目标环境验收通过。

## 历史经验方法

排查独跑与套件差异、审查fixture可见性或补内容级验收时，按需读取 [历史经验方法](references/historical-methods.md)。只复用调查与验证方法，当前规则和原始证据分别维护。
