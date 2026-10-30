# fix/wdi-current 本地收尾验证（2026-10-25）

## 基线与完成口径

实际后端为 `~/.codex/worktrees/wdi-current/file-batch-system`，分支 `fix/wdi-current`、HEAD `f3d5e400d`；实际前端为同级 `batch-console`，同名分支、HEAD `662d254`。两仓已有暂存、未暂存和新增内容均保留，HEAD 不能单独代表验证源码。原 Workflow 工作树保持冻结；没有提交、推送或创建 PR。

本批按六组收尾：测试规约、模型候选、脚本守卫、安全/质量、归档对照、最终运行联验。以下记录本批已取得证据，运行联验及后续用户追加的 Worker 批量报告接线分别补记，不能用较早 SIM 代表后续代码。

**本轮交付状态：原六组本地收尾及批量链路已完成。** B201代表性归档对照和最终事务回归完成；容量/尾延迟签收、安全工具例外及其它会话功能仍按各自边界保留。最后只读健康检查八个本地服务均UP，不据此冒称它们已重新部署B201归档补丁。

R03本批本地恢复及联合运行条件已补齐，正式R01–R36更新为24项已验证、12项部分验证；其余行保留EX-01–EX-04的精确范围。本地通过不代替目标部署、容量签收、多实例传播、真实服务商或上线观察。数据库全面加外键/索引/分区的扩项保持暂停。另见下文新增维护项，不能据此声称全仓没有问题或已可发布。

## 已整合实现与定向验证

| 范围 | 实际改动及结果 |
|---|---|
| Java 测试 | 145 文件；21 SQL 实参、26 JDBC 嵌套断言具名承接，保留原事务/轮询/异常回调；11 openMocks 和 105 普通测试类的 252 字段 mock 改为声明式初始化；30 普通 JUnit 断言迁移；17 LENIENT 逐项裁定，4 移除、13 保留真实理由。343 条原定位全部分类。最新 XML 按类+方法去重 847 例 PASS、0 失败/错误/跳过；895 个原测试声明未删除。 |
| 模型候选 | 651 位置逐项裁定：506 Map 中 400 为动态边界、101 已类型化、2 方法已移除、2 本批规约修正、1 真实映射缺陷；145 构造中 101 原整改、14 当前已改变、29 有依据保留、1 提取具名局部量。原 44/482 待分类快照关闭，不等于修复 526 个缺陷。 |
| 实际 Mapper 绑定 | 模板查询 Java 返回 Map、XML 返回视图的 ClassCastException 经真实 PG 首红后修复；固定投影复用既有类型并明确 resultMap；ConfigChangeLog 使用具名参数。103 个不同用例通过，覆盖 24 条固定投影的实际非空结果绑定；24 是查询数。 |
| Kafka 日志 | 消费重试耗尽不再输出 LaunchEnvelope 敏感 payload，保留元数据、原因和持久恢复语义；首红 2 例后修，关联 4 例通过。 |
| 调度器关闭 | 自定义 scheduler 沿用共享 BatchSchedulingProperties 的关闭策略，修复 Spring 暂停上下文下关闭重复等待；6 个组件例通过。原 53 例独立复跑 Maven exit 0、fork 自然退出、无超时 dump。53 已属于 847 集合，不再相加。 |
| Console 启动 | local 配置同时扫描两个同名 StatusCountView，导致真实 JAR 不能启动；复用已有 ClusterStatusCountView 并删除旧重复类型。真实 application-local.yml 别名注册先红，修复后 12 例通过；clean 重建 Console，旧 class 不再打包。 |
| Python 依赖 | 仅将 aiohttp 3.14.3→3.14.4、certifi 2026.7.22→2026.10.19；锁定图 37 包不变，95 个不同 Python 用例通过。 |
| 脚本与镜像上下文 | 22 个路径/类别、30 个高风险操作逐项核对后精确登记；原663操作/252分组，追加夹具副本的三处精确清理后为666操作/254分组，9条旧登记未擅删。14个相关脚本测试通过。Docker COPY实验验证6个根/嵌套秘密环境文件排除、7个模板/资源仍可进入；仅新增.env/.env.*排除并保留.env.example。 |
| 前端 | 冻结 Workflow 前端的 330 已跟踪路径、55 新功能路径完整迁入，连同本批测试修复共 429 路径（62 新增/6 删除）；历史 output 和新增 Skill UI 元数据未混入。保留并发 CI 文档图。174 文件/1030 单测通过，类型、lint、API、i18n、fixture、维护性和生产构建通过。11 个定向浏览器例为生产预览/受控响应证据，真实后端联动另列。 |

