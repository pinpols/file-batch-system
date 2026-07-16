# Atomic Worker 生产隔离基线

`batch-worker-atomic` 同时承载 SQL、stored-proc、HTTP 和可选 shell/Spark SPI。它不是普通业务 worker，生产部署必须把它当作受控的 dual-use 执行器处理。

## Helm 生产门禁

`helm/values-prod.yaml` 已开启 `workerAtomic.productionIsolationRequired`，模板会拒绝以下配置：

- 没有独立的 `ServiceAccount`
- 没有独立的受限 Secret
- `requireIsolation` 或 `isolationAcknowledged` 未开启
- `NetworkPolicy` 未开启

生产 Secret `batch-worker-atomic-secret` 和 ServiceAccount `batch-worker-atomic` 由集群的密钥/RBAC 管理系统预先创建。Chart 不生成它们，也不允许回退到平台共享 Secret。

NetworkPolicy 仅放行 DNS、平台 PostgreSQL、Kafka 和 values 中显式列出的 SPI 目标。生产 overlay 使用 `db`、`kafka` namespace selector；真实集群 namespace 变化时必须在部署 overlay 中同步修改，不能改回 `0.0.0.0/0`。

## Executor 默认策略

生产 overlay 默认关闭 SQL、stored-proc、HTTP、shell。需要启用某类 executor 时，必须同时提供其白名单：

- SQL：`allowed-data-source-beans`
- stored-proc：`allowed-schemas`
- HTTP：`allowed-host-patterns` 或显式 deny-all
- shell：`command-whitelist`
- Spark：`app-resource-allowlist`

`AtomicExecutorProductionGuard` 会在 `prod` profile 启动时拒绝空白名单；`AtomicIsolationStartupCheck` 会校验隔离确认标志。两道检查都通过后，worker 才允许进入 Ready。

## 验证

```bash
helm lint helm/batch-platform -f helm/values-prod.yaml \
  --set-string postgresql.platform.password=smoke-platform-pass \
  --set-string postgresql.business.password=smoke-business-pass \
  --set-string objectStorage.accessKey=smoke-access-key \
  --set-string objectStorage.secretKey=smoke-secret-key \
  --set-string security.internalSecret=smoke-internal-secret-123 \
  --set-string security.consoleJwtSecret=smoke-console-jwt-123

helm template batch-platform helm/batch-platform -f helm/values-prod.yaml \
  --set-string postgresql.platform.password=smoke-platform-pass \
  --set-string postgresql.business.password=smoke-business-pass \
  --set-string objectStorage.accessKey=smoke-access-key \
  --set-string objectStorage.secretKey=smoke-secret-key \
  --set-string security.internalSecret=smoke-internal-secret-123 \
  --set-string security.consoleJwtSecret=smoke-console-jwt-123 \
  | grep -A8 'name: batch-platform-worker-atomic'
```

渲染结果必须包含独立 `serviceAccountName`、独立 Secret、`BATCH_WORKER_ATOMIC_REQUIRE_ISOLATION=true` 和 default-deny egress NetworkPolicy。
