# file-batch-system

批量任务编排控制面 + 文件 / 任务交付闭环。9 模块 Maven multi-module:trigger 触发 → orchestrator 派发 → workers 执行 → console-api 控制面。

> **维护规则**:本文件只装「不能从代码推断的约束」+「高频违反的红线」+「关键路径指针」。细节去 `docs/`。
>
> 影响本文件已有规范的改动 → 同步 [`docs/changelog.md`](docs/changelog.md)(日期倒序)。Feature / bugfix / 运维 → git commit + PR + 对应 `docs/` 子目录,**不要**写到本文件。

## 模块

固定 10 个,不可擅自增删:
`batch-common` · `batch-trigger` · `batch-orchestrator` · `batch-worker-core` · `batch-worker-import` · `batch-worker-export` · `batch-worker-process` · `batch-worker-dispatch` · `batch-worker-atomic` · `batch-console-api`

> `batch-worker-atomic` = 专用 Task SPI worker,独占 shell/sql/stored-proc/http 原子执行器(dual-use RCE 隔离),不带文件 pipeline。见 ADR-029。2026-05-30 由 9 增至 10,破"固定模块"规则的理由(安全特权隔离)记于 ADR-029。
>
> `batch-worker-sdk` / `batch-worker-sdk-testkit` / `batch-worker-sdk-spring-boot-starter` 是 ADR-035 的租户自托管 SDK 发布/测试/可选 Spring 适配模块,不属于平台运行时固定 10 模块;core SDK 保持 Spring-free,starter 是独立可选模块。

`load-tests` 是独立 reactor(未纳入根 reactor),版本字面量与根版本手工同步。

## 分支用途

两类改动走两条分支,**绝不互相 PR**:

- **本地 Docker 部署相关**(`docker-compose*.yml` / `docker/compose/*.yml` / `.env.local` / `scripts/ps1/docker/*` / `scripts/local/sync-main.sh` / `.github/workflows/{build-image,deploy,deploy-linux}.yml` / logback `/logs` 挂载这类宿主机布置)→ 提交到 `feature/docker-deploy`(部署分支)
- **业务开发 / bug 修复 / 测试 / 文档**(controller / service / 9 模块代码 / `docs/` / 单测集成测)→ 提交到 `feature/<topic>`(如 `feature/be-bugfixed`),走标准 PR → `main`
- **部署分支不进 main**(也不被 PR 到 main);只接收"main → 部署分支"单向 sync,工具 `scripts/local/sync-main.sh`(.ps1 等价) / 跨仓 `C:\Users\aa\scripts\sync-all.ps1`


## 构建

- `mvn package` — 默认 build,产物 `batch-*-${revision}.jar`(根 pom flatten 插件展开 `${revision}`)
- `mvn -Drevision=X.Y.Z package` — release 覆盖版本
- 跳测试**只用 `-DskipTests`**;**严禁 `-Dmaven.test.skip=true`**(后者会同时跳 test-jar 生成,打断 `batch-common:tests` 依赖链)
- SemVer 2.0.0;main 默认 `<revision>1.1.0-SNAPSHOT</revision>`。完整 release flow → [`docs/runbook/releasing.md`](docs/runbook/releasing.md)

## 架构硬约束

- **主链**:`DB → Outbox → Kafka → CLAIM → EXECUTE → REPORT`
- Orchestrator 是**唯一**状态主机;Worker **不能直接写** `job_instance` / `workflow_run` / `workflow_node_run`
- Worker 执行前**必须 CLAIM**
- `outbox_event` 写入**必须与任务状态同事务**
- Console-api **不能直接 UPDATE/DELETE** `outbox_event`;运维(cleanup / republish)走 `ConsoleOrchestratorProxyService` → orchestrator `/internal/outbox/*`
- **读写分离仅 console-api**;trigger / orchestrator / worker 严禁引入(状态机依赖 read-after-write 强一致),详见 [`docs/runbook/read-replica.md`](docs/runbook/read-replica.md) §六
- **禁覆盖** `batch-common` AutoConfiguration 基础设施 bean(`taskScheduler` / `lockProvider` 等),用 `ObjectProvider` 注入扩展点