测试和模型分类清单分别见 [Java 测试裁定](../audit/wdi-test-candidate-disposition-2026-10-25.csv)与[模型裁定](../audit/wdi-model-candidate-disposition-2026-10-25.csv)。未删除首红日志，未扩大 LENIENT、规则基线或安全忽略以换取通过。

## 归档多批对照

执行当前可复现的 `ArchiveDiagnosticCadenceProbeIT`，真实 PG 上三组交替 A/B，每臂 29 个实例，批次均为 10/10/9；每臂 3 次事务提交。旧逐批诊断调用为 3 次，新 scheduler cadence 为 1 次；归档前后全行 JSON 对照一致，自有租户及冷热数据清理为 0。

| 组 | 旧耗时（秒） | 新耗时（秒） | 旧/新诊断次数 |
|---|---:|---:|---:|
| 0 | 1.068 | 0.744 | 3 / 1 |
| 1 | 0.711 | 0.444 | 3 / 1 |
| 2 | 0.490 | 0.341 | 3 / 1 |

最初将 Probe 与负例归档类合跑，隔离前置检查发现同 JVM 已有 11 条负例残留而失败。保留失败，不放宽隔离断言；改为独立 JVM 后 Probe 1/1 通过。按每类最新报告，归档相关 6 类共 47 个不同用例通过。该 29 行本地微基准不能外推生产吞吐或 R36 容量签收；历史 5 万终态 EXPLAIN 仍只保留其原环境结论。

## 安全扫描边界

- 秘密扫描原 6 候选：2 个测试固定值已改为运行期 UUID，4 个为审计 CSV 的 SHA-256 值误报；保留 hash 和原扫描报告，不增加宽泛忽略。
- SAST 使用 199 规则、5490 targets，0 parser error；18 发现逐项追踪后未确认本范围存在可利用路径。原 9 ERROR/9 WARNING 不改写成零发现。
- Python 锁定依赖修复后 Trivy 漏洞 0；IaC 现行策略下 HIGH 0。NVD 依赖检查因缺 API/cache 数据失败，仍未通过，不以 Trivy 代替该工具结论。
- 原八镜像共用基础层的OpenSSL高危已修复，APT只升级openssl/libssl3两包。B199及追加B200各八镜像均重建复扫，高危/严重/秘密为0；B200每镜像仍有63 MEDIUM/16 LOW。构建上下文指纹与并发Console源码变化分别留证，不称整个工作树冻结或零漏洞。
- 认证DAST已经执行，四条受保护GET真实认证返回200、匿名401；12条Low/Informational发现导致策略exit2，属于WARN_NONZERO。完整边界见[安全验证](wdi-local-security-validation-2026-10-25.md)。

## B200 批量主链的实际接线

生产源码及相邻测试七个文件已按交付清单SHA写入实际工作树。最终客户端SHA为`f8c48fd4b990297e644c365b9a0902235e6fd821c1f0c57a64b3050720210455`；没有改动原registry/incarnation、invocation CAS或服务端逐项事务。

| 环节 | 当前行为与验证 |
|---|---|
| 触发 | 前端逐个Workflow发起，各自拥有幂等身份、逐项结果和失败重试；预检独立。保留既有单条服务端入口，没有建立整批共同事务。真实认证浏览器补证见下节。 |
| 认领 | 按taskId匹配乱序响应，拒绝重复/外来/缺失身份，校验config的task/tenant；未知或损坏响应沿原单条核对。保持服务端逐task CAS与最大批数限制。 |
| 执行 | 同一poll内串行执行，跨listener保留既有并发；不因批量认领直接扩大执行并发。 |
| 续租 | 全部已领任务先登记，包括排队项；原定时renew-batch与invocation/registry围栏保持。批响应改为taskId匹配，避免别的任务renewed/cancelRequested套给当前项；未知响应回退单条。 |
| 回报 | 同步有界微批、同租户、唯一taskId；HTTP在途/队满/关闭不继续排队。逐项确认，未知项沿原剩余HTTP预算重试，耗尽后交原Outbox，后台重投绕过微批；不重新执行业务。 |

