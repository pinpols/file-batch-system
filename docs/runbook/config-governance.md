# 配置治理与生效契约

## 1. 分类模型

配置用三个正交维度描述，禁止把敏感级别与生效方式混成一个枚举：

| 维度 | 值 | 含义 |
|---|---|---|
| 来源 | `STATIC` | Spring Boot 启动期绑定，来源为 yml、环境变量、ConfigMap 或 Secret |
| 来源 | `DYNAMIC_DB` | 以平台数据库为权威源，写后通过共享缓存失效传播 |
| 生效方式 | `RESTART_REQUIRED` | Bean 在启动期绑定，变更后必须滚动重启 |
| 生效方式 | `IMMEDIATE_AFTER_CONFIRMATION` | 数据提交后仍须等待目标实例确认，不能把 `PUBLISHED` 当作已全量生效 |
| 敏感级别 | `PUBLIC` / `SECRET` | 决定是否只能经 Secret/外部密钥系统注入，与是否重启无关 |

机器登记源是 [config-governance-registry.yml](./config-governance-registry.yml)。生产
`@ConfigurationProperties` 绑定点以登记表与源码校验结果为准；包含
`@ConfigurationPropertiesScan`、测试启动类或注释的文本命中数不作为验收数字。

## 2. 启动期配置

- 所有生产 `@ConfigurationProperties` 均登记为 `STATIC + RESTART_REQUIRED`，其中含密码、密钥、令牌或凭据的配置额外标为 `SECRET`。
- Helm 工作负载的 Pod template 同时携带 `checksum/config` 与 `checksum/secret`。共享 ConfigMap 或 Secret
  渲染结果变化会改变 template hash，由 Kubernetes 自动滚动更新 Pod。
- 不支持运行时重绑定。`@RefreshScope`、`ConfigurationPropertiesRebinder`、Spring Cloud Config、
  Nacos Config 和 Apollo 客户端由 CI 禁止，避免部分 Bean 刷新、连接池未重建和多实例漂移。
- 配置的可执行边界用 `@Validated` 和字段约束在绑定期校验。副本采样周期、Worker 并发与续租
  阈值必须为正数；首次采样延迟、Kafka 最小拉取字节数和等待时间允许零。已有跨字段背压检查
  保留在容器工厂；批量领取大小的退化规则和已有零值禁用语义不因本次治理改变。
- Worker Kafka consumer 使用 `WorkerKafkaProperties` 绑定原有 `spring.kafka` 配置键，保留
  `max-poll-interval-ms`、`metadata-max-age-ms` 等现有键和毫秒单位；不新增 Spring Boot Kafka
  自动配置依赖。producer 继续复用现有集中配置，手动 ACK、观测与批量背压行为保持不变。
- Spring 管理的异步线程池由初始化回调创建底层线程池，Bean 工厂方法不再手动调用
  `initialize()`；资源关闭仍交给 Spring 生命周期。

### Worker 注册端口

五类 Worker 仅使用主 WebServer 绑定后写入的 `local.server.port` 注册，不使用 `server.port`
配置值或固定类型端口兜底。`server.port=0` 可以使用实际随机端口；独立 management 服务的
`local.management.port` 不参与 Worker 注册。服务尚未绑定或运行时端口无效时拒绝注册，心跳沿既有
重试路径等待绑定完成；配置端口不代表监听成功。修改 `server.port` 的部署覆盖入口保持不变。

定向验证包括配置绑定成功/失败、Kafka 参数传递与跨字段容量限制、Spring 线程池初始化/销毁、
未绑定时拒绝注册，以及主服务和管理服务均使用真实随机端口时的注册结果。加载完整 Worker 应用的
集成测试使用 `RANDOM_PORT`，不能使用非 Web 上下文再依赖固定端口完成注册；不加载 Worker 注册循环的
纯组件测试仍可使用非 Web 上下文。上述验证不替代整套 sim 或 staging 验证。

### 本地验证记录（2026-10-08）

