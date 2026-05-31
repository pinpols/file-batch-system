# sample-tenant-worker — ADR-035 Phase 1.4 示范

租户自托管 Worker 最小示范,集成 `batch-worker-sdk`。业务方按此模板复制到自己 repo,把 handler 换成业务实现即可。

## 跑

```bash
# 1. 装 SDK 到本地 maven (首次)
mvn -pl batch-worker-sdk -am install -DskipTests

# 2. 打 sample worker
mvn install -f examples/sample-tenant-worker/pom.xml -DskipTests

# 3. 跑(需配置以下环境变量)
export BATCH_BASE_URL=https://batch.example.com
export BATCH_TENANT_ID=tenant-xyz
export BATCH_WORKER_CODE=xyz-sample-worker-1
export BATCH_KAFKA=kafka.example.com:9092
export BATCH_API_KEY=...  # P2 启用后必填
java -jar examples/sample-tenant-worker/target/sample-tenant-worker-1.0.0-SNAPSHOT.jar
```

## 注册的 handler

| taskType | 行为 |
|---|---|
| `echo`  | 把 `parameters` 原样回吐,演示最小契约 |
| `sleep` | 按 `parameters.millis` sleep,演示长任务 + lease 自动续约 |

## 验证

控制台触发一个 `taskType=echo` 的 Job,看 sample worker 日志出现:

```
echo handler taskId=N params={...}
```

`sleep` Job 配 `millis=120000`,看 worker 在执行期间每 60s 触发一次 `LeaseRenewalScheduler`。

## 业务方落地

复制本目录到自己 repo,改:

1. `pom.xml` 的 `groupId/artifactId`
2. `SampleTenantWorker.main()` 里的 config(从你自己的配置中心读)
3. 把 `EchoHandler / SleepHandler` 换成业务实现(实现 `SdkTaskHandler` 即可)

不要引入 Spring / `batch-common` / `batch-worker-core` — SDK 设计就是为了不把这些拉到租户进程里。