136个不同用例、13类最终组合通过，0失败/错误/跳过；包含原始首红和最后client/renew增量。99、51等中间复跑不能相加。N=8受控HTTP验证8次单条变为1次批量请求，只证明请求数变化，不证明时延/吞吐改善。真实服务端与SIM证据另记，不能用HTTP桩替代。

新增`report-batch-enabled=false`与`report-batch-wait-millis=5`，等待上界100ms；claim开关保持独立且生产默认关闭。共享默认值、Compose、Helm、环境示例、配置字典及benchmark同步；六个配置检查与Helm四项覆盖值（含等待0）通过。配置修改需重启Worker。

## 联合质量门与真实浏览器

- Java联合治理：32类、11模块，按最新XML与类/方法去重77/77通过。首轮FileDispatchRecordQuery位置参数及E2E旧报告未执行的问题均保留失败记录；最小修复后仅补跑受影响项。七个生产模块PMD零违规/处理错误；四个本批文件Spotless exit0。六个自有查询容器均已不存在。
- 真实JWT/CSRF浏览器：两个恢复场景及一个批量Workflow场景共三个不同用例通过；新增批量用例最终命令exit0。匿名401、缺CSRF403；Root与四节点SUCCESS、终态Outbox PUBLISHED、无Job实例。原键POST返回409并带CONFLICT/idempotentReplay=true，随后GET同一LAUNCHED回执；前后数据库身份和数量不变。20类专有夹具清理计数均0。最终新增用例ESLint与独立TypeScript检查exit0。
- 浏览器测试六个新增文件已同步实际前端，使用独立SQL准备/回读/清理，不用SQL代替触发API。私有生产预览对应本批写入的前端快照；其它会话后来新增的DAG推送/今日统计不由这三个用例代验。
- 夹具排查发现非法建图参数缺nodeName或缺业务日历时仍可能落至数据库500；本批补齐合法业务身份，不把测试修正当产品输入校验已修复。列为独立P3诊断维护，避免扩展当前主链范围。

## 真实运行与告警夹具修复

1. B199官方全量入口28/28 SIM、21/21 strict通过。原WARN审计81条中69已分类、12 UNKNOWN，原始失败保留；该运行发生在B200批量客户端接线前。
2. 修复导入前夹具的地址与旧API键：生成器使用当前契约；03先生成权限600的私有副本，再以同一内容上传并核对Apply指纹；Excel仅改对应配置单元格，其它ZIP成员保持。成功/失败/TERM只清本次副本。14项合并脚本测试及四份工作簿结构、语法、脚本治理通过，CI原fixture步骤增加此覆盖。
3. 同一套canonical环境先备份两个库并验证dump目录，再按全新库契约重置本地测试数据及platform的batch/archive/quartz；未另建第二套数据库、删共享卷或覆盖导入ledger。八个B200本地JAR全部核对共享默认配置，五个Worker均含新batcher。强JWT本地配置已补齐，值不进入报告。
4. 新库导入、前置检查通过；B200只跑受影响的`sim-selected 04 05 07 08 09 10 14 15 19 27`，10/10通过，随后quiesce和21/21 strict通过。五类Worker、Trigger、取消与批量claim均覆盖。启动WARN为12条已分类、0 UNKNOWN；定向运行后原始WARN为22条、21已分类、1 UNKNOWN，来自取消场景，精确核对另留证；不能把此审计写成exit0。

### 开关切换、脚本恢复与最终窗口

| 真实模式 | 作业及分区 | 批量claim | 批量report |
|---|---|---:|---:|
| REPORT关闭 | 作业SUCCESS、4/4分区SUCCESS | 1次、4项 | 0次、0项；沿单条上报 |
| REPORT开启 | 作业SUCCESS、4/4分区SUCCESS | 1次、4项 | 4次、4项；本场景单listener串行，不能宣称微批提高吞吐 |

