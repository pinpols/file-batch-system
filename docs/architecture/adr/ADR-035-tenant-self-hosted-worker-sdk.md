# ADR-035 · 平台定位:纯调度面 + 租户自托管 Worker SDK

- **Status**: Proposed(2026-05-31)
- **Date**: 2026-05-31
- **Related**: ADR-029 dedicated SPI worker / `docs/design/task-spi-design.md` / ADR-034 CAP 定位
- **Supersedes**: 部分修订 task-spi-design.md §Phase 4「示范第三方扩展」的"jar 入 classpath"形态
- **Plan**: `docs/plans/multi-tenant-isolation-plan-2026-05-31.md` §Phase B

## 背景

当前架构内 SPI 扩展形态是**部署期 jar 插件**:

- 业务方写 `BatchTaskExecutor` 实现 → 打成 jar → **由平台运维**把 jar 丢进 worker 的 `lib/` → 重启 worker → 新 taskType 生效
- 信任边界 = 平台运维审过的代码。租户没法自己加新 taskType,也没法把自己业务 worker 进程脱离平台 SLO 运营

这种形态默认假设「平台 = 全栈托管(调度 + 执行)」,但实际两方面诉求都做不好:

1. **租户视角**:想跑自己的业务逻辑(连自己 DB / 自有依赖 / 自己语言栈),只能让平台代部署,审核流程长
2. **平台视角**:跑租户代码 → 必须 sandbox / IAM / 计量 / blast radius 控制,工作量是「再造半个 FaaS」(参 ADR-029 顶部「不做通用 job 平台」红线)

## 决策

**正式把平台定位收敛为「纯调度面」,租户 worker 走 SDK 自托管**:

| 层 | 归属 | 职责 |
|---|---|---|
| **调度面**(平台) | `batch-trigger` + `batch-orchestrator` + `batch-console-api` | DB 状态主机 / Outbox / Kafka 派单 / 接 REPORT / 可观测 |
| **执行面**(平台) | `batch-worker-{import,export,process,dispatch,spi}` | 平台代运维的内建 worker(给"不想自管"的租户用) |
| **执行面**(租户)| 租户进程,引 `batch-worker-sdk` | 租户自己跑业务,只通过 SDK 调平台 HTTP + Kafka 接平台调度 |

**核心契约**:`HTTP /internal/*` + `Kafka batch.task.dispatch.*` topic 格式。SDK 只是 Java 友好封装,任何语言照协议都能接入。

## 关键设计

### 1. 新模块 `batch-worker-sdk`

从 `batch-common` 剥出**只含**:

- `BatchTaskExecutor` 接口 + `TaskContext` / `TaskResult` DTO(已在 `batch-common.spi.task.*`)
- HTTP client(register / heartbeat / claim / report,带 API key auth header)
- Kafka consumer wrapper(SASL 凭据 + topic 配置)
- 基本 logging / 重试退避

**严格不含**:MyBatis / Flyway / Redis / OTel / Spring Boot 服务端组件。目标:`batch-worker-sdk` jar < 2 MB,只引 jackson + http client + kafka-client。

### 2. 平台侧改造

**鉴权**(P2 落地 — 双轨 filter `InternalAuthFilter`,实现见 batch-orchestrator):
- 路径 1 `X-Batch-Api-Key + X-Batch-Tenant-Id`:租户自托管 worker;查 `batch.api_key` 表(**复用**现有表 + `ConsoleApiKeyService` 写路径,**不另建 `service_principal` 表**),SHA-256 hash + tenantId 双匹配 + enabled + 未过期 + 未 revoke
- 路径 2 `X-Internal-Secret`(legacy):主项目可信 worker / orchestrator 互调
- API Key 提供但校验失败 → 401,**不 fallback** secret(防 key 泄漏后冒充)
- 校验成功写 `request.attribute("batch.auth.resolvedTenantId")` 让 controller 防租户冒充
- API Key scope 校验:老 key `scopes='*'` 通配通过;新 key 必须显式包含 `worker.execute` 才放行 `/internal/workers/*` 与 `/internal/tasks/*`(由 controller-side 检查或 filter 扩展)

