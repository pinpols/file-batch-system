---
name: cross-repo-governance-review
description: 对 file-batch-system 与 batch-console 做周期治理、全量工程审查、规范防退化、代码腐化盘点或发布前治理复核时使用。固定覆盖前后端业务链、架构、代码、测试、CI、安全、运行时、文档及治理守卫本身；普通单 PR 审查使用各仓代码审查 skill。
---

# 前后端周期治理总控

## 目的与边界

本技能是两仓治理审查的总入口，解决“每次重新想应该查什么”的问题。它负责固定覆盖面、证据口径、专项技能路由和闭环方式，不复制各专项规则，也不把所有检查机械地塞进每个 PR。

- 适用于月度/季度治理、全项目深扫、规范漂移、代码腐化、技术债复核、发布前工程审查、事故后的系统性复盘，以及用户明确要求前后端一起检查。
- 单个页面、模块、PR/diff 或已明确的专项缺陷直接使用对应专项 skill。
- “审查”只产出有证据的发现和建议；只有用户要求修复/落地时才修改代码、门禁或文档。提交、推送、PR、合并和定时任务仍需各自明确授权。
- 当前代码、OpenAPI、迁移、脚本、workflow 和运行证据高于历史报告。历史审计只提供复发假设，不继承其问题状态或通过结论。

## 必读入口

先确认两仓实际路径、分支、HEAD、工作区和审查基线，保留已有改动，然后完整读取：

- 后端 `docs/audit/convention-drift-guard-index.md`
- 前端 `../batch-console/docs/engineering/convention-drift-review.md`
- 两仓 `AGENTS.md`、CI/runbook、当前脚本清单和适用专项 skill

配对前端通常是 `../batch-console`；路径不存在时先定位，不凭记忆猜测。带日期报告和 `docs/archive/` 只作历史证据，不作为当前规范。

## 审查模式

| 模式 | 基线与深度 | 最低交付 |
|---|---|---|
| 月度增量 | 上次已确认治理基线到当前两仓 `main`；无可靠基线时声明所用日期/提交 | 完整覆盖矩阵、本期变更链路、至少一条高风险失败路径 |
| 季度存量 | 月度增量外，对每个适用领域选代表性存量样本；包含关键旅程、治理例外和运行入口 | 覆盖矩阵、样本依据、趋势/复发项、守卫有效性 |
| 发布前 | 固定待发布 SHA、镜像/配置和目标环境；核对 required checks、真实服务、回滚和值班路径 | 发布阻断项、环境证据、未验证项、恢复结论 |
| 事故后 | 从用户影响和数据结果逆向追根因，再检查同类入口、缺失测试和守卫 | 根因链、扩散面、修复验证、防复发措施 |
| 首次全量基线 | 当前两仓主线的全域静态与可执行证据，不以时间窗口缩小范围 | 全覆盖矩阵、债务分类、优先级、机器化候选、外部验证缺口 |

用户未指定时：`定期/月度` 用月度增量，`季度/全面存量` 用季度，`上线/发布` 用发布前，`事故/复盘` 用事故后，`第一次/全量/不要遗漏` 用首次全量基线。仍有小歧义时声明采用的模式并继续。

## 固定覆盖矩阵

每次必须逐行标记 `已审查`、`不适用（理由）`、`未验证（缺什么）` 或 `明确排除（用户范围）`，不得静默省略。月度可以按变更聚焦深度，但仍需判断所有行的适用性。

