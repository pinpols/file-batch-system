# 深度审查报告:SDK 自托管 / Atomic Worker / 前端三角现状

**日期**:2026-06-02
**范围**:SDK 自托管(ADR-035)+ Atomic Worker(ADR-029)+ 前端 `batch-console`(围绕 SDK/Atomic 接入)
**方法**:并行调研三个独立 agent → 综合 → 评分 + TOP 10 改进项
**配对仓**:`../batch-console`(FE) ↔ 本仓(BE)

---

## 0. 执行摘要

| 板块 | 评分 | 一句话定性 |
|---|---|---|
| **SDK 自托管(ADR-035)** | **4.0/5** | 模块边界与协议设计扎实,Phase 1-5 链路接近完成;**KafkaTaskConsumer capacity-aware pause 缺失是 P0 生产 blocker**,文档/集成测/K8s 验收明显滞后。 |
| **Atomic Worker(ADR-029)** | **3.8/5** | 物理隔离强硬、四执行器各有安全闸,但**白名单"空=允许全部"的 fail-open 隐患** + **dry-run 执行器层无感知** + **console schema 手维护漂移**是三道短板。 |
| **前端(batch-console)** | **3.8/5** | 整体架构扎实、约束体系完善;新页面交付 ~60% 清单(A.1/B.1/B.2 半完整/C.1),A.2 工作流编辑器集成 + B.3 安全闸越界提示 + 凭据硬警告 三大缺口最显眼。 |
| **🔴 跨域综合** | — | 三处共有一条主线:**"代码 ready / 协议 ready,但运营可视化 + 生产验收 + 可观测端点 没完工"**。 |

**最关键一句**:从纯架构看本 H2 项目已可交付 demo,但**租户首次接入到生产稳定运行的"最后一公里"——可观测端点、灰度切流脚本、敏感凭据 UX 警示、e2e 真实环境验收——分散在三处都缺**。建议把后续 2-3 周聚焦补齐 §5 列出的 TOP 10。

---

## 1. SDK 自托管(ADR-035)

### 1.1 七维度评分

| # | 维度 | 评分 | 关键证据 |
|---|---|---|---|
| 1 | 架构与边界 | 4.5/5 | 三模块分清(core/starter/testkit)`batch-worker-sdk/pom.xml:7-24` 严格 minimal dep;`SdkTaskHandler.java:21-47` 3 方法极简;`WorkerRuntimeState.java:17-27` 4 态注释到位 |
| 2 | 协议与通信 | 4/5 | `TaskDispatchMessage.java:15-48` 带 schemaVersion + ignoreUnknown;`HeartbeatDirective.java:30-77` directive 5 字段完整;**Report DTO 未在 SDK 端绑定**,只在 `SdkPlatformContractTest.java` 走 wire 对账 |
| 3 | 生命周期 | 4/5 | V159/V160/V161/V162/V163 五代迁移全落;**KafkaTaskConsumer 的 partition pause/resume 未实现**(ADR-035 §11.1 给了伪码) |
| 4 | 安全与隔离 | 4/5 | apiKey/SASL 全走 env;**凭据策略仅文档纪律,无强制 SensitiveDataValidator** |
| 5 | 可观测性 | 3.5/5 | ProgressReporter + MDC 串联到位;**fingerprint 看板端点、cancel 主动推流、ThrottledLogger、OTel trace 全缺** |
| 6 | DX | 3.5/5 | typed handler / testkit / starter 都就位;**缺独立 template repo + 凭据管理示例 + Spring AoT** |
| 7 | 测试/CI/文档 | 3.5/5 | 45 单测 + Contract Test;**无 SDK↔orchestrator 真实集成测、无 K8s 验收、文档零散** |

### 1.2 三大风险

