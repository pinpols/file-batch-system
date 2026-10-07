# CI 门禁耗时分析与优化记录（2026-10-07）

## 结论

本轮目标不是削减验证范围，而是消除错误路由、分片长尾和无效环境准备：

- PR Gate 从“任意 Maven 变更都跑全部单元分片”改为依赖边界保守路由。
- 原 `unit-it-b2` 拆为 Worker 与 Console 两个并行执行分片，稳定的 `unit-it-b2` required context 由聚合 job 提供。
- PR CodeQL 使用 Java `build-mode: none`；main push、定时和手工运行继续执行手工全量编译。
- Full/Staging E2E 使用最近一次成功运行的测试类耗时，从 4 片重排为 6 片。
- PR / Full Gate 的 Java 分片均将依赖构建与测试执行分开，上游模块只编译安装，不在每个分片重复测试。
- Python / Shell / 契约类守卫留在 `static-checks`，与 Maven unit/IT 隔离；同一 GitHub Actions SHA 守卫不再重复执行。
- 27 个 `*ArchTest` / `*ConventionTest` 从业务 unit/IT 中排除，由 PR、Full、Staging 的独立 `java-governance` job 执行；源码清单与 Surefire 报告必须一一对应。
- 不运行 Testcontainers 的静态、安全和 CodeQL job 不再恢复或拉取容器镜像缓存。

目标区间是 PR required checks P50 3.5-5 分钟、Full Gate P50 4.5-6 分钟。GitHub-hosted runner 的排队时间不受 workflow 内部并发配置控制，因此 P90 允许比目标上浮 1-2 分钟。

## 优化前证据

统计时间为 2026-10-07，数据来自 GitHub Actions 最近成功运行：

| Workflow | 样本 | P50 | P90 | 最近一次 |
|---|---:|---:|---:|---:|
| PR Gate | 20 | 8m10s | 8m58s | 8m58s |
| PR CodeQL | 18 | 8m19s | 9m58s | 9m02s |
| Full Gate | 20 | 8m26s | 11m44s | 8m01s |

最近一次 PR Gate 的首个 runner 等待 47 秒；随后关键路径是 `unit-it-b2` 482 秒，其中 Maven 测试 424 秒。对应 Full Gate 首个 runner 只等待约 3 秒，最长 `unit-it-b2` / E2E job 分别为 475 / 477 秒。更新的成功运行 `37607420871` 中，`unit-it-a` 的 Maven 阶段为 328 秒，E2E 每片又花费 80-116 秒重复安装上游 reactor。由此可见，单纯增加 `max-parallel` 不能解决问题，必须先去掉分片内重复测试。

PR CodeQL 最近一次耗时构成：环境准备 44 秒、全 reactor 编译 289 秒、分析 136 秒、上传 7 秒。Java 无构建模式可以删除 PR 上的手工编译关键路径；GitHub 官方同时提示，无构建模式对生成代码和依赖推断可能不如手工构建准确，因此本项目只在 PR 使用，main 和定时扫描继续保留手工构建。

参考运行：

- PR Gate：<https://github.com/pinpols/file-batch-system/actions/runs/37599727117>
- PR CodeQL：<https://github.com/pinpols/file-batch-system/actions/runs/37599727109>
- Full Gate：<https://github.com/pinpols/file-batch-system/actions/runs/37601373202>
- main CodeQL：<https://github.com/pinpols/file-batch-system/actions/runs/37601373236>

## PR 单元测试路由

| 变更来源 | 执行分片 |
|---|---|
| `batch-orchestrator`、Java SDK | `unit-it-a` |
| `batch-worker/core` | `unit-it-a`、`unit-it-b1`、Worker b2 |
| Trigger、Process、Dispatch | `unit-it-b1` |
| Import、Export、Atomic | Worker b2 |
| Console API | Console b2 |
| `batch-common`、`batch-test-support`、数据库迁移、任意 POM、Maven Wrapper、未知路径 | 全部分片 |

