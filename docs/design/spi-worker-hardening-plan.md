# SPI Worker 安全硬化与优化方案(ADR-029 后续)

> 适用模块:`batch-worker-spi`(shell / sql / stored-proc / http 四类原子任务执行器)
> 性质:dual-use(RCE 级)能力,默认全关。本文 = 代码审查发现 + 业界对比 + 分阶段优化路线。
> 关联:[ADR-029 专用 SPI worker](../architecture/adr/ADR-029-dedicated-spi-worker.md)、`docs/design/task-spi-design.md`

## 0. 核心原则

代码层的语句/命令白名单**会被绕过**(本文 SSRF、大小写、DO 块都是例证)。安全的重心应**下沉到运行时与基础设施**:

> DB grant / `has_function_privilege` · NetworkPolicy 出口管控 · OS 沙箱(seccomp/namespace)· 独立低权限凭据

代码白名单保留为**纵深防御的内层**,不作唯一防线。本方案据此排优先级:**先补绕过型漏洞,再把控制下沉,最后上架构级隔离**。

---

## 1. 代码审查发现(已逐条核实源码)

severity:CRITICAL 直接可利用 / HIGH 条件可利用或设计缺陷 / MEDIUM 需特定配置 / LOW 低概率或 foot-gun。

| ID | 文件:位置 | 级别 | 问题 | 利用 / 后果 | 修复方向 |
|----|----------|------|------|------------|---------|
| H-1 | `http/HttpTaskExecutor.java` `validateHost`/`matchesGlob` | **CRITICAL** | SSRF:对 `uri.getHost()` 纯字符串 glob 匹配,从不解析 IP | `http://[::ffff:169.254.169.254]/`、八进制/十进制 IP 绕过黑名单直达云 metadata;DNS rebinding/TOCTOU 完全未覆盖;默认 `allowed-host-patterns` 空 = 仅靠黑名单 | 解析 `InetAddress`,按 `isLoopback/isLinkLocal/isSiteLocal`+硬编码 `169.254.0.0/16` 拦**解析后 IP**;生产强制白名单;连接 pin 到已校验 IP |
| D-1 | `helm/.../worker-spi.yaml` + values | **HIGH(设计)** | ADR-029 的最小权限隔离是**部署期可选**:`serviceAccountName`/`envFromSecretName` 留空回退共享凭据 | ops 不显式配 → spi worker 与其它服务同权限跑,"RCE 隔离"沦为名义,无 fail-fast | 执行器启用 + 检测到共享凭据时启动告警/拒绝;helm 对缺独立 SA fail/lint |
| S-1 | `sql/SqlTaskExecutor.java:154-156`<br>`storedproc/StoredProcTaskExecutor.java:154-156` | **HIGH** | `dataSourceBean` 任务参数可指定任意 DataSource bean,无白名单 | 能写任务参数者 `getBean(任意名)` → 指向高权限/别租户库,跨权限边界 | 加 `allowed-data-source-beans` 白名单,默认仅配置的那个 |
| P-1 | `storedproc/StoredProcTaskExecutor.java` `requireAllowed` | **MEDIUM** | 过程白名单 `Set.contains()` 大小写敏感,PG 标识符大小写不敏感 | `batch.Refresh_Metrics` 过不了精确白名单却仍命中 `allowedSchemas` → 精确白名单失效 | 用 `has_function_privilege()` 走 DB 判定(见 §2.4),或比较前 `toLowerCase` |
| Q-1 | `sql/SqlTaskExecutor.java` `detectStatementType` | **MEDIUM** | PG `DO $$ … $$` 匿名块判为 `OTHER` | `allowed-statement-types` 含 `OTHER` 时,匿名块执行任意 PL/pgSQL,绕过 DDL 白名单 | `DO` 归 `DDL`;文档注明 OTHER ≈ 不受限 |
| P-2 | `storedproc/StoredProcTaskExecutor.java` `readRefCursor` | **MEDIUM** | REFCURSOR 无行数上限(scalar OUT 有 `maxOutBytesPerParam`,cursor 无) | 存过返回大 REFCURSOR 全读进堆 → OOM DoS | 加 ref-cursor fetch size + 行上限 |
| SH-1 | `shell/ShellExecutorProperties.java:65` | **MEDIUM** | `argRegexAllowlist` 默认 `^[\w\-./@= :+,]*$` 允许 `..` | 配 `cp/tar` 等命令时 `../../etc/passwd` 过校验 → 路径穿越 | 默认拦 `..` 序列或文档强调 |
| SH-2 | `shell/ShellTaskExecutor.java:289/362` | **LOW** | reader 结果 map 用 `stdout-<pid>` 当 key(单例共享 map) | PID 复用极端情况跨任务串 stdout(概率低、设计糙) | 用自增计数/UUID 当 key |
| H-2 | `http/HttpTaskExecutor.java:327` | **LOW** | `HttpClient` 未显式 `followRedirects(NEVER)` | JDK 默认 NEVER 当前安全;改 ALWAYS 则只校验首跳 → 全裸 | 显式 NEVER + 注释;若需重定向逐跳 `validateHost` |

