# 项目全局深度审查报告（2026-06-08）

## 审查基线

- 审查时间：2026-06-08 20:53 CST。
- 后端仓库：`/Users/dengchao/Downloads/file-batch-system`
- 前端仓库：`/Users/dengchao/Downloads/batch-console`
- 后端本地基线：`main` @ `fef75e1ed feat: improve trace diagnostics and governance handling (#437)`。
- 注意：审查时 `origin/main` 已前进到 `20b114a52 fix: harden scheduled task redis startup handling (#438)`，本报告未纳入该远端新提交的差异。
- 前端本地基线：`main` @ `ec5f174 Merge pull request #79 from pinpols/feature/trace-diagnostics-ui-20260608-bugfixed`。
- 方法：静态代码审查 + 架构/安全/资源/流程/测试/运维配置扫描；未运行测试，未修改业务代码。

## 总体结论

项目主架构方向是清晰的：`trigger -> orchestrator -> workers -> console-api` 分层明确，核心链路坚持 `DB -> Outbox -> Kafka -> CLAIM -> EXECUTE -> REPORT`，orchestrator 作为唯一状态主机的边界写入了工程规范。SDK / starter / testkit 与平台 runtime 10 模块的关系也有 ADR 例外说明，未把 Spring 绑进 core SDK。

当前最需要处理的不是“大重构”，而是几个关键契约没有完全闭死：

1. SDK Kafka offset commit 与 dispatcher 拒收语义存在不一致，可能导致消息被 commit 但未进入执行。
2. Console 认证已切 HttpOnly cookie，但后端仍关闭 CSRF，前端只是预留 XSRF 头配置。
3. PR gate 为提速移除了 IT/E2E，主干质量依赖 full-ci / staging 回退，必须确保 merge queue 和 required check 配置真的闭环。
4. Import COPY 路径在应用侧仍整块缓冲 CSV，且模板 chunk size 没上限。
5. Import checkpoint/重试安全依赖 strict idempotency，但默认仍是兼容模式。
6. 运行参数基线文档、DB seed 和 YAML 默认值存在漂移。

## P0 / 上线阻断级

### P0-1 SDK Kafka offset 提交语义可能导致派单消息丢失

证据：

- `batch-worker-sdk/src/main/java/io/github/pinpols/batch/sdk/dispatcher/KafkaTaskConsumer.java`
  - 34-36 行注释说明：dispatcher 提交到线程池后立刻 commit offset。
  - 149-154 行实际是处理 poll 批次后统一 `consumer.commitSync()`。
- `batch-worker-sdk/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java`
  - 184-203 行：`draining` / `fatal` / `platformState` 非接收态时直接 `return`。
  - 210-220 行：tenant mismatch 注释写“不 ack offset”，但方法依旧只是 `return`，外层 consumer 仍会统一 commit。

风险：

- 平台 PAUSED / DRAINING 生效前已有在途消息，dispatcher 防御性 `return` 后 offset 仍前进。
- tenant mismatch 是 Kafka ACL / consumer group 配置漂移的强信号，当前注释期望“不 ack 留给 redeliver / 人工介入”，但实际可能直接提交，导致任务从 Kafka 视角消失。
- 这类问题不会表现为 worker 慢，而会表现为 orchestrator 侧任务卡在可重派/lease/retry 语义里，排查成本高。

建议：

- 把 `TaskDispatcher.onMessage` 改为返回显式结果：`SUBMITTED` / `RETRY_LATER` / `DROP_TERMINAL`。
- `KafkaTaskConsumer` 只对 `SUBMITTED` 和明确坏消息终态 commit。
- 对 `PAUSED` / `DRAINING` / `tenant mismatch` 使用 pause + seek 或不 commit。
- 补 MockConsumer 集成测试：paused/draining/tenant mismatch 时 offset 不提交；invalid schema / parse error 是否提交要按设计明确。

## P1 / 上线前应修

### P1-1 Console HttpOnly cookie 已落地，但 CSRF 保护未闭环

证据：

