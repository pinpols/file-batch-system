# 全方位深度审计报告 · 2026-06-10

> 基线:工作区 `fix/review-2026-06-09-findings`(HEAD `7e7ada8e7` #441 + 32 个未提交修复文件,审计对象为**工作区最新状态**)。
> 方法:8 路并行深扫——① 06-09 修复分支核验 ② 架构硬约束 conformance ③ 多租隔离全局对标 ④ 编码规范 R1–R12 ⑤ 异常契约 4xx/5xx ⑥ 并发/事务/Kafka 语义 ⑦ 安全+资源 ⑧ 既往 P0/P1 回归 + 架构负空间。
> 每条发现均经代码核实,只保留置信度 ≥ 70;已知有意决策(scheduler 单点 / PG 瓶颈 / Kafka 硬依赖 / business 库不走 Flyway / P2 readOnly 不做等)不重复报。
> 活动定性:②③④为 conformance 扫描(地图内找错路),⑧ Part 2 为架构负空间评审(质疑地图够不够大),两者并做,防"全绿错觉"。

---

## 修复状态(2026-06-10,分支 `fix/audit-2026-06-10`)

本轮已修并验证(编译 + 单测全绿):

| 项 | 修法 | 验证 |
|---|---|---|
| P0-1 幽灵表 | 三个 RLS 脚本每表加 `to_regclass` 存在性守护 + NOTICE,缺表跳过不整体回滚(未捏造 customer_processed DDL) | RlsPhaseAMigrationCoverageTest |
| P1-1 RLS 探针死代码 | worker-process `BusinessDataSourceConfiguration` 注册 `RlsPolicyHealthIndicator` @Bean(默认开,测试库关) | test-compile |
| P1-2 守护清单漏表+单向 | 清单补 `process_event_copy`;覆盖测试改**双向**互为子集;runbook/strict 注释同步 | RlsPhaseAMigrationCoverageTest(3) |
| P1-3 重放 10 处 500 | governance 10 处 `IllegalStateException` → `BizException`(404/409/400)+ i18n key×9;同步修 `isOrphanPartitionReplayFailure` 死信 orphan 判定 | DefaultRetryGovernanceServiceTest(16)/DeadLetterAutoRetryTest(4) |
| P1-4 @Async 自调用 | `ApiKeyVerifier` 加 `@Lazy self`,PBKDF2/touch 走代理异步 | ApiKeyVerifierTest(14) |
| P1-5 两步非同事务 | `FileGovernanceRepository` 加 `@Transactional` 组合方法 | test-compile |
| P2-1 Redis 锁竞态 | release/renew 改 Lua + cjson 原子 owner 校验 | WorkflowDesignLockServiceTest(6) |
| P2-2 KMS 弱密钥 | crypto auto-config prod 校验拒绝全零/占位密钥 | BatchObjectCryptoServiceTest |
| P2-3 TypeMismatch→500 | `AbstractApiExceptionHandler` 加 `MethodArgumentTypeMismatchException`→400 基类映射 | ExceptionHandler 测试 |
| P2-4 markExecuted 500 | → `BizException(STATE_CONFLICT)` 409 | DefaultApprovalWorkflowServiceTest(11) |
| P2-5 secret 长度 timing | 新增 `SecretComparator`(sha256 归一化)用于 orchestrator+trigger 内部密钥比对 | InternalAuthFilterTest(9)/TriggerSecurityFilterTest(5) |
| P2-8 dropPartition 守护 | 调用前强制 `^process_staging_p[0-9]{8}$` 白名单 | ProcessStagingPartitionMaintenanceTest(4) |
| P2-9 守护测试缺口 | 补 `WorkerCoreMapperXmlTenantGuardArchTest`(其余模块已存在,审计基于旧快照) | 新测试通过 |

**本分支不做 / 转交(理由)**:
- **P1-6 compose S3 凭据默认值** → `docker-compose*.yml` 按 CLAUDE.md 分支政策属 `feature/docker-deploy`,不进 main,转该分支处理。
- **P1-7 备份/PITR** → 运维投入(base backup + WAL 归档 + 恢复演练),非本仓代码改动。
- **P1-8 GitHub ruleset 未生效** → 组织账号层面(需升 Team org),仓库内无法闭环。
- **P2-6 未 CLAIM 分区重派 sweeper / P2-12 COPY streaming** → feature 级改动,分期项。
- **P2-7 磁盘/表体积告警** → observability 规则文件(prometheus-batch-rules.yml),运维侧。
- **P2-10 field injection(4 处)/ P2-11 config-defaults-sync 脚本** → 建议修;P2-10 四处均有代码注释记录的 test-compat 理由(其中 2 处是 @ConfigurationProperties+Environment 标准 Spring 模式,转构造器注入会破坏本轮新增的 @PostConstruct 校验),按"不过度工程"暂留,如需统一可在 CLAUDE.md 增补豁免③。
- **R1 参数对象封装(26)** → 侵入 Mapper/MyBatis 签名,风险高收益低,单独评估。
- **R3 FQN(42)/ R6(2)** → 纯机械规范churn、零行为变更,可走 `/convention-audit` 自动修复,不混入本批正确性修复。

## 总评

工程质量中上偏好。**架构硬约束 11 条全部合规**(worker 禁写状态表 / CLAIM 前置 / outbox MANDATORY 同事务 / 事件路由三表 / archive 镜像 V160–V169 逐条核验);核心攻击面(4 大 RCE 执行器、全部 mapper `${}`、SSRF、bypass-mode prod 拒绝)防护到位;06-08 评审的 6 项 P0/P1 已闭环 4 项、部分闭环 1 项(PR #439 为主载体);06-09 评审的 12 项修复在工作区**全部落地且修法正确**。

本轮新发现集中在三类:
1. **守护层本身失效**(最高价值):RLS HealthIndicator 从未注册是死代码、守护清单漏表且校验单向、幽灵表导致全新部署 RLS 脚本整体失败——再次印证"全绿 ≠ 守护在跑"。
2. **老毛病复发**:`DefaultRetryGovernanceService` 重放链路 10 处 `IllegalStateException` → 500(与 2026-06-05 修过的 F1/F2/F3 完全同型,本应 404/409)。
3. **运维负空间**:备份/PITR 全空白(failover playbook ≠ 备份)、归档治理失效本身无监控。

---

## P0 / 部署阻断级

### P0-1 幽灵表 `biz.customer_processed`:全新环境 RLS 脚本整体失败(置信 85)

- **位置**:`scripts/db/business/rls-phase-a.sql:82`(及 strict / rollback 脚本)引用 `biz.customer_processed`,但全仓**无任何 DDL 建它**(create_biz_tables.sql / docker init / Flyway / Java 动态建表均无;sim 环境实际有数据,说明是历史手工建的 PROCESS 输出表)。
- **后果**:全新环境按 README 顺序跑 `create_biz_tables.sql` → `rls-phase-a.sql`,DO 块对不存在的表 `ALTER TABLE` 报错 → **整个 DO 块回滚,所有 biz 表一张都拿不到 RLS policy**(biz 循环无 `IF EXISTS` 守护,只有 process_staging 有)。
- **修法**:create_biz_tables.sql 补 `biz.customer_processed` DDL(以 sim 实际结构为准);RLS 脚本每表加存在性守护 + 缺表 RAISE NOTICE。

## P1 / 上线前应修

### P1-1 `RlsPolicyHealthIndicator` 是死代码,RLS 健康守护从未生效(置信 90)

- **位置**:`batch-common/src/main/java/com/example/batch/common/rls/RlsPolicyHealthIndicator.java:25`
- 无 `@Component`、无任何 AutoConfiguration/`@Bean` 注册,唯一引用是测试。runbook `docs/runbook/multi-tenant-rls.md` 宣称的"缺 ENABLE/FORCE/policy 即 /actuator/health 报 DOWN"自 PR #155(2026-05-31)起从未存在——06-09 的 `process_event_copy` 漏 RLS 正是它本该拦的场景。
- **修法**:在持有 business DataSource 的模块(worker-process/import/export)加 `@Bean` 注册(profile 条件化,e2e 单库跳过)+ 补"indicator 必须被 AutoConfiguration 引用"守护测试。

### P1-2 RLS 守护清单漏 `biz.process_event_copy` 且覆盖测试单向(置信 95)

- **位置**:`RlsPolicyHealthIndicator.java:28-39`(`EXPECTED_RLS_TABLES` 仅 10 项)+ `RlsPhaseAMigrationCoverageTest.java:47-51`(只查"清单 ⊆ 脚本",不查"脚本 ⊆ 清单")。
- 06-09 补 RLS 脚本时清单未同步,测试照绿——清单类缺口在守护测试自身复现。
- **修法**:清单补表;测试改双向互为子集校验。随手修:`docs/runbook/multi-tenant-rls.md:38` 验证 SQL 与 `rls-phase-a-strict.sql:71` 尾注("9 张"实为 10 张)同步。

### P1-3 重放治理链路 10 处 `IllegalStateException` → 500,应为 404/409/400(置信 85–90)

与 2026-06-05 已修的 F1/F2/F3 同型,集中在 `batch-orchestrator/.../governance/DefaultRetryGovernanceService.java`,全部经 console 操作员可触达:

| 端点(console) | 位置 | 现状 | 应为 |
|---|---|---|---|
| 死信重放 | :443 not found / :455 not replayable / :463 replay conflict / :469 unsupported sourceType | 500 | 404 / 409 / 409 / 400 |
| 任务重放 | :341 task not found / :347 non-terminal | 500 | 404 / 409 |
| 分区重放 | :532 / :537 / :560 / :595(partition/instance/task not found) | 500 | 404 |

- **修法**:改 `BizException.of(ResultCode.NOT_FOUND/STATE_CONFLICT, "error.<scope>.<reason>")`,与 `DefaultApprovalWorkflowService` 已修模式一致。

### P1-4 `ApiKeyVerifier` @Async 自调用失效,PBKDF2 同步阻塞请求线程(置信 95)

- **位置**:`batch-orchestrator/.../auth/ApiKeyVerifier.java:63,65`(`touchAsync` / `upgradeLegacyHashAsync` 为 `this.` 调用,AOP 代理失效;测试注释已自证)。
- **后果**:每次认证同步执行 DB 写 + PBKDF2(50–200ms CPU),洪峰可耗尽 Tomcat 线程池。
- **修法**:CLAUDE.md 豁免①模式 `@Lazy @Autowired private ApiKeyVerifier self;`(同时把 CLAUDE.md self-injection 计数 9→10,见 P3-4 实为 10→11)。

### P1-5 `FileGovernanceScheduler.sweepStaleRunningPipelines` 两步 UPDATE 非同事务(置信 85)

- **位置**:`batch-orchestrator/.../file/FileGovernanceScheduler.java:119-150`。
- 第一步 pipeline 置 FAILED 提交后崩溃,第二步 steps 置 FAILED 未跑;下一轮扫描基于 `pipeline_instance.updated_at`,已 FAILED 的不再命中 → **steps 永久滞留 RUNNING**。连带 metrics 脏数据写 Redis(P2 级连带)。
- **修法**:两步并入同一 `@Transactional`,或第二步改按 `pipeline_step.updated_at` 独立扫描。

### P1-6 `docker-compose.app.yml` 6 处 S3 凭据默认 `minioadmin`(置信 82)

- **位置**:`docker-compose.app.yml:56,165,224,282,340,398`(`${BATCH_S3_ACCESS_KEY:-minioadmin}`);与 `docker-compose.yml` 的 POSTGRES/MINIO 无默认值策略不一致;secret-key 回落 batch-defaults 空值。
- **修法**:删默认值改 fail-fast;`BatchSecurityProperties.validateSecuritySettings()` prod 检查面补 S3 凭据占位符校验。

### P1-7 备份 / PITR 全空白(负空间,真缺口)

- 全仓 `pg_dump`/`pgbackrest`/`wal-g`/`archive_command` 零命中。现有的流复制 standby + `pg-primary-failover.md` 是 **HA 不是备份**(误删同步到从库);batch_business 手工脚本库重建路径更弱;Kafka 单 broker RF=1。
- **修法**:base backup + WAL 归档 + 双库一致性恢复 runbook + 至少一次演练。上生产前必办。

### P1-8(流程债,非代码)GitHub ruleset 未生效,full-ci 非 required check

- `docs/runbook/ci-cd-followup-2026-05-22.md:47` 自证私有仓库 ruleset 不强制(需升 Team org);pr-gate 仍 skipITs,主干质量靠"full-ci 事后拦 + 作者 revert"约定。06-08 评审 P1-2 至今未动,仓库内无法闭环,需组织账号层面决策。

## P2 / 计划内修复

| # | 问题 | 位置 | 置信 | 修法 |
|---|---|---|---|---|
| P2-1 | `WorkflowDesignLockService` Redis GET-then-SET/DELETE 非原子:TTL 临界点 A 可删/覆盖 B 的锁,DAG 编辑锁失效 | `console/.../WorkflowDesignLockService.java:80-108` | 80 | Lua 原子 compare-and-delete/set |
| P2-2 | KMS 默认密钥全零 base64,prod 无校验 | `batch-defaults.yml:246` | 78 | prod 校验拒绝全零/占位符 |
| P2-3 | `MethodArgumentTypeMismatchException` 未映射:orchestrator/trigger(含基类)path variable 类型错 → 500 | `AbstractApiExceptionHandler` 及两子类 | 80 | 基类加 → 400,全模块受益 |
| P2-4 | 审批 `markExecuted` 状态竞争 → 500,应 409 | `DefaultApprovalWorkflowService.java:111` | 80 | 改 BizException STATE_CONFLICT |
| P2-5 | `InternalAuthFilter` secret 比对长度 timing 泄漏(实际危害低) | `InternalAuthFilter.java:102-104`;同型 `TriggerSecurityConfiguration.java:88` | 80 | 先 sha256 归一化再 `MessageDigest.isEqual` |
| P2-6 | Kafka 消息丢失后「已发出、从未 CLAIM」分区(READY + lease NULL + 无 RUNNING task)三层自愈均不命中,只能实例硬超时或人工 republish | `JobPartitionMapper.xml:208-218` 等 | — | 加"派发后 N 分钟未 CLAIM → 重发 outbox" sweeper,或 runbook 列标准动作 |
| P2-7 | 磁盘/表体积告警零条:归档治理失效本身无第二道防线(exporter 已 scrape,规则缺失) | `prometheus-batch-rules.yml` | — | 补磁盘水位/库体积增速/归档 scheduler 最近成功时间 3–5 条 |
| P2-8 | `dropPartition` 的 `${partitionName}` 当前安全但调用链无强制正则守护 | `ProcessStagingMapper.xml:57-59` | 75 | 调用前强制 `^process_staging_p[0-9]{8}$` |
| P2-9 | `MapperXmlTenantGuardArchTest` 仅 console-api/orchestrator 有;trigger/worker-* 有含 tenant_id 的 XML 但无守护(当前 0 活跃违规,缺防回退) | 各模块 src/test | 80 | 各加空子类 extends 基类 |
| P2-10 | `OrchestratorGracefulShutdown` field injection(`@Autowired(required=false) ApplicationEventPublisher`),不在两类豁免内;同型 `BatchSecurityProperties:38` / `ConsoleSecurityProperties:122` / `AbstractTaskConsumer:102` | 4 处 | 80–85 | 构造器 `ObjectProvider` 注入,或在 CLAUDE.md 增补豁免③并注明理由 |
| P2-11 | config-defaults-sync 脚本不查 `batch_runtime_default_parameter` seed(06-08 P1-5 遗留的防回漂项) | `scripts/ci/check-config-defaults-sync.py` | — | 扩展脚本对账 YAML ↔ V-seed |
| P2-12 | Import COPY 路径仍 JVM 内整块构造单 chunk CSV(已有 10000 行上限,残余分期项) | `GenericJdbcMappedImportLoadPlugin.java:392-400` | — | 按计划改 streaming,非紧急 |

## P3 / 低优先 & 文档

1. **lease 混合时钟域**:写入用应用时钟、过期判定用 DB `current_timestamp`(`DefaultTaskAssignmentService.java:161/375` vs `JobPartitionMapper.xml:209/218`);NTP 漂移缩短 lease 寿命 → 提前 reclaim(幂等兜底存在)。修法:lease 写入改 DB 时钟,一处 SQL;ShedLock/Redis 已正确用服务端时钟。
2. **rate-limit 默认全关**:`rate-limit.enabled=false`、per-tenant launch 维度 0、`global-max-running-jobs=0`——launch 风暴入口默认无闸(quota 子系统/MQ per-tenant routing 默认开,能力在)。prod profile 给保守默认值。
3. **回滚策略未成文**:migration 纪律强(checklist/NOT VALID guard/expand-contract 事实执行),但"前滚 only + 例外清单"没写下来。db-migration-checklist 加一节即可。
4. **文档漂移三处**:`event-routing-policy.md:80` 称 OutboxRetryScheduler 扫 `event_outbox_retry` 重投,代码现状仅 console 展示;CLAUDE.md self-injection 计数 9 → 实际 10(`DefaultRetryGovernanceService.replayTransactionalSelf`);docker init 004 注释提及不存在的 loan_* 表。
5. **strict RLS rollout 注意项**:`ProcessStagingMapper` 维护类 SQL(orphan 清理)翻 strict 后未 `SET LOCAL app.tenant_id` 会静默 0 行——列入 strict rollout checklist(确认 cleaner 走 BYPASSRLS 或仅靠分区 DROP 回收)。

## 编码规范扫描(R1–R12 全量)

**汇总:R1 26 | R2 0 | R3 43 | R4 4 | R5 1 组(35 注解) | R6 2 | R7–R12 全 0 | 合计 76**

- **R1 参数超限**:12 项 ≥7 硬违规(`ConsoleWebhookSubscriptionMapper.insert/update`、`DeadLetterTaskMapper.markReplayFailure`、`ForensicExportLogMapper.markCompleted`、`ConsoleAlertRoutingApplicationService.list` 接口+实现+Controller、`ConsoleExcelStyles.addDropdownValidation`、`DispatchManifestSupport.manifestPayload`、`ConfigPackageExcelValidator` ×2、`DataQualityCheckExecutor.writeCheck`)+ 14 项 =6 的 Mapper/Service 接口(convention-audit skill 规则要求封装)。已有 8 处 `@SuppressWarnings(PMD)` 注释豁免合规。
- **R3 FQN**:42 处必须修,分布 25 文件(高发:`DefaultWorkflowDagService` 8 处、`TriggerOutboxRelay` 5 处、`SqlTemplateExportDataPlugin` 5 处),纯机械改 import 零行为风险。合法豁免(同名冲突被迫 FQN)已排除。
- **R4**:4 处 `@Autowired(required=false)` field 注入(见 P2-10)。
- **R5**:Controller/Mapper 上 @Transactional 0;35 处非默认传播(8 MANDATORY outbox 防护 + 27 REQUIRES_NEW 调度隔离)是**规则文本与既成架构的系统性冲突**,建议 CLAUDE.md 修订 carve-out / ADR 收口,不改代码。
- **R6**:`ShellTaskExecutor:300` 裸 RuntimeException、`TenantConfigCopyRequest:75` IAE 硬编码英文,均建议修。
- 修复执行可走 `/convention-audit`(自动修复流程)。

## 06-09 评审 12 项修复核验(工作区未提交 diff)

**12/12 全部落地且修法正确**(逐项核验表略,要点):EncryptingObjectStore `supportsRangeRead()` 三处联动一致;S3AutoConfiguration 条件化无 bean 缺失;attachClause 白名单严于黑名单;RLS 四处补齐;relkind 幂等守护两脚本对齐;circuitOpen/CAS/ObjectStoreException 均正确。
新发现 2 条:孤儿 session 扫描 SQL 无 tenant 过滤(置信 70,与既有平台级调度 `selectArchivedFilesForCleanup` 同模式,属设计选择,建议补豁免注释);`OrchestratorGracefulShutdown` field injection(并入 P2-10)。
⚠️ **这 32 个文件还没 commit**——确认后尽快提交推 PR,避免与后续工作混淆。

## 06-08 评审 P0/P1 回归核查

| 项 | 状态 | 载体 |
|---|---|---|
| P0-1 SDK Kafka offset 语义 | ✅ 已修(DispatchDecision 三态 + seek/pause,仅 SUBMITTED/DROP_TERMINAL commit,含 3 个专项测试) | PR #439 |
| P1-1 CSRF | ✅ 已修(double-submit + CsrfCookieMaterializeFilter) | PR #439 |
| P1-2 pr-gate 兜底闭环 | ❌ 未动(GitHub ruleset 不强制,见 P1-8) | — |
| P1-3 COPY 缓冲 + chunk 上限 | 🟡 部分(上限已加 10000;streaming 未做,见 P2-12) | PR #439 |
| P1-4 strict idempotency 默认 | ✅ 已修(YAML 默认 true,仅 local 豁免) | PR #439 |
| P1-5 参数基线漂移 | 🟡 大体修(V169 对齐;CI 防回漂未加,见 P2-11) | V169 |

## 已核验正确面(防后续误报,简列)

架构硬约束 11/11 合规(worker 写表清单逐 XML 核对、outbox 全链 MANDATORY、`worker_report_outbox` 是 ADR-015 REPORT 通道非第 4 张路由表、archive V160–V169 逐条配对);mapper `${}` 全仓仅 3 处且系统派生;4 大 RCE 执行器 + SSRF + PathSanitizer + ReDoS 防护齐;bypass-mode prod fail-secure(空 profile 视为生产);/internal/** 双保险 + 常量时间比对;缓存 key 全带 tenant 段;V150+ 新表 tenant_id/UNIQUE 全合规;双脚本 process_staging 定义零漂移;AbstractTaskConsumer MANUAL_IMMEDIATE ack 语义正确;ShedLock usingDbTime;线程池 CallerRuns 有界;@Scheduled 异常不致停摆;outbox relay 幂等。

## 建议执行顺序

1. **立即**:提交当前 32 文件修复分支 → P0-1 幽灵表 + P1-1/P1-2 RLS 守护三件套(同一 PR 顺手把 runbook/strict 注释修齐)。
2. **本周**:P1-3 重放链路 10 处异常契约(同型批量修,半天)→ P1-4 @Async 自调用 → P1-5 governance 事务 → P1-6 compose 凭据。
3. **上生产前**:P1-7 备份/PITR;P1-8 找组织管理员升级或换分支保护方案。
4. **排期**:P2 清单 + `/convention-audit` 清规范违规 + R5 规则修订 ADR。
