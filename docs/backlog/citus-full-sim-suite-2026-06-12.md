# Citus 后续专项:scripts/sim/* 完整 25 阶段 LAN 仿真上 Citus

> 2026-06-12。本轮已把**平台层**在 Citus 验透(34 场景套件 + workflow PIPELINE/GATEWAY + 失败注入 +
> 压测 + 多租户 worker 执行 + 业务库数值落库)。`scripts/sim/*` 那套 **25 阶段 LAN 仿真本体**(import/export/
> process/dispatch/atomic/trigger 的 b/c/d/e 多阶段变体 + 断点崩溃恢复 + SDK worker)**未在 Citus 跑**。
> 本文记录已摸清的 4 层 prerequisite + 所有踩到的坑,供后续整体启用。

## 已验证可行的基础(不必重做)
- ✅ sftp + mockserver 基础设施(`bash scripts/sim/02-start-sim.sh` / compose)
- ✅ **多租户 worker 执行**:worker `subscribe-mode=PATTERN`(默认)让 default-tenant worker 跨租户领 ta/tb/tc 任务;
  无需为每租户单独起 worker。实测 tb XML transaction 导入 → 端到端 SUCCESS、numeric 落 biz.transaction。
- ✅ ta/tb/tc 全配置可 bootstrap 上 Citus:`sim-e2e-bootstrap.sql`(已修 batch.tenant IMMUTABLE,commit 13d8ffb3a)
  去事务后灌 citus-coord,ta/tb/tc 得 5/3/4 jobs + 2/4/2 templates(CSV/XML/FIXED/JSON/EXCEL)。