| 层级 | 范围 | 结果 |
|---|---|---|
| 定向测试 | 配置真实绑定、线程池初始化/关闭、Kafka 参数与背压、注册/心跳/租约及主服务与管理服务随机端口 | 104 个用例通过，失败和跳过均为 0 |
| Worker 集成测试 | 五类 Worker 的 21 个完整应用及相关集成测试类；包括业务库路由、导出分片、对象存储、SFTP/邮件、Atomic 执行器 | 70 个用例通过，失败和跳过均为 0 |
| 端口复验 | 空值校验收敛后重新执行 `AbstractWorkerLoopTest` 与 `WorkerPortBindingTest` | 20 个用例通过，失败和跳过均为 0 |
| Worker core 完整测试 | `./mvnw -ntp -pl batch-worker/core -am test` | core 235 个用例通过，失败和跳过均为 0 |
| 静态治理 | 配置登记、默认值同步、环境变量、Helm/生产覆盖入口、模块依赖和空值判断规范 | 通过；配置绑定登记共 134 处 |
| Sonar 本地增量报告 | 全仓分析后按 `origin/main` 至工作树的 Java 变更行过滤；报告目录 `reports/sonar/2026-10-08_00-31-04/` | 36 个包含新增行的 Java 文件，OPEN issue 0、待审安全热点 0 |

集成测试复用仓库 Testcontainers 基础设施；Orchestrator 注册接口使用既有 WireMock 支撑，
不代表真实控制面故障恢复或生产容量验收。未重启本地常驻应用，未执行整套 sim、Full Gate 或 staging 验收。
完整 core 测试的上游模块也无失败；common 的 2 个外部分片用例与 test-support 的 3 个 S3 活体契约
用例因未启用对应外部环境而跳过，不作为本轮通过证据。
Sonar 本轮未采集覆盖率；增量无问题不等于全仓历史问题清零，也不替代线上 required checks。
运行日志分别为 `/tmp/bfs-spring-config-tests.log`、`/tmp/bfs-spring-config-integration.log` 和
`/tmp/bfs-spring-config-port-recheck.log`；这些临时文件仅作本轮本机取证，不是仓库测试入口。

## 3. 动态数据库配置

`business-calendar`、`batch-window`、`job-definition`、`workflow-definition`、
`tenant-quota-policy` 与配置发布单登记为 `DYNAMIC_DB`。现有写链路必须保持：

```text
Console 事务写 DB -> afterCommit 删除共享 Redis 缓存 -> 各实例下一次读取回源 DB
```

配置导入、租户复制和 Excel 配置包 apply 也属于动态配置写入，必须遵循相同的事务后失效契约。
批量初始化按实际创建、更新或迁移的配置类别登记失效；dry-run、跳过项及回滚事务不得触发失效，且 dry-run 不调用可能落库的托管单作业 Workflow provisioner。
初始化的租户事务仍由 `TenantConfigInitTenantExecutor` 管理；`TenantConfigInitApplyHandlers` 统一管理逐项 `NESTED` 事务和子事务完成后的结果统计。
Job依赖/监控与Workflow图/版本分别由 `TenantJobDefinitionApplySupport`、`TenantWorkflowDefinitionApplySupport` 执行，协作者不新开事务，
仍按日历→Job→Workflow及原后续类别顺序应用。
Excel apply 在各配置项写入成功后登记对应的失效事件，事务提交后由共享 Redis 失效通道通知 Orchestrator
清理本机近缓存。新增配置类别或导入入口时，应同步核对其运行时缓存消费者与失效映射。

批量初始化、Config Sync 和 Job Bundle 实际应用 Job 默认参数时，与单条维护及 Excel 导入使用相同的掩码语义：
`UPSERT` 将敏感字段中的 `****` 恢复为目标租户同一位置的已有值；新建或目标缺少该值时，该项返回
`error.config.masked_secret_requires_value`，不会把掩码写成运行参数。非严格请求保留其它成功项，严格请求回滚同租户整包；
更新省略 `defaultParams` 时保持原值。`dryRun` 只预判，不执行此写入校验。

