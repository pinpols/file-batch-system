# sample-tenant-worker — ADR-035 Phase 1.4 示范

租户自托管 Worker 最小示范,集成 `batch-worker-sdk`。业务方按此模板复制到自己 repo,把 handler 换成业务实现即可。

> **同一自托管能力的 4 种接入,按租户技术栈选**:
> - **`sample-tenant-worker`(本目录)** — 纯 Java + 手写 `main` wiring
> - [`../sample-tenant-worker-spring`](../sample-tenant-worker-spring/) — Java + Spring Boot starter(自动装配)
> - [`../sample-tenant-worker-python`](../sample-tenant-worker-python/) — Python 3.12+ + asyncio
> - [`../batch-worker-sdk-template`](../batch-worker-sdk-template/) — Java 生产 fork 起点(Dockerfile + CI)
>
> 其它语言(Go / Rust / Node)走 [BYO SDK guide](../../docs/sdk/byo-sdk-guide.md) 自研。

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
| `sample_import_echo` | ADR-036 Import 模板 + **SDK P3 M3.1 `descriptor()` 端到端示范** |
| `sample_export_echo` / `sample_process_echo` / `sample_dispatch_echo` / `sample_atomic_echo` | ADR-036 其余四类业务模板 sample |

## descriptor() 端到端(SDK Phase 3 M3.1)

`ImportEchoHandler` 重写了 `SdkTaskHandler.descriptor()`,声明 taskType 元数据:

```java
@Override
public SdkTaskTypeDescriptor descriptor() {
  return SdkTaskTypeDescriptor.builder()
      .displayName("示范导入(echo)")
      .version("v1")
      .defaults(Map.of("batchSize", 2, "targetTable", "staging_${bizDate}"))
      .inputSchema(Map.of("type", "object", "required", List.of("sourcePath"), ...))
      .templateVariables(List.of("bizDate", "dataIntervalStart", "dataIntervalEnd"))
      .build();
}
```

声明后的完整链路:

1. **register 上报** — worker 启动 register 时,SDK 收集所有非 null `descriptor()`,以 `taskType()` 为权威 `code`,随 `POST /internal/workers/register` 的 `taskTypes[]` 上报平台。
2. **平台 upsert** — orchestrator 把 descriptor upsert 到 `batch.custom_task_type_registry`(状态 `ACTIVE`,记录 `last_declared_at`)。
3. **console 查询** — 租户/平台管理员经 `GET /api/console/custom-task-types?tenantId=` 列出、`/{taskTypeCode}` 看 descriptor 全文(API-P3-1),据 `inputSchema` 渲染表单、据 `defaults` 预填。
4. **派单合并** — orchestrator 派单时按 `descriptor.defaults < job default_params < node.parameters` 三级合并,并对 `${bizDate}` 等模板变量做替换(ORCH-P3-2b),合并后的 `effective_parameters` 落 `job_task`(ORCH-P3-3)。

> **凭据纪律**:SFTP 密码 / S3 secret 等敏感值**禁止**走 `defaults` —— 它们会写入数据库 `custom_task_type_registry` 并回显 console。凭据一律走 worker 进程的环境变量。

## 验证

控制台触发一个 `taskType=echo` 的 Job,看 sample worker 日志出现:

```
echo handler taskId=N params={...}
```

`sleep` Job 配 `millis=120000`,看 worker 在执行期间每 60s 触发一次 `LeaseRenewalScheduler`。

descriptor 验证:启动 sample worker(register 成功)后,经 console 查 `GET /api/console/custom-task-types?tenantId=<你的租户>`,应能看到 `sample_import_echo` 一行,`descriptor` 字段含上面声明的 defaults/inputSchema。

## 业务方落地

复制本目录到自己 repo,改:

1. `pom.xml` 的 `groupId/artifactId`
2. `SampleTenantWorker.main()` 里的 config(从你自己的配置中心读)
3. 把 `EchoHandler / SleepHandler` 换成业务实现(实现 `SdkTaskHandler` 即可)

不要引入 Spring / `batch-common` / `batch-worker-core` — SDK 设计就是为了不把这些拉到租户进程里。
