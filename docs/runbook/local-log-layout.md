# 本地日志布局

本地脚本统一使用 `scripts/lib/logging.sh` 创建日志目录。目标是把“常驻应用日志”和“一次性运行日志”分开,避免 `logs/` 根目录继续堆 `run2.log`、`jacoco-run-2` 这类不可读文件。

## 目录约定

```text
logs/
  current/
    app/                 # start-all / restart 当前进程日志
    docker/              # docker 应用日志落盘目录
  runs/
    tests/<run-id>/      # run-tests.sh 每次执行
    be-acceptance/<run-id>/
    sim-harness/<run-id>/
    sim-4day/<run-id>/   # sim-4day 4 天批量/批量日/观测快照
  verification/<domain>/ # 按验证领域分组的运行日志
  archive/
    app-<component>/     # 按 console / orchestrator / worker 等组件分组的历史应用日志
    legacy/              # 首次迁移旧 logs/app、logs/test 等目录

.local/run/pids/
  start-all.pids           # 本地进程状态，不属于日志
```

`logs/` 的真实目录最多三层。协调过程快照、SQL 输入、源码备份、编译产物和依赖缓存不属于运行日志，统一放在被忽略的 `tmp/log-artifacts/` 或 `tmp/cache/`；PID 状态保存在 `.local/run/pids/`。运行报告和复现资产由本地迁移清单记录，不混入应用日志目录。

## 兼容路径

以下旧路径会自动变成软链,旧脚本/人工习惯短期仍可用:

- `logs/app` -> `logs/current/app`
- `logs/docker` -> `logs/current/docker`
- `logs/test` -> 最新一次 `logs/runs/tests/<run-id>`
- `logs/be-acceptance` -> 最新一次 `logs/runs/be-acceptance/<run-id>`
- `logs/sim-4day` -> 最新一次 `logs/runs/sim-4day/<run-id>`
- `logs/start-all.pids` -> `.local/run/pids/start-all.pids`

## 命名规则

- 常驻应用:`<component>.log`,例如 `orchestrator.log`、`worker-import.log`。
- 验收步骤:`<step>-<action>.log`,例如 `01-build-maven.log`、`03-test-integration.log`。
- 测试结果:`<step>-test-<scope>-failed.log`,例如 `02-test-integration-failed.log`。
- 4day 仿真:`00-run-4days.log`、`02-day0-2026-06-06.log`、`02-day0-2026-06-06-watch.log`。
- run id:`<label>-YYYYMMDD-HHMMSS-<git-sha>`。

归档目录：

- 常驻日志归档文件：`logs/archive/app-<component>/<component>.YYYYMMDD-HHMMSS-<git-sha>.log`
- Docker 日志归档文件：`logs/archive/docker/<service>-YYYYMMDD-HHMMSS-<git-sha>.log`
- 一次性运行目录：`logs/archive/legacy/` 用于第一次碰到旧布局（如 `logs/app`）时自动迁移保留。

旧目录首次遇到时会移动到 `logs/archive/legacy/` 后再创建软链,不会直接删除。

每次获取当前日志目录时会执行一次轻量整理：误放在 `logs/current/` 或 `logs/current/app/` 的 `.gz`、`*.screen.log`、`*.manual*.log`、`*.debug*.log` 和压测日志会移动到 `logs/archive/legacy/`。`logs/current/app/` 只保留当前常驻应用的规范 `.log`，确保运行时 WARN 审计不会重复扫描历史或人工日志。Spark E2E 依赖缓存和本地进程 PID 状态分别保存在 `tmp/cache/` 与 `.local/run/pids/`，不放入日志目录。

相同组件在同一秒内连续启动时，归档名会追加 `.1`、`.2` 等序号，避免覆盖。

## 保存、压缩归档与到期清理

统一入口为 `scripts/local/manage-logs.py`，只依赖 Python 3.9+ 标准库和 `lsof`（macOS 自带；Linux 需安装）。策略的唯一默认来源是 `config/local-log-retention.json`，所有类别均按最后修改时间计算连续 24 小时日龄：

| 类别 | 原文保存 | 范围 |
|---|---|---|
| runs | 7 天 | `logs/runs` 的完整运行目录；以目录内最新文件判断，兼容旧版直接位于 runs 下的运行目录 |
| app | 7 天 | `logs/archive/app-*` 及旧版 `logs/archive/app` 的历史应用文件 |
| verification | 30 天 | `logs/verification` 下按文件判断的验证日志 |
| legacy | 30 天 | `logs/archive/legacy`、`docker` 与 `docker-stopped-*` 的历史文件/目录 |