### 已核实"做得好"(保留)
- Shell 用 **`ProcessBuilder` arg-list 而非 `bash -c`** → shell 元字符是字面量,天然防命令注入;`env.clear()` 后只透传白名单 env。
- SQL `splitStatements` 正确跳过引号/注释里的 `;`;事务 commit/rollback + `finally` 恢复 autoCommit;timeout **只能缩短**。
- stored-proc `procName` 严格标识符正则;`isProcedure` 用 `PreparedStatement` 占位符。
- registry 重复 taskType 启动 fail-fast;全部 `@ConditionalOnProperty` 默认关。

---

## 2. 业界可借鉴做法

### 2.1 HTTP / SSRF(OWASP SSRF Cheat Sheet、云 IMDSv2)
- **白名单优先(deny by default)**,而非黑名单。
- **解析 DNS → 校验解析后 IP 私网段 → 连 pin 住的 IP**(防 rebinding TOCTOU)。
- 禁重定向或逐跳校验;出口走 **forward proxy / NetworkPolicy** 兜底。
- 云侧 metadata 用 **IMDSv2(强制 token)**。

### 2.2 Shell / 命令(Airflow K8sExecutor、XXL-JOB、nsjail/gVisor)
- 趋势:**容器/pod per task**(非 root、只读 rootfs、drop caps、seccomp、cgroup cpu/mem/pids 限额、网络限制)。
- 轻量替代:**nsjail / bubblewrap**(namespace+seccomp 沙箱,不用整容器)。
- **命令模板化**(Rundeck job / Ansible module):只接"模板名 + 类型化参数",消灭自由 arg 注入面。
- XXL-JOB 教训:GLUE 跑任意代码 → 多个 RCE CVE;实战靠 **网络隔离 + admin↔executor accessToken**。

### 2.3 SQL(dbt、Airflow SQL 连接惯例)
- 真正控制在 **DB 侧最小权限 role**;SELECT 走 **`READ ONLY` 事务**。
- **服务端 `statement_timeout`**(客户端超时能被绕)。
- 限制为单语句 / 参数化模板优于自由多语句。

### 2.4 Stored Proc(PG 原生权限 + 安全惯例)—— 重点
存过安全本质是"**谁能 CREATE / 谁能 EXECUTE 哪个**",PG 用 grant + catalog 管得最准:
- **DB 原生 `GRANT EXECUTE` 当真闸门**:执行器低权限 role 只 GRANT 指定过程 → 应用白名单被绕也由 DB 拒。
- **`has_function_privilege(current_user,'schema.proc(argtypes)','EXECUTE')` 当校验**:DB 是真相源,大小写/schema/重载语义天然正确,**顺手修 P-1**。
- **会话级 pin `search_path`**(PG CVE-2018-1058 后官方建议):CALL 前 `SET search_path = pg_catalog,<目标schema>`,防 search_path 注入劫持。
- **`quote_ident` 拼 CALL** 各标识符段。
- **OUT 类型从 catalog 读**(`pg_proc.proargtypes` / `information_schema.parameters`),不信 payload 的 `outParams` 类型串。
- **`prosecdef` 感知**:SECURITY DEFINER 存过以属主权限运行(天然提权),默认只允许 INVOKER,DEFINER 需额外显式白名单。
- **pgAudit**:服务端独立记录每次过程调用(防篡改审计轨)。
- **非幂等存过 + 重试 = 重复副作用**:`TaskCapability.idempotent` 已标 false,确保 orchestrator 不盲目重试。

### 2.5 跨执行器 / 架构
- **default-deny 出口 NetworkPolicy**(只放行平台库+kafka+显式 SPI 目标)——SSRF/外泄的基础设施兜底。
- **dual-use 执行全程审计 + 密钥脱敏**(Airflow secrets masking):经 `console_operation_audit` 记 who/what/params-hash,输出/错误按模式脱敏。
- **审批门控**:shell/DDL 任务定义走审批或 dry-run(复用项目已有 DryRunGuard + Web Push)。
- **任务派发签名 + worker↔orchestrator 鉴权**(XXL-JOB accessToken / Temporal namespace):防编排链被攻破后注入任意命令。