**Kafka ACL**(P3 落地):
- topic 命名(权威): `batch.task.dispatch.{type}.{tenantId}`(对齐 orchestrator `BatchTopicResolver` + `init-tenant-topics.sh` + `per-tenant-worker-onboarding.md` §1;早期草稿写过 `batch.task.dispatch.tenant.{tenantId}.{type}` 是笔误)
- Kafka SASL/SCRAM per-tenant 凭据 + ACL 强制(只 consume 自己 tenant topic)
- 自托管 onboarding 工具:`scripts/data/init-tenant-kafka-acl.sh`(创 SCRAM user + 授该 tenant topic / consumer group READ + DESCRIBE)

**租户 worker 注册**:
- `POST /internal/workers/register`(SDK 启动期调,带 API key + tenant_id);body 对齐 `WorkerHeartbeatDto`
- `POST /internal/workers/{workerCode}/heartbeat`(SDK 后台线程,30s);body 对齐 `WorkerHeartbeatDto` + `currentLoad = dispatcher.inFlightCount()`
- `POST /internal/workers/{workerCode}/deactivate`(SDK `stop()` 时优雅下线;失败 swallow)
- `worker_registry` 表加 `is_self_hosted boolean default false` — SDK register 带 `isSelfHosted=true`,console "我的 Worker" 按此过滤
- console「我的 Worker」页:租户管理员看自己 worker 健康度 / 任务量 / API key 自助轮转

### 3. 安全模型 — 平台不跑租户代码

**关键安全好处**:平台进程不加载租户代码 → 不需要 sandbox(gVisor / seccomp / WASM)。

- 租户代码跑在租户自己机器 → blast radius 在租户侧
- 平台只校验 API key + ACL,所有租户输入(REPORT body / heartbeat metric)按 untrusted 处理(JSON schema 校验)
- ADR-029 的 `batch-worker-atomic`(SQL/StoredProc/HTTP **3 件套**)定位不变 — 仍是「平台代部署的内建 dual-use worker」;**shell 不在平台内建集内**(理由见 §8)

### 4. 范围边界(不做)

- ❌ **不下放 DB schema**:租户 worker 不连平台 DB
- ❌ **不下放 outbox 写权限**:租户 worker 不直接发 Kafka 业务事件,只通过 REPORT 回报(平台代发)
- ❌ **不下放 orchestrator 状态机**:租户 worker 只能 CLAIM 自己被派发的任务、REPORT 自己任务结果
- ❌ **不做 sandbox / 计量 / billing**:平台不跑租户代码就不需要;billing 留 P2 评估
- ❌ **不做多语言 SDK 一期**:先 Java SDK + 协议文档,Python/Go 用户照协议自实现

### 5. 内建 worker 不退役

`batch-worker-{import,export,process,dispatch,spi}` 5 个内建 worker 继续提供「平台代运维」选项 — 是默认形态,不是要被替代的。

| 形态 | 适用 |
|---|---|
| 平台代运维(内建 worker)| 标准文件处理 / SPI 3 件套(SQL/StoredProc/HTTP)够用、不想自管 |
| 租户自托管(SDK worker)| 业务逻辑复杂、跨语言、连自有 DB、有运维能力 |

### 6. 与内建 Pipeline(IMPORT / EXPORT / PROCESS / DISPATCH)的边界

「租户能不能用 IMPORT」分三条路径,**默认走路径 1,不要为了'下放'而让租户自己重造 pipeline**:

**路径 1:租户用平台 IMPORT(默认 / 主流场景)**
- 租户在 console 配 `file_template_config` / `file_channel_config` / `job_definition.job_type=IMPORT`
- 平台内建 import worker 跑完整 5-stage(SCAN→PREPROCESS→LOAD→VERIFY→COMMIT)
- 拿到的:文件级 retry / 行级 reject / `batch_day` 追溯 / `file_record` 表全链路可观测
- **不需要 SDK,不在本 ADR 范围**

**路径 2:租户要定制 IMPORT 内部某一步(部署期 plugin,本 ADR 不改)**
- `batch-common.spi.plugin` 已暴露 stage-级 plugin SPI(`ImportLoadPlugin` / `ExportDataPlugin`)
- 仍是部署期 jar 形态(运维代部署),**不下放到租户自托管** — 钩子深入 pipeline 状态机 / `file_record` 写入,blast radius 在平台进程内,sandbox 风险跟 ADR-029 的 shell/proc 同级
- 租户深度定制 IMPORT 的需求 → 优先路径 1 + 路径 3 组合,不要走路径 2