## 持久化(ADR-001)

- **全模块统一 MyBatis** + JdbcTemplate;**禁 JPA/Hibernate**;**禁 Spring Data JDBC**(`batch-console-api` / `batch-orchestrator` / `batch-trigger` / `batch-worker-*` 都不引 `spring-boot-starter-data-jdbc`)
- 表行类型放 `domain/entity/`,统一 `*Entity` 后缀(record 或 `@Data` class),**禁** `*Record` 后缀
- 同一表同一写路径**禁双主入口**(Mapper + Repository 二选一)

## 多租隔离

所有业务表必须带 `tenant_id`;所有 UNIQUE / PRIMARY 约束必须含 `tenant_id`(首选直接含 `UNIQUE (tenant_id, code)`)。

合法豁免仅 4 张系统表:`batch_runtime_default_parameter` / `step_registry` / `shedlock` / `biz_table_schema`。

守护:`MultiTenantIsolationIntegrationTest`(batch-orchestrator)+ 各模块 `MapperXmlTenantGuardArchTest`(静态扫描 mapper XML,禁可空 `<if tenantId>` 守护)。

## 异步事件路由

3 张表分工,**禁互相复用,禁新增第 4 张同义表**:

| 表 | 用途 |
|---|---|
| `outbox_event` | 通用业务事件 |
| `event_outbox_retry` | 投递失败的退避重试 |
| `trigger_outbox_event` | trigger fire → orchestrator launch 调度事件 |

判定:"是不是 trigger fire?" 是 → `trigger_outbox_event`;否 → `outbox_event`。详见 [`docs/architecture/event-routing-policy.md`](docs/architecture/event-routing-policy.md)。

## Pipeline vs Workflow vs Job 边界

- **Pipeline** = 文件处理(IMPORT/EXPORT/PROCESS/DISPATCH 固定 5-6 stages,**内置不可扩**),`pipeline_*` 表,worker 内部记录,运维不介入
- **Workflow** = 用户 DAG 编排(任意 Job 组合 + GATEWAY + 补偿 + 审批),`workflow_*` 表,支持人工干预
- **Job** = 单执行单元,`job_*` 表
- 跨域引用单向:`workflow_node.related_pipeline_code → pipeline_definition.job_code`(FILE_STEP 专用,**不反向**)
- **禁** `SELECT FROM pipeline_instance UNION SELECT FROM workflow_run`

详见 [`docs/design/pipeline-vs-workflow-definition.md`](docs/design/pipeline-vs-workflow-definition.md)。

## archive 冷表对齐

热表 `batch.*` 与 `archive.*_archive` 必须 1:1 字段镜像;任何 `ALTER TABLE batch.*` 必须**同 PR** 补齐归档表 migration。启动期 `ArchiveSchemaDriftCheck` fail-fast 拦截。

## 时区 / 编码

- **时区**:全系统默认 `batch.timezone.default-zone`(默认 `Asia/Shanghai`),业务代码**禁** `ZoneId.systemDefault()`,统一注入 `BatchTimezoneProvider`。详见 [`docs/coding-conventions.md`](docs/coding-conventions.md) §时区
- **编码**:全系统 UTF-8;Import (`PreprocessStep`) 是唯一允许读非 UTF-8 源文件的边界。代码用 `StandardCharsets.UTF_8`,**禁** `Charset.forName("UTF-8")` / 字面量。容器 locale 走 `BATCH_LOCALE` env。详见 [`docs/coding-conventions.md`](docs/coding-conventions.md) §字符编码

## 字典 / i18n / 配置开关

