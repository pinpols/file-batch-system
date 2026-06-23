# ADR-029 · 专用 Task SPI Worker(dual-use 能力隔离)

- **Status**: Accepted(2026-05-30)
- **Date**: 2026-05-30
- **Related**: Task SPI Phase 1-5(`docs/design/task-spi-design.md`)/ 差距分析(`docs/analysis/competitive-gap-analysis-2026-05-30.md`)
- **Source**: dual-use(RCE 级)executor 部署隔离讨论

## 背景

Task SPI 的 `shell` / `stored-proc` 执行器是 **RCE 级 dual-use 能力**(能执行任意命令 / 存储过程)。
原设计把全部 executor(shell/sql/stored-proc/http)放在共享的 `batch-worker-core`,四个 pipeline
worker(import/export/process/dispatch)技术上都能经 `@ConditionalOnProperty` 开启。

问题:
1. **blast radius**:若在某 pipeline worker 开 shell,该 worker 本身握业务 DB 凭据 / MinIO / Kafka
   (尤其 process worker:COMMIT 阶段直接写业务表、跑配置化 SQL),一个坏 shell 任务 = 拿到该 worker
   能碰的一切。CI 配置守护只能控"在哪开",**控不了"开了多危险"**。
2. **职责揉杂**:pipeline worker 是固定文件管线,塞通用 RCE executor 模糊定位。

## 决策

**新增第 10 个模块 `batch-worker-atomic`** —— 专用 Task SPI worker:

- 把 shell / sql / stored-proc / http 四个 executor **从 worker-core 迁入本模块**(`io.github.pinpols.batch.worker.atomic.*`)
- 四个 pipeline worker 的 classpath **物理上不再含这些 executor** → 结构上做不了 SPI(强于 CI 软守护)
- worker-core 退回纯运行时(CLAIM / lease / report / dispatch-consumer + SPI registry 路由机器)
- 本 worker 部署时配**独立低权限 datasource**(不连业务库)、独立 K8s serviceaccount / 网络策略 /
  seccomp,把 RCE blast radius 压到最小

## 破"固定模块"规则的理由

CLAUDE.md 原"固定 9 模块不可擅自增删"。本次显式增到 10,理由是**安全基线**(RCE 特权隔离),
不是 scope 扩张 —— 这正是该破规则的少数正当场景。已同步更新 CLAUDE.md 模块清单。

## 范围边界(不做)

- **不**把 SPI 变成通用 job 平台:SPI 仍只服务"文件交付 workflow 的胶水步骤"(见差距分析 scope 红线)。
- sql / http(低危)也一并迁入本模块图统一,但低危的本可留 core;若将来要 http 贴近 dispatch 的出向
  基础设施,可单独评估。

## 实施分阶段

| 阶段 | 内容 | 状态 |
|---|---|---|
| PR-1 结构隔离 | 新模块 + 迁 executor + 4 worker 物理无 SPI + 本 ADR + CLAUDE.md | ✅ 本 PR |
| PR-2 运行时接线 | WorkerRouteAdapter + worker_type + application.yml + 注册/路由 + IT | ⏳ 待环境 |
| PR-3 部署 | Helm / compose + 最小权限下放 | ⏳ ops |

**注意**:PR-1 后 SPI 暂无 worker 可跑(executor 已迁出 core,新 worker 运行时未接)。当前生产**未使用**
shell/proc,此 sequencing 可接受;PR-2 接好再上生产。

## 后果

- ✅ RCE 特权隔离(只有本 worker 能跑 shell/proc,且可最小权限部署)
- ✅ 4 pipeline worker 纯净,worker-core 纯运行时
- ✅ `scripts/ci/check-dual-use-executor-allowlist.py`(软守护)被结构隔离取代,PR-2 后可退役或缩为弱版
- ⚠️ +1 模块 +1 部署物(Helm/CI build),运维面增加