## 4 层 prerequisite(每层都是独立工作量)
1. **完整 tenant 配置导入**:`03-import-tenants.sh` 走 console API 导入 `docs/test-data/test-full-coverage-import-suite`
   完整 fixture —— 含**精确的 template 字段映射** + **export 的 export_data_ref/jdbc_mapped_export 配置** + channel。
   bootstrap 只灌了骨架;光 bootstrap 不够:实测 export 报 `export_data_ref is required in template config`、
   import 自造内容报 `null value in customer_no`(字段映射对不上)。
   **2026-06-12 实测此层本身有 2 个子 blocker(在解 #1 前必先过)**:
   - (a) **console RBAC**:admin(ROLE_ADMIN,tenant=system)上传 tenant-package 报 **403 访问被拒绝**——
     ROLE_ADMIN 缺该端点的细粒度权限(Citus 上 RBAC role→permission 映射没全 seed)。**临时绕过 = 起 console 带
     `-Dbatch.security.bypass-mode=true`**(放行认证/授权);收尾记得关掉。
   - (b) **Excel fixture 与 schema 漂移**:`{ta,tb,tc}-tenant-config-package-test.xlsx` 的 job_definition sheet
     **缺 `watermark_field` 表头** → 上传报 `INVALID_ARGUMENT: missing required headers: [watermark_field]`。
     这是 fixture 陈旧(console import schema 加了新列),**与 Citus 无关,普通 PG 也会挂**。要么更新 .xlsx fixture,
     要么改走"直接 SQL 灌完整 config"(但完整字段映射/export_data_ref 只在 Excel 里,SQL 化得重写)。
   实际可行路径:console import 链路(auth bypass + 修 fixture)或绕开 Excel 直接 SQL 灌全配置;两条都要额外工作。
2. **精确内容 fixture**:每 stage 脚本里的 XML/CSV/JSON/定宽内容必须**精确匹配** template 解析 schema
   (列序/定宽位置/JSON 字段名)。自造内容会 parse 过但 LOAD 撞 not-null。从 stage 脚本本体抽真 fixture。
3. **去硬编码(关键,防污染 main)**:25 个脚本写死 `docker exec batch-postgres-primary psql -d batch_platform`
   —— 而 **batch-postgres-primary 上同时有 main 的 batch_platform**!裸跑会把 bootstrap 灌进 main + 在错库验。
   必须把平台查询重定向 citus-coord:25432,业务查询 batch-postgres-primary:15432/**batch_business_part**
   (非 batch_business)。`env-common.sh` 只有一个 PG_CONTAINER,需像 validate-seed-scenarios 那样拆参数化。
4. **channel endpoint 适配**:ta/tb/tc 的 SFTP/HTTP channel 配 docker 内网名(`sftp:22`/`mockserver:1080`),
   但 Citus 部署的 worker 是本地 JVM → 要改 `localhost:12222`/`localhost:11080`。且 bootstrap 的 SFTP channel
   UPDATE(`config_json ? 'host'` jsonb 存在运算符)在 distributed UPDATE 上报 `text = boolean`(Citus `?` 与
   bind 占位冲突?待查;可能要 `jsonb_exists()` 替 `?`)。

## 部署坑(本轮血泪,务必带上)
- **`stringtype=unspecified` 不能丢**:业务 URL 覆盖时必须保留 `?stringtype=unspecified&reWriteBatchedInserts=true`
  (application-local.yml 原有 + 注释警告)。丢了 → PG JDBC 按 varchar 绑参 → 写 numeric/date 列报
  `column "amount" is numeric but expression is character varying`。**全文本列(customer_account)不暴露,
  一碰 transaction.amount/risk_score 就炸**。本轮所有 /tmp 部署脚本都漏了,已知修法:URL 带上这俩参数。
- 同时重启 8 服务风暴 Citus 扇出(max_shared_pool_size)→ 分批重启。
- workflow_run 按 workflow_definition_id 键(无 job_code 列);清卡死 active run 用 wd_id。

## 建议执行顺序(后续整体启用时)
1. citus-cluster up + initdb + Flyway + distribute + platform_seed(去事务)
2. 业务库 batch_business_part 确保完整 biz schema(本轮已确认 transaction/risk_score 等都在)
3. 起 8 服务,**业务 URL 带 stringtype=unspecified**,分批起防扇出打满
4. 起 sftp+mockserver;ta/tb/tc channel endpoint 改 localhost
5. 参数化 env-common.sh + 25 脚本的 PG 重定向(平台→25432 / 业务→part);**严禁连 main batch_platform**
6. 03 console 导入完整 fixture(或把 fixture 直接灌 Citus,注意 INSERT...SELECT⋈VALUES / 裸 ON CONFLICT /
   DO UPDATE IMMUTABLE 三类坑,见 multi-tenant-seed 已修模式)
7. 逐阶段跑 08→25,含 25-checkpoint-crash 断点恢复、06-sdk-worker
8. 每阶段验:instance SUCCESS + 业务行落 part + 0 Citus 方言报错 + main 库零污染自证

相关:[[project_partition_branch_ready]];本轮提交 57fa04c88/8baeea11d/13d8ffb3a。

---

## 2026-06-12 续:逐阶段实跑实录(08→15)+ 真实 bug + 环境 blocker

### 已验证通过(Citus 端到端,数据落 batch_business_part,平台落 citus-coord)
| stage | worker | 结果 |
|---|---|---|
| 08 import-stage2 | import | ✅ XML/FIXED 2 SUCCESS + 3 预期 FAILED |
| 09 export-stage3 | export | ✅ JSON/FIXED/EXCEL SUCCESS + bad_sql 预期失败 |
| 10 process-stage4 | process | ✅ JSONB/DIRECT/EMPTY SUCCESS + validate_fail 预期失败 |
| 11 import-stage2b | import | ✅ upsert 幂等 + load_bad/partition_guard 预期失败 |
| 12 export-stage3b | export(4 分区) | ✅ 4 partition 并行 SUCCESS,4|4|4|40 |
| 13 process-stage4b | process | ✅ v1→v2 重处理覆盖 |

### 本轮新修真实 Citus bug(全部 commit,双栈安全)
1. **bootstrap 派生 job/pipeline `INSERT dist SELECT FROM dist CROSS JOIN (VALUES)` 静默插 0 行** → 展开为每变体一条 co-located INSERT...SELECT(reference 源/纯 VALUES 源不受影响,只 hash-distributed 源 CROSS JOIN 本地 VALUES 中招)
2. **export 模板 `(:batchNo IS NOT NULL)` 恒真条件 prepared-stmt 无法推断参数类型** → `CAST(:batchNo AS text)`
3. **验证查询 job_task/job_partition/trigger_request/file_dispatch_record join 缺分布键** → 批量补 `and X.tenant_id=Y.tenant_id`(含反序 `p.id=t.job_partition_id` 形态);`where job_instance_id=X` 无 tenant_id 是 multi-shard fan-out → 补 tenant_id 走单 shard
4. **stage fixtures `DO UPDATE SET current_timestamp` 非 IMMUTABLE**(reference 表也中招,只 local 表 pipeline_step_definition 豁免)→ `EXCLUDED.updated_at`
5. **⭐ dispatch circuit-breaker `DispatchChannelHealthMapper.tryClaimHalfOpenProbe` plain UPDATE 误用 `excluded`**(commit 6c97a11e5 的 IMMUTABLE 适配过度替换;`excluded` 仅 ON CONFLICT 有效,plain UPDATE 用之报 `missing FROM-clause entry for excluded`)→ 改回 `current_timestamp`。**单机也错,半开探测需故障渠道触发才暴露;SQL 层双向验证(修复版 UPDATE 1 / 旧版 ERROR)**。审计扫不出,真实场景挖出。
6. 13 v2 source 经 `PG_BIZ + -f /dev/stdin + input=` 但 PG_BIZ 不含 `-i`,stdin 不入容器 → 补 `docker exec -i`

### 基建/脚本改造(commit)
- env-common.sh 加 platform/business 双容器变量 + pg_platform/pg_business helper;新增 env-citus.sh(platform→citus-coord、business→batch_business_part)
- 21 个 stage 脚本去硬编码(shell helper + python PG_PLAT/PG_BIZ);25 补迁移;10 fixtures seed `-f docs/...` 容器内路径 → `-f /dev/stdin <`
- 缺失 fixture 补 self-contained:sim-stage5b-dispatch-fixtures.sql(TB_DISPATCH_STAGE5_FAIL_ONCE + tb_api_fail,单机由 created_by=sim-e2e 外部 seed 建,常驻分支漏带)
- coordinator `citus.max_shared_pool_size` 热加载 25→60(缓解 fan-out 连接耗尽)

### ⚠️ 未完成 / 环境 blocker(剩余 14-25)
- **worker-dispatch 重 build jar 在 JDK25 启动病态慢(10min+ 起不来)**:jstack 卡在 AspectJ 1.9.25 `World.resolve`/`ReflectionBasedResolvedMemberImpl.hasAnnotation` → `jdk.internal.classfile.impl.EntryMap`(JDK22+ 新 ClassFile API)。AspectJ pointcut 匹配 + nested-jar 类解析在 JDK25 病态慢(同 Mockito+JDK25 byte-buddy 性质)。其他 worker(旧 jar)35-77s 能起。**14/20 dispatch stage 完整 worker 验证 blocked**(mapper bug 已修+SQL验证,逻辑正确)。待:AspectJ 升级 / 或编译期织入 / 或 jar 用旧 jar 热替 mapper。
- **orchestrator hikari pool=6 在 15 storm(30 并发 launch)+ citus 慢查询下打满** → 连接超时、health down。调 pool=16 仍被 citus-w1 连接慢(偶发 30s establish 超时)拖到 connection leak。根因:citus coordinator→worker 连接建立偶发慢 + 服务 pool 偏小。待:服务 pool 调大 + citus 连接预热 / pgbouncer。
- **手动重启服务必须 source scripts/lib/env-common.sh**(供 BATCH_S3_ACCESS_KEY/SECRET_KEY/INTERNAL_SECRET),否则 S3Client bean `Secret access key cannot be blank` 启动失败。citus 拓扑用 `-Dspring.datasource.url=...25432... -Dspring.datasource.password=poc -Dbatch.datasource.business.url=...15432/batch_business_part`。
- 15 trigger / 16 atomic / 17-19 c系列 / 21 atomic / 22-25 trigger&import:未跑(环境恢复后续)。

### ⭐ Citus 性能发现:orchestrator 定时扫描 fan-out 过载(生产隐患,非仅测试)
现象:重启 orchestrator 后系统 load 飙到 16-24、citus-w1/w2 worker CPU 持续 300%+、
节点间连接建立 3s(sslmode=require SSL handshake)、orchestrator health timeout、不自愈。
诊断:citus-w1 上 30+ active 查询全是 `SELECT ji.id ... FROM batch.job_instance` 的
worker fan-out(coordinator 上 orchestrator 10+ active)。即 orchestrator 后台定时任务
(PartitionLeaseReclaim / DefaultRetryGovernance / 待处理 instance 扫描)发起的 job_instance
**全表/范围扫描查询不带 tenant_id 路由键 → Citus fan-out 到所有 shard 并行执行**,
高频定时 + 连接建立慢 → worker CPU 打满 → 查询堆积 → 恶性循环。
**这是 job_instance 复合分布(tenant_id)后,凡是"跨租户扫全表"的运维/调度查询都会 fan-out
放大的固有摩擦,生产同样存在**。待:① 定时扫描加 tenant 维度分批 / 或走 coordinator-local
物化视图 ② 调度查询避免无路由全表扫 ③ 节点间 sslmode=require→改快或预热连接池
④ 服务 hikari 必须 connection-timeout 放宽(citus 连接建立慢)+ initialization-fail-timeout=-1。

### 环境恢复 SOP(本轮过载后)
1. 系统过载(load>15 / worker CPU 300%+)不会自愈 → 重启 citus 栈 + 8 服务
2. 服务手动起必须 `source scripts/lib/env-common.sh`(S3/secret env)+ citus datasource -D 覆盖
3. worker-dispatch 改 mapper 后重 build 的 jar 在 JDK25 AspectJ 慢启动(10min+),需先解决再起
4. orchestrator/服务 hikari 加 -Dspring.datasource.hikari.connection-timeout=60000
   -Dspring.datasource.hikari.initialization-fail-timeout=-1(容忍 citus 连接建立慢)→ 实测 Started in 29.9s

### 🔴🔴 架构级阻塞(本轮最重要发现):orchestrator 后台定时任务 fan-out 过载
**精确定位**(coordinator pg_stat_activity 抓到的源头查询):
- `update batch.outbox_event set publish_status=$1, next_publish_at=current_timestamp where publish_status=$2 and updated_at < ...` — **outbox relay 高频投递扫描,WHERE 无 tenant_id**
- `select * from batch.job_instance where instance_status in ('WAITING','READY','RUNNING') and sla_alerted_at is not null and sla_alerted_at < $1 order by tenant...` — **SLA 监控扫描,WHERE 无 tenant_id**
- (同类:PartitionLeaseReclaim / retry governance 的待处理 instance 扫描)

**机理**:job_instance / outbox_event 复合分布(tenant_id)后,这些**全局后台扫描(本就该跨租户)
没有 tenant_id 路由键 → Citus 每次 fan-out 到所有 shard 并行执行**;定时任务高频(秒级)→
worker CPU 持续打满(实测单 orchestrator 即把 citus-w1/w2 推到 300-420%)→ 查询堆积 →
连接耗尽 → health timeout。**停 orchestrator,citus worker CPU 立即从 420% 落到 0.5%——
铁证**。这不是性能隐患而是**阻塞级**:当前 orchestrator 代码在 Citus 上一启动就过载,环境无法稳定。

**修复方向(需 orchestrator 代码层,非配置)**:
1. outbox relay:按 ACTIVE 租户列表分批扫(每租户单 shard 路由),或用 tenant 维度游标;参考已有
   TriggerOutboxRelay.selectPendingTenantIds 的按租户路由模式
2. SLA / lease-reclaim 扫描:同样改成按租户迭代单 shard,或建 coordinator-local 摘要表(物化)只在协调器扫
3. 通用原则:**任何不带分布键的 distributed 表全表/范围扫描,在 Citus 上都是 fan-out 热点;
   高频定时任务尤甚**。复合分布改造后须审计所有后台扫描查询(grep mapper 无 tenant_id 的 select/update on job_instance/outbox_event/job_task)
4. 注意:修后 rebuild orchestrator 会遇 JDK25+AspectJ 慢启动(同 worker-dispatch),需一并解决

**这是 partition-readiness(job_instance 复合 PK / Citus 分布)的核心遗留,优先级高于本轮所有 SQL bug。**

### 🔬 fan-out 过载的更深根因(实测,2026-06-13)——"什么原因"的完整答案
不是数据量(outbox 1785/job_instance 267,极小)。是 **fan-out 单次成本被三个因素叠加放大**:
| 实测 | 耗时 |
|---|---|
| 同查询带 tenant_id(单 shard 路由) | 0.28s |
| 不带 tenant_id(fan-out 32 shard) | 1.5-4s(不稳定) |

放大器:
1. **shard_count=32 过度分片**:每表 32 shard,数据却只有百千行。每个 fan-out 查询付 32 shard
   协调税(即使每 shard 0 行也要连 + 扫 + 聚合)。小数据 + 高频全局扫的表用 32 shard 是反模式。
2. **citus.max_cached_conns_per_worker=1**:coordinator 到每 worker 只缓存 1 连接,fan-out 要并行
   16 shard/worker 时反复新建连接。调到 16 后复用降到 0.577s(但需暖 + 不稳)。
3. **citus.node_conninfo=sslmode=require**:每次新建连接 SSL handshake 慢(冷却时实测 3s)。

机理:orchestrator 后台定时任务(outbox relay/SLA/lease,秒级)× 无 tenant 路由 × 上述慢 fan-out
→ 查询排队堆积 → worker CPU 打满 → 连接耗尽 → 主链路 worker→orchestrator REPORT 被 cancelled。

**为什么 08-13 跑通、现在跑不通**:08-13 跑单 stage,worker 查询都是单租户路由(单 shard 0.28s,
能挤进去慢慢完成);orchestrator 后台 fan-out 当时也慢但不阻塞 stage 的单 shard 查询。本轮我
反复重启服务 + worker-dispatch AspectJ 占满 CPU + 15 storm 30 并发,把系统推过临界 → 雪崩。

**修复分层(轻→重)**:
- 配置缓解:max_cached_conns_per_worker↑(已试 1→16 有效但不够)、sslmode=require→优化、
  **shard_count 32→小(如 4-8)重新 distribute**(治标最有效,小数据不需 32 shard)
- 代码治本:orchestrator 定时扫描加 tenant 路由/分批(参考 selectPendingTenantIds)
- 架构反思:outbox_event/job_instance 这类"小数据+高频全局扫"的表,32-shard distribute 是否合适?
  考虑更少 shard / reference / coordinator-local 摘要表

### ⚙️ Citus 性能优化实测 SOP + 配置层硬限制确认(2026-06-13)
**有效优化(citus CPU 330%→43%,fan-out 单次 1.5-4s→暖后 ~487ms)**:
- `max_connections` 100→**500**(3 节点,需重启容器)——容纳 pool×shard 连接,核心改善
- `citus.max_cached_conns_per_worker` 1→**8**(reload)——平衡:太大(32)爆 worker max_connections,太小(1)fan-out 反复建连
- 服务 hikari:`connection-timeout=60000`(后改 15000)+ `initialization-fail-timeout=-1`——容忍 citus 连接建立慢,否则启动 BatchPgSession bean 失败/health timeout

**认证坑(本轮我折腾 node_conninfo 引入的次生问题,务必记)**:
- `citus.node_conninfo` 有**白名单,不接受 password 参数**(安全),alter 加 password 被静默过滤
- 节点间密码认证(worker pg_hba `host all all all scram-sha-256`)的密码必须走 **.pgpass 或 pg_hba trust**
- 最简:worker pg_hba `scram-sha-256`→`trust`(docker 同网络,sed 改文件 + 重启 citus 清坏缓存连接)
- **改 node_conninfo 后必须重启 citus**(reload 对已缓存的节点间连接不生效,坏连接会一直 no-password 重试)
- 手动重启服务必须 source env-common(S3/secret),citus datasource 用 -D 覆盖(25432/poc + business 15432/part)

**❌ 配置层硬限制(已穷尽验证)**:即使上述全部优化,orchestrator 在 **32-shard fan-out 定时任务**下
**仍 pool 耗尽**(实测 Connection-timeout 30+ 次,08 launch 失败)。数学矛盾:orchestrator pool N 个
backend 并发 fan-out,每个需连一个 worker 的 16 shard,N×16×2 很快顶满 worker max_connections=500;
降 cached 缓解爆连接但 fan-out 变慢,此消彼长。**配置层只能把"完全崩溃"改善到"大幅缓解但仍受限",
无法让 orchestrator 稳定消费 sim 负载**。

**唯一根治(二选一,都需代码/schema 改动 + 测试,不宜在疲劳会话仓促做)**:
1. **reshard 32→4**:`alter_distributed_table(任一表, shard_count=>4, cascade_to_colocated=>true)`
   (group 2 含全部 123 表,注意 outbox_event/job_instance 月分区表的 reshard 兼容性需先验证)
2. **orchestrator 定时扫描加租户路由**(outbox relay/SLA/lease 按 ACTIVE 租户分批,参考 selectPendingTenantIds)

### ✅✅ 根治成功:reshard 32→4 解决 fan-out 过载(2026-06-13)
配置层穷尽后确认 32-shard 是硬限制,执行 reshard 根治:
```sql
SELECT alter_distributed_table('batch.job_task', shard_count => 4, cascade_to_colocated => true);
```
- group 2 全部 123 表(含 outbox_event/job_instance 两张月分区表)32→4,citus 13 在线 reshard,
  数据量小(总几千行)几分钟完成,无需停 schema(停服务避免锁冲突即可)
- **fan-out 暖后 487ms → 4.9ms(降 100 倍)**;orchestrator pool 不再耗尽
- **orchestrator health 200 恢复,08 端到端跑通(2 SUCCESS + 3 预期 FAILED)**
- citus 13 的 alter_distributed_table 正确处理了月分区表(reshard parent + 分区),无兼容问题

**结论**:job_instance/outbox_event 这类"小数据 + 高频全局扫描"的表,32-shard 是过度分片;
4-shard 在保留分布式能力的同时让 fan-out 成本可接受。这应是 partition-readiness 的 shard_count
默认值修正(citus.shard_count 默认 32 对本系统数据规模偏大)。配合前述配置优化(max_connections=500
+ 认证 pg_hba trust),环境恢复可承载 sim。

### 🏁 reshard 后 sim 全阶段跑完结果(2026-06-13)
环境经 reshard 32→4 + 配置优化 + 认证修复恢复后,跑完 15-25:
| stage | 结果 | 说明 |
|---|---|---|
| 08-13 | ✅ PASS×6 | import/export/process 三类 worker 全格式矩阵 |
| 15 trigger-stage6b | ✅ PASS | storm 30 |
| 16 atomic-stage5b | ⚠️ 功能 SUCCESS | shell/sql/proc instance 全 terminal SUCCESS;脚本 0/3 是 worker-atomic 调度间隔 2.5min/task(重启后状态)致 wait 150s 超时 |
| 17 import-stage2c | ✅ PASS | import matrix |
| 18 export-stage3c | ✅ PASS | |
| 19 process-stage4c | ✅ PASS | |
| 21 atomic-stage5c | ⚠️ 功能 SUCCESS | atomic_http_demo 真访问 example.com SUCCESS;同 16 worker-atomic 调度间隔致脚本 timeout |
| 22 trigger-stage6c | ✅ PASS | storm 60 |
| 23 import-stage2d | ❌ edge | skip_under_threshold 场景 validate STATUS_INVALID(行数阈值跳过逻辑 vs 数据 status,需查 import skip 逻辑) |
| 24 trigger-stage6d | ❌ edge | cron-fire✓/pause✓,但 resume 后 scheduler 没重新 fire(pause/resume misfire 时序) |
| 25 checkpoint-crash | ❌ 脚本适配 | worker-import 用 scripts/local/restart.sh(单机 15432)重启,citus 下连错库致 checkpoint 不推进;需改 25 用 citus 启动命令重启 worker |
| 14/20 dispatch | ⏸ blocked | worker-dispatch 重 build jar JDK25+AspectJ 慢启动(10min+),未起 |

**实质成果**:11 stage 完整 PASS + 2 stage(16/21)功能验证通过(脚本受 worker 调度间隔);3 个 edge/适配(23/24/25)+ 2 个 dispatch(工具链)待。**核心 Citus 兼容性 + 主链路(trigger→orch→kafka→worker→report,含 storm 60 并发)在 4-shard Citus 上验证通过**。

### 23/24/25 根因诊断(worker/scheduler 代码级,非环境/主链路)
reshard 根治环境后,主链路 + 11 stage 通过;剩 3 个是 worker 代码细节的 Citus 适配:
- **25 checkpoint-crash**:`PlatformFileRuntimeMapper.insertPipelineInstance` 的
  `ON CONFLICT (tenant_id, related_job_instance_id) WHERE related_job_instance_id IS NOT NULL`
  报 `no unique/exclusion constraint matching`。jar 内 mapper 确有 WHERE(非旧 jar),uk 部分索引
  citus/单机都在(reshard 未丢),08(related_job_instance_id 有值)reshard 后 PASS;25 失败。
  差异在 **checkpoint 模式**:pipeline_instance 先创建时 related_job_instance_id 为 null,
  Citus distributed 表对 partial-index(带 WHERE 谓词)的 ON CONFLICT 推断比单机严格 → 不匹配。
  修:worker-core checkpoint 创建逻辑 / 或部分索引谓词调整;需 rebuild worker-import(遇 AspectJ 慢)。
  另:25 脚本 worker 重启已从 restart.sh(单机)改 env-citus 的 citus_restart_worker helper。
- **23 import-stage2d**:skip_under_threshold 场景(1 坏行应低于阈值跳过校验→SUCCESS)实际
  validate STATUS_INVALID。worker-import 的"坏行容错阈值"逻辑/配置在 Citus 未按预期跳过。
- **24 trigger-stage6d**:cron-fire✓/pause✓,但 resume(sim-stage6d-trigger-resume.sql 清
  trigger_runtime_state + enable)后 scheduler 180s 没重新 fire。trigger 调度器 resume 后
  next-fire 恢复时序问题。
**性质**:三者都是 partition-readiness 的 **worker/scheduler 业务逻辑** Citus 适配,非 SQL 方言/
环境;每个需深入对应 worker 代码 + rebuild(JDK25 AspectJ 慢启动),宜作独立专项。
