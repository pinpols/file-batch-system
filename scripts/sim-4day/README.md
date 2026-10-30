# SIM 4day — 10 租户 / 4 业务日 本地批量调度模拟

10 个租户(ta/tb/tc + 克隆 t04–t10),3 种 archetype profile,连续 4 个 bizDate 跑
IMPORT → EXPORT → DISPATCH → WORKFLOW,带增量数据 + 大文件,全程可观测。

## 前置
- infra(docker)+ 8 个 app JVM 在跑(`scripts/local/start-all.sh`)。
- 触发走 trigger API `:18081`,用 `X-Internal-Secret`(`.env.local` BATCH_INTERNAL_SECRET),无需登录。
- 配置导入(一次性)需 console 登录 token(admin/admin123)。

## 租户矩阵(profile)
| archetype | 租户 | 导入→业务表 | 导出源 | 分发渠道 |
|---|---|---|---|---|
| retail | ta, t04, t07, t10 | customer→`biz.customer_account` | customer_account | `ta_local_archive`(LOCAL) |
| bank   | tb, t05, t08 | transaction→`biz.transaction` | transaction | `tb_api_push`(API_PUSH→mockserver) |
| risk   | tc, t06, t09 | risk_score→`biz.risk_score` | risk_score | `tc_api_risk_push`(API_PUSH→mockserver) |

## 跑法(按序)
```bash
cd <repo>
# P0 清空异常数据(保留 config)
bash scripts/sim-4day/00-clean.sh
# P1 配置:重导 ta/tb/tc(需 token)+ bootstrap + 克隆到 10 租户
#   token: SIM_COOKIE_FILE="$(mktemp)"; curl -s -c "$SIM_COOKIE_FILE" POST "$CONSOLE_BASE/api/console/auth/login" -d '{"username":"admin","password":"admin123"}'
#   （00-clean 不动 config;若已克隆过可跳过 P1)
source scripts/lib/env-common.sh
docker exec -i "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$PLATFORM_DB" < scripts/sim-4day/10-clone-tenants.sql

# P3+P4 一键 4 天(每天行数递增 300/600/900/1200 + Day0 投大文件)
bash scripts/sim-4day/41-run-4days.sh 2026-06-06 300

# 单天手动
ROWS=500 bash scripts/sim-4day/40-run-day.sh 2026-06-07

# 仅大文件
bash scripts/sim-4day/30-gen-bigfiles.sh 20260606

# P5 观测(一屏 / 持续刷新)
bash scripts/sim-4day/50-watch.sh
bash scripts/sim-4day/50-watch.sh --loop
```
也可在控制台 UI 看:`$CONSOLE_BASE`(文件流水线 / 监控 / Outbox / 审批)。

## 关键技术契约(已验证)
- **IMPORT**:`launch{jobCode, params:{templateCode, content:<CSV>}}` → ParseStep(preserveLogicalRow)
  → LoadStep 按模板 `query_param_schema.jdbcMappedImport.columnMappings`(identity 映射)写 biz 表,
  `conflictColumns` UPSERT。行 key 含 bizDate ⇒ 每天新增 = 增量。
- **EXPORT**:`launch{jobCode, params:{templateCode, batchNo}}` → 读 biz 表 → 写 MinIO
  `batch-dev/outbound/<JOB>/<bizDate>/<batchNo>/v1/*.csv`,file_record=GENERATED。
- **DISPATCH**:`launch{jobCode, params:{fileId:<GENERATED 文件>, channelCode}}`。LOCAL / API_PUSH(→mockserver)已通;
  OSS 暂不支持、SFTP 需 sftp 容器。驱动里自动取最新 GENERATED 文件分发(export→dispatch 链)。
- **大文件**:投 MinIO `batch-dev/ingress/<tenant>/<...yyyyMMdd...>.csv`,ImportIngressScanner(30s+30s稳定窗)
  登记 file_record=RECEIVED(真实体积)。

## 已知边界(诚实记录)
- **大文件的“真加载入库”**走的是内联 content 路径(40-run-day)。基于 MinIO 对象的 import 自动加载
  在本环境用 trigger 参数(storagePath/fileId)未驱通(RECEIVE 默认 sourceType=UPLOAD/LOCAL,需更多 source-type
  接线),所以大文件主要演示**摄取/扫描/登记大对象**;入库体积由 content 驱动(可调 ROWS 到数千)。
- `TA_IMPORT_ORDER_TPL` 等模板是**占位规格**(无 columnMappings)→ 触发会 LOAD 失败;驱动只用已补全的
  customer/transaction/risk_score 三类导入。
- DISPATCH 仅 LOCAL/API_PUSH 通;OSS/SFTP 渠道在本机不可用(无 sftp 容器 / worker 未实现 OSS dispatch)。
- `dead_letter` 会累积失败任务(含清库前的在途重试 churn + order 导入 + 不可用渠道);属预期噪声。

## 文件
- `00-clean.sh` 清空异常数据(保留 config)，同时清理 Kafka runtime topic 历史消息
- `10-clone-tenants.sql` 克隆 ta/tb/tc → t04–t10(幂等)
- `30-gen-bigfiles.sh` 生成大文件投 MinIO ingress
- `40-run-day.sh <bizDate> [ROWS]` 单日驱动(10 租户)
- `41-run-4days.sh <startDate> [baseRows]` 4 天驱动(行数递增 + Day0 大文件)
- `50-watch.sh [--loop]` 监控仪表盘