- **领域字典**:`batch-common/.../enums/`,实现 `DictEnum`(`code()` / `label()`),Lombok 样板。暴露给 FE 的必须登记 `ConsoleMetaQueryService.REGISTRATIONS`(守护测试 `ConsoleMetaEnumRegistrationTest`)。详见 [`docs/coding-conventions.md`](docs/coding-conventions.md) §18
- **i18n 错误码**:`BizException.of(ResultCode.X, "error.<scope>.<reason>", args...)`,key snake_case,`messages.properties` (en) + `messages_zh_CN.properties` (zh) 1:1。详见 [`docs/design/i18n.md`](docs/design/i18n.md)
- **bypass 开关**:`batch.security.bypass-mode` 总开关(认证 / 加解密 / 审批全放行),prod profile 强制拒绝。旧键 `testing-open` 已删,**无兼容**。详见 [`docs/runbook/feature-switches.md`](docs/runbook/feature-switches.md)

## API 文档同步

改 `batch-console-api` 控制层(新增 / 删除 / 改 path / 改请求响应字段)**必须同 PR 更新**:
- `docs/api/console-api.openapi.yaml`(补 path + schema,无悬空 `$ref`)
- `docs/api/console-api-protocol.md`(Changelog 表追加日期 + 摘要)

CI `pr-gate` 拦截漂移。

## Java 编码细则

完整规则 + 反例表见 [`docs/coding-conventions.md`](docs/coding-conventions.md)。**以下每条都常被违,写代码 / 改代码必须先扫一遍**:

| # | 规则 | 反例 |
|---|---|---|
| 1 | **禁全限定类名**(FQN),必走 `import` | `java.util.concurrent.TimeUnit.SECONDS` |
| 2 | 方法参数 **≤ 6**;≥ 7 必须封装 Command/Param 类 | `void f(a,b,c,d,e,f,g)` |
| 3 | 依赖注入**只用构造器**(`@RequiredArgsConstructor`);**禁** `@Autowired` field / setter 注入。**两类豁免**:① `@Lazy @Autowired private SelfType self;` AOP 自调用 workaround(全仓 9 处);② `@SpringBootTest` IT 测试(全仓 ~77 处沿用 Spring 测试惯例,新 IT 可继续 `@Autowired private Foo foo;`,不强制改构造器) | 生产代码 `@Autowired private Foo foo;` |
| 4 | `@Transactional` **只放 Service 公共方法**,不放 Controller / Mapper;**禁** `Propagation.NEVER` 之外的非默认传播 | `@Transactional` 在 Controller |
| 5 | 业务异常一律 `BizException.of(ResultCode.X, "error.<scope>.<reason>", args...)`,**禁** `new BizException(code, literal)` / `throw new RuntimeException(...)` | 抛裸 `IllegalArgumentException` |
| 6 | Controller 返回值一律 `CommonResponse<T>`(走 `ResponseFactory.success()`),**禁**裸返 DTO 或自封装 envelope | `return user;` |
| 7 | 日志**用占位符**,不用字符串拼接;ERROR 级必须带 `traceId` / 业务 ID;循环里不打 INFO | `log.info("user=" + u)` |
| 8 | `@Builder` 加到普通 class 必须配 `@NoArgsConstructor` + `@AllArgsConstructor`(或 `@Tolerate`)兜底空参,否则破坏 Jackson / MyBatis 反射 | 裸 `@Builder` + 隐式空参 class |
| 9 | if-chain / switch **≥ 3 分支**必须改 `Map<String, Handler>` 路由表 | 4 个 `else if` 散排 |
| 10 | 集合返回 `List.of()` / `Map.of()` 不可变,**禁**返 `new ArrayList<>(...)` 后又 add | `return new ArrayList<>(list)` |

**红线**(违反 = 直接 reject):
- Spring Data JDBC entity / `@Entity` / `@Table` 持久化类**一律不加** `@Builder`(侵入持久化路径)
- **禁重命名任何字段**(破坏 mybatis xml `#{q.xxx}` / canonical constructor 调用方)
- **禁** `ZoneId.systemDefault()`(用 `BatchTimezoneProvider`)
- **禁** `Charset.forName("UTF-8")` / 字面量(用 `StandardCharsets.UTF_8`)

