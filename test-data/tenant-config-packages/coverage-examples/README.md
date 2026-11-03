# 三租户 Job 与 Workflow 覆盖示例

本目录是独立的配置展示样本，不替换上级目录供 SIM 使用的三个权威测试包。

| 文件 | 指定租户 | Job | Workflow | 分支汇聚 |
|---|---|---|---|---|
| `ta-coverage-example.xlsx` | `ta` | 8 个支持执行的类型各 1，另含禁用的 GENERAL 占位 | 串行、分支、WAIT | ALL |
| `tb-coverage-example.xlsx` | `tb` | 同上 | 串行、分支、WAIT | ANY |
| `tc-coverage-example.xlsx` | `tc` | 同上 | 串行、分支、WAIT | N_OF=2 |

8 个类型为 IMPORT、EXPORT、PROCESS、DISPATCH、ATOMIC、BUNDLE_IMPORT、BUNDLE_EXPORT、BUNDLE_DISPATCH。GENERAL 虽在 JobType 字典中，当前没有平台通用 Worker handler，样本仅留禁用占位，不能按可执行类型计数。历史 WORKFLOW 包装 Job、FILE_STEP、APPROVAL 和历史 TASK 节点不是当前新建配置类型。

每包采用 11 个当前数据 sheet 和 Console 模板的辅助说明 sheet，不含已退役的 `pipeline_definition` 和 `pipeline_step_definition`。每包包含 START、END、JOB、GATEWAY、WAIT 五类新建节点及 SUCCESS、FAILURE、CONDITION、ALWAYS 四类边；三包合计覆盖 ALL、ANY、N_OF 三种汇聚。`WF_SERIAL` 展示导入到导出，`WF_BRANCH_*` 展示条件/失败分支及汇聚，`WF_WAIT` 展示文件到达等待，并带作业依赖。导出 Job 依赖导入 Job；具体含义以当前调度契约为准。CONDITION 表达式为 `proceed == true`；触发参数需要显式提供该字段。WAIT 使用 `sensor_type=FILE_ARRIVAL` 与 `sensor_spec`，当前桌面设计器不支持编辑 WAIT 节点。TB 覆盖日历的 `catch_up_policy=AUTO`，用于真实 CATCH_UP 验收；TA/TC 保持 `NONE`。

## 使用及范围

1. 先通过平台管理创建或选择目标业务租户 `ta`、`tb`、`tc`；每份文件的 `tenant_id` 固定为对应目标，不能直接导入不同租户。选择当前租户后，在全局搜索中打开“配置导入”。平台管理员或租户管理员按后端权限上传、预览、确认依赖和风险后再应用；租户管理员的资源队列等平台配置权限以实际预览和授权校验结果为准。
2. 所有 Job 和样本中的组合 Workflow 使用 MANUAL，组合 Workflow 的 `schedule_enabled` 为 false。导入时平台还会自动创建托管单作业 Workflow；隔离验证中其调度开关实际为 true，但类型为 MANUAL，不符合定时调度器注册条件。创建配置不会自动发起示例运行。仍应先核对现有同编码配置与预览新增/更新/跳过项，导入会持久写入，可能影响同编码已有配置。示例编码带租户 `COV` 前缀，不能代替影响核对。
3. 这些是配置覆盖示例，不附带真实业务文件、业务库表、凭据、Worker 或外部系统。官方示例的 SFTP 端点 `sftp.example.com`、用户名、表名、SQL、模板和业务字段是占位，不含真实凭据；执行前必须按环境完成配置、权限、RLS、网络白名单与数据准备。三种 BUNDLE 类型需在触发时提供对应 manifest，不能把空参数当作真实文件束。请勿把仅能预览或导入当作完成 Worker 或业务链路验证。
4. GENERAL 占位默认禁用；WAIT 在实际运行时须有匹配的文件到达以及相应 Worker/传感器环境。条件和失败分支需要分别构造 true/false 或成功/失败输入，不能仅凭图上存在边就视为路径已运行。

## 生成

从与目标版本配套的 Console 下载“全部场景”示例模板，然后：

```bash
python3 test-data/tenant-config-packages/coverage-examples/generate.py \
  --sample-template /path/to/console-all-scenarios.xlsx \
  --output-dir /path/to/new-empty-directory
```

脚本拒绝覆盖已有同名文件，并检查 11 个数据 sheet 及退役 sheet。生成后需复核当前后端 schema，执行覆盖测试与隔离环境的上传、预览、应用；不能仅根据工作簿可打开判断可导入。本次基底来自本地 Console 的 `/api/console/config/tenant-package/excel/sample-template?scenarios=ALL`；是否已完成本版隔离验收以 [任务记录](../../../docs/plans/tenant-coverage-packages-2026-11-03.md) 为准。

静态覆盖检查：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s test-data/tenant-config-packages/coverage-examples -p 'test_coverage.py' -v
```
