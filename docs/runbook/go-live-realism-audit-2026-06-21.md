# Go-Live 真落地体检(Realism Audit)· 2026-06-21

> **目的**:从"真实企业批量/结算业务能不能落地用"的角度体检系统,而不是看测试绿。
> **总判据**:**一个真实客户拿它跑一个完整账期(日/月批),出事能自己救、数能拿去出账、固定窗口跑得完、明年还在用——做得到才是企业级,做不到就是形式化落地。**
> **配套**:[`go-live-readiness.md`](go-live-readiness.md)(就绪检查表)、[`go-live-staging-execution.md`](go-live-staging-execution.md)(staging 执行 playbook)。本文是**审核视角与审核点**,上线前逐条出"真落地 vs 形式化落地"红黑榜。

## 怎么用本文

- 每个审核点配四件套:**审核点(查什么)/ 形式化落地红旗(假落地信号)/ 验证方法(怎么查才挖得出)/ 判据(过/不过)**。
- **方法决定能挖到什么**(历史教训):全部通过 ≠ 正确;扫描只查"当前模型内一致性",挖不出架构级缺口(RLS 缺失就是扫不出来)。真正的洞靠**威胁模型 / 对标基准 / 真实数据 / 混沌注入 / 负载压测 / 对抗式复核**,静态扫只占一小部分。
- **优先级**:§1–§5 是"能不能真用"的**核心门槛**(业务表达、运维自救、数字可信、跑得完、坏了能恢复);§6–§20 是"能不能规模化、可持续交付"。核心门槛不过 = No-Go,不论交付面多漂亮。
- 全文 **20 条审核点**,核心门槛 5 条 + 交付/可持续 15 条。

---

## §1 业务表达力 —— 真实批量场景装得下吗 【核心门槛】

**审核点**
- 日批/月批/账期(`bizDate`)、节假日日历、跨日依赖、**上游晚到**(ADR-043 readiness defer:窗口内等上游就绪再跑、不丢当天批;超窗 `WAITING_READINESS_TIMEOUT` 告警)。
- 重跑一个账期、补数(backfill)、**部分失败只续失败的**(PARTIAL_FAILED 语义)、控制总额/笔数对账(声明 vs 实际累加,ADR-041 + manifest recordCount)。
- 文件侧:大文件流式、非 UTF-8 源(Import 边界)、坏行定位到行列、sidecar(`.chk`)校验、断点续跑(checkpoint/resume)。

**形式化落地红旗**:能跑单 job demo,但表达不了"02:00 跑但前提上游今天成功、04:00 还没好按 misfire 处置"这种结算级语义;重跑只能整批不能按账期/按 job;对账只记不拦。

**验证方法**:拿一个**真实账期场景**(上游 SETTLE → 导入 → 加工 → 出账)端到端跑;`sim` 连跑(非单跑)含 stage25 checkpoint-crash;readiness defer 在真 wheel 并发下验窗口内重检 + bizDate 跨午夜 pin 不漂。

**判据**:真实账期链路可配置、可重跑、可补数、可对账拦截;readiness defer 端到端验证通过。

---

## §2 Day-2 运维 —— 出事运维能不能**自己**救 【核心门槛】

**审核点**
- 卡住的任务怎么查、怎么重推;DLQ 怎么看怎么重放;`outbox` 积压怎么清(经 `ConsoleOrchestratorProxyService` → orchestrator `/internal/outbox/*`,console 不直写 outbox);暂停/恢复/取消(ADR-044)即时生效;改配置不重启生效(缓存失效广播)。
- 审计:谁在什么时候改了配置/重跑/审批(operation audit)。
- **核心判据:出一次故障,运维要不要 SSH 进 DB 手改数据?要,就是没落地。**

**形式化落地红旗**:console 一堆"只能看不能动"的页面;runbook 写"联系开发";恢复动作没幂等保护(重推一下变重复出账)。

**验证方法**:对照前端 CRUD-by-design 地图,逐个高频故障场景(长期停滞/积压/死信/误触发)走一遍 console 能否闭环;Playwright 验关键操作真触发(原生 click)。

**判据**:Top 故障场景均有 console/运维入口闭环 + 幂等保护 + 审计留痕,零"必须上数据库手改"。

---

## §3 数字可信 —— 跑成功的批敢不敢拿去出账 【核心门槛】

