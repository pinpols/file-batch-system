# 三租户覆盖包与真实执行

用于用户要求用 ta/tb/tc 检查作业、Workflow 图或 Worker 时，先区分配置样本、隔离导入和实际运行。此参考不授权重置共享环境，也不把样本当作自动执行的测试。

## 确定输入与分母

- 当前覆盖示例及维护入口为 [`coverage-examples/README.md`](../../../../test-data/tenant-config-packages/coverage-examples/README.md)，逐包对象、图和依赖以文件及其测试为准。上级 `*-tenant-config-package-test.xlsx` 是原 SIM 夹具，不替代覆盖示例，也不要覆盖原件。
- 按租户、Job 类型、Workflow 编码、节点/边类型、汇聚方式和依赖列出计划测试的对象，再逐项记录实际结果。`GENERAL` 是禁用的历史占位，不计为可执行 Worker；BUNDLE 类型需要对应文件清单。图里有条件、失败或 WAIT 节点不等于相关路径已执行。
- 记录后端及前端源码/构建身份、三个工作簿 SHA-256、运行环境与目标租户；测试范围和数据版本不一致时不能沿用旧结果。

## 配置检查与隔离导入

1. 只需要检查样本时，运行目录 README 中的 `test_coverage.py` 命令；它检查工作簿静态覆盖与图约束，不连接服务、不证明能导入或执行。修改样本后按 README 用当前 Console 模板生成到新目录，再运行静态检查。
2. 若要验证真实 upload → preview → apply，先确认用户授权的隔离数据库、Console 身份、目标租户及其数据归属。示例的 `tenant_id` 固定对应 ta/tb/tc；不能将文件直接应用到别的租户。完整示例含平台资源配置，使用具备权限的平台管理员；预览成功不能代替 Apply 授权。
3. 在写入之前核对目标租户及相关配置是否为空、租户与业务数据库是否对齐、同编码和隐藏/软删除记录是否存在。预览不提供可靠的完整新增/覆盖清单时，不以空预览或自定义前缀作为不覆盖已有数据的证明；无法确认就停止。保留预检回执、影响范围和恢复方案。
4. 本地初始化入口见 [`scripts/data/README.md` 的覆盖租户章节](../../../../scripts/data/README.md#本地覆盖租户初始化)：脚本仅接受 loopback 地址，默认检查租户存在性与包哈希，真实登录可能写认证审计；`--apply` 才创建租户并导入，需凭据文件和显式承认 insert-only 限制。它不会清理数据，也不能事务内保证只新增。创建或应用结果不明时停止并人工核对，不能重发或盲目续跑；已完成阶段按回执规则恢复。具体参数、凭据和本机 bypass 的限制以该 README 为准，mock 测试不等于真实初始化成功。
5. 手动或脚本导入均须维持同一租户和认证身份，使用最新 `previewToken`。Apply 是持久化写入，记录请求和响应，随后按租户回读定义、图和关键字段；重试前核对幂等状态。禁止用 SQL 直接造成功结果。任何未完成项按失败或未验证记录，不清空共享库/队列/对象存储。
6. 需要可重复隔离集成证据时，可按当前测试源码选择 `ConsoleTenantCoveragePackagesIntegrationTest`，用 Maven Wrapper 和 `-pl batch-console-api -am` 运行，并确认 Testcontainers/Docker 可用、测试未跳过。该测试的随机端口/独立容器和数据库回读证明的是配置导入，不是前台浏览器或 Worker 运行。

## 真实运行与恢复

1. 单独核对各类型所需 Worker、数据库表与 RLS、对象存储、SFTP/API 对端、凭据和网络；样本中的地址、SQL、模板与文件名是示意，不能当作环境已配置。每种 BUNDLE 使用该场景有效的 manifest；WAIT 准备匹配的文件到达事件。依赖不能满足时标记未验证，不发请求试错。
2. 使用经授权的独占租户、唯一批次/幂等键和合适的业务日，通过 Console 的工作流列表运行入口或 `POST /api/console/workflow-definitions/{id}/trigger` 按定义 ID 触发；请求字段、认证和幂等头以当前 OpenAPI/Controller 为准。单 Job 使用平台受管单作业 Workflow。按需构造条件真/假、成功/失败、ALL/ANY/N_OF 和依赖满足/不满足的输入，不让自动调度或复用已有实例掩盖目标路径。
3. 对每项按根请求 → WorkflowRun/DAG 节点 → JobInstance → task/partition → Worker claim/执行/report 核对身份与状态，并回读业务库、对象字节/校验和、外部回执等该类型实际副作用。控制节点不等同 Worker 任务；HTTP 受理、静态图或单次 SUCCESS 都不能证明整条链路和全部分支。
4. 仅在用户授权且环境隔离时准备失败、重启或故障恢复；先确定下游副作用和未知结果处理，再按现有 Worker/恢复 Runbook 复核幂等、lease、Outbox 和残留。定向场景优先；[`scripts/local/sim-harness.sh`](../../../../scripts/local/sim-harness.sh) 默认样本与本覆盖包不同，其 `all/reset/prereq` 有写入、重启和清理副作用，不能作为本示例默认入口。
5. 结束时只清理由本轮明确拥有的资源，核对残留和清理失败；回执或资源归属不明时保留现场。报告每租户每场景的静态、真实导入、真实执行、业务副作用及恢复证据，并单列跳过、失败和未验证；隔离集成不代表 staging 或生产验收。

## 按需查阅

- [运行测试与验收入口](../../../../docs/runbook/be-acceptance.md)和 [SIM 脚本边界](../../../../scripts/sim/README.md)
- [覆盖包实施与历史隔离导入记录](../../../../docs/plans/tenant-coverage-packages-2026-11-03.md)：仅作为当时环境和版本的证据，实际执行前重新核对版本与范围。
- [Worker 与 Pipeline 链路审查](../../worker-pipeline-review/SKILL.md)，只在实际执行和恢复涉及相应链路时使用
- 配对前端 `batch-console/docs/user-guide/jobs-and-workflows.md` 和 `runs-and-approvals.md` 说明手动触发、结果不确定的核对及运行 DAG；`batch-console/e2e/fixtures/runtime-progress/README.md` 供需要 UI、SSE 或角色联验时使用，其前置、授权和清理边界必须另核。