- `batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ConsoleSecurityConfiguration.java`
  - 51-52 行：启用 CORS，但 `csrf(AbstractHttpConfigurer::disable)`。
- `../batch-console/src/api/client.ts`
  - 11-14 行：axios `withCredentials=true`，依赖 HttpOnly cookie。
  - 15-19 行：前端声明 `XSRF-TOKEN` / `X-XSRF-TOKEN`，但注释明确 BE 当前可能尚未配置。
- `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/web/ConsoleAuthController.java`
  - 183-188 行：cookie 为 HttpOnly + Secure + SameSite=Lax。

风险：

- HttpOnly 防 XSS 窃 token，SameSite=Lax 降低跨站 POST 风险，但不能等同于完整 CSRF 防护。
- 目前 mutating API 主要靠 CORS allowlist、自定义 header 和 SameSite 策略，纵深不足。

建议：

- 后端启用 `CookieCsrfTokenRepository` 或等价 double-submit 机制。
- 对 POST/PUT/PATCH/DELETE 强校验 `X-XSRF-TOKEN`。
- 登录、公钥、健康检查、prometheus、SSE ticket 等按需豁免。
- 前端已有 xsrf 配置，可直接复用。

### P1-2 PR gate 不跑 IT/E2E，主干质量依赖事后回退

证据：

- `.github/workflows/pr-gate.yml`
  - 3-8 行：明确 PR 只跑 static + unit + security，IT/E2E 交给 full-ci main push 与 staging nightly。
  - 179-186、216-221、251-256 行：unit shard 均使用 `mvn test -DskipITs=true`。
- `.github/workflows/full-ci-gate.yml`
  - 85-171 行：main push 上跑 unit + IT。
  - 265-341 行：main push 上跑 e2e shard。

风险：

- 如果 GitHub merge queue 没有真正启用，或者 full-ci-gate 不是 merge 必过，IT/E2E 回归会先进入 main。
- 近期 worker 状态机、trigger misfire、import/export benchmark 等都依赖端到端语义，单测不足以兜住。

建议：

- 确认 ruleset：merge_group 必须要求 pr-gate；main 必须要求 full-ci-gate 成功。
- 至少把最关键状态机 smoke IT 放回 PR 或 merge queue。
- 对高风险路径建立标签触发：修改 worker/orchestrator/trigger/import/export 时自动跑相关 IT 子集。

### P1-3 Import COPY 路径应用侧整块缓冲，chunk size 无上限

证据：

- `batch-worker-import/src/main/java/io/github/pinpols/batch/worker/imports/plugin/GenericJdbcMappedImportLoadPlugin.java`
  - 391-400 行：先 `buildCopyCsv` 生成完整字符串，再 `new StringReader(csv)` 传给 `CopyManager.copyIn`。
  - 465-470 行：`StringBuilder` 初始容量按 `records.size() * insertCols.size() * 16` 估算。
- `batch-worker-import/src/main/java/io/github/pinpols/batch/worker/imports/stage/support/ImportStageSupport.java`
  - 58-71 行：模板 `chunk_size` 只做 `Math.max(1, value)`，没有上限。

风险：

- 默认 2000 行在多数场景可接受，但模板可配大值，宽表或大文本列会造成 worker 堆峰值。
- COPY 路径名义上是高吞吐，但当前不是严格 streaming copy。

建议：

- 增加 `batch.worker.import.file-processing.max-chunk-size`。
- 模板加载或运行期超过上限直接拒绝。
- 后续把 CSV 构造改成 streaming Reader，减少复制和大字符串驻留。

### P1-4 Import checkpoint/续跑依赖幂等，但 strict idempotency 默认关闭

证据：

- `batch-worker-import/src/main/java/io/github/pinpols/batch/worker/imports/config/JdbcMappedImportSecurityProperties.java`
  - 15-31 行：`strictIdempotency` 默认 false，注释称兼容模式。
- `batch-worker-import/src/main/java/io/github/pinpols/batch/worker/imports/plugin/GenericJdbcMappedImportLoadPlugin.java`
  - 99-107 行：无 `conflict_columns` 时日志明确提示 retry 会重复写。
