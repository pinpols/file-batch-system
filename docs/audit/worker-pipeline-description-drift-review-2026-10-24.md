# Worker / Pipeline 描述漂移核查（2026-10-24）

## 结论与基线

已修正或补注 **32 个内容文件：13 个 Java（12 个生产类、1 个测试类）、17 个 Markdown、2 个 Shell**。另更新本审查、索引、Changelog 和 TODO。重点问题是共享模板漏列 PROCESS、旧定义表来源残留、IMPORT 阶段顺序错误以及扫描器/部署职责误述。

工作树为 `/Users/dengchao/.codex/worktrees/workflow-daily-integration/file-batch-system`，分支 `feature/workflow-daily-integration`，HEAD `ee01e97515b04fd167ed17977535ec5cab8572f8`；范围包含当前暂存、未暂存和未跟踪内容，未使用冻结主分支替代。沿用 `documentation-governance` 和代码审查流程，不丢弃既有改动。

> **后续门禁收口**：用户要求继续修复后，重新分类原5条命中为2处真实键混用、3处扫描器误报。两处代码和扫描器均已修复，14个相关Java的Spotless、摘要键全仓扫描及28项守卫测试通过，另有55项Java定向回归通过。下方初轮失败保留为历史证据，当前状态见[后续修复](#后续摘要键与格式修复2026-10-24)。

## 当前权威口径

| 维度 | 当前实现 |
|---|---|
| 内建 Worker | IMPORT / EXPORT / PROCESS / DISPATCH / ATOMIC，共五类 |
| 文件 Pipeline 阶段模板 | 前四类共享 `AbstractStageExecutor`、`ExecutionContext` 与生命周期适配器；ATOMIC 使用独立任务执行器 |
| 消费骨架与路由 | 五类均使用 `AbstractTaskConsumer`，有五个具体 `WorkerRouteAdapter` |
| IMPORT | RECEIVE → PREPROCESS → PARSE → VALIDATE → LOAD → FEEDBACK（6） |
| EXPORT | PREPARE → GENERATE → STORE → REGISTER → COMPLETE（5） |
| PROCESS | PREPARE → COMPUTE → VALIDATE → COMMIT → FEEDBACK（5） |
| DISPATCH | 目录含 PREPARE / DISPATCH / ACK / RETRY / COMPENSATE / COMPLETE（6）；实际按结果分支推进，不保证每次执行六个阶段 |
| 阶段来源 | `SystemPipelineCatalog` 固定阶段/版本，Worker 提供本地模板，适配器合并 Job 冻结参数；不回查租户 Pipeline 定义表 |
| BUNDLE | BUNDLE_IMPORT / BUNDLE_EXPORT / BUNDLE_DISPATCH 分别映射现有对应 Pipeline，不新增 Worker 类型 |
| 数据源装配 | 平台业务库分片装配的三个使用模块是 import/export/process；不能由此推断 ATOMIC 或租户 SDK 不访问业务数据库 |

依据：[系统目录](../../batch-common/src/main/java/io/github/pinpols/batch/common/enums/SystemPipelineCatalog.java)、[阶段模板](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractStageExecutor.java)、[生命周期适配器](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/AbstractPipelineStepExecutionAdapter.java)、各 Worker 的阶段枚举/执行器、[Compose 实际探针](../../deploy/docker/compose/app.yml)及 ImportIngressScanner。

## 已处理的漂移

| 优先级 / 描述 | 实际修正与入口 |
|---|---|
| P2 / 配置真相仍写旧表 | 修正 agent-baseline、SPI/文件链路设计、模板供给接口与 PROCESS SQL 规格说明；错误消息也指向 COMPUTE 参数，避免引导用户查已退役表 |
| P2 / IMPORT 注释顺序/终点错误 | DefaultImportStageExecutor 从 RECEIVE→PARSE→PREPROCESS…COMPLETE 改为真实六阶段；去掉“平台定义可配置、无配置回退”说明 |
| P2 / 扫描器被写成直接触发执行 | [首次配置手册](../runbook/first-tenant-config-quickstart.md)明确扫描器只发现并登记文件，还需匹配的 Workflow 触发/到达入口 |
| P2 / 健康检查说明错误 | [Docker 手册](../runbook/docker-deployment.md)改为五类 Worker 均有 HTTP 探针，核对实际端口及 actuator 路径 |
| P3 / 共享基类遗漏 PROCESS | AbstractStageExecutor、ExecutionContext、Task SPI 表格统一四类文件 Worker；消费骨架说明统一五类；路由实现数改为五个 |
| P3 / 旧定义来源与保留承诺 | 修正文件执行图/时序、Pipeline与Workflow边界、多租户配置清单、软删除表清单；旧定义表退役与运行历史保留分开说明 |
| P3 / 配置包旧规格仍被称为保留 | 明确仅当前10张数据sheet，旧sheet规格/示例已删、输入显式拒绝、不转换；不把只读说明sheet算作数据sheet |
| P3 / 场景数和 Worker 数混用 | seed验证“五个场景”涉及四类文件Worker；SIM当前五类场景与启停脚本五类Worker说明同步 |
| P3 / PROCESS 局部描述 | 跳过安全集明确只有 COMPUTE/VALIDATE 两个；SQL staging 指明业务库；测试说明限于实际断言的空上下文 |
| P3 / 复制或历史说明误导 | Pipeline身份校验的错误Quartz注释已修正；旧TASK/复合DB定义故事改为当前身份解析职责。早期DDL、分类评估和类行数快照就地标明历史范围及当前替代事实 |

没有机械替换所有“三/四/五”：保留三个业务数据源装配模块、四类文件Pipeline、五类完整Worker、三种部署/任务分类及“三种外部投递渠道”等各自正确口径。历史ADR、日期化验证与Changelog保留当时证据，不宣称已重验过去的生产结论。

## 核查与验证

- 对代码、测试、README、现行 architecture/design/runbook/testing 和 scripts 检索 Worker 数量、共享基类、阶段序列、默认模板登记、DB回退及旧表来源；首轮候选91行，结合继承关系、调用点、目录和配置逐类核对，并扩展到基线/规范。
- 关键词只能辅助定位，不等于穷尽自然语言语义。未对仓库全部业务描述、历史记录或外部部署文档作逐句保证。
- 最终定向 Maven 编译/测试 **78/78通过，0失败/错误/跳过，退出0**：Core13、Import8、Export7、Process45、Dispatch5。包含四类执行器、真实PostgreSQL阶段续跑、PROCESS规格解析与默认步骤；62项为前次退役验证的重复复验，不与原276项累加。
- 五类Worker对齐、文档结构/日期、代码与文档路径引用、Changelog同步、两个Shell的 `bash -n` 均退出0；Shell只做语法验证，没有启动/停止服务。
- `check-pipeline-summary-keys.py` **未通过**，仍有5处既有 `KEY_LITERAL_AND_CONSTANT`：JobPushIntakeService（dryRun）、ResultVersionMapper（dryRun两处）、FileDispatchRepository（pipelineInstanceId）、SqlTransformComputePlugin（taskId）。前三个文件本轮未改；插件除说明文字外代码与任务开始相同。未扩大门禁豁免或更改其业务代码。
- 当前13个Java的 Spotless 检查 **未通过**，首个失败为 AbstractTaskConsumer 中既有布尔表达式换行；该文件除注释外与任务开始相同，保留原布局，没有为注释审查重排业务代码。检查在首个失败模块停止，不把下游未执行模块报告为通过。

精确命令、文件清单、退出码和本地日志入口见 `logs/verification/workflow/worker-pipeline-description-20261024-summary.json`；同目录的 `worker-pipeline-description-20261024-worker.log`、`worker-pipeline-description-20261024-guards.json`、`worker-pipeline-description-20261024-spotless.log` 分别保存测试、守护结果与格式首因。这些日志为本地忽略资产，不作为可随仓库分发的文档链接。首轮格式调用曾误用glob参数，已改为插件支持的正则后得到上述有效失败；不把工具调用错误当作代码缺陷。

运行数据库与服务未变更；未提交、推送、建立PR或执行完整CI/SIM/staging验收。新增发现登记到[当前TODO](../plans/todo-master.md#workerpipeline描述漂移核查2026-10-24)，原数据库专项与正式验收状态不因本次说明修正改变。


## 后续摘要键与格式修复（2026-10-24）

用户要求继续修复初轮的5条命中和Spotless失败。本轮沿用同一开发工作树，补充 `script-sql-governance` 与 `ci-governance`，先复现再修改。**原5条自动命中中，2条为真实混用，3条为误报**；初轮“5处既有摘要键问题”只是门禁结果，不应理解为5个已验证的业务缺陷。

| 原命中 | 核实结果 | 最终处理 |
|---|---|---|
| JobPushIntakeService：dryRun | 扫描器把状态字符串 `"DRY_RUN"` 当成裸键常量；该类没有引用 PipelineRuntimeKeys | 修正检查器词法识别；保留正式推送/预演判定，不给Console添加Worker Core依赖 |
| ResultVersionMapper：两个 dryRun 参数 | 扫描器把 `VersionStatus.DRY_RUN` 枚举声明当成键常量；`@Param("dryRun")` 是真实MyBatis绑定名 | 修正裸引用来源判定；保留两个Mapper参数及SQL绑定，不给Orchestrator添加Worker Core依赖 |
| FileDispatchRepository：pipelineInstanceId | 真实混用 | 插入参数使用已有 `KEY_PIPELINE_INSTANCE_ID`；对应值仍为 `pipelineInstanceId` |
| SqlTransformComputePlugin：taskId | 真实混用 | 暂存generation参数使用 `PipelineRuntimeKeys.TASK_ID`；对应值仍为 `taskId` |
| AbstractTaskConsumer及相关修改文件格式 | Palantir布局不一致 | 使用仓库Spotless在原13文件及新增FileDispatchRepository共14文件范围执行apply/check；只改变布局/导入排序，保留逻辑 |

R2改为等长屏蔽Java字符串、字符与文本块，再识别代码中的限定常量；裸常量仅在已知键类的显式/通配静态导入或键类自身作用域中成立，导入声明本身不算读写。保留真实限定引用、静态导入混用和完整源码扫描，没有增加白名单、降低规则级别或修改CI执行条件。适用范围和回归入口同步 `scripts/ci/README.md`。

新增9个正反例覆盖同名枚举、状态文本、字符串/文本块中的限定名、正确/错误类型静态导入、通配导入、未使用导入、自身键类及引用行号。修复前28项中8项失败，其中7项为新增误报/行号复现，1项为已有真实仓库契约失败；保留首轮日志。修复后28/28通过，原有真实混用正例继续拦截。既有R1回灌及R3前端计数契约保持检查。

本轮实际验证：

- `python3 -m unittest scripts/ci/tests/test_check_pipeline_summary_keys.py`：**28/28通过**。
- `python3 scripts/ci/check-pipeline-summary-keys.py`：**退出0**，内置自测及配对前端7个计数键校验通过，真实混用已清除。
- `./mvnw -ntp -pl batch-worker/import,batch-worker/export,batch-worker/process,batch-worker/dispatch -am -DspotlessFiles=... spotless:apply spotless:check`：**14个相关Java文件通过，退出0**；精确正则见本地汇总JSON，不代表全仓所有Java已格式化。
- `./mvnw -ntp -pl batch-worker/process,batch-worker/dispatch -am -Dtest=AbstractTaskConsumerTest,AbstractTaskConsumerBackpressureTest,TaskConsumerRoutingPolicyNamespaceTest,SqlTransformComputePluginIntegrationTest,FileDispatchMapperTenantReferenceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`：**55/55通过，0失败/错误/跳过，Maven退出0**（Core35、Process15、Dispatch5），覆盖消费路由/背压、真实PostgreSQL的PROCESS暂存generation归属与SQL链路、DISPATCH持久化/跨租户拒绝及SFTP投递。
- `python3 scripts/ci/check-script-governance.py`、`check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py`、`check-changelog-sync.py`：**全部退出0**；本轮文件的 `git diff --check` 通过。Java差异核对只包含两处预期常量替换、格式布局与导入排序，键值未变。

命令、退出码和日志入口见本地 `logs/verification/workflow/worker-summary-keys-fix-20261024-summary.json`；同目录保存 `worker-summary-keys-fix-20261024-before.log`、`worker-summary-keys-fix-20261024-tests-red.log`、`worker-summary-keys-fix-20261024-guards.json`、`worker-summary-keys-fix-20261024-spotless.log`、`worker-summary-keys-fix-20261024-java-tests.log`。原初轮日志不覆盖。

WORKER-SUMMARY-KEYS、WORKER-COMMENT-FORMAT两项已闭环。这里只报告上述本地范围；未运行完整CI/SIM/staging，也未变更运行库、提交或发布。