1. **🔴 P0 — KafkaTaskConsumer 缺 capacity-aware pause**:in-flight 满 `maxConcurrentTasks` 时不 pause partition,持续 poll 会内存爆。ADR-035 §11.1 已有伪码,代码未落。
2. **🟠 优雅停止无 timeout 上限**:`BatchPlatformClient.stop()` 没有 `Duration timeout` 参数,K8s `terminationGracePeriodSeconds` 到期前不结束就被 SIGKILL,正在跑的 task 状态错乱。
3. **🟠 lease 配置无 cross-field 校验**:`leaseRenewInterval` 配 >= server TTL 时任务被无故回收,无运行期校验,无告警。

### 1.3 完成度的另一面

- ✅ **V159–V163 五代 SQL 迁移**全落,等价于 SDK Phase 1–5 数据层完成
- ✅ **Phase 2 四态 FSM**(`TaskDispatcher.java:88-128 applyPlatformDirective`)已生效,Phase 3 自定义 taskType registry(`CustomTaskTypeEntity`)console-api 已有 GET 三件套
- ✅ **Phase 4 cancel/lease-revoke/progress** 通过 `CancellationSignal.java` + `ProgressReporter.java` 落地
- ✅ **Phase 5 fingerprint + testkit + starter + typed handler** 4/5 完成

---

## 2. Atomic Worker(ADR-029)

### 2.1 六维度评分

| # | 维度 | 评分 | 关键证据 |
|---|---|---|---|
| 1 | 隔离与定位 | 4/5 | 物理剥离 `batch-worker-atomic` 模块,4 个 pipeline worker classpath 无 atomic executor class;**缺 startup-check 强制断言** |
| 2 | 四执行器实现 | 3.5/5 | sql/proc/shell/http 各自安全字段完整;**SQL `forbidOsCapableRole` 仅 PostgreSQL 实现,跨方言失效无警告**;HTTP `enforceAllowlist=false` 默认 |
| 3 | 安全闸总览 | 4/5 | shell 默认 `enabled=false`,其余 true;**白/黑名单"空=允许全部"的 fail-open 隐含**(SQL/StoredProc/HTTP);**dry-run 执行器层无感知**,会真执行 |
| 4 | 运行期治理 | 3.5/5 | timeout 双保险(`stmt.setQueryTimeout` + `SET LOCAL statement_timeout`);**无 executor type 级线程池隔离**,shell(CPU)与 http(IO)会互相饿死 |
| 5 | console schema 暴露 | 4/5 | `ConsoleAtomicTaskTypeSchemaService.java:24-88` 静态 CATALOG;**schema 手维护,与 executor 源头无 CI 漂移守护** |
| 6 | 测试与回归 | 3.5/5 | 16 个 test 类含 hardening / SSRF / Integration;**漂移 CI gate 缺、跨方言失效场景未测** |

### 2.2 三大风险

1. **🔴 生产 fail-open**:SQL `allowedDataSourceBeans=空` / StoredProc `allowedSchemas=空` / HTTP `allowedHostPatterns=空 且 enforceAllowlist=false` 三处隐式"允许全部",生产环境若误用默认配置会大开攻击面。**目前各 `*ExecutorProperties` 注释里写了"空可接受"是开发友好,但缺 prod profile 启动期强制断言**。
2. **🟠 dry-run 执行器无感知**:`V115__dry_run_mode.sql` 已加 dry_run 字段,executor 层无判断,`ctx.isDryRun()==true` 时仍真发 SQL/Shell/HTTP。ADR-026 dry-run 的"看会不会跑/看 SQL"承诺在 atomic 这条路径打折。
3. **🟠 console schema 与 executor 漂移**:`ConsoleAtomicTaskTypeSchemaService` 的 CATALOG 是手维护硬编码,executor 改字段不会自动同步,FE 表单与实际参数不一致只能靠人发现。

### 2.3 已较完整建设的部分

