# Atomic Worker 生产隔离基线

`batch-worker-atomic` 同时承载 SQL、stored-proc、HTTP 和可选 shell/Spark SPI。它不是普通业务 worker，生产部署必须把它当作受控的 dual-use 执行器处理。

## Helm 生产门禁

`helm/values-prod.yaml` 已开启 `workerAtomic.productionIsolationRequired` 与显式拓扑门禁，部署还必须叠加站点私有 values（字段模板见 `helm/values-site.example.yaml`）。模板会拒绝以下配置：

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

stored-proc 的未限定过程名会使用 `default-schema` 补全，授权检查和实际调用指向同一 schema。事务模式下额外使用 `SET LOCAL search_path`；任务开启 `autoCommit` 时，该设置不会跨语句保留，过程内部未限定对象的解析依赖数据库会话配置，应由数据库管理员控制过程及其运行环境。

Spark 本地绝对资源路径按消除 `..` 后的路径匹配允许前缀；此检查不解析符号链接，允许目录及其中的链接应由可信管理员控制。远端 URI 仍按原始前缀匹配，不能用本地路径规则推断其存储端解析行为。

原子任务成功时，执行器的业务输出会随任务报告保存并作为 Workflow 节点输出供后续节点使用。Shell 标准输出、HTTP 响应和 SQL 查询结果可能包含业务敏感数据，应按任务授权与数据访问范围设计；步骤查询展示时会遮蔽 HTTP JSON 正文中可识别的凭据字段，不改变保存的原始输出。不需要 SQL 结果明细时可关闭 `batch.worker.executors.sql.include-result-set`。PostgreSQL 的 JSON 和 JSONB 列按 JSON 值传递，而不是暴露 JDBC 驱动对象。

Shell 和 HTTP 任务的 `timeoutSeconds`、SQL 和 stored-proc 任务的 `statementTimeoutSeconds` 若提供非空值，必须是可表示为 64 位整数的正整数数值；Console 的 Atomic 配置保存入口会先校验，Worker 执行入口也会拒绝无效值并返回 `CONFIG_INVALID`，不会执行命令、发送请求或连接数据库。Console 表单只接受 JavaScript 可精确表示的安全整数。字段缺失或为 null 时使用执行器默认超时；合法值只能缩短默认超时，不能延长它。Spark 任务使用独立参数解析规则。

## 验证

```bash
helm lint helm/batch-platform -f helm/values-prod.yaml -f /secure/path/site-values.yaml \
  --set-string postgresql.platform.password="${SMOKE_PLATFORM_PASSWORD:?set SMOKE_PLATFORM_PASSWORD}" \
  --set-string postgresql.business.password="${SMOKE_BUSINESS_PASSWORD:?set SMOKE_BUSINESS_PASSWORD}" \
  --set-string objectStorage.accessKey="${SMOKE_OBJECT_STORAGE_ACCESS_KEY:?set SMOKE_OBJECT_STORAGE_ACCESS_KEY}" \
  --set-string objectStorage.secretKey="${SMOKE_OBJECT_STORAGE_SECRET_KEY:?set SMOKE_OBJECT_STORAGE_SECRET_KEY}" \
  --set-string security.internalSecret="${SMOKE_INTERNAL_SECRET:?set SMOKE_INTERNAL_SECRET}" \
  --set-string security.consoleJwtSecret="${SMOKE_CONSOLE_JWT_SECRET:?set SMOKE_CONSOLE_JWT_SECRET}"

helm template batch-platform helm/batch-platform -f helm/values-prod.yaml -f /secure/path/site-values.yaml \
  --set-string postgresql.platform.password="${SMOKE_PLATFORM_PASSWORD:?set SMOKE_PLATFORM_PASSWORD}" \
  --set-string postgresql.business.password="${SMOKE_BUSINESS_PASSWORD:?set SMOKE_BUSINESS_PASSWORD}" \
  --set-string objectStorage.accessKey="${SMOKE_OBJECT_STORAGE_ACCESS_KEY:?set SMOKE_OBJECT_STORAGE_ACCESS_KEY}" \
  --set-string objectStorage.secretKey="${SMOKE_OBJECT_STORAGE_SECRET_KEY:?set SMOKE_OBJECT_STORAGE_SECRET_KEY}" \
  --set-string security.internalSecret="${SMOKE_INTERNAL_SECRET:?set SMOKE_INTERNAL_SECRET}" \
  --set-string security.consoleJwtSecret="${SMOKE_CONSOLE_JWT_SECRET:?set SMOKE_CONSOLE_JWT_SECRET}" \
  | grep -A8 'name: batch-platform-worker-atomic'
```

渲染结果必须包含独立 `serviceAccountName`、独立 Secret、`BATCH_WORKER_ATOMIC_REQUIRE_ISOLATION=true` 和 default-deny egress NetworkPolicy。
