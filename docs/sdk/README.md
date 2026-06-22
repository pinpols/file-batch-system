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
| [sdk-parity-matrix.md](sdk-parity-matrix.md) | 5 语言能力对齐矩阵(谁实现了什么) |

> 契约 fixture 实体在 [`docs/api/sdk-contract-fixtures/`](../api/sdk-contract-fixtures/),跨语言共享常量在 [`docs/api/sdk-shared-constants.yaml`](../api/sdk-shared-constants.yaml)。

## 测试与覆盖

| 文档 | 说明 |
|---|---|
| [local-e2e-coverage.md](local-e2e-coverage.md) | 本地全链路覆盖策略 + per-language/per-stage 现状(真 orchestrator 实测) |
| [conformance-gap-analysis-2026-06-16.md](conformance-gap-analysis-2026-06-16.md) | conformance 缺口分析(历史快照) |

## 发布

| 文档 | 说明 |
|---|---|
| [RELEASING.md](RELEASING.md) | 5 语言发布管线、真实包名、registry 凭据、dry-run 流程 |