两轮Orchestrator在场景开始前发生过恢复，已按`process_start_time_seconds`识别进程变化，使用新进程累计值，未将跨重启负差值截为0。OFF场景及恢复/清理完成，原私有协调器为避免第二次无谓重启五个Worker而主动终止，exit143另记，不称整个协调命令通过；ON最终官方命令exit0。B200首次十阶段的累积报告指标为40次HTTP/42项，证明真实并发曾发生合批，不外推性能。

首次ON复验发现harness自身缺陷：阶段已启用batch-claim，随后核心健康恢复把Worker参数复位，作业SUCCESS但claim批量指标为0。原失败保留。现由核心恢复显式接收并重建阶段要求的Import/Process模式，恢复失败阻断执行；四个新增测试方法首红（含三个Import子场景）后，相关22例全部通过，语法/脚本治理通过，CI原运行时脚本契约步骤已有覆盖。最终只重跑Stage27通过，未重跑全量SIM。 独立复核随后补出Bash条件调用会禁用内部errexit的失败传播缺口：三个重启命令显式返回失败；提取真实helper、重启返回7而旧health仍200的三个负例先红，补丁后脚本契约最终23/23通过。该末次改动仅改变失败分支，未再次重启服务或重跑业务SIM。

最终串行窗口使用现有生命周期锁，quiesce后strict再次21/21，模式验证后的默认WARN审计12/12通过。官方stop→cleanup-inputs→start完成，SFTP精确移除3个SIM输入并复核、MinIO输入0残留；正常启动12条WARN全部分类。之后真实JWT/CSRF浏览器三个用例在20秒内全部通过，预览进程停止，专有夹具清理完成。

浏览器后原始审计为18条、12已分类、6 UNKNOWN，exit1保留：1条为ConfigSync测试预设的持久写入故障；2条为批量触发场景主动重发原键，原回执/唯一运行均验证成功；3条为`/auth/stream/ticket`重复幂等键拒绝，尚不能当成预设故障，另列维护候选。未增加告警白名单，也未通过重启擦掉该批失败证据。

生命周期锁释放后其它会话继续重启Console，并在19:45:47产生另一个Orchestrator STATE_CONFLICT；后续共享环境审计14条、13已分类、1 UNKNOWN，仍未通过，该trace不属于本批task75的取消场景，不能套用旧归因。本批真实认证窗口匿名401；后续接管检查匿名200，故不把认证状态描述为持续不变。八包最终回读与健康均通过，五个Worker打包的交付类字节码与当前编译产物逐项相同，七个交付源码SHA未漂移；Console PID接管变化及各包哈希保留，不能称整个持续开发工作树已冻结。

## 新增维护候选与发布边界

- P2／前端共享SSE接口：B200观察到的三次票据重复键拒绝已在B201确认并修复，见下节；原始失败不删除。其它会话的新DAG/今日统计不由票据补丁代验。
- P3／Console图输入诊断：缺nodeName/业务日历的非法输入可能返回数据库500，已记录；合法批量请求的业务身份与结果已验证。
- 安全维护：SameSite及79个中低危package/advisory对保持后续维护；NVD数据输入不可用仍未通过，认证DAST为WARN_NONZERO。服务端未生效的enabled/jobOverrides配置独立见TODO的DOC-BATCH-FLAG-1，不把最大批数限制冒充端点开关。

以上属于已按用户要求记录的边界及旁项；本批完成的批量主链、脚本修复与本地R03条件不等于这些候选已修、全部安全工具通过或完整发布放行。

## 流程图与并发范围

[ADR-046](../architecture/adr/ADR-046-file-bundle-aggregation.md)及[Worker插件](../architecture/worker-plugins.md)已更新认领、排队续租、批内串行、报告微批/剩余重试预算和Outbox恢复图。三个图亮暗共六次解析、SVG与PNG渲染通过，已目视核对主链和回报恢复图。

