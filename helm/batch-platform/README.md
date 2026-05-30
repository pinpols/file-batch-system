# batch-platform Helm Chart 说明

这是批量调度系统的主 Helm Chart，负责把应用、配置、密钥、Ingress 和可选的 OTEL Collector 组装成一套可部署清单。

## 包含的组件

- `console-api`
- `trigger`
- `orchestrator`
- `worker-import`
- `worker-export`
- `worker-dispatch`
- `worker-spi`
- 可选 `otel-collector`

## 核心文件

- [Chart.yaml](./Chart.yaml) - chart 元信息
- [values.yaml](./values.yaml) - 默认值
- [NOTES.txt](./NOTES.txt) - 安装后提示
- `templates/` - Kubernetes 模板

## 部署方式

```bash
helm upgrade --install batch-platform ./helm/batch-platform \
  -f helm/values-prod.yaml
```

常见覆盖项：

- `image.registry` / `image.tag`
- `postgresql.platform.password`
- `postgresql.business.password`
- `minio.accessKey`
- `minio.secretKey`
- `consoleApi.ingress.enabled`
- `otelCollector.enabled`

## 环境分层

- `values.yaml`：默认本地联调值
- `helm/values-prod.yaml`：生产或类生产覆盖值
- 需要 staging 专用值时，可以新增 `values-staging.yaml`

## 模板说明

- `configmap.yaml`：跨服务环境变量
- `secret.yaml`：数据库和对象存储密钥
- `console-api.yaml` / `trigger.yaml` / `orchestrator.yaml` / `worker-*.yaml`：各服务 Deployment
- `ingress.yaml`：console-api 对外入口
- `otel-collector.yaml`：可选的 OTEL Collector
- `_helpers.tpl`：命名、标签、镜像和 envFrom 辅助函数

## 参考

- [../README.md](../README.md)
- [../../docs/testing/release-gate.md](../../docs/testing/release-gate.md)
- [../../docs/testing/staging-live-deploy-smoke-checklist.md](../../docs/testing/staging-live-deploy-smoke-checklist.md)
- [../../docs/observability/otel-integration.md](../../docs/observability/otel-integration.md)
