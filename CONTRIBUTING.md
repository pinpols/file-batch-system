# Contributing

感谢参与 file-batch-system。仓库同时包含控制面、五类业务 Worker、共享 Worker Core、五语言 SDK、运维脚本和部署资产；提交前请先确认变更归属，不要通过跨模块复制逻辑解决边界问题。

## 开始之前

1. 阅读根目录 [`AGENTS.md`](AGENTS.md)、[`docs/agent-baseline.md`](docs/agent-baseline.md) 和受影响目录的 README。
2. 从最新 `main` 创建短生命周期分支；不要直接向受保护的 `main` 推送。
3. 检查 `git status` 和现有差异，保留他人或并行任务留下的改动。
4. 优先复用 Maven Wrapper、仓库脚本和现有测试入口，不在 CI 配置中复制业务判断。

## 变更归属

| 变更类型 | 主要位置 | 必须同步核对 |
|---|---|---|
| Console REST API | `batch-console-api` | OpenAPI、配对前端 `../batch-console` 的生成类型和调用方 |
| 调度、状态机、重试、补偿 | `batch-orchestrator` | CAS/事务、Outbox、幂等、租户隔离和 E2E |
| 触发、misfire、日历 | `batch-trigger` | Quartz 持久化、去重、补点语义 |
| Worker 执行 | `batch-worker/*` | lease、取消、checkpoint、失败恢复和资源边界 |
| 数据库 | `db/migration`、Mapper | 前向迁移、锁、约束、RLS、回滚/恢复；已发布迁移不可修改 |
| Java SDK | `sdk/java/*` | core 轻依赖边界、starter/testkit、协议 fixture |
| Python/Go/TS/Rust SDK | `sdk/<language>` | wire contract、共享常量、运行时 transport 和语言门禁 |
| Shell/SQL/运维 | `scripts/*` | 公共函数、配置来源、Linux/macOS、Docker/非 Docker 和失败退出码 |
| 部署配置 | `deploy`、`helm`、Compose | 配置事实来源、Secret、生产 overlay 和文档 |

平台运行时模块边界、禁止事项和例外以 [`docs/agent-baseline.md`](docs/agent-baseline.md) 及 ADR 为准。禁止新增 JPA/Hibernate；不要让 Worker 或 SDK 直接写平台数据库。

## 实现要求

- 修改前沿调用链核对校验、鉴权、租户归属、事务、并发、幂等、重试和错误映射。
- 保持状态单一写入和既有模块依赖方向；新增抽象必须解决真实重复或隔离问题。
- 配置项应有唯一事实来源，并同步默认值、环境变量、容器、Helm、脚本和文档。
- HTTP、日志、异常和诊断输出不得泄露凭据、完整敏感响应或跨租户数据。
- 数据库迁移只允许追加新版本；涉及 `UNIQUE`、`ON CONFLICT`、RLS 或分区时必须检查完整契约。
- SDK core 保持语言约定的轻依赖边界；可选框架放在独立适配层，transport 变更必须评估公共 API、依赖闭包和非幂等请求重放语义。
- 只在业务意图不明显时写中文注释，重点解释为什么存在该约束，而不是复述代码。

## 文档和生成物

- Console HTTP 契约变化时更新：
  - `docs/api/console-api-protocol.md`
  - `docs/api/console-api.openapi.yaml`
  - `../batch-console/src/types/api.generated.ts` 及受影响调用方
- Orchestrator 内部协议变化时同步 `docs/api/orchestrator-internal.openapi.yaml`、SDK fixture 和五语言实现。
- Excel 模板由 API 的 `downloadTemplate` 端点运行时生成，不维护静态模板副本。
- 架构约束或 ADR 状态变化才更新 `docs/changelog.md`；普通功能记录以提交、PR 和对应专题文档为准。
- 不手改生成文件。使用仓库记录的生成命令，并把源文件和生成结果放在同一 PR 中。

## 验证

所有 Maven 命令使用 `./mvnw`。先跑最小相关验证，再按影响扩大范围；多模块验证使用 `-am` 带上依赖模块。

```bash
# 编译受影响模块
./mvnw -ntp -pl <module> -am -DskipTests compile

# 定向单元/集成测试
./mvnw -ntp -pl <module> -am test

# 文档和仓库结构
python3 scripts/ci/check-docs-structure.py
python3 scripts/ci/check-changelog-sync.py --base origin/main

# PR 级本地入口
make ci-pr
```

按变更类型补充：

| 范围 | 推荐入口 |
|---|---|
| Java 全量 | `make ci` 或 `bash scripts/ci/run-full-regression.sh` |
| 单模块 | `make ci-module M=<module>` |
| SDK 契约 | 各语言测试 + `bash scripts/ci/run-sdk-live-transport-gate.sh` |
| 本地全链路 | `bash scripts/local/sim-harness.sh all`，以当前 runbook 为准 |
| 性能 | `load-tests/` 对应入口；记录 SHA、镜像、环境、工作负载和业务结果 |

不能执行的验证必须在 PR 中写明原因和残余风险。静态检查、编译、单测、容器 IT、本地真实服务和 staging 验收是不同证据，不得互相替代。

## 提交与 Pull Request

- 每个提交保持单一意图；不要混入 IDE 状态、构建产物、日志、临时数据或机器路径。
- PR 描述至少包含：问题/目标、实现边界、风险、验证命令与结果、未验证项。
- required checks 未通过时不得强制合并或绕过门禁。失败应定位根因并补定向验证。
- 行为、配置或运维流程变化必须同步测试和文档；纯重构也要证明外部契约未变。
- 合并后确认 `main` 门禁，并清理已合并的短期分支和 worktree。

## 安全问题

不要在公开 Issue 中披露可利用漏洞、生产凭据或真实租户数据。请按 [`SECURITY.md`](SECURITY.md) 的渠道报告安全问题。