Workflow 节点参数遵循相同掩码规则，并限定为目标租户同一定义、同一节点编码的字段。初始化在删除旧图前读取原值；
新节点或新定义无法恢复的掩码使该 Workflow 条目失败，保存图与版本快照由原有逐项/严格事务一同回滚。
节点列表省略时保留原图；提交完整节点列表但省略某节点的 `nodeParams` 仍清空该节点参数，不改变完整图替换语义。

Workflow 配置包的 NodeSpec 同时携带 `crossDayDependencies` 与 `crossDayDependencyTimeoutSeconds`；
源配置导出、JSON 传输、目标节点与版本快照均保留这两个 ADR-018 字段。超时 `0` 表示永久等待，
未传时沿用 Mapper 的 `86400` 秒默认值；完整图替换省略跨日依赖时清空声明。复制后仍需在目标租户核对上游 Job 及结果版本就绪，
配置字段完整不等于目标业务日的依赖已经满足。
此处是 JSON 配置包链路；Excel 模板当前没有这两列，其更新路径只保留目标节点已有值，不能据此宣称 Excel 可完整迁移跨日声明。

配置发布动作必须提交 `expectedVersionNo`。服务端依次校验：

1. 请求版本等于目标发布记录版本；
2. 目标版本是同租户、同类型、同 key 的最新版本；
3. SQL 使用 `version_no + config_status` 做 CAS；
4. CAS 更新行数必须等于 `1`，否则返回 `STATE_CONFLICT`。

审批提交、批准和拒绝复用相同 CAS 约束，不能绕过旧版本门禁。

### 通知渠道创建与恢复

通知渠道创建按清洗后的编码查重和写入。同租户已有活动渠道时返回HTTP409；并发创建也只能有一个成功，冲突不会改写现有名称、类型或投递配置。不要把创建当作更新入口，修改活动渠道使用PUT接口。

软删除后按相同编码创建会恢复原行及主键、保留创建身份并应用新配置；首尾空白、全角等归一化后等价的编码属于同一身份，不会绕过查重。不同租户仍可独立使用同一编码，清洗后为空返回HTTP400。保存或恢复配置不会发送测试通知，实际投递按通知链路另行验证。

### 日历维护与节假日导入

日历时区必须能由 Java `ZoneId` 解析。HTTP 创建、更新在写入前拒绝无效值；JSON 初始化对将创建或更新的项在预演及实际应用中校验，省略时沿用默认时区，跳过已有项保持原跳过语义；Excel 配置包预览与应用报告无效时区。已有错误数据不会自动修复。

`CalendarSaveRequest`没有说明字段；`PUT /api/console/calendars/{id}`保留目标日历已有说明，包括Excel导入写入的内容。保存接口不改编码或启用状态，启用状态仍走独立开关；HTTP创建省略enabled时默认false，JSON/Excel初始化的默认值仍按各自原契约执行。

节假日HTTP批量导入及JSON初始化沿用字符串日期，SQL显式转换为DATE；Excel解析后的日期为LocalDate。JSON对已有且启用的日历提交非空节假日列表时整体重建，失败按原条目事务回滚；Excel按同租户、同日历、同日期upsert，保留未提交的日期与冲突行主键。日历说明保留与这些不同的集合更新语义不可混用。

### 窗口写入入口的共同校验

窗口实际写入前统一校验合法时区与本地时间，非跨日窗口必须满足开始时间早于结束时间。时间采用 ISO 本地时间（配置包/Excel 为 `HH:mm` 或 `HH:mm:ss`），不能依靠 PostgreSQL 将 `now` 等动态字面量转换为配置；显式跨日和相等时间表示全天的既有语义保留。该约束接在已枚举的创建、更新、初始化 upsert 与 Excel apply 四个 Java 写点之前。

`PUT /api/console/batch-windows/{id}` 的可更新配置字段省略或为 `null` 时沿用已有值，先合并再校验。因此只修改一个时间或仅关闭跨日标志，也不能留下反向或相等的非跨日区间；可在同次更新中调整区间后关闭跨日。非法结果返回 HTTP 400 / `INVALID_ARGUMENT`，原行不变且不触发窗口缓存失效。