**审核点**
- 精确一次(全 worker 组崩溃/重投/GC pause 旧 leader 都不重复)、防终态复活(`NotFromTerminal` 谓词 + 状态机 guardTerminal 双防线)、幂等承重墙(56 处 `ON CONFLICT` 承重在全局 UNIQUE)。
- **rerun 后旧结果会不会被下游误用**(readiness 口径=最新 attempt 为 SUCCESS,不放行过期成功)。
- 对账:声明总额/笔数 vs 实际累加(命中即拦,标 `source`)。
- outbox 与状态写**同事务**在每条写路径成立(含新增 defer / pause 路径);CAS 前态核了**全部**写入路径(历史教训:markAcked 漏同步 ack)。

**形式化落地红旗**:"测试全部通过"但无真实并发/崩溃注入证据;exactly-once 只在 happy path 成立;改 UNIQUE 列集的迁移没核幂等语义。

**验证方法**:真实 DB + 并发/崩溃**对抗式注入**(`dr-drill-fleet-crash.sh`);`grep -r 'on conflict'` 全量对账每条幂等键;逐个状态写 SQL 核 `NotFromTerminal`。

**判据**:全 worker 组崩溃精确一次演练退 0;幂等键全量核对无语义漂移;无终态复活路径。

---

## §4 容量确定性 —— 固定窗口跑得完吗 【核心门槛】

**审核点**
- 批处理死线(凌晨账期窗口必须跑完);真实账期数据量下吞吐(已知单机 ~20–60 jobs/s,**瓶颈在控制面**:launch 单线程消费 + report/claim 争用,PG 写有 10–15× 余量)。
- 逼近上限的余量 + 扩展预案;**补数/重跑不拖垮在线批**;洪峰下 `outbox` 积压不发散、Kafka consumer lag 收敛。

**形式化落地红旗**:标称吞吐是空跑 helloworld 测的,真实大文件/复杂 DAG 掉一个数量级;没人测过"月底全量重算"峰值。

**验证方法**:Gatling(`ControlPlaneMixedPressureSimulation` 等)压到靶点 + SLO 硬门;峰值时实测 `pg_stat_activity` 锁等待 / Kafka lag / outbox 积压。

**判据**:目标量级 SLO 达标且积压不发散;给出容量上限 + 扩展预案。

---

## §5 故障恢复 —— 出事不丢、不重 【核心门槛】

**审核点**:整队 worker 崩→lease/task 超时回收→重投→精确一次;PG 主备切换;Kafka 短时不可用降级;**PITR 真达标 RTO<2h / RPO<15min**;进程跑到一半异常退出续跑(checkpoint);pre-claim backpressure(历史:无界池只看 claim 后 inFlight,已 Semaphore permit 根治)。

**形式化落地红旗**:有 DR 脚本但从没在 staging 实跑;RTO/RPO 是纸面值;"高可用"只是部署了副本没演练切换。

**验证方法**:`dr-drill-fleet-crash.sh` / `dr-drill-pitr.sh` 在**生产同构 staging 实跑** + 混沌注入(`*ToxicIT`);PITR 断言 S0 指纹不丢 + RTO 计时。

**判据**:全 worker 组崩溃 + PITR + failover 三演练实跑通过并签字。

---

## §6 多租 / 权限 —— 真隔离还是演示隔离

**审核点**:跨租查询真返 0 行(batch 列级 + biz RLS `SET LOCAL app.tenant_id` + 分片路由);角色权限够细撑企业审批链;租户凭据注入非明文;Kafka topic/consumer-group 无跨租漂移。

**形式化落地红旗**:隔离只在单节点测过(**Citus 多节点 RLS 实测坏过**:GUC 不跨节点传播);权限只有"管理员/普通"两档。

**验证方法**:真实数据跨租查询验 0 行 + 威胁模型逐信任边界;biz 分布表必多节点验。

**判据**:三层隔离真实数据验证通过;权限模型覆盖企业审批/操作分离。

---

## §7 接入上手 —— 新租户/作业/异构 worker 多快能上

**审核点**:租户一键 provision + 首登改密 + 就绪自检;作业模板 + Excel 批量导入(.xls fail-fast/坏行带行列/表头校验);**BYO 五语言 SDK 自托管 worker 真能接通**;runbook 齐全。

**形式化落地红旗**:onboarding demo 顺,真实异构客户(自己的 Go/Python worker)接入卡在 lease/心跳/取消/背压的**生产行为漂移**(conformance≠production:fixture 测决策核,transport/lifecycle/scheduler 是另一套,全部通过藏 bug,已挖 12 项)。