新增 `batch-*` 源码模块在登记前按全分片处理。纯 CI、文档、部署配置或脚本变化继续执行对应静态检查，但不会无依据启动 Maven 单元测试。

## E2E 六片基线

最近一次 Full Gate 的 28 个 Surefire XML 总测试体耗时约 999 秒。按测试类实测耗时执行 LPT 后，六片测试体分别约为：

| Shard | 测试体基线 |
|---:|---:|
| 1 | 158s |
| 2 | 179s |
| 3 | 177s |
| 4 | 176s |
| 5 | 159s |
| 6 | 148s |

每片先使用 `install -DskipTests -am` 安装依赖闭包，再不带 `-am` 只测试分片自身模块。这样
`batch-common` / `batch-test-support` 中的架构守卫和 Testcontainers 用例不会被多个分片重复执行。
依赖编译仍保留，不以跳过上游构建换取速度。预计宽变更的 PR 关键路径约 4-5 分钟；继续扩到 8 片会明显增加冷启动和 runner 占用，暂不采用。

PR unit 分片还显式排除 `*IntegrationTest`。`-DskipITs` 只会跳过 Failsafe，不会阻止 Surefire
按 `*Test` 规则命中这些类。本地对照中，orchestrator 的 PR 测试阶段由 3 分 50 秒降到
44.9 秒，1406 个单元/组件测试通过；被排除的真集成测试仍由 Full Gate 和 Staging Gate 执行。

Full Gate 使用相同的依赖构建分离模型，但目标模块仍执行
`verify -DskipITs=false`，因此 unit 和 Testcontainers IT 覆盖不减少。`batch-common` 和
`batch-test-support` 在 `unit-it-b1` 中执行一次，不再因其他 shard 的 `-am` 被重复测试。
静态守卫独立于 Maven unit/IT，但不拆成新 runner job：当前 `static-checks` 约 3 分 41 秒，
不在 Full Gate 关键路径，额外 checkout/JDK 准备只会增加算力和排队。

Java 测试型治理守卫单独拆为 `java-governance` job，因为它们需要编译后类路径且失败责任与业务 unit/IT 不同。
统一入口改为单 reactor 命令后，本地实测 27 个治理测试在 11 个归属模块中约 68 秒完成，
相比原先“安装上游 48 秒 + 定向测试 63 秒”减少约 43 秒；与业务分片并发后不进入当前关键路径。
`*GuardTest` 不作为粗暴迁移规则：其中多数是限流、准入、存储和配置的业务行为测试，仍归 unit/IT。
三个在线 workflow 与本地 pre-push 统一调用 `run-java-governance-tests.sh`，避免模块清单和 Maven 参数复制四份后漂移；
pre-commit 只跑治理测试源码清单检查，不承担 Maven 构建成本。
Staging Gate 采用相同路由：治理组与 6 个全量 E2E shard 并发，E2E 清单、完整性校验和业务结果不受治理测试影响。
不增加共享 E2E 预构建前置 job，避免所有 shard 等待单点构建而拉长墙钟时间；重复上游安装后续只在有在线耗时证据时再评估制品复用。

## 验收与回退

本地只能验证 YAML、路由器单测、E2E 清单完整性、Action 固定和文档同步；真实性能必须看合入前 PR 与合入后 Full Gate 的在线运行。

验收窗口为连续 10 次有效运行：

- PR Gate 与 PR CodeQL 分别统计 P50/P90，取消运行不进入样本。
- Full Gate 必须保持单元、集成、E2E、安全扫描全部成功。
- 六个 E2E shard 均必须产出声明数量的 Surefire suite。
- PR `build-mode: none` 与 main 手工 CodeQL 的告警范围不得出现无法解释的持续差异。
- 任一 workflow P90 连续三次超过目标上限 50%，按 `docs/runbook/ci.md` 排查 runner 排队、缓存命中和分片漂移。

如 PR CodeQL 出现漏析证据，将 PR 恢复为 `manual`；如六片 E2E 的 P90 未改善或 runner 排队显著恶化，回退到四片并使用本记录中的类耗时重新平衡。required check 名称不因回退变化。