JSON 初始化沿用原完整 upsert/default 语义：时区省略默认 `Asia/Shanghai`，跨日标志省略默认 `false`。无效项遵守原逐项失败统计；严格模式回滚同租户，部分模式保留合法兄弟项。`SKIP_EXISTING` 不应用未使用的输入；dry-run 仍是原动作预判，不代替实际写入约束验收。配置复制、Sync 和发布实际应用沿该初始化写入路径执行，不新增迁移或兼容分支。

Excel 更新省略可选跨日列时保留目标已有值，新行仍按 `false`；预览不能先按默认 `false` 误拒已有的合法跨日窗口。普通合法行不增加窗口查询，仅省略标志且非递增的区间行回查同租户同编码；应用在保留可选值后再次校验，不能只信任此前预览。显式 `false` 不得被已有 `true` 覆盖，非法行在预览中报告并阻止应用。多实例缓存传播、完整上传/审批界面与目标调度时刻仍以各自验收证据为准。

### 配置同步响应丢失与原请求恢复

Console 的配置同步导入在发送前保留原请求身份；同一标签页刷新后按部署/API 地址、操作者及审计租户恢复，不保存配置正文。存在未确认命令时不能生成另一条导入身份。重试必须恢复原环境、目标租户和 JSON，沿用原键；超过23小时不再重试，只核对审计。存储损坏、不可用或无法计算可靠摘要时阻止导入，不能通过生成新键绕过。

管理员通过 `GET /api/console/config/sync/requests/status` 携带原 `Idempotency-Key` 查询审计。`found=false` 不证明未执行；读到 RUNNING 后按原日志 ID 核对目标收据，缺失收据继续保持未知。只有原导入返回确定结果，或审计已确认终态后，才解除对应恢复记录；旧回包不能删除另一条命令。查询、核对和业务导入是不同动作，核对不会重新应用配置。

配置复制和作业包使用独立的回查接口 `GET /api/console/config/batch-operation`。管理员携带原 `Idempotency-Key`、原请求的 `X-Tenant-Id`、`auditTenantId` 和 `operationKind`；原请求未提供租户头时回查也不提供。复制的审计租户是源租户，作业包的审计租户是原请求所属租户；类型分别为 `TENANT_COPY`、`JOB_BUNDLE_CREATE` 和 `JOB_BUNDLE_IMPORT`。回查身份沿用原租户头、POST 方法、原路径和幂等键的组合，不能把其他入口的相同键当作同一操作。

回查按原目标顺序返回回执，重复目标以各自序号区分。`COMPLETE` 仅表示全部目标回执已确认，目标是否成功仍以该项 `result.success` 为准；缺失或不匹配的回执为 `UNKNOWN`。`UNKNOWN` 与 404 都不能证明未执行，回查只读取记录，不会自动重跑或续接。只有携带原键、非 dry-run 且完成登记的新操作才有回查依据；试运行、无键操作和旧操作不保证有登记，普通租户初始化也不属于该回查接口。

上述记录由 [V373](../../db/migration/V373__persist_config_batch_operation_receipts.sql) 提供：登记按审计租户隔离，回执按目标租户隔离，成功回执与目标配置在同一事务提交。V373 不自动给独立的 Console 数据库角色授予表权限；若部署采用分离账号，部署方须核对该角色对两张新表所需的读写权限及 RLS，并确认原有 schema、V232 等权限。现有 [V372 队列授权 SQL](../../scripts/db/config-cache-invalidation-role.sql) 只覆盖失效队列，不会授权 V373 的两张表。

部署人员确认目标平台库、已有 Console 主库角色及该角色已具备 `batch` schema 的 `USAGE` 后，可使用具有两表授权权利的数据库管理员账号执行：

```sh
psql -X -v ON_ERROR_STOP=1 -U "$PLATFORM_DBA_USER" -d "$PLATFORM_DATABASE" \
  -v console_role="$CONSOLE_PRIMARY_ROLE" <<'SQL'
BEGIN;
GRANT SELECT, INSERT ON batch.config_batch_operation, batch.config_batch_target_receipt
  TO :"console_role";
COMMIT;
SQL
```