**验证方法**:补**打真 transport 的集成 conformance**(不止 fixture);真实自托管 worker 走 register→dispatch→CLAIM→EXECUTE→REPORT + lease 续约 + 取消 + 优雅停全程。

**判据**:五语言 SDK 在真 transport 上行为等价;真实 worker 端到端接入跑通。

---

## §8 可观测 —— 看得见 + 出事有人知道

**审核点**:端到端 trace 贯通(trigger→orchestrator→worker→report 一个 traceId);SLO 大盘;**关键告警真配了**(outbox 积压 / Kafka lag / DLQ / `readiness.timeout` / PG 锁等待),不是只有 metric 没告警;health/liveness 探针 + 优雅停机预算实测。

**形式化落地红旗**:大盘很炫但无告警规则;出事靠用户打电话才知道;异常被直接抛出未映射异常当 500(应 4xx,历史 F1/F2/F3 缺口,只有真实场景+DB 验证挖得出)。

**验证方法**:真实异常场景注入 + trace 串联核对;核每个新 metric 有无对应告警。

**判据**:trace 端到端贯通;关键告警规则齐;异常→正确 HTTP 码契约成立。

---

## §9 边界纪律 —— 没膨胀成四不实(反形式化落地本身)

**审核点**:系统定位 = **批量运行控制面 + 文件/任务交付闭环**。核对有没有越界做**数据治理 / 自研 K8s 调度 / 实时合规审计**这类"看着高级、维护成本高、真实客户不用"的功能(ADR-021/022/026/027 的「❌不做」清单);反过来,核心能力有没有被花哨功能挤掉。

**形式化落地红旗**:为 demo 加的"AI/治理/大盘"模块占维护成本却无真实使用;dry-run/对账/取证被拿去"裁定业务对错"越界(ADR-021 红线)。

**验证方法**:对每个 ADR 顶部「范围边界」判定提问逐一对答;盘点模块的真实使用率 vs 维护成本。

**判据**:无越界功能在拖维护成本;核心能力完整无被挤占。

---

## §10 上线就绪 —— go-live 真能签字

**审核点**:staging 同构(**非 testcontainers**)全链路验收 + `sim` 连跑 + sim-4day;割接演练(Flyway prod-sized dry-run + 锁影响 + 回滚);灰度预案 + 值班;**prod 强制 `batch.security.bypass-mode=off`**(本地 sim 曾临时开 true,prod 必须 false)。安全机制纵深见 §18。

**形式化落地红旗**:就绪检查表全是"测试覆盖"没有"操作演练 + 签字";从没在生产同构环境压过真实数据;prod 配置没人实查过。

**验证方法**:[`go-live-staging-execution.md`](go-live-staging-execution.md) 的 Phase 1–3 逐条跑 + 签字;prod profile 配置实查;CodeQL/trivy 清零。

**判据**:Phase 1–3 全签字;prod 安全配置实查通过;割接 + 回滚演练通过。

---

## §11 调度准确性 —— 不重、不漏、不早、不晚

**审核点**:防双 fire(marker CAS + select-by-dedupKey 软幂等 + `job_instance` 唯一约束三层兜底);misfire 处置(NONE/AUTO/MANUAL_APPROVAL)语义正确;leader 选举(ShedLock)+ leader 失守检测 + stale marker 接管;cron 语义 + **DST 重叠/跳变**(`fire_sequence` + `schedule_timezone` 快照);catch-up 限流防雪崩;readiness defer 不干扰 misfire 分流。

**形式化落地红旗**:调度只在单实例测过(多 leader 漂移、GC pause 旧 leader 重发没验);DST 那两天没人想过;misfire 风暴打挂 LaunchService。

**验证方法**:多 leader 并发 IT(`WheelMisfireIntegrationTest` / `HashedWheelTriggerSchedulerIntegrationTest`)+ 构造 DST 边界 + leader 切换混沌。

**判据**:并发/漂移/DST 下不重不漏;misfire 限流生效。

---

## §12 时区与编码 —— 跨区账期不错日、多源文件不乱码

**审核点**:全系统统一 `BatchTimezoneProvider`(默认 `Asia/Shanghai`),**禁 `ZoneId.systemDefault()`**;`bizDate` 对齐与本地日历一致;全系统 UTF-8,**禁 `Charset.forName("UTF-8")`**,Import(`PreprocessStep`)是唯一允许读非 UTF-8 源的边界;容器 locale 走 `BATCH_LOCALE`。

