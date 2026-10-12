# 前后端约定与 CI 守卫漂移复核（2026-10-12）

> 本文是审计快照，不是新的规范来源。基线：后端 `7f0bdc17555d`、前端 `ed668caef240`，并核对了两仓当前未提交的工作区文件。并行修改中的文件未被本轮改写。方法：对照 AGENTS、权威文档、workflow、变更范围分类器和守卫脚本，做静态调用链核对及一项只读配置探针；未运行 CI、浏览器 E2E 或目标环境验收。

## 已确认的问题

| 优先级 | 范围 | 证据与影响 | 收口建议 |
|---|---|---|---|
| P1 | 前端 staging 浏览器验收 | `batch-console/.github/workflows/staging-gate.yml` 的普通 E2E 步骤只设置 `PLAYWRIGHT_BASE_URL`，但 `playwright.config.cjs` 经 `e2e/config.cjs` 读取 `E2E_BASE_URL`；API 地址还默认 `http://localhost:18080`。只设置 workflow 变量的只读探针返回前端 `http://localhost:5173`、API `http://localhost:18080`。`e2e/global-setup.cjs` 也使用固定的 `admin/admin123`，没有消费 workflow 提供的 `E2E_USERNAME/E2E_PASSWORD`。视觉回归配置读取 `PLAYWRIGHT_BASE_URL`，故不能把整个 staging gate 一概判为同一问题。 | 统一普通/视觉 E2E 的目标 URL 与 API 源，使用 staging 凭据；启动前断言目标 origin、后端地址和部署 SHA，补一条 workflow→Playwright 配置的负向回归测试。修复前不能把普通 E2E 的绿色结果称为“against staging”。 |
| P1 | 后端 PR 路由 | `scripts/ci/detect-change-scope.py` 把 `docs/governance/soft-gates.json`、`application-governance-contract.yaml`、`direct-config-key-access-baseline.txt`、`test-conventions-baseline.txt` 判为 `docs` 而非 `ci`。对应 PR workflow 的软门禁、应用治理和两个基线检查以 `ci`/`java`/`config` 为条件，因此单改这些文件时 PR 不运行专项守卫。Main 的 Full Gate 会运行，故这是 PR 阶段漏扫，不是全域无检查。 | 将机器可读治理契约逐项纳入精确变更范围，并对每个文件增加“该跑/不该跑”的分类器测试；不要把整个 `docs/**` 粗暴归入 Java 或 CI。 |
| P1 | 前后端存量基线 | 后端 `check-direct-config-key-access.py`、`check-test-conventions.py` 仅计算 `current - baseline`；前端 `check-maintainability.mjs`、`check-ui-complexity.mjs` 直接接受当前 JSON 的上限。基线与新缺口在同一 PR 中一起扩大时，相应检查仍可通过。 | 对基线文件增加相对 merge-base 的“不得无理由扩张”守卫或受控例外审查；减少基线允许自动通过，增加须列出新增项、owner、理由、到期/删除条件，并验证正反例。 |
| P1 | 前端 E2E 完整性 | staging 执行 `test:e2e:all`，但多个 spec 根据数据/权限/环境调用 `test.skip`，例如 `e2e/business-data-strict.spec.ts` 在 API 空列表时跳过字段校验；`e2e/monitor-row-actions.spec.ts` 在无实例时跳过。命令包含用例不等于用例实际验证了业务。 | 将发布必验场景的 seed 缺失改为失败或显式准入阻断；可选/破坏性场景保留 skip，但发布报告按场景列出 PASS/SKIP/FAIL 和原因，不以单个绿色 job 代表全覆盖。 |

## 后续落地状态（2026-10-12）

本分支对四项编码缺口做了定向收口；状态不代表 PR/CI 或真实 staging 已验证：

