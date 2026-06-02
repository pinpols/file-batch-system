# batch-worker-sdk-template

> ADR-035 Round-3 #10 — 租户自托管 worker 的**开箱即用 template**。
>
> **本目录是 template repo 的雏形**(主仓 examples/ 孵化),后续会拆成独立 repo
> `github.com/example-batch/batch-worker-sdk-template` 供租户直接 fork。

跟 `examples/sample-tenant-worker-spring` 的区别:

| | sample-tenant-worker-spring | **batch-worker-sdk-template** |
|---|---|---|
| 定位 | starter 自动装配**示范** | 租户 fork 起点,**直接生产可用** |
| Dockerfile | ✗ | ✓ multi-stage(maven build + alpine JRE) |
| `.env.example` + `run.sh` | ✗ | ✓ 一键起容器 |
| CI workflow | ✗ | ✓ `.github/workflows/ci.yml`(mvn verify + docker build) |
| handler 示范 | echo + sleep | echo + healthcheck(更贴生产探活) |

## 5 分钟 quickstart

### 1. 拷贝 / fork 本目录到自家 repo

```bash
cp -r examples/batch-worker-sdk-template ~/my-tenant-worker
cd ~/my-tenant-worker
git init && git add . && git commit -m "init from batch-worker-sdk-template"
```

### 2. 填 env

```bash
cp .env.example .env
$EDITOR .env     # 至少填 BATCH_BASE_URL / BATCH_TENANT_ID / BATCH_WORKER_CODE
```

prod 强制项:`BATCH_API_KEY` / `BATCH_KAFKA_SASL_*`(否则 starter 启动失败,符合 fail-fast 预期)。

### 3. 跑

```bash
./run.sh                    # 一键 docker build + run
./run.sh --logs             # follow 日志
docker logs -f batch-worker-sdk-template
```

或本地 IDE:

```bash
mvn spring-boot:run
```

### 4. 验证

平台 dispatch 一条 `template.healthcheck` task → worker 日志看到
`healthcheck taskId=... workerId=...` → console-api `GET /api/job-instances/{id}` 看到结果。

> 完整 onboarding 流程(从 0 到平台 register → 第一条 task 跑通)见
> [`docs/sdk/onboarding-journey.md`](../../docs/sdk/onboarding-journey.md)(主仓 Issue #249 交付)。
> 协议契约见 [`docs/sdk/wire-protocol.md`](../../docs/sdk/wire-protocol.md)。

## 加自己的 handler

```java
// src/main/java/com/example/template/handlers/MyBusinessHandler.java
@Component
public class MyBusinessHandler implements SdkTaskHandler {
  @Override public String taskType() { return "myco.do-something"; }
  @Override public SdkTaskResult execute(SdkTaskContext ctx) {
    // 真业务
    return SdkTaskResult.ok("done", Map.of("processed", 42));
  }
}
```

`@Component` 即被 starter 自动 register,无需手写 `client.register(...)`。

## 凭据安全

- `application.yml` 全用 `${ENV:default}` 占位,**禁**写明文 api-key / SASL 密码
- `.env` 已加 `.gitignore`,绝不提交
- prod 用 K8s Secret / HashiCorp Vault 注入 env,不用 `.env` 文件
- 与 R3-11(ADR-039 envRef)对齐:descriptor 端凭据走 env 引用,SDK 端这里同样路径

## 不挂主 reactor

本目录**不进主 reactor**(`mvn package -f pom.xml`(主仓根)不会编它),`pom.xml` 自带
`spring-boot-starter-parent`。租户拷走后改 `groupId` / `artifactId` 重命名即独立项目。

## 文件清单

```
batch-worker-sdk-template/
├── README.md                                    # 本文件
├── pom.xml                                      # SDK starter + Boot
├── Dockerfile                                   # multi-stage(maven + alpine JRE)
├── run.sh                                       # ./run.sh 一键起
├── .env.example                                 # env 变量清单
├── .gitignore
├── .github/workflows/ci.yml                     # 示范 CI(mvn verify + docker build)
└── src/main/
    ├── java/com/example/template/
    │   ├── TemplateWorkerApplication.java       # @SpringBootApplication 入口
    │   └── handlers/
    │       ├── EchoHandler.java                 # 示范 1:回显 parameters
    │       └── HealthcheckHandler.java          # 示范 2:返回 worker 内部状态
    └── resources/application.yml                # 全 env 占位
```

## 相关

- ADR-035:租户自托管 SDK
- ADR-039(R3-11 并行做):descriptor envRef 凭据风格
- Issue #249:onboarding journey 文档
- `examples/sample-tenant-worker-spring`:starter 自动装配最小示范
- `examples/sample-tenant-worker`:纯 Java 手写 wiring 示范
