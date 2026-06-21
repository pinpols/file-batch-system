# 提交审查报告:2026-06-05 ~ 2026-06-09

> 审查范围:`origin/main` 59 个提交(#365 ~ #441),744 文件,+36,184 / -4,600。
> 方法:按主题 4 路并行深审(存储迁移 / 导入导出 / 性能分区 / 稳定性契约),每条发现逐项核验后只保留高置信度(≥80)结论。
> 所有问题已在同日修复分支 `fix/review-2026-06-09-findings` 处理,状态见各条目。

## Critical

| # | 问题 | 位置 | 来源 | 置信度 | 修复 |
|---|------|------|------|--------|------|
| 1 | sim 清理脚本在**平台库** TRUNCATE `batch.process_staging`(该表属业务库):清错表或报错中断 `00-clean.sh`,sim 带异常数据跑 | `scripts/sim-4day/sql/clean-platform-runtime.sql` | benchmark 系列 | 95 | ✅ 移到 clean-business-runtime.sql |
| 2 | `EncryptingObjectStore.statSize()` 返回**密文**长度,被 `PreprocessStep` 当明文长度做分区 range 切分;`decoratorEnabled=true` 时必然分片错位(当前默认 false 幸免) | `batch-common/.../EncryptingObjectStore.java` | #395/#398 | 90 | ✅ 新增 `supportsRangeRead()` 能力探测,加密层回退整份流式;statSize/presign 补密文语义文档 |
| 3 | `pg-write-parameter-matrix.sh` 用 `ALTER SYSTEM` 改实例级 PG 参数,无 PGHOST 守护,`.env.local` 指错地址即改生产实例 | `scripts/local/pg-write-parameter-matrix.sh` | benchmark 系列 | 88 | ✅ 加 localhost 白名单守护(`PG_PARAM_MATRIX_ALLOW_REMOTE=1` 显式越过) |
| 4 | `attachClause`(Console 可写模板字段)自由文本直拼 `ATTACH PARTITION` DDL,黑名单校验防不了 `/* */` 块注释 | `JdbcMappedImportSpec.validateStageSwap()` | #412 | 88 | ✅ 改白名单字符集校验(仅允许分区边界字面量字符) |
| 5 | `S3AutoConfiguration` 无 backend 条件:`backend=filesystem` 时仍建 S3Client + 健康探针,readiness 被拉 DOWN | `batch-common/.../S3AutoConfiguration.java` | #394/#395 | 85 | ✅ 加 `@ConditionalOnProperty(backend=s3, matchIfMissing=true)`(properties 注册由 BatchObjectStoreAutoConfiguration 回退,filesystem 模式不受影响) |

## Important

| # | 问题 | 位置 | 置信度 | 修复 |
|---|------|------|--------|------|
| 6 | `biz.process_event_copy` 新表(带 tenant_id)漏 RLS:transition/strict/rollback 三脚本 + sim-clean 均未覆盖 | `scripts/db/business/rls-phase-a*.sql` | 82 | ✅ 四处补齐 |
| 7 | upload session 孤儿无清理:创建 session 后不上传不 confirm,`file_record` 永久滞留 `WAITING_ARRIVAL`,现有三条清理路径全覆盖不到(#440 功能缺口) | `FileGovernanceScheduler` | 85 | ✅ 新增 TTL 清理调度(默认 24h,可关;S3 对象已存在的跳过) |
| 8 | `create_biz_tables.sql` 无条件 `DROP TABLE process_staging CASCADE`:运维误跑(本应跑 migrate 脚本)会删掉生产分区表 | `scripts/db/business/create_biz_tables.sql` | 85 | ✅ 改 relkind 幂等判定:已是分区表绝不 DROP |
| 9 | stale launch recovery 中 dispatch 已提交后 `reconcileLaunched` 失败 → 本次恢复被误记 failed(实际 trigger_request 滞留可由 ADR-010 reconciler 自愈) | `StaleCreatedLaunchRecoveryScheduler` | 80 | ✅ reconcile 失败降级 WARN,注明自愈路径 |
| 10 | lease fast-retry 不检查熔断:orchestrator 不可达时主路径熔断但 fast-retry 继续逐条加压 | `WorkerTaskLeaseRenewer` | 80 | ✅ 入口加 `circuitOpen` 检查(与主路径对称) |
| 11 | `stopDraining` 诊断字段在 CAS 块外赋值:并发 start/stop 时 status() 与实际状态对不上 | `OrchestratorGracefulShutdown` | 80 | ✅ 移入 CAS 赢家分支 |
| 12 | `writeTimeoutMs` 假配置(声明可绑定但从未生效);`DispatchFileContentResolver` 残留 "MinIO not configured" 文案 + `IllegalStateException` 不走存储异常体系 | `S3StorageProperties` / `DispatchFileContentResolver` | 88/80 | ✅ 删字段补语义注释;改 `ObjectStoreException` + 中性文案 |

## 验证为正确的关键设计(审查中专项核验,非清单式打勾)

- **导出分片**:hashtext 负数取模 `(h%N+N)%N`、keyset 等宽切分、末片 `includeUpper`、range-slice 行边界对齐(同 Hadoop TextInputFormat split 语义)——无重复无丢数
- **分区策略**:UTC 整天边界 + ShedLock 互斥 + default 回退分区;`migrate-process-staging-to-partitioned.sql` 的 relkind 幂等 DO 块是本批最规范 DDL;docker init 与生产脚本结构一致
- **stage-swap 原子性**:DETACH/DROP/RENAME/ATTACH 四步 DDL 在同一事务——PostgreSQL 事务性 DDL 保证整体回滚(MySQL 无此性质)
- **缓存租户隔离**(#441):业务数据 key 全部过 `keySegment(tenantId)`(转义+hash 防撞);kafka-lag 不带 tenant 是有意的平台级设计
- **并发原语**:lease registry 读写锁 + `while` 防虚假唤醒 + 单调钟防 NTP 回拨;GracefulKafkaShutdown 三步顺序不可交换且实现对齐
- **4xx 契约**(#380):`AbstractApiExceptionHandler` 基类统一三模块,`DuplicateKey`/乐观锁 → 409 全覆盖
- **stale recovery 防误判**(#420):SQL 三重 not-exists 守卫 + 60s 窗口 + DB 唯一键回退,无静默双执行
- **ADR-038 续跑**:字节位点截断边界正确,终页不记位点 + `offset=0` 哨兵防崩溃窗口误续跑

## 总评

工程质量中上。问题集中两类:**`scripts/` 运维脚本缺守护**(最易直连生产出事的地方,主代码路径反而严谨)和**抽象层边角语义**(加密装饰器 statSize/presign、自动配置条件这类静默失败弹)。`biz.process_event_copy` 漏 RLS 再次印证:逐 PR 扫描查不出"全局清单类"缺口,需要定期对标(RLS 表清单 vs 实际带 tenant_id 的表)回退。
