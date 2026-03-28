# Helm 目录说明

这里收纳批量调度系统的 Helm 部署产物和环境覆盖值。

## 目录分工

- `batch-platform/`：主 Helm Chart，包含 orchestrator、trigger、console-api 和三个 worker 的部署清单
- `values-prod.yaml`：生产或类生产环境的覆盖值示例

## 使用顺序

1. 先看 [batch-platform/README.md](./batch-platform/README.md)
2. 再根据环境选择 `values.yaml` 或 `values-prod.yaml`
3. 最后通过 `helm upgrade --install` 部署

## 相关文档

- [batch-platform/README.md](./batch-platform/README.md)
- [values-prod.yaml](./values-prod.yaml)
- [docs/testing/staging-live-deploy-smoke-checklist.md](../docs/testing/staging-live-deploy-smoke-checklist.md)
- [docs/testing/deployment-verification-report.md](../docs/testing/deployment-verification-report.md)
- [docs/observability/otel-integration.md](../docs/observability/otel-integration.md)
