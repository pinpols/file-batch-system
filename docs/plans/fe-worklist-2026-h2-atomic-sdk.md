# FE 工作清单 — Atomic + SDK(2026 H2)

> **用途**:把散在 [`sdk-roadmap-2026-h2.md`](sdk-roadmap-2026-h2.md) 多处的前端待办**集中**成一份可独立排期的 mini-sprint 清单,并**补上** roadmap 未覆盖的 atomic 内置四类(sql / shell / stored_proc / http)配置 UI。
>
> **维护规则**:本文件是**汇总视图**,BE/协议细节仍以 roadmap 各 phase 节为权威源(本文给指针,不复制实现细节)。roadmap 对应节有更新 → 同步本文状态列。
>
> **总原则**(承自 roadmap §1):🔵 FE 工作默认**延后**到对应 BE 完成验证后启动,**不阻塞 SDK 发版**;延后期用 Swagger UI / SQL 临时替代(见 §4)。第一个真实运营需求出现时,作为独立 mini-sprint 一次性排进(全部合计 ~1.5-2 周)。

---

## 0. 一页全景

| 区块 | 来源 | 触发条件 | 工作量 | BE 依赖 |
|---|---|---|---|---|
| **A. SDK 自定义 taskType** | roadmap §5.2 / §7.2 | 第一个租户运营提需求 | ~6d | ✅ 全就绪(#207–#213) |
| **B. Atomic 内置四类配置 UI** | 本文新增(roadmap 未覆盖) | 运营要在 console 配 sql/shell/http/proc 节点 | ~4-5d | ⚠️ 需补 1 个 schema endpoint |
| **C. 长任务可观测** | roadmap §6.1 4.6 | 长任务真实跑起来 + 运维提需求 | ~1d | ✅ 就绪(#215–#218) |

> 🔵 = 默认延后 · ✅ = BE 已就绪可起 FE · ⚠️ = 起 FE 前需先补一小段 BE

---

## 1. 内置 vs 自托管 — FE 边界(贯穿全文的设计前提)

> 系统现在同时有**平台内置**执行(atomic 四类,跑在平台 `batch-worker-atomic`)和**自托管**执行(SDK 自定义 taskType,跑在租户自己进程)。两者在前端**不能拉平成一个无差别节点列表**,也**不必两套完全独立重写** —— 分三层处理。

### 1.1 两类本质差异(决定 FE 怎么呈现)

| 维度 | 内置四类(平台跑) | 自托管 taskType(租户进程跑) |
|---|---|---|
| 谁执行 | 平台 `batch-worker-atomic` | 租户自己的 worker 进程 |
| schema 谁定 | 平台固定(4 类写死) | 租户注册的 descriptor |
| 安全闸 | 平台配的白/黑名单,租户**改不了**(只读提示) | 租户自负,凭据走 env |
| 生命周期 | 永远在,无注册 / 版本概念 | 要注册 / 版本切换 / 灰度 |
| 健康度 | 平台 worker 健康(运维管) | 租户 worker fingerprint(租户自己看) |
| 坏了找谁 | 平台运维 | 租户自己 |

### 1.2 三层分法

- **✅ 共享 —— 只共享最底层的「JSON Schema → 表单」渲染器。** 两边都是 schema 驱动的参数表单,一个组件、两个 schema 源(内置走 `GET /console/atomic-task-types/schema`,自托管走 registry descriptor)。这层同构,不写两遍。
- **🚫 分开 —— 目录 / 生命周期 / 信任表达,三层都要区分:**
  - **节点面板(工作流编辑器)**:分两组「平台内置」vs「我的 taskType」,每节点带 badge 标清**在哪执行**。绝不拉平成单列表 —— 用户得一眼知道"跑在平台还是我自己进程",否则出事不知找谁、日志去哪。
  - **发现页 / 版本切换 / 健康度看板(A.4-A.6)**:**只给自托管**。内置四类没有"注册 / 版本 / 我的 worker"概念,给这些页面无意义。
  - **安全 UX**:内置 = 展示平台闸(**只读**,"白名单要找平台管理员加");自托管 = "你自己负责,凭据别填表单、走 env"。

> 一句话:**渲染器同构,目录 / 生命周期 / 信任分治。** 下面 A 区(自托管)、B 区(内置)的拆分即按此边界,§5 复用建议同此口径。

---

## A. SDK 自定义 taskType 前端

> 权威源:roadmap §5.2(M3.2 注册页基础)+ §7.2(接入旅程 5.F.*)。BE 全链已完成:`custom_task_type_registry` 表(#207)、register upsert + 派单合并(#209/#210)、`effective_parameters`(#211)、`/console/custom-task-types/*`(#212)、SDK descriptor 上报(#208)。**FE 是唯一未做的一环。**

| # | 页面 / 组件 | 用户故事 | 工作量 | 读什么 BE |
|---|---|---|---|---|
| A.1 | **「我的 taskType」列表 / 详情页** | 租户开发者看自己注册的 + 平台预置的 taskType,每个看 descriptor(输入契约 / 版本 / buildId) | 1d | `GET /console/custom-task-types/{tenantId}` (#212) |
| A.2 | **工作流编辑器:自定义节点按 schema 渲染表单** | 拖自定义节点 → 读 descriptor 的 JSON Schema → 自动出参数表单 + 必填校验 | 2d | descriptor schema(registry) |
| A.3 | **模板变量补全** | 表单值支持 `${bizDate}` / `${trigger.fireTime}` 等补全 + 悬浮说明 | 1d | 变量目录(文档/常量) |
| A.4 | **TaskType 发现页**(§7.2 5.F.2) | 看自己注册 + 平台预置,每个 type 一键 copy handler 骨架 code-block + 跳 quickstart | 1.5d | registry + 静态骨架模板 |
| A.5 | **重载 / 版本切换页**(§7.2 5.F.3) | `_v1`→`_v2` 灰度规则可视化(按 tenantId / buildId)+ 切换确认 + 一键回滚 | 2d | registry 版本字段 |
| A.6 | **Worker 健康度看板**(§7.2 5.F.4) | 看本租户 worker fingerprint(buildId / pid / sdkVersion)+ in-flight / 错误率 / consumer lag | 2d | fingerprint(#220)+ metrics 快照 |

> **关键纪律**(承 roadmap §5.5):敏感凭据(DB 密码 / OAuth secret)**禁止**走 parameters 表单 —— A.2 表单遇到疑似凭据字段须给硬警告,引导走 env。
>
> **排期建议**:A.1 + A.2 + A.3 是「能用」的最小闭环(注册页规划内,~4d);A.4-A.6 是「好用」增强(发现 / 版本 / 健康度,§7.2 明确排到 H2 末尾或 H1 2027),按需再上。

---

## B. Atomic 内置四类配置 UI(本文新增)

> **背景**:平台 `batch-worker-atomic` 提供 4 个内置原子执行器(sql / shell / stored_proc / http,ADR-029)。它们**不走**自定义 taskType registry(A 区),而是平台预置 —— 所以 A.2 的「按 descriptor schema 渲染」覆盖不到它们。运营要在工作流编辑器里配这 4 类节点,需要 FE 内建对应的参数表单 + 安全闸提示。
>
> **⚠️ BE 前置(起 FE 前必做一小段)**:内置四类的参数 schema 目前只散在各 `*TaskExecutor.java` 的 `PARAM_*` 常量 + `*ExecutorProperties.java` 的安全字段里,**没有对外 endpoint**。两条路二选一:
> - **(推荐)** console-api 加 `GET /console/atomic-task-types/schema` 返回四类的字段 schema + 安全闸说明(单一权威源,FE 不硬编码、不漂移);约 0.5d BE。
> - **(临时)** FE 按下方表硬编码四类表单,接受「executor 改字段→FE 手动跟」的漂移风险。

### B.1 各 type 参数表单(字段取自当前 executor 实现)

| taskType | 必填 | 可选 | 表单要点 |
|---|---|---|---|
| **`sql`** | `sql`(多行 SQL 编辑器,非空) | `dataSourceBean` · `statementTimeoutSeconds` · `autoCommit` | SQL 代码框 + 语法高亮;`dataSourceBean` 走下拉(取 `allowedDataSourceBeans` 白名单,空=仅 dev) |
| **`stored_proc`** | `procedureName` | `inParams`(有序列表) · `outParams`(SQL 类型列表) · `statementTimeoutSeconds` · `dataSourceBean` · `autoCommit` | `procedureName` 受 `allowedSchemas` 限定(默认 schema=`batch`);in/out 参用动态行编辑器 |
| **`shell`** | `command` | `args`(字符串列表) · `timeoutSeconds` · `env`(key→value) | `command` 须在 `commandWhitelist` 内(**默认空 = 全禁**,UI 要明确提示「未配白名单则该节点不可用」);`env` key 只透传 `allowedEnvKeys` 名单内 |
| **`http`** | `url` | `method`(默认 GET) · `headers`(map) · `body` · `timeoutSeconds` · `expectStatus` · `auth` | `method` 走 `allowedMethods` 下拉;`url` host 受 `blockedHostPatterns`(SSRF,默认拒 metadata/localhost)+ `allowedHostPatterns` 双闸校验 |

### B.2 安全闸可视化(四类共性)

UI 在配置 / 保存这 4 类节点时,要把后端安全闸**前置可见**,而不是等运行期报错:

| 闸 | 涉及 type | FE 呈现 |
|---|---|---|
| **executor 总开关 `enabled`** | 全部(`shell` 默认 **false**;sql/proc/http 默认 true) | type 下拉里禁用未启用的;`shell` 旁标注「需平台开启」 |
| **命令 / host / schema 白名单** | shell `commandWhitelist` · http `allowedHostPatterns` · proc `allowedSchemas` | 表单实时校验 + 越界即时红字,不让保存 |
| **黑名单(SSRF)** | http `blockedHostPatterns` | 命中 metadata/localhost 直接拦 + 说明 |
| **资源限额** | timeout / maxStdoutBytes / maxResponseBytes | 输入框带上限提示(超过后端会截断/杀进程) |
| **`forbidOsCapableRole`** | sql / stored_proc | 提示「该 DB 角色禁带 OS 能力」,凭据走 env 不走表单 |

### B.3 落地拆分(建议)

| # | 内容 | 工作量 |
|---|---|---|
| B.1 | (BE 推荐项)`GET /console/atomic-task-types/schema` endpoint | 0.5d |
| B.2 | 四类节点参数表单组件(schema 驱动 or 硬编码) | 2d |
| B.3 | 安全闸校验 + 越界提示(白/黑名单、限额、enabled 态) | 1.5d |
| B.4 | sql / shell 专用编辑体验(SQL 高亮、命令白名单选择器) | 1d |

---

## C. 长任务可观测前端

> 权威源:roadmap §6.1 任务 4.6 / §6.4。BE 全就绪:heartbeat-with-details + cancel push(#215）、taskTimeout（#216）、`isCancelled()`（#217）、lease revoked + details API（#218）。

| # | 页面 | 用户故事 | 工作量 | 读什么 BE |
|---|---|---|---|---|
| C.1 | **任务详情页 heartbeat details** | 长任务跑动时,每 ~10s 看到一次进度 / checkpoint 更新(进度条 + details JSON) | 1d | `task` + `task_progress` 表最新 details |

> **决策点**(roadmap §6.2):若租户任务都 < 5min,本区可砍 —— progress 详情 UI 非必达,cancel + timeout(纯 BE)才是必做。

---

## 4. FE 延后期 workaround(承 roadmap §10)

延后不影响 SDK 发版;延后期运营用以下临时替代,体验差但能跑:

| 缺的 FE 能力 | 临时替代 |
|---|---|
| A.1「我的 taskType」列表 | console-api Swagger UI / SQL 查 `custom_task_type_registry` |
| A.2 工作流编辑器按 schema 渲染 | OpenAPI / Swagger UI / 直接 POST 派单 |
| A.3 模板变量补全 | 文档列出可用变量,人工填写 |
| B 内置四类配置 | Swagger UI 直填 `parameters`(参各 `*TaskExecutor` javadoc 的「parameters 协议」段) |
| C.1 任务详情进度 | `task` / `task_progress` 表 SQL 查最新 details |

---

## 5. 合计与排期

| 区块 | 最小闭环 | 完整版 | 触发 |
|---|---|---|---|
| A. SDK 自定义 taskType | A.1-A.3 ~4d | + A.4-A.6 ~5.5d | 首个租户运营需求 |
| B. Atomic 内置四类 | B.1-B.3 ~4d | + B.4 ~1d | 运营要 console 配原子节点 |
| C. 长任务可观测 | C.1 ~1d | — | 长任务 + 运维需求 |

**最小可用 mini-sprint**(A 闭环 + B 闭环 + C)≈ **9d / ~2 周(单人)**;A.4-A.6 运营增强按真实需求单排。

> 起 FE 前先确认:① B 区是否先补 schema endpoint(推荐,见 §B);② A.2 与 B.2 的复用边界**按 §1.2 三层分法**——只共享底层「JSON Schema → 表单」渲染器(自托管走 registry descriptor,内置走 atomic schema endpoint);节点目录、生命周期页、安全 UX **不复用、按内置/自托管分治**,别拉平成一个无差别列表。