另一会话同期开发Console进度推送/今日统计，构建前后身份差异已记录并保留其源码。额外Console定向4/4及一次package成功不代表其整项交付完成，辅助包未替换本轮已验收运行包；其后续真实联调由所属会话完成。不重开已暂停的数据库全面治理，不提交、推送或创建PR。

## 可复现入口与原件

- Java 定向：`./mvnw -o -ntp -pl <受影响模块> -am -Dtest=<本批实际类/方法清单> -Dsurefire.failIfNoSpecifiedTests=false test`。逐类最新 XML 和完整命令由本机 evidence manifest 索引，不将复跑叠加为不同用例。
- 归档组合：`./mvnw -o -ntp -pl batch-orchestrator -am -Dtest=ArchiveColdStorageIntegrationTest,PipelineProgressArchiveIntegrationTest,SuccessInstanceArchiveServiceTest,SuccessInstanceArchiveMapperXmlTest,ArchiveSchedulerHealthReportingTest -Dsurefire.failIfNoSpecifiedTests=false test`；Probe 以 `-Dtest=ArchiveDiagnosticCadenceProbeIT` 独立执行。
- 实际静态检查：check-java-readability、check-java-logging-governance、check-test-conventions、check-test-config-sources、check-dependency-boundaries、check-java-suppression-registry、check-observability-contract、check-destructive-ops-governance，均实际 exit 0；不等同于完整 CI/Sonar。
- 完整 SIM 入口：`BATCH_SCRIPT_RUNTIME=host RUNTIME_WARNING_STRICT=1 bash scripts/local/sim-harness.sh all`，一次按官方入口执行；没有在每个小补丁后重复全量。
- 本机原件：`/private/tmp/wdi-local-close-b1gcqnvy`、`/private/tmp/wdi-test-conventions`、`/private/tmp/wdi-model-evidence`、`/private/tmp/wdi-semantic-review`、`/private/tmp/wdi-frontend-finish-evidence`、`/private/tmp/wdi-script-security-1hhd7_6y`。原件包含失败与最终通过、逐路径 SHA、XML、清理结果及实际命令；运行日志按仓库当前日志布局保存。


## B201 完成审计补证（2026-10-25）

- 原六组范围保持：测试规约、模型候选、脚本守卫、归档性能、质量安全、最终联验；本批不以已通过部分重定义目标。审计发现29行不能单独关闭代表性吞吐/等待，已完成50000/臂、批1000、三组交替A/B及同连接设置复位；目标容量签收仍属EX-03。
- SSE票据真实缺陷已确认：同样POST空体被通用短时幂等缓存复用，下一次独立票据发行因此409。实际前端fetchStreamTicket显式使用既有createIdempotencyKey；传输重试仍保留请求配置原键，Workflow业务重试不变。新测试先红，当前stream/interceptors/workflow/idempotency四文件44例通过，实际typecheck/API生成漂移检查及ESLint退出0。
- 使用与实际代码逐字一致的票据函数/幂等工具和实际批量测试重建受控预览，不代验并发开发的新页面。真实JWT/CSRF窗口下，独立票据刷新场景及增强后的批量回执场景各1/1通过，连续刷新票据响应均200且键各异；原Workflow键重发409并复用原回执/唯一根。两场景专有数据及预览进程清理完成。
- 正向票据场景按八个同一日志文件的inode/字节偏移截取真实执行窗口，既有默认告警规则审计1条/1已分类、0UNKNOWN；该条为本地登录RSA公钥初始化，未改策略、未过滤窗口内消息，不改写此前负例或并发会话全局FAIL。
- 原145个后端测试文件审计时与847例冻结源码逐字相同；本批随后仅强化既有生产归档全行用例，为execution_started_at准备非空值并通过真实归档服务核对冷热整行。实际执行时间SQL另5项通过，不把手工复制冷行误当生产归档覆盖。
- 651模型定位全部保留既有语义裁定；299文件六处哈希变化已逐项检查，20行刷新当前元数据，原快照hash/状态保留。17个最终模型交付文件仍与103例快照一致，另一个projection测试与后续真实PG修复/格式快照完全一致；仅更新核对口径，不增加业务重构。
- 共享DefaultTaskAssignmentService和JobInstanceMapper新增了其它会话的执行开始记账。为当前续租边界补跑三个服务类62项全部通过，前后七个源文件SHA无漂移；不是其它SLA功能的整体验收。归档Mapper同期只增execution_started_at的INSERT/SELECT两列，其余服务/调度器/配置/接口与旧对照完全相同，新增非空字段的生产运行树用例已通过。
- 本机原件：/private/tmp/wdi-local-close-b1gcqnvy/b201；真实浏览器原件real-ui-b201-sse和real-ui-b201-bulk位于既有前端证据目录。原SDK并发编译中间态失败保留；仅在确认方法/测试清理方式已落地后原命令续跑，没有跳过SDK编译。