**形式化落地红旗**:跨时区部署后账期日期偏一天;源文件 GBK/Big5 进来乱码或静默丢字符;时区靠机器默认。

**验证方法**:ArchUnit/静态扫 `ZoneId.systemDefault` / `Charset.forName`;跨区 + 非 UTF-8 真实文件端到端验。

**判据**:无禁用 API;跨区账期日期正确;非 UTF-8 源按边界正确转码。

---

## §13 数据生命周期 —— 归档 / 保留 / 存储膨胀治理

**审核点**:热表 `batch.*` ↔ `archive.*_archive` **1:1 镜像**(改 `ALTER TABLE batch.*` 必须同 PR 补归档表,`ArchiveSchemaDriftCheck` 启动 fail-fast);数据保留期 + 月分区(V172/V173)滚动;**存储膨胀治理**(历史:146G 靠 time-partition + ShedLock 清理释放);PITR 恢复点覆盖保留窗。

**形式化落地红旗**:跑几个月后表无限膨胀没人清;归档表与热表 schema 漂移启动才失败;分区/保留没真实滚动验证。

**验证方法**:`ArchiveSchemaDriftCheck` 启动通过;分区滚动 + 清理任务真实跑;`grep 'on conflict'` 核分区不破幂等。

**判据**:冷热镜像无漂移;保留/分区/清理闭环;长期运行存储可控。

---

## §14 配置与变更管理 —— 开关、灰度、迁移可逆

**审核点**:feature switch 治理(`bypass-mode` 等总开关 prod 强拒;Citus 默认关;读写分离仅 console-api);配置改动即时生效(缓存失效广播);**Flyway 迁移可逆 + 回滚脚本**;business 库不走 Flyway 的手工脚本安全(`check-db-scripts-safety`);灰度按租户/作业类型分批放量。

**形式化落地红旗**:开关多但没人知道 prod 该开哪些;迁移只能前滚不能回退;改配置要重启;business 库 DDL 无 lint 裸跑。

**验证方法**:配置开关矩阵核对;迁移 dry-run + 回滚演练;开关改动实时生效验证。

**判据**:开关矩阵清晰且 prod 安全;迁移可逆;灰度预案可执行。

---

## §15 性能隔离与公平 —— 大租户不饿死小租户

**审核点**:租户间互不影响(fair-share group + 队列 `queue_code` + 优先级 `priority`);单租户洪峰不拖垮全局;补数/重跑与在线批资源隔离;active 任务按租户/队列/公平组限流(`countActiveByFairShareGroup` 等)。

**形式化落地红旗**:一个大租户月底全量重算把整个平台拖垮;没有公平/优先级,先到先占;隔离只是理论。

**验证方法**:多租户混合负载压测(一个大租户 + 多小租户)观察小租户 SLA 是否被饿死;公平组/队列限流实测。

**判据**:洪峰下小租户 SLA 不被大租户饿死;公平/优先级/队列限流生效。

---

## §16 升级与向后兼容 —— 滚动升级不破存量

**审核点**:服务滚动升级不中断在飞任务(优雅停 + lease 保活);**SDK wire-protocol 版本兼容**(v3 未知大版本不提交 offset 等硬契约;缺失 schema 按 v1 accept);schema 演进不破存量(新增列默认值、`@Builder` 配空参兜底 Jackson/MyBatis);**禁重命名字段**(破 mybatis `#{q.xxx}` / canonical constructor)。

**形式化落地红旗**:升级要停机;新版 orchestrator 不认旧 worker;改字段名静默破反序列化;SDK 升级不向后兼容逼客户同步升。

**验证方法**:滚动升级演练(新旧版本共存期跑在飞任务);SDK 跨版本 conformance;schema 演进对存量数据回归。

**判据**:滚动升级零中断;新旧版本兼容期行为正确;无破坏性字段变更。

---

## §17 成本与资源效率 —— 不过度工程、利用率可控

**审核点**:worker 利用率(空转/过配);资源占用可预测(连接池、线程池、内存,大文件流式不 OOM);**不为指标刷测试/过度工程**(覆盖率聚焦有用方法,DTO/glue 跳过);Citus 是按需而非默认(瓶颈在控制面非 PG,Citus 非当前杠杆)。