| 领域 | 必核对内容 | 主要路由 |
|---|---|---|
| 1. 产品关键旅程 | 四类角色、租户、建模/导入、运行、监控、审批、重试/补数/恢复的完整结果 | 前端 `frontend-quality-review`、`project-engineering-review` |
| 2. 批量领域语义 | 业务日、时区/DST、状态机、幂等、乱序、重试、补偿、checkpoint、最终结果选择 | `batch-scheduling-engineering`、`scheduler-correctness` |
| 3. Worker、消息与 SDK | 五类 Worker、claim/lease/report、Outbox/Kafka、背压、恢复、五语言 SDK 行为与版本 | `worker-pipeline-review`、`sdk-contract-governance` |
| 4. 前后端 API 契约 | Controller/DTO/OpenAPI/生成类型/API 客户端、错误语义、认证权限和兼容性 | 前端 `frontend-api-contract`、`frontend-backend-contract` |
| 5. 文件与数据完整性 | 导入/导出、Excel 模板、控制总数、校验和、对象存储、临时文件、重放与交付一致性 | 前端 `frontend-import-template-governance`、`object-storage-governance` |
| 6. 前端状态与数据流 | 租户切换、query key、分页、轮询、SSE、迟到响应、取消、缓存、空/错/加载态 | 前端 `frontend-state-data-flow` |
| 7. 数据库与迁移 | schema、事务、RLS/tenant、约束、归档、Flyway、前滚/恢复、查询与索引 | `database-migration-safety`、`sql-query-performance` |
| 8. 架构与职责 | 模块依赖、分层、端口适配器、公共模块、抽象强度、重复实现 | `project-engineering-review`、前端 `frontend-engineering-governance` |
| 9. 可读性与代码健康 | 复杂职责、深层控制流、参数合法状态、嵌套调用、固定 Map/字符串、重复/死代码、无价值包装、债务趋势 | `code-review-and-gates`、前端 `frontend-quality-review` |
| 10. 安全与隐私 | 认证授权、跨租、Cookie/CSRF/XSS、动态 SQL、文件、密钥、日志脱敏、bypass、例外期限 | `security-scan-governance`、前端质量/部署 skill |
| 11. 配置、部署与运行时 | 配置事实源、默认值、动态刷新、版本快照、env/Compose/Helm/Nginx/PWA、镜像及本地/容器/生产一致性 | `configuration-governance`、`module-config-boundaries`、前端 `frontend-deploy-runtime` |
| 12. 测试真实性 | 独立断言、fixture 来源、mock/真实依赖、skip/flaky、并发/故障/恢复、E2E 拓扑与数据隔离 | `ci-governance`、`acceptance-validation`、前端 `frontend-testing-ci` |
| 13. CI 与门禁有效性 | 路径分类、required checks、enforce/report/ignore、SKIP、基线扩张、正反例、失败汇总、定时任务 | 两仓 CI/工程治理 skill |
| 14. 静态分析与供应链 | Sonar/CodeQL/PMD/SpotBugs/ESLint、suppression、依赖新鲜度/漏洞、运行时版本、许可证、SBOM、Action 固定 | `security-scan-governance`、两仓工程治理 skill |
| 15. UI、i18n 与可访问性 | IA、视觉一致性、响应式/移动、中英文、长文本、键盘/焦点、非纯颜色表达 | 前端 `frontend-ui-ia-review`、`frontend-i18n-accessibility` |
| 16. 性能、容量与成本 | 热查询、锁/连接池、线程/队列/缓存/句柄、Kafka/对象存储背压、bundle/渲染、AI/资源成本、容量证据 | `performance-validation`、`sql-query-performance`、前端工程治理 |
| 17. 可观测性与审计 | 日志/指标/追踪、SLO/告警、业务审计、敏感信息、事件可关联性、仪表盘与真实故障信号 | 项目审查、配置治理及 runbook |
| 18. 韧性与运维闭环 | 超时/降级、DLQ/outbox、卡实例、备份/PITR/HA、滚动升级、Console 与脚本恢复能力及 RTO/RPO | `disaster-recovery-validation`、`acceptance-validation`、runbook |
| 19. 文档、决策与发布可信度 | ADR、架构事实、runbook、API/字典、索引、changelog、TODO/backlog、版本/发布说明、历史材料状态 | 两仓文档治理和 `git-pr-workflow` |
| 20. 治理系统自身 | 新目录/模块是否入扫描，规则是否命中真实风险，白名单/基线膨胀、重复门禁、过时阈值、owner/期限和假绿 | 本技能 + 两仓 CI/工程治理 skill |

## 执行方法

1. **冻结事实基线**：记录两仓路径、branch、HEAD、merge-base/时间窗口、dirty 状态、目标环境和排除项。远端或 CI 结论要实时查询。
2. **建立变更与风险地图**：用 `rg --files`、Git diff/log、文档索引、路由/Controller、迁移、workflow 和脚本登记表找新增/删除入口及职责迁移；不要只按文件后缀分类。
3. **填覆盖矩阵**：先判断 20 域适用性和证据，再深入高风险链。每域至少说明检查入口、样本和结论；季度/全量不得只扫 diff。
4. **分开机械与语义审查**：复用仓库已有命令，不重写第二套扫描器。门禁通过只证明声明范围；人工检查业务组合、抽象适度、测试代表性、文档真实性和用户任务。
5. **验证正反例**：守卫缺口先证明一个应拦和一个相似但应放行案例。只有事实源稳定、误报可控且有自测时才建议阻断；否则用报告型、增量型或周期复核。
6. **沿链路确认问题**：候选问题追到入口、状态/数据副作用、用户/运维影响和根因。搜索命中、Sonar issue、历史 TODO 和阈值只是候选。
7. **闭环且不制造新债务**：修复时同步必要的代码、测试、契约/生成物、fixture、CI 登记、runbook、ADR/规范、索引和 changelog。禁止靠扩大基线、跳过、吞错、宽泛 suppression 或重复文档换绿色。
8. **复核治理成本**：新增门禁前评估耗时、稳定性、owner、失败处置及删除/升级条件；已有门禁精确覆盖时不加同义脚本。

## 证据与状态口径

证据强度通常为：目标环境真实业务结果 > 真实依赖集成/故障演练 > 契约或组件测试 > 单元测试 > 编译/类型/静态检查 > 源码推断 > 历史报告。不同层不能互相冒充。

- `PASS`：本轮在指定 SHA/环境实际完成且成功。
- `FAIL`：实际执行失败，保留首个有效错误、范围和归因。
- `SKIP/REPORT/IGNORE`：写明原因、owner/期限和是否阻断，一律不算通过。
- `未运行/未验证`：写明缺少环境、凭据、数据、时间或授权及其影响。
- `抽样未发现`：只对已列样本成立，不外推全仓。

## 固定交付格式

1. 基线与模式：两仓 SHA、范围、环境、dirty/远端状态。
2. 发现：按严重度列位置、触发条件、影响、证据、根因、建议和验收标准；分开确认缺陷、治理缺口、待验证风险和设计建议。
3. 20 域覆盖矩阵：逐项状态、样本、命令/证据和未覆盖项。
4. 验证记录：实际命令与 PASS/FAIL/SKIP/未运行，明确 mock、容器、真实后端、staging/生产边界。
5. 防复发闭环：代码/测试/门禁/文档/runbook 中应落到哪里，哪些保留人工周期复核。
6. 下一周期基线：用户需要持续治理时记录两仓 SHA 和仍有效的未验证项，不复制易漂移的告警数或测试数。

无确认问题时仍必须给覆盖和残余风险，不能只写“未发现问题”。