B201的全树Java可读性检查目前exit1，来源为其它会话新建的Console进度查询代码（显式类型及proxyBeanMethods）；原始日志保留，本批归档生产文件显式范围检查exit0。按用户分工不修改该会话文件，不把限定范围通过写成全树通过。


### 六组交付条件逐项复核

| 原定组 | 本地证据与边界 | 当前状态 |
|---|---|---|
| 测试规约 | 145文件原整改及847例快照保留；本批补强归档新增列与局部JIT测试；49个不同归档用例通过；不接手其它会话新建测试 | 本地完成 |
| 模型候选 | 651定位全部裁定，103例及24条真实固定投影；当前元数据已逐项复核 | 本地完成 |
| 脚本守卫 | 危险操作666/254已核对；夹具与恢复契约、三个条件调用失败负例23/23，失败不误报就绪 | 本地完成 |
| 归档性能 | 5万/臂、批1000、三对交替完成；完整行/提交/清理均通过，吞吐与等待原值见下；不承诺尾延迟改善 | 对照完成；目标容量未签收 |
| 质量与安全 | B199/B200限定范围扫描与修复有原件；NVD数据不可用、DAST非零低风险及镜像中低危保留；全树新代码规约由所属会话收尾 | 按限定范围完成；例外不判通过 |
| 最终联验 | 官方全量28/28与后续受影响10/10、strict21/21、真实认证及清理均有证据；B201票据正向告警窗口通过。后续共享Worker开始记账已找到并核对当前源码相同的31/2/1/6定向原件 | 本地完成，复用相同源码证据 |

