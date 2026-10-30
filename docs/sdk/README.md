# SDK 文档索引

ADR-035 租户自托管 Worker SDK 的文档集。代码总入口见 [`sdk/README.md`](../../sdk/README.md);设计背景见 [ADR-035](../architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md)。

## 上手

| 文档 | 说明 |
|---|---|
| [quickstart.md](quickstart.md) | 5 分钟跑起一个租户自托管 worker(最短可运行路径) |
| [onboarding-journey.md](onboarding-journey.md) | 从 0 到生产的接入旅程 |
| [byo-sdk-guide.md](byo-sdk-guide.md) | BYO(Bring-Your-Own)多语言 SDK 完整指南 |
| [troubleshooting.md](troubleshooting.md) | 常见问题与排障 |

> 申请 API key / 配 Kafka SASL / 注册 task type 的运维流程见 [`docs/runbook/per-tenant-worker-onboarding.md`](../runbook/per-tenant-worker-onboarding.md)。

## 协议与契约(权威源)

| 文档 | 说明 |
|---|---|
| [wire-protocol.md](wire-protocol.md) | HTTP `/internal/*` + Kafka 通信协议的权威定义(§A/§B/§C) |
| [byo-conformance-contract.md](byo-conformance-contract.md) | 防漂移契约:`then.expect` 封闭词表 + 跨语言一致性要求 |
| [sdk-parity-matrix.md](sdk-parity-matrix.md) | 基础协议历史对齐矩阵及当前 Replay 能力边界 |
| [config-reference.md](config-reference.md) | SDK 配置项、默认值和语言差异 |
| [ipv6-happy-eyeballs.md](ipv6-happy-eyeballs.md) | 五语言控制面 HTTP 的 IPv4/IPv6 与 Happy Eyeballs 能力、边界和验证矩阵 |

> 契约 fixture 实体在 [`docs/api/sdk-contract-fixtures/`](../api/sdk-contract-fixtures),跨语言共享常量在 [`docs/api/sdk-shared-constants.yaml`](../api/sdk-shared-constants.yaml)。

> 新顶层入口是 Workflow；普通 SDK 不自行创建任务或计算日程。平台新增 Replay 字段的支持范围见协议与矩阵，不能据旧 conformance 通过推断五语言已支持。

## 测试与覆盖

| 文档 | 说明 |
|---|---|
| [local-e2e-coverage.md](local-e2e-coverage.md) | 本地全链路覆盖和五语言 pause/resume、cancel、drain 控制指令真实栈验证 |
| [byo-conformance-contract.md](byo-conformance-contract.md) | 当前自托管 SDK conformance 合同与验证边界 |

## 发布

| 文档 | 说明 |
|---|---|
| [RELEASING.md](RELEASING.md) | 5 语言发布管线、真实包名、registry 凭据、dry-run 流程 |
