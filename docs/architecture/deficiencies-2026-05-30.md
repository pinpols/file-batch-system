> 注:本文为 2026-05-30 时点快照,部分内容已被后续实现取代(如 Task SPI 与五语言 SDK 已落地),以代码与 ADR 为准。

# 项目客观缺陷盘点(2026-05-30)

> 基于本次 session 实战(grep / mvn / e2e / 跑流水线 / 翻代码)+ 跟 DolphinScheduler / Airflow / Azkaban / XXL-Job 对比识别。
>
> 演进路线见 [p0-p1-p2-roadmap](./p0-p1-p2-roadmap.md)(P0/P1/P2 对应下面前 3 条)。

---

## 架构 / 设计层

### 1. 控制面过重(console-api + orchestrator 占 67%)

- `console-api` 44k 行 main,职责膨胀到:RBAC / 审批 / 告警 / 监控 / 报表 / Web Push / 文件配置 / 队列管理 / SLA / outbox 运维 / AI chat / ...
- `orchestrator` 36k 行,既要状态机又要调度策略 + 补偿 + DAG 解析 + outbox relay
- **风险**:任一处 bug → 整个控制面 / 调度面崩,部署粒度太粗,blast radius 大
- **对照**:DolphinScheduler 把 API / Master / Worker / Alert 拆 5 个进程
- **路线**:见 [roadmap §P1-A](./p0-p1-p2-roadmap.md#p1-a-console-api-拆分)

### 2. 没有任务类型扩展机制(SPI / Plugin)

- Pipeline 5-6 stage 写死(`IMPORT/EXPORT/PROCESS/DISPATCH`)
- 加新业务集成(SFTP / API 拉取 / MQ 消费)= 改 BE + 写 worker 模块,周期 ≥ 1 个月
- **对照**:DolphinScheduler 40+ 任务类型走 SPI,加新类型零核心改动
- **现状成本**:每加一种业务集成 = 一次 mini 项目
- **路线**:见 [roadmap §P0](./p0-p1-p2-roadmap.md#p0-任务类型-spi--plugin-化)

### 3. 没有可视化 DAG 设计器

- workflow 走 JSON 定义 + 后端校验,前端只能"看"不能"编"
- 业务 / 运营要拼 workflow 必须懂 JSON schema
- **对照**:DolphinScheduler / Airflow 拖拽编辑是行业事实标准
- 现有 `workflow viewer` 只读,做不到交互闭环
- **路线**:见 [roadmap §P2](./p0-p1-p2-roadmap.md#p2-可视化-dag-编辑器)

---

## 工程纪律 / 一致性

### 4. 代码风格漂移(同类组件两套写法)

- **确凿例子**:`ConsolePushProperties.java` 手写 13 个 getter/setter,而其他所有 `*Properties` 都用 `@Data`
- **推测**:`ConsolePush 模块`整体从前端仓 `backend-patch/` "搬"进来,没按本仓约定再 review
- **根因**:合并 / patch import 缺少风格 lint 强制(PMD 规则不覆盖手写 accessor)
- **修法**:
  - 短期:本次已修 `ConsolePushProperties`(commit `c46dd05c`)
  - 中期:加 PMD/Checkstyle 规则 — `@ConfigurationProperties` 类必须用 `@Data` 或 `@Getter/@Setter`
  - 长期:重要外部 patch 走"风格 review"checklist(PR 模板加一项)

### 5. 测试覆盖不均

- 整体 test/main = 0.56(及格)
- 5 个模块 < 0.5(worker-* / console-api 主仓);`console-api` 44k main 只 21k test 比例偏低
- **变化最频繁的恰好覆盖最低**(console-api)→ 回归脆弱
- e2e 跑出来的失败大半是**测试假设过期**(seed 数据 today 过滤、locator 飘),说明 spec 维护跟 FE 改动节奏脱节
- **修法**:
  - 短期:把 console-api 的核心域(RBAC / workflow / job)逐域补到 0.7+
  - 中期:e2e seed 数据用相对日期(`bizDate = today - 3d`)而非硬编码,避免漂移
  - 长期:CI 加每域覆盖率门禁(`test/main ≥ 0.5`)

### 6. 跨仓协作靠脚本不靠规范

- BE 改 OpenAPI yaml,FE 跑 `gen:api` 同步 — 但**改动作 owner 自己记不住,要靠 CI `gen:api:check` 拦**
- BE / FE 各自一仓 + worktree + agent session 多线作业 → 本 session 出现 `ci/admin-automerge-rewrite` 分支累积工作 / worktree 死了 2 天没人理
- 这是**工程问题不是技术问题**
- **修法**:
  - 短期:已加 [branch-hygiene skill](../../.claude/skills/branch-hygiene.md) 防短命分支累积
  - 中期:BE OpenAPI 改动直接 PR 触发 FE 仓 `gen:api` workflow(GitHub repository_dispatch)
  - 长期:考虑 monorepo(短期不推,长期看团队规模)

---

## 运维 / 可观测

### 7. 服务自愈能力弱

- Trigger 服务 2 天前 hung 死,没人发现 → 本次手动 restart 才好
- 没看到:liveness 探针不响应 → 自动 restart / Watchdog 进程 / CrashLoopBackOff 告警
- **现状假设**:依赖运维 / K8s 替做(但本地开发 nohup 起的进程没这层保护)
- **修法**:
  - 短期:本地 `scripts/local/start-all.sh` 加 watchdog 循环(curl actuator/health 失败 → 自动 restart)
  - 中期:K8s deployment 强制 livenessProbe + readinessProbe + restartPolicy
  - 长期:接 Alertmanager,`up==0` for 1m → 告警

### 8. Downstream 不可用没降级

- 本次手动加了 `triggerList()` 的 `RestClientException` catch → 空 list 降级
- 但**还有大量类似的 proxy call**(`schedulerStatus` / dashboard 聚合 / outbox 查询)**没加**
- 任一上游挂,对应页就崩
- **应该有**:统一的 RestClient interceptor + 降级策略表(哪些可降级 / 哪些必 fail-fast)
- **路线**:见 [roadmap §P1-B](./p0-p1-p2-roadmap.md#p1-b-downstream-降级统一)

### 9. 缺统一的健康巡检 UI

- BE 9 个模块的健康 / 版本 / SLA / 数据完整性散在各处
- 当下要确认"系统是否正常"还得拼:看 prom 指标 + actuator/health + BE log + 跑 `strict-verify.sh`
- **对照**:DolphinScheduler"监控中心"页一眼能看 Master/Worker/DB/ZK 五件套状态
- 现有 `ops-diagnostic` 页已有雏形,但还不是 single pane
- **修法**:
  - 短期:`ops-diagnostic` 页补 9 模块 health + 版本号 + 关键指标(SLA / outbox lag / kafka lag)
  - 中期:加"集群拓扑图"(每个模块画方块,颜色 = 健康度)
  - 长期:接 Grafana 大盘 iframe 嵌入

---

## 业务功能层

### 10. 多端缺统一 SDK

- console-api 暴露 REST,FE 用 `gen:api` 生成 TS 类型
- 第三方系统要集成(其他业务方接调度)→ 没有现成的 Java SDK / Python SDK,要他们手写 HTTP client
- 对内部平台是常见痛点,中后期会有 ROI
- **修法**:
  - 短期:基于 OpenAPI yaml 用 `openapi-generator` 生成 Java SDK(MVP 几天)
  - 中期:Python SDK 同上
  - 长期:加 SDK example repo + getting-started 文档

### 11. 不支持工作流即代码(WaC)

- workflow 只能通过 console UI / API 创建,不能 commit 进 git 走 PR review
- 平台演进趋势:DolphinScheduler 3.x 已补 PythonGateway + WorkflowAsCode,Airflow 天然是
- 业务方想"workflow 走 git review" → 没办法
- **修法**:
  - 短期:暴露 workflow JSON 导入 / 导出 API + 命名空间(git 里维护 `.workflows/<name>.json`)
  - 中期:CLI 工具(`batch workflow apply / diff / lint`)
  - 长期:Python DSL(`@workflow def my_pipeline(): ...`)

### 12. 缺少补数(backfill)/ 重算 / 调试模式的成熟 UX

- 已有 `batch-day-replay` 接口,但 UI 看(本次跑 e2e 时 `batch-day-replay` 4 个 spec 全失败,说明这个功能链路本身**没完全稳态**)
- 数据治理团队最重需求,这块投入不够会被诟病
- **修法**:
  - 短期:e2e 4 个 spec 必修 + UI 体验闭环(选日期范围 → 选 scope → 预览影响 → 确认 → 进度页)
  - 中期:加"调试模式"(单 job 跑 + 立即看结果 + 不影响生产数据)
  - 长期:跟 lineage 联动(改了上游,自动算下游要重跑哪些)

---

## 文档 / 治理(优势但有阴面)

### 13. 文档增长过快没清理机制

- **60k 行 / 283 md 文件**
- 本次扫到:`docs/backlog/` 多份按日期的 acceptance 报告,`docs/archive/` 也在长
- 没看到周期性归档 / 清理策略 → 6 个月后**文档比代码还多**,新人 onboarding 不知道哪份是权威
- **修法**:加 doc lifecycle policy:
  - `active`:当前生效,放 `docs/architecture/` `docs/runbook/` `docs/design/`
  - `archived`:历史快照,挪到 `docs/archive/<year>/`
  - `deprecated`:明确告知"已被 X 替代",在文件顶部加红 banner
  - 季度 audit:跑脚本扫"6 个月没改 + 没被任何文件 link"的 md → 候选 archive

---

## 总结

| 类别 | 项数 | 优先级 | 路线 |
|---|---:|---|---|
| 架构 / 设计 | 3 | P0-P2 | 已写 [roadmap](./p0-p1-p2-roadmap.md) |
| 工程纪律 | 3 | 持续 | 短期渐进,无大项目 |
| 运维 / 可观测 | 3 | P1 + P3 | P1-B 已写 roadmap,P7/P9 后续单独立项 |
| 业务功能 | 3 | 看战略选 | SDK / WaC / backfill 看团队需求优先级 |
| 文档治理 | 1 | 长期 | 加 lifecycle policy |

**关键判断**:工程纪律(4/5/6)+ 运维(7/9)对**当下稳态**威胁最大,但单项工期短,可滚动消化。架构层(1/2/3)对**未来扩展**决定上限,需要立项推。

---

## 复算 / 验证

- 代码行数据源:[loc-2026-05-29.md](../stats/loc-2026-05-29.md)
- ConsolePushProperties 风格违规来源:本次 session 修 commit `c46dd05c`
- Trigger 服务 hung 来源:本次 session restart `bxjx6vgdy`(2 天未察觉)
- e2e batch-day-replay 失败来源:本次 session 跑 `bq26nm3lg`(20 fail 列表)