JIT修复的证据与选择：JIT开启的首个5万条计划中，诊断执行13551ms、其中JIT11080ms；候选执行10510ms、其中JIT10444ms。保留原未完成实验及其停止原因，不把中断计通过。新代码仅在归档事务使用set_config的local参数，双方A/B均启用同一修复，区分JIT计划诊断与cadence完整对照。[PostgreSQL说明](https://www.postgresql.org/docs/17/functions-admin.html)确认local设置限定当前事务；设置复位仍以本批同一物理连接实测为准。


### 共享Worker增量的证据复用

另一会话的执行开始记账随后修改了HttpTaskExecutionClient的renew分流，不改claim/report微批主体。独立只读核对 `/private/tmp/codex-sla-validation-20261025/source-manifest.json`、该目录源快照和Surefire XML、`/private/tmp/codex-sla-scoped-regression-20261025.log` 的BUILD SUCCESS：

- 当前HttpTaskExecutionClient/TaskLeaseRenewItem及HTTP测试与该快照SHA相同；HTTP31/31完整保留B200原29项并新增两项混合分流和旧invocation拒绝。
- 执行前租约确认2/2、已领排队围栏1/1，与当前测试源码SHA相同；生产wrapper仅注释不同，无行为差异。
- 服务端执行开始6/6与当前Service/Test SHA相同，承接B201先前62项窗口之后的相关源码变化。

以上按类分列，重叠结果不相加；没有为已有相同源码的通过原件再跑一轮。SLA会话完整128项不代作本任务全部验收。全树测试命名检查首轮15处中，本批报告预算用例的命名已修正；其余14处属同期Console进度测试，保留失败，按用户分工由所属会话处理，不扩大基线。


### B201代表性归档最终结果

命令：`./mvnw -o -ntp -pl batch-orchestrator -am -Dtest=SuccessInstanceArchiveServiceTest,SuccessInstanceArchiveMapperXmlTest,ArchiveDiagnosticCadenceProbeIT -Darchive.probe.instances=50000 -Darchive.probe.batch-size=1000 -Dsurefire.failIfNoSpecifiedTests=false test`，exit0、12/12。六臂共归档300000条根实例，每臂50个1000行批次、50次提交；诊断旧50/新1。每臂冷热完整JSON一致，自有数据清理为0；同一物理连接提交/回滚后的JIT复位通过。12份实际计划均显示jit=off、无JIT段。JIT开启的中断实验不计通过。

| 臂 | 总秒 | 实例/秒 | 批事务p50 / p95 / max（毫秒） |
|---|---:|---:|---:|
| pair-0-legacy | 114.47 | 436.81 | 1499.9 / 3972.6 / 19947.3 |
| pair-0-scheduler | 80.73 | 619.36 | 260.6 / 15447.0 / 17651.0 |
| pair-1-legacy | 95.03 | 526.15 | 1107.7 / 2789.6 / 18839.2 |
| pair-1-scheduler | 91.30 | 547.67 | 279.5 / 19067.9 / 20823.7 |
| pair-2-legacy | 81.48 | 613.65 | 1070.5 / 1836.5 / 15860.1 |
| pair-2-scheduler | 83.91 | 595.86 | 275.2 / 16861.4 / 19303.5 |

批耗时为第一条SQL至事务完成；新路径的轮级只读诊断另计入总时间，不混入写批p95。三对有两对新路径更快、一对略慢，尾延迟新路径更高；不据此声称稳定吞吐或尾延迟改善。100ms采样记录旧臂NO_REPORTED_WAIT分别693/553/468、新臂122/126/116，另有少量WAL/数据IO/ClientRead；无Lock样本。它是活跃客户端采样，既非精确等待时长，也不能从未报告wait推断CPU占用。共享Docker VM为8核/约7.75GiB，容器无单独CPU/内存限制；JVM最大堆4GiB，正常本地服务仍运行，未承诺资源隔离。目标环境容量、尾延迟预算和签收继续保留EX-03，不新增数据库索引或全局调优扩项。

随后仅顺序执行既有五类归档组合：`ArchiveColdStorageIntegrationTest,PipelineProgressArchiveIntegrationTest,SuccessInstanceArchiveServiceTest,SuccessInstanceArchiveMapperXmlTest,ArchiveSchedulerHealthReportingTest`，exit0、47/47；包括完整运行树新增非空时间列、DQ/位点复制冲突回滚、引用与并发保护。与探针按类/方法去重为49个不同用例，不能将12+47相加。探针结束后只对自有文件格式化，唯一格式差异是探针排版，生产SQL/类无漂移；最终事务回归源快照稳定。两轮自有临时测试容器自然退出，未清理其它会话或canonical环境。没有重跑全量SIM。

最终归档原件：`b201/archive-50000-final/`含六臂原始指标/12份计划/SQL/参数、12项XML和summary；`archive-final-regression-reports/`含47项原件；`archive-final-source-stability.json`、`archive-testcontainer-resources.json`、`archive-docker-host.json`记录源码和资源。已完成的原六组及批量链路以本报告限定范围交付；正式24/36与12行部分验证、其它会话新增功能、NVD/DAST/历史WARN及维护项均保持各自边界。

最终结束前观察到模型清单关联文件又有9处同期功能源码变化，另存 `b201/model-later-concurrent-drift.json`；原651项裁定与20行元数据刷新只属于其记录窗口，未自动把这些后续变动重新签成通过。直接影响本批链路的Worker/服务端变化已按上方相同源码证据承接，其它前端进度/SLA功能由所属会话继续验收；不循环追逐整个活动工作树。

收尾门禁：限定文件Spotless check、代码diff空白检查均exit0；文档结构、日期、readiness同步、代码/文档路径引用四项实际exit0。全树同期规约失败另记，没有覆盖为通过。独立只读完成审计确认原定六组及批量链路无新增本地阻断；代表性对照不新增未经事先约定的p95放行预算。
