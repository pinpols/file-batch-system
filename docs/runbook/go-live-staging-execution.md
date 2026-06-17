# 上线 · Phase 1-3 staging 执行 playbook(可复制粘贴)

> 配套 [`go-live-readiness.md`](go-live-readiness.md) 的**最小执行清单**:在**生产同构 staging** 上逐条跑、记录、签字。
> 本次参数:**5–20 jobs/s**、**RTO < 2h / RPO < 15min**。
> 通用前置:`unset BATCH_ENV_COMMON_ROOT`(防 profile 污染);staging 的 app(orchestrator+workers+console+trigger)+ infra(PG/Kafka/MinIO/Valkey)已起。

---

## Phase 1-A · 全链路功能

```bash
# sim 连跑(非单跑)。preflight 失败先按提示修(常见:BATCH_SECURITY_BYPASS_MODE)
bash scripts/local/sim-harness.sh all
# 收尾静默(负向 cron→MANUAL + 清死信)
bash scripts/sim/98-quiesce-schedules.sh
```
**通过判据**:`PASS 阶段数` = 全部;`FAIL` 仅出现在已知 sim-tooling(若 06-verify/strict-verify 已合 #562 应无 FAIL);stage25 checkpoint-crash PASS。
签字:__________

## Phase 1-B · 容量 / SLO(目标 5–20 jobs/s)

```bash
cd load-tests
# 控制面混压(本次核心场景)+ 目标量级 + SLO 硬门。STAGING_URL 指向 staging trigger/console。
mvn gatling:test -Dsimulation=ControlPlaneMixedPressureSimulation \
  -Dusers.peak=20 \
  -Dslo.write.p95ms=500 -Dslo.read.p99ms=300 -Dslo.maxErrorPct=1.0 \
  -Dtarget.baseUrl=$STAGING_URL          # 按 load-tests/README 的 profile/参数指向 staging
# 再各跑一遍:容量基线 + launch 写压 + 端到尾完成
for S in CapacityBaselineSimulation JobLaunchSimulation LaunchPipelineCompletionSimulation; do
  mvn gatling:test -Dsimulation=$S -Dusers.peak=20 -Dslo.maxErrorPct=1.0 -Dtarget.baseUrl=$STAGING_URL
done
```
**峰值时同时记录**(控制面瓶颈核对,已知单机~20 jobs/s):
```bash
PSQL='docker exec <staging-pg> psql -U <user> -d batch_platform -tAc'
$PSQL "select count(*) from batch.outbox_event where publish_status in ('NEW','FAILED');"   # outbox 积压应稳定不发散
$PSQL "select wait_event_type, count(*) from pg_stat_activity where state='active' group by 1;" # claim/report 锁等待
# Kafka consumer lag(launch 单线程消费)+ PG 写 TPS 走你的监控大盘
```
**通过判据**:Gatling assertions 全绿(p95<500ms / 错误率<1%)+ outbox 积压不发散 + lag 收敛。逼近 20 则记录余量 + 定容量上限。
签字:__________

## Phase 1-C · DR 演练

```bash
# (1) 整队崩 → 精确一次(脚本已对真实 schema 校验)
PG_CONTAINER=<staging-pg> POSTGRES_USER=<user> PG_PLATFORM_DB=batch_platform \
WORKER_CONTAINERS="worker-import worker-export worker-process worker-dispatch worker-atomic" \
SETTLE_TIMEOUT_S=180 \
bash scripts/sim/dr-drill-fleet-crash.sh
# 前置:先起一批载荷在飞(如 sim 05-load 或 load-tests),再跑本脚本

# (2) PITR → RPO/RTO(RESTORE_CMD 填你的备份工具)
PG_CONTAINER=<staging-pg> POSTGRES_USER=<user> PG_PLATFORM_DB=batch_platform \
RTO_BUDGET_S=7200 \
RESTORE_CMD='pgbackrest --stanza=batch --type=time --target="$RESTORE_TARGET_TIME" restore' \
bash scripts/sim/dr-drill-pitr.sh
#   WAL-G:  RESTORE_CMD='wal-g backup-fetch /var/lib/postgresql/data LATEST && touch .../recovery.signal && ...'
#   RDS/Aurora: RESTORE_CMD='aws rds restore-db-instance-to-point-in-time --restore-time "$RESTORE_TARGET_TIME" ...'
```
**通过判据**:整队崩脚本退 0(无重复 job_instance / 无重复 outbox / 无复活卡死);PITR 脚本退 0(RPO 不丢 T0 前已提交 + RTO ≤ 2h)。另做 PG failover + Kafka 短时不可用 + DLQ 重放。
签字:__________

## Phase 1-D · 安全签收

```bash
# ① prod profile 强制 bypass-mode=off(⚠️ 本机 sim 曾临时改 true,prod 必须 false/未设)
$PSQL "show batch.security.bypass_mode;" 2>/dev/null   # 或查 app 启动配置/actuator/env
curl -s -o /dev/null -w '%{http_code}' -X POST $STAGING_URL/api/...   # 无凭据写应被拒(401/403),非 200
# ② 越权:以租户 A 上下文查租户 B 数据应 0 行(batch 列 + biz RLS)
$PSQL "set local app.tenant_id='A'; select count(*) from biz.<表> where tenant_id='B';"  # 期望 0
# ③ 依赖/镜像漏洞:codeql + trivy 清零(CI),atomic worker RCE 隔离(ADR-029)按 IT 证据
```
**通过判据**:prod bypass=off;无凭据写被拒;跨租查 0 行;扫描无新高危。
签字:__________

## Phase 1-E · 可观测

- [ ] OTel trace 端到端贯通(trigger→orchestrator→worker→report,一个 traceId 串全)
- [ ] SLO 大盘:吞吐 / p99 / outbox 积压 / DLQ / PG 锁 / Kafka lag
- [ ] 告警规则 + health/liveness 探针 + 优雅停机预算实测(drain 期 lease 保活、不丢在飞)
签字:__________

## Phase 2 · 割接演练

```bash
# Flyway 在 prod-sized 数据上 dry-run(记录耗时 + 锁影响)
mvn -pl batch-orchestrator flyway:info flyway:migrate -Dflyway.url=<staging-prod-sized>
# 承重墙复核:UNIQUE 幂等语义迁移后不变
grep -rn 'on conflict' --include=*.sql . | wc -l   # 对照基线;新增/改动 UNIQUE 列集需评审
# 回滚演练:迁移可逆 / 数据可回退(按你的回滚脚本)
```
**通过判据**:迁移耗时可接受 + 锁影响可控 + 回滚验证通过 + RTO/RPO 达标。
签字:__________

## Go / No-Go 总闸
- [ ] Phase 0(CI 全绿 + 扫描清零)
- [ ] Phase 1-A~E 全签字 + 1-B SLO 达标 + 1-C 整队崩/PITR 通过
- [ ] Phase 2 迁移 + 回滚 + RTO/RPO
- [ ] 灰度预案 + 监控基线 + 回滚预案 + 值班就位

**全勾 = 可上线。** 任一未勾 = No-Go,补齐再发。
