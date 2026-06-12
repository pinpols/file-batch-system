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
