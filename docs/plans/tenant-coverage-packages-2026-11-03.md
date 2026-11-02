# ta/tb/tc 全类型与 Workflow 覆盖配置包

## 目标与范围

在现行配置包契约上新增一套独立的 ta/tb/tc 示例，支持前台上传和预览，覆盖作业类型与有代表性的 Workflow 图、分支和依赖。原有 `test-data/tenant-config-packages/*-tenant-config-package-test.xlsx` 仍为 SIM 权威输入，不覆盖或改写。示例是受控的配置材料，不代表业务表、渠道、Worker 或执行结果已就绪。

## 基线与缺口

- 后端当前工作树分支 `fix/menu-display-alignment`；基线提交 `4975b6a0f`，并行改动保留。前端基线 `fix/status-filters-worker-loading`、`e039a7fe4`。
- 后端三份原包共 10 个 Job，仅含 IMPORT、EXPORT、DISPATCH；5 个 Workflow 共 28 节点/28 条边，含 START/END/JOB/GATEWAY、四种边和 ALL/N_OF 汇聚，无 WAIT/ANY，也没有作业或 Workflow 的 `depends_on_job_code`。
- `JobType` 共 9 个字典值。GENERAL 是历史占位，无平台 Worker handler；其余 8 类要按实际契约覆盖，不能把定义登记等同于可执行或业务成功。TASK 是历史 Workflow 节点，不计入新建节点覆盖；WAIT 的 `node_params` 以实际传感器校验规则为准。
- tc 旧包条件边使用 `${bizDate != null}`，现行生成器使用 `bizDate != null`。两者不是可直接互换的表达式；本套按实际条件求值器使用规范形式，并以独立真/假条件验证。
- 前端历史三包包含已退役 Pipeline 两张 sheet，当前后端明确拒绝；不可作为现行可导入样本。

## 设计与验收

1. 在独立目录产出三份清晰命名的 XLSX 与可复现生成入口；使用后端当前 Schema/模板列，均为手动调度，避免导入后自动发起任务。每租户覆盖 8 类当前可执行 Job 和禁用的 GENERAL 占位；三租户联合覆盖所有汇聚模式。
2. 每张图具备合法起止、无环、节点引用与边条件；覆盖串行、并行/ALL/ANY/N_OF、条件/失败分支、WAIT，至少提供作业依赖和 Workflow 依赖示例。标明系统可导入但前端设计器不可编辑的 WAIT。
3. 标明导入作用的租户、角色、配置项以及表、凭证、渠道、清单、Worker 和外部依赖；样本不携带可用密钥，不默认执行外部副作用。
4. 静态核验逐包记录 Job 类型、图节点、边、汇聚和依赖；如可使用隔离 Testcontainers，真实 Console 分别上传、预览、应用并回读独立租户配置；若环境不可用，列为未验证，不以结构检查或旧 SIM 代替。真实业务执行、用户共享租户导入和实际数据操作不在本次范围。

## 当前状态

2026-11-03：完成当前工作簿和真实本地数据库只读盘点；盘点时本地数据库仅 `default`、`default-tenant`、`system`，三者无 Job 或 Workflow；ta/tb/tc 不存在。新增 [`coverage-examples`](../../test-data/tenant-config-packages/coverage-examples/README.md) 三包及不覆盖旧文件的生成脚本；每包 9 类 Job（含禁用 GENERAL）、3 个组合 Workflow、START/END/JOB/GATEWAY/WAIT、四类边，分别示例 ALL/ANY/N_OF。静态覆盖检查 1 项通过，逐包检查类型、引用、无环、条件和依赖。

使用 `ConsoleTenantCoveragePackagesIntegrationTest` 和新建 Testcontainers PostgreSQL、Redis、Kafka、对象存储，关闭 console 只读副本和安全 bypass，核验匿名 401；三包分别以认证身份经真实 HTTP 上传、预览（无无效行）、应用并在数据库回读类型、禁用占位、组合 Workflow、WAIT、汇聚、条件和作业依赖，测试通过。所有落库 Workflow 类型为 MANUAL；三份组合 Workflow 的调度开关为 false。系统另外创建的 9 个托管单作业 Workflow 的 `schedule_enabled` 实际为 true，但 MANUAL 不满足 Trigger 的 CRON/FIXED_RATE 且 SCHEDULED/MIXED 注册条件。首次隔离应用发现空 calendar 会引用不存在的 `default_calendar`，故各 Job 已显式引用包内租户日历并重新通过验收。

条件求值器定向测试验证 `proceed == true` 对 true/false 两种参数分别返回真/假；这不是 Workflow 分支执行验证。范围边界：此次验证走真实 HTTP 与隔离数据库，未经过浏览器向导，未运行 Worker、分支、WAIT、文件、业务数据或外部服务；导入成功不等同业务执行成功。测试未写共享实例；示例渠道和表是占位，运行前须准备说明中所列资源。原 SIM 文件保持不变。