- ✅ shell 默认 `enabled=false` + `commandWhitelist=空 即 全禁` 是教科书级 fail-closed
- ✅ http SSRF 防护两层:glob 黑名单 + DNS 解析后 IP 段拒(`blockPrivateIps=true` 默认)
- ✅ sql 在 PG 上的 `forbidOsCapableRole` 反向查询 `pg_has_role()` 是巧思
- ✅ 物理隔离让"误开 shell 在 process worker"成为编译期不可能

---

## 3. 前端(batch-console)

### 3.1 七维度评分

| # | 维度 | 评分 | 关键证据 |
|---|---|---|---|
| 1 | 整体架构 | 4/5 | 桌面/移动双端路由分流清晰;PageContainer→PageHeader→SectionCard→ProTable 体系成型;**TanStack Query 与直接 axios 调用边界模糊** |
| 2 | 新功能(A/B/C)完成度 | 3.5/5 | A.1 ✅ B.1 ✅ C.1 ✅ A.2 ⚠️半(无编辑器集成) B.2 ⚠️半(只读无编辑) **A.3/A.4-A.6/B.3/B.4 未做(本批不阻塞)** |
| 3 | 类型/契约同步 | 3/5 | `customTaskTypes.ts` 手写 wrapper(BE OpenAPI `data` 是 untyped),`gen:api:check` 无法检测此层漂移 |
| 4 | i18n/token/a11y | 3.5/5 | zh/en 4000+ 行 1:1 ✅;**LayoutTabs / MWorkflowViewer 等仍有裸 px / hex(~8 处)**;dark/compact 切换状态不明 |
| 5 | 测试 | 3.5/5 | 413 Vitest 测集中在 api/utils;**SFC 组件零单测、新页面边界场景(空/错/超时)无 e2e** |
| 6 | 构建/CI | 4/5 | Vite 8 / TS 6 / build 7s;**e2e 仅 staging-gate(不在 pr-gate),9h 总耗时未分片** |
| 7 | 安全/治理 | 3.5/5 | XSS / HttpOnly cookie 规范;**敏感凭据字段无 FE 硬警告(清单 §A.2 / §B.3 要求)** |

### 3.2 清单 A/B/C 完成度对照(权威源:`docs/plans/fe-worklist-2026-h2-atomic-sdk.md`)

