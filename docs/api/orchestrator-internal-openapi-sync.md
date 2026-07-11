# orchestrator internal OpenAPI ↔ controller 同步守护

`docs/api/orchestrator-internal.openapi.yaml` 是 SDK-facing internal API 的手写契约(BYO SDK 按它实现 CLAIM/REPORT/heartbeat/renew 等)。它与真实 `batch-orchestrator` internal controller 之间此前是**人肉同步链**(评审 R5)。本页记录已落地的机器化守护与后续收口。

## 现状:静态路径存在性守护(已落地,log-only)

工作流 `.github/workflows/orchestrator-internal-openapi-drift.yml` 在改动 spec 或 internal controller 的 PR 上触发,**不启动应用**,纯静态解析:

- 抽取 spec `paths:` 下的每条 `/internal/...` 路径;
- 抽取每个 `controller/*Controller.java` 的类级 `@RequestMapping` 基路径 + 方法 `@*Mapping` 组装全路径;
- 路径参数名归一(`{workerCode}` → `{}`)后比对,报告 **spec 有、controller 无** 的幽灵路径。

### 为什么只做单向(spec → controller)

`orchestrator-internal.openapi.yaml` 是 **SDK-facing 精选子集**(workers / tasks / files / readiness / batch-day-replay 等 19 条),不是全部 25 个 internal controller 的镜像 —— ops / admin / 治理类 internal 端点有意不进 SDK 契约。故:

- **spec → controller**(每条 spec 路径必须真实存在):有意义,能抓 spec 引用了已改名/删除端点的漂移 → 校验。
- **controller → spec**(每个 controller 端点都要文档化):与子集设计冲突,会对所有非 SDK 端点误报 → 不校验。

log-only 起步:`continue-on-error` + 只写 job summary。稳定一轮后把脚本尾部 `sys.exit(0)` 改 `sys.exit(1)` 转硬失败,并考虑纳入 `pr-gate`。

## 收口 follow-up:字段级 schema diff(需 springdoc)

路径存在性抓不到 **request/response 字段级漂移**(如 `TaskExecutionReportDto` 改字段名 / 增删属性)。要机器化字段级对账,需运行期产真 spec 再与手写 yaml diff:

1. batch-orchestrator 依赖当前**不含** springdoc(`springdoc-openapi-starter-webmvc-*`);引入后 `@RestController` 自动产 `/v3/api-docs`。
2. 加一个只对 internal controller group 产 spec 的最小配置(`GroupedOpenApi` 限定 `/internal/**`),用 `@WebMvcTest` 或轻量 boot 在 test 阶段 dump spec 到文件。
3. CI 用 `openapi-diff`(如 `openapitools/openapi-diff`)比对生成 spec 与手写 yaml,BREAKING diff 报警。
4. 工程量:引 springdoc + group 配置 + test dump + diff 工具接入,约中等;需评估对 orchestrator 启动 / 依赖面的影响(核心运行时模块,谨慎)。落地前先在本页登记方案,避免误加重依赖。

在字段级 diff 落地前,手写 yaml 仍靠 code review + 本页的路径守护 + `SdkPlatformContractTest`(SDK 侧字段集契约)三者兜底。

## 关联

- 契约:`docs/api/orchestrator-internal.openapi.yaml`
- 守护:`.github/workflows/orchestrator-internal-openapi-drift.yml`
- SDK 侧字段契约:`sdk/java/core/src/test/.../SdkPlatformContractTest.java`
- CI required 收口:`docs/runbook/ci/sdk-e2e-required-path.md`
