# 全方位深度扫描 — 总览(2026-06-03)

> **背景**:Phase 1 派出 11 个并发 agent 各 40min 深扫(BE 5 + FE 4 + SDK + CICD)。
> **结果**:**4/11 完成出报告**,7/11 在 API session 限额前完成扫描但未落地文档。
> **下一步**:API 4:10am(Asia/Shanghai)重置后补跑 7 个缺失 lane。

## 已落地报告(4 份)

| Lane | 文档 | P0 | P1 | P2 | P3 |
|---|---|---:|---:|---:|---:|
| BE 架构 + 维护性 | [be-architecture](2026-06-03-deep-scan-be-architecture.md) | 5 | 8 | 9 | 4 |
| BE 资源 + 调度 | [be-resources-scheduling](2026-06-03-deep-scan-be-resources-scheduling.md) | 0 | 5 | 9 | — |
| FE 布局 + 响应式 | [fe-layout-responsive](2026-06-03-deep-scan-fe-layout-responsive.md) | — | — | — | — |
| FE 配色 + 主题 + a11y | [fe-theme-color-a11y](2026-06-03-deep-scan-fe-theme-color-a11y.md) | 2 | 5 | 6 | — |
| **合计** | — | **7+** | **18+** | **24+** | **4+** |

## 待补 7 个 lane(API 重置后补)

- BE 安全审计(OWASP / SQL injection / SSRF / 命令注入 / 凭据写入数据库 / 多租隔离)
- BE 业务 bug + 运维(workflow / pipeline / batch-day / replay / outbox / dead-letter)
- FE 交互 + 表单 + 键盘(form 校验 / 快捷键 / 拖拽 / 错误恢复)
- FE 反馈 + loading + 错误(toast / 进度 / 空态 / 维护模式 banner)
- FE 易用性 + UX bug(信息密度 / 心智模型 / 移动端易用 / 角色 UX)
- SDK 双语言 + 数据完整性(Java↔Python wire / migration archive 同步)
- CI/CD + 部署 + 监控(workflows / docker / k8s / observability)

## 已识别的 P0(共 7 项)

### BE 架构 P0

1. **`MapperXmlTenantGuardArchTest` 守护断层**:仅覆盖 `batch-orchestrator` + `batch-console-api`,**`batch-trigger` / `batch-worker-dispatch` / `batch-worker-process` 的 mapper XML 无同等守护**,多租 `tenant_id` 强制 WHERE 可能被新代码绕过。
2. **`*Record` 后缀禁令在主代码 3 处违反**:orchestrator / worker-import / sdk;无 ArchTest 拦截。CLAUDE.md 红线明文禁止。
3. **`executeLegacy` 死代码假象**:`batch-worker-import/LoadStep.java:91,96` 标 `@Deprecated` 但被同类主路径调用,语义被破坏。
4. **CHANGELOG 双源不明**:根 `CHANGELOG.md`(11 天未动)+ `docs/changelog.md`(今天还在更)节奏脱钩,权威源不明。
5. **`MultiTenantIsolationIntegrationTest` 单点**:V160-V165 新表无回归覆盖,新加表的隔离性靠 ArchTest 兜不住。

### FE 配色 P0

6. **mobile 体系几乎完全脱离 token 系**:独立 `--ios-*` 调色板 + 大量裸 hex(`#007aff` / `#ff3b30`),主题切换不生效,暗色模式断层。
7. **色盲安全色板 0 处理**:7 色 ECharts 调色板红绿同时存在,~10% 用户辨识困难。

## 已识别的 P1(18+ 项)

详见各 lane 报告 §2 / §10。重点:

- BE 架构:`@Autowired field` 生产代码 6 处违例 / 单实现接口 TODO 11 天未结 / 同表双 Mapper 接口同名
- BE 资源:Hikari 总账 ~145 vs PG `max_connections=100`,扩 2 副本 orchestrator 必超
- FE 布局:固定 `width="800px"` dialog 4 处平板溢出 / 桌面 views `@media` 覆盖仅 17.3%
- FE 配色:业务图表硬编码 hex 21 处(`useOpsSummary.ts`)/ aria 覆盖 15.5%

## Phase 2 — 修复策略

P0 中可立刻自动修的(纯 archtest 守护补 + 代码 sed):
- P0-1 `MapperXmlTenantGuardArchTest` 扩到全 5 个 mapper 模块
- P0-2 `*Record` 后缀禁令 ArchTest 加上(扫 3 处违例,改名 `*Entity`)
- P0-3 死代码 `executeLegacy` 调用关系扫清(要么真用,要么真删)

P0 中需要设计决策的:
- P0-4 CHANGELOG 双源:跟用户对一下哪份留
- P0-6 mobile token 化:大改,~50 文件 sed + token 表扩展,**单独 lane**
- P0-7 色盲安全色板:工程化短,~半天

## Phase 3 — Acceptance(7 lane 补完后)

- BE:`mvn clean verify`(orchestrator + worker-* + console-api)+ `be-acceptance` skill
- FE:`npm run test:unit && npm run build && npm run test:e2e:smoke` + `fe-acceptance` skill

## 跟踪

API 重置后(4:10am Asia/Shanghai)补跑 7 个 lane,完成后补 P0/P1 修复 + acceptance。