| 项目 | 本地落地 | 尚未证明 |
|---|---|---|
| GDRIFT-1 staging 目标对齐 | workflow 显式接收前端/API origin 和账号；健康检查、非 loopback 检查；Playwright staging 不启本地 Vite；配置负例测试通过 | staging 凭据、网络可达性、部署 SHA 对齐和真实浏览器运行 |
| GDRIFT-2 治理契约路由 | 4 份机器可读治理文件精确归入 CI scope，新增正反例；普通 Markdown 保持 docs-only | PR 与 Full Gate 实际 Actions 记录 |
| GDRIFT-3 基线受控扩张 | 两端比较目标分支；新增长度/数量需要匹配、未过期的 owner 审批及 CODEOWNERS；定向测试通过 | 维护者实际审批和远端 CI 执行 |
| GDRIFT-4 发布必验场景 | 必验 seed 缺失/跳过/未运行或标签全失效均失败；可选 skip 汇总到 Actions Summary | staging 实际数据下的场景通过率与业务正确性 |

真实 staging 未运行前不得把本地 E2E 配置测试称为 staging 通过。

## 守卫边界与周期审查

| 判断 | 当前证据 | 处理方式 |
|---|---|---|
| 自动守卫应补精确负向用例 | 后端 `check-script-governance.py` 能验证脚本登记，却不能证明脚本被 PR/Full 实际调用；`check-application-governance.py` 能验证状态、非空证据路径，却不能证明 `implemented` 已在运行环境生效。 | 新增守卫时同时核对“输入文件→路径分类→workflow 步骤→失败退出→required check”，对业务实施状态保留人工/目标环境核验。 |
| 守卫过窄，需要抽样核对 | 前端 `check-architecture.mjs` 用正则分析静态 import/export，不能覆盖动态加载或所有间接调用；`check:i18n` 编译译文、类型检查核对键结构，但不证明页面无硬编码用户文案，也不评价译文质量。 | 每月抽取新页面与关键调用链做人工/AI 审查；重复出现且可稳定识别的模式再补 AST/ESLint 守卫。 |
| 守卫过紧或只查字面，先改判据 | 前端 `check-env-governance.mjs` 以精确字符串匹配 Compose 片段；`check-go-live-readiness-doc.mjs` 以关键词出现判定文档覆盖。等价 YAML 排版可能被误拦，关键词存在也不能证明上线证据真实。 | 配置校验优先解析结构；文档语义、操作可执行性与验收证据保留季度抽样。改门禁前要证明一条误拦和一条应拦。 |
| 资源消耗候选，不直接关闭门禁 | 前端 `pr-gate` 与 `frontend-ci` 在同一 `.node-version` 下重复 Typecheck、Vitest、Build；Full Gate 在 PR 上也运行静态/单测。 | 先量化 job 关键路径与独立失效模式，再决定复用产物或缩减重复；保留 required check 名称和完整 fallback，不能只因耗时而跳过。 |
| 报告性检查不能当阻断证据 | 前端 Full Gate 的 Lighthouse 步骤 `continue-on-error: true`；后端软门禁有登记的期限和 owner。 | 报告里区分阻断、报告、跳过；每季度复审预算、例外理由与到期日，不能把 workflow 绿色解释为性能或安全全部达标。 |
| 跨仓契约与产品体验必须人工闭环 | 前端 `gen:api:check` 在有同名后端分支时读取该分支，否则回退后端 main；后端单仓 CI 不会自动证明前端页面、角色权限、Excel 模板和实际作业恢复流程可用。 | API/配置包改动按配对提交记录后端契约 SHA、前端生成类型 SHA 与目标环境操作证据；月度按角色抽样用户旅程，季度复核权限和文档。 |

## 复扫方法与节奏

每月审“上次基线到当前 main”的增量，以及配置维护、调度主链、授权和测试代表性；每季度抽样存量模块、例外/基线、部署与运维文档；上线前及重大故障后核对真实环境恢复和浏览器业务闭环。各项入口见[约定约束与漂移防护总账](./convention-drift-guard-index.md)及前端 `docs/engineering/convention-drift-review.md`。

每次记录：基线提交、变更范围、抽样依据、应拦/应放行样例、CI 实际运行证据、目标环境证据、SKIP、未覆盖项。不要把静态核对写成测试通过；本轮确认缺口已登记到[TODO Master 的 GDRIFT-1–4](../analysis/todo-master.md)，设计取舍与仅有风险线索分开登记。
