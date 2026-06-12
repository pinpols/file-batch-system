# Citus 下一阶段:sim 多业务 + 真实数据验证(backlog)

> 前置已达成(2026-06-12):atomic worker 端到端 SUCCESS、5 类 Citus SQL 方言约束通用清障模式、
> 双栈零回归 BUILD SUCCESS。本文是"sim 全业务在 Citus 全绿 + 真实数据严格验证"的可执行清单。
> 上游:docs/analysis/citus-w8-runtime-findings-2026-06-11.md(约束模式 + harvest 方法)。

## 一次性环境准备
1. 集群:`bash scripts/local/citus-cluster.sh up && initdb`(3 GUC 已固化)
2. Flyway:起 orchestrator(`-Dspring.datasource.url=...25432/batch_platform -D...password=poc
   -Dspring.datasource.hikari.maximum-pool-size=6`),等 flyway 顶=179
3. distribute:停服务 → `psql < scripts/db/citus/01-distribute.sql`(123 dist + 32 ref)
4. seed:`grep -vE '^BEGIN|^COMMIT' platform_seed.sql | psql`(去事务,reference 配置落地;
   distributed demo 行 INSERT 报缺分片列属预期,不连坐)
5. 业务库:worker 连 batch_business_part(`-Dbatch.datasource.business.url=...15432/batch_business_part`)
6. 连接池:全局 6(8 服务 × 6 < coordinator max_connections 100;打满是真坑)

## sim 脚本的 Citus 适配(待做)
- scripts/sim/* 默认连主库 15432;需参数化/覆盖为 coordinator 25432 + business part
- sim seed(01-init-biz 等)同样去事务 + distributed 表 INSERT 带分片列

## 逐业务 harvest(每类按 /tmp 模式:发任务→轮询 worker SUCCESS→抓错→清→重试)
已通:**atomic**(shell/sql/stored-proc/http,CLAIM/REPORT 通用链路已清)
待逐个跑 + 清专属 SQL:
- [ ] **import**:process_staging(local 表,不分布)写入;PreprocessStep/LOAD 链路
- [ ] **export**:keyset 分片查询(已 PR #393)在 distributed 表的跨分片行为
- [ ] **process**:pipeline_step_run / pipeline_progress(已清 markCompleted)/ SqlTransformCompute
- [ ] **dispatch**:file_dispatch_record / file_channel_health(已清健康上报 upsert)
- 每跑一类,grep worker 日志的 Citus 错误(distributed/IMMUTABLE/FOR UPDATE/CASE/COALESCE/
  partition column),按 5 类模式清,双栈验证后提交

## 已知待清(subagent 误判 + 未现形)
- pipeline_progress 已清(b833b1717);FileGovernance maxProcessingDelay/staleSteps 已清
- selectProcessingDelaySamples(FileGovernanceMapper SELECT 列表 extract(current_timestamp)):
  非 CASE/COALESCE,Citus 可能允许,harvest 验;不行则同样传参
- 其他业务专属 upsert/CASE-COALESCE:跑到才暴露,逐个清

## 真实数据严格验证(worker SUCCESS 后)
- strict-verify.sh 适配 Citus(PG_CONTAINER/PG_PORT 指 coordinator):cursor/offset 一致性、
  审计落表、cron-preview、maintenance、§3b outbox 重复事件——在分布式表上复验
- 多租户洪峰(citus-introduction-plan 门槛③):N 租户并发各跑批,观测分片均衡 + SLA

## 收尾
- 全部 worker SUCCESS + 真实数据绿 → 完整 e2e 在 Citus 上(可选,需 e2e app 连 coordinator)
- 双栈零回归终审(普通 PG)+ 推分支(不合 main)
