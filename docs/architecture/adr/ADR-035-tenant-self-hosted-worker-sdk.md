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

**核心契约**:`HTTP /api/internal/*` + `Kafka batch.task.dispatch.*` topic 格式。SDK 只是 Java 友好封装,任何语言照协议都能接入。

## 关键设计

### 1. 新模块 `batch-worker-sdk`

从 `batch-common` 剥出**只含**:

- `BatchTaskExecutor` 接口 + `TaskContext` / `TaskResult` DTO(已在 `batch-common.spi.task.*`)
- HTTP client(register / heartbeat / claim / report,带 API key auth header)
- Kafka consumer wrapper(SASL 凭据 + topic 配置)
- 基本 logging / 重试退避

**严格不含**:MyBatis / Flyway / Redis / OTel / Spring Boot 服务端组件。目标:`batch-worker-sdk` jar < 2 MB,只引 jackson + http client + kafka-client。

### 2. 平台侧改造

**鉴权**:
- `/internal/*` API 强制 `X-Batch-Api-Key` header(双轨期内建 worker 走 IP allowlist)
- 新表 `service_principal`:`(id, tenant_id, key_hash, worker_code_whitelist, status, created_at, last_used_at)`
- `ServicePrincipalAuthFilter` 拦截

**Kafka ACL**:
- topic 命名加 tenant 维度:`batch.task.dispatch.tenant.{tenantId}.{worker-type}`
- Kafka SASL/SCRAM per-tenant 凭据 + ACL 强制(只 consume 自己 tenant topic)

**租户 worker 注册**:
- `POST /api/internal/workers/register`(SDK 启动期调,带 API key + tenant_id)
- `POST /api/internal/workers/{code}/heartbeat`(后台线程)
- `worker` 表加 `is_self_hosted` 字段
- console「我的 Worker」页:租户管理员看自己 worker 健康度 / 任务量 / API key 自助轮转

### 3. 安全模型 — 平台不跑租户代码

**关键安全好处**:平台进程不加载租户代码 → 不需要 sandbox(gVisor / seccomp / WASM)。

- 租户代码跑在租户自己机器 → blast radius 在租户侧
- 平台只校验 API key + ACL,所有租户输入(REPORT body / heartbeat metric)按 untrusted 处理(JSON schema 校验)
- ADR-029 的 `batch-worker-spi`(shell/sql/proc/http)定位不变 — 仍是「平台代部署的内建 dual-use worker」

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

## 业界参照

| 系统 | 形态 |
|---|---|
| GitHub Actions self-hosted runner | central control plane + 用户 K8s/VM 自托管 runner |
| GitLab Runner | 同上,注册 token + 拉任务 |
| Jenkins agent | master 派单 + agent 自拉(JNLP / SSH) |
| Temporal Worker | 客户端 SDK + 长连接拉 task queue |

差异:Kafka 派单(异步 push)而非长连接 pull,更适合批量场景(consumer group rebalance 天然支持 worker 弹缩)。

## 实施分阶段

| Phase | 内容 | 估时 | 必须 |
|---|---|---|---|
| **P1: SDK 起步** | 抽 `batch-worker-sdk` 子模块 + HTTP client + Kafka consumer wrapper + sample worker | 2 周 | ✅ |
| **P2: 鉴权** | `service_principal` 表 + API key 签发 + `ServicePrincipalAuthFilter` | 2 周 | ✅ |
| **P3: Kafka ACL** | per-tenant topic 命名 + SASL/SCRAM 凭据 + ACL 模板 | 1-2 周 | ✅ |
| **P4: console 我的 Worker** | 注册接口 + 列表页 + 健康监控 + API key 自助轮转 | 2 周 | ✅ |
| **P5: 接入文档** | 独立 GitHub repo template + 快速接入指南 + 协议参考 | 1 周 | ✅ |
| **P6: 端到端示例** | sample 租户 worker 跑通真实派单 → REPORT 全链路 | 1 周 | ✅ |
| **P7(可选)** | Python SDK | 视需求 | ❌ |
| **P8(可选)** | per-tenant 计量 / billing | P2 评估 | ❌ |

**总量 ≈ 8-10 周**(P1-P6 必做)。

## 兼容性 / 风险

| 风险 | 缓解 |
|---|---|
| 现有 `/internal/*` 信任内网,加 API key 会断开旧 worker | 双轨期:已注册的内建 worker 仍走 internal IP allowlist,新自托管走 API key |
| `batch-common` 拆 SDK 影响下游 | SDK 只剥客户端薄壳,`batch-common` 本身不变 |
| Kafka ACL 改造影响现有部署 | 现有 4 内建 worker 仍用旧 topic 名,通过 ACL 配 default-tenant 维持兼容 |
| 租户 worker 滥用 REPORT 投毒 | REPORT body 已有 JSON schema 校验,加每 worker_code 速率限制 |
| 文档 / sample 维护负担 | 接入 repo 独立仓库,版本号跟 `batch-worker-sdk` 对齐;CI 测 sample 编译通过即可 |

## 验收

- [ ] `batch-worker-sdk` jar published(本地 Maven repo)
- [ ] 一个 sample 租户 worker 进程能注册、消费、REPORT、出现在「我的 Worker」页
- [ ] API key 鉴权对 `/internal/*` 强制启用,无 key 返 401
- [ ] Kafka per-tenant topic + SASL/ACL 实测一个租户不能消费另一个租户 topic
- [ ] 文档:`docs/runbook/tenant-self-hosted-worker.md` 写清接入步骤、协议、安全约束、FAQ

## 关联文档

- Plan: `docs/plans/multi-tenant-isolation-plan-2026-05-31.md`(本 ADR 是 Phase B 的详细设计)
- ADR-029 dedicated SPI worker:不变,内建 SPI worker 仍是「平台代部署」选项
- `docs/design/task-spi-design.md` §Phase 4:本 ADR **替换**该节"jar 入 classpath"形态,改为「SDK 自托管 worker」
- ADR-034 CAP 定位:平台状态机仍 CP,租户 worker 是「stateless executor」不影响 CAP 边界
- CLAUDE.md §架构硬约束:本 ADR 不破任何硬约束(主链不变 / orchestrator 唯一状态主机 / worker 必须 CLAIM / outbox 同事务 — 全保留)