- `docs/runbook/platform-worker-checkpoint-howto.md`
  - 明确要求 checkpoint 场景确认 `strict-idempotency=true`。

风险：

- checkpoint 崩溃续跑、partition reclaim、worker kill 恢复都依赖 chunk 可重放。
- 对无唯一约束/无 conflict columns 的模板，重跑会双写。

建议：

- prod profile 显式打开 `batch.worker.import.jdbc-mapped.strict-idempotency=true`。
- 对 checkpoint/partition replace/copy 路径强制模板声明 conflict columns 或唯一约束。
- 在模板预检报告中把幂等状态作为阻断项，而不是仅日志告警。

### P1-5 运行参数基线漂移：YAML / 文档 / DB seed 不一致

证据：

- `batch-worker-import/src/main/resources/application.yml`
  - 70-76 行：Import `chunk-size` 已调优为 2000。
- `docs/design/runtime-default-parameters.md`
  - 19-25 行：仍写 Import/Export `chunk-size=500`。
- `db/migration/V24__batch_runtime_default_parameter.sql`
  - 61-69 行：DB seed 仍为 Import/Export `chunk_size=500`。

风险：

- 运维、审计和压测报告看到的“默认参数”不一致。
- 生产调参时可能误以为默认仍是 500，导致回滚/复验口径错误。

建议：

- 统一 YAML、文档和 runtime default seed。
- 变更默认值时新增迁移或运维脚本更新 `batch_runtime_default_parameter`。
- 在 CI 增加 YAML 默认值与 runtime default seed 的一致性检查。

## P2 / 加固与治理

### P2-1 Worker core 执行池配置未 fail-fast

证据：

- `batch-worker-core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/TaskExecutionPool.java`
  - 40-48 行：使用 `Executors.newFixedThreadPool`。
- `batch-worker-core/src/main/java/io/github/pinpols/batch/worker/core/config/WorkerExecutionTimeoutProperties.java`
  - 17-20 行：注释要求 `poolSize >= batch.worker.max-concurrent-tasks`。
- `batch-worker-core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerStartupRuntimeAudit.java`
  - 88-100 行：发现 `execution poolSize < maxConcurrentTasks` 只进入 audit issue。

风险：

- 配错时 listener 拿到并发许可但 executor 排队，造成虚假超时或吞吐异常。

建议：

- 启动期 fail-fast。
- 或替换为 bounded executor + 明确拒绝策略，把背压暴露到 consumer。

### P2-2 内部接口仍保留 legacy 单共享 secret

证据：

- `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/config/InternalAuthFilter.java`
  - 24-34 行：同时支持 `X-Internal-Secret` 和 per-tenant API key。
  - 99-106 行：legacy secret 通过后直接放行，且不绑定 tenant。

风险：

- 任一内部服务拿到共享 secret，就具备较大范围的 `/internal/**` 调用能力。
- 与 ADR-035 的 per-tenant worker API key 模型并存，长期会增加权限边界复杂度。

建议：

- 对 legacy secret 设置迁移期限。
- 引入 endpoint scope / service identity / mTLS。
- 至少把高危 `/internal/tasks/**`、`/internal/workers/**` 优先迁到 scoped API key。

### P2-3 Actuator 暴露面依赖网络隔离

证据：

- `batch-common/src/main/resources/batch-defaults.yml`
  - 128-142 行：默认暴露 `health,info,prometheus,loggers`，health details 为 `always`。
- `batch-common/src/main/resources/application-prod.yml`
  - 13-16 行：prod 将 management port 隔离到独立端口。
- `batch-trigger/src/main/java/io/github/pinpols/batch/trigger/config/TriggerSecurityConfiguration.java`
  - 45-46、68-72 行：`/actuator/**` permitAll 且 filter 放行。

风险：

- 如果 management port 被错误暴露，`loggers` 和详细 health 可能泄露服务拓扑、依赖状态或日志配置。

建议：

- 生产只对内网/探针暴露 management port。
- 外部只允许 `/actuator/health/readiness`、`/actuator/prometheus`，loggers 仅管理员内网可达。
- 考虑 prod 下 `show-details=when_authorized`。