| 清单项 | 状态 | 备注 |
|---|---|---|
| **A.1** 「我的 taskType」列表 + 详情 | ✅ | `CustomTaskTypeList.vue`,drawer 详情含 descriptor JSON + 凭据安全提示 alert |
| **A.2** 工作流编辑器按 schema 渲染表单 | ❌ | **未做 — 仓内无交互式编辑器**(只有 `WorkflowMermaidViewer`),已与用户确认改成独立配置中心(降级方案) |
| **A.3** 模板变量补全(`${bizDate}`) | ❌ | 未做,清单 1d 工作量,**按运营需求再上** |
| **A.4** TaskType 发现页 | ❌ | 同清单计划"按需再上" |
| **A.5** 重载/版本切换 | ❌ | 同上 |
| **A.6** Worker 健康度看板(fingerprint) | ❌ | 同上;**BE V163 列已加,仅缺 console 端点 + FE 页面** |
| **B.1** atomic schema endpoint | ✅ | `getAtomicTaskTypeSchema` + `AtomicTaskTypeCenter.vue` 接通 |
| **B.2** 四类参数表单 | ⚠️ 半 | **只读 schema 展示 + 试填本地预览,不能真正保存配置**(运营改 SSRF 白名单等仍需 Swagger / 直改 DB) |
| **B.3** 安全闸校验 + 越界提示 | ❌ | 未做 — schema 表只展示安全闸字段含义,但**未做"白名单越界即时红字"** |
| **B.4** SQL/shell 编辑器(高亮+命令 picker) | ❌ | 未做 |
| **C.1** 任务详情 heartbeat | ✅ | `HeartbeatProgressPanel.vue`(autoRefresh + 进度条 + JSON 详情);桌面 `JobInstanceDetail` 加 tab;移动 `MJobInstanceDetail` 也加了进度卡 |
| **首登向导 + NoTenantBanner** | ✅ | 用户单独提出的需求,本批合(PR #37);router guard 联动 |

**实际完成度**:核心闭环 5/11 完整 + 2/11 半完整 + 4/11 未做(按清单"按需再上")。**MVP 可用,但 B 区作为运营配置中心当前仍只是"查 + 预览"性质**。

### 3.3 前端三大风险

1. **🟠 凭据 UX 警示缺失**:清单 §A 区明确"敏感凭据(DB 密码 / OAuth secret)禁止走 parameters 表单 — A.2 表单遇到疑似凭据字段须给硬警告,引导走 env"。**A.1 详情 drawer 里有一句静态文案,但试填表单(B.2 AtomicTaskTypeCenter)对 `password/secret/credential` 字段无检测、无硬警示**。运营若在试填里填了密码截图给同事,泄露面真实存在。
2. **🟠 类型双源(generated vs 手写 wrapper)无 CI 守护**:`customTaskTypes.ts:10-26` 手写 11 字段是因 BE OpenAPI `data` 为 untyped。但**BE 改了字段(如加 `sdkVersion`),`gen:api:check` 不会报错**,FE 拿不到新字段。当前是"可控漂移",随时间累积会失控。
3. **🟡 e2e regression 反馈延迟**:e2e 仅 staging-gate 跑(故意 — BE 在 PR-gate 起来不稳),PR merge → main → staging deploy 才能验证,反馈周期 ~10min+。combined with 64 specs 单 worker 跑 ~9h,失败后定位慢。

### 3.4 前端实现较完整的部分

- ✅ **设计体系套用一致**:新 5 页面都走 `PageContainer → PageHeader → SectionCard`,无重创设计语言
- ✅ **i18n 1:1 严格**:zh/en 都各加了 4 个 namespace,`npm run check:i18n` 全过
- ✅ **mobile 进度卡用原生 CSS**:避开 Element Plus 进度条在 mobile 字号问题,小尺寸下显示干净
- ✅ **api 模块 26 个新单测**:虽然 404 catch 分支被 vitest mockRejected 怪行为拦下 deferred,但核心 happy path 覆盖到位

---

## 4. 跨域关键发现(三处共有的问题)

### 4.1 fingerprint / progress 这条可观测链 三处全断

| 层 | 现状 |
|---|---|
| **数据层(BE)** | ✅ V163 worker_registry 加 buildId/pid/sdkVersion;`job_task` 加 heartbeat_details JSONB |
| **SDK 上报端** | ✅ `BatchPlatformClient.register` 上报 fingerprint(SDK-P5-3 #220);`ProgressReporter` 写 details |
| **console-api 读端** | ⚠️ 仅 `getTaskHeartbeatDetails(taskId)` 一个端点(C.1 用),**无 fingerprint 看板查询、无 progress 历史曲线、无 worker 分组统计** |
| **前端展示** | ⚠️ C.1 单 task 进度做了;**A.6 fingerprint 看板未做(清单标注"按需");灰度切流时无可视化反馈** |

→ "灰度推 buildId X% 流量"这事架构上拼齐了,但**运维看不到现在哪台 worker 跑哪个 buildId、健康度如何**,只能 SQL 直查。

### 4.2 凭据安全:三层都靠"约定"无强制

| 层 | 凭据策略 |
|---|---|
| **SDK 文档** | "凭据走环境变量" — 文档纪律,无 SensitiveDataValidator 强制 |
| **atomic executor** | shell `allowedEnvKeys=空` 是机制保护;sql/proc/http 凭据散落在 dataSource bean / Properties,**业务方仍可在 parameters 里塞** |
| **前端** | `descriptor` 详情 drawer 有静态 alert 文案;**试填表单层无 `password/secret/credential` 字段检测** |

→ 三层加在一起,运营/开发者只要不读文档,**仍能轻易把凭据从 FE 试填 → 派单 parameters → BE 写入数据库**,留下事故根因。

### 4.3 dry-run / strict-verify 这条质量链 atomic 路径有缺口

- BE strict-verify 7 项全过(`scripts/local/strict-verify.sh` 已验证 20/20)
- ADR-026 dry-run 在常规执行路径都有感知
- **但 atomic executor(sql/shell/http/proc)的 `execute()` 都不读 `ctx.isDryRun()`** → dry-run 一个 sql 任务,会真发 DML
- 影响:运维想用 dry-run 看 "这个 atomic 节点会执行什么" 时,可能造成生产数据污染

### 4.4 schema 漂移:atomic 是手维护,custom 是 untyped JSON

| | atomic | custom(SDK) |
|---|---|---|
| schema 来源 | console-api `ConsoleAtomicTaskTypeSchemaService.java` 硬编码 CATALOG | SDK descriptor 上报至 `custom_task_type_registry.descriptor` JSONB |
| FE 拿到的形态 | 结构化 `AtomicTaskTypeSchema` 接口 | `descriptor: string`,FE `JSON.parse` 后处理 unknown 字段 |
| 漂移守护 | ❌ executor 改字段不会自动同步 console CATALOG | ❌ FE wrapper 手写,`gen:api:check` 检测不到 |
| 现状评估 | 两边都依赖"提交时人记得改" |

→ schema 漂移监控空白,**两类 taskType 在不同路径上都有同种风险**。

### 4.5 文档碎片:SDK / Atomic / FE 三套 docs 互相不指

- **SDK**:`docs/architecture/adr/ADR-035-*.md`(428 行长文,验收 section 标"K8s 待验")+ `docs/runbook/per-tenant-worker-onboarding.md`(Phase D 详尽)+ 各 `examples/*/README.md`
- **Atomic**:`docs/adr/029-*.md` + `*ExecutorProperties.java` 内嵌 Javadoc
- **FE**:`docs/plans/fe-worklist-2026-h2-atomic-sdk.md`(本批清单)+ `../batch-console/CLAUDE.md`
- **缺一份"租户接入旅程"**:从拿到 SDK → 配 Kafka ACL → 注册首个 taskType → 在 console 看到 → 派第一个任务 → 看到进度 → 灰度切 buildId,**端到端 5 分钟 quickstart 没有**

---

## 5. TOP 10 改进项(跨三域统一排序)

按 ROI(影响面 ÷ 工作量)排序:

| # | 改进 | 域 | 工作量 | 优先级 | ROI |
|---|---|---|---|---|---|
| **1** | **KafkaTaskConsumer 加 capacity-aware pause/resume + stop(timeout)** | SDK | 1-2d | 🔴 P0 | 防生产 OOM / 强杀,ADR-035 §11.1 已有伪码 |
| **2** | **atomic executor 加 `ctx.isDryRun()` 感知 + prod profile 启动强制断言 fail-closed** | Atomic | 1.5d | 🔴 P0 | 修 dry-run 真发 + fail-open 两个隐患 |
| **3** | **凭据 SensitiveDataValidator 三层联动**(SDK descriptor / atomic parameters / FE 试填表单 password/secret 自动警示) | 跨域 | 1d | 🟠 高 | 防最常见运营泄露,一次写多处用 |
| **4** | **executor ↔ console schema CI 漂移守护**(反射扫 `PARAM_*` 常量 vs CATALOG) | Atomic | 1d | 🟠 高 | 消除"FE 表单与 BE 字段不一致"长期债务 |
| **5** | **fingerprint / progress 看板补 console 端点 + FE 页面**(对应清单 A.6) | 跨域 | 3d | 🟠 高 | 灰度推送可视化,运维诊断必备 |
| **6** | **集中 SDK 文档到 `docs/sdk/`**(quickstart + wire-protocol + troubleshooting + architecture)+ 独立 template repo | SDK | 2d | 🟠 高 | 降租户 onboarding 成本 50%+ |
| **7** | **e2e 分级:smoke + critical 进 pr-gate(15min cap),全量分片 4x staging-gate** | FE | 1d | 🟡 中 | regression 反馈周期 10min → 5min,e2e 总耗 9h → 2.5h |
| **8** | **B.2 升级为可保存配置**(atomic 节点真正写库,接 workflow_node 或新表) | FE+BE | 3d | 🟡 中 | B 区真正落地"运营在 console 配置原子节点"的产品诉求 |
| **9** | **SDK ↔ BE 集成测 + K8s 验收 CI hook**(消除 ADR-035 验收 section "K8s 待验") | SDK | 3-4d | 🟡 中 | 真实环境兼容性回退,避免"上线才发现"的事故 |
| **10** | **类型双源消化**:推 BE OpenAPI 完善 customTaskTypes 字段,删 FE 手写 wrapper,`gen:api:check` 自动守护 | FE+BE | 1d FE+1d BE | 🟡 中 | 类型权威源回归单一,减少团队疑惑 |

**前 6 项做完 ≈ 1.5 周**,可把生产成熟度从当前 ~3.8/5 提到 4.5/5。

---

## 6. 风险登记表(按发生概率 × 影响)

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| Kafka in-flight 满,consumer 不 pause → OOM/lag 爆 | 中 | 极高 | 改进 #1 |
| 生产配 atomic 时 allowlist 留空 fail-open → SSRF/RCE | 中 | 极高 | 改进 #2(prod profile fail-closed) |
| 运营把密码填到 atomic 试填表单 → 截图传 → 凭据泄露 | 高 | 高 | 改进 #3(三层警示) |
| executor 加字段 console schema 没改 → FE 显示陈旧/缺字段 | 中 | 中 | 改进 #4 |
| 灰度推 buildId,无 fingerprint 看板,运维盲推 → 一片失败 | 中 | 中 | 改进 #5 |
| 租户首登失败(env 配错/ACL 错)无 troubleshooting 文档 → 工单 | 高 | 低 | 改进 #6 |
| FE 改组件破回归 → staging 才暴露 → 紧急回滚 | 中 | 中 | 改进 #7 |
| dry-run 真发 SQL → 污染业务表 | 低 | 极高 | 改进 #2 |
| SDK 升 minor 与老 orchestrator 兼容 → 字段错配 | 低 | 高 | 改进 #9 |
| customTaskTypes 字段漂移积累 → 后期重构成本 | 高 | 低 | 改进 #10 |

---

## 7. 结论

**架构层**:三处都已搭得稳——SDK 模块边界清晰、协议向后兼容设计成熟;atomic 物理隔离教科书级、四执行器各有 fail-closed 设计;前端约束体系 + 设计语言一致。**这是过去 H2 sprint 的核心产出**。

**收敛层**:三处共有的"最后一公里"问题——**生产 hardening 缺失(SDK Kafka pause / atomic fail-closed)、可观测端点缺失(fingerprint / progress 看板)、运营 UX 缺失(凭据警示 / atomic 可保存配置 / SDK 接入旅程)**——决定了从 "demo ready" 到 "运营自己上线" 的距离。

**建议节奏**:把 TOP 1-3(P0)在下一个 sprint 内做完,TOP 4-6(高 ROI)排入再下个 sprint,TOP 7-10 按需排。**完成 TOP 6 后,本批 SDK 自托管 + atomic + FE 三角能从"功能交付"进入"运营可用",才真正完成 H2 立项目标**。

---

## 8. 交付追踪(2026-06-02 当日并发 5 lane 落地)

5 个独立 worktree 并发跑(各自 general-purpose agent),全部 PR 已合 main。Lane 间冲突(B/C 同改 atomic executor)由 GitHub 自动 rebase 干净合并,无需人工解。

| Lane | TOP # | PR | 状态 | 落地点 / 关键文件 | 测试 |
|---|---|---|---|---|---|
| **A** SDK Kafka pause + stop(timeout) | #1 | [#239](https://github.com/pinpols/file-batch-system/pull/239) | ✅ MERGED | `batch-worker-sdk/.../client/BatchPlatformClient.java:163-209`(`stop(Duration)` + 预算分摊 kafka 20% / dispatcher 75%)+ `dispatcher/TaskDispatcher.java:490-519`(超时 WARN 列 `inFlightTaskIds()`)| 286 单测全部通过,新增 8(`*StopTimeoutTest` + `*CapacityPauseTest`)|
| **B** Atomic dry-run + prod fail-closed | #2 | [#241](https://github.com/pinpols/file-batch-system/pull/241) | ✅ MERGED | `batch-common/.../spi/task/TaskContext.java` 加默认 `isDryRun()` + 4 个 `*TaskExecutor` 短路 + `runtime/AtomicExecutorProductionGuard.java` 启动期 fail-fast(`prod` / `prod-*` profile 强制白名单)| atomic 155/155、common 325/1-skip,新 15 用例 |
| **C** SensitiveDataValidator(BE) | #3 | [#242](https://github.com/pinpols/file-batch-system/pull/242) | ✅ MERGED | `batch-common/.../security/SensitiveDataValidator.java`(116 行)+ i18n 2 文件 + atomic 4 executor 入口接入 + `DefaultWorkerRegistryService.java:119-122` register 路径拒入 + 21 用例 | 全部通过,5 测试文件;HTTP `auth` 子树豁免(协议字段),需 FE follow-up 警示 |
| **D** Worker fingerprint 端点 | #5 BE 部分 | [#240](https://github.com/pinpols/file-batch-system/pull/240) | ✅ MERGED | `ConsoleWorkerFingerprintController.java` + `WorkerFingerprintMapper.{java,xml}` + 2 Response record + OpenAPI `/api/console/workers/fingerprints[/summary]` 双端点 + protocol.md changelog | 794/794 全量绿;5 控制器测;`check-console-openapi-paths.py` 327 routes OK |
| **E1** docs/sdk/ 集中化 | #6 | [#238](https://github.com/pinpols/file-batch-system/pull/238) | ✅ MERGED | `docs/sdk/quickstart.md`(92 行)+ `docs/sdk/troubleshooting.md`(88 行)| — |
| **E2** wire-protocol 协议文档 | #6 续 | [#243](https://github.com/pinpols/file-batch-system/pull/243) | ⏳ OPEN(auto-merge armed) | `docs/sdk/wire-protocol.md`(161 行,双通道分工 + 12 故障矩阵 + 6 项时序约束)| — |
| **doc** 本报告本身 | — | [#237](https://github.com/pinpols/file-batch-system/pull/237) | ✅ MERGED | `docs/analysis/2026-06-02-sdk-atomic-fe-deep-review.md`(本文)| — |

### 8.1 关键超出预期发现(交付中浮现)

1. **Lane A1 早已实现**:`KafkaTaskConsumer.applyBackpressure()` 在历史 SDK Phase 1-3 P0 hardening 时就完整覆盖了 capacity-aware pause/resume(含 `paused` flag、rebalance re-pause、平台 directive 联动)。本批 Lane A 只补缺的聚焦测试,主体逻辑不动。**审查报告 §1.2 「P0 hardening 缺失」结论需修正为「未覆盖 Kafka SASL fail-fast」**。
2. **Lane B `TaskContext.isDryRun()` 之前不存在**:agent 顺手在 `batch-common` 加默认方法,SPI 兼容改动,现有 record 构造点零改。原审查报告假设 ctx 已有该方法,实际本批才补齐。
3. **Lane C atomic executor 入口注入未冲突 Lane B**:GitHub 自动 rebase 把 Lane B 的 dry-run 短路 + Lane C 的 validator 调用合并干净,两段都进了 `execute()` 顶部。
4. **Lane C 凭据关键字 `token` 过宽**:可能误报 `csrf_token` / `idempotency_token` 等协议字段;HTTP executor 已显式豁免 `auth.password / auth.token` 子树(否则全部 bearer 任务挂)。需 ADR follow-up 把 HTTP auth 改成 `auth.envRef: "MY_TOKEN"` 风格 secret reference,彻底干掉 payload 明文凭据。
5. **Lane D worker_registry 实际字段差异**:`heartbeat_at`(非 `last_heartbeat_at`)、`process_id`(非 `pid`)、status 枚举 `ONLINE/OFFLINE/DRAINING/DECOMMISSIONED`(无 `ACTIVE`)— 实现以 schema 为准。
6. **多 agent 并发 worktree 切换**:agents 在同一仓的不同 worktree 干活时,主 worktree 的 HEAD 被频繁切到各 lane 分支,工作树会混入其他 lane 未提交改动。各 agent 按 file 名 stage + 全 refspec push 避免了串污;PR diff 全部清洁。

### 8.2 未在本批做的(留 follow-up)

| 改进 | 原因 |
|---|---|
| **TOP #4** executor↔console schema CI 漂移守护 | 本批未排入 5 lane(避免单批超额) |
| **TOP #5** FE 看板 | BE 端点(Lane D)已就位;FE 页面单独 follow-up |
| **TOP #7** e2e 分级 + Playwright 分片 | FE 仓独立 lane,本批 BE-only |
| **TOP #8** B.2 atomic 节点可保存配置 | 需先做 schema 设计(workflow_node 接 or 新表) |
| **TOP #9** SDK↔BE 集成测 + K8s 验收 | 跨多模块 + 需要 docker compose,工作量大单独排 |
| **TOP #10** FE customTaskTypes 类型双源消化 | 依赖 BE OpenAPI 完善 customTaskTypes response schema 后做 |
| **Lane C FE 部分** SensitiveFieldAlert 通用组件 | FE 仓独立 follow-up |
| **Lane C 凭据 validator 落 atomic executor 入口** | Lane B 与 Lane C 已合并,无需再做 — GitHub auto-rebase 已合两段 |

### 8.3 评分回望

| 板块 | 原评分(2026-06-02 上午)| 本批后(2026-06-02 下午)|
|---|---|---|
| SDK 自托管 | 4.0/5 | **4.3/5**(+ stop timeout + Kafka pause 测试 + 文档三连击) |
| Atomic Worker | 3.8/5 | **4.2/5**(+ dry-run 感知 + prod fail-closed 启动断言 + 凭据 register 路径拦截) |
| 前端 batch-console | 3.8/5 | **3.8/5**(本批 BE-only,FE 仓未动)|
| **三角综合** | 3.9/5 | **4.1/5**(向 4.5 目标推进约 60%) |

---

## 附:本报告调研方法

并行三个 Explore agent,分别针对 SDK 自托管 / Atomic Worker / 前端做独立深度审查(各 2500-3000 字、各自带 7-8 维度评分 + file:line 引用 + TOP 5 改进项),由主进程合成并去重 TOP 15 → TOP 10。

**调研所触文件主要范围**:
- SDK:`batch-worker-sdk/`、`batch-worker-sdk-spring-boot-starter/`、`batch-worker-sdk-testkit/`、`examples/sample-tenant-worker*/`、`db/migration/V159-V163`、`docs/architecture/adr/ADR-035-*`
- Atomic:`batch-worker-atomic/`、`batch-console-api/.../ConsoleAtomicTaskType*`、`docs/adr/029-*`
- FE:`../batch-console/src/{api,views,views-mobile,components,router,locales}/`、`../batch-console/CLAUDE.md`、`docs/plans/fe-worklist-2026-h2-atomic-sdk.md`