## 脚本与配套 SQL 索引

本表登记本目录的执行入口、公共模块及配套 SQL；测试代码通过测试命令执行。新增、移动或删除资产时同步跳转和用途，SQL 正文保持独立维护。

| 文件 | 用途 / 调用归属 |
|---|---|
| [00-clean.sh](00-clean.sh) | ADR-sim 4day · P0 清空异常数据(保留所有 config)。 |
| [10-clone-tenants.sql](10-clone-tenants.sql) | DANGER: 替换 4-day sim 克隆租户配置数据。仅限本地 sim 平台库，执行前确认租户范围。 |
| [30-gen-bigfiles.sh](30-gen-bigfiles.sh) | ADR-sim 4day · P3 大文件:生成几个大 CSV 投到 MinIO ingress,演示大文件摄取(扫描器登记 + 真实体积)。 |
| [40-run-day.sh](40-run-day.sh) | ADR-sim 4day · P4 单日驱动:对 10 租户按 archetype 跑 IMPORT→EXPORT→DISPATCH→WORKFLOW。 |
| [41-run-4days.sh](41-run-4days.sh) | ADR-sim 4day · P4 四天驱动:连续 4 个 bizDate 跑批,行数逐日递增(增量放大)。 |
| [42-run-4days-batchday.sh](42-run-4days-batchday.sh) | ADR-sim 4day · 真批量日版:每个业务日 open(由 launch 自动开)→ 跑批 → 手动日切 CLOSE(→SETTLED)→ 推进。 |
| [50-watch.sh](50-watch.sh) | ADR-sim 4day · P5 监控仪表盘:批量调度过程一屏看。 |
| [sql/batchday-status-by-day.sql](sql/batchday-status-by-day.sql) | 供 [42-run-4days-batchday.sh](42-run-4days-batchday.sh) 使用的配套 SQL。 |
| [sql/batchday-summary.sql](sql/batchday-summary.sql) | 供 [42-run-4days-batchday.sh](42-run-4days-batchday.sh) 使用的配套 SQL。 |
| [sql/clean-business-runtime.sql](sql/clean-business-runtime.sql) | 供 [00-clean.sh](00-clean.sh) 使用的配套 SQL。 |
| [sql/clean-platform-runtime.sql](sql/clean-platform-runtime.sql) | 供 [00-clean.sh](00-clean.sh) 使用的配套 SQL。 |
| [sql/close-day.sql](sql/close-day.sql) | 供 [42-run-4days-batchday.sh](42-run-4days-batchday.sh) 使用的配套 SQL。 |
| [sql/config-baseline.sql](sql/config-baseline.sql) | 供 [00-clean.sh](00-clean.sh) 使用的配套 SQL。 |
| [sql/runtime-residue.sql](sql/runtime-residue.sql) | 供 [00-clean.sh](00-clean.sh) 使用的配套 SQL。 |
| [sql/select-latest-generated-file.sql](sql/select-latest-generated-file.sql) | 供 [40-run-day.sh](40-run-day.sh) 使用的配套 SQL。 |
| [sql/select-request-execution-status.sql](sql/select-request-execution-status.sql) | 跨业务日 SIM 场景的配套 SQL；由场景入口或参数化路径选择，执行边界见所属说明。 |
| [sql/select-workflow-definition-id.sql](sql/select-workflow-definition-id.sql) | 供 [40-run-day.sh](40-run-day.sh) 使用的配套 SQL。 |
| [sql/select-workflow-run-status.sql](sql/select-workflow-run-status.sql) | 供 [40-run-day.sh](40-run-day.sh) 使用的配套 SQL。 |
| [sql/verify-business-counts.sql](sql/verify-business-counts.sql) | 供 [41-run-4days.sh](41-run-4days.sh) 使用的配套 SQL。 |
| [sql/verify-export-files.sql](sql/verify-export-files.sql) | 供 [41-run-4days.sh](41-run-4days.sh) 使用的配套 SQL。 |
| [sql/verify-platform-run.sql](sql/verify-platform-run.sql) | 供 [41-run-4days.sh](41-run-4days.sh) 使用的配套 SQL。 |
| [sql/verify-workflow-fixtures.sql](sql/verify-workflow-fixtures.sql) | 供 [41-run-4days.sh](41-run-4days.sh) 使用的配套 SQL。 |
| [sql/watch-business-counts.sql](sql/watch-business-counts.sql) | 供 [50-watch.sh](50-watch.sh) 使用的配套 SQL。 |
| [sql/watch-job-instance-status.sql](sql/watch-job-instance-status.sql) | 供 [50-watch.sh](50-watch.sh) 使用的配套 SQL。 |
| [sql/watch-outbox-deadletter.sql](sql/watch-outbox-deadletter.sql) | 供 [50-watch.sh](50-watch.sh) 使用的配套 SQL。 |
| [sql/watch-pipeline-status.sql](sql/watch-pipeline-status.sql) | 供 [50-watch.sh](50-watch.sh) 使用的配套 SQL。 |
| [sql/watch-recent-failures.sql](sql/watch-recent-failures.sql) | 供 [50-watch.sh](50-watch.sh) 使用的配套 SQL。 |