### P2-4 依赖安全扫描不是硬门禁

证据：

- `.github/workflows/full-ci-gate.yml`
  - 222-231 行：dependency scan `continue-on-error: true` 且 timeout 5 分钟。
  - 239-247 行：Trivy fs 扫描仍按 HIGH/CRITICAL 硬拦。

风险：

- Maven dependency-check 因 NVD 限流被软化，依赖 CVE 覆盖不稳定。

建议：

- 配置 NVD API key。
- 或统一改为 Trivy/Snyk/OSV-Scanner 等可控耗时工具作为硬门禁。

### P2-5 SQL/config 分离已有守护，但历史白名单偏大

证据：

- `scripts/ci/check-sql-config-boundaries.sh`
  - 7-18 行：允许 SQL 放到专门目录。
  - 20-58 行：历史遗留 shell 白名单。
  - 62-88 行：新违规会失败。

风险：

- 新增内容已被拦住，但历史脚本仍混有 SQL / 配置，不利于复用、审计和 IDE 校验。

建议：

- 每轮压测/运维脚本改动顺手迁移一个白名单项。
- 最终目标：shell 只负责流程，SQL 放 `scripts/*/sql/*.sql` / `load-tests/sql/*.sql`。

## 架构与设计评价

### 已做对的部分

- 主链路约束明确：`CLAUDE.md` 36-43 行规定 `DB -> Outbox -> Kafka -> CLAIM -> EXECUTE -> REPORT`，并限制 worker 直接写状态表。
- 模块边界清楚：runtime 固定 10 模块，SDK/testkit/starter 作为 ADR-035 例外，core SDK 保持 Spring-free。
- Atomic worker 单独隔离 shell/sql/stored-proc/http executor，符合 dual-use RCE 风险隔离思路。
- Console security 有回退：`ConsoleSecurityConfiguration` 对 `/api/console/**` 至少要求有效角色，并对 loggers 限 ADMIN。
- SQL 模板防线较强：export/process 均限制 SELECT、schema allowlist、禁用函数、必填参数。
- 前端认证方向正确：JWT 不进 localStorage，HttpOnly cookie 为主；富文本统一通过 `v-safe-html` + DOMPurify。
- OpenAPI / Kafka topic / Flyway / SQL boundary 已有 CI 守护，工程化底盘比普通项目更成熟。

### 架构上仍需补的设计契约

- Kafka 消费结果必须变成显式协议，不应靠 `void onMessage` 表达“提交/不提交/丢弃/重试”。
- 幂等策略需要从“日志提醒”上升为“按 load mode 的模板准入规则”。
- 参数基线需要一个 canonical source，不能 YAML、DB seed、文档三套口径并行漂移。
- PR gate 与 full-ci/staging 的关系需要用 GitHub ruleset 固化，而不只写在 workflow 注释里。
- Console cookie 认证切换后，CSRF 要作为认证设计的一部分闭环。

## 推荐整改顺序

1. 修 SDK Kafka offset commit 契约，并补 consumer/dispatcher 集成测试。
2. 补 Console CSRF double-submit，前端现有 axios xsrf 配置直接复用。
3. 统一 Import/Export chunk-size 参数基线，补 CI 一致性检查。
4. prod 打开 import strict idempotency，并把 checkpoint/partition load mode 纳入模板准入。
5. 给 Import chunk-size 加上限，COPY 路径逐步改 streaming。
6. worker execution pool 配置 fail-fast。
7. 确认 GitHub ruleset / merge queue / full-ci required check。
8. 收敛 internal secret、actuator 暴露面、dependency scan 软门禁和 SQL 历史白名单。

## 本轮未覆盖

- 未运行单测、IT、E2E、benchmark。
- 未审查远端新提交 `20b114a52` 的具体 diff。
- 未对 Kubernetes / Helm 实际部署环境做连通性验证。
- 未做真实 DAST 扫描，仅从代码配置层面审查风险。
- 未逐个业务页面做交互/可访问性回归，本报告以前后端架构、安全、流程、资源、业务链路为主。