连接地址和端口沿用部署环境的 PostgreSQL 客户端配置；`console_role` 是已有角色，psql 将其按标识符引用。该授权仅覆盖两张 V373 表的 `SELECT`、`INSERT`；V373 的表不使用序列，无需额外序列权限。两表已有按租户上下文执行的 RLS 策略，仍需验证 Console 连接使用的角色及租户上下文。既有业务对象、V232 函数和 V372 队列等权限另行保留和核对，不能用此授权替代。

目标租户列表不接受 null、空串或空白。作业包导入同样校验每个元素，初始化应用服务在启动任何目标事务前校验整组身份；内部调用也不得先提交前一租户后才发现后一目标非法。HTTP 校验与同步应用服务均在创建审计之前拒绝无效身份，避免无目标写入留下无法收敛的审计。不同租户的合法部分失败继续沿既有目标事务和收据处理，不扩大全批事务。

## 4. Console 展示语义

配置复制的源租户不得出现在目标列表。复制会剥离环境端点与凭据，回写源租户可能清空原渠道配置；服务在读取源配置之前返回参数错误，不处理其他目标。面板更换源租户时移除重合项，保留其余目标。源目标相同的只读差异查询不等于复制写入，不扩大此写保护到查询契约。

复制、预览与矩阵的租户/作业集合元素不接受null、空串或空白，配置类型集合不接受null元素。可选作业/类型集合省略或为空仍保留原先的全量范围语义。协议配置类型大小写解析不受JVM默认语言影响。

Workflow配置导入按精确编码读取最高版本，不能使用列表模糊搜索定位写入目标。新建Workflow会检查该编码的全部当前版本，编辑升版后仍不能重复创建同码定义。配置包只带每个编码当前最高版本及其节点/边；当前版本停用时保留停用状态，不回退到旧的启用版本。历史快照继续按原定义ID和版本读取，不因本轮修复被删除。

配置包复制、导出、只读预览及作业矩阵复用完整租户集合读取，不使用交互列表的200条单页上限。Workflow节点和边分别完整读取；节点合法上限仍由现有DAG校验器控制，不能将节点上限套用到边数。全量读取使用既有只读重复读快照，目标写入仍按租户划分事务，普通列表分页策略保持不变。

作业矩阵的显式 `baselineTenantId` 可以在展示的 `tenantIds` 之外；服务仍加载该基准计算差异，响应不增加额外展示行。基准中缺少作业按缺失事实比较，不把没有加载基准当作无差异。

复制面板编辑输入、换账号、关闭或重开后需要重新预览；过期预览不恢复，也不影响当前读取的忙碌状态。这些端点均为POST，业务只读预览也按服务端HTTP方法策略受维护冻结约束；校验期间重复点击只发起一次。已经发送的复制不因关闭面板而撤销，未知结果按上面的原请求回查和人工核对边界处理。

租户初始化和复制按目标租户隔离事务。严格包已确认回滚时，结果记为该租户失败，其他租户继续；事务提交或连接异常不能证明未生效，入口停止后续目标并向上传播。遇到此类错误先按原操作核对已确认结果及配置事实，不得按“失败”直接重做整批。配置复制与作业包的原键回查和配置同步的审计恢复是不同接口；普通初始化及未登记的旧操作不能套用两者的回查保证。

手写初始化 JSON 不得覆盖界面选择的目标租户、写入模式或试运行开关。只有目标和条目全部成功才显示初始化完成；失败或部分失败保留结果面板供核对。试运行不会显示正式初始化完成。

初始化与配置包的配置列表可省略或传空列表，但列表中不得包含 `null` 条目；作业依赖规则、Workflow 节点和边同样如此。此类输入在请求校验阶段返回 400，不进入租户配置写入。

初始化面板的校验和写入绑定当前打开代次、目标、输入、账号和写权限；校验等待时关闭或改变这些上下文，原操作不续发。请求已经发送后，关闭面板不取消服务端业务；旧响应只停止更新当前页面，仍需按配置事实核对。