**路径 3:租户完全自跑 IMPORT-like 业务(本 ADR 覆盖)**
- 整个 import 作为一个**黑盒 `BatchTaskExecutor`**:`taskType="acme_invoice_import"`,租户进程自己拉文件 / 解析 / 写自家 DB,平台只收 SUCCESS/FAILED + 元数据
- 失败 → SDK 内置退避或返 FAILED 走 workflow 补偿节点
- **权衡**:租户拿不到内建 pipeline 的 retry / batch_day 追溯 / 行级 reject 能力。要这些 → 回路径 1

**为什么不直接把 Pipeline SPI 也 SDK 化(Option C 否决)**
- `AbstractPipelineStepExecutionAdapter` 强绑 `file_record` / `pipeline_instance` / `pipeline_step_run` 三表写入,这些是平台状态主机的内部表
- 把这套 SDK 化等于把 orchestrator 的写权限下放给租户,直接破 CLAUDE.md §架构硬约束「orchestrator 唯一状态主机」
- 业界对照:GitHub Actions self-hosted runner 也只暴露 `run-step` API,不暴露 workflow 内部状态机

| 诉求 | 走哪条路径 |
|---|---|
| 用标准 SFTP/MinIO 导入到平台 staging 表 | 路径 1 |
| 文件格式很怪,要自己写解析器 | 路径 2(运维代部署 plugin)|
| 业务全跑自家 DB,不进平台 staging | 路径 3(SDK 自托管)|
| 需要平台 batch_day 追溯 + 自家逻辑 | 路径 1 主流程 + 路径 3 跟随节点(workflow 串)|

### 7. 「平台 worker × 租户 DB」混合模型 — 否决

**方案**:平台代部署 worker,但连每个租户自己的 business DB(需要租户 DBA grant 一个连接账号)。

**否决理由**:每接一个租户 = 一次完整 DBA 谈判(权限/网络/凭据/SLA/合规),凭据池/网络拓扑/schema 漂移/SQL 兼容/故障归因全是地狱。业界先例:Fivetran 走这条 — 有专门的 Solutions Engineer 帮租户跑 30+ 步配置。

**替代**:**B'(SDK + 平台发布标准 worker 镜像)**
- 平台发布 `batch-worker:1.0` 镜像(SDK 已编译进去)
- 租户在自家 K8s `helm install batch-worker --set platform=https://... --set api-key=xxx --set my-db=postgres://localhost`
- 凭据 / 网络 / 数据**全在租户内网**,**不需要租户 DBA grant**
- 平台还是「纯调度面 + 镜像供应者」

### 8. Shell 执行能力 — 平台内建但默认 OFF + 租户自托管为主

**结论**(2026-05-31 修订):`ShellTaskExecutor` **作为平台内建 SPI 提供**(在 `batch-worker-atomic.shell` 包),但 **默认完全关闭**(`batch.worker.executors.shell.enabled=false`,bean 不注册 / SPI registry 找不到 "shell" type)。需要在平台侧开 shell 的部署**必须显式 opt-in**并自行承担风险(下方"启用风险"清单)。租户跑 shell **首选自托管 SDK** 形态(§A/B)。

**启用风险**(opt-in 前必须了解):in-process shell 命令继承 worker **进程**身份 / 挂载卷 / ServiceAccount token,任何 app 层白名单都堵不住;多租户共享部署里一个租户的 shell 命令可触及其它租户数据 / 平台凭据。要在平台侧安全跑 shell 真正的隔离需要"一次性隔离 compute"(K8sPodOperator / 容器 per-task / microVM),撞 ADR-027「挑 worker √ vs 挑机器 ✗」边界。本系统平台域内常规任务由 SQL/StoredProc/HTTP + 文件 pipeline 覆盖,通常**无须** shell。

**安全加固**(`ShellTaskExecutor` 已实现):不走 shell 解释器(execve 直接调命令,无 injection);command 白名单 + arg 限制;workdir 隔离;输出截断;timeout 限制。

**前置条件**(开 shell 前):per-tenant worker pool(`batch-worker-atomic-{tenant}`)单独部署 + RBAC 隔离 + ServiceAccount 收窄 + 不挂任何共享 secret 卷;否则等价 RCE。

**为什么租户自托管下可以**:按本 ADR §3,租户代码跑在租户自己机器、blast radius 在租户侧,平台进程不加载它。所以"租户要 shell"= 租户在自家 SDK worker 里跑,平台不暴露。两种落地形态:

| 形态 | 做法 | 平台是否提供代码 |
|---|---|---|
| **A. 租户自写**(推荐 / 默认) | 租户照 `examples/sftp-push-executor` 写一个 `ProcessBuilder` executor,`taskType` 自取,放进自家 SDK worker | 否 —— 平台只给 SDK + 协议 |
| **B. 平台出 example shell executor** | 平台把 `ShellTaskExecutor` 作为 **SDK example/参考实现**发布,租户 opt-in 拷进自家 worker;**平台 `batch-worker-atomic` 永不启用它** | 是(仅 example,非平台内建) |

**软线**(原"红线"已 2026-05-31 翻转 — 用户决策):平台代运维的 `batch-worker-atomic` **默认不启用 shell**(`enabled=false`,bean 不注册),需要 opt-in 才生效;一旦 opt-in,部署侧必须按"启用风险"清单做 per-tenant pool + 收窄 RBAC,否则等价 RCE。租户跑 shell 的**默认推荐路径仍是自托管 SDK** worker(§A),平台内建仅为「特殊场景 opt-in」。

**当前代码现状**(2026-05-31):`batch-worker-atomic.shell.ShellTaskExecutor` 已存在并以 `@ConditionalOnProperty(batch.worker.executors.shell.enabled, havingValue="true")` 守门;`ShellExecutorProperties.enabled` 默认 false。本节修订后**保留代码不动**,文档红线翻转为软线 + 明确 opt-in 风险。

### 9. 两套绑定 + 一个 wire 协议:内部演进与漂移防护

**事实澄清(对着代码)**:平台和 SDK 用的是**两套平行 Java 契约,不是同一个类**:

- 平台侧:`BatchTaskExecutor` + `TaskContext`/`TaskResult`(`batch-common.spi.task`),被 **8 个内建 worker** 实现(sql/storedproc/http + import/export/process/dispatch)+ 示例 `sftp-push-executor`。
- SDK 侧:`SdkTaskHandler` + `SdkTaskContext`/`SdkTaskResult`(`batch-worker-sdk`),注释自述「`BatchTaskExecutor` 的 SDK 侧投影」。**SDK 不依赖 `batch-common`**(pom 显式排除,避免 MyBatis/Flyway/Redis 拖死租户进程)。

**真正的契约是 wire 协议**(Kafka 派单 payload + HTTP `/internal/*` register/heartbeat/claim/report 的 JSON 格式),`BatchTaskExecutor` 与 `SdkTaskHandler` 只是它的两套语言绑定 —— 与本 ADR §决策「核心契约 = HTTP+Kafka 协议,SDK 只是 Java 友好封装」一致。由此两条决策:

**(a) SPI 内部重构不碰 `BatchTaskExecutor` —— 理由是保护平台 8 个实现(不是 SDK)。**
`batch-worker-atomic` 的内部演进(生命周期钩子、连接池统一)一律落在 `batch-worker-atomic` 模块内,不改 `batch-common` 的 `BatchTaskExecutor`(含不加 `default` 方法)。改它的真实 blast radius 是**平台侧 8 个实现** + 示例,全部被迫跟改;SDK 因为不依赖它,不直接受影响。两个具体例子:

| 改动 | 落点 | 影响面 |
|---|---|---|
| **`AbstractBatchTaskExecutor` 基类**(模板方法:`validate → before → doExecute → after` + `finally` 保证 `cleanup`,给执行器自己的前置/结束步骤) | `batch-worker-atomic` 内,**只让 SPI 执行器 `extends`** | SPI-only;pipeline 的 `XxxTaskExecutor` 仍直接 `implements BatchTaskExecutor` 不动,**零影响** ✅ |
| **`SpiConnectionManager`**(统一 DataSource 解析 + `allowedDataSourceBeans` 闸 + 有界连接池策略 + 角色闸 + `withConnection` 回调式 acquire/release/rollback) | `batch-worker-atomic` 内,**只被 SPI 的 sql/storedproc 用** | SPI-only;pipeline 4 个 worker 各自管自己模块内的 DB 访问,**零影响** ✅ |

