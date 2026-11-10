# batch-platform Helm Chart 说明

这是批量调度系统的主 Helm Chart，负责把应用、配置、密钥、Ingress 和可选的 OTEL Collector 组装成一套可部署清单。

## 包含的组件

- `console-api`
- `trigger`
- `orchestrator`
- `worker-import`
- `worker-export`
- `worker-dispatch`
- `worker-atomic`
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
- `objectStorage.accessKey`
- `objectStorage.secretKey`
- `orchestratorBaseUrl` / `triggerBaseUrl` / `workerAtomicBaseUrl`
- `consoleApi.ingress.enabled`
- `otelCollector.enabled`
- `workerImport` / `workerExport` / `workerProcess` / `workerDispatch` 的 `platformDb.credentialsSecretName`：指向含 `BATCH_PLATFORM_DB_USERNAME` 与 `BATCH_PLATFORM_DB_PASSWORD` 的独立 Secret。该 Worker 改用 [`scripts/db/platform-worker-roles.sql`](../../scripts/db/README.md) 授权的最小权限角色，留空时沿用共享平台库账号。切换顺序：先建角色并执行授权脚本，再创建 Secret，最后逐个 Worker 设置并滚动发布。ATOMIC 继续使用 `workerAtomic.envFromSecretName` 指定的独立 Secret。
- Import scanner 的范围、可信渠道及权威清单绑定由平台库的统一入口归属声明提供，Import 和 Orchestrator 读取同一份声明。`workerImport.scannerChannelBindings` 与 `workerImport.scannerManifestBindings` 已废弃，非空时 Helm 会拒绝渲染并提示迁移，避免静默丢弃已有绑定。升级时先暂停相关上游投递、scanner 和对象对账，在平台库完成迁移及[入口归属声明和授权](../../scripts/db/README.md)，核对已登记文件与在途任务，再从 Helm values 移除旧绑定并启动相关服务；配置变更的具体步骤见[事件到达手册](../../docs/runbook/event-driven-arrival.md)。
- `consoleApi` / `trigger` / `orchestrator` 的 `platformDb.credentialsSecretName`：同样指向含 `BATCH_PLATFORM_DB_USERNAME` 与 `BATCH_PLATFORM_DB_PASSWORD` 的独立 Secret，运行时改用 [`scripts/db/platform-control-plane-roles.sql`](../../scripts/db/README.md) 授权的最小权限角色。Console 的主库与只读副本账号一并改用该 Secret；Trigger/Orchestrator 的 Flyway 迁移改读共享 ConfigMap/Secret 中的 owner 账号（`BATCH_PLATFORM_FLYWAY_USERNAME/PASSWORD`），运行时角色不需要 DDL 权限。切换顺序：先建角色，执行控制面授权脚本（Console 角色再执行 `config-cache-invalidation-role.sql`），再创建 Secret，最后逐个组件设置并滚动发布；每次发布含新迁移时，迁移后重新执行授权脚本。

## 环境分层

- `values.yaml`：默认本地联调值
- `helm/values-prod.yaml`：生产或类生产覆盖值
- 需要 staging 专用值时，可以新增 `values-staging.yaml`

生产或类生产 profile 会拒绝 localhost/loopback 的数据库、Kafka、Redis、Orchestrator 和 S3
地址，并要求显式配置可发现的 management 端口。跨服务 URL 必须与实际 release fullname 和
namespace 对齐，不能依赖应用里的本地开发默认值。

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
- [../../docs/runbook/go-live-staging-execution.md](../../docs/runbook/go-live-staging-execution.md)
- [../../docs/runbook/distributed-tracing.md](../../docs/runbook/distributed-tracing.md)