配置发布列表详情明确展示：

- `configSource`：配置来源；
- `activationMode`：生效方式；
- `restartRequired`：是否需要滚动重启；
- `applyConfirmationStatus`：实例确认状态。

当前 `config_release` 是发布治理记录，不是通用配置执行引擎。因此发布后的状态明确返回
`CONFIRMATION_REQUIRED`，不能声称所有实例已经采用 `config_payload`。具体业务配置仍以对应业务表写入和
Redis `afterCommit` 失效为生效边界。后续若引入配置中心，必须先实现“目标实例快照、实例 ACK、超时失败、
版本水位指标”完整闭环，再允许返回 `CONFIRMED`。

## 5. 多实例一致性

动态数据库配置变更后，Console 不只删除共享 Redis key，还会在事务 `afterCommit` 后发布
`batch:config:invalidation` 事件。事件包含：

- `tenantId`、`type`、`code`：定位要失效的配置；`code=*` 表示该类型整租户批量失效；
- `revision`：全局单调递增版本，用于跨实例比较处理水位；
- `keyRevision`：单 key 单调递增版本，用于拒绝乱序旧事件；
- `changedAt`：用于计算事件传播延迟。

Orchestrator 每个实例都订阅该频道并清理本机 Caffeine 近缓存，避免仅删除共享 Redis 后本地缓存继续返回旧配置。
同时每 10 秒读取 Redis 全局 revision；若发现本机 `applied_revision` 落后，会清空全部本地配置缓存并推进水位，
作为 Pub/Sub 瞬断或实例重连期间漏事件的兜底。

作业、Workflow、日历、窗口和配额的共享配置缓存使用带版本的独立键：键包含对应租户和类型的
通配修订号及配置项精确修订号。对应修订号成功推进后，即使较早的数据库读取迟到回填，也只能写入旧版本键；
旧版本键按原有五分钟 TTL 过期。本地失效与回填使用同一进程内代次保护；负缓存还记录所属版本。
批量通配事件仍会按类型清空该实例所有租户的本地缓存，并非按租户清空。

显式声明 `REPEATABLE_READ` 或 `SERIALIZABLE` 的事务直接读取数据库快照，不读写上述配置缓存；
其他活跃事务可以读取已命中的缓存，但不会把本事务的数据库回源结果写入共享或本地正负缓存，
避免 MyBatis 会话内旧结果影响事务外读取。非事务读取仍可按缓存未命中路径回填。只有从 Spring
事务上下文能识别显式隔离级别时才适用固定快照分支；若数据源默认隔离级别被外部设置为固定快照且事务未显式声明，
不能据此认定该分支已覆盖。

读取修订号时区分键不存在与 Redis 命令失败：前者作为初始版本，后者在本地缓存未命中时回源且不新增缓存。
已经命中的本地正缓存仍遵守原有短 TTL 或事件/对账失效。当前开发分支的 [V372](../../db/migration/V372__persist_config_cache_invalidation.sql)
在作业、Workflow、日历、窗口和配额配置表变更时，与配置写入同事务记录失效意图；Console 在提交后仍尝试立即失效，
并由后台投递器领取待处理记录、推进版本并发布事件，失败后保留记录重试。该实现可能对成功的立即失效再次投递；
队列清空只说明投递操作完成，不表示所有订阅实例均已应用。隔离 PG/Valkey 的局部版本写入失败、Redis 暂停恢复及
丢广播对账用例已通过；该测试使用同 JVM 服务和订阅器，不能以目标回执或单次投递成功代替实例生效证据。

投递依赖 Console 运行并能访问队列表。V372 与现有 V232 一样按迁移执行账号建立权限和 RLS 策略；
若 Console 主库使用独立账号，需由部署方明确可信角色并核对队列、序列、RLS 及 V232 函数权限，
不能仅授表权限或对 PUBLIC 放开。隔离 PostgreSQL 和真实 Mapper 已验证专属角色的表权限与 RLS 策略组合；
Spring 使用独立账号的部署及 V232 函数权限仍需在目标环境核实。Redis revision 被清除或回退、旧版 Orchestrator
滚动升级和目标环境恢复仍需单独验证；性能与积压容量尚未测量。