**(b) SDK ↔ 平台靠 wire 协议对齐,防两套绑定漂移。**
两套 Java 类各自演进,真正要守的是它们对**同一 wire payload** 的映射不分叉(否则同一 taskType 在平台 worker vs 租户 SDK worker 行为不一致)。约束:
- 派单 payload + REPORT body 的字段集是**协议级契约**,变更要同步改两侧绑定(`TaskContext`↔`SdkTaskContext`、`TaskResult`↔`SdkTaskResult` **字段保持兼容**)。
- 加**共享协议 fixture / 契约测试**(同一份 JSON 样本,两侧绑定各自反序列化 + 断言字段对齐),CI 跑,作为漂移的 fail-fast 守门。
- 协议字段优先**只增不改不删**(向后兼容);确需破坏性变更走显式协议版本号。

**红线**:
- 前置/结束能力用**基类模板方法钩子**(opt-in),**不**把它变回 pipeline 的多 stage 状态机 —— 那是 atomic task 与 pipeline 的分界线(见 `task-spi-design.md`)。
- 想让钩子对全体执行器强制生效而去接口加 `default` 方法 = 越过本节红线,**不允许**;要全局能力请在 SDK 侧另设扩展点,不改 `BatchTaskExecutor`。
- SPI 专用连接池要与平台 claim/lease 池**隔离**,避免 dual-use 执行占满平台调度自身的连接。

## 业界参照

| 系统 | 形态 |
|---|---|
| GitHub Actions self-hosted runner | central control plane + 用户 K8s/VM 自托管 runner |
| GitLab Runner | 同上,注册 token + 拉任务 |
| Jenkins agent | master 派单 + agent 自拉(JNLP / SSH) |
| Temporal Worker | 客户端 SDK + 长连接拉 task queue |

差异:Kafka 派单(异步 push)而非长连接 pull,更适合批量场景(consumer group rebalance 天然支持 worker 弹缩)。

## 实施分阶段

| Phase | 内容 | 状态(2026-05-31) | PR |
|---|---|---|---|
| **P1.1: SDK 起步** | `batch-worker-sdk` 子模块 + HTTP client + DTO + Builder | ✅ Merged | #164 |
| **P1.2: Kafka consumer + dispatcher** | Kafka topic Pattern subscribe + TaskDispatcher 线程池 + claim→exec→report | ✅ 待 merge | #165 |
| **P1.3: 心跳 + lease renewal** | HeartbeatScheduler(30s)+ LeaseRenewalScheduler(60s,< server TTL/2) | ✅ 待 merge | #165 |
| **P1.4: sample-tenant-worker E2E 示范** | 独立 reactor + echo/sleep handler + Shutdown hook | ✅ 待 merge | #165 |
| **P1.5: 协议对齐 + 契约测试** | SDK 路径/body 字段对齐真 BE DTO + SdkPlatformContractTest 守门 | ✅ 待 merge | #167 |
| **P2: 鉴权** | `InternalAuthFilter` 双路径(API Key + legacy secret)+ ApiKeyVerifier;**复用** `batch.api_key`(不另建 service_principal) | ✅ 待 merge | #166 |
| **P3: Kafka ACL** | SDK SASL/SCRAM config 字段 + `init-tenant-kafka-acl.sh` + onboarding runbook §5.1 | ✅ 待 merge | #167 |
| **P4: console 我的 Worker** | BE:`is_self_hosted` 列 + GET /api/console/my-workers;FE:列表页 + API key 自助轮转 | 🚧 BE 进行中,FE 未开始 | TBD |
| **P5: 接入文档** | 独立 GitHub repo template + 快速接入指南 + 协议参考 | 半成 — sample-tenant-worker README + per-tenant-worker-onboarding §5.1 已写;独立 repo template 未做 | — |
| **P6: 端到端示例** | sample worker 跑通真实派单 → REPORT 全链路实测 | 🚧 sample 进程已可 build,真实派单实测未跑(需 K8s 环境) | — |
| **P7(可选)** | Python SDK | 视需求 | ❌ |
| **P8(可选)** | per-tenant 计量 / billing | P2 评估 | ❌ |

**总量 ≈ 8-10 周**(P1-P6 必做)。当前进度 ≈ P1-P3 代码完成、P4 进行中。

## 兼容性 / 风险

| 风险 | 缓解 |
|---|---|
| 现有 `/internal/*` 信任内网,加 API key 会断开旧 worker | 双轨期:已注册的内建 worker 仍走 internal IP allowlist,新自托管走 API key |
| `batch-common` 拆 SDK 影响下游 | SDK 只剥客户端薄壳,`batch-common` 本身不变 |
| Kafka ACL 改造影响现有部署 | 现有 4 内建 worker 仍用旧 topic 名,通过 ACL 配 default-tenant 维持兼容 |
| 租户 worker 滥用 REPORT 投毒 | REPORT body 已有 JSON schema 校验,加每 worker_code 速率限制 |
| 文档 / sample 维护负担 | 接入 repo 独立仓库,版本号跟 `batch-worker-sdk` 对齐;CI 测 sample 编译通过即可 |

