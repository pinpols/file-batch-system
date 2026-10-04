# 五类 Worker 1w / 10w 压测验收记录 - 2026-10-04

本文是五类 Worker 压测的当前验收记录模板和证据入口。它不声明五类 1w / 10w 已在当前代码全部通过；只有填入本页的实跑 RUN_ID、原始报告、终态统计和环境签名后，才可作为上线容量证据。

## 范围

| Worker | 入口 | 1w | 10w | 说明 |
|---|---|---|---|---|
| Import | `load-tests/scripts/run-worker-load-tests.sh` | 待实跑 | 待实跑 | inline import 容量；对象存储大文件导入另走专项 |
| Export | `load-tests/scripts/run-worker-load-tests.sh` | 待实跑 | 待实跑 | 导出链路和文件登记容量 |
| Dispatch | `load-tests/scripts/run-worker-load-tests.sh` | 待实跑 | 待实跑 | 本地 LOCAL channel；外部 SFTP/NAS/EMAIL/OSS 不在默认安全压测内 |
| Process | `load-tests/scripts/run-worker-load-tests.sh` | 待实跑 | 待实跑 | launch 数容量；千万行计算吞吐另走 `run-process-worker-benchmark.sh` |
| Atomic | `load-tests/scripts/run-p2-capacity-profile.sh` | 待实跑 | 待实跑 | 隔离 benchmark 拓扑 task storm |

## 权威命令

四类业务 worker 使用单类运行，避免多模块互相污染：

```bash
RUN_ID=ltw-import-1w-<ts> WORKER_MODULES_CSV=import USERS_PER_WORKER=10000 \
  WAIT_TERMINAL_TIMEOUT_SECONDS=3600 bash load-tests/scripts/run-worker-load-tests.sh

RUN_ID=ltw-import-10w-<ts> WORKER_MODULES_CSV=import USERS_PER_WORKER=100000 \
  WAIT_TERMINAL_TIMEOUT_SECONDS=7200 bash load-tests/scripts/run-worker-load-tests.sh
```

将 `WORKER_MODULES_CSV` 分别替换为 `export`、`dispatch`、`process` 后复用同一口径。

Atomic 使用 P2 容量画像入口：

```bash
RUN_ID=p2-atomic-1w-<ts> STORM_TOTAL_REQUESTS=10000 STORM_RPS=100 \
  RUN_10W_STORM=1 RUN_FAIRNESS=0 bash load-tests/scripts/run-p2-capacity-profile.sh

RUN_ID=p2-atomic-10w-<ts> STORM_TOTAL_REQUESTS=100000 STORM_RPS=200 \
  RUN_10W_STORM=1 RUN_FAIRNESS=0 bash load-tests/scripts/run-p2-capacity-profile.sh
```

## 留档要求

每一轮必须填写：

- Git revision、工作树 dirty 状态、Docker CPU/内存、PostgreSQL/Kafka/Worker 容器拓扑。
- `RUN_ID`、原始报告路径、HTTP OK/KO、实例总数、终态数、`SUCCESS` 数、非终态数。
- Kafka lag、PostgreSQL 容量/保留计划、磁盘水位、自动清理结果。
- 是否使用 `SKIP_AUTO_CLEANUP=1`；若保留现场，必须写清清理命令和完成状态。
- 是否可作为生产容量承诺。默认本地单机结果只能作为本地容量边界和设计缺陷暴露证据。

## 当前状态

截至 2026-10-04，仓库已落地五类压测入口、清理脚本、磁盘水位保护和生产容量/保留治理计划；当前文档尚未填入本轮五类 1w / 10w 的完整实跑结果。

已有历史参考：

- [`control-plane-100k-throughput-optimization-2026-09-12.md`](./control-plane-100k-throughput-optimization-2026-09-12.md)
- [`worker-local-runtime-performance-2026-09-20.md`](./worker-local-runtime-performance-2026-09-20.md)
- [`worker-matrix-verification-2026-06-08.md`](./worker-matrix-verification-2026-06-08.md)
- [`worker-p2-capacity-profile-2026-06-08.md`](./worker-p2-capacity-profile-2026-06-08.md)