V372 完成后，如确认 Console 主库使用独立的可信数据库角色，平台库 DBA 可在核对目标库和角色后执行
[队列角色授权 SQL](../../scripts/db/config-cache-invalidation-role.sql)：

```sh
psql -X -v ON_ERROR_STOP=1 -v console_role="$CONSOLE_PRIMARY_ROLE" -v action=grant \
  -f scripts/db/config-cache-invalidation-role.sql
```

角色必须已经存在且具有 `batch` schema 的 `USAGE`；脚本只授该角色 V372 队列表的
`SELECT/INSERT/UPDATE/DELETE`、队列序列的 `USAGE` 和对应专属 RLS 策略，不创建角色或开放给 `PUBLIC`。
V232 的两个配置版本函数及其他平台对象权限仍由原有授权流程维护。脚本支持重复执行；角色轮换时，先为新角色授权并验证，
切换完成后以旧角色执行同一命令并将 `action` 改为 `revoke`。撤销只移除该角色的队列权限和脚本对应策略，不撤销
schema 或 V232 权限；继承角色、其他策略或对象所有者权限可能仍然允许访问，需由 DBA 另行核对。

本地缓存未命中新增两次独立的 Redis 版本读取；固定快照事务以及活跃事务回源不预热缓存会增加数据库读取，
其负载和容量影响尚未进行性能验证。

关键指标：

| 指标 | 位置 | 含义 |
|---|---|---|
| `batch.console.config.invalidation.published_revision` | Console | Console 已发布的全局配置失效版本 |
| `batch.console.config.invalidation.publish.total{result}` | Console | 发布成功 / 失败次数 |
| `batch.orchestrator.config.invalidation.applied_revision` | Orchestrator | 单实例已应用的最高配置失效版本 |
| `batch.orchestrator.config.invalidation.event.total{result}` | Orchestrator | 事件应用、拒绝旧事件、失败次数 |
| `batch.orchestrator.config.invalidation.reconcile.total` | Orchestrator | revision 落后后触发全量本地失效的次数 |
| `batch.orchestrator.config.invalidation.event_lag` | Orchestrator | 事件从 Console 发布到本实例处理的延迟 |

生产告警建议：

- `max(batch.console.config.invalidation.published_revision) - min(batch.orchestrator.config.invalidation.applied_revision) > 0`
  且持续超过 1 分钟，说明至少一个 Orchestrator 未跟上动态配置失效；
- `batch.console.config.invalidation.publish.total{result="failure"}` 或
  `batch.orchestrator.config.invalidation.event.total{result="failure"}` 增长，说明 Redis 发布或消费异常；
- `reconcile.total` 持续增长，说明 Pub/Sub 不稳定，虽然缓存会被兜底清理，但应排查 Redis 连接质量。

## 6. 校验命令

```bash
python3 scripts/ci/check-config-governance.py
helm lint helm/batch-platform
helm template test helm/batch-platform | rg 'checksum/(config|secret)'
```

新增或删除配置绑定点后执行：

```bash
python3 scripts/ci/check-config-governance.py --write
```

登记表必须与源码一同提交，PR Gate 和 Full CI Gate 都会阻断遗漏或未经评审的热刷新机制。

## 5. 作业包面板与向导上下文

JSON作业包的新建、导入及导出受服务端ROLE_ADMIN限制；租户管理员使用普通作业定义入口。向导和导入发出请求后，输入、租户、账号、权限、维护状态或面板会话变化会使旧反馈失效，不会撤销已经提交的配置。结果不确定时核对原目标持久化事实，不能仅因当前面板未出现成功提示而更换请求键重做。

向导切换租户或操作者立即清空旧关联选项和选择；元数据、渠道、模板、Workflow与告警路由的旧响应不能恢复先前选项。异步步骤校验只推进发起时的同一步，重复点击不叠加推进。