到期原文压缩到 `logs/archive/retention-<类别>/<UTC时间>-<唯一ID>.tar.gz`。每类每次生成一个包，减少散落目录；每次最多处理 256 MiB 原文，可修改策略的 `max_source_mib`。超过单次上限的完整运行留待后续处理，不拆开运行目录。预览中的“保留或超过单次上限”还包括近期、被保护、包含软链或特殊文件的条目。

包内保留原始 `logs/...` 相对路径、文件大小、时间及 SHA-256 清单。写入临时包、校验全部内容、同步落盘后才发布；再次检查原文件未变且未重新受保护后移除原文。失败时原文保留；归档成功后原文删除中断时可能暂时保留重复副本。文件权限为 0600；归档内容未作脱敏或外传。

压缩包从生成时刻起再保存 90 天。只有显式添加 `--prune-archives` 才会列出到期包；同时加 `--apply` 才删除。仅识别本脚本格式、当前工作树来源、通过内容校验的受管包；手工包、损坏包和其他工作树的包不会作为可直接删除的候选。

以下内容受到保护：

- `logs/current` 永远不参与候选；当前应用仍由 start-all/restart 在重启时归档，脚本不对持续运行的 stdout 实施在线轮转。
- 软链及其目标（包括 `latest` 和兼容入口）保留，不跟随软链写入或删除目录外的数据。包含软链/特殊文件的运行目录整体保留。
- `lsof` 标识为打开中的文件保留；无法完整取得占用信息就失败退出。
- 文件旁的 `<文件名>.keep`、所在目录或祖先目录中的 `.keep` 固定保留；运行内任意受保护文件会保护整个运行。
- `docs/**/*.md` 和根目录 Markdown 中字面引用的具体日志文件及具体运行目录保留；通用目录示例不冻结整类日志。通配符、带空格等不能由字面引用识别的证据，请显式加 `.keep`。后来新增的证据引用也会保护包含该原始路径的受管归档包。

```bash
# 默认只读预览，不创建归档或删除文件。
python3 scripts/local/manage-logs.py

# 仅预览验证日志；覆盖当前调用的原文保存天数。
python3 scripts/local/manage-logs.py --category verification --keep-days 30

# 在目标开发工作树根目录执行预览中列出的压缩归档。
python3 scripts/local/manage-logs.py --apply --confirm-root "$PWD"

# 预览归档操作和超过 90 天的受管包；执行时再添加 --apply --confirm-root "$PWD"。
python3 scripts/local/manage-logs.py --prune-archives

# 固定保留一轮运行或某个独立文件。
touch logs/runs/tests/<run-id>/.keep
touch logs/verification/workflow/<evidence>.log.keep
```

执行需要传入与 `--root` 解析后相同的工作树绝对路径；默认 root 是脚本所在工作树。维护任务通过 `.local/run/locks/log-retention.lock` 的操作系统文件锁串行化，进程退出即释放锁；不注册 cron/launchd，也不在应用启动时自动删除历史日志。

`cleanup-disk.sh --include-run-logs / --include-app-logs` 已委托该入口，`--retention-days` 作为对应类别的原文保存天数（兼容显式 0，表示无额外年龄窗口）；其 `--apply --confirm-root <仓库目录名>` 兼容参数保持有效。旧入口现在执行压缩校验后移除原文，不直接销毁原日志；归档包的到期删除统一使用新入口的 `--prune-archives`。

恢复时先用 `tar -tzf <归档包>` 查看内容，再解压到 `tmp/log-artifacts/` 下新建的空目录，按清单核对 SHA-256 后把需要的日志放回原路径，避免覆盖当前文件。归档包是本地留存，不替代机器故障或磁盘损坏的异地备份。

本地回归：`python3 -m unittest scripts/ci/tests/test_local_log_retention.py`。覆盖默认预览、归档内容/删除顺序、当前日志、占用、引用/固定标记、软链、文件变化、损坏包、过期判定、确认路径及并发锁。真实工作树仅运行只读预览，未对已有日志执行归档或过期删除。

本次实现的本地验证和全仓门禁边界见[2026-10-24 留存验证记录](../verifications/local-log-retention-2026-10-24.md)。