## 验收

- [x] `batch-worker-sdk` jar published(本地 Maven repo;jar size 34K,目标 < 2MB)
- [ ] 一个 sample 租户 worker 进程能注册、消费、REPORT、出现在「我的 Worker」页(待 K8s 环境实测)
- [x] `InternalAuthFilter` 支持 API Key 路径(P2 完成);全局强制启用待 P4 切换
- [ ] Kafka per-tenant topic + SASL/ACL 实测一个租户不能消费另一个租户 topic(脚本就绪,待 K8s 实测)
- [x] 文档:`examples/sample-tenant-worker/README` + `docs/runbook/per-tenant-worker-onboarding.md` §5.1;独立 `docs/runbook/tenant-self-hosted-worker.md` TBD

## 实施记录:协议字段细节

### Kafka 派单 payload (`TaskDispatchMessage`)

```json
{
  "taskId": 12345,
  "tenantId": "acme",
  "jobCode": "daily-report",
  "taskType": "echo",
  "taskInstanceId": "ti-789",
  "parameters": { "bizDate": "2026-05-31" },
  "runtimeAttributes": { "traceId": "abc", "partitionInvocationId": "inv-1" }
}
```

SDK 侧 `TaskDispatchMessage` 用 `@JsonIgnoreProperties(ignoreUnknown=true)` 容忍平台后续新增字段。

### HTTP body schema(关键字段 — 完整字段见 BE DTO)

| Endpoint | Body 对齐的 DTO | 必填字段 |
|---|---|---|
| `POST /internal/workers/register` | `WorkerHeartbeatDto` | tenantId / workerCode / status / heartbeatAt / currentLoad |
| `POST /internal/workers/{workerCode}/heartbeat` | `WorkerHeartbeatDto` | 同上 |
| `POST /internal/workers/{workerCode}/deactivate` | `WorkerHeartbeatDto` | tenantId / workerCode / status="OFFLINE" |
| `POST /internal/tasks/{taskId}/claim` | `TaskClaimRequest` | tenantId / workerId / partitionInvocationId(可选) |
| `POST /internal/tasks/{taskId}/renew` | `TaskClaimRequest` | 同上 |
| `POST /internal/tasks/{taskId}/report` | `TaskExecutionReportDto` | taskId / tenantId / workerId / success / message / outputs(注意是复数,不是 output)/ errorCode / resultSummary |

### Wire-protocol 契约守门

`batch-worker-sdk/src/test/.../dispatcher/SdkPlatformContractTest`:fail-fast 守门测试,任何 BE DTO 字段重命名 / 删除会让本测试爆。**改 BE DTO 必须同步改 SDK + 契约测试**(ADR §9 两套绑定一个 wire 协议)。

### workerId vs workerCode

P1-P3 阶段:`workerId == workerCode`(SDK 默认行为)。P4 后由 server 在 register 响应里分配独立 `workerId`(代理 workerCode + tenant + 注册时戳生成),SDK 接到后用 server 分配的值。本切换计划见 P4。

### Scheduler 节奏(默认)

- Heartbeat: 30s(对齐 server missed-heartbeat 阈值通常 90s)
- Lease renewal: 60s(< server lease TTL 默认 ~3min 的 1/2)
- Kafka poll: 200ms
- HTTP timeout: 10s

均可通过 `BatchPlatformClientConfig` 覆盖。

## 关联文档

- Plan: `docs/plans/multi-tenant-isolation-plan-2026-05-31.md`(本 ADR 是 Phase B 的详细设计)
- ADR-029 dedicated SPI worker:不变,内建 SPI worker 仍是「平台代部署」选项
- `docs/design/task-spi-design.md` §Phase 4:本 ADR **替换**该节"jar 入 classpath"形态,改为「SDK 自托管 worker」
- ADR-034 CAP 定位:平台状态机仍 CP,租户 worker 是「stateless executor」不影响 CAP 边界
- CLAUDE.md §架构硬约束:本 ADR 不破任何硬约束(主链不变 / orchestrator 唯一状态主机 / worker 必须 CLAIM / outbox 同事务 — 全保留)