---

## 3. 分阶段优化路线(逐步实施)

> 每条:改动文件 → 怎么改 → 验收 → 工作量(S<0.5d / M~1d / L>2d)。全程保持**默认关 + 向后兼容**;新单测/IT 覆盖。

### Phase 1 — 绕过型漏洞 + 设计缺陷(P0,优先)
| # | 对应 | 改动 | 验收 | 量 |
|---|------|------|------|----|
| 1.1 | H-1 | `HttpTaskExecutor.validateHost`:解析 IP + 私网段拦截 + pin 连接;`HttpExecutorProperties` 加 `enforce-allowlist` 默认 true | 单测:IPv4-mapped/八进制/IPv6 字面量、rebinding mock 全被拒;白名单内放行 | M |
| 1.2 | H-2 | `HttpClient` 显式 `followRedirects(NEVER)` | 单测:302→Location 内网 不被跟随 | S |
| 1.3 | S-1 | sql+storedproc 加 `allowed-data-source-beans` 白名单,默认仅 `dataSourceBeanName` | 单测:非白名单 bean 名被拒 | S |
| 1.4 | D-1 | 启用 dual-use 执行器 + 检测共享凭据 → 启动告警(可配 fail);helm lint 警告缺独立 SA | IT:共享凭据下启动日志含告警 | M |

### Phase 2 — 控制下沉(P1,借鉴-立刻类)
| # | 对应 | 改动 | 验收 | 量 |
|---|------|------|------|----|
| 2.1 | §2.4 P-1 | stored-proc 改用 `has_function_privilege` 校验 + pin `search_path` + `quote_ident` | IT(真 PG):大小写变体、search_path 劫持 均被正确处理 | M |
| 2.2 | Q-1 | `detectStatementType`:`DO` 归 DDL;文档注明 OTHER 风险 | 单测:`DO $$...$$` 在仅 SELECT 配置下被拒 | S |
| 2.3 | P-2 | REFCURSOR fetch size + 行上限(新 `max-ref-cursor-rows`) | IT:大游标被截断不 OOM | S |
| 2.4 | §2.3 | SQL SELECT 走 `READ ONLY` 事务 + 服务端 `statement_timeout` | IT:只读事务里 DML 被 DB 拒 | M |
| 2.5 | §2.5 | dual-use 执行接 `console_operation_audit` + 输出/错误密钥脱敏 | IT:审计落表;敏感串被掩码 | M |
| 2.6 | SH-1 | shell arg 默认拦 `..`;`SH-2` reader map 改 UUID key | 单测:`..` 参数被拒;并发不串流 | S |

### Phase 3 — 基础设施 / 架构级(P2,借鉴-架构类)
| # | 对应 | 改动 | 量 |
|---|------|------|----|
| 3.1 | §2.5 | helm 给 worker-spi 配 **default-deny 出口 NetworkPolicy** 模板 | M |
| 3.2 | §2.2 | shell 进 **nsjail/bubblewrap** 沙箱(非 root/只读 FS/seccomp/ulimit);或命令模板化 | L |
| 3.3 | §2.4 | 部署侧:SPI worker 专用低权限 DB role + 按需 `GRANT EXECUTE` + pgAudit(runbook) | M |
| 3.4 | §2.2/2.5 | 任务派发签名 + worker↔orchestrator 鉴权;高危任务定义审批门控(复用 DryRunGuard) | L |
| 3.5 | §2.2 | (大赌注)pod/容器 per task(K8sExecutor 式强隔离) | L+ |

---

## 4. 验收与回归约束
- **默认关 + 向后兼容**:所有新约束默认不改变现有"默认安全"行为;生产开关渐进收紧。
- 每条改动配 **单测**(校验逻辑)+ 关键项配 **testcontainers IT**(真 PG/真 HTTP mock)。
- 复用现有 e2e(`SpiTaskPipelineE2eIT` 等)+ `strict-verify §7` 做回归。
- 安全相关默认值变更(如 `enforce-allowlist=true`)需在 changelog + 升级说明标注。

## 5. 执行建议
按 Phase 顺序在 `fix/spi-worker-hardening` 分支逐条 PR(每条独立可回滚)。Phase 1 四条为安全必修,建议最先合入。