## 测试约定

454 单测 + 23 IT,框架层已统一(JUnit5 100% / AssertJ 主流 / Mockito 独占)。新代码按以下约定:

| 维度 | 约定 |
|---|---|
| 测试框架 | **JUnit5**(`org.junit.jupiter.api.*`),**禁** JUnit4 |
| 断言库 | **AssertJ** `assertThat`(静态 import),**禁** Hamcrest / `assertEquals`(legacy 文件除外) |
| Mock 框架 | **Mockito**,**禁** EasyMock / PowerMock |
| Mock 初始化 | 新单测一律 `@ExtendWith(MockitoExtension.class)` + `@Mock` + `@InjectMocks`(声明式);**禁** `MockitoAnnotations.openMocks(this)` 命令式与 `private Foo foo = Mockito.mock(Foo.class)` 字段直 mock(改动旧代码时顺带迁) |
| Mock strictness | 默认 strict(MockitoExtension 自带),**禁** `@MockitoSettings(strictness = Strictness.LENIENT)` 当模板拷贝带入;只有跨方法共享 stub 且部分方法不触发的场景才允许,需注释说明 |
| 类命名 | 单测 `XxxTest`,集成测 `XxxIT`(Maven failsafe 区分),**禁** `XxxTests` / `XxxSpec` |
| 方法命名 | **首选** `shouldDoX_whenY()`,**接受** `xxx_when_yyy()` 下划线风格(老代码 ~48%),**禁** `testXxx` / `test1` |
| `@DisplayName` | 业务复杂或方法名表达不清时**强烈推荐**中文 `@DisplayName`(参考 `SoftDeleteRecoveryIntegrationTest`);简单字段校验可省 |
| `@Nested` | Controller / Service 测试 ≥ 15 方法时**建议**用 `@Nested` 分组(目前 0 使用,新模块可示范) |
| Spring 集成测 | **必须** `extends AbstractIntegrationTest`(`batch-common/src/test/.../testing`)复用 Testcontainers PG/Kafka/Redis/MinIO,**禁** 各自 `@Testcontainers @SpringBootTest` |
| AAA 注释 | 业务逻辑 > 3 行的测试**建议** `// arrange / // act / // assert` 三行注释(目前 0 使用,新代码示范) |
| Builder / Fixture | 重复构造的 entity / command 抽 `XxxTestBuilder` 静态工厂或 `@Builder` 私有 record(参考 `JobInstanceTerminalStatusCommand` 测试) |

完整测试基础设施(`AbstractIntegrationTest` / `@BatchIntegrationTest` / Testcontainers 端口选择 / Flyway schemas)见 `batch-common/src/test/java/com/example/batch/testing/`。

## ADR 与范围纪律

系统定位:**批量运行控制面 + 文件 / 任务交付闭环**。**不扩张**为企业数据治理 / 容器资源编排 / 合规审计平台。

4 个最高越界风险 ADR 实施 PR 必须先在描述里**答出 ADR 文档顶部「范围边界」判定提问** + 引用「❌ 不做」清单,评审者发现越界(即使代码正确)必须 reject:

| ADR | 一句话边界 |
|---|---|
| **ADR-021** 数据对账 | 「修业务数据」√ vs「裁定业务对错」✗ |
| **ADR-022** Forensic | 「按 bizDate 圈取证包」√ vs「实时合规审计流」✗ |
| **ADR-026** dry-run | 「看配置 / 看会不会跑 / 看 SQL」√ vs「看业务结果对」✗ |
| **ADR-027** 资源亲和 | 「挑 worker」√ vs「挑机器」✗(自研 K8s 调度) |

三阶段优先级 + 完整判定提问 → [`docs/archive/analysis/adr-012-021-027-priority-scope-2026-05-06.md`](docs/archive/analysis/adr-012-021-027-priority-scope-2026-05-06.md)。各 ADR 文档顶部「范围边界」小节是单一权威源。