**形式化落地红旗**:为"高级感"上 Citus/复杂架构但真实负载用不到,徒增运维成本;大文件一次性进内存 OOM;测试为覆盖率凑数。

**验证方法**:真实负载下资源 profile;架构选型对标真实瓶颈(`docs/analysis` 吞吐分析);覆盖率原则核对。

**判据**:资源占用可预测无 OOM;架构复杂度匹配真实需求,无为复杂而复杂。

---

## §18 安全机制纵深 —— 审批 / 脱敏 / 隔离 / 密钥 / 审计

**审核点**:审批链(MANUAL_APPROVAL catch-up、workflow 审批节点)真拦得住;敏感数据脱敏(log masking);**atomic worker RCE 隔离**(ADR-029,shell/sql/stored-proc/http 特权隔离);SSRF / sensitive-data 拦截;密钥非明文落库 + 强度校验 + 轮换;内部端点鉴权(orchestrator `InternalAuthFilter` + 各 worker `/internal` 同源,刚补 import);审计完整(谁改了什么、敏感操作留痕)。

**形式化落地红旗**:审批只是 UI 摆设不真拦;敏感数据进日志;atomic worker 能任意 RCE;密钥明文落库;内部端点缺少鉴权(刚发现 import 缺鉴权,**其余 worker 要同样对标**)。

**验证方法**:威胁模型逐信任边界 + 越权矩阵;CodeQL/trivy;RCE 隔离 IT 证据;审计留痕真实场景验。

**判据**:审批真拦、脱敏到位、RCE 隔离、密钥安全、内部端点全鉴权、审计完整。

---

## §19 文档与可维护 —— 知识不在某个人脑子里

**审核点**:runbook 覆盖高频运维场景(已有 biz-tenant-routing / instance-pause-resume / dependency-aware-fire 等);ADR 决策可追溯(范围边界判定提问);新人按文档能起本地环境 + 跑 sim;API 文档与控制层同步(`pr-gate` 拦漂移);CLAUDE.md 装"不能从代码推断的约束"。

**形式化落地红旗**:出事只有原作者能救;ADR 写了不更新与实现漂移(如 ADR-043 §6.4 与实现差异需注明);文档与代码两张皮。

**验证方法**:新人按文档实操起环境 + 跑 sim;ADR vs 实现一致性抽查;API 文档 oasdiff 守护。

**判据**:关键路径有 runbook;ADR 与实现一致或差异已注明;文档可让新人独立上手。

---

## §20 真实失败模式覆盖 —— 反"只测 happy path"

**审核点**:负向用例真实覆盖(坏输入、超额、越权、租户停用、schema 拒绝、下游异常);网络分区 / Kafka 短时不可用 / DB 锁等待超时;时钟漂移 / GC pause;**异常 → 正确 HTTP 码契约**(下游/存储异常直接抛出未映射异常被当 500 应 4xx,只有真实场景+DB 验证挖得出);sim 负向阶段断言区分正/负用例。

**形式化落地红旗**:测试全是 happy path,坏输入直接 500 或静默吞;"全部通过"但从没注入过故障;负向用例当噪音过滤掉。

**验证方法**:故障注入(`*ToxicIT` + sim 负向阶段)+ 异常场景真实 DB 验证;对抗式构造边界/坏输入。

**判据**:负向/故障/边界有真实覆盖;异常→正确码契约成立;无"坏输入即 500"。

---

## Go / No-Go

| 闸 | 覆盖 | 判据 |
|---|---|---|
| **核心门槛(§1–§5)** | 业务表达 / 运维自救 / 数字可信 / 跑得完 / 坏了能恢复 | **全部过,任一不过 = No-Go** |
| 交付面(§6–§10) | 隔离 / 接入 / 可观测 / 边界纪律 / 上线就绪 | 全签字 |
| 可持续(§11–§20) | 调度准确 / 时区编码 / 数据生命周期 / 配置变更 / 性能隔离 / 升级兼容 / 成本效率 / 安全纵深 / 文档可维护 / 真实失败覆盖 | 全签字 |
| 形式化落地清场 | 全 20 节「形式化落地红旗」 | 逐条排除;越界/无用功能列出处置 |

> **一句话**:核心门槛决定"能不能真用",交付面决定"能不能规模化卖"。形式化落地风险是这份体检的重点——**任何"只能 demo、真上线无法形成真实使用/无法承载"的东西,在这里暴露。**
